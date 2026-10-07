package com.electrotamp.kishids.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.electrotamp.kishids.core.LiveInput;

import java.util.ArrayList;
import java.util.List;

/** The custom-drawn controls and live visuals: icons, slider, switch, segmented control, stick/curve/trigger/LED views, progress ring and the controller art. */
public final class Widgets {
    private Widgets() {}

    /** Views that animate on their own (breathing LED, spinning ring) are ticked once per frame by the activity. */
    public interface Ticking { void tick(); }

    private static final List<Ticking> TICKERS = new ArrayList<>();

    static void register(Ticking t) { if (!TICKERS.contains(t)) TICKERS.add(t); }

    static void unregister(Ticking t) { TICKERS.remove(t); }

    public static void tickAll() { for (int i = 0; i < TICKERS.size(); i++) TICKERS.get(i).tick(); }

    private static Paint paint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Ui.medium);
        return p;
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    // ================================================================ icon

    public static final class IconView extends View {
        private final Paint p = paint();
        private String name;
        private final float sizeDp;

        public IconView(Context c, String name, float sizeDp, int color) {
            super(c);
            this.name = name;
            this.sizeDp = sizeDp;
            p.setColor(color);
        }

        public void set(String name, int color) { this.name = name; p.setColor(color); invalidate(); }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(Ui.dp(sizeDp), Ui.dp(sizeDp)); }

        @Override protected void onDraw(Canvas c) { Icons.draw(c, name, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()), p); }
    }

    /** A round touch target (40 dp) with an icon and ripple. */
    public static final class IconButton extends View {
        private final Paint p = paint();
        private String name;
        private int color;

        public IconButton(Context c, String name, int color, Runnable onClick) {
            super(c);
            this.name = name;
            this.color = color;
            setClickable(true);
            setBackground(Ui.ripple(new android.graphics.drawable.ColorDrawable(0), 20));
            if (onClick != null) setOnClickListener(v -> onClick.run());
        }

        public void set(String name, int color) { this.name = name; this.color = color; invalidate(); }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(Ui.dp(44), Ui.dp(44)); }

        @Override protected void onDraw(Canvas c) {
            p.setColor(color);
            Icons.draw(c, name, getWidth() / 2f, getHeight() / 2f, Ui.dpf(22), p);
        }
    }

    // ================================================================ slider

    public interface DoubleListener { void on(double v); }

    public static final class SliderView extends View {
        private final Paint p = paint();
        private double min, max, step, value;
        private boolean dragging;
        public DoubleListener listener;

        public SliderView(Context c, double min, double max, double step) {
            super(c);
            this.min = min; this.max = max; this.step = step; this.value = min;
        }

        public boolean isDragging() { return dragging; }

        public void setValue(double v) {
            if (dragging || v == value) return;
            value = v;
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(40)); }

        private float pad() { return Ui.dpf(14); }

        @Override protected void onDraw(Canvas c) {
            float l = pad(), r = getWidth() - pad(), cy = getHeight() / 2f, th = Ui.dpf(6);
            RectF track = new RectF(l, cy - th / 2, r, cy + th / 2);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.field);
            c.drawRoundRect(track, th / 2, th / 2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(1));
            p.setColor(Theme.stroke);
            c.drawRoundRect(track, th / 2, th / 2, p);
            float f = (float) ((value - min) / (max - min));
            float x = l + (r - l) * clamp(f, 0, 1);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.accent);
            c.drawRoundRect(new RectF(l, cy - th / 2, Math.max(x, l + th), cy + th / 2), th / 2, th / 2, p);
            if (dragging) {
                p.setColor(Theme.alpha(Theme.accent, 60));
                c.drawCircle(x, cy, Ui.dpf(19), p);
            }
            p.setColor(Color.WHITE);
            c.drawCircle(x, cy, Ui.dpf(11), p);
            p.setColor(Theme.accent);
            c.drawCircle(x, cy, Ui.dpf(4.5f), p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    update(e.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    update(e.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        private void update(float x) {
            float l = pad(), r = getWidth() - pad();
            double f = clamp((x - l) / (r - l), 0, 1);
            double v = min + f * (max - min);
            v = Math.round(v / step) * step;
            v = Math.max(min, Math.min(max, v));
            if (v != value) {
                value = v;
                invalidate();
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                if (listener != null) listener.on(v);
            } else invalidate();
        }
    }

    // ================================================================ switch

    public interface BoolListener { void on(boolean v); }

    public static final class ToggleView extends View {
        private final Paint p = paint();
        private boolean on;
        private float pos;      // 0 off .. 1 on, animated
        private ValueAnimator anim;
        public BoolListener listener;

        public ToggleView(Context c) { super(c); }

        public boolean isOn() { return on; }

        /** Set without notifying. */
        public void setOn(boolean v) {
            if (v == on) return;
            on = v;
            animateTo(v ? 1 : 0);
        }

        public void toggle() {
            on = !on;
            animateTo(on ? 1 : 0);
            if (listener != null) listener.on(on);
        }

        private void animateTo(float t) {
            if (anim != null) anim.cancel();
            if (!isShown()) { pos = t; invalidate(); return; }
            anim = ValueAnimator.ofFloat(pos, t);
            anim.setDuration(140);
            anim.addUpdateListener(a -> { pos = (float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(Ui.dp(50), Ui.dp(30)); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), th = Ui.dpf(26), tw = Ui.dpf(46);
            RectF r = new RectF((w - tw) / 2, (h - th) / 2, (w + tw) / 2, (h + th) / 2);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.mix(Theme.toggleOffTrack, Theme.accent, pos));
            c.drawRoundRect(r, th / 2, th / 2, p);
            if (pos < 1) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(1));
                p.setColor(Theme.alpha(Theme.toggleOffBorder, (int) (255 * (1 - pos))));
                c.drawRoundRect(r, th / 2, th / 2, p);
            }
            float rad = Ui.dpf(9.5f), x = r.left + th / 2 + (tw - th) * pos;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.mix(Theme.thumbOff, Color.WHITE, pos));
            c.drawCircle(x, h / 2, rad, p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) { toggle(); performClick(); }
            return e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_UP;
        }
    }

    // ================================================================ segmented control

    public interface IntListener { void on(int v); }

    public static final class SegmentedView extends View {
        private final Paint p = paint();
        private final String[] options;
        private int selected;
        public IntListener listener;

        public SegmentedView(Context c, String[] options) {
            super(c);
            this.options = options;
        }

        public void setSelected(int i) { if (i != selected) { selected = i; invalidate(); } }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(48)); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), pad = Ui.dpf(4);
            RectF all = new RectF(0, 0, w, h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.field);
            c.drawRoundRect(all, Ui.dpf(14), Ui.dpf(14), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(1));
            p.setColor(Theme.stroke);
            c.drawRoundRect(new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), Ui.dpf(14), Ui.dpf(14), p);
            float seg = (w - 2 * pad) / options.length;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.accent);
            c.drawRoundRect(new RectF(pad + seg * selected, pad, pad + seg * (selected + 1), h - pad), Ui.dpf(10), Ui.dpf(10), p);
            for (int i = 0; i < options.length; i++) {
                float size = Ui.sp(13.5f);
                p.setTextSize(size);
                while (p.measureText(options[i]) > seg - Ui.dpf(10) && size > Ui.sp(10)) { size -= Ui.sp(0.5f); p.setTextSize(size); }
                p.setColor(i == selected ? Color.WHITE : Theme.textDim);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(options[i], pad + seg * (i + 0.5f), h / 2f - (p.descent() + p.ascent()) / 2, p);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                float pad = Ui.dpf(4), seg = (getWidth() - 2 * pad) / options.length;
                int i = (int) clamp((e.getX() - pad) / seg, 0, options.length - 1);
                if (i != selected) {
                    selected = i;
                    invalidate();
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    if (listener != null) listener.on(i);
                }
                performClick();
            }
            return e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_UP;
        }
    }

    // ================================================================ stick preview

    /** A stick's range: deadzone, full-scale ring and the live thumb position. */
    public static final class StickPreview extends View {
        private final Paint p = paint();
        private float dead = 8, outer = 90;
        private int x = 128, y = 128;

        public StickPreview(Context c) { super(c); }

        public void set(float dead, float outer, int x, int y) {
            if (dead == this.dead && outer == this.outer && x == this.x && y == this.y) return;
            this.dead = dead; this.outer = outer; this.x = x; this.y = y;
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) {
            int s = Math.min(MeasureSpec.getSize(w), Ui.dp(180));
            setMeasuredDimension(s, s);
        }

        @Override protected void onDraw(Canvas c) {
            float size = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f, cy = getHeight() / 2f, R = size / 2 - Ui.dpf(4);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.field);
            c.drawCircle(cx, cy, R, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(1.5f));
            p.setColor(Theme.strokeHi);
            c.drawCircle(cx, cy, R, p);
            c.drawLine(cx - R, cy, cx + R, cy, p);
            c.drawLine(cx, cy - R, cx, cy + R, p);
            float d = dead / 100f, full = d + (1 - d) * outer / 100f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(0x40, 0xF8, 0x71, 0x71));
            c.drawCircle(cx, cy, R * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Color.argb(0xAA, 0xF8, 0x71, 0x71));
            c.drawCircle(cx, cy, R * d, p);
            p.setColor(Color.argb(0xAA, 0x34, 0xD3, 0x99));
            p.setPathEffect(new DashPathEffect(new float[] { Ui.dpf(3), Ui.dpf(3) }, 0));
            c.drawCircle(cx, cy, R * full, p);
            p.setPathEffect(null);
            float dx = (x - 128) / 127f * R, dy = (y - 128) / 127f * R, len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > R) { dx *= R / len; dy *= R / len; }
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(0x30, 0x3B, 0x82, 0xF6));
            c.drawCircle(cx + dx, cy + dy, Ui.dpf(15), p);
            p.setColor(Theme.accent);
            c.drawCircle(cx + dx, cy + dy, Ui.dpf(8), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(2));
            p.setColor(Color.WHITE);
            c.drawCircle(cx + dx, cy + dy, Ui.dpf(8), p);
        }
    }

    // ================================================================ response curve

    /** Response curve: stick travel (x) against reported value (y). */
    public static final class CurveGraph extends View {
        private final Paint p = paint();
        private float dead = 8, outer = 90;
        private int curve;

        public CurveGraph(Context c) { super(c); }

        public void set(float dead, float outer, int curve) {
            if (dead == this.dead && outer == this.outer && curve == this.curve) return;
            this.dead = dead; this.outer = outer; this.curve = curve;
            invalidate();
        }

        /** Same maths as the firmware's stick_scale (normalised 0..1). */
        public static double response(double x, double dead, double outer, int curve) {
            double d = dead / 100.0, span = (1 - d) * outer / 100.0;
            double v = x <= d ? 0 : Math.min(1, (x - d) / Math.max(span, 1e-6));
            return curve == 1 ? v * v : curve == 2 ? Math.min(1, 2 * v - v * v) : v;
        }

        @Override protected void onMeasure(int w, int h) {
            int s = Math.min(MeasureSpec.getSize(w), Ui.dp(180));
            setMeasuredDimension(s, s);
        }

        @Override protected void onDraw(Canvas c) {
            RectF r = new RectF(Ui.dpf(4), Ui.dpf(4), getWidth() - Ui.dpf(4), getHeight() - Ui.dpf(4));
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.field);
            c.drawRoundRect(r, Ui.dpf(12), Ui.dpf(12), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(1));
            p.setColor(Theme.stroke);
            c.drawRoundRect(r, Ui.dpf(12), Ui.dpf(12), p);
            for (int i = 1; i < 4; i++) {
                c.drawLine(r.left + r.width() * i / 4, r.top + Ui.dpf(6), r.left + r.width() * i / 4, r.bottom - Ui.dpf(6), p);
                c.drawLine(r.left + Ui.dpf(6), r.top + r.height() * i / 4, r.right - Ui.dpf(6), r.top + r.height() * i / 4, p);
            }
            RectF in = new RectF(r.left + Ui.dpf(10), r.top + Ui.dpf(10), r.right - Ui.dpf(10), r.bottom - Ui.dpf(10));
            Path path = new Path();
            for (int i = 0; i <= 100; i++) {
                double x = i / 100.0;
                float px = in.left + (float) x * in.width(), py = in.bottom - (float) response(x, dead, outer, curve) * in.height();
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            p.setColor(Theme.accent);
            p.setStrokeWidth(Ui.dpf(3));
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawPath(path, p);
        }
    }

    // ================================================================ trigger bar

    /** Horizontal analogue bar with the digital-press threshold marked. */
    public static final class TriggerBar extends View {
        private final Paint p = paint();
        private final String label;
        private int value, threshold = 10;

        public TriggerBar(Context c, String label) { super(c); this.label = label; }

        public void set(int value, int threshold) {
            if (value == this.value && threshold == this.threshold) return;
            this.value = value; this.threshold = threshold;
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(40)); }

        @Override protected void onDraw(Canvas c) {
            float h = Ui.dpf(30), top = (getHeight() - h) / 2;
            RectF bar = new RectF(Ui.dpf(40), top, getWidth(), top + h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.field);
            c.drawRoundRect(bar, h / 2, h / 2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(1));
            p.setColor(Theme.stroke);
            c.drawRoundRect(bar, h / 2, h / 2, p);
            float w = clamp(value / 255f, 0, 1) * bar.width();
            if (w > 1) {
                c.save();
                Path clip = new Path();
                clip.addRoundRect(bar, h / 2, h / 2, Path.Direction.CW);
                c.clipPath(clip);
                p.setStyle(Paint.Style.FILL);
                p.setColor(value > threshold ? Theme.accent : Theme.textFaint);
                c.drawRect(bar.left, bar.top, bar.left + w, bar.bottom, p);
                c.restore();
            }
            float tx = bar.left + bar.width() * threshold / 255f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(2));
            p.setColor(Color.rgb(0xFB, 0xBF, 0x24));
            c.drawLine(tx, bar.top - Ui.dpf(3), tx, bar.bottom + Ui.dpf(3), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.textDim);
            p.setTextSize(Ui.sp(14));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(label, Ui.dpf(17), getHeight() / 2f - (p.descent() + p.ascent()) / 2, p);
        }
    }

    // ================================================================ progress ring

    /** Circular progress for the flashing step.  State: 0 normal, 1 success, 2 failure, 3 idle. */
    public static final class RingProgress extends View implements Ticking {
        private final Paint p = paint();
        private double value;
        private boolean spinning;
        private int state = 3;
        private float spin;

        public RingProgress(Context c) { super(c); }

        public void set(double value, boolean spinning, int state) {
            if (value == this.value && spinning == this.spinning && state == this.state) return;
            this.value = value; this.spinning = spinning; this.state = state;
            invalidate();
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); register(this); }

        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); unregister(this); }

        @Override public void tick() {
            if (!spinning || !isShown()) return;
            spin = (spin + 4) % 360;
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) {
            int s = Math.min(MeasureSpec.getSize(w), Ui.dp(140));
            setMeasuredDimension(s, s);
        }

        @Override protected void onDraw(Canvas c) {
            float size = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f, R = size / 2 - Ui.dpf(8);
            int color = state == 3 ? Theme.textFaint : state == 1 ? Theme.good : state == 2 ? Theme.bad : Theme.accent;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(8));
            p.setColor(Theme.stroke);
            c.drawCircle(cx, cy, R, p);
            float sweep = spinning ? 90 : (float) Math.max(0, Math.min(1, value)) * 359.9f;
            if (state == 1 || state == 2) sweep = 359.9f; else if (state == 3) sweep = 0;
            float start = spinning ? spin - 90 : -90;
            if (sweep > 0.5f) {
                p.setColor(color);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawArc(new RectF(cx - R, cy - R, cx + R, cy + R), start, sweep, false, p);
            }
            if (state == 1) Icons.draw(c, Icons.CHECK, cx, cy, R * 0.9f, tinted(color));
            else if (state == 2) Icons.draw(c, Icons.CLOSE, cx, cy, R * 0.8f, tinted(color));
            else if (state == 3) Icons.draw(c, Icons.DOWNLOAD, cx, cy, R * 0.8f, tinted(color));
            else if (!spinning) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Theme.text);
                p.setTextSize(R * 0.42f);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(Math.round(value * 100) + "%", cx, cy - (p.descent() + p.ascent()) / 2, p);
            }
        }

        private Paint tinted(int color) {
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            q.setColor(color);
            return q;
        }
    }

    // ================================================================ LED preview

    /** Glowing preview of the blue LED in the selected mode. */
    public static final class LedPreview extends View implements Ticking {
        private final Paint p = paint();
        private final long t0 = SystemClock.uptimeMillis();
        private int mode = 1, brightness = 255;
        private double breathSeconds = 2;
        private boolean active = true;

        public LedPreview(Context c) { super(c); }

        public void set(int mode, int brightness, double breathSeconds, boolean active) {
            this.mode = mode; this.brightness = brightness; this.breathSeconds = breathSeconds; this.active = active;
            invalidate();
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); register(this); }

        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); unregister(this); }

        @Override public void tick() { if (mode == 2 && isShown()) invalidate(); }

        /** Same shape as the firmware: level, then gamma 2. */
        private double level() {
            double b = brightness / 255.0, period = Math.max(0.5, breathSeconds), lv;
            switch (mode) {
                case 0: lv = 0; break;
                case 2: { double ph = (((SystemClock.uptimeMillis() - t0) / 1000.0) % period) / period; lv = (ph < 0.5 ? ph * 2 : (1 - ph) * 2) * b; break; }
                case 3: lv = active ? b : 0; break;
                default: lv = b;
            }
            return lv * lv;
        }

        @Override protected void onMeasure(int w, int h) { int s = MeasureSpec.getSize(w); setMeasuredDimension(s, Math.min(s, Ui.dp(240))); }

        @Override protected void onDraw(Canvas c) {
            float size = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
            double L = level();
            float R = size * 0.16f;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx, cy, size / 2, Color.argb((int) (200 * L), 0x3B, 0x82, 0xF6), Color.argb(0, 0x3B, 0x82, 0xF6), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, size / 2, p);
            p.setShader(null);
            p.setColor(Theme.cardHi);
            c.drawCircle(cx, cy, R + Ui.dpf(12), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(2));
            p.setColor(Theme.strokeHi);
            c.drawCircle(cx, cy, R + Ui.dpf(12), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb((int) (0x20 + (0x7C - 0x20) * L), (int) (0x28 + (0xB4 - 0x28) * L), (int) (0x3A + (0xFF - 0x3A) * L)));
            c.drawCircle(cx, cy, R, p);
        }
    }

    // ================================================================ raw ADC bar

    /** One raw ADC channel: current value with captured min/max range markers. */
    public static final class RawBar extends View {
        private final Paint p = paint();
        private final String label;
        private int value, min = 4095, max;

        public RawBar(Context c, String label) { super(c); this.label = label; }

        public void set(int value, int min, int max) {
            if (value == this.value && min == this.min && max == this.max) return;
            this.value = value; this.min = min; this.max = max;
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(34)); }

        @Override protected void onDraw(Canvas c) {
            float labelW = Ui.dpf(104), valueW = Ui.dpf(40), hh = Ui.dpf(14), top = (getHeight() - hh) / 2;
            RectF bar = new RectF(labelW, top, Math.max(labelW + Ui.dpf(20), getWidth() - valueW), top + hh);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.field);
            c.drawRoundRect(bar, hh / 2, hh / 2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(1));
            p.setColor(Theme.stroke);
            c.drawRoundRect(bar, hh / 2, hh / 2, p);
            float x0 = bar.left + bar.width() * clamp(min / 4095f, 0, 1), x1 = bar.left + bar.width() * clamp(max / 4095f, 0, 1);
            p.setStyle(Paint.Style.FILL);
            if (max > min) {
                p.setColor(Color.argb(0x55, 0x3B, 0x82, 0xF6));
                c.drawRoundRect(new RectF(x0, bar.top + Ui.dpf(2), Math.max(x0 + Ui.dpf(2), x1), bar.bottom - Ui.dpf(2)), Ui.dpf(5), Ui.dpf(5), p);
            }
            float vx = bar.left + bar.width() * clamp(value / 4095f, 0, 1);
            p.setColor(Color.WHITE);
            c.drawCircle(vx, getHeight() / 2f, Ui.dpf(8), p);
            p.setColor(Theme.accentHi);
            c.drawCircle(vx, getHeight() / 2f, Ui.dpf(5.5f), p);
            p.setTypeface(Ui.regular);
            p.setTextSize(Ui.sp(12.5f));
            p.setColor(Theme.textDim);
            p.setTextAlign(Paint.Align.LEFT);
            float ty = getHeight() / 2f - (p.descent() + p.ascent()) / 2;
            c.drawText(label, 0, ty, p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.valueOf(value), getWidth(), ty, p);
            p.setTypeface(Ui.medium);
        }
    }

    // ================================================================ stage background

    /** The window background: the page colour with soft blue and violet glows in two corners, like the desktop stage. */
    public static final class StageBackground extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            c.drawColor(Theme.bg);
            float big = Math.max(b.width(), b.height());
            if (Theme.dark) {
                glow(c, b.right, b.top, big * 0.75f, Theme.glowBlue);
                glow(c, b.left, b.bottom, big * 0.7f, Theme.glowViolet);
            } else {
                glow(c, b.right, b.top, big * 0.8f, Theme.glowBlue);
                glow(c, b.left, b.bottom, big * 0.7f, Theme.glowViolet);
            }
        }

        private void glow(Canvas c, float x, float y, float r, int color) {
            p.setShader(new RadialGradient(x, y, r, color, Theme.alpha(color, 0), Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, p);
            p.setShader(null);
        }

        @Override public void setAlpha(int a) { }

        @Override public void setColorFilter(android.graphics.ColorFilter f) { }

        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    // ================================================================ the controller

    /**
     * The Kishi V1, drawn from the two vector halves with a bridge between them.  Pressed buttons glow, the stick caps follow the live
     * axes, the trigger bars fill and the status LED mirrors the LED mode.  Mirrors the desktop app's ControllerView.
     */
    public static final class ControllerView extends View implements Ticking {
        // Scene, in art units.  Each half is 630 x 890; the left half's open side is x=630 and the right half's is its own x=0.
        private static final float HALF_W = 630, GAP = 640, ART = 890, TOP = -70, SCENE_W = HALF_W * 2 + GAP, SCENE_H = ART - TOP, RIGHT_X = HALF_W + GAP, CAP_TRAVEL = 24;
        private static final int ACCENT = Color.rgb(0x3B, 0x82, 0xF6), ACCENT_HI = Color.rgb(0x7B, 0xB0, 0xFF);

        private final Paint p = paint();
        private final Art art;
        private final Path scratch = new Path();
        private final Shader haloShader = new RadialGradient(0, 0, 1, new int[] { Color.argb(0, 0x3B, 0x82, 0xF6), Color.argb(0, 0x3B, 0x82, 0xF6), Color.argb(0x66, 0x3B, 0x82, 0xF6), Color.argb(0, 0x3B, 0x82, 0xF6) },
                new float[] { 0, 0.55f, 0.72f, 1 }, Shader.TileMode.CLAMP);
        private final Shader unitGlow = new RadialGradient(0, 0, 1, new int[] { Color.argb(0xFF, 0x4D, 0xA3, 0xFF), Color.argb(0, 0x4D, 0xA3, 0xFF) }, null, Shader.TileMode.CLAMP);
        private final Shader barShader = new LinearGradient(0, 0, 232, 0, ACCENT, Color.rgb(0x8B, 0x5C, 0xF6), Shader.TileMode.CLAMP);
        private final Shader backlight = new RadialGradient(0, 0, 1, new int[] { Color.argb(0x30, 0x3B, 0x82, 0xF6), Color.argb(0x10, 0x8B, 0x5C, 0xF6), Color.argb(0, 0x3B, 0x82, 0xF6) },
                new float[] { 0, 0.55f, 1 }, Shader.TileMode.CLAMP);
        private final Shader bridge = new LinearGradient(0, 34, 0, 870, new int[] { Color.rgb(0x2A, 0x2D, 0x33), Color.rgb(0x12, 0x13, 0x17), Color.rgb(0x0A, 0x0B, 0x0E), Color.rgb(0x12, 0x13, 0x17), Color.rgb(0x24, 0x27, 0x2C) },
                new float[] { 0, 0.14f, 0.5f, 0.86f, 1 }, Shader.TileMode.CLAMP);
        private final Matrix m = new Matrix();

        private LiveInput input = LiveInput.NEUTRAL;
        private int mask, ledMode = 1;
        private boolean connected;

        public ControllerView(Context c) {
            super(c);
            art = Art.get(c);
        }

        public void set(LiveInput input, int mask, boolean connected, int ledMode) {
            if (input == this.input && mask == this.mask && connected == this.connected && ledMode == this.ledMode) return;
            this.input = input; this.mask = mask; this.connected = connected; this.ledMode = ledMode;
            invalidate();
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); register(this); }

        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); unregister(this); }

        @Override public void tick() { if (ledMode == 2 && connected && isShown()) invalidate(); }

        private boolean down(int bit) { return (mask & (1 << bit)) != 0; }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, (int) (width * SCENE_H / SCENE_W));
        }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth() / SCENE_W, getHeight() / SCENE_H);
            if (s <= 0) return;
            c.save();
            c.translate((getWidth() - SCENE_W * s) / 2, (getHeight() - SCENE_H * s) / 2);
            c.scale(s, s);
            c.translate(0, -TOP);
            if (!connected) c.saveLayerAlpha(0, TOP, SCENE_W, ART, 128);
            else c.save();

            fillUnit(c, backlight, SCENE_W / 2, 450, SCENE_W * 0.52f, 560);
            drawBridge(c);
            leftHalf(c);
            c.save();
            c.translate(RIGHT_X, 0);
            rightHalf(c);
            c.restore();

            c.restore();
            c.restore();
        }

        private void fillUnit(Canvas c, Shader unit, float cx, float cy, float rx, float ry) {
            c.save();
            c.translate(cx, cy);
            c.scale(rx, ry);
            p.setStyle(Paint.Style.FILL);
            p.setShader(unit);
            c.drawCircle(0, 0, 1, p);
            p.setShader(null);
            c.restore();
        }

        private void drawBridge(Canvas c) {
            float x0 = HALF_W - 24, w = GAP + 48;
            p.setStyle(Paint.Style.FILL);
            p.setShader(bridge);
            c.drawRoundRect(x0, 34, x0 + w, 870, 18, 18, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(Color.rgb(0x05, 0x06, 0x08));
            c.drawRoundRect(x0, 34, x0 + w, 870, 18, 18, p);
            p.setStrokeWidth(2);
            p.setColor(Color.rgb(0x3A, 0x3E, 0x46));
            c.drawLine(x0 + 12, 122, x0 + w - 12, 122, p);
            c.drawLine(x0 + 12, 782, x0 + w - 12, 782, p);
            // The phone cradle between the two arms.
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(0xB0, 0x07, 0x08, 0x0B));
            c.drawRoundRect(HALF_W + 44, 150, HALF_W + 44 + GAP - 88, 740, 46, 46, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(Color.rgb(0x22, 0x26, 0x30));
            p.setPathEffect(new DashPathEffect(new float[] { 10, 12 }, 0));
            c.drawRoundRect(HALF_W + 44, 150, HALF_W + 44 + GAP - 88, 740, 46, 46, p);
            p.setPathEffect(null);
        }

        private void leftHalf(Canvas c) {
            art.drawGroup(c, "KishiLeftStatic");
            if (down(8)) pressGeo(c, "KishiLeftShoulderGeo");
            triggerBar(c, 128, -44, 232, 30, input.l2, "L2");
            if (down(4)) arrow(c, 279, 413, 265, 437, 293, 437);
            if (down(5)) arrow(c, 265, 584, 293, 584, 279, 609);
            if (down(6)) arrow(c, 182, 511, 206, 497, 206, 525);
            if (down(7)) arrow(c, 352, 497, 377, 511, 352, 525);
            if (down(14)) press(c, 384, 372, 26);
            if (down(13)) press(c, 384, 721, 29);
            cap(c, "KishiLeftCap", 239, 230, input.lx, input.ly, down(10));
        }

        private void rightHalf(Canvas c) {
            art.drawGroup(c, "KishiRightStatic");
            if (down(9)) pressGeo(c, "KishiRightShoulderGeo");
            triggerBar(c, 290, -44, 232, 30, input.r2, "R2");
            if (down(3)) press(c, 381, 156, 44);   // Y
            if (down(2)) press(c, 299, 238, 44);   // X
            if (down(1)) press(c, 464, 238, 44);   // B
            if (down(0)) press(c, 381, 320, 44);   // A
            if (down(12)) press(c, 247, 721, 26);  // right function
            led(c, 529, 372);
            cap(c, "KishiRightCap", 338, 520, input.rx, input.ry, down(11));
        }

        private void pressGeo(Canvas c, String key) {
            Path g = art.geos.get(key);
            if (g == null) return;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(0x70, 0x3B, 0x82, 0xF6));
            c.drawPath(g, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            p.setColor(ACCENT_HI);
            c.drawPath(g, p);
        }

        private void cap(Canvas c, String key, float cx, float cy, int ax, int ay, boolean pressed) {
            float dx = (ax - 128) / 127f * CAP_TRAVEL, dy = (ay - 128) / 127f * CAP_TRAVEL, len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > CAP_TRAVEL) { dx *= CAP_TRAVEL / len; dy *= CAP_TRAVEL / len; }
            c.save();
            c.translate(dx, dy);
            art.drawGroup(c, key);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(0x16, 0xFF, 0xFF, 0xFF));
            c.drawCircle(cx, cy, 36, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(Color.argb(0x26, 0xFF, 0xFF, 0xFF));
            c.drawCircle(cx, cy, 36, p);
            if (pressed) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(0x70, 0x3B, 0x82, 0xF6));
                c.drawCircle(cx, cy, 57, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(4);
                p.setColor(ACCENT_HI);
                c.drawCircle(cx, cy, 57, p);
            }
            c.restore();
        }

        private void press(Canvas c, float cx, float cy, float r) {
            fillUnit(c, haloShader, cx, cy, r * 1.55f, r * 1.55f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(0x70, 0x3B, 0x82, 0xF6));
            c.drawCircle(cx, cy, r - 2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            p.setColor(ACCENT_HI);
            c.drawCircle(cx, cy, r - 2, p);
        }

        private void arrow(Canvas c, float x0, float y0, float x1, float y1, float x2, float y2) {
            fillUnit(c, haloShader, (x0 + x1 + x2) / 3, (y0 + y1 + y2) / 3, 46, 46);
            scratch.reset();
            scratch.moveTo(x0, y0); scratch.lineTo(x1, y1); scratch.lineTo(x2, y2); scratch.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(ACCENT_HI);
            c.drawPath(scratch, p);
        }

        private void triggerBar(Canvas c, float x, float y, float w, float h, int value, String label) {
            RectF r = new RectF(x, y, x + w, y + h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.field);
            c.drawRoundRect(r, h / 2, h / 2, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(Theme.strokeHi);
            c.drawRoundRect(r, h / 2, h / 2, p);
            float fw = clamp(value / 255f, 0, 1) * w;
            if (fw > 1) {
                c.save();
                scratch.reset();
                scratch.addRoundRect(r, h / 2, h / 2, Path.Direction.CW);
                c.clipPath(scratch);
                m.setTranslate(x, 0);
                barShader.setLocalMatrix(m);
                p.setStyle(Paint.Style.FILL);
                p.setShader(barShader);
                c.drawRect(x, y, x + fw, y + h, p);
                p.setShader(null);
                c.restore();
            }
            boolean light = fw > w * 0.5f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(light ? Color.WHITE : Color.rgb(0x7C, 0x88, 0xA0));
            p.setTextSize(21);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(label, x + w / 2, y + h / 2 - (p.descent() + p.ascent()) / 2, p);
        }

        private void led(Canvas c, float cx, float cy) {
            double lit;
            switch (ledMode) {
                case 0: lit = 0; break;
                case 2: lit = 0.18 + 0.82 * (0.5 - 0.5 * Math.cos(SystemClock.uptimeMillis() / 3000.0 * 2 * Math.PI)); break;
                default: lit = 1;
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(0x08, 0x34, 0x7A));
            c.drawCircle(cx, cy, 10, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(Color.rgb(0x08, 0x09, 0x0B));
            c.drawCircle(cx, cy, 10, p);
            if (!connected || lit <= 0.01) return;
            c.save();
            c.translate(cx, cy);
            c.scale(38, 38);
            p.setStyle(Paint.Style.FILL);
            p.setShader(unitGlow);
            p.setAlpha((int) (lit * 150));
            c.drawCircle(0, 0, 1, p);
            p.setShader(null);
            c.restore();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (lit * 255), 0x4D, 0xA3, 0xFF));
            c.drawCircle(cx, cy, 10, p);
        }
    }
}
