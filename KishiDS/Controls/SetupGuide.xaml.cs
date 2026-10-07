using System.ComponentModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using KishiDS.Core;
using Microsoft.Win32;

namespace KishiDS.Controls;

/// <summary>First-run walkthrough: welcome, get and save Razer's original firmware, then switch the controller.</summary>
public partial class SetupGuide : UserControl
{
    private const int LastStep = 3;
    private int _step = 1;
    private StockCandidate? _found;
    private bool _scanning;
    private readonly DispatcherTimer _scan = new() { Interval = TimeSpan.FromSeconds(2) };

    public event Action? Finished;

    public SetupGuide()
    {
        InitializeComponent();
        _scan.Tick += async (_, _) => await ScanAsync();
        Loaded += (_, _) =>
        {
            if (DataContext is AppModel m) m.PropertyChanged += OnModelChanged;
            Refresh();
        };
        Unloaded += (_, _) =>
        {
            _scan.Stop();
            if (DataContext is AppModel m) m.PropertyChanged -= OnModelChanged;
        };
    }

    private AppModel Model => (AppModel)DataContext;

    public int Step
    {
        get => _step;
        set { _step = Math.Clamp(value, 1, LastStep); Refresh(); }
    }

    private void OnModelChanged(object? s, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(AppModel.HasStock) or nameof(AppModel.Device) or nameof(AppModel.IsLive) or nameof(AppModel.DeviceTitle))
            Dispatcher.BeginInvoke(Refresh);
    }

    // ------------------------------------------------------------------------------------------------ rendering

    private Brush Res(string key) => (Brush)FindResource(key);

    private void Refresh()
    {
        if (DataContext is not AppModel m) return;
        Step1.Visibility = _step == 1 ? Visibility.Visible : Visibility.Collapsed;
        Step2.Visibility = _step == 2 ? Visibility.Visible : Visibility.Collapsed;
        Step3.Visibility = _step == 3 ? Visibility.Visible : Visibility.Collapsed;
        BackButton.Visibility = _step > 1 ? Visibility.Visible : Visibility.Collapsed;

        // Progress dots: done = green with a tick, current = accent, upcoming = quiet.
        (Border dot, TextBlock text, TextBlock label)[] dots = { (Dot1, DotText1, DotLabel1), (Dot2, DotText2, DotLabel2), (Dot3, DotText3, DotLabel3) };
        for (int i = 0; i < dots.Length; i++)
        {
            int n = i + 1;
            bool done = n < _step || (n == 2 && m.HasStock && _step > 2);
            bool current = n == _step;
            dots[i].dot.Background = current ? Res("AccentBrush") : done ? Res("GoodSoftBrush") : Res("AccentSoftBrush");
            dots[i].text.Text = done ? "" : n.ToString();
            dots[i].text.FontFamily = done ? (FontFamily)FindResource("IconFont") : (FontFamily)FindResource("UiFont");
            dots[i].text.Foreground = current ? Brushes.White : done ? Res("GoodBrush") : Res("AccentHiBrush");
            dots[i].label.Foreground = current ? Res("TextBrush") : Res("TextDimBrush");
            dots[i].label.FontWeight = current ? FontWeights.SemiBold : FontWeights.Normal;
        }

        switch (_step)
        {
            case 1: RefreshWelcome(m); break;
            case 2: RefreshOriginal(m); break;
            default: RefreshSwitch(m); break;
        }

        if (_step == 2 && !m.HasStock) { if (!_scan.IsEnabled) { _scan.Start(); _ = ScanAsync(); } }
        else _scan.Stop();
    }

    private void RefreshWelcome(AppModel m)
    {
        NextButton.Content = "Get started";
        NextButton.IsEnabled = true;
        SkipButton.Visibility = Visibility.Collapsed;
        WelcomeDot.Fill = (Brush)new DeviceStateToBrush().Convert(m.Device, typeof(Brush), null!, null!);
        WelcomeStatus.Text = m.Device switch
        {
            DeviceState.CustomFirmware => "Your Kishi is already running KishiDS firmware.",
            DeviceState.StockFirmware => "Kishi found, running Razer's original firmware.",
            DeviceState.Bootloader => "Kishi found in update mode.",
            _ => "No controller detected yet. You can plug it in whenever you like.",
        };
    }

    private void RefreshOriginal(AppModel m)
    {
        bool have = m.HasStock;
        StockOk.Visibility = have ? Visibility.Visible : Visibility.Collapsed;
        StockFound.Visibility = !have && _found is not null ? Visibility.Visible : Visibility.Collapsed;
        StockWaiting.Visibility = !have && _found is null ? Visibility.Visible : Visibility.Collapsed;
        NextButton.Content = "Continue";
        SkipButton.Content = "Skip for now";
        NextButton.IsEnabled = have;
        SkipButton.Visibility = have ? Visibility.Collapsed : Visibility.Visible;
        if (_found is not null)
        {
            FoundName.Text = $"Found {_found.Name}";
            FoundDetail.Text = $"In {_found.Detail}. It contains the original firmware.";
        }
    }

    private void RefreshSwitch(AppModel m)
    {
        bool live = m.IsLive;
        Step3Title.Text = live ? "You're all set" : "Switch your controller";
        Step3Steps.Visibility = live ? Visibility.Collapsed : Visibility.Visible;
        Step3Tips.Visibility = live ? Visibility.Visible : Visibility.Collapsed;
        Step3Intro.Text = live
            ? "Your Kishi is already running KishiDS firmware with live editing. Every change you make applies instantly. You can reopen this guide from the Firmware page."
            : "KishiDS puts the controller into its update mode, writes the new firmware and checks it came back correctly. The controller can always be returned to update mode with the button combination below, even if something goes wrong.";
        NextButton.Content = live ? "Finish" : "Switch my controller";
        SkipButton.Visibility = live ? Visibility.Collapsed : Visibility.Visible;
        SkipButton.Content = "Not now";
        NextButton.IsEnabled = true;
        Step3Dot.Fill = m.HasStock ? Res("GoodBrush") : Res("WarnBrush");
        Step3Status.Text = m.HasStock
            ? "Razer's original firmware is saved. You can restore it from the Firmware page at any time."
            : "Razer's original firmware is not saved yet. Going back to stock won't be possible until you import it.";
    }

    // ------------------------------------------------------------------------------------------------ finding the file

    private async Task ScanAsync()
    {
        if (_scanning || DataContext is not AppModel m || m.HasStock) return;
        _scanning = true;
        try
        {
            var list = await Task.Run(StockFirmware.FindInUserFolders);
            _found = list.FirstOrDefault();
            WaitingDetail.Text = _found is null ? "Nothing found yet in Downloads, Desktop or Documents. This checks again every couple of seconds." : "";
            Refresh();
        }
        finally { _scanning = false; }
    }

    private void UseFound_Click(object sender, RoutedEventArgs e)
    {
        if (_found is null) return;
        TryImport(_found.Path);
    }

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Choose the Razer Kishi app (.apk) or the original firmware (.bin)",
            Filter = "Razer Kishi app or firmware (*.apk;*.bin)|*.apk;*.bin|All files|*.*",
        };
        if (dlg.ShowDialog() == true) TryImport(dlg.FileName);
    }

    private void TryImport(string path)
    {
        var error = Model.ImportStock(path);
        if (error is not null) MessageBox.Show(error, "KishiDS", MessageBoxButton.OK, MessageBoxImage.Warning);
        else { _found = null; Refresh(); }
    }

    private void Google_Click(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo("https://www.google.com/search?q=Razer+Kishi+apk") { UseShellExecute = true }); }
        catch { MessageBox.Show("Couldn't open your browser. Search Google for: Razer Kishi apk", "KishiDS", MessageBoxButton.OK, MessageBoxImage.Information); }
    }

    // ------------------------------------------------------------------------------------------------ navigation

    private void Back_Click(object sender, RoutedEventArgs e) => Step = _step - 1;

    private void Skip_Click(object sender, RoutedEventArgs e)
    {
        if (_step == 2)
        {
            var go = MessageBox.Show(
                "Without the original firmware you won't be able to put the controller back to Razer's version from this app.\n\nYou can still import it later from the Firmware page. Skip this step for now?",
                "KishiDS", MessageBoxButton.YesNo, MessageBoxImage.Warning);
            if (go == MessageBoxResult.Yes) Step = 3;
        }
        else Finish();
    }

    private void Next_Click(object sender, RoutedEventArgs e)
    {
        if (_step < 3) { Step = _step + 1; return; }
        bool live = Model.IsLive;
        Finish();
        if (!live) Model.ApplyCommand.Execute(null);   // shows the Firmware page with the update-mode steps
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Finish();

    private void Finish()
    {
        _scan.Stop();
        Model.SetupDone = true;
        Finished?.Invoke();
    }
}
