package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.electrotamp.kishids.app.AppModel;

/** Put KishiDS firmware on the controller (needed once, to switch on live editing), or put Razer's original firmware back. */
public final class FirmwarePage extends Page {
    private final LinearLayout warning, stepsBox, stockPill;
    private final Widgets.RingProgress ring;
    private final TextView flowTitle, flowDetail, stockText, logText;
    private final Widgets.IconView stockIcon;
    private final Ui.Button apply, flash, cancel, allow, find, how, restore;
    private final LinearLayout logBox;
    private boolean logOpen;
    private long lastLog;

    public FirmwarePage(Activity act, AppModel m, Host host) {
        super(act, m, host, "Firmware", "Update the controller's firmware (needed only once to switch on live editing), or put the original firmware back.");

        // ---- warning: no copy of Razer's firmware saved yet ----
        warning = Ui.hbox(act);
        warning.setBackground(Ui.box(Theme.warnSoft, 16, Theme.warn, 1));
        Ui.pad(warning, 16, 14, 14, 14);
        warning.addView(new Widgets.IconView(act, Icons.WARNING, 24, Theme.warn), Ui.lp(Ui.dp(24), Ui.dp(24), 0, 0, 14, 0));
        LinearLayout ww = Ui.vbox(act);
        ww.addView(Ui.medium(act, "Save Razer's original firmware before you switch the controller", 14.5f, Theme.text));
        ww.addView(Ui.caption(act, "Switching replaces Razer's firmware, and it can't be read back from the controller afterwards. Without a saved copy you can't return to stock from this app."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 4, 0, 10));
        ww.addView(Ui.primary(act, "Show me how", () -> host.showSetup(2)), Ui.lp(Ui.WRAP, Ui.WRAP));
        warning.addView(ww, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        add(warning, 16);

        // ---- the flow ----
        LinearLayout flow = card();
        Ui.pad(flow, 20, 20, 20, 20);
        LinearLayout top = Ui.hbox(act);
        ring = new Widgets.RingProgress(act);
        top.addView(ring, Ui.lp(Ui.dp(96), Ui.dp(96), 0, 0, 18, 0));
        LinearLayout words = Ui.vbox(act);
        flowTitle = Ui.medium(act, "", 20, Theme.text);
        flowDetail = Ui.caption(act, "");
        flowDetail.setTextSize(14);
        words.addView(flowTitle);
        words.addView(flowDetail, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
        top.addView(words, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        flow.addView(top);

        stepsBox = Ui.vbox(act);
        stepsBox.setBackground(Ui.box(Theme.panelInset, 14, Theme.stroke, 1));
        Ui.pad(stepsBox, 16, 14, 16, 8);
        stepsBox.addView(Ui.medium(act, "Enter update mode", 15, Theme.text), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 12));
        String[] steps = { "Take the phone out of the Kishi, so it is unplugged.", "Press and hold Y + B + the Right Function button.",
                "Slide the phone back on while still holding them.", "Let go once this page says the bootloader is detected." };
        for (int i = 0; i < steps.length; i++) stepsBox.addView(step(i + 1, steps[i]), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 10));
        flow.addView(stepsBox, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 18, 0, 0));

        LinearLayout buttons = Ui.hbox(act);
        apply = Ui.primary(act, "Apply to controller", () -> m.beginApply());
        flash = Ui.primary(act, "Flash now", m::flashNow);
        allow = Ui.secondary(act, "Allow access", m::allowAccess);
        cancel = Ui.secondary(act, "Cancel", m::cancelFlow);
        buttons.addView(apply, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, 10, 0));
        buttons.addView(flash, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, 10, 0));
        buttons.addView(allow, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, 10, 0));
        buttons.addView(cancel);
        flow.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 18, 0, 0));

        // ---- original firmware ----
        LinearLayout orig = card();
        orig.addView(Ui.section(act, "Original firmware"));
        orig.addView(Ui.caption(act, "Put your Kishi back exactly as it came from Razer. KishiDS keeps a verified copy taken from Razer's own Android app (.apk) or the firmware file (.bin)."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 4, 0, 14));
        stockPill = Ui.hbox(act);
        Ui.pad(stockPill, 12, 10, 12, 10);
        stockIcon = new Widgets.IconView(act, Icons.CHECK, 18, Theme.good);
        stockPill.addView(stockIcon, Ui.lp(Ui.dp(18), Ui.dp(18), 0, 0, 10, 0));
        stockText = Ui.tv(act, "", 13, Theme.text);
        stockPill.addView(stockText);
        orig.addView(stockPill, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));
        find = Ui.secondary(act, "Find it for me", host::importFromInstalledRazerApp);
        orig.addView(find, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 8));
        orig.addView(Ui.secondary(act, "Choose a file…", host::importOriginalFirmware), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 8));
        how = Ui.ghost(act, "How do I get it?", () -> host.showSetup(2));
        orig.addView(how, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 8));
        restore = Ui.secondary(act, "Restore original firmware", m::beginRestore);
        orig.addView(restore, Ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout tutorial = Ui.hbox(act);
        tutorial.setGravity(Gravity.CENTER);
        tutorial.setBackground(Ui.ripple(Ui.box(Theme.cardHi, 16, Theme.strokeHi, 1), 16));
        tutorial.setMinimumHeight(Ui.dp(52));
        tutorial.addView(new Widgets.IconView(act, Icons.HELP, 20, Theme.text), Ui.lp(Ui.dp(20), Ui.dp(20), 0, 0, 10, 0));
        tutorial.addView(Ui.medium(act, "Replay welcome tutorial", 15, Theme.text));
        tutorial.setClickable(true);
        tutorial.setOnClickListener(v -> host.showSetup(1));

        TextView note = Ui.caption(act, "Your Kishi can always be put back into update mode with the button combination, even if a setting makes it behave strangely.");

        if (wide) {
            LinearLayout cols = new LinearLayout(act);
            cols.addView(flow, Ui.weight(1.4f, 0, 0, 7, 0));
            LinearLayout right = Ui.vbox(act);
            right.addView(orig);
            right.addView(tutorial, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
            right.addView(note, Ui.lp(Ui.MATCH, Ui.WRAP, 6, 12, 6, 0));
            cols.addView(right, Ui.weight(1, 7, 0, 0, 0));
            add(cols, 14);
        } else {
            add(flow, 14);
            add(orig, 14);
            add(tutorial, 12);
            add(note, 12);
        }

        // ---- technical log ----
        LinearLayout head = Ui.hbox(act);
        head.setClickable(true);
        head.setMinimumHeight(Ui.dp(48));
        Widgets.IconView arrow = new Widgets.IconView(act, Icons.CHEVRON, 18, Theme.textDim);
        head.addView(arrow, Ui.lp(Ui.dp(18), Ui.dp(18), 2, 0, 10, 0));
        head.addView(Ui.tv(act, "Technical log", 14, Theme.textDim));
        logBox = card();
        logText = Ui.monoText(act, "", 11.5f, Theme.textDim);
        logText.setTextIsSelectable(true);
        logBox.addView(logText);
        Ui.show(logBox, false);
        head.setOnClickListener(v -> {
            logOpen = !logOpen;
            Ui.show(logBox, logOpen);
            arrow.set(logOpen ? Icons.CHEVRON_DOWN : Icons.CHEVRON, Theme.textDim);
        });
        add(head, 10);
        add(logBox, 4);
    }

    private View step(int n, String text) {
        LinearLayout row = Ui.hbox(act);
        TextView num = Ui.medium(act, String.valueOf(n), 12.5f, Theme.accentHi);
        num.setGravity(Gravity.CENTER);
        num.setBackground(Ui.box(Theme.accentSoft, 13, 0, 0));
        row.addView(num, Ui.lp(Ui.dp(26), Ui.dp(26), 0, 0, 12, 0));
        row.addView(Ui.tv(act, text, 14, Theme.text), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        return row;
    }

    @Override protected void onUpdate() {
        AppModel.Phase ph = m.phase;
        Ui.show(warning, !m.hasStock());
        ring.set(m.progress, ph == AppModel.Phase.WAITING_FOR_BOOTLOADER || ph == AppModel.Phase.VERIFYING, ringState(ph));
        flowTitle.setText(m.flowTitle);
        flowDetail.setText(m.flowDetail.isEmpty() ? idleDetail() : m.flowDetail);
        Ui.show(stepsBox, m.showSteps() && m.device != AppModel.Device.BOOTLOADER);
        Ui.show(apply, m.canStart());
        Ui.show(flash, ph == AppModel.Phase.READY);
        Ui.show(allow, ph == AppModel.Phase.WAITING_FOR_BOOTLOADER && m.device == AppModel.Device.BOOTLOADER);
        Ui.show(cancel, m.showSteps());

        boolean has = m.hasStock();
        stockPill.setBackground(Ui.box(has ? Theme.goodSoft : Theme.warnSoft, 10, 0, 0));
        stockIcon.set(has ? Icons.CHECK : Icons.WARNING, has ? Theme.good : Theme.warn);
        stockText.setText(m.stockStatus());
        Ui.show(find, !has);
        Ui.show(how, !has);
        restore.setEnabled(has && m.canStart());

        if (logOpen && SystemClock.uptimeMillis() - lastLog > 500) {
            lastLog = SystemClock.uptimeMillis();
            logText.setText(m.log());
        }
    }

    private String idleDetail() { return m.phase == AppModel.Phase.IDLE ? "Writes your settings and the KishiDS firmware to the controller." : ""; }

    private static int ringState(AppModel.Phase ph) {
        switch (ph) {
            case DONE: return 1;
            case FAILED: return 2;
            case FLASHING: case VERIFYING: case WAITING_FOR_BOOTLOADER: return 0;
            default: return 3;
        }
    }
}
