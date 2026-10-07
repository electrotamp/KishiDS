package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.electrotamp.kishids.app.AppModel;
import com.electrotamp.kishids.core.CalibrationWizard;

/** Measure the sticks and triggers for the most accurate response, and choose how fast the controller reports. */
public final class CalibrationPage extends Page {
    private final TextView wizTitle, wizText, wizMessage, wizReview;
    private final Meter meter;
    private final Ui.Button start, finish, save, cancel;
    private final Widgets.RawBar[] bars = new Widgets.RawBar[6];

    /** A thin progress bar. */
    private static final class Meter extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float value;

        Meter(Activity a) { super(a); }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(8)); }

        @Override protected void onDraw(Canvas c) {
            RectF r = new RectF(0, 0, getWidth(), getHeight());
            p.setColor(Theme.field);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, p);
            p.setColor(Theme.accent);
            c.drawRoundRect(new RectF(0, 0, Math.max(r.height(), getWidth() * value), r.height()), r.height() / 2, r.height() / 2, p);
        }
    }

    public CalibrationPage(Activity act, AppModel m, Host host) {
        super(act, m, host, "Calibration", "Measure your own sticks and triggers for the most accurate response, and choose how fast the controller reports.");

        LinearLayout wiz = card();
        wizTitle = Ui.section(act, "");
        wiz.addView(wizTitle);
        wizText = Ui.caption(act, "");
        wiz.addView(wizText, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        meter = new Meter(act);
        wiz.addView(meter, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));
        wizMessage = Ui.tv(act, "", 13, Theme.warn);
        wiz.addView(wizMessage, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
        wizReview = Ui.monoText(act, "", 12, Theme.textDim);
        wiz.addView(wizReview, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
        LinearLayout buttons = Ui.hbox(act);
        start = Ui.primary(act, "Start calibration", () -> {
            if (m.device != AppModel.Device.CUSTOM) { Ui.message(act, "Calibration", "Connect a Kishi running KishiDS firmware to calibrate it.", "OK", null, null); return; }
            m.calibration.start(android.os.SystemClock.uptimeMillis());
        });
        finish = Ui.primary(act, "I've done both", () -> m.calibration.finish());
        save = Ui.primary(act, "Save to profile", () -> m.calibration.save());
        cancel = Ui.ghost(act, "Cancel", () -> m.calibration.cancel());
        buttons.addView(start);
        buttons.addView(finish);
        buttons.addView(save);
        buttons.addView(cancel, Ui.lp(Ui.WRAP, Ui.WRAP, 8, 0, 0, 0));
        wiz.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 18, 0, 0));

        LinearLayout use = card();
        use.addView(toggle("Use my calibration", "Off uses the values stored by the original firmware.", "calib_mode", 0));

        LinearLayout rate = card();
        rate.addView(Ui.section(act, "Report rate"), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));
        rate.addView(slider("USB polling interval", " ms", "poll_ms", "How often the host asks for input. 1 ms is the fastest (1000 Hz); the default is 5 ms."));

        LinearLayout raw = card();
        raw.addView(Ui.section(act, "Live sensor readings"));
        raw.addView(Ui.caption(act, "Raw values from the controller (0–4095). The blue band is the range captured so far."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 3, 0, 12));
        String[] names = { "Left stick X", "Left stick Y", "Right stick X", "Right stick Y", "Left trigger", "Right trigger" };
        for (int i = 0; i < 6; i++) {
            bars[i] = new Widgets.RawBar(act, names[i]);
            raw.addView(bars[i]);
        }

        if (wide) {
            LinearLayout left = Ui.vbox(act);
            left.addView(wiz);
            left.addView(use, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
            left.addView(rate, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
            LinearLayout cols = new LinearLayout(act);
            cols.addView(left, Ui.weight(1, 0, 0, 7, 0));
            cols.addView(raw, Ui.weight(1, 7, 0, 0, 0));
            cols.setBaselineAligned(false);
            add(cols, 16);
        } else {
            add(wiz, 16);
            add(raw, 14);
            add(use, 14);
            add(rate, 14);
        }
    }

    /** ADC order (firmware): 0 RX, 1 RY, 2 R2, 3 LY, 4 LX, 5 L2; the rows above are in reading order. */
    private static final int[] ADC_FOR_ROW = { 4, 3, 0, 1, 5, 2 };

    @Override protected void onUpdate() {
        CalibrationWizard w = m.calibration;
        wizTitle.setText(w.title());
        wizText.setText(w.instruction());
        meter.value = (float) w.centerProgress;
        meter.invalidate();
        Ui.show(meter, w.step == CalibrationWizard.Step.CENTER);
        wizMessage.setText(w.message);
        Ui.show(wizMessage, !w.message.isEmpty());
        Ui.show(start, w.step == CalibrationWizard.Step.IDLE);
        Ui.show(finish, w.step == CalibrationWizard.Step.RANGE);
        Ui.show(save, w.step == CalibrationWizard.Step.REVIEW);
        Ui.show(cancel, w.isRunning());
        Ui.show(wizReview, w.step == CalibrationWizard.Step.REVIEW);
        if (w.step == CalibrationWizard.Step.REVIEW) {
            StringBuilder sb = new StringBuilder();
            for (int a = 0; a < 6; a++) sb.append(String.format("%-14s %4d … %4d  (centre %d)\n", CalibrationWizard.NAMES[a], w.min[a], w.max[a], w.center[a]));
            wizReview.setText(sb.toString().trim());
        }
        for (int i = 0; i < 6; i++) {
            int a = ADC_FOR_ROW[i];
            bars[i].set(w.live[a], w.min[a], w.max[a]);
        }
    }
}
