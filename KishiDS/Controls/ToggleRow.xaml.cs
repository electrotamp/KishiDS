using System.Windows;
using System.Windows.Controls;

namespace KishiDS.Controls;

public partial class ToggleRow : UserControl
{
    public static readonly DependencyProperty LabelProperty = DependencyProperty.Register(nameof(Label), typeof(string), typeof(ToggleRow), new PropertyMetadata(""));
    public static readonly DependencyProperty HintProperty = DependencyProperty.Register(nameof(Hint), typeof(string), typeof(ToggleRow), new PropertyMetadata("", (d, _) => ((ToggleRow)d).HintChanged()));
    public static readonly DependencyProperty IsOnProperty = DependencyProperty.Register(nameof(IsOn), typeof(bool), typeof(ToggleRow), new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.BindsTwoWayByDefault));
    public static readonly DependencyProperty HintVisibilityProperty = DependencyProperty.Register(nameof(HintVisibility), typeof(Visibility), typeof(ToggleRow), new PropertyMetadata(Visibility.Collapsed));

    public ToggleRow() => InitializeComponent();

    public string Label { get => (string)GetValue(LabelProperty); set => SetValue(LabelProperty, value); }
    public string Hint { get => (string)GetValue(HintProperty); set => SetValue(HintProperty, value); }
    public bool IsOn { get => (bool)GetValue(IsOnProperty); set => SetValue(IsOnProperty, value); }
    public Visibility HintVisibility { get => (Visibility)GetValue(HintVisibilityProperty); set => SetValue(HintVisibilityProperty, value); }

    private void HintChanged() => HintVisibility = string.IsNullOrEmpty(Hint) ? Visibility.Collapsed : Visibility.Visible;
}
