using System.Text;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using KishiDS.Controls;
using KishiDS.Core;

namespace KishiDS;

public partial class App : Application
{
    private AppModel? _model;

    // ---- theme (dark / light) --------------------------------------------------------------------------------------
    // Colours live in Theme/Palette.*.xaml.  Templates resolve many of them statically, so switching swaps the palette,
    // reloads the theme dictionaries and rebuilds the window (same model, same position and size).
    public static bool IsLight { get; private set; }

    /// <summary>--setup: open the first-run guide even if it was completed before.</summary>
    public static bool ForceSetup { get; private set; }

    private static string ThemePath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KishiDS", "theme.txt");

    private static bool SavedThemeIsLight()
    {
        try { return File.Exists(ThemePath) && File.ReadAllText(ThemePath).Trim().Equals("light", StringComparison.OrdinalIgnoreCase); }
        catch { return false; }
    }

    private static void ApplyTheme(bool light)
    {
        var md = Current.Resources.MergedDictionaries;
        md.Clear();
        md.Add(new ResourceDictionary { Source = new Uri(light ? "Theme/Palette.Light.xaml" : "Theme/Palette.Dark.xaml", UriKind.Relative) });
        md.Add(new ResourceDictionary { Source = new Uri("Theme/Theme.xaml", UriKind.Relative) });
        md.Add(new ResourceDictionary { Source = new Uri("Theme/KishiArt.xaml", UriKind.Relative) });
        IsLight = light;
    }

    public void ToggleTheme()
    {
        if (_model is null || MainWindow is not MainWindow old) return;
        ApplyTheme(!IsLight);
        try { Directory.CreateDirectory(Path.GetDirectoryName(ThemePath)!); File.WriteAllText(ThemePath, IsLight ? "light" : "dark"); } catch { /* preference is optional */ }
        var w = new MainWindow(_model) { WindowStartupLocation = WindowStartupLocation.Manual, Left = old.Left, Top = old.Top };
        if (old.WindowState == WindowState.Normal) { w.Width = old.ActualWidth; w.Height = old.ActualHeight; }
        else w.WindowState = old.WindowState;
        w.Navigate(old.CurrentPage, animate: false);
        w.ResumeSetupAt = old.OpenSetupStep;
        MainWindow = w;
        w.Show();
        old.Close();
    }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var args = e.Args;
        int sd = Array.IndexOf(args, "--stockdir");
        if (sd >= 0 && sd + 1 < args.Length) StockFirmware.DirectoryOverride = args[sd + 1];

        // ---- Developer / automation modes (no window) ------------------------------------------------------
        int st = Array.IndexOf(args, "--selftest");
        if (st >= 0)
        {
            string? file = st + 1 < args.Length ? args[st + 1] : null;
            using var w = file is null ? Console.Out : new StreamWriter(file);
            Shutdown(SelfTest.Run(w));
            return;
        }
        if (LiveHeadless(args)) return;
        if (Headless(args)) return;

        // ---- Normal start ----------------------------------------------------------------------------------
        ForceSetup = args.Contains("--setup");
        ApplyTheme(args.Contains("--light") || (!args.Contains("--dark") && SavedThemeIsLight()));
        bool demo = args.Contains("--demo");
        AppModel.DemoStress = args.Contains("--stress");
        int shot = Array.IndexOf(args, "--screenshot");
        _model = new AppModel(demo || shot >= 0);
        var window = new MainWindow(_model);

        if (shot >= 0)
        {
            Screenshot(window, args, shot);
            return;
        }
        window.Show();
        int pf = Array.IndexOf(args, "--perf");
        if (pf >= 0 && pf + 1 < args.Length) Perf(window, args[pf + 1], args.Length > pf + 2 ? args[pf + 2] : "Overview");
    }

    /// <summary>Developer aid: with --demo, measure UI-thread responsiveness and frame rate for 6 s on one page.  --perf &lt;log&gt; [Page]</summary>
    private static void Perf(MainWindow window, string log, string page)
    {
        bool cycle = page == "Nav";
        window.Navigate(cycle ? "Overview" : page, animate: false);
        if (cycle)
        {
            string[] names = { "Buttons", "Sticks", "Overview", "Lighting", "Firmware", "Triggers" };
            int ni = 0;
            var nav = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(700) };
            nav.Tick += (_, _) => window.Navigate(names[ni++ % names.Length], animate: true);
            nav.Start();
        }
        int frames = 0;
        EventHandler onRender = (_, _) => frames++;
        var lat = new List<double>();
        var sw = System.Diagnostics.Stopwatch.StartNew();
        double last = 0;
        var probe = new DispatcherTimer(DispatcherPriority.Normal) { Interval = TimeSpan.FromMilliseconds(10) };
        probe.Tick += (_, _) => { double now = sw.Elapsed.TotalMilliseconds; if (last > 0) lat.Add(now - last - 10); last = now; };
        var start = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        start.Tick += (_, _) =>
        {
            start.Stop();
            sw.Restart(); last = 0; frames = 0; lat.Clear(); ControllerView.PerfMs = 0; ControllerView.PerfCalls = 0; DeviceMonitor.PerfReports = 0; DeviceMonitor.PerfTeleReads = 0; DeviceMonitor.PerfTeleMs = 0;
            CompositionTarget.Rendering += onRender;
            probe.Start();
            var end = new DispatcherTimer { Interval = TimeSpan.FromSeconds(6) };
            end.Tick += (_, _) =>
            {
                end.Stop(); probe.Stop(); CompositionTarget.Rendering -= onRender;
                lat.Sort();
                double avg = lat.Count > 0 ? lat.Average() : 0, p95 = lat.Count > 0 ? lat[(int)(lat.Count * 0.95)] : 0, max = lat.Count > 0 ? lat[^1] : 0;
                File.WriteAllText(log, $"page={page} frames/s={frames / sw.Elapsed.TotalSeconds:0.0} dispatcher-delay avg={avg:0.0}ms p95={p95:0.0}ms max={max:0.0}ms (lower is better); tier={RenderCapability.Tier >> 16} ControllerView.OnRender calls/s={ControllerView.PerfCalls / sw.Elapsed.TotalSeconds:0.0} avg={(ControllerView.PerfCalls > 0 ? ControllerView.PerfMs / ControllerView.PerfCalls : 0):0.00}ms"+$" | device: input reports/s={DeviceMonitor.PerfReports / sw.Elapsed.TotalSeconds:0.0} telemetry reads/s={DeviceMonitor.PerfTeleReads / sw.Elapsed.TotalSeconds:0.0} avg read={(DeviceMonitor.PerfTeleReads > 0 ? DeviceMonitor.PerfTeleMs / DeviceMonitor.PerfTeleReads : 0):0.00}ms"+Environment.NewLine);
                Current.Shutdown(0);
            };
            end.Start();
        };
        start.Start();
    }

    // ------------------------------------------------------------------------------------------------------
    // Headless modes drive the real production code (device monitor, DFU flashing, verification) from the
    // command line, so the whole path can be exercised without clicking through the UI:
    //   --status <log>                         report what the monitor sees
    //   --apply <log> [profile.json]           run the "Apply to controller" flow (waits for the bootloader)
    //   --restore <log>                        run "Restore original firmware"
    //   --import-stock <file> <log>            import the original v2.70 image (.bin or Razer .apk)
    // ------------------------------------------------------------------------------------------------------
    // --live <log> [profile.json] [--save] [--reboot]
    //   Exercise live editing against a connected controller with no UI: read its config, push the profile (or the
    //   defaults), verify by CRC and read-back, optionally save to flash and reboot to confirm it persisted.
    private bool LiveHeadless(string[] args)
    {
        int li = Array.IndexOf(args, "--live");
        if (li < 0 || li + 1 >= args.Length) return false;
        string? profile = li + 2 < args.Length && !args[li + 2].StartsWith("--") ? args[li + 2] : null;
        bool save = args.Contains("--save"), reboot = args.Contains("--reboot");
        var log = new StreamWriter(args[li + 1], append: false, Encoding.UTF8) { AutoFlush = true };
        void L(string s) => log.WriteLine($"{DateTime.Now:HH:mm:ss.fff}  {s}");

        Task.Run(async () =>
        {
            int code = 0;
            try
            {
                var cfg = new ConfigBlock();
                if (profile is not null)
                {
                    cfg.LoadProfile(System.Text.Json.JsonSerializer.Deserialize<Dictionary<string, System.Text.Json.JsonElement>>(File.ReadAllText(profile))!);
                    L($"profile loaded: {profile}");
                }
                L($"target config crc 0x{cfg.Crc:X8}");
                using var mon = new DeviceMonitor();
                mon.AddCandidate((ushort)cfg.Get("vid"), (ushort)cfg.Get("pid"));
                mon.Start();

                async Task<Telemetry?> Wait(Func<Telemetry, bool> ok, int seconds)
                {
                    for (int i = 0; i < seconds * 10; i++)
                    {
                        if (mon.State == DeviceState.CustomFirmware && mon.Latest is { } t && ok(t)) return t;
                        await Task.Delay(100);
                    }
                    return null;
                }
                string Show(Telemetry t) => $"live={t.Live} fromSaved={t.FromSaved} unsaved={t.Unsaved} identityDiffers={t.IdentityDiffers} active=0x{t.ConfigCrc:X8} persisted=0x{t.PersistedCrc:X8}";

                var t0 = await Wait(_ => true, 15);
                if (t0 is null) { L("FAIL no custom-firmware controller found"); Dispatcher.Invoke(() => Shutdown(1)); return; }
                L("telemetry: " + Show(t0.Value));
                if (!t0.Value.Live) { L("FAIL firmware does not support live editing (flash the new image first)"); Dispatcher.Invoke(() => Shutdown(1)); return; }

                var (before, err) = mon.ReadConfig();
                L(before is null ? $"FAIL read config: {err}" : $"read config ok, crc matches telemetry: {BitConverter.ToUInt32(before, 12) == t0.Value.ConfigCrc}");
                if (before is not null) { var b = new ConfigBlock(before); L($"  controller: led_mode={b.Get("led_mode")} brightness={b.Get("led_brightness")} map[0..1]={b.Get("button_map", 0)},{b.Get("button_map", 1)} product=\"{b.GetString("product")}\""); }

                var push = mon.PushConfig(cfg.ToBytes());
                L($"push: {(push.Ok ? "ok" : "FAIL " + push.Message)}");
                if (!push.Ok) code = 1;
                var t1 = await Wait(t => t.ConfigCrc == cfg.Crc, 3);
                L(t1 is { } a ? "telemetry after push: " + Show(a) : "FAIL telemetry never reported the new crc");
                if (t1 is null) code = 1;
                var (after, err2) = mon.ReadConfig();
                bool same = after is not null && after.SequenceEqual(cfg.ToBytes());
                L($"read-back equals pushed block: {same}{(after is null ? " (" + err2 + ")" : "")}");
                if (!same) code = 1;

                if (save)
                {
                    var s = mon.Save();
                    L($"save: {(s.Ok ? "ok" : "FAIL " + s.Message)}");
                    if (!s.Ok) code = 1;
                    var t2 = await Wait(t => !t.Unsaved && t.PersistedCrc == cfg.Crc, 3);
                    L(t2 is { } c ? "telemetry after save: " + Show(c) : "FAIL telemetry did not show the config as saved");
                    if (t2 is null) code = 1;
                }
                if (reboot)
                {
                    var r = mon.Reboot();
                    L($"reboot requested: {r.Ok}");
                    for (int i = 0; i < 80 && mon.State == DeviceState.CustomFirmware; i++) await Task.Delay(100);
                    L($"device state after reboot request: {mon.State}");
                    var t3 = await Wait(t => true, 25);
                    L(t3 is { } d ? "telemetry after reboot: " + Show(d) : "FAIL controller did not come back");
                    if (t3 is null || t3.Value.ConfigCrc != cfg.Crc) { L("FAIL config after reboot differs from the pushed one"); code = 1; }
                    else L("config survived the reboot");
                }
                L(code == 0 ? "ALL OK" : "FAILURES");
            }
            catch (Exception ex) { L("EXCEPTION: " + ex); code = 2; }
            finally { log.Dispose(); }
            Dispatcher.Invoke(() => Shutdown(code));
        });
        return true;
    }

    private bool Headless(string[] args)
    {
        int status = Array.IndexOf(args, "--status");
        int apply = Array.IndexOf(args, "--apply");
        int restore = Array.IndexOf(args, "--restore");
        int import = Array.IndexOf(args, "--import-stock");
        int idx = new[] { status, apply, restore, import }.Where(i => i >= 0).DefaultIfEmpty(-1).Min();
        if (idx < 0) return false;

        string logPath = idx == import ? args[idx + 2] : args[idx + 1];
        var log = new StreamWriter(logPath, append: false, Encoding.UTF8) { AutoFlush = true };
        void L(string s) => log.WriteLine($"{DateTime.Now:HH:mm:ss.fff}  {s}");

        _model = new AppModel(demo: false);
        Dispatcher.InvokeAsync(async () =>
        {
            int code = 0;
            try
            {
                if (idx == import)
                {
                    var err = _model.ImportStock(args[idx + 1]);
                    L(err ?? "stock image imported");
                    code = err is null ? 0 : 1;
                }
                else if (idx == status)
                {
                    for (int i = 0; i < 30 && !(_model.Device == DeviceState.CustomFirmware && _model.Tele is not null) && _model.Device != DeviceState.Bootloader; i++)
                        await Task.Delay(200);
                    L($"device: {_model.Device}  serial: {_model.Serial ?? "(none)"}");
                    if (_model.Tele is { } t)
                        L($"telemetry: configured={t.Configured} fromImage={t.ConfigFromImage} buttons=0x{t.ButtonMask:X4} adc=[{string.Join(",", t.Adc)}] configCrc=0x{t.ConfigCrc:X8}");
                    L($"app config crc: 0x{_model.Config.Crc:X8}  inSync={_model.InSync}");
                    L($"live: LX={_model.Live.Lx} LY={_model.Live.Ly} RX={_model.Live.Rx} RY={_model.Live.Ry} L2={_model.Live.L2} R2={_model.Live.R2} hat={_model.Live.Hat}");
                }
                else
                {
                    if (idx == apply && apply + 2 < args.Length) { _model.LoadProfile(args[apply + 2]); L($"profile loaded: {args[apply + 2]}"); }
                    _model.PropertyChanged += (_, ev) =>
                    {
                        // Headless runs have nobody to press "Flash now": confirm automatically once the bootloader is detected.
                        if (ev.PropertyName is nameof(AppModel.Phase) && _model.Phase == FlashPhase.Ready) _model.FlashNowCommand.Execute(null);
                        if (ev.PropertyName is nameof(AppModel.Phase)) L($"phase: {_model.Phase}  |  {_model.FlowTitle}  |  {_model.FlowDetail}");
                        else if (ev.PropertyName is nameof(AppModel.Progress) && _model.Phase == FlashPhase.Flashing) L($"progress: {_model.Progress:P0}");
                    };
                    L(idx == apply ? "starting apply flow" : "starting restore flow");
                    await (idx == apply ? _model.BeginApplyAsync() : _model.BeginRestoreAsync());
                    L($"result: {_model.Phase}  |  {_model.FlowTitle}  |  {_model.FlowDetail}");
                    L("---- dfu-util log ----");
                    log.WriteLine(_model.Log);
                    code = _model.Phase == FlashPhase.Done ? 0 : 1;
                }
            }
            catch (Exception ex) { L("EXCEPTION: " + ex); code = 2; }
            finally { log.Dispose(); }
            Shutdown(code);
        });
        return true;
    }

    private static void Screenshot(MainWindow window, string[] args, int shot)
    {
        // Developer aid: render a page to a PNG off-screen and exit.   --screenshot <Page> <file.png> [width height] [--phase X]
        string page = args[shot + 1], file = args[shot + 2];
        int w = shot + 3 < args.Length && int.TryParse(args[shot + 3], out var ww) ? ww : 1240;
        int h = shot + 4 < args.Length && int.TryParse(args[shot + 4], out var hh) ? hh : 804;
        window.WindowStartupLocation = WindowStartupLocation.Manual;
        window.Left = -20000;
        window.Top = -20000;
        window.Width = w;
        window.Height = h;
        window.ShowInTaskbar = false;
        window.Show();
        if (page.StartsWith("Setup", StringComparison.OrdinalIgnoreCase))
        {
            window.Navigate("Overview", animate: false);
            window.ShowSetup(page.Length > 5 && int.TryParse(page[5..], out var st) ? st : 1);
        }
        else window.Navigate(page, animate: false);
        int ph = Array.IndexOf(args, "--phase");
        if (ph >= 0 && Enum.TryParse<FlashPhase>(args[ph + 1], out var phase))
        {
            ((AppModel)window.DataContext).ShowDemoPhase(phase, phase == FlashPhase.Flashing ? 0.62 : 0, phase switch
            {
                FlashPhase.WaitingForBootloader => "Put the Kishi in update mode",
                FlashPhase.Ready => "Bootloader detected",
                FlashPhase.Flashing => "Writing firmware",
                FlashPhase.Verifying => "Restarting controller",
                FlashPhase.Done => "Applied and verified",
                FlashPhase.Failed => "Flashing didn't complete",
                _ => "Ready when you are",
            }, phase == FlashPhase.Failed ? "The bootloader rejected the image." : "Keep the controller connected.");
        }
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1400) };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            var root = (FrameworkElement)window.Content;
            int pw = (int)root.ActualWidth, pht = (int)root.ActualHeight;
            // Paint the window background first so the PNG is opaque (a window's Background is not part of its Content).
            var dv = new DrawingVisual();
            using (var dc = dv.RenderOpen())
            {
                dc.DrawRectangle((Brush)Current.FindResource("BgBrush"), null, new Rect(0, 0, pw, pht));
                dc.DrawRectangle(new VisualBrush(root), null, new Rect(0, 0, pw, pht));
            }
            var rtb = new RenderTargetBitmap(pw, pht, 96, 96, PixelFormats.Pbgra32);
            rtb.Render(dv);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(rtb));
            using (var fs = File.Create(file)) enc.Save(fs);
            Current.Shutdown(0);
        };
        timer.Start();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _model?.Dispose();
        base.OnExit(e);
    }
}
