using System.Windows;
using System.Windows.Controls;
using KishiDS.Core;

namespace KishiDS.Pages;

public partial class IdentityPage : UserControl
{
    public IdentityPage() => InitializeComponent();

    private void ResetIdentity_Click(object sender, RoutedEventArgs e)
    {
        var cfg = ((AppModel)DataContext).Config;
        foreach (var f in new[] { "product" }) cfg.ResetField(f);
    }
}
