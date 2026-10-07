package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small helpers for building the UI in code: sizes, rounded surfaces, text styles, buttons, cards, dialogs and sheets. */
public final class Ui {
    private Ui() {}

    public static float density = 3f, scaledDensity = 3f;
    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    public static Typeface regular = Typeface.DEFAULT, medium = Typeface.create("sans-serif-medium", Typeface.NORMAL), bold = Typeface.create("sans-serif-medium", Typeface.BOLD),
            mono = Typeface.MONOSPACE;

    /** Anything that shows model state and can be told to re-read it. */
    public interface Refreshable { void refresh(); }

    public static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
        scaledDensity = c.getResources().getDisplayMetrics().scaledDensity;
    }

    public static int dp(float v) { return Math.round(v * density); }

    public static float dpf(float v) { return v * density; }

    public static float sp(float v) { return v * scaledDensity; }

    /** True on wide screens (landscape phones, tablets): pages lay cards out side by side and the navigation becomes a side rail. */
    public static boolean wide(Context c) { return c.getResources().getConfiguration().screenWidthDp >= 600; }

    /** A short landscape screen (a phone held in the Kishi): everything tightens up and the system bars get out of the way. */
    public static boolean compact(Context c) {
        android.content.res.Configuration cfg = c.getResources().getConfiguration();
        return cfg.screenWidthDp >= 600 && cfg.screenHeightDp < 520;
    }

    /** Layout params sharing a row equally and stretching to its height. */
    public static LinearLayout.LayoutParams weightFill(float w, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, MATCH, w);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    // ---------------------------------------------------------------- surfaces

    public static GradientDrawable box(int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dpf(radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }

    public static GradientDrawable gradientBox(int a, int b, float radiusDp, int stroke, float strokeDp, GradientDrawable.Orientation o) {
        GradientDrawable g = new GradientDrawable(o, new int[] { a, b });
        g.setCornerRadius(dpf(radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }

    /** Touch feedback over a rounded surface. */
    public static Drawable ripple(Drawable base, float radiusDp) {
        int tint = Theme.alpha(Theme.dark ? Color.WHITE : Theme.accent, Theme.dark ? 30 : 40);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dpf(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(tint), base, mask);
    }

    // ---------------------------------------------------------------- text

    public static TextView tv(Context c, CharSequence text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(regular);
        return t;
    }

    public static TextView medium(Context c, CharSequence text, float sp, int color) {
        TextView t = tv(c, text, sp, color);
        t.setTypeface(medium);
        return t;
    }

    public static TextView pageTitle(Context c, String text) {
        TextView t = tv(c, text, 30, Theme.text);
        t.setTypeface(bold);
        return t;
    }

    public static TextView caption(Context c, CharSequence text) {
        TextView t = tv(c, text, 13.5f, Theme.textDim);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    public static TextView section(Context c, String text) {
        TextView t = tv(c, text, 18, Theme.text);
        t.setTypeface(medium);
        return t;
    }

    public static TextView eyebrow(Context c, String text) {
        TextView t = tv(c, text.toUpperCase(), 11, Theme.textFaint);
        t.setTypeface(medium);
        t.setLetterSpacing(0.08f);
        return t;
    }

    public static TextView monoText(Context c, CharSequence text, float sp, int color) {
        TextView t = tv(c, text, sp, color);
        t.setTypeface(mono);
        return t;
    }

    // ---------------------------------------------------------------- layout

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    /** Layout params with margins in dp. */
    public static LinearLayout.LayoutParams lp(int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    public static LinearLayout.LayoutParams weight(float w, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, w);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    public static void pad(View v, float l, float t, float r, float b) { v.setPadding(dp(l), dp(t), dp(r), dp(b)); }

    public static View space(Context c, float widthDp, float heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)));
        return v;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Theme.stroke);
        v.setLayoutParams(lp(MATCH, Math.max(1, dp(1))));
        return v;
    }

    /** A rounded surface: the desktop app's Card. */
    public static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(box(Theme.card, 18, Theme.stroke, 1));
        pad(l, 18, 18, 18, 18);
        return l;
    }

    public static void show(View v, boolean visible) { v.setVisibility(visible ? View.VISIBLE : View.GONE); }

    // ---------------------------------------------------------------- buttons

    public enum Kind { PRIMARY, SECONDARY, GHOST, WARN }

    /** A text button with a minimum 48 dp touch height that dims when disabled. */
    public static final class Button extends TextView {
        private final Kind kind;

        public Button(Context c, String text, Kind kind, Runnable onClick) {
            super(c);
            this.kind = kind;
            setText(text);
            setGravity(Gravity.CENTER);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            setTypeface(medium);
            setSingleLine(true);
            setEllipsize(TextUtils.TruncateAt.END);
            setIncludeFontPadding(false);
            setMinHeight(dp(48));
            setMinimumWidth(dp(48));
            pad(this, 20, 0, 20, 0);
            setClickable(true);
            if (onClick != null) setOnClickListener(v -> onClick.run());
            style();
        }

        private void style() {
            Drawable base;
            int fg;
            switch (kind) {
                case PRIMARY:
                    base = gradientBox(Theme.accent, Theme.mix(Theme.accent, Theme.violet, 0.55f), 14, 0, 0, GradientDrawable.Orientation.LEFT_RIGHT);
                    fg = Color.WHITE;
                    break;
                case WARN:
                    base = box(Theme.warn, 14, 0, 0);
                    fg = Color.parseColor("#1B1500");
                    break;
                case SECONDARY:
                    base = box(Theme.cardHi, 14, Theme.strokeHi, 1);
                    fg = Theme.text;
                    break;
                default:
                    base = new ColorDrawable(Color.TRANSPARENT);
                    fg = Theme.textDim;
                    break;
            }
            setTextColor(fg);
            setBackground(ripple(base, 14));
            setAlpha(isEnabled() ? 1f : 0.4f);
        }

        @Override public void setEnabled(boolean e) {
            super.setEnabled(e);
            setAlpha(e ? 1f : 0.4f);
        }
    }

    public static Button primary(Context c, String text, Runnable r) { return new Button(c, text, Kind.PRIMARY, r); }

    public static Button secondary(Context c, String text, Runnable r) { return new Button(c, text, Kind.SECONDARY, r); }

    public static Button ghost(Context c, String text, Runnable r) { return new Button(c, text, Kind.GHOST, r); }

    // ---------------------------------------------------------------- dialogs

    /** A themed message dialog.  {@code secondary} may be null for a single-button notice. */
    public static void message(Activity a, String title, String body, String primary, String secondary, Runnable onPrimary) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = vbox(a);
        box.setBackground(box(Theme.card, 22, Theme.strokeHi, 1));
        pad(box, 22, 22, 22, 16);
        box.addView(section(a, title));
        TextView msg = caption(a, body);
        msg.setTextSize(14.5f);
        msg.setTextColor(Theme.textDim);
        box.addView(msg, lp(MATCH, WRAP, 0, 10, 0, 18));
        LinearLayout row = hbox(a);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        if (secondary != null) row.addView(ghost(a, secondary, d::dismiss));
        row.addView(primary(a, primary, () -> { d.dismiss(); if (onPrimary != null) onPrimary.run(); }), lp(WRAP, WRAP, 8, 0, 0, 0));
        box.addView(row, lp(MATCH, WRAP));
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) Math.min(dpf(420), a.getResources().getDisplayMetrics().widthPixels - dpf(40)), WRAP);
        }
        d.show();
    }

    /** A two-choice dialog where both buttons do something (either callback may be null). */
    public static void confirm(Activity a, String title, String body, String primary, Runnable onPrimary, String secondary, Runnable onSecondary) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = vbox(a);
        box.setBackground(box(Theme.card, 22, Theme.strokeHi, 1));
        pad(box, 22, 22, 22, 16);
        box.addView(section(a, title));
        TextView msg = caption(a, body);
        msg.setTextSize(14.5f);
        box.addView(msg, lp(MATCH, WRAP, 0, 10, 0, 18));
        LinearLayout row = hbox(a);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(ghost(a, secondary, () -> { d.dismiss(); if (onSecondary != null) onSecondary.run(); }));
        row.addView(primary(a, primary, () -> { d.dismiss(); if (onPrimary != null) onPrimary.run(); }), lp(WRAP, WRAP, 8, 0, 0, 0));
        box.addView(row, lp(MATCH, WRAP));
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) Math.min(dpf(420), a.getResources().getDisplayMetrics().widthPixels - dpf(40)), WRAP);
        }
        d.show();
    }

    public interface IntPick { void pick(int index); }

    /** A bottom sheet listing {@code options} with a check by the selected one: the touch replacement for a drop-down. */
    public static void sheet(Activity a, String title, String[] options, int selected, IntPick onPick) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = vbox(a);
        GradientDrawable bg = box(Theme.card, 0, Theme.strokeHi, 1);
        float r = dpf(24);
        bg.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
        root.setBackground(bg);
        pad(root, 8, 10, 8, 12);
        View grip = new View(a);
        grip.setBackground(box(Theme.strokeHi, 3, 0, 0));
        LinearLayout.LayoutParams gp = lp(dp(40), dp(4), 0, 0, 0, 8);
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(grip, gp);
        TextView t = section(a, title);
        pad(t, 14, 4, 14, 8);
        root.addView(t);
        LinearLayout list = vbox(a);
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            LinearLayout row = hbox(a);
            row.setMinimumHeight(dp(52));
            pad(row, 14, 0, 14, 0);
            boolean sel = i == selected;
            row.setBackground(ripple(box(sel ? Theme.accentSoft : Color.TRANSPARENT, 12, 0, 0), 12));
            TextView label = tv(a, options[i], 16, sel ? Theme.text : Theme.textDim);
            if (sel) label.setTypeface(medium);
            row.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1));
            if (sel) {
                Widgets.IconView check = new Widgets.IconView(a, Icons.CHECK, 20, Theme.accentHi);
                row.addView(check, lp(dp(20), dp(20)));
            }
            row.setOnClickListener(v -> { d.dismiss(); onPick.pick(idx); });
            list.addView(row, lp(MATCH, WRAP, 0, 1, 0, 1));
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(list);
        root.addView(sv, lp(MATCH, 0));
        ((LinearLayout.LayoutParams) sv.getLayoutParams()).weight = 1;
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int h = a.getResources().getDisplayMetrics().heightPixels;
            int wid = Math.min(a.getResources().getDisplayMetrics().widthPixels, dp(560));
            w.setLayout(wid, Math.min((int) (h * 0.82), dp(76) + options.length * dp(54)));
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        d.show();
    }

    /** A frame with a fixed aspect ratio (width : height), used to size the artwork. */
    public static final class AspectFrame extends FrameLayout {
        private final float ratio;

        public AspectFrame(Context c, float ratio) { super(c); this.ratio = ratio; }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((int) (w / ratio), MeasureSpec.EXACTLY));
        }
    }
}
