using System.Diagnostics;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;

namespace KishiDS.Core;

public sealed record FlashResult(bool Success, string Message, string Log);

/// <summary>Flashes firmware to the Kishi's bootloader (27F8:0BC0) with the embedded dfu-util.</summary>
public static class DfuService
{
    private static readonly Regex Percent = new(@"(\d{1,3})%", RegexOptions.Compiled);

    /// <summary>Extracts the embedded dfu-util to a per-user folder (once) and returns its path.</summary>
    public static string EnsureTool()
    {
        string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KishiDS", "bin");
        Directory.CreateDirectory(dir);
        string exe = Path.Combine(dir, "dfu-util.exe");
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("dfu-util.exe")
                      ?? throw new InvalidOperationException("embedded dfu-util missing");
        if (!File.Exists(exe) || new FileInfo(exe).Length != s.Length)
        {
            using var f = File.Create(exe);
            s.CopyTo(f);
        }
        return exe;
    }

    public static async Task<FlashResult> FlashAsync(byte[] image, IProgress<double>? progress, CancellationToken ct = default)
    {
        var problems = FirmwareImage.Validate(image, requireConfigBlock: false);
        if (problems.Count > 0) return new FlashResult(false, "Refusing to flash: " + problems[0], "");

        string exe = EnsureTool();
        string tmp = Path.Combine(Path.GetTempPath(), $"kishi-{Guid.NewGuid():N}.bin");
        await File.WriteAllBytesAsync(tmp, image, ct);
        var log = new StringBuilder();
        try
        {
            var psi = new ProcessStartInfo(exe, $"-d {DeviceMonitor.StockVid:x4}:{DeviceMonitor.BootPid:x4} -a 0 -D \"{tmp}\"")
            {
                RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true,
                StandardOutputEncoding = Encoding.ASCII, StandardErrorEncoding = Encoding.ASCII,
            };
            using var p = Process.Start(psi) ?? throw new InvalidOperationException("could not start dfu-util");
            var pump = new[] { Pump(p.StandardOutput, log, progress, ct), Pump(p.StandardError, log, progress, ct) };
            await p.WaitForExitAsync(ct);
            await Task.WhenAll(pump);

            var result = Interpret(log.ToString());
            if (result.Success) progress?.Report(1.0);
            return result;
        }
        finally
        {
            try { File.Delete(tmp); } catch { }
        }
    }

    /// <summary>Decide from dfu-util's output whether the transfer really succeeded.</summary>
    public static FlashResult Interpret(string text)
    {
        if (text.Contains("No DFU capable USB device", StringComparison.OrdinalIgnoreCase))
            return new FlashResult(false, "The Kishi bootloader isn't connected. Hold Y + B + Right Function while plugging it in.", text);
        if (text.Contains("Cannot open DFU device", StringComparison.OrdinalIgnoreCase) || text.Contains("LIBUSB_ERROR_ACCESS"))
            return new FlashResult(false, "Windows won't let the app open the bootloader (driver). The bootloader needs the WinUSB driver.", text);
        if (text.Contains("status(7)") || text.Contains("failed verification", StringComparison.OrdinalIgnoreCase))
            return new FlashResult(false, "The bootloader rejected the image (verification failed). Nothing was changed; the previous firmware still runs or recovery is available.", text);
        // The bootloader's own sticky "firmware is corrupt" status (10) before the transfer is expected and harmless.
        // What matters is the state after "Download done.": this controller restarts so quickly that dfu-util often
        // cannot read the final status at all (LIBUSB_ERROR_PIPE/IO), which is a normal, successful finish.  A rejected
        // image instead shows an error state after the download (handled above).
        int done = text.IndexOf("Download done.", StringComparison.Ordinal);
        if (done >= 0)
        {
            string after = text[done..];
            bool errorState = after.Contains("dfuERROR", StringComparison.Ordinal);
            bool manifest = after.Contains("dfuMANIFEST-SYNC, status(0)", StringComparison.Ordinal);
            bool vanished = after.Contains("unable to read DFU status after completion", StringComparison.Ordinal);
            if (!errorState && (manifest || vanished))
                return new FlashResult(true, "Firmware written. The controller is restarting.", text);
        }
        return new FlashResult(false, "The transfer did not complete cleanly. See the log for details.", text);
    }

    // dfu-util redraws its progress bar with carriage returns, so read character by character.
    private static async Task Pump(StreamReader r, StringBuilder log, IProgress<double>? progress, CancellationToken ct)
    {
        var line = new StringBuilder();
        var one = new char[1];
        while (await r.ReadAsync(one, ct) > 0)
        {
            char c = one[0];
            if (c is '\r' or '\n')
            {
                Consume(line.ToString(), log, progress);
                line.Clear();
                if (c == '\n') lock (log) log.Append('\n');
            }
            else line.Append(c);
        }
        Consume(line.ToString(), log, progress);
    }

    private static void Consume(string line, StringBuilder log, IProgress<double>? progress)
    {
        if (line.Length == 0) return;
        var m = Percent.Matches(line);
        if (m.Count > 0 && line.Contains("Download") && int.TryParse(m[^1].Groups[1].Value, out int pct))
        {
            progress?.Report(Math.Min(pct, 99) / 100.0);
            return;   // progress redraws are not worth keeping in the log
        }
        lock (log) log.AppendLine(line);
    }
}
