package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.electrotamp.kishids.app.AppModel;

/** The pages that do not fit the phone's bottom bar, as a list. */
public final class MorePage extends Page {
    private final TextView[] subtitles = new TextView[5];

    public MorePage(Activity act, AppModel m, Host host) {
        super(act, m, host, "More", "Everything else the controller can do.");
        add(entry(0, Icons.DPAD, "D-pad", "Normal D-pad, or have it drive a stick", Host.DPAD), 16);
        add(entry(1, Icons.SUN, "Lighting", "The blue status light", Host.LIGHTING), 10);
        add(entry(2, Icons.PULSE, "Calibration", "Measure your sticks and triggers, report rate", Host.CALIBRATION), 10);
        add(entry(3, Icons.PERSON, "Identity", "The name your phone sees", Host.IDENTITY), 10);
        add(entry(4, Icons.DOWNLOAD, "Firmware", "Update the controller or restore Razer's firmware", Host.FIRMWARE), 10);

        LinearLayout about = card();
        about.addView(Ui.section(act, "KishiDS for Android"));
        String version = "";
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            version = "Version " + pi.versionName + ". ";
        } catch (Exception e) { /* optional */ }
        about.addView(Ui.caption(act, version + "Configures the KishiDS firmware on your Razer Kishi V1 over its USB-C connection, the same way the Windows app does. Settings are saved on the controller, so they work in every game."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 4, 0, 12));
        LinearLayout row = Ui.hbox(act);
        row.addView(Ui.secondary(act, "Replay tutorial", () -> host.showSetup(1)), Ui.weight(1, 0, 0, 5, 0));
        row.addView(Ui.secondary(act, "Project page", () -> host.openUrl("https://github.com/electrotamp/KishiDS")), Ui.weight(1, 5, 0, 0, 0));
        about.addView(row);
        add(about, 16);
    }

    private LinearLayout entry(int index, String icon, String title, String sub, String target) {
        LinearLayout row = Ui.hbox(act);
        row.setBackground(Ui.ripple(Ui.box(Theme.card, 18, Theme.stroke, 1), 18));
        Ui.pad(row, 16, 14, 12, 14);
        row.setMinimumHeight(Ui.dp(76));
        LinearLayout tile = new LinearLayout(act);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(Ui.box(Theme.iconTileBg, 13, Theme.iconTileStroke, 1));
        tile.addView(new Widgets.IconView(act, icon, 24, Theme.iconTileFg));
        row.addView(tile, Ui.lp(Ui.dp(46), Ui.dp(46), 0, 0, 14, 0));
        LinearLayout words = Ui.vbox(act);
        words.addView(Ui.medium(act, title, 17, Theme.text));
        subtitles[index] = Ui.caption(act, sub);
        words.addView(subtitles[index], Ui.lp(Ui.MATCH, Ui.WRAP, 0, 2, 0, 0));
        row.addView(words, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        row.addView(new Widgets.IconView(act, Icons.CHEVRON, 20, Theme.textDim));
        row.setClickable(true);
        row.setOnClickListener(v -> host.go(target));
        return row;
    }

    @Override protected void onUpdate() {
        subtitles[1].setText("The blue status light · " + m.s.ledModeName());
        subtitles[2].setText("Sticks, triggers, report rate · " + m.s.pollHz() + " Hz");
        subtitles[3].setText("The name your phone sees · " + m.s.product());
    }
}
