using System.Windows;
using System.Windows.Controls;

namespace KishiDS.Controls;

public partial class SliderRow : UserControl
{
    public static readonly DependencyProperty LabelProperty = DependencyProperty.Register(nameof(Label), typeof(string), typeof(SliderRow), new PropertyMetadata(""));
    public static readonly DependencyProperty HintProperty = DependencyProperty.Register(nameof(Hint), typeof(string), typeof(SliderRow), new PropertyMetadata("", (d, _) => ((SliderRow)d).HintChanged()));
    public static readonly DependencyProperty UnitProperty = DependencyProperty.Register(nameof(Unit), typeof(string), typeof(SliderRow), new PropertyMetadata(""));
    public static readonly DependencyProperty ValueProperty = DependencyProperty.Register(nameof(Value), typeof(double), typeof(SliderRow), new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.BindsTwoWayByDefault));
    public static readonly DependencyProperty MinimumProperty = DependencyProperty.Register(nameof(Minimum), typeof(double), typeof(SliderRow), new PropertyMetadata(0.0));
    public static readonly DependencyProperty MaximumProperty = DependencyProperty.Register(nameof(Maximum), typeof(double), typeof(SliderRow), new PropertyMetadata(100.0));
    public static readonly DependencyProperty StepProperty = DependencyProperty.Register(nameof(Step), typeof(double), typeof(SliderRow), new PropertyMetadata(1.0));
    public static readonly DependencyProperty HintVisibilityProperty = DependencyProperty.Register(nameof(HintVisibility), typeof(Visibility), typeof(SliderRow), new PropertyMetadata(Visibility.Collapsed));

    public SliderRow() => InitializeComponent();

    public string Label { get => (string)GetValue(LabelProperty); set => SetValue(LabelProperty, value); }
    public string Hint { get => (string)GetValue(HintProperty); set => SetValue(HintProperty, value); }
    public string Unit { get => (string)GetValue(UnitProperty); set => SetValue(UnitProperty, value); }
    public double Value { get => (double)GetValue(ValueProperty); set => SetValue(ValueProperty, value); }
    public double Minimum { get => (double)GetValue(MinimumProperty); set => SetValue(MinimumProperty, value); }
    public double Maximum { get => (double)GetValue(MaximumProperty); set => SetValue(MaximumProperty, value); }
    public double Step { get => (double)GetValue(StepProperty); set => SetValue(StepProperty, value); }
    public Visibility HintVisibility { get => (Visibility)GetValue(HintVisibilityProperty); set => SetValue(HintVisibilityProperty, value); }

    private void HintChanged() => HintVisibility = string.IsNullOrEmpty(Hint) ? Visibility.Collapsed : Visibility.Visible;
}
