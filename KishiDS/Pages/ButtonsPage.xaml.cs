using System.Windows;
using System.Windows.Controls;
using KishiDS.Core;

namespace KishiDS.Pages;

public partial class ButtonsPage : UserControl
{
    public ButtonsPage() => InitializeComponent();

    private AppModel Model => (AppModel)DataContext;

    private void ResetRow_Click(object sender, RoutedEventArgs e)
    {
        if (((FrameworkElement)sender).DataContext is ButtonRow row) row.Output = ConfigBlock.Field("button_map").Default[row.Index];
    }

    private int Row(string name) => Array.IndexOf(ConfigLayout.KishiButtons, name);

    private void Swap(string a, string b)
    {
        int ia = Row(a), ib = Row(b);
        int va = Model.Config.Get("button_map", ia), vb = Model.Config.Get("button_map", ib);
        Model.Config.Set("button_map", vb, ia);
        Model.Config.Set("button_map", va, ib);
    }

    private void SwapAB_Click(object sender, RoutedEventArgs e) => Swap("A", "B");
    private void SwapXY_Click(object sender, RoutedEventArgs e) => Swap("X", "Y");

    private void SwapShoulders_Click(object sender, RoutedEventArgs e)
    {
        // Bumpers become triggers and the other way round (the Kishi has no physical L2/R2 buttons to swap with,
        // so this maps L1/R1 to L2/R2 and the stick clicks stay put).
        Model.Config.Set("button_map", Array.IndexOf(ConfigLayout.Outputs, "L2"), Row("L1"));
        Model.Config.Set("button_map", Array.IndexOf(ConfigLayout.Outputs, "R2"), Row("R1"));
    }

    private void ResetAll_Click(object sender, RoutedEventArgs e) => Model.Config.ResetField("button_map");
}
