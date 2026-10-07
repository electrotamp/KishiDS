using System.Windows;
using System.Windows.Media;

namespace KishiDS.Controls;

/// <summary>Glowing preview of the blue LED in the selected mode.</summary>
public sealed class LedPreview : FrameworkElement
{
    public static readonly DependencyProperty ModeProperty = Draw.Prop(nameof(Mode), typeof(LedPreview), 1);
    public static readonly DependencyProperty BrightnessProperty = Draw.Prop(nameof(Brightness), typeof(LedPreview), 255);
    public static readonly DependencyProperty BreathSecondsProperty = Draw.Prop(nameof(BreathSeconds), typeof(LedPreview), 2.0);
    public static readonly DependencyProperty ActiveProperty = Draw.Prop(nameof(Active), typeof(LedPreview), true);

    public int Mode { get => (int)GetValue(ModeProperty); set => SetValue(ModeProperty, value); }
    public int Brightness { get => (int)GetValue(BrightnessProperty); set => SetValue(BrightnessProperty, value); }
    public double BreathSeconds { get => (double)GetValue(BreathSecondsProperty); set => SetValue(BreathSecondsProperty, value); }
    public bool Active { get => (bool)GetValue(ActiveProperty); set => SetValue(ActiveProperty, value); }

    private readonly DateTime _t0 = DateTime.UtcNow;

    public LedPreview()
    {
        CompositionTarget.Rendering += (_, _) => { if (Mode == 2 && IsVisible) InvalidateVisual(); };
    }

    /// <summary>Same shape as the firmware: level, then gamma 2.</summary>
    private double Level()
    {
        double b = Brightness / 255.0;
        double period = Math.Max(0.5, BreathSeconds);
        double level = Mode switch
        {
            0 => 0,
            2 => Tri(((DateTime.UtcNow - _t0).TotalSeconds % period) / period) * b,
            3 => Active ? b : 0,
            _ => b,
        };
        return level * level;
    }

    private static double Tri(double phase) => phase < 0.5 ? phase * 2 : (1 - phase) * 2;

    protected override void OnRender(DrawingContext dc)
    {
        double size = Math.Min(ActualWidth, ActualHeight);
        if (size <= 0) return;
        var c = new Point(ActualWidth / 2, ActualHeight / 2);
        double L = Level();
        double R = size * 0.16;
        var glow = new RadialGradientBrush(Color.FromArgb((byte)(200 * L), 0x3B, 0x82, 0xF6), Color.FromArgb(0, 0x3B, 0x82, 0xF6))
        {
            Center = new Point(0.5, 0.5),
            GradientOrigin = new Point(0.5, 0.5),
        };
        dc.DrawEllipse(glow, null, c, size / 2, size / 2);
        dc.DrawEllipse(Draw.B(this, "CardHiBrush", Brushes.Black), new Pen(Draw.B(this, "StrokeHiBrush", Brushes.Gray), 2), c, R + 16, R + 16);
        var core = new SolidColorBrush(Color.FromRgb((byte)(0x20 + (0x7C - 0x20) * L), (byte)(0x28 + (0xB4 - 0x28) * L), (byte)(0x3A + (0xFF - 0x3A) * L)));
        dc.DrawEllipse(core, null, c, R, R);
    }
}

/// <summary>One raw ADC channel: current value with captured min/max range markers.</summary>
public sealed class RawBar : FrameworkElement
{
    public static readonly DependencyProperty ValueProperty = Draw.Prop(nameof(Value), typeof(RawBar), 0);
    public static readonly DependencyProperty MinProperty = Draw.Prop(nameof(Min), typeof(RawBar), 4095);
    public static readonly DependencyProperty MaxProperty = Draw.Prop(nameof(Max), typeof(RawBar), 0);
    public static readonly DependencyProperty LabelProperty = Draw.Prop(nameof(Label), typeof(RawBar), "");

    public int Value { get => (int)GetValue(ValueProperty); set => SetValue(ValueProperty, value); }
    public int Min { get => (int)GetValue(MinProperty); set => SetValue(MinProperty, value); }
    public int Max { get => (int)GetValue(MaxProperty); set => SetValue(MaxProperty, value); }
    public string Label { get => (string)GetValue(LabelProperty); set => SetValue(LabelProperty, value); }

    protected override void OnRender(DrawingContext dc)
    {
        if (ActualWidth <= 0) return;
        var bar = new Rect(130, (ActualHeight - 14) / 2, Math.Max(10, ActualWidth - 130 - 54), 14);
        dc.DrawRoundedRectangle(Draw.B(this, "FieldBrush", Brushes.Black), new Pen(Draw.B(this, "StrokeBrush", Brushes.Gray), 1), bar, 7, 7);
        double X(int v) => bar.X + bar.Width * Math.Clamp(v / 4095.0, 0, 1);
        if (Max > Min)
            dc.DrawRoundedRectangle(new SolidColorBrush(Color.FromArgb(0x55, 0x3B, 0x82, 0xF6)), null,
                new Rect(X(Min), bar.Y + 2, Math.Max(2, X(Max) - X(Min)), bar.Height - 4), 5, 5);
        dc.DrawEllipse(Draw.B(this, "AccentHiBrush", Brushes.DodgerBlue), new Pen(Brushes.White, 1.5), new Point(X(Value), bar.Y + bar.Height / 2), 6, 6);
        var lab = Draw.Text(this, Label, 12.5, Draw.B(this, "TextDimBrush", Brushes.Gray), FontWeights.Normal);
        dc.DrawText(lab, new Point(0, (ActualHeight - lab.Height) / 2));
        var ft = Draw.Text(this, Value.ToString(), 12, Draw.B(this, "TextDimBrush", Brushes.Gray), FontWeights.Normal);
        dc.DrawText(ft, new Point(ActualWidth - ft.Width, (ActualHeight - ft.Height) / 2));
    }
}
