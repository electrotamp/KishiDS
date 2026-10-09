using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using KishiDS.Core;

namespace KishiDS.Controls;

/// <summary>
/// The Kishi V1, drawn from the two vector halves (Theme/KishiArt.xaml, generated from SVG/) with a bridge between them.
/// Pressed buttons glow, the stick caps follow the live axes, the trigger bars fill and the status LED mirrors the LED mode.
/// </summary>
public sealed partial class ControllerView : FrameworkElement
{
    public static readonly DependencyProperty InputProperty = Draw.Prop(nameof(Input), typeof(ControllerView), LiveInput.Neutral);
    public static readonly DependencyProperty ButtonMaskProperty = Draw.Prop(nameof(ButtonMask), typeof(ControllerView), 0);
    public static readonly DependencyProperty ConnectedProperty = Draw.Prop(nameof(Connected), typeof(ControllerView), false);
    public static readonly DependencyProperty LedModeProperty = Draw.Prop(nameof(LedMode), typeof(ControllerView), 1);   // 0 off, 1 solid, 2 breathing, 3 while in use

    public LiveInput Input { get => (LiveInput)GetValue(InputProperty); set => SetValue(InputProperty, value); }
    public int ButtonMask { get => (int)GetValue(ButtonMaskProperty); set => SetValue(ButtonMaskProperty, value); }
    public bool Connected { get => (bool)GetValue(ConnectedProperty); set => SetValue(ConnectedProperty, value); }
    public int LedMode { get => (int)GetValue(LedModeProperty); set => SetValue(LedModeProperty, value); }

    /// <summary>Draw the Kishi V2 Pro (ControllerViewV2.cs) instead of the V1.</summary>
    public static readonly DependencyProperty IsV2ProProperty = Draw.Prop(nameof(IsV2Pro), typeof(ControllerView), false);
    public static readonly DependencyProperty LedColorProperty = Draw.Prop(nameof(LedColor), typeof(ControllerView), Color.FromRgb(0, 0, 255));
    public bool IsV2Pro { get => (bool)GetValue(IsV2ProProperty); set => SetValue(IsV2ProProperty, value); }
    public Color LedColor { get => (Color)GetValue(LedColorProperty); set => SetValue(LedColorProperty, value); }

    // Scene, in art units.  Each half is 630 x 890; the left half's open side is x=630 and the right half's is its own x=0.
    private const double HalfW = 630, Gap = 640, Art = 890, Top = -70;
    private const double SceneW = HalfW * 2 + Gap, SceneH = Art - Top;
    private const double RightX = HalfW + Gap;
    private const double CapTravel = 24;

    private static readonly Color Accent = Color.FromRgb(0x3B, 0x82, 0xF6);
    private static readonly Color AccentHi = Color.FromRgb(0x7B, 0xB0, 0xFF);

    private static T Frozen<T>(T f) where T : Freezable { f.Freeze(); return f; }
    private static SolidColorBrush Solid(Color c) => Frozen(new SolidColorBrush(c));

    private static readonly Brush PressFill = Solid(Color.FromArgb(0x70, 0x3B, 0x82, 0xF6));
    private static readonly Pen PressPen = Frozen(new Pen(Solid(AccentHi), 4));
    private static readonly Brush Halo = Frozen(new RadialGradientBrush(new GradientStopCollection
    {
        new(Color.FromArgb(0x00, 0x3B, 0x82, 0xF6), 0.0), new(Color.FromArgb(0x00, 0x3B, 0x82, 0xF6), 0.55),
        new(Color.FromArgb(0x66, 0x3B, 0x82, 0xF6), 0.72), new(Color.FromArgb(0x00, 0x3B, 0x82, 0xF6), 1.0),
    }));
    private static readonly Brush Backlight = Frozen(new RadialGradientBrush(new GradientStopCollection
    {
        new(Color.FromArgb(0x30, 0x3B, 0x82, 0xF6), 0.0), new(Color.FromArgb(0x10, 0x8B, 0x5C, 0xF6), 0.55), new(Color.FromArgb(0x00, 0x3B, 0x82, 0xF6), 1.0),
    }));
    private static readonly Brush BarFill = Frozen(new LinearGradientBrush(Accent, Color.FromRgb(0x8B, 0x5C, 0xF6), 0));
    private static readonly Brush BarText = Solid(Color.FromRgb(0x7C, 0x88, 0xA0));
    private static readonly Brush CapDimple = Solid(Color.FromArgb(0x16, 0xFF, 0xFF, 0xFF));
    private static readonly Pen CapRing = Frozen(new Pen(Solid(Color.FromArgb(0x26, 0xFF, 0xFF, 0xFF)), 3));
    private static readonly Brush BridgeFill = Frozen(new LinearGradientBrush(new GradientStopCollection
    {
        new(Color.FromRgb(0x2A, 0x2D, 0x33), 0.0), new(Color.FromRgb(0x12, 0x13, 0x17), 0.14), new(Color.FromRgb(0x0A, 0x0B, 0x0E), 0.5),
        new(Color.FromRgb(0x12, 0x13, 0x17), 0.86), new(Color.FromRgb(0x24, 0x27, 0x2C), 1.0),
    }, 90));
    private static readonly Pen BridgeEdge = Frozen(new Pen(Solid(Color.FromRgb(0x05, 0x06, 0x08)), 3));
    private static readonly Pen BridgeGlint = Frozen(new Pen(Solid(Color.FromRgb(0x3A, 0x3E, 0x46)), 2));
    private static readonly Brush CradleFill = Solid(Color.FromArgb(0xB0, 0x07, 0x08, 0x0B));
    private static readonly Pen CradleEdge = Frozen(new Pen(Solid(Color.FromRgb(0x22, 0x26, 0x30)), 2) { DashStyle = new DashStyle(new double[] { 5, 6 }, 0) });

    private static readonly Point[] DpadUp = { new(279, 413), new(265, 437), new(293, 437) };
    private static readonly Point[] DpadDown = { new(265, 584), new(293, 584), new(279, 609) };
    private static readonly Point[] DpadLeft = { new(182, 511), new(206, 497), new(206, 525) };
    private static readonly Point[] DpadRight = { new(352, 497), new(377, 511), new(352, 525) };

    private readonly Dictionary<(string, bool, double), FormattedText> _labels = new();
    private readonly DispatcherTimer _pulse = new() { Interval = TimeSpan.FromMilliseconds(40) };

    public ControllerView()
    {
        _pulse.Tick += (_, _) => { if (LedMode == 2 && Connected && IsVisible) InvalidateVisual(); };
        Loaded += (_, _) => _pulse.Start();
        Unloaded += (_, _) => _pulse.Stop();
    }

    private bool Down(int bit) => (ButtonMask & (1 << bit)) != 0;

    // The art is large (gradients and curves).  Frozen drawings are shared with the render thread instead of being re-marshalled on every frame.
    private static readonly Dictionary<string, Drawing> DrawingCache = new();

    private static Drawing? Res(string key)
    {
        if (DrawingCache.TryGetValue(key, out var cached)) return cached;
        if (Application.Current?.TryFindResource(key) is not Drawing d) return null;
        if (!d.IsFrozen && d.CanFreeze) d.Freeze();
        return DrawingCache[key] = d;
    }

    private static readonly Dictionary<string, Geometry> GeometryCache = new();

    private static Geometry? Geo(string key)
    {
        if (GeometryCache.TryGetValue(key, out var cached)) return cached;
        if (Application.Current?.TryFindResource(key) is not Geometry g) return null;
        if (!g.IsFrozen && g.CanFreeze) g.Freeze();
        return GeometryCache[key] = g;
    }

    public static double PerfMs;
    public static int PerfCalls;

    protected override void OnRender(DrawingContext dc)
    {
        long t0 = System.Diagnostics.Stopwatch.GetTimestamp();
        RenderScene(dc);
        PerfMs += (System.Diagnostics.Stopwatch.GetTimestamp() - t0) * 1000.0 / System.Diagnostics.Stopwatch.Frequency;
        PerfCalls++;
    }

    private void RenderScene(DrawingContext dc)
    {
        double s = Math.Min(ActualWidth / SceneW, ActualHeight / SceneH);
        if (s <= 0) return;
        dc.PushTransform(new TranslateTransform((ActualWidth - SceneW * s) / 2, (ActualHeight - SceneH * s) / 2));
        dc.PushTransform(new ScaleTransform(s, s));
        dc.PushTransform(new TranslateTransform(0, -Top));
        dc.PushOpacity(Connected ? 1.0 : 0.5);

        dc.DrawEllipse(Backlight, null, new Point(SceneW / 2, 450), SceneW * 0.52, 560);
        if (IsV2Pro) V2Bridge(dc); else Bridge(dc);
        if (IsV2Pro) V2LeftHalf(dc); else LeftHalf(dc);
        dc.PushTransform(new TranslateTransform(RightX, 0));
        if (IsV2Pro) V2RightHalf(dc); else RightHalf(dc);
        dc.Pop();

        dc.Pop();
        dc.Pop();
        dc.Pop();
        dc.Pop();
    }

    // ------------------------------------------------------------------------------------------------ pieces

    private void Bridge(DrawingContext dc)
    {
        double x0 = HalfW - 24, w = Gap + 48;
        dc.DrawRoundedRectangle(BridgeFill, BridgeEdge, new Rect(x0, 34, w, 836), 18, 18);
        dc.DrawLine(BridgeGlint, new Point(x0 + 12, 122), new Point(x0 + w - 12, 122));
        dc.DrawLine(BridgeGlint, new Point(x0 + 12, 782), new Point(x0 + w - 12, 782));
        // The phone cradle between the two arms.
        dc.DrawRoundedRectangle(CradleFill, CradleEdge, new Rect(HalfW + 44, 150, Gap - 88, 590), 46, 46);
    }

    private void LeftHalf(DrawingContext dc)
    {
        if (Res("KishiLeftStatic") is { } body) dc.DrawDrawing(body);

        if (Down(8) && Geo("KishiLeftShoulderGeo") is { } sh) dc.DrawGeometry(PressFill, PressPen, sh);
        TriggerBar(dc, new Rect(128, -44, 232, 30), Input.L2, "L2");

        // D-pad directions.
        if (Down(4)) Arrow(dc, DpadUp);
        if (Down(5)) Arrow(dc, DpadDown);
        if (Down(6)) Arrow(dc, DpadLeft);
        if (Down(7)) Arrow(dc, DpadRight);

        if (Down(14)) Press(dc, new Point(384, 372), 26);   // left function
        if (Down(13)) Press(dc, new Point(384, 721), 29);   // home

        Cap(dc, "KishiLeftCap", new Point(239, 230), Input.Lx, Input.Ly, Down(10));
    }

    private void RightHalf(DrawingContext dc)
    {
        if (Res("KishiRightStatic") is { } body) dc.DrawDrawing(body);

        if (Down(9) && Geo("KishiRightShoulderGeo") is { } sh) dc.DrawGeometry(PressFill, PressPen, sh);
        TriggerBar(dc, new Rect(290, -44, 232, 30), Input.R2, "R2");

        if (Down(3)) Press(dc, new Point(381, 156), 44);    // Y
        if (Down(2)) Press(dc, new Point(299, 238), 44);    // X
        if (Down(1)) Press(dc, new Point(464, 238), 44);    // B
        if (Down(0)) Press(dc, new Point(381, 320), 44);    // A
        if (Down(12)) Press(dc, new Point(247, 721), 26);   // right function

        Led(dc, new Point(529, 372));
        Cap(dc, "KishiRightCap", new Point(338, 520), Input.Rx, Input.Ry, Down(11));
    }

    private void Cap(DrawingContext dc, string key, Point c, byte ax, byte ay, bool pressed)
    {
        double dx = (ax - 128) / 127.0 * CapTravel, dy = (ay - 128) / 127.0 * CapTravel;
        double len = Math.Sqrt(dx * dx + dy * dy);
        if (len > CapTravel) { dx *= CapTravel / len; dy *= CapTravel / len; }
        dc.PushTransform(new TranslateTransform(dx, dy));
        if (Res(key) is { } cap) dc.DrawDrawing(cap);
        dc.DrawEllipse(CapDimple, CapRing, c, 36, 36);
        if (pressed) dc.DrawEllipse(PressFill, PressPen, c, 57, 57);
        dc.Pop();
    }

    private static void Press(DrawingContext dc, Point c, double r)
    {
        dc.DrawEllipse(Halo, null, c, r * 1.55, r * 1.55);
        dc.DrawEllipse(PressFill, PressPen, c, r - 2, r - 2);
    }

    private static void Arrow(DrawingContext dc, Point[] tri)
    {
        var g = new StreamGeometry();
        using (var ctx = g.Open())
        {
            ctx.BeginFigure(tri[0], true, true);
            ctx.PolyLineTo(new[] { tri[1], tri[2] }, true, false);
        }
        g.Freeze();
        var cx = (tri[0].X + tri[1].X + tri[2].X) / 3;
        var cy = (tri[0].Y + tri[1].Y + tri[2].Y) / 3;
        dc.DrawEllipse(Halo, null, new Point(cx, cy), 46, 46);
        dc.DrawGeometry(Solid(AccentHi), null, g);
    }

    private void TriggerBar(DrawingContext dc, Rect r, int value, string label)
    {
        dc.DrawRoundedRectangle(Draw.B(this, "FieldBrush", Brushes.Black), new Pen(Draw.B(this, "StrokeHiBrush", Brushes.Gray), 2), r, r.Height / 2, r.Height / 2);
        double w = Math.Max(0, Math.Min(1, value / 255.0)) * r.Width;
        if (w > 1)
        {
            dc.PushClip(new RectangleGeometry(r, r.Height / 2, r.Height / 2));
            dc.DrawRectangle(BarFill, null, new Rect(r.X, r.Y, w, r.Height));
            dc.Pop();
        }
        bool light = w > r.Width * 0.5;
        double dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        if (!_labels.TryGetValue((label, light, dpi), out var ft)) _labels[(label, light, dpi)] = ft = Draw.Text(this, label, 21, light ? Brushes.White : BarText);
        dc.DrawText(ft, new Point(r.X + r.Width / 2 - ft.Width / 2, r.Y + r.Height / 2 - ft.Height / 2));
    }

    private void Led(DrawingContext dc, Point c)
    {
        double lit = LedMode switch
        {
            0 => 0.0,
            2 => 0.18 + 0.82 * (0.5 - 0.5 * Math.Cos(Environment.TickCount64 / 3000.0 * 2 * Math.PI)),
            _ => 1.0,
        };
        dc.DrawEllipse(Solid(Color.FromRgb(0x08, 0x34, 0x7A)), new Pen(Solid(Color.FromRgb(0x08, 0x09, 0x0B)), 2), c, 10, 10);
        if (!Connected || lit <= 0.01) return;
        byte a = (byte)(lit * 255);
        dc.DrawEllipse(new RadialGradientBrush(new GradientStopCollection
        {
            new(Color.FromArgb((byte)(lit * 150), 0x4D, 0xA3, 0xFF), 0.0), new(Color.FromArgb(0, 0x4D, 0xA3, 0xFF), 1.0),
        }), null, c, 38, 38);
        dc.DrawEllipse(new SolidColorBrush(Color.FromArgb(a, 0x4D, 0xA3, 0xFF)), null, c, 10, 10);
    }
}
