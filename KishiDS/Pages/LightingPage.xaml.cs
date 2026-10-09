using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using KishiDS.Core;

namespace KishiDS.Pages;

public partial class LightingPage : UserControl
{
    private static readonly string[] Colours =
        { "#FF0000", "#FF6A00", "#FFD000", "#00FF00", "#00FFC8", "#00A0FF", "#0000FF", "#8000FF", "#FF00C8", "#FFFFFF" };

    public LightingPage()
    {
        InitializeComponent();
        Swatches.ItemsSource = Colours;
    }

    private void Swatch_Click(object sender, RoutedEventArgs e)
    {
        if (DataContext is not AppModel m || sender is not FrameworkElement { Tag: string hex }) return;
        var c = (Color)ColorConverter.ConvertFromString(hex);
        m.S.SetLedColor(c.R, c.G, c.B);
    }
}
