package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.electrotamp.kishids.app.AppModel;

/** First-run walkthrough: welcome, get and save Razer's original firmware, then switch the controller. */
public final class SetupGuide extends FrameLayout {
    private static final int LAST = 3;
    private final Activity act;
    private final AppModel m;
    private final Host host;
    private int step = 1;
    private Runnable onFinished;
    private long installedChecked;
    private boolean installedCache;

    private final TextView[] dotText = new TextView[3], dotLabel = new TextView[3];
    private final LinearLayout[] dots = new LinearLayout[3];
    private final LinearLayout[] panes = new LinearLayout[3];
    private final Ui.Button back, next, skip;

    // step 1
    private final View welcomeDot;
    private final TextView welcomeStatus;
    // step 2
    private final LinearLayout stockOk, stockMissing;
    private final Ui.Button useInstalled;
    private final TextView installedNote;
    // step 3
    private final TextView s3Title, s3Intro, s3Status;
    private final LinearLayout s3Steps;

    public SetupGuide(Activity act, AppModel m, Host host) {
        super(act);
        this.act = act;
        this.m = m;
        this.host = host;
        setBackgroundColor(Theme.alpha(Theme.bg, 242));
        setClickable(true);

        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        LinearLayout outer = new LinearLayout(act);
        outer.setGravity(Gravity.CENTER);
        Ui.pad(outer, 14, 14, 14, 14);

        LinearLayout card = Ui.vbox(act);
        card.setBackground(Ui.box(Theme.card, 24, Theme.strokeHi, 1));
        Ui.pad(card, 22, 20, 22, 18);

        // header: progress dots
        LinearLayout head = Ui.hbox(act);
        String[] labels = { "Welcome", "Original firmware", "Switch" };
        for (int i = 0; i < 3; i++) {
            LinearLayout d = Ui.vbox(act);
            d.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView t = Ui.medium(act, String.valueOf(i + 1), 13, Color.WHITE);
            t.setGravity(Gravity.CENTER);
            dotText[i] = t;
            d.addView(t, Ui.lp(Ui.dp(30), Ui.dp(30)));
            dotLabel[i] = Ui.tv(act, labels[i], 11.5f, Theme.textDim);
            dotLabel[i].setGravity(Gravity.CENTER);
            d.addView(dotLabel[i], Ui.lp(Ui.WRAP, Ui.WRAP, 0, 5, 0, 0));
            dots[i] = d;
            head.addView(d, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        }
        card.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 18));

        // ---- step 1: welcome ----
        LinearLayout p1 = Ui.vbox(act);
        android.widget.ImageView logo = new android.widget.ImageView(act);
        logo.setImageResource(com.electrotamp.kishids.R.drawable.logo);
        p1.addView(logo, Ui.lp(Ui.dp(64), Ui.dp(64), 0, 0, 0, 10));
        p1.addView(Ui.medium(act, "Welcome to KishiDS", 24, Theme.text));
        p1.addView(Ui.caption(act, "Remap buttons, tune the sticks and triggers, and control the light on your Razer Kishi V1 from your phone. Keep the phone in the Kishi: the app talks to the controller over its USB-C plug."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 8, 0, 16));
        LinearLayout sRow = Ui.hbox(act);
        sRow.setBackground(Ui.box(Theme.panelInset, 14, Theme.stroke, 1));
        Ui.pad(sRow, 14, 12, 14, 12);
        welcomeDot = new View(act);
        sRow.addView(welcomeDot, Ui.lp(Ui.dp(10), Ui.dp(10), 0, 0, 12, 0));
        welcomeStatus = Ui.tv(act, "", 14, Theme.text);
        sRow.addView(welcomeStatus, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        p1.addView(sRow);
        panes[0] = p1;

        // ---- step 2: original firmware ----
        LinearLayout p2 = Ui.vbox(act);
        p2.addView(Ui.medium(act, "Save Razer's original firmware", 22, Theme.text));
        p2.addView(Ui.caption(act, "KishiDS replaces Razer's firmware, and it can't be read back from the controller. Keep a verified copy now so you can always go back to stock."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 8, 0, 14));
        stockOk = Ui.hbox(act);
        stockOk.setBackground(Ui.box(Theme.goodSoft, 12, 0, 0));
        Ui.pad(stockOk, 14, 12, 14, 12);
        stockOk.addView(new Widgets.IconView(act, Icons.CHECK, 20, Theme.good), Ui.lp(Ui.dp(20), Ui.dp(20), 0, 0, 10, 0));
        stockOk.addView(Ui.tv(act, "Original firmware v2.70 is saved on this phone.", 14, Theme.text), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        p2.addView(stockOk);
        stockMissing = Ui.vbox(act);
        installedNote = Ui.caption(act, "");
        stockMissing.addView(installedNote, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 10));
        useInstalled = Ui.primary(act, "Use the Razer app on this phone", () -> host.importFromInstalledRazerApp());
        stockMissing.addView(useInstalled, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 8));
        stockMissing.addView(Ui.secondary(act, "Choose a file…", host::importOriginalFirmware), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 8));
        stockMissing.addView(Ui.ghost(act, "Search the web for the Razer Kishi app", () -> host.openUrl("https://www.google.com/search?q=Razer+Kishi+apk")), Ui.lp(Ui.MATCH, Ui.WRAP));
        stockMissing.addView(Ui.caption(act, "The file is Razer's Android app, Razer Kishi version 1.0.34 or 1.0.66 (an .apk), or the 28,108-byte firmware .bin. It is checked against its known fingerprint before it is kept, and it stays on this phone."), Ui.lp(Ui.MATCH, Ui.WRAP, 2, 8, 0, 0));
        p2.addView(stockMissing);
        panes[1] = p2;

        // ---- step 3: switch ----
        LinearLayout p3 = Ui.vbox(act);
        s3Title = Ui.medium(act, "", 22, Theme.text);
        p3.addView(s3Title);
        s3Intro = Ui.caption(act, "");
        p3.addView(s3Intro, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 8, 0, 12));
        s3Steps = Ui.vbox(act);
        s3Steps.setBackground(Ui.box(Theme.panelInset, 14, Theme.stroke, 1));
        Ui.pad(s3Steps, 16, 14, 16, 6);
        String[] steps = { "Take the phone out of the Kishi.", "Press and hold Y + B + the Right Function button.", "Slide the phone back on while still holding them.", "Let go when KishiDS says the bootloader is detected." };
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = Ui.hbox(act);
            TextView num = Ui.medium(act, String.valueOf(i + 1), 12.5f, Theme.accentHi);
            num.setGravity(Gravity.CENTER);
            num.setBackground(Ui.box(Theme.accentSoft, 13, 0, 0));
            row.addView(num, Ui.lp(Ui.dp(26), Ui.dp(26), 0, 0, 12, 0));
            row.addView(Ui.tv(act, steps[i], 14, Theme.text), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
            s3Steps.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 10));
        }
        p3.addView(s3Steps);
        s3Status = Ui.caption(act, "");
        p3.addView(s3Status, Ui.lp(Ui.MATCH, Ui.WRAP, 2, 12, 0, 0));
        panes[2] = p3;

        for (LinearLayout p : panes) card.addView(p, Ui.lp(Ui.MATCH, Ui.WRAP));

        // ---- buttons ----
        LinearLayout buttons = Ui.hbox(act);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        back = Ui.ghost(act, "Back", () -> setStep(step - 1));
        skip = Ui.ghost(act, "Skip for now", this::skipClicked);
        next = Ui.primary(act, "Get started", this::nextClicked);
        buttons.addView(back);
        View spacer = new View(act);
        buttons.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        buttons.addView(skip);
        buttons.addView(next, Ui.lp(Ui.WRAP, Ui.WRAP, 8, 0, 0, 0));
        card.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 20, 0, 0));

        outer.addView(card, new LinearLayout.LayoutParams((int) Math.min(Ui.dpf(520), act.getResources().getDisplayMetrics().widthPixels - Ui.dpf(28)), Ui.WRAP));
        sv.addView(outer, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        addView(sv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
    }

    public void setOnFinished(Runnable r) { onFinished = r; }

    public int step() { return step; }

    public void setStep(int s) {
        step = Math.max(1, Math.min(LAST, s));
        update();
    }

    private void skipClicked() {
        if (step == 2) {
            Ui.message(act, "Skip this step?", "Without the original firmware you won't be able to put the controller back to Razer's version from this app.\n\nYou can still import it later from the Firmware page.", "Skip", "Go back", () -> setStep(3));
        } else finish();
    }

    private void nextClicked() {
        if (step < LAST) { setStep(step + 1); return; }
        boolean live = m.isLive();
        finish();
        if (!live) m.beginApply();   // shows the Firmware page with the update-mode steps
    }

    private void finish() {
        m.markSetupDone();
        if (onFinished != null) onFinished.run();
    }

    /** Called every frame while the guide is up. */
    public void update() {
        for (int i = 0; i < 3; i++) {
            int n = i + 1;
            boolean done = n < step || (n == 2 && m.hasStock() && step > 2);
            boolean current = n == step;
            dotText[i].setBackground(Ui.box(current ? Theme.accent : done ? Theme.goodSoft : Theme.accentSoft, 15, 0, 0));
            dotText[i].setText(done ? "✓" : String.valueOf(n));
            dotText[i].setTextColor(current ? Color.WHITE : done ? Theme.good : Theme.accentHi);
            dotLabel[i].setTextColor(current ? Theme.text : Theme.textDim);
            panes[i].setVisibility(n == step ? VISIBLE : GONE);
        }
        Ui.show(back, step > 1);

        switch (step) {
            case 1:
                next.setText("Get started");
                next.setEnabled(true);
                Ui.show(skip, false);
                welcomeDot.setBackground(Ui.box(m.device == AppModel.Device.DISCONNECTED ? Theme.textFaint : Theme.good, 5, 0, 0));
                welcomeStatus.setText(statusLine());
                break;
            case 2: {
                boolean have = m.hasStock();
                Ui.show(stockOk, have);
                Ui.show(stockMissing, !have);
                next.setText("Continue");
                next.setEnabled(have);
                skip.setText("Skip for now");
                Ui.show(skip, !have);
                long now = android.os.SystemClock.uptimeMillis();
                if (now - installedChecked > 2000) { installedChecked = now; installedCache = !RazerApps.installed(act).isEmpty(); }
                boolean installed = installedCache;
                Ui.show(useInstalled, installed);
                installedNote.setText(installed ? "Razer's Kishi app is installed on this phone, so KishiDS can take the firmware straight from it."
                        : "Install Razer's Kishi app (or download its .apk), then choose it below.");
                break;
            }
            default: {
                boolean live = m.isLive();
                s3Title.setText(live ? "You're all set" : "Switch your controller");
                Ui.show(s3Steps, !live);
                s3Intro.setText(live
                        ? "Your Kishi is already running KishiDS firmware with live editing. Every change you make applies instantly. You can reopen this guide from the More page."
                        : "KishiDS puts the controller into its update mode, writes the new firmware and checks it came back correctly. The controller can always be returned to update mode with the button combination below, even if something goes wrong.");
                next.setText(live ? "Finish" : "Switch my controller");
                next.setEnabled(true);
                skip.setText("Not now");
                Ui.show(skip, !live);
                s3Status.setText(m.hasStock() ? "Razer's original firmware is saved. You can restore it from the Firmware page at any time."
                        : "Razer's original firmware is not saved yet. Going back to stock won't be possible until you import it.");
                s3Status.setTextColor(m.hasStock() ? Theme.textDim : Theme.warn);
                break;
            }
        }
    }

    private String statusLine() {
        switch (m.device) {
            case CUSTOM: return "Your Kishi is already running KishiDS firmware.";
            case CUSTOM_LOCKED: return "Kishi found. Allow USB access when Android asks.";
            case STOCK: return "Kishi found, running Razer's original firmware.";
            case BOOTLOADER: return "Kishi found in update mode.";
            default: return "No controller detected yet. You can plug it in whenever you like.";
        }
    }
}
