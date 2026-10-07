using System.Windows;
using System.Windows.Controls;
using KishiDS.Core;
using Microsoft.Win32;

namespace KishiDS.Pages;

public partial class OverviewPage : UserControl
{
    public OverviewPage() => InitializeComponent();

    private AppModel Model => (AppModel)DataContext;

    private void Go_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string page } && Window.GetWindow(this) is MainWindow w) w.Navigate(page);
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new SaveFileDialog { Title = "Save profile", Filter = "KishiDS profile (*.kishi.json)|*.kishi.json", FileName = "my-profile.kishi.json" };
        if (dlg.ShowDialog() == true) Model.SaveProfile(dlg.FileName);
    }

    private void Load_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog { Title = "Load profile", Filter = "KishiDS profile (*.json)|*.json|All files|*.*" };
        if (dlg.ShowDialog() != true) return;
        try { Model.LoadProfile(dlg.FileName); }
        catch (Exception ex) { MessageBox.Show("That file isn't a valid profile.\n\n" + ex.Message, "KishiDS", MessageBoxButton.OK, MessageBoxImage.Warning); }
    }
}
