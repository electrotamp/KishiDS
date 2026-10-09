using System.Windows;
using System.Windows.Media;

namespace KishiDS.Controls;

/// <summary>
/// The Kishi V2 Pro, drawn in code on the same scene as the V1 (two 630 x 890 halves and the bridge), traced from
/// Razer's product photo: narrow grips with a rounded outer edge.  Left: stick on top, View (three dots) beside it,
/// the D-pad in a round recess, Share below.  Right: A/B/X/Y on top, the LED, the stick, Nexus and Menu below.  Two
/// slots on each inner edge; L1/R1 with M1/M2 beside them on top.  Button bits as the firmware sends them (kishi_io.h
/// order, then 15 Share, 16 M1, 17 M2).
/// </summary>
public sealed partial class ControllerView
{
    private static readonly Brush V2BodyFill = Frozen(new LinearGradientBrush(new GradientStopCollection
    {
        new(Color.FromRgb(0x34, 0x37, 0x3E), 0.0), new(Color.FromRgb(0x22, 0x24, 0x29), 0.35), new(Color.FromRgb(0x15, 0x16, 0x1A), 1.0),
    }, new Point(0.3, 0), new Point(0.7, 1)));
    private static readonly Brush V2Sheen = Frozen(new LinearGradientBrush(new GradientStopCollection
    {
        new(Color.FromArgb(0x00, 0xFF, 0xFF, 0xFF), 0.0), new(Color.FromArgb(0x16, 0xFF, 0xFF, 0xFF), 0.5), new(Color.FromArgb(0x00, 0xFF, 0xFF, 0xFF), 1.0),
    }, 0));
    private static readonly Pen V2Edge = Frozen(new Pen(Solid(Color.FromRgb(0x05, 0x06, 0x08)), 4));
    private static readonly Pen V2Rim = Frozen(new Pen(Solid(Color.FromArgb(0x38, 0xFF, 0xFF, 0xFF)), 2.5));
    private static readonly Brush V2Recess = Frozen(new RadialGradientBrush(new GradientStopCollection
    {
        new(Color.FromRgb(0x12, 0x13, 0x16), 0.0), new(Color.FromRgb(0x16, 0x17, 0x1B), 0.8), new(Color.FromRgb(0x0A, 0x0B, 0x0D), 1.0),
    }));
    private static readonly Pen V2RecessEdge = Frozen(new Pen(Solid(Color.FromArgb(0x24, 0xFF, 0xFF, 0xFF)), 2));
    private static readonly Brush V2Gloss = Frozen(new RadialGradientBrush(new GradientStopCollection
    {
        new(Color.FromRgb(0x3C, 0x3E, 0x44), 0.0), new(Color.FromRgb(0x12, 0x13, 0x16), 0.7), new(Color.FromRgb(0x05, 0x05, 0x06), 1.0),
    }) { GradientOrigin = new Point(0.38, 0.3) });
    private static readonly Pen V2GlossEdge = Frozen(new Pen(Solid(Color.FromRgb(0x02, 0x02, 0x03)), 3));
    private static readonly Brush V2Highlight = Solid(Color.FromArgb(0x30, 0xFF, 0xFF, 0xFF));
    private static readonly Brush V2Cap = Frozen(new RadialGradientBrush(new GradientStopCollection
    {
        new(Color.FromRgb(0x2E, 0x30, 0x36), 0.0), new(Color.FromRgb(0x24, 0x26, 0x2B), 0.75), new(Color.FromRgb(0x3A, 0x3D, 0x44), 0.9), new(Color.FromRgb(0x10, 0x11, 0x14), 1.0),
    }) { GradientOrigin = new Point(0.4, 0.35) });
    private static readonly Brush V2White = Solid(Color.FromRgb(0xE8, 0xEA, 0xEE));
    private static readonly Pen V2WhitePen = Frozen(new Pen(Solid(Color.FromRgb(0xE8, 0xEA, 0xEE)), 4) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round });
    private static readonly Brush V2Slot = Solid(Color.FromRgb(0x08, 0x09, 0x0B));
    private static readonly Brush V2Bumper = Frozen(new LinearGradientBrush(Color.FromRgb(0x3A, 0x3D, 0x44), Color.FromRgb(0x1C, 0x1E, 0x22), 90));
    private static readonly Brush V2Label = Solid(Color.FromRgb(0x9A, 0xA2, 0xB2));

    // The left grip (inner edge x = 630); the right one is its mirror image (x -> 630 - x).
    private static readonly Geometry V2Grip = Frozen(Geometry.Parse(
        "F1 M 384.8,32.1 C 373.9,32.8 370.8,34.2 363.2,36.3 C 355.6,38.4 346.4,41.5 339.3,44.6 C 332.1,47.8 324.9,51.9 " +
        "320.3,55.1 C 315.6,58.2 313.6,60.7 311.5,63.4 C 309.3,66.2 308.5,68.7 307.5,71.8 C 306.5,74.9 306.0,77.7 305.6" +
        ",82.2 C 305.2,86.8 304.8,93.7 305.1,99.0 C 305.4,104.2 310.3,107.3 307.3,113.6 C 304.3,119.9 291.8,130.7 287.0" +
        ",136.6 C 282.2,142.5 281.7,143.6 278.5,149.1 C 275.3,154.7 269.6,165.8 267.9,170.0 C 266.2,174.2 269.1,172.5 2" +
        "68.4,174.2 C 267.7,176.0 265.5,176.3 263.8,180.5 C 262.0,184.7 260.6,187.4 257.9,199.3 C 255.2,211.1 252.4,216" +
        ".4 247.4,251.5 C 242.5,286.7 232.0,378.3 228.2,410.4 C 224.4,442.4 225.0,437.9 224.8,443.8 C 224.5,449.7 226.7" +
        ",444.9 226.6,445.9 C 226.5,447.0 227.1,421.2 224.0,450.1 C 220.8,479.0 210.5,587.7 207.5,619.4 C 204.5,651.1 2" +
        "05.9,636.4 206.0,640.3 C 206.0,644.1 207.9,641.7 207.8,642.4 C 207.7,643.1 206.1,638.9 205.4,644.5 C 204.7,650" +
        ".0 203.7,670.2 203.7,675.8 C 203.8,681.4 205.7,676.9 205.7,677.9 C 205.7,678.9 203.9,678.9 203.5,682.1 C 203.2" +
        ",685.2 203.2,693.9 203.5,696.7 C 203.9,699.5 205.7,698.1 205.7,698.8 C 205.7,699.5 203.8,696.4 203.7,700.9 C 2" +
        "03.7,705.4 203.8,715.2 205.5,726.0 C 207.2,736.8 210.4,754.2 213.8,765.7 C 217.2,777.2 221.0,785.5 226.1,794.9" +
        " C 231.2,804.3 237.4,813.7 244.6,822.1 C 251.8,830.5 260.5,838.5 269.3,845.1 C 278.0,851.7 286.6,856.9 297.2,8" +
        "61.8 C 307.8,866.7 320.1,871.2 332.9,874.4 C 345.7,877.5 333.1,879.6 374.2,880.6 C 415.2,881.7 543.4,881.3 579" +
        ".3,880.6 C 615.3,879.9 586.9,878.5 589.9,876.4 C 592.9,874.4 596.4,869.8 597.5,868.1 C 598.6,866.3 596.2,866.7" +
        " 596.5,866.0 C 596.8,865.3 598.7,869.8 599.2,863.9 C 599.8,858.0 599.1,836.7 600.0,830.5 C 600.9,824.2 600.9,8" +
        "27.3 604.8,826.3 C 608.6,825.2 618.9,825.2 622.9,824.2 C 626.9,823.2 627.5,821.4 628.7,820.0 C 629.9,818.6 629" +
        ".8,929.7 630.0,815.8 C 630.1,701.9 630.9,250.8 629.7,136.6 C 628.6,22.3 627.1,131.7 623.0,130.3 C 618.8,128.9 " +
        "608.6,129.3 604.7,128.2 C 600.9,127.2 600.9,130.7 599.9,124.0 C 598.8,117.4 600.2,96.2 598.5,88.5 C 596.8,80.9" +
        " 593.0,80.5 589.7,78.1 C 586.3,75.6 588.3,74.9 578.5,73.9 C 568.6,72.8 538.7,76.7 530.7,71.8 C 522.7,66.9 530." +
        "7,49.9 530.3,44.6 C 530.0,39.4 529.9,41.8 528.8,40.5 C 527.6,39.1 539.4,37.3 523.5,36.3 C 507.7,35.2 449.4,34." +
        "9 433.5,34.2 C 417.7,33.5 436.6,32.4 428.5,32.1 C 420.4,31.7 395.6,31.4 384.8,32.1 Z"));
    private static readonly Transform Mirror = Frozen(new MatrixTransform(-1, 0, 0, 1, HalfW, 0));

    private void V2LeftHalf(DrawingContext dc)
    {
        TriggerBar(dc, new Rect(305, -58, 220, 30), Input.L2, "L2");
        Grip(dc, false);
        TopButton(dc, new Rect(305, 30, 120, 70), "L1", Down(8));
        TopButton(dc, new Rect(437, 34, 92, 38), "M1", Down(16));
        Slots(dc, 592);

        V2Stick(dc, new Point(434, 252), Input.Lx, Input.Ly, Down(10));
        SmallKey(dc, new Point(538, 354), Down(14), IconDots);
        V2Dpad(dc, new Point(444, 513));
        SmallKey(dc, new Point(538, 749), Down(15), IconCapture);
    }

    private void V2RightHalf(DrawingContext dc)
    {
        TriggerBar(dc, new Rect(105, -58, 220, 30), Input.R2, "R2");
        Grip(dc, true);
        TopButton(dc, new Rect(205, 30, 120, 70), "R1", Down(9));
        TopButton(dc, new Rect(101, 34, 92, 38), "M2", Down(17));
        Slots(dc, 38);

        Face(dc, new Point(194, 178), "Y", Down(3));
        Face(dc, new Point(116, 258), "X", Down(2));
        Face(dc, new Point(272, 258), "B", Down(1));
        Face(dc, new Point(194, 335), "A", Down(0));
        V2Led(dc, new Point(294, 392));
        V2Stick(dc, new Point(194, 513), Input.Rx, Input.Ry, Down(11));
        SmallKey(dc, new Point(134, 657), Down(13), IconNexus);
        SmallKey(dc, new Point(113, 738), Down(12), IconMenu);
    }

    private static readonly Brush V2Plate = Frozen(new LinearGradientBrush(new GradientStopCollection
    {
        new(Color.FromRgb(0x24, 0x26, 0x2B), 0.0), new(Color.FromRgb(0x17, 0x18, 0x1C), 0.5), new(Color.FromRgb(0x0E, 0x0F, 0x12), 1.0),
    }, 90));
    private static readonly Pen V2Rib = Frozen(new Pen(Solid(Color.FromRgb(0x0A, 0x0B, 0x0D)), 3));
    private static readonly Brush V2Metal = Frozen(new LinearGradientBrush(Color.FromRgb(0x6A, 0x6E, 0x76), Color.FromRgb(0x30, 0x33, 0x39), 90));

    /// <summary>The telescopic bridge between the grips, with its ribbed ends and the USB-C plug on the right.</summary>
    private static void V2Bridge(DrawingContext dc)
    {
        var plate = new Rect(HalfW - 12, 318, Gap + 24, 318);
        dc.DrawRoundedRectangle(V2Plate, V2Edge, plate, 10, 10);
        dc.DrawLine(V2Rib, new Point(plate.X + plate.Width / 2, plate.Y + 6), new Point(plate.X + plate.Width / 2, plate.Bottom - 6));
        foreach (double x0 in new[] { HalfW + 12, RightX - 102 })
        {
            dc.DrawRoundedRectangle(Solid(Color.FromRgb(0x13, 0x14, 0x17)), null, new Rect(x0, 340, 90, 274), 8, 8);
            for (double y = 356; y < 606; y += 14) dc.DrawLine(V2Rib, new Point(x0 + 8, y), new Point(x0 + 82, y));
        }
        dc.DrawRoundedRectangle(V2Metal, V2Edge, new Rect(RightX - 44, 446, 44, 52), 6, 6);
        dc.DrawRoundedRectangle(Solid(Color.FromRgb(0x10, 0x11, 0x14)), null, new Rect(RightX - 36, 462, 30, 20), 4, 4);
    }

    private static void Grip(DrawingContext dc, bool mirror)
    {
        if (mirror) dc.PushTransform(Mirror);
        dc.DrawGeometry(V2BodyFill, V2Edge, V2Grip);
        dc.PushClip(V2Grip);
        dc.DrawRectangle(V2Sheen, null, new Rect(200, 40, 220, 860));
        dc.Pop();
        dc.DrawGeometry(null, V2Rim, V2Grip);
        if (mirror) dc.Pop();
    }

    private static void Slots(DrawingContext dc, double x)
    {
        dc.DrawRoundedRectangle(V2Slot, null, new Rect(x - 6, 220, 12, 142), 6, 6);
        dc.DrawRoundedRectangle(V2Slot, null, new Rect(x - 6, 592, 12, 136), 6, 6);
    }

    /// <summary>A shoulder or top button: part of the traced outline; lit when pressed, labelled above.</summary>
    private void TopButton(DrawingContext dc, Rect r, string label, bool down)
    {
        if (down)
        {
            dc.DrawEllipse(Halo, null, new Point(r.X + r.Width / 2, r.Y + r.Height / 2), r.Width * 0.7, r.Width * 0.7);
            dc.DrawRoundedRectangle(PressFill, PressPen, r, 16, 16);
        }
        Draw.CenteredText(dc, this, label, new Point(r.X + r.Width / 2, r.Y + r.Height / 2), 22, V2Label);
    }

    private void Face(DrawingContext dc, Point c, string letter, bool down)
    {
        Gloss(dc, c, 34);
        if (down) Press(dc, c, 36);
        Draw.CenteredText(dc, this, letter, c, 34, V2White);
    }

    private static void Gloss(DrawingContext dc, Point c, double r)
    {
        dc.DrawEllipse(V2Gloss, V2GlossEdge, c, r, r);
        dc.DrawEllipse(V2Highlight, null, new Point(c.X - r * 0.25, c.Y - r * 0.42), r * 0.45, r * 0.22);
    }

    private static void SmallKey(DrawingContext dc, Point c, bool down, Action<DrawingContext, Point> icon)
    {
        Gloss(dc, c, 31);
        if (down) Press(dc, c, 33);
        icon(dc, c);
    }

    private static void IconDots(DrawingContext dc, Point c)
    {
        for (int i = -1; i <= 1; i++) dc.DrawEllipse(V2White, null, new Point(c.X + i * 11, c.Y), 4, 4);
    }

    private static void IconCapture(DrawingContext dc, Point c)
    {
        dc.DrawEllipse(null, V2WhitePen, c, 14, 14);
        dc.DrawEllipse(V2White, null, c, 5, 5);
    }

    private static void IconNexus(DrawingContext dc, Point c)
    {
        // A ring open at the top right with a short bar into it, as on the button.
        var g = new StreamGeometry();
        using (var ctx = g.Open())
        {
            ctx.BeginFigure(new Point(c.X + 14, c.Y - 3), false, false);
            ctx.ArcTo(new Point(c.X + 4, c.Y - 14), new Size(14, 14), 0, true, SweepDirection.Clockwise, true, false);
        }
        g.Freeze();
        dc.DrawGeometry(null, V2WhitePen, g);
        dc.DrawLine(V2WhitePen, new Point(c.X + 2, c.Y), new Point(c.X + 14, c.Y));
    }

    private static void IconMenu(DrawingContext dc, Point c)
    {
        for (int i = -1; i <= 1; i++) dc.DrawLine(V2WhitePen, new Point(c.X - 12, c.Y + i * 8), new Point(c.X + 12, c.Y + i * 8));
    }

    private static void V2Stick(DrawingContext dc, Point c, byte ax, byte ay, bool pressed)
    {
        dc.DrawEllipse(V2Recess, V2RecessEdge, c, 78, 78);
        double dx = (ax - 128) / 127.0 * CapTravel, dy = (ay - 128) / 127.0 * CapTravel;
        double len = Math.Sqrt(dx * dx + dy * dy);
        if (len > CapTravel) { dx *= CapTravel / len; dy *= CapTravel / len; }
        var cap = new Point(c.X + dx, c.Y + dy);
        dc.DrawEllipse(Solid(Color.FromArgb(0x60, 0, 0, 0)), null, new Point(cap.X + 4, cap.Y + 8), 60, 60);
        dc.DrawEllipse(V2Cap, V2GlossEdge, cap, 58, 58);
        dc.DrawEllipse(null, CapRing, cap, 42, 42);
        if (pressed) dc.DrawEllipse(PressFill, PressPen, cap, 60, 60);
    }

    private void V2Dpad(DrawingContext dc, Point c)
    {
        dc.DrawEllipse(V2Recess, V2RecessEdge, c, 119, 119);
        const double arm = 106, half = 37;
        var cross = new CombinedGeometry(GeometryCombineMode.Union,
            new RectangleGeometry(new Rect(c.X - arm, c.Y - half, arm * 2, half * 2), 16, 16),
            new RectangleGeometry(new Rect(c.X - half, c.Y - arm, half * 2, arm * 2), 16, 16));
        cross.Freeze();
        dc.PushTransform(new TranslateTransform(3, 7));
        dc.DrawGeometry(Solid(Color.FromArgb(0x70, 0, 0, 0)), null, cross);
        dc.Pop();
        dc.DrawGeometry(V2Cap, V2GlossEdge, cross);
        dc.DrawEllipse(Solid(Color.FromArgb(0x50, 0, 0, 0)), null, c, 16, 16);
        (int bit, double ox, double oy)[] dirs = { (4, 0, -1), (5, 0, 1), (6, -1, 0), (7, 1, 0) };
        foreach (var (bit, ox, oy) in dirs)
        {
            var tip = new Point(c.X + ox * 82, c.Y + oy * 82);
            var b1 = new Point(c.X + ox * 60 - oy * 13, c.Y + oy * 60 - ox * 13);
            var b2 = new Point(c.X + ox * 60 + oy * 13, c.Y + oy * 60 + ox * 13);
            if (Down(bit)) { Arrow(dc, new[] { tip, b1, b2 }); continue; }
            var g = new StreamGeometry();
            using (var ctx = g.Open()) { ctx.BeginFigure(tip, true, true); ctx.PolyLineTo(new[] { b1, b2 }, true, false); }
            g.Freeze();
            dc.DrawGeometry(Solid(Color.FromArgb(0x90, 0x05, 0x05, 0x06)), null, g);
        }
    }

    private void V2Led(DrawingContext dc, Point c)
    {
        double lit = LedMode switch
        {
            0 => 0.0,
            2 => 0.18 + 0.82 * (0.5 - 0.5 * Math.Cos(Environment.TickCount64 / 3000.0 * 2 * Math.PI)),
            _ => 1.0,
        };
        var col = LedColor;
        dc.DrawEllipse(Solid(Color.FromRgb(0x3A, 0x3C, 0x42)), new Pen(Solid(Color.FromRgb(0x08, 0x09, 0x0B)), 2), c, 8, 8);
        if (!Connected || lit <= 0.01) return;
        dc.DrawEllipse(new RadialGradientBrush(new GradientStopCollection
        {
            new(Color.FromArgb((byte)(lit * 170), col.R, col.G, col.B), 0.0), new(Color.FromArgb(0, col.R, col.G, col.B), 1.0),
        }), null, c, 36, 36);
        dc.DrawEllipse(new SolidColorBrush(Color.FromArgb((byte)(lit * 255), col.R, col.G, col.B)), null, c, 8, 8);
    }
}
