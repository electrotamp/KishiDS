package com.electrotamp.kishids.ui;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.electrotamp.kishids.core.ConfigBlock;
import com.electrotamp.kishids.core.ConfigLayout;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Label + control rows used by the settings pages (the desktop app's SliderRow and ToggleRow). */
public final class Rows {
    private Rows() {}

    /** A labelled slider with a value pill and an optional hint. */
    public static final class SliderRow extends LinearLayout implements Ui.Refreshable {
        private final Widgets.SliderView slider;
        private final TextView value;
        private final DoubleSupplier get;
        private final String unit;
        private final boolean decimals;

        public SliderRow(Context c, String label, String unit, double min, double max, double step, String hint, DoubleSupplier get, DoubleConsumer set) {
            super(c);
            setOrientation(VERTICAL);
            setClipChildren(false);   // the slider's touch area reaches into the card padding so its thumb is never cut off
            this.get = get;
            this.unit = unit;
            this.decimals = step < 1;
            LinearLayout top = Ui.hbox(c);
            TextView l = Ui.medium(c, label, 15, Theme.text);
            top.addView(l, new LayoutParams(0, Ui.WRAP, 1));
            value = Ui.monoText(c, "", 13, Theme.text);
            value.setBackground(Ui.box(Theme.field, 9, Theme.stroke, 1));
            Ui.pad(value, 11, 5, 11, 5);
            top.addView(value);
            addView(top, Ui.lp(Ui.MATCH, Ui.WRAP));
            if (hint != null && !hint.isEmpty()) addView(Ui.caption(c, hint), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
            slider = new Widgets.SliderView(c, min, max, step);
            slider.listener = v -> { set.accept(v); show(v); };
            addView(slider, Ui.lp(Ui.MATCH, Ui.WRAP, -14, 2, -14, 0));
            setPadding(0, 0, 0, Ui.dp(14));
        }

        /** Convenience for an integer config field. */
        public static SliderRow field(Context c, ConfigBlock cfg, String label, String unit, String field, String hint) {
            ConfigLayout.FieldDef f = ConfigBlock.field(field);
            return new SliderRow(c, label, unit, f.min, f.max, 1, hint, () -> cfg.get(field), v -> cfg.set(field, (int) Math.round(v)));
        }

        private void show(double v) {
            value.setText(decimals ? String.format(Locale.US, "%.1f%s", v, unit) : String.format(Locale.US, "%d%s", Math.round(v), unit));
        }

        @Override public void refresh() {
            double v = get.getAsDouble();
            slider.setValue(v);
            if (!slider.isDragging()) show(v);
        }
    }

    /** A labelled switch; the whole row is the touch target. */
    public static final class ToggleRow extends LinearLayout implements Ui.Refreshable {
        private final Widgets.ToggleView toggle;
        private final BooleanSupplier get;

        public ToggleRow(Context c, String label, String hint, BooleanSupplier get, Consumer<Boolean> set) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            this.get = get;
            LinearLayout text = Ui.vbox(c);
            text.addView(Ui.medium(c, label, 15, Theme.text));
            if (hint != null && !hint.isEmpty()) text.addView(Ui.caption(c, hint), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
            addView(text, new LayoutParams(0, Ui.WRAP, 1));
            toggle = new Widgets.ToggleView(c);
            toggle.listener = v -> set.accept(v);
            addView(toggle, Ui.lp(Ui.WRAP, Ui.WRAP, 16, 0, 0, 0));
            setMinimumHeight(Ui.dp(56));
            setClickable(true);
            setOnClickListener(v -> toggle.toggle());
            setBackground(Ui.ripple(new ColorDrawable(0), 12));
        }

        @Override public void refresh() { toggle.setOn(get.getAsBoolean()); }
    }

    /** A segmented control bound to an int config field. */
    public static final class ChoiceRow extends LinearLayout implements Ui.Refreshable {
        private final Widgets.SegmentedView seg;
        private final IntSupplier get;

        public ChoiceRow(Context c, String[] options, IntSupplier get, IntConsumer set) {
            super(c);
            setOrientation(VERTICAL);
            this.get = get;
            seg = new Widgets.SegmentedView(c, options);
            seg.listener = v -> set.accept(v);
            addView(seg, Ui.lp(Ui.MATCH, Ui.WRAP));
        }

        @Override public void refresh() { seg.setSelected(get.getAsInt()); }
    }
}
