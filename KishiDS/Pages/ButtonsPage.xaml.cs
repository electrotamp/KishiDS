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
        if (((FrameworkElement)sender).DataContext is ButtonRow row) row.Output = row.DefaultOutput;
    }

    private void Swap(string a, string b)
    {
        if (Model.S.Button(a) is not { } ra || Model.S.Button(b) is not { } rb) return;
        (ra.Output, rb.Output) = (rb.Output, ra.Output);
    }

    private void SwapAB_Click(object sender, RoutedEventArgs e) => Swap("A", "B");
    private void SwapXY_Click(object sender, RoutedEventArgs e) => Swap("X", "Y");

    private void SwapShoulders_Click(object sender, RoutedEventArgs e)
    {
        // Bumpers become triggers and the other way round (the Kishi has no physical L2/R2 buttons to swap with,
        // so this maps L1/R1 to L2/R2 and the stick clicks stay put).
        if (Model.S.Button("L1") is { } l1) l1.Output = Array.IndexOf(ConfigLayout.Outputs, "L2");
        if (Model.S.Button("R1") is { } r1) r1.Output = Array.IndexOf(ConfigLayout.Outputs, "R2");
    }

    private void ResetAll_Click(object sender, RoutedEventArgs e) => Model.S.ResetButtons();
}
