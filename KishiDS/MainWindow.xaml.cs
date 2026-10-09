using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Shell;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using KishiDS.Controls;
using KishiDS.Core;
using KishiDS.Pages;

namespace KishiDS;

public partial class MainWindow : Window
{
    private readonly AppModel _model;
    private readonly Dictionary<string, Func<UserControl>> _factories;
    private readonly Dictionary<string, UserControl> _pages = new();
    private string _current = "Overview";
    private readonly Action _showFirmware;

    public string CurrentPage => _current;

    /// <summary>Set before Show() to reopen the setup guide at that step (used when the window is rebuilt for a theme change).</summary>
    public int? ResumeSetupAt { get; set; }

    public int? OpenSetupStep => Setup.Visibility == Visibility.Visible ? Setup.Step : null;

    // The stage the UI is laid out on (see MainWindow.xaml); the window keeps this aspect ratio.
    private const double DesignW = 1240, DesignH = 804;

    public MainWindow(AppModel model)
    {
        InitializeComponent();
        // Never open bigger than the screen: shrink the start size, keeping the aspect ratio.
        var wa = SystemParameters.WorkArea;
        double fit = Math.Min(1, Math.Min(wa.Width * 0.94 / DesignW, wa.Height * 0.94 / DesignH));
        Width = DesignW * fit;
        Height = DesignH * fit;
        SizeChanged += (_, _) => UpdateScale();
        _model = model;
        DataContext = model;
        _factories = new()
        {
            ["Overview"] = () => new OverviewPage(),
            ["Buttons"] = () => new ButtonsPage(),
            ["Sticks"] = () => new SticksPage(),
            ["Triggers"] = () => new TriggersPage(),
            ["D-pad"] = () => new DpadPage(),
            ["Lighting"] = () => new LightingPage(),
            ["Rumble"] = () => new RumblePage(),
            ["Calibration"] = () => new CalibrationPage(),
            ["Identity"] = () => new IdentityPage(),
            ["Firmware"] = () => new FirmwarePage(),
        };
        _showFirmware = () => Dispatcher.Invoke(() => Navigate("Firmware"));
        model.ShowFirmwarePage += _showFirmware;
        Closed += (_, _) => { model.PropertyChanged -= OnModelChanged; model.ShowFirmwarePage -= _showFirmware; model.ConfirmWithoutOriginal = null; };
        model.ConfirmWithoutOriginal = ConfirmFlashWithoutOriginal;
        Setup.Finished += CloseSetup;
        ThemeIcon.Text = App.IsLight ? "" : "";
        ThemeButton.ToolTip = App.IsLight ? "Switch to dark theme" : "Switch to light theme";
        model.PropertyChanged += OnModelChanged;
        Navigate("Overview", animate: false);
        Loaded += (_, _) =>
        {
            UpdateApplyBar();
            WarmPages();
            if (ResumeSetupAt is { } step) ShowSetup(step);
            else if (App.ForceSetup || (!model.Demo && !model.SetupDone)) ShowSetup(1);
        };
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        // Windows 11: ask DWM for rounded window corners (no transparency tricks needed).
        int round = 2;   // DWMWCP_ROUND
        var hwnd = new WindowInteropHelper(this).Handle;
        Native_DwmCorner(hwnd, round);
        HwndSource.FromHwnd(hwnd)?.AddHook(SizingHook);
    }

    /// <summary>Window-to-stage scale; also keeps the draggable title strip and popups in proportion.</summary>
    private void UpdateScale()
    {
        double k = Math.Min(ActualWidth / DesignW, ActualHeight / DesignH);
        if (k <= 0) return;
        UiScale.Instance.Value = k;
        if (WindowChrome.GetWindowChrome(this) is { } chrome) chrome.CaptionHeight = 56 * k;
    }

    // ---- fixed aspect ratio: while the user drags an edge or corner, derive the other dimension -------------------

    private const int WM_SIZING = 0x0214;

    [StructLayout(LayoutKind.Sequential)] private struct RECT { public int Left, Top, Right, Bottom; }

    private IntPtr SizingHook(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg != WM_SIZING) return IntPtr.Zero;
        var r = Marshal.PtrToStructure<RECT>(lParam);
        const double ratio = DesignW / DesignH;
        int w = r.Right - r.Left, h = r.Bottom - r.Top;
        switch (wParam.ToInt32())
        {
            case 1: case 2:               // left / right edge: width leads, bottom follows
            case 7: case 8:               // bottom corners: width leads, bottom follows
                r.Bottom = r.Top + (int)Math.Round(w / ratio); break;
            case 3: case 6:               // top / bottom edge: height leads, right follows
                r.Right = r.Left + (int)Math.Round(h * ratio); break;
            case 4: case 5:               // top corners: width leads, top follows
                r.Top = r.Bottom - (int)Math.Round(w / ratio); break;
            default: return IntPtr.Zero;
        }
        Marshal.StructureToPtr(r, lParam, false);
        handled = true;
        return (IntPtr)1;
    }

    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    private static void Native_DwmCorner(IntPtr hwnd, int pref)
    {
        try { DwmSetWindowAttribute(hwnd, 33, ref pref, sizeof(int)); } catch { /* older Windows: square corners */ }
    }

    // ------------------------------------------------------------------ navigation

    /// <summary>Builds the pages one at a time while the app is idle, so the first visit to each doesn't stall the slide-in animation.</summary>
    private void WarmPages()
    {
        var pending = new Queue<string>(_factories.Keys.Where(k => !_pages.ContainsKey(k)));
        void Next()
        {
            if (pending.Count == 0) return;
            var key = pending.Dequeue();
            if (!_pages.ContainsKey(key)) _pages[key] = _factories[key]();
            Dispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle, Next);
        }
        Dispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle, Next);
    }

    public void Navigate(string key, bool animate = true)
    {
        if (!_pages.TryGetValue(key, out var page)) _pages[key] = page = _factories[key]();
        _current = key;
        foreach (var rb in NavPanel.Children.OfType<RadioButton>()) rb.IsChecked = rb.Content as string == key;
        Host.Content = page;
        if (animate) PlayEnter();
        UpdateApplyBar();
    }

    private void Nav_Click(object sender, RoutedEventArgs e)
    {
        if (sender is RadioButton rb && rb.Content is string key) Navigate(key);
    }

    private void PlayEnter()
    {
        var sb = new Storyboard();
        var fade = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(260)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
        Storyboard.SetTarget(fade, Host);
        Storyboard.SetTargetProperty(fade, new PropertyPath(OpacityProperty));
        var slide = new DoubleAnimation(14, 0, TimeSpan.FromMilliseconds(300)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
        Storyboard.SetTarget(slide, Host);
        Storyboard.SetTargetProperty(slide, new PropertyPath("RenderTransform.(TranslateTransform.Y)"));
        sb.Children.Add(fade);
        sb.Children.Add(slide);
        sb.Begin();
    }

    // ------------------------------------------------------------------ floating apply bar

    private void OnModelChanged(object? s, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(AppModel.Bar) or nameof(AppModel.Dirty) or nameof(AppModel.Phase) or nameof(AppModel.Device)) Dispatcher.Invoke(UpdateApplyBar);
    }

    private bool _barShown;

    private void UpdateApplyBar()
    {
        bool show = _model.Bar != AppModel.BarKind.None && _model.CanStart && _current != "Firmware";
        if (show == _barShown) return;
        _barShown = show;
        var ease = new CubicEase { EasingMode = show ? EasingMode.EaseOut : EasingMode.EaseIn };
        var dur = TimeSpan.FromMilliseconds(show ? 300 : 200);
        ApplyBar.IsHitTestVisible = show;
        ApplyBar.BeginAnimation(OpacityProperty, new DoubleAnimation(show ? 1 : 0, dur) { EasingFunction = ease });
        BarShift.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, new DoubleAnimation(show ? 0 : 24, dur) { EasingFunction = ease });
    }

    // ------------------------------------------------------------------ window buttons

    // ------------------------------------------------------------------ setup guide, drag-and-drop, warnings

    public void ShowSetup(int step = 1)
    {
        Setup.Step = step;
        Setup.Visibility = Visibility.Visible;
        NavPanel.IsEnabled = false;
        UpdateApplyBar();
    }

    private void CloseSetup()
    {
        Setup.Visibility = Visibility.Collapsed;
        NavPanel.IsEnabled = true;
        UpdateApplyBar();
    }

    /// <summary>Asked before a flash that would erase Razer's firmware when no copy is saved. Yes = go ahead, No = show how to get it.</summary>
    private bool ConfirmFlashWithoutOriginal()
    {
        var r = MessageBox.Show(
            "Razer's original firmware isn't saved yet."+"\n\n"+"Once this controller is switched, Razer's firmware is gone from it and can't be read back, so you won't be able to restore it from this app."+"\n\n"+"Yes: continue anyway"+"\n"+"No: show me how to get the original first",
            "KishiDS", MessageBoxButton.YesNo, MessageBoxImage.Warning, MessageBoxResult.No);
        if (r == MessageBoxResult.Yes) return true;
        ShowSetup(2);
        return false;
    }

    private void Window_DragOver(object sender, DragEventArgs e)
    {
        e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
        e.Handled = true;
    }

    private void Window_Drop(object sender, DragEventArgs e)
    {
        if (e.Data.GetData(DataFormats.FileDrop) is not string[] files) return;
        string? lastError = null;
        foreach (var f in files.Where(f => f.EndsWith(".apk", StringComparison.OrdinalIgnoreCase) || f.EndsWith(".bin", StringComparison.OrdinalIgnoreCase) || f.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)))
        {
            lastError = _model.ImportStock(f);
            if (lastError is null) break;
        }
        if (lastError is not null) { MessageBox.Show(lastError, "KishiDS", MessageBoxButton.OK, MessageBoxImage.Warning); return; }
        if (!_model.HasStock) { MessageBox.Show("Drop the Razer Kishi app (.apk) or the original firmware (.bin).", "KishiDS", MessageBoxButton.OK, MessageBoxImage.Information); return; }
        if (Setup.Visibility != Visibility.Visible)
        {
            Navigate("Firmware");
            MessageBox.Show("Original firmware saved. You can restore it any time from this page.", "KishiDS", MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    private void Theme_Click(object sender, RoutedEventArgs e) => ((App)Application.Current).ToggleTheme();

    private void Min_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    private void Max_Click(object sender, RoutedEventArgs e) => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
