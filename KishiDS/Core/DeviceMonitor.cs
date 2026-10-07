using Microsoft.Win32.SafeHandles;

namespace KishiDS.Core;

public enum DeviceState { Disconnected, Bootloader, StockFirmware, CustomFirmware }

/// <summary>A decoded DualShock-4 style input report from the custom firmware.</summary>
public readonly record struct LiveInput(byte Lx, byte Ly, byte Rx, byte Ry, byte L2, byte R2, int Hat, byte Face, byte Buttons, byte Misc)
{
    public static LiveInput Neutral => new(128, 128, 128, 128, 0, 0, 8, 0, 0, 0);
}

/// <summary>
/// Firmware telemetry (feature report 0xAB): raw buttons/ADC and which config the firmware is running.
/// The last five fields come from firmware with live editing; older firmware leaves them at their defaults.
/// </summary>
public readonly record struct Telemetry(bool Configured, bool ConfigFromImage, ushort ButtonMask, ushort[] Adc, uint ConfigCrc,
    bool Live = false, bool FromSaved = false, bool Unsaved = false, bool IdentityDiffers = false, uint PersistedCrc = 0);

/// <summary>
/// Watches for the Kishi (bootloader, stock firmware, or this app's firmware) and, when the custom firmware is
/// present, streams its input reports and telemetry.  Monitoring is read-only; the only writes are the explicit
/// live-editing calls (<see cref="PushConfig"/>, <see cref="Save"/>, <see cref="EraseSaved"/>, <see cref="Reboot"/>).
/// </summary>
public sealed class DeviceMonitor : IDisposable
{
    public const ushort StockVid = 0x27F8, StockPid = 0x0BBF, BootPid = 0x0BC0;

    private readonly CancellationTokenSource _cts = new();
    private readonly object _lock = new();
    private readonly List<(ushort Vid, ushort Pid)> _candidates = new() { (0x054C, 0x05C4) };
    private SafeFileHandle? _handle;
    // A second handle on the same device for feature reports (telemetry, live editing).  Windows serialises I/O on a synchronous
    // handle, so sharing the reader's handle made every feature read wait behind the blocking input-report read (~17 ms each).
    private SafeFileHandle? _control;

    public DeviceState State { get; private set; } = DeviceState.Disconnected;
    public LiveInput Input { get; private set; } = LiveInput.Neutral;
    public Telemetry? Latest { get; private set; }

    // Perf counters (developer aid, see App.Perf): input reports received, telemetry reads and their total round-trip time.
    public static long PerfReports, PerfTeleReads;
    public static double PerfTeleMs;

    /// <summary>The connected controller's USB serial number (on the sticker on its back), once it is known.</summary>
    public string? Serial { get; private set; }

    public event Action<DeviceState>? StateChanged;
    public event Action? Updated;

    /// <summary>Tell the monitor which VID/PID the custom firmware might currently be using.</summary>
    public void AddCandidate(ushort vid, ushort pid)
    {
        lock (_lock) if (!_candidates.Contains((vid, pid))) _candidates.Add((vid, pid));
    }

    public void Start()
    {
        Native.timeBeginPeriod(1);   // so the short sleeps below really are ~1 ms instead of ~15 ms
        _timerRaised = true;
        Task.Run(() => Loop(_cts.Token));
    }

    private bool _timerRaised;

    public void Dispose()
    {
        _cts.Cancel();
        if (_timerRaised) { Native.timeEndPeriod(1); _timerRaised = false; }
        CloseHandle();
    }

    private void SetState(DeviceState s)
    {
        if (s == State) return;
        State = s;
        if (s != DeviceState.CustomFirmware) { Input = LiveInput.Neutral; Latest = null; }
        if (s is DeviceState.Disconnected or DeviceState.Bootloader) Serial = null;   // the bootloader's serial is not the sticker's
        StateChanged?.Invoke(s);
    }

    private void CloseHandle()
    {
        var c = _control;
        _control = null;
        if (c is { IsInvalid: false, IsClosed: false } && !ReferenceEquals(c, _handle)) c.Dispose();
        var h = _handle;
        _handle = null;
        if (h is { IsInvalid: false, IsClosed: false })
        {
            Native.CancelIoEx(h, IntPtr.Zero);
            h.Dispose();
        }
    }

    private async Task Loop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (_handle is not null) { await Task.Delay(400, ct); continue; }   // the reader owns the connected state

                var ids = Native.PresentUsbDeviceIds();
                bool boot = ids.Any(i => i.Contains($"VID_{StockVid:X4}&PID_{BootPid:X4}", StringComparison.OrdinalIgnoreCase));
                if (boot) { SetState(DeviceState.Bootloader); await Task.Delay(500, ct); continue; }

                var hid = TryOpenCustom(out var devPath);
                if (hid is not null)
                {
                    _control = OpenControl(devPath) ?? hid;
                    _handle = hid;
                    Serial = Native.GetSerial(hid);
                    SetState(DeviceState.CustomFirmware);
                    _ = Task.Run(() => ReadLoop(hid, ct));
                    var ctl = _control; _ = Task.Run(() => TelemetryLoop(ctl, ct));
                    continue;
                }

                bool stock = ids.Any(i => i.Contains($"VID_{StockVid:X4}&PID_{StockPid:X4}", StringComparison.OrdinalIgnoreCase));
                Serial = stock ? ids.Select(i => SerialFromInstanceId(i, StockVid, StockPid)).FirstOrDefault(s => s is not null) : null;
                SetState(stock ? DeviceState.StockFirmware : DeviceState.Disconnected);
                await Task.Delay(600, ct);
            }
            catch (OperationCanceledException) { return; }
            catch { try { await Task.Delay(800, ct); } catch (OperationCanceledException) { return; } }
        }
    }

    /// <summary>
    /// The USB serial number Windows put at the end of a device instance ID ("USB\VID_27F8&amp;PID_0BBF\SAMPLE000000001"), or
    /// null for another device or when the device has no serial (Windows then invents an ID containing '&amp;').
    /// </summary>
    public static string? SerialFromInstanceId(string instanceId, ushort vid, ushort pid)
    {
        var parts = instanceId.Split('\\');
        if (parts.Length != 3 || !parts[1].Equals($"VID_{vid:X4}&PID_{pid:X4}", StringComparison.OrdinalIgnoreCase)) return null;
        return parts[2].Length == 0 || parts[2].Contains('&') ? null : parts[2];
    }

    private static SafeFileHandle? OpenControl(string? path)
    {
        if (path is null) return null;
        var h = Native.CreateFile(path, Native.GENERIC_READ | Native.GENERIC_WRITE, Native.FILE_SHARE_READ | Native.FILE_SHARE_WRITE, IntPtr.Zero, Native.OPEN_EXISTING, 0, IntPtr.Zero);
        if (h.IsInvalid) h = Native.CreateFile(path, Native.GENERIC_READ, Native.FILE_SHARE_READ | Native.FILE_SHARE_WRITE, IntPtr.Zero, Native.OPEN_EXISTING, 0, IntPtr.Zero);
        return h.IsInvalid ? null : h;
    }

    private SafeFileHandle? TryOpenCustom(out string? devicePath)
    {
        devicePath = null;
        (ushort, ushort)[] cands;
        lock (_lock) cands = _candidates.ToArray();
        foreach (var path in Native.EnumerateHidPaths())
        {
            // Cheap pre-filter on the device path ("...vid_054c&pid_05c4...") before opening anything.
            string p = path.ToLowerInvariant();
            if (!cands.Any(c => p.Contains($"vid_{c.Item1:x4}&pid_{c.Item2:x4}"))) continue;
            var h = Native.CreateFile(path, Native.GENERIC_READ | Native.GENERIC_WRITE, Native.FILE_SHARE_READ | Native.FILE_SHARE_WRITE, IntPtr.Zero, Native.OPEN_EXISTING, 0, IntPtr.Zero);
            if (h.IsInvalid) h = Native.CreateFile(path, Native.GENERIC_READ, Native.FILE_SHARE_READ | Native.FILE_SHARE_WRITE, IntPtr.Zero, Native.OPEN_EXISTING, 0, IntPtr.Zero);
            if (h.IsInvalid) continue;
            // Our firmware answers feature report 0xAB with a version-1 telemetry block; nothing else does.
            if (ReadTelemetry(h) is not null) { devicePath = path; return h; }
            h.Dispose();
        }
        return null;
    }

    private static Telemetry? ReadTelemetry(SafeFileHandle h)
    {
        var buf = new byte[64];
        buf[0] = 0xAB;
        if (!Native.HidD_GetFeature(h, buf, buf.Length)) return null;
        return ParseTelemetry(buf);
    }

    public static Telemetry? ParseTelemetry(byte[] buf)
    {
        if (buf.Length < 26 || buf[0] != 0xAB || buf[1] != 1) return null;
        var adc = new ushort[6];
        for (int i = 0; i < 6; i++) adc[i] = (ushort)(buf[5 + 2 * i] | (buf[6 + 2 * i] << 8));
        uint crc = BitConverter.ToUInt32(buf, 17);
        bool live = (buf[2] & 0x04) != 0 && buf[25] == LiveProtocol.Protocol;   // flag + protocol byte: older firmware has neither
        return new Telemetry((buf[2] & 1) != 0, (buf[2] & 2) != 0, (ushort)(buf[3] | (buf[4] << 8)), adc, crc,
            live, live && (buf[2] & 0x08) != 0, live && (buf[2] & 0x10) != 0, live && (buf[2] & 0x20) != 0, live ? BitConverter.ToUInt32(buf, 21) : 0);
    }

    // ---------------------------------------------------------------- live editing (blocking; call from a worker thread)

    private readonly object _io = new();

    private static LiveProtocol.Status? ReadLiveStatus(SafeFileHandle h)
    {
        var buf = new byte[LiveProtocol.ReportSize];
        buf[0] = LiveProtocol.ReportId;
        return Native.HidD_GetFeature(h, buf, buf.Length) ? LiveProtocol.ParseStatus(buf) : null;
    }

    private static bool Send(SafeFileHandle h, byte[] packet) => Native.HidD_SetFeature(h, packet, packet.Length);

    private SafeFileHandle? CurrentHandle()
    {
        var h = _control ?? _handle;
        return h is { IsInvalid: false, IsClosed: false } ? h : null;
    }

    /// <summary>Make <paramref name="block"/> the controller's active configuration (not saved to flash).</summary>
    public LiveResult PushConfig(byte[] block)
    {
        lock (_io)
        {
            var h = CurrentHandle();
            if (h is null) return LiveResult.Fail("controller not connected");
            foreach (var p in LiveProtocol.PushPackets(block))
                if (!Send(h, p)) return LiveResult.Fail("USB write failed");
            if (ReadLiveStatus(h) is not { } st) return LiveResult.Fail("no reply from the controller");
            if (st.Result != LiveProtocol.Result.Ok) return LiveResult.Fail(LiveProtocol.Describe(st.Result));
            uint want = BitConverter.ToUInt32(block, 12);
            return st.ActiveCrc == want ? LiveResult.Success : LiveResult.Fail("the controller adjusted some values");
        }
    }

    /// <summary>Write the active configuration to the controller's flash so it survives unplugging.</summary>
    public LiveResult Save() => Persistent(LiveProtocol.Cmd.Save);

    /// <summary>Forget the saved configuration; the controller falls back to what was flashed.</summary>
    public LiveResult EraseSaved() => Persistent(LiveProtocol.Cmd.Erase);

    private LiveResult Persistent(LiveProtocol.Cmd cmd)
    {
        lock (_io)
        {
            var h = CurrentHandle();
            if (h is null) return LiveResult.Fail("controller not connected");
            if (!Send(h, LiveProtocol.Packet(cmd))) return LiveResult.Fail("USB write failed");
            for (int i = 0; i < 25; i++)   // the firmware does the flash work right after replying
            {
                if (ReadLiveStatus(h) is { } st && !st.Flags.HasFlag(LiveProtocol.StatusFlags.Pending))
                    return st.Result == LiveProtocol.Result.Ok ? LiveResult.Success : LiveResult.Fail(LiveProtocol.Describe(st.Result));
                Thread.Sleep(20);
            }
            return LiveResult.Fail("the controller did not finish in time");
        }
    }

    /// <summary>Reset the controller.  It re-enumerates, which is how a new USB name/ID takes effect.</summary>
    public LiveResult Reboot()
    {
        lock (_io)
        {
            var h = CurrentHandle();
            if (h is null) return LiveResult.Fail("controller not connected");
            _ = Send(h, LiveProtocol.Packet(LiveProtocol.Cmd.Reboot));   // the controller resets before the transfer completes, so the call usually reports failure
            return LiveResult.Success;
        }
    }

    /// <summary>Read the configuration the controller is running right now.</summary>
    public (byte[]? Block, string Error) ReadConfig()
    {
        lock (_io)
        {
            var h = CurrentHandle();
            if (h is null) return (null, "controller not connected");
            var block = new byte[ConfigLayout.Size];
            for (int i = 0; i < LiveProtocol.Chunks; i++)
            {
                if (!Send(h, LiveProtocol.Packet(LiveProtocol.Cmd.ReadSelect, i))) return (null, "USB write failed");
                if (ReadLiveStatus(h) is not { } st || st.ReadIndex != i) return (null, "no reply from the controller");
                st.Chunk.CopyTo(block, i * LiveProtocol.ChunkSize);
            }
            return new ConfigBlock(block).IsValid() ? (block, "") : (null, "the controller returned an invalid block");
        }
    }

    private void ReadLoop(SafeFileHandle h, CancellationToken ct)
    {
        var buf = new byte[64];
        try
        {
            while (!ct.IsCancellationRequested && !h.IsClosed)
            {
                if (!Native.ReadFile(h, buf, buf.Length, out int n, IntPtr.Zero) || n < 10) break;
                if (buf[0] != 0x01) continue;
                PerfReports++;
                Input = new LiveInput(buf[1], buf[2], buf[3], buf[4], buf[8], buf[9], buf[5] & 0x0F, (byte)(buf[5] & 0xF0), buf[6], buf[7]);
                Updated?.Invoke();
            }
        }
        catch { }
        finally
        {
            if (ReferenceEquals(_handle, h)) { CloseHandle(); SetState(DeviceState.Disconnected); }
        }
    }

    private void TelemetryLoop(SafeFileHandle h, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested && !h.IsClosed)
            {
                long t0 = System.Diagnostics.Stopwatch.GetTimestamp();
                var t = ReadTelemetry(h);
                PerfTeleMs += (System.Diagnostics.Stopwatch.GetTimestamp() - t0) * 1000.0 / System.Diagnostics.Stopwatch.Frequency; PerfTeleReads++;
                if (t is null) break;
                Latest = t;
                Updated?.Invoke();
                Thread.Sleep(4);
            }
        }
        catch { }
    }
}
