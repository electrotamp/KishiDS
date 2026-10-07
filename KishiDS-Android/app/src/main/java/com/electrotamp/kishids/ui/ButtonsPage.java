package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.electrotamp.kishids.app.AppModel;
import com.electrotamp.kishids.core.ConfigBlock;
import com.electrotamp.kishids.core.ConfigLayout;
import com.electrotamp.kishids.core.Settings;

/** Choose what each physical button does.  A row lights up while its button is held on the controller. */
public final class ButtonsPage extends Page {
    private final LinearLayout[] rows = new LinearLayout[Settings.BUTTON_COUNT];
    private final TextView[] values = new TextView[Settings.BUTTON_COUNT];
    private final Widgets.IconButton[] resets = new Widgets.IconButton[Settings.BUTTON_COUNT];
    private final boolean[] lastPressed = new boolean[Settings.BUTTON_COUNT];
    private final View2[] dotViews = new View2[Settings.BUTTON_COUNT];
    private final Widgets.ControllerView preview;

    /** The little status dot beside each row. */
    private static final class View2 extends android.view.View {
        boolean on;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        View2(android.content.Context c) { super(c); }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(Ui.dp(14), Ui.dp(14)); }

        @Override protected void onDraw(android.graphics.Canvas c) {
            p.setColor(on ? Theme.accentHi : Theme.dotIdle);
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, Ui.dpf(on ? 5 : 4), p);
        }
    }

    public ButtonsPage(Activity act, AppModel m, Host host) {
        super(act, m, host, "Buttons", "Choose what each physical button does. Hold a button on the controller and its row lights up.");

        preview = new Widgets.ControllerView(act);
        LinearLayout previewCard = card();
        Ui.pad(previewCard, 10, 10, 10, 6);
        previewCard.addView(preview);

        LinearLayout list = card();
        Ui.pad(list, 8, 8, 8, 8);
        for (int i = 0; i < Settings.BUTTON_COUNT; i++) list.addView(buildRow(i), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 1, 0, 1));

        LinearLayout quick = card();
        quick.addView(Ui.section(act, "Quick changes"));
        quick.addView(Ui.caption(act, "Common layouts in one tap."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 3, 0, 12));
        quick.addView(Ui.secondary(act, "Swap A and B", () -> m.s.swapButtons("A", "B")), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 8));
        quick.addView(Ui.secondary(act, "Swap X and Y", () -> m.s.swapButtons("X", "Y")), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 8));
        quick.addView(Ui.secondary(act, "Swap L1/R1 with L2/R2", () -> {
            // Bumpers become triggers (the Kishi has no physical L2/R2 buttons to swap with, so this maps L1/R1 to L2/R2).
            m.config.set("button_map", indexOfOutput("L2"), Settings.indexOfButton("L1"));
            m.config.set("button_map", indexOfOutput("R2"), Settings.indexOfButton("R1"));
        }), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 4));
        quick.addView(Ui.ghost(act, "Reset all buttons", () -> m.config.resetField("button_map")), Ui.lp(Ui.MATCH, Ui.WRAP));

        TextView note = Ui.caption(act, "Mapping a button to L2 or R2 presses that trigger fully. Mapping a button to a D-pad direction works like pressing that direction.");

        if (wide) {
            LinearLayout cols = new LinearLayout(act);
            cols.setOrientation(LinearLayout.HORIZONTAL);
            cols.addView(list, Ui.weight(1.25f, 0, 0, 7, 0));
            LinearLayout right = Ui.vbox(act);
            right.addView(previewCard);
            right.addView(quick, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
            right.addView(note, Ui.lp(Ui.MATCH, Ui.WRAP, 4, 12, 4, 0));
            cols.addView(right, Ui.weight(1, 7, 0, 0, 0));
            add(cols, 16);
        } else {
            add(previewCard, 14);
            add(list, 14);
            add(quick, 14);
            add(note, 12);
        }
    }

    private static int indexOfOutput(String name) {
        for (int i = 0; i < ConfigLayout.OUTPUTS.length; i++) if (ConfigLayout.OUTPUTS[i].equals(name)) return i;
        throw new IllegalArgumentException(name);
    }

    private LinearLayout buildRow(int i) {
        LinearLayout row = Ui.hbox(act);
        row.setMinimumHeight(Ui.dp(Ui.compact(act) ? 50 : 58));
        Ui.pad(row, 12, 4, 8, 4);
        View2 dot = new View2(act);
        dotViews[i] = dot;
        row.addView(dot, Ui.lp(Ui.dp(14), Ui.dp(14), 0, 0, 12, 0));
        TextView name = Ui.medium(act, ConfigLayout.KISHI_BUTTONS[i], 15, Theme.text);
        row.addView(name, new LinearLayout.LayoutParams(0, Ui.WRAP, 0.8f));
        row.addView(new Widgets.IconView(act, Icons.ARROW, 18, Theme.textFaint), Ui.lp(Ui.dp(18), Ui.dp(18), 0, 0, 10, 0));

        LinearLayout picker = Ui.hbox(act);
        picker.setBackground(Ui.ripple(Ui.box(Theme.field, 12, Theme.stroke, 1), 12));
        Ui.pad(picker, 14, 0, 10, 0);
        picker.setMinimumHeight(Ui.dp(Ui.compact(act) ? 40 : 46));
        TextView value = Ui.tv(act, "", 14.5f, Theme.text);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        values[i] = value;
        picker.addView(value, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        picker.addView(new Widgets.IconView(act, Icons.CHEVRON_DOWN, 18, Theme.textDim));
        picker.setClickable(true);
        picker.setOnClickListener(v -> Ui.sheet(act, ConfigLayout.KISHI_BUTTONS[i] + " button does…", Settings.OUTPUT_LABELS, m.s.output(i), idx -> m.s.setOutput(i, idx)));
        row.addView(picker, new LinearLayout.LayoutParams(0, Ui.WRAP, 1.5f));

        Widgets.IconButton reset = new Widgets.IconButton(act, Icons.RESET, Theme.textDim, () -> m.config.set("button_map", ConfigBlock.field("button_map").defaults[i], i));
        resets[i] = reset;
        row.addView(reset, Ui.lp(Ui.dp(44), Ui.dp(44), 0, 0, 0, 0));
        rows[i] = row;
        return row;
    }

    @Override protected void onUpdate() {
        preview.set(m.live, m.tele == null ? 0 : m.tele.buttonMask, m.isConnected(), m.config.get("led_mode"));
        for (int i = 0; i < Settings.BUTTON_COUNT; i++) {
            boolean pressed = m.pressed(i);
            if (pressed != lastPressed[i] || rows[i].getBackground() == null) {
                lastPressed[i] = pressed;
                rows[i].setBackground(pressed ? Ui.box(Theme.accentSoft, 14, 0, 0) : null);
                dotViews[i].on = pressed;
                dotViews[i].invalidate();
            }
            String label = Settings.OUTPUT_LABELS[m.s.output(i)];
            if (!label.contentEquals(values[i].getText())) values[i].setText(label);
            boolean def = m.s.isDefault(i);
            resets[i].setVisibility(def ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
        }
    }
}
