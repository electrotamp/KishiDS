using System.Windows;
using System.Windows.Controls;
using KishiDS.Core;
using Microsoft.Win32;

namespace KishiDS.Pages;

public partial class FirmwarePage : UserControl
{
    public FirmwarePage() => InitializeComponent();

    private MainWindow? Main => Window.GetWindow(this) as MainWindow;

    private void Tutorial_Click(object sender, RoutedEventArgs e) => Main?.ShowSetup(1);

    private void Guide_Click(object sender, RoutedEventArgs e) => Main?.ShowSetup(2);

    private async void Find_Click(object sender, RoutedEventArgs e)
    {
        var list = await Task.Run(StockFirmware.FindInUserFolders);
        if (list.Count == 0)
        {
            var go = MessageBox.Show("Nothing found in Downloads, Desktop or Documents."+"\n\n"+"Show the step-by-step guide for getting Razer's original firmware?",
                "KishiDS", MessageBoxButton.YesNo, MessageBoxImage.Information);
            if (go == MessageBoxResult.Yes) Main?.ShowSetup(2);
            return;
        }
        var best = list[0];
        var ask = MessageBox.Show($"Found {best.Name} in {best.Detail.Split(' ')[0]}."+"\n\n"+$"Save the original firmware from it?", "KishiDS", MessageBoxButton.YesNo, MessageBoxImage.Question);
        if (ask != MessageBoxResult.Yes) return;
        var error = ((AppModel)DataContext).ImportStock(best.Path);
        if (error is not null) MessageBox.Show(error, "KishiDS", MessageBoxButton.OK, MessageBoxImage.Warning);
    }

    private void Import_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Import the original Kishi firmware",
            Filter = "Firmware image or Razer app (*.bin;*.apk)|*.bin;*.apk|All files|*.*",
        };
        if (dlg.ShowDialog() != true) return;
        var error = ((AppModel)DataContext).ImportStock(dlg.FileName);
        if (error is not null) MessageBox.Show(error, "KishiDS", MessageBoxButton.OK, MessageBoxImage.Warning);
    }
}
