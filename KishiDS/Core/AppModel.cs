using System.Text.Json;
using System.Windows.Input;
using System.Windows.Threading;

namespace KishiDS.Core;

public enum FlashPhase { Idle, WaitingForBootloader, Ready, Flashing, Verifying, Done, Failed }

/// <summary>Application state shared by every page: settings, live device data, and the apply/restore workflow.</summary>
public sealed class AppModel : ObservableObject, IDisposable
{
    private static readonly string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KishiDS");
    private static string CurrentPath => Path.Combine(DataDir, "current.json");
    private static string AppliedPath => Path.Combine(DataDir, "applied.txt");

    private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromMilliseconds(8) };
    private readonly DispatcherTimer _save = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private readonly DispatcherTimer _debounce = new() { Interval = TimeSpan.FromMilliseconds(70) };    // settle a slider drag, then push
    private readonly DispatcherTimer _persist = new() { Interval = TimeSpan.FromMilliseconds(1500) };   // quiet period before writing flash
    private readonly DateTime _t0 = DateTime.UtcNow;
    private CancellationTokenSource? _flowCts;
    private byte[] _baseImage;

    public AppModel(bool demo = false)
    {
        Demo = demo;
        _baseImage = FirmwareImage.LoadEmbedded();
        Config = new ConfigBlock();
        LoadCurrent();
        S = new Settings(Config);
        Calibration = new CalibrationWizard(Config);
        Monitor = new DeviceMonitor();   // before Config.Changed can fire
        Config.Changed += _ =>
        {
            _save.Stop(); _save.Start();
            Monitor.AddCandidate((ushort)Config.Get("vid"), (ushort)Config.Get("pid"));
            if (IsLive && _linked) { _debounce.Stop(); _debounce.Start(); _persist.Stop(); }
            RefreshStatus();
        };
        _save.Tick += (_, _) => { _save.Stop(); SaveCurrent(); };
        _debounce.Tick += (_, _) => { _debounce.Stop(); _ = PushAsync(); };
        _persist.Tick += (_, _) => { _persist.Stop(); _ = SaveToControllerAsync(); };

        Monitor.AddCandidate((ushort)Config.Get("vid"), (ushort)Config.Get("pid"));
        if (!demo) Monitor.Start();

        ApplyCommand = new RelayCommand(() => _ = BeginApplyAsync(), () => Phase is FlashPhase.Idle or FlashPhase.Done or FlashPhase.Failed);
        RestoreCommand = new RelayCommand(() => _ = BeginRestoreAsync(), () => Phase is FlashPhase.Idle or FlashPhase.Done or FlashPhase.Failed);
        FlashNowCommand = new RelayCommand(() => _flashNow?.TrySetResult(), () => Phase == FlashPhase.Ready);
        CancelFlowCommand = new RelayCommand(CancelFlow, () => Phase is FlashPhase.WaitingForBootloader or FlashPhase.Ready);
        ResetAllCommand = new RelayCommand(() => Config.ResetAll());
        RestartControllerCommand = new RelayCommand(() => _ = RestartControllerAsync());

        _tick.Tick += (_, _) => Poll();
        _tick.Start();
    }

    public bool Demo { get; }
    public static bool DemoStress { get; set; }
    public ConfigBlock Config { get; }
    public Settings S { get; }
    public CalibrationWizard Calibration { get; }
    public DeviceMonitor Monitor { get; }

    public ICommand ApplyCommand { get; }
    public ICommand RestoreCommand { get; }
    public ICommand FlashNowCommand { get; }
    public ICommand CancelFlowCommand { get; }
    public ICommand ResetAllCommand { get; }
    public ICommand RestartControllerCommand { get; }

    /// <summary>Raised when the workflow wants the Firmware page in front.</summary>
    public event Action? ShowFirmwarePage;

    // ---------------------------------------------------------------- live device data

    private DeviceState _device;
    private LiveInput _live = LiveInput.Neutral;
    private Telemetry? _tele;

    public DeviceState Device { get => _device; private set { if (Set(ref _device, value)) { Raise(nameof(DeviceTitle)); Raise(nameof(DeviceDetail)); Raise(nameof(IsConnected)); Raise(nameof(Dirty)); Raise(nameof(InSync)); Raise(nameof(SyncText)); Raise(nameof(NeedsOriginal)); } } }
    public LiveInput Live { get => _live; private set => Set(ref _live, value); }
    public Telemetry? Tele { get => _tele; private set { _tele = value; Raise(); Raise(nameof(InSync)); Raise(nameof(Dirty)); Raise(nameof(SyncText)); } }

    public bool IsConnected => Device != DeviceState.Disconnected;

    private string? _serial;
    /// <summary>The controller's own serial number as the PC sees it; null until a KishiDS controller is connected.</summary>
    public string? Serial { get => _serial; private set { if (Set(ref _serial, value)) Raise(nameof(SerialText)); } }
    public string SerialText => Serial ?? "Shown when your Kishi is connected";

    /// <summary>The last model seen (kept while unplugged, so the pages keep showing it); V1 until a controller says otherwise.</summary>
    public KishiModel Model { get; private set; } = KishiModel.V1;
    public bool IsV2Pro => Model == KishiModel.V2Pro;
    public string ModelName => IsV2Pro ? "Kishi V2 Pro" : "Kishi V1";

    private void SetModel(KishiModel m)
    {
        if (m == Model && S.IsV2Pro == IsV2Pro) return;
        Model = m;
        S.SetBoard(IsV2Pro);
        Raise(nameof(Model)); Raise(nameof(IsV2Pro)); Raise(nameof(ModelName)); Raise(nameof(ShowOriginalWarning)); Raise(nameof(ShowApplyButton)); Raise(nameof(DeviceTitle)); Raise(nameof(DeviceDetail));
    }

    public string DeviceTitle => Device switch
    {
        DeviceState.CustomFirmware => $"{ModelName} connected",
        DeviceState.StockFirmware => $"{ModelName} (stock firmware)",
        DeviceState.Bootloader => IsV2Pro ? "V2 Pro bootloader" : "Bootloader ready",
        _ => "No controller",
    };

    public string DeviceDetail => Device switch
    {
        DeviceState.CustomFirmware when IsLive => "Live editing is on. Changes apply instantly.",
        DeviceState.CustomFirmware when Tele is not null => "Older KishiDS firmware. Apply once to switch on live editing.",
        DeviceState.CustomFirmware => "Running KishiDS firmware",
        DeviceState.StockFirmware when IsV2Pro => "Original Razer firmware. The V2 Pro is switched with tools/v2pro_dfu.py (not from this app yet).",
        DeviceState.StockFirmware => "Original Razer firmware. Apply to switch to KishiDS firmware.",
        DeviceState.Bootloader when IsV2Pro => "Flash it with tools/v2pro_dfu.py; this app flashes the V1 only.",
        DeviceState.Bootloader => "In firmware-update mode, ready to flash",
        _ => "Plug in your Kishi V1 or V2 Pro to get started",
    };

    /// <summary>True when the controller is running exactly the settings shown here.</summary>
    public bool InSync => Device == DeviceState.CustomFirmware && Tele is { } t && t.ConfigCrc == Config.Crc;

    /// <summary>Settings differ from what the controller is (or last was) running.</summary>
    public bool Dirty => Device == DeviceState.CustomFirmware && Tele is { } t ? t.ConfigCrc != Config.Crc : Config.Crc != AppliedCrc;

    public string SyncText => Device switch
    {
        DeviceState.CustomFirmware when Tele is null => "Reading controller…",
        DeviceState.CustomFirmware when IsLive && !_linked => _liveError is { } rerr ? "Couldn't read controller: " + rerr : "Reading controller settings…",
        DeviceState.CustomFirmware when IsLive && !InSync => _liveError is { } err ? "Couldn't sync: " + err : "Applying…",
        DeviceState.CustomFirmware when IsLive => Tele!.Value.Unsaved ? "Live · saving…" : "Live · saved on controller",
        DeviceState.CustomFirmware => InSync ? "Controller is up to date" : "Changes not applied yet",
        DeviceState.Disconnected => "Settings saved on this PC",
        _ => Dirty ? "Changes not applied yet" : "Settings saved",
    };

    public uint AppliedCrc { get; private set; }

    // ---------------------------------------------------------------- live editing

    /// <summary>The controller runs firmware with live editing (the app can change its settings over USB).</summary>
    public bool IsLive => Device == DeviceState.CustomFirmware && Tele is { Live: true };

    /// <summary>The "Apply to controller" (update mode) button is only needed without live editing.</summary>
    public bool ShowApplyButton => !IsLive && !IsV2Pro;   // the V2 Pro is flashed with tools/v2pro_dfu.py

    private bool _linked;          // app settings and controller are tied together: edits are sent as they happen
    private bool _pushing, _pushAgain;
    private int _failures;
    private string? _liveError;

    public enum BarKind { None, Apply, Restart }

    /// <summary>What the floating bar at the bottom should offer.</summary>
    public BarKind Bar
    {
        get
        {
            // With live editing there is nothing to confirm: only a USB-identity change (name, rate) needs a restart.
            if (IsLive) return _linked && Tele!.Value.IdentityDiffers ? BarKind.Restart : BarKind.None;
            // Apply (via update mode) is only for the older firmware without live editing.
            return Device == DeviceState.CustomFirmware && Tele is not null && Dirty ? BarKind.Apply : BarKind.None;
        }
    }

    public string BarMessage => Bar switch
    {
        BarKind.Restart => "The new device name or USB rate takes effect after restarting the controller",
        _ => "You have changes that aren't on the controller yet",
    };

    public string BarPrimaryText => Bar switch { BarKind.Restart => "Restart controller", _ => "Apply to controller" };
    public ICommand BarPrimaryCommand => Bar switch { BarKind.Restart => RestartControllerCommand, _ => ApplyCommand };
    public string? BarSecondaryText => Bar == BarKind.Apply ? "Reset all" : null;
    public ICommand? BarSecondaryCommand => Bar == BarKind.Apply ? ResetAllCommand : null;

    private (BarKind, bool, bool, string) _lastStatus;

    /// <summary>Re-raise everything derived from device/telemetry/config state, only when something visible changed.</summary>
    private void RefreshStatus()
    {
        var now = (Bar, IsLive, InSync, SyncText);
        if (now == _lastStatus) return;
        _lastStatus = now;
        Raise(nameof(Bar)); Raise(nameof(BarMessage)); Raise(nameof(BarPrimaryText)); Raise(nameof(BarPrimaryCommand));
        Raise(nameof(BarSecondaryText)); Raise(nameof(BarSecondaryCommand)); Raise(nameof(HasBarSecondary));
        Raise(nameof(IsLive)); Raise(nameof(ShowApplyButton)); Raise(nameof(InSync)); Raise(nameof(Dirty)); Raise(nameof(SyncText)); Raise(nameof(DeviceDetail));
    }

    public bool HasBarSecondary => BarSecondaryText is not null;

    /// <summary>Called every tick: decide whether the app and controller are linked, and re-send if they drifted apart.</summary>
    private void UpdateLiveLink()
    {
        if (!IsLive) { _linked = false; _failures = 0; _liveError = null; return; }
        var t = Tele!.Value;
        if (!_linked)
        {
            if (t.ConfigCrc == Config.Crc) _linked = true;   // already identical
            else if (!_adopting && _failures < 3) _ = AdoptControllerSettingsAsync();   // the controller is the source of truth
            return;
        }
        if (t.ConfigCrc != Config.Crc && !_pushing && !_debounce.IsEnabled && _failures < 3) _debounce.Start();
        else if (t.ConfigCrc == Config.Crc) { _failures = 0; _liveError = null; }
        if (t.ConfigCrc == Config.Crc && t.Unsaved && !_persist.IsEnabled && !_pushing && !_debounce.IsEnabled && !_saving) _persist.Start();
    }

    private bool _saving;

    private async Task PushAsync()
    {
        if (_pushing) { _pushAgain = true; return; }
        _pushing = true;
        try
        {
            do
            {
                _pushAgain = false;
                var block = Config.ToBytes();
                if (Tele is { } t && t.ConfigCrc == Config.Crc) break;   // already running exactly this
                var res = await Task.Run(() => Monitor.PushConfig(block));
                if (res.Ok) { _failures = 0; _liveError = null; _persist.Stop(); _persist.Start(); }
                else { _failures++; _liveError = res.Message; }
            } while (_pushAgain);
        }
        finally { _pushing = false; }
        RefreshStatus();
    }

    private async Task SaveToControllerAsync()
    {
        if (_saving || !IsLive) return;
        _saving = true;
        try
        {
            var res = await Task.Run(Monitor.Save);
            if (!res.Ok) _liveError = res.Message;
        }
        finally { _saving = false; }
        RefreshStatus();
    }

    private bool _adopting;

    /// <summary>On first contact, load whatever the controller is running into the app (an old autosave must not overwrite it).</summary>
    private async Task AdoptControllerSettingsAsync()
    {
        _adopting = true;
        try
        {
            var (block, err) = await Task.Run(Monitor.ReadConfig);
            if (block is null) { _failures++; _liveError = err; }
            else
            {
                _failures = 0; _liveError = null;
                _linked = true;                   // set first: the load below is not an edit to push back
                Config.LoadBytes(block);
            }
        }
        finally { _adopting = false; }
        RefreshStatus();
    }

    /// <summary>Make sure the controller has the current settings saved, then reset it so USB identity changes take effect.</summary>
    private async Task RestartControllerAsync()
    {
        if (!IsLive) return;
        Monitor.AddCandidate((ushort)Config.Get("vid"), (ushort)Config.Get("pid"));
        _debounce.Stop(); _persist.Stop();
        var block = Config.ToBytes();
        var res = await Task.Run(() =>
        {
            var r = Monitor.PushConfig(block);
            return r.Ok ? Monitor.Save() : r;
        });
        if (!res.Ok) { _liveError = res.Message; RefreshStatus(); return; }
        _ = await Task.Run(Monitor.Reboot);
    }

    private void Poll()
    {
        if (Demo) { PollDemo(); return; }
        Device = Monitor.State;
        Serial = Monitor.Serial;
        var li = Monitor.Input;
        if (!li.Equals(Live)) Live = li;
        var t = Monitor.Latest;
        if (!SameTelemetry(t, Tele)) Tele = t;
        else if (t is not null) _tele = t;   // only the analog readings moved: keep them current without re-evaluating every binding
        if (Monitor.Model is { } m) SetModel(m);
        UpdateLiveLink();
        RefreshStatus();
        UpdatePressed();
        Calibration.Update(Tele);
    }

    private static bool SameTelemetry(Telemetry? a, Telemetry? b) =>
        a is null ? b is null : b is not null && a.Value.ButtonMask == b.Value.ButtonMask && a.Value.ConfigCrc == b.Value.ConfigCrc &&
                                a.Value.Configured == b.Value.Configured &&
                                a.Value.Live == b.Value.Live && a.Value.Unsaved == b.Value.Unsaved && a.Value.IdentityDiffers == b.Value.IdentityDiffers &&
                                a.Value.FromSaved == b.Value.FromSaved && a.Value.V2Pro == b.Value.V2Pro;

    private void UpdatePressed()
    {
        int mask = Tele?.ButtonMask ?? 0;
        foreach (var b in S.Buttons) b.Pressed = (mask & (1 << b.Index)) != 0;
    }

    private void PollDemo()
    {
        double t = (DateTime.UtcNow - _t0).TotalSeconds;
        Device = DeviceState.CustomFirmware;
        Serial = "SAMPLE000000001";
        byte Axis(double v) => (byte)Math.Clamp(128 + v * 127, 1, 255);
        Live = new LiveInput(Axis(Math.Cos(t * 1.3) * 0.7), Axis(Math.Sin(t * 1.3) * 0.7), Axis(Math.Sin(t * 0.9) * 0.5), Axis(Math.Cos(t * 1.7) * 0.5),
            (byte)(Math.Max(0, Math.Sin(t * 1.1)) * 255), (byte)(Math.Max(0, Math.Sin(t * 0.7 + 1)) * 255), 8, 0, 0, 0);
        ushort mask = 0;
        int step = (int)(t * 2) % 15;
        mask |= (ushort)(1 << step);
        var adc = new ushort[] { 2000, 2100, 1963, 2041, 2130, 2144 };
        if (DemoStress)   // developer aid (--demo --stress): every button and axis changes on every tick, like mashing the controller
        {
            var r = Random.Shared;
            mask = (ushort)r.Next(0, 1 << 15);
            Live = new LiveInput((byte)r.Next(256), (byte)r.Next(256), (byte)r.Next(256), (byte)r.Next(256), (byte)r.Next(256), (byte)r.Next(256), 8, 0, 0, 0);
            for (int i = 0; i < adc.Length; i++) adc[i] = (ushort)r.Next(4096);
        }
        Tele = new Telemetry(true, true, mask, adc, Config.Crc, Live: true, PersistedCrc: Config.Crc);
        _linked = true;
        RefreshStatus();
        UpdatePressed();
        Calibration.Update(Tele);
    }

    // ---------------------------------------------------------------- persistence

    private void LoadCurrent()
    {
        try
        {
            if (File.Exists(CurrentPath))
            {
                var d = JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(File.ReadAllText(CurrentPath));
                if (d is not null) Config.LoadProfile(d);
            }
            if (File.Exists(AppliedPath) && uint.TryParse(File.ReadAllText(AppliedPath).Trim(), out uint crc)) AppliedCrc = crc;
        }
        catch { /* a corrupt file just means defaults */ }
    }

    private void SaveCurrent()
    {
        try
        {
            Directory.CreateDirectory(DataDir);
            File.WriteAllText(CurrentPath, JsonSerializer.Serialize(Config.ToProfile(), new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
    }

    public void SaveProfile(string path) =>
        File.WriteAllText(path, JsonSerializer.Serialize(Config.ToProfile(), new JsonSerializerOptions { WriteIndented = true }));

    public void LoadProfile(string path)
    {
        var d = JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(File.ReadAllText(path)) ?? throw new InvalidDataException("empty profile");
        Config.LoadProfile(d);
    }

    // ---------------------------------------------------------------- apply / restore workflow

    private FlashPhase _phase;
    private double _progress;
    private string _flowTitle = "Ready when you are";
    private string _flowDetail = "";
    private string _log = "";
    private bool _restoring;
    private TaskCompletionSource? _flashNow;

    public FlashPhase Phase
    {
        get => _phase;
        private set
        {
            if (!Set(ref _phase, value)) return;
            Raise(nameof(IsBusy)); Raise(nameof(ShowSteps)); Raise(nameof(CanStart));
            (ApplyCommand as RelayCommand)?.RaiseCanExecuteChanged();
            (RestoreCommand as RelayCommand)?.RaiseCanExecuteChanged();
            (FlashNowCommand as RelayCommand)?.RaiseCanExecuteChanged();
            (CancelFlowCommand as RelayCommand)?.RaiseCanExecuteChanged();
        }
    }

    public double Progress { get => _progress; private set => Set(ref _progress, value); }
    public string FlowTitle { get => _flowTitle; private set => Set(ref _flowTitle, value); }
    public string FlowDetail { get => _flowDetail; private set => Set(ref _flowDetail, value); }
    public string Log { get => _log; private set => Set(ref _log, value); }
    public bool Restoring { get => _restoring; private set => Set(ref _restoring, value); }
    public bool IsBusy => Phase is FlashPhase.Flashing or FlashPhase.Verifying;
    public bool ShowSteps => Phase is FlashPhase.WaitingForBootloader or FlashPhase.Ready;
    public bool CanStart => Phase is FlashPhase.Idle or FlashPhase.Done or FlashPhase.Failed;
    public bool HasStock => StockFirmware.LoadStored() is not null;
    public string StockGlyph => HasStock ? "" : "";
    public string StockStatus => HasStock ? "Original firmware v2.70 is saved" : "Not saved yet";

    /// <summary>Asked before a flash that would overwrite Razer's firmware while no copy of it is saved; return false to cancel.</summary>
    public Func<bool>? ConfirmWithoutOriginal { get; set; }

    /// <summary>The original isn't saved yet, so show the warning on the Firmware page.</summary>
    public bool ShowOriginalWarning => !HasStock && !IsV2Pro;   // the saved original is the V1's image

    /// <summary>Flashing now would replace Razer's firmware with no way back (the controller still runs it, or is in update mode).</summary>
    public bool NeedsOriginal => !HasStock && Device != DeviceState.CustomFirmware;

    // ---- first-run setup guide ----
    private static string SetupPath => Path.Combine(DataDir, "setup-done.txt");
    public bool SetupDone
    {
        get { try { return File.Exists(SetupPath); } catch { return false; } }
        set { try { Directory.CreateDirectory(DataDir); File.WriteAllText(SetupPath, "1"); } catch { /* optional */ } }
    }

    public void RaiseStock() { Raise(nameof(HasStock)); Raise(nameof(StockGlyph)); Raise(nameof(StockStatus)); Raise(nameof(ShowOriginalWarning)); Raise(nameof(NeedsOriginal)); }

    private void CancelFlow()
    {
        _flowCts?.Cancel();
        _flashNow?.TrySetCanceled();
    }

    public Task BeginApplyAsync()
    {
        if (NeedsOriginal && ConfirmWithoutOriginal is { } ask && !ask()) return Task.CompletedTask;
        return RunFlowAsync(restoring: false);
    }

    public Task BeginRestoreAsync() => RunFlowAsync(restoring: true);

    private async Task RunFlowAsync(bool restoring)
    {
        if (!CanStart) return;
        if (IsV2Pro)
        {
            // The V2 Pro's bootloader speaks Razer's protocol, not the V1's DFU; tools/v2pro_dfu.py flashes it.
            ShowFirmwarePage?.Invoke();
            Fail("The Kishi V2 Pro is flashed with tools/v2pro_dfu.py", "This app writes the Kishi V1's firmware only. Settings still apply live.");
            return;
        }
        Restoring = restoring;
        Log = "";
        Progress = 0;
        ShowFirmwarePage?.Invoke();

        byte[] image;
        if (restoring)
        {
            var stock = StockFirmware.LoadStored();
            if (stock is null) { Fail("The original firmware isn't imported yet", "Import it from a backup file or the Razer app (APK) first."); return; }
            image = stock;
        }
        else
        {
            image = FirmwareImage.Patch(_baseImage, Config);
            var problems = FirmwareImage.Validate(image);
            if (problems.Count > 0) { Fail("This configuration can't be built", problems[0]); return; }
            Monitor.AddCandidate((ushort)Config.Get("vid"), (ushort)Config.Get("pid"));
        }

        _flowCts = new CancellationTokenSource();
        var ct = _flowCts.Token;
        try
        {
            // 1. Wait for the bootloader.
            if (Monitor.State != DeviceState.Bootloader)
            {
                FlowTitle = "Put the Kishi in update mode";
                FlowDetail = "Unplug it, hold Y + B + Right Function, then plug it back in while holding.";
                Phase = FlashPhase.WaitingForBootloader;
                while (Monitor.State != DeviceState.Bootloader) await Task.Delay(150, ct);
            }
            if (Monitor.Model == KishiModel.V2Pro)
            {
                Fail("That is a Kishi V2 Pro", "This app flashes the Kishi V1 only; use tools/v2pro_dfu.py for the V2 Pro.");
                return;
            }
            FlowTitle = "Bootloader detected";
            FlowDetail = restoring ? "Ready to restore the original Razer firmware." : "Ready to write your settings to the controller.";
            _flashNow = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            Phase = FlashPhase.Ready;
            using (ct.Register(() => _flashNow.TrySetCanceled()))
                await _flashNow.Task;

            // 2. Flash.
            FlowTitle = restoring ? "Restoring original firmware" : "Writing firmware";
            FlowDetail = "Keep the controller connected.";
            Phase = FlashPhase.Flashing;
            var prog = new Progress<double>(p => Progress = p);
            var result = await DfuService.FlashAsync(image, prog, CancellationToken.None);
            Log = result.Log;
            if (!result.Success) { Fail("Flashing didn't complete", result.Message); return; }

            // 3. Verify it came back as the expected firmware.
            FlowTitle = "Restarting controller";
            FlowDetail = "Waiting for it to reconnect…";
            Phase = FlashPhase.Verifying;
            Progress = 1;
            var want = restoring ? DeviceState.StockFirmware : DeviceState.CustomFirmware;
            var deadline = DateTime.UtcNow.AddSeconds(30);
            while (Monitor.State != want && DateTime.UtcNow < deadline) await Task.Delay(200);

            if (restoring)
            {
                AppliedCrc = 0;
                File.WriteAllText(AppliedPath, "0");
                Done(Monitor.State == want ? "Original firmware restored" : "Original firmware written",
                     Monitor.State == want ? "Your Kishi is back to stock." : "Unplug and replug the controller if it doesn't reappear.");
                return;
            }

            uint expected = Config.Crc;
            var settleUntil = DateTime.UtcNow.AddSeconds(8);
            while (DateTime.UtcNow < settleUntil && !(Monitor.State == want && Monitor.Latest is { } t0 && t0.ConfigCrc == expected)) await Task.Delay(150);
            bool verified = Monitor.State == want && Monitor.Latest is { } t && t.ConfigCrc == expected;
            AppliedCrc = expected;
            try { Directory.CreateDirectory(DataDir); File.WriteAllText(AppliedPath, expected.ToString()); } catch { }
            Done(verified ? "Applied and verified" : "Firmware written",
                 verified ? "The controller reports it is running exactly these settings." : "The controller restarted but didn't confirm its settings yet. Unplug and replug it, then check the status in the header.");
        }
        catch (OperationCanceledException)
        {
            Phase = FlashPhase.Idle;
            FlowTitle = "Cancelled";
            FlowDetail = "Nothing was changed.";
        }
        catch (Exception ex) { Fail("Something went wrong", ex.Message); }
        finally
        {
            _flowCts?.Dispose();
            _flowCts = null;
            Raise(nameof(Dirty)); Raise(nameof(SyncText)); RaiseStock();
        }
    }

    /// <summary>Developer aid for screenshots: show a given workflow state without flashing anything.</summary>
    public void ShowDemoPhase(FlashPhase phase, double progress, string title, string detail)
    {
        Progress = progress; FlowTitle = title; FlowDetail = detail; Phase = phase;
    }

    private void Done(string title, string detail) { FlowTitle = title; FlowDetail = detail; Phase = FlashPhase.Done; }

    private void Fail(string title, string detail) { FlowTitle = title; FlowDetail = detail; Phase = FlashPhase.Failed; }

    public string? ImportStock(string path)
    {
        var err = StockFirmware.Import(path);
        RaiseStock();
        return err;
    }

    public void Dispose()
    {
        _tick.Stop();
        _save.Stop();
        _debounce.Stop();
        _persist.Stop();
        SaveCurrent();
        Monitor.Dispose();
    }
}
