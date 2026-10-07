using System.Globalization;
using System.Windows;
using System.Windows.Media;
using KishiDS.Core;

namespace KishiDS.Controls;

internal static class Draw
{
    public static Brush B(FrameworkElement e, string key, Brush fallback) => e.TryFindResource(key) as Brush ?? fallback;

    public static Color C(FrameworkElement e, string key, Color fallback) => e.TryFindResource(key) is Color c ? c : fallback;

    public static FormattedText Text(FrameworkElement e, string s, double size, Brush brush, FontWeight? weight = null)
    {
        var tf = new Typeface(new FontFamily("Segoe UI Variable Text, Segoe UI"), FontStyles.Normal, weight ?? FontWeights.SemiBold, FontStretches.Normal);
        return new FormattedText(s, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, tf, size, brush, VisualTreeHelper.GetDpi(e).PixelsPerDip);
    }

    public static void CenteredText(DrawingContext dc, FrameworkElement e, string s, Point c, double size, Brush brush)
    {
        var ft = Text(e, s, size, brush);
        dc.DrawText(ft, new Point(c.X - ft.Width / 2, c.Y - ft.Height / 2));
    }

    public static DependencyProperty Prop<T>(string name, Type owner, T def) =>
        DependencyProperty.Register(name, typeof(T), owner, new FrameworkPropertyMetadata(def, FrameworkPropertyMetadataOptions.AffectsRender));
}

/// <summary>A stick's range: deadzone, full-scale ring and the live thumb position.</summary>
public sealed class StickPreview : FrameworkElement
{
    public static readonly DependencyProperty DeadProperty = Draw.Prop(nameof(Dead), typeof(StickPreview), 8.0);
    public static readonly DependencyProperty OuterProperty = Draw.Prop(nameof(Outer), typeof(StickPreview), 90.0);
    public static readonly DependencyProperty XProperty = Draw.Prop(nameof(X), typeof(StickPreview), 128);
    public static readonly DependencyProperty YProperty = Draw.Prop(nameof(Y), typeof(StickPreview), 128);
    public static readonly DependencyProperty InvertXProperty = Draw.Prop(nameof(InvertX), typeof(StickPreview), false);

    public double Dead { get => (double)GetValue(DeadProperty); set => SetValue(DeadProperty, value); }
    public double Outer { get => (double)GetValue(OuterProperty); set => SetValue(OuterProperty, value); }
    public int X { get => (int)GetValue(XProperty); set => SetValue(XProperty, value); }
    public int Y { get => (int)GetValue(YProperty); set => SetValue(YProperty, value); }
    public bool InvertX { get => (bool)GetValue(InvertXProperty); set => SetValue(InvertXProperty, value); }

    protected override void OnRender(DrawingContext dc)
    {
        double size = Math.Min(ActualWidth, ActualHeight);
        if (size <= 0) return;
        var c = new Point(ActualWidth / 2, ActualHeight / 2);
        double R = size / 2 - 6;
        var accent = Draw.B(this, "AccentBrush", Brushes.DodgerBlue);
        var line = new Pen(Draw.B(this, "StrokeHiBrush", Brushes.Gray), 1.5);

        dc.DrawEllipse(Draw.B(this, "FieldBrush", Brushes.Black), new Pen(Draw.B(this, "StrokeHiBrush", Brushes.Gray), 2), c, R, R);
        // crosshair
        dc.DrawLine(line, new Point(c.X - R, c.Y), new Point(c.X + R, c.Y));
        dc.DrawLine(line, new Point(c.X, c.Y - R), new Point(c.X, c.Y + R));
        // deadzone (filled) and the radius where output reaches full scale
        double d = Dead / 100.0;
        double full = d + (1 - d) * Outer / 100.0;
        dc.DrawEllipse(new SolidColorBrush(Color.FromArgb(0x40, 0xF8, 0x71, 0x71)), new Pen(new SolidColorBrush(Color.FromArgb(0xAA, 0xF8, 0x71, 0x71)), 1.5), c, R * d, R * d);
        dc.DrawEllipse(null, new Pen(new SolidColorBrush(Color.FromArgb(0xAA, 0x34, 0xD3, 0x99)), 1.5) { DashStyle = new DashStyle(new double[] { 3, 3 }, 0) }, c, R * full, R * full);

        double dx = (X - 128) / 127.0 * R, dy = (Y - 128) / 127.0 * R;
        double len = Math.Sqrt(dx * dx + dy * dy);
        if (len > R) { dx *= R / len; dy *= R / len; }
        var p = new Point(c.X + dx, c.Y + dy);
        dc.DrawEllipse(new SolidColorBrush(Color.FromArgb(0x30, 0x3B, 0x82, 0xF6)), null, p, 15, 15);
        dc.DrawEllipse(accent, new Pen(Brushes.White, 2), p, 8, 8);
    }
}

/// <summary>Response curve: stick travel (x) against reported value (y).</summary>
public sealed class CurveGraph : FrameworkElement
{
    public static readonly DependencyProperty DeadProperty = Draw.Prop(nameof(Dead), typeof(CurveGraph), 8.0);
    public static readonly DependencyProperty OuterProperty = Draw.Prop(nameof(Outer), typeof(CurveGraph), 90.0);
    public static readonly DependencyProperty CurveProperty = Draw.Prop(nameof(Curve), typeof(CurveGraph), 0);

    public double Dead { get => (double)GetValue(DeadProperty); set => SetValue(DeadProperty, value); }
    public double Outer { get => (double)GetValue(OuterProperty); set => SetValue(OuterProperty, value); }
    public int Curve { get => (int)GetValue(CurveProperty); set => SetValue(CurveProperty, value); }

    /// <summary>Same maths as the firmware's stick_scale (normalised 0..1).</summary>
    public static double Response(double x, double dead, double outer, int curve)
    {
        double d = dead / 100.0;
        double span = (1 - d) * outer / 100.0;
        double v = x <= d ? 0 : Math.Min(1, (x - d) / Math.Max(span, 1e-6));
        return curve switch { 1 => v * v, 2 => Math.Min(1, 2 * v - v * v), _ => v };
    }

    protected override void OnRender(DrawingContext dc)
    {
        var r = new Rect(6, 6, ActualWidth - 12, ActualHeight - 12);
        if (r.Width <= 0 || r.Height <= 0) return;
        var grid = new Pen(Draw.B(this, "StrokeBrush", Brushes.Gray), 1);
        dc.DrawRoundedRectangle(Draw.B(this, "FieldBrush", Brushes.Black), new Pen(Draw.B(this, "StrokeBrush", Brushes.Gray), 1), r, 12, 12);
        for (int i = 1; i < 4; i++)
        {
            dc.DrawLine(grid, new Point(r.X + r.Width * i / 4, r.Y + 6), new Point(r.X + r.Width * i / 4, r.Bottom - 6));
            dc.DrawLine(grid, new Point(r.X + 6, r.Y + r.Height * i / 4), new Point(r.Right - 6, r.Y + r.Height * i / 4));
        }
        var inner = new Rect(r.X + 10, r.Y + 10, r.Width - 20, r.Height - 20);
        var geo = new StreamGeometry();
        using (var g = geo.Open())
        {
            for (int i = 0; i <= 100; i++)
            {
                double x = i / 100.0;
                var p = new Point(inner.X + x * inner.Width, inner.Bottom - Response(x, Dead, Outer, Curve) * inner.Height);
                if (i == 0) g.BeginFigure(p, false, false); else g.LineTo(p, true, true);
            }
        }
        geo.Freeze();
        dc.DrawGeometry(null, new Pen(Draw.B(this, "AccentBrush", Brushes.DodgerBlue), 3) { LineJoin = PenLineJoin.Round, StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round }, geo);
    }
}

/// <summary>Horizontal analog bar with the digital-press threshold marked.</summary>
public sealed class TriggerBar : FrameworkElement
{
    public static readonly DependencyProperty ValueProperty = Draw.Prop(nameof(Value), typeof(TriggerBar), 0);
    public static readonly DependencyProperty ThresholdProperty = Draw.Prop(nameof(Threshold), typeof(TriggerBar), 10);
    public static readonly DependencyProperty InnerProperty = Draw.Prop(nameof(Inner), typeof(TriggerBar), 0.0);
    public static readonly DependencyProperty OuterProperty = Draw.Prop(nameof(Outer), typeof(TriggerBar), 100.0);
    public static readonly DependencyProperty LabelProperty = Draw.Prop(nameof(Label), typeof(TriggerBar), "L2");

    public int Value { get => (int)GetValue(ValueProperty); set => SetValue(ValueProperty, value); }
    public int Threshold { get => (int)GetValue(ThresholdProperty); set => SetValue(ThresholdProperty, value); }
    public double Inner { get => (double)GetValue(InnerProperty); set => SetValue(InnerProperty, value); }
    public double Outer { get => (double)GetValue(OuterProperty); set => SetValue(OuterProperty, value); }
    public string Label { get => (string)GetValue(LabelProperty); set => SetValue(LabelProperty, value); }

    protected override void OnRender(DrawingContext dc)
    {
        var r = new Rect(0, 0, ActualWidth, ActualHeight);
        if (r.Width <= 0) return;
        var accent = Draw.B(this, "AccentBrush", Brushes.DodgerBlue);
        double h = Math.Min(r.Height, 30);
        var bar = new Rect(46, (r.Height - h) / 2, r.Width - 46, h);
        dc.DrawRoundedRectangle(Draw.B(this, "FieldBrush", Brushes.Black), new Pen(Draw.B(this, "StrokeBrush", Brushes.Gray), 1), bar, h / 2, h / 2);
        // dead and over-travel regions
        double x0 = bar.X + bar.Width * Inner / 100.0 * 0.0;
        double w = Math.Max(0, Math.Min(1, Value / 255.0)) * bar.Width;
        if (w > 1)
        {
            dc.PushClip(new RectangleGeometry(bar, h / 2, h / 2));
            dc.DrawRectangle(Value > Threshold ? accent : Draw.B(this, "TextFaintBrush", Brushes.Gray), null, new Rect(bar.X, bar.Y, w, bar.Height));
            dc.Pop();
        }
        double tx = bar.X + bar.Width * Threshold / 255.0;
        dc.DrawLine(new Pen(new SolidColorBrush(Color.FromRgb(0xFB, 0xBF, 0x24)), 2), new Point(tx, bar.Y - 3), new Point(tx, bar.Bottom + 3));
        Draw.CenteredText(dc, this, Label, new Point(20, r.Height / 2), 13, Draw.B(this, "TextDimBrush", Brushes.Gray));
        _ = x0;
    }
}

/// <summary>Circular progress for the flashing step.</summary>
public sealed class RingProgress : FrameworkElement
{
    public static readonly DependencyProperty ValueProperty = Draw.Prop(nameof(Value), typeof(RingProgress), 0.0);
    public static readonly DependencyProperty SpinningProperty = DependencyProperty.Register(nameof(Spinning), typeof(bool), typeof(RingProgress),
        new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender, (d, e) => ((RingProgress)d).Listen((bool)e.NewValue)));
    public static readonly DependencyProperty StateProperty = Draw.Prop(nameof(State), typeof(RingProgress), 0);   // 0 normal, 1 success, 2 failure

    public double Value { get => (double)GetValue(ValueProperty); set => SetValue(ValueProperty, value); }
    public bool Spinning { get => (bool)GetValue(SpinningProperty); set => SetValue(SpinningProperty, value); }
    public int State { get => (int)GetValue(StateProperty); set => SetValue(StateProperty, value); }

    private double _spin;

    public RingProgress()
    {
        Unloaded += (_, _) => Listen(false);
    }

    private bool _listening;

    private void Listen(bool on)
    {
        if (on == _listening) return;
        _listening = on;
        if (on) CompositionTarget.Rendering += OnFrame; else CompositionTarget.Rendering -= OnFrame;
    }

    private void OnFrame(object? sender, EventArgs e)
    {
        if (!Spinning || !IsVisible) return;
        _spin = (_spin + 4) % 360;
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        double size = Math.Min(ActualWidth, ActualHeight);
        if (size <= 0) return;
        var c = new Point(ActualWidth / 2, ActualHeight / 2);
        double R = size / 2 - 8;
        Brush color = State == 3 ? Draw.B(this, "TextFaintBrush", Brushes.Gray) : State == 1 ? Draw.B(this, "GoodBrush", Brushes.LightGreen) : State == 2 ? Draw.B(this, "BadBrush", Brushes.IndianRed) : Draw.B(this, "AccentBrush", Brushes.DodgerBlue);
        dc.DrawEllipse(null, new Pen(Draw.B(this, "StrokeBrush", Brushes.Gray), 8), c, R, R);

        double sweep = Spinning ? 90 : Math.Max(0, Math.Min(1, Value)) * 359.9;
        if (State is 1 or 2) sweep = 359.9; else if (State == 3) sweep = 0;
        double start = Spinning ? _spin - 90 : -90;
        if (sweep > 0.5)
        {
            var p0 = Pt(c, R, start);
            var p1 = Pt(c, R, start + sweep);
            var geo = new StreamGeometry();
            using (var g = geo.Open())
            {
                g.BeginFigure(p0, false, false);
                g.ArcTo(p1, new Size(R, R), 0, sweep > 180, SweepDirection.Clockwise, true, true);
            }
            geo.Freeze();
            dc.DrawGeometry(null, new Pen(color, 8) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round }, geo);
        }
        string center = State == 1 ? "" : State == 2 ? "" : State == 3 ? "" : "";
        if (center.Length > 0)
        {
            var tf = new Typeface(new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets"), FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            var ft = new FormattedText(center, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, tf, R * 0.7, color, VisualTreeHelper.GetDpi(this).PixelsPerDip);
            dc.DrawText(ft, new Point(c.X - ft.Width / 2, c.Y - ft.Height / 2));
        }
        else if (!Spinning)
            Draw.CenteredText(dc, this, $"{(int)Math.Round(Value * 100)}%", c, R * 0.42, Draw.B(this, "TextBrush", Brushes.White));
    }

    private static Point Pt(Point c, double r, double deg)
    {
        double a = deg * Math.PI / 180;
        return new Point(c.X + r * Math.Cos(a), c.Y + r * Math.Sin(a));
    }
}
