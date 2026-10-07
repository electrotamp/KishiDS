package com.electrotamp.kishids.core;

import java.util.Arrays;

/**
 * Walks the user through capturing each stick's centre and range and each trigger's rest/pressed values from the firmware's raw ADC
 * telemetry, then writes them into the config block (and switches to custom calibration).
 * ADC order (firmware): 0 RX, 1 RY, 2 R2, 3 LY, 4 LX, 5 L2.  Mirrors the desktop app's CalibrationWizard.cs.
 */
public final class CalibrationWizard {
    public enum Step { IDLE, CENTER, RANGE, REVIEW }

    public static final String[] NAMES = { "Right stick X", "Right stick Y", "Right trigger", "Left stick Y", "Left stick X", "Left trigger" };

    private final ConfigBlock cfg;
    private final double[] sum = new double[6];
    private int samples;
    private long centerStartMs;
    private int[] lastAdc;

    public Step step = Step.IDLE;
    public String message = "";
    public double centerProgress;
    public final int[] live = new int[6], min = new int[6], max = new int[6], center = new int[6];

    public CalibrationWizard(ConfigBlock cfg) {
        this.cfg = cfg;
        reset();
    }

    public boolean isRunning() { return step != Step.IDLE; }

    public String title() {
        switch (step) {
            case CENTER: return "Step 1 of 2: Centre";
            case RANGE: return "Step 2 of 2: Range";
            case REVIEW: return "Review";
            default: return "Calibrate your controller";
        }
    }

    public String instruction() {
        switch (step) {
            case CENTER: return "Let go of both sticks and triggers and leave the controller still. This takes a moment.";
            case RANGE: return "Roll both sticks slowly around their full edge a few times, then squeeze both triggers all the way down and release.";
            case REVIEW: return "Here is what was measured. Save it to use these values instead of the factory ones.";
            default: return "Stick and trigger sensors vary between controllers. Calibrating teaches the firmware your exact centre and range, so full deflection reads 100% and rest reads 0.";
        }
    }

    public void reset() {
        Arrays.fill(min, 4095);
        Arrays.fill(max, 0);
        Arrays.fill(center, 0);
        Arrays.fill(sum, 0);
        samples = 0;
        centerProgress = 0;
        message = "";
        step = Step.IDLE;
    }

    public void start(long nowMs) {
        reset();
        centerStartMs = nowMs;
        step = Step.CENTER;
    }

    public void cancel() { reset(); }

    /** Feed the latest telemetry; returns true when something the page shows has changed. */
    public boolean update(Telemetry t, long nowMs) {
        if (t == null || t.adc.length < 6) return false;
        boolean running = step == Step.CENTER || step == Step.RANGE;
        if (!running && lastAdc != null && Arrays.equals(t.adc, lastAdc)) return false;   // idle and nothing moved
        lastAdc = t.adc;
        System.arraycopy(t.adc, 0, live, 0, 6);
        if (step == Step.CENTER) {
            double elapsed = (nowMs - centerStartMs) / 1000.0;
            for (int i = 0; i < 6; i++) sum[i] += t.adc[i];
            samples++;
            centerProgress = Math.min(1, elapsed / 1.5);
            if (elapsed >= 1.5 && samples > 10) {
                for (int i = 0; i < 6; i++) {
                    center[i] = (int) Math.round(sum[i] / samples);
                    min[i] = max[i] = center[i];
                }
                step = Step.RANGE;
            }
        } else if (step == Step.RANGE) {
            for (int i = 0; i < 6; i++) {
                min[i] = Math.min(min[i], t.adc[i]);
                max[i] = Math.max(max[i], t.adc[i]);
            }
        }
        return true;
    }

    /** Whether enough movement was captured to trust the result; null when fine, otherwise the problem in plain words. */
    public String rangeProblem() {
        for (int i : new int[] { 0, 1, 3, 4 }) if (max[i] - min[i] < 1400) return NAMES[i] + " hasn't been moved through its full range yet.";
        for (int i : new int[] { 2, 5 }) if (center[i] - min[i] < 500) return NAMES[i] + " hasn't been pressed all the way yet.";
        return null;
    }

    public void finish() {
        String problem = rangeProblem();
        if (problem != null) { message = problem; return; }
        message = "";
        step = Step.REVIEW;
    }

    /** Write the measured values into the config block. */
    public void save() {
        // Stick order in the config: RX, RY, LX, LY  <-  ADC 0, 1, 4, 3.
        int[] adcFor = { 0, 1, 4, 3 };
        for (int s = 0; s < 4; s++) {
            int a = adcFor[s];
            int c = center[a];
            cfg.set("cal_scenter", c, s);
            cfg.set("cal_smax", c + (int) Math.round((max[a] - c) * 0.97), s);
            cfg.set("cal_smin", c - (int) Math.round((c - min[a]) * 0.97), s);
        }
        // Triggers: config index 0 = L2 (ADC 5), 1 = R2 (ADC 2).  They read high at rest and fall when pressed.
        int[] trigAdc = { 5, 2 };
        for (int t = 0; t < 2; t++) {
            int a = trigAdc[t];
            int rest = center[a], pressed = min[a];
            cfg.set("cal_t_hi", rest - (int) Math.round((rest - pressed) * 0.04), t);
            cfg.set("cal_t_lo", pressed + (int) Math.round((rest - pressed) * 0.03), t);
        }
        cfg.set("calib_mode", 1);
        reset();
        message = "Calibration saved.";
    }
}
