package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.electrotamp.kishids.app.AppModel;

/** A live view of the Kishi, a summary of the main settings, and profile load/save. */
public final class OverviewPage extends Page {
    private final Widgets.ControllerView controller;
    private final TextView title, detail, kvDevice, kvIds, kvMode, kvSerial;
    private final Ui.Button apply, allow;
    private final Tile tileButtons, tileRate, tileLed, tileName;

    /** A tappable summary tile: a coloured card with an eyebrow label, a value and a caption. */
    private static final class Tile {
        LinearLayout view;
        TextView value, caption;
    }

    public OverviewPage(Activity act, AppModel m, Host host) {
        super(act, m, host, "Overview", "A live view of your Kishi. Press buttons and move the sticks to see them respond.");

        // ---- hero ----
        LinearLayout hero = Ui.vbox(act);
        hero.setBackground(Ui.gradientBox(Theme.heroA, Theme.heroB, 22, Theme.stroke, 1, GradientDrawable.Orientation.TL_BR));
        Ui.pad(hero, 18, 18, 18, 18);

        LinearLayout info = Ui.vbox(act);
        LinearLayout head = Ui.hbox(act);
        head.setGravity(Gravity.TOP);
        LinearLayout tile = new LinearLayout(act);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(Ui.box(Theme.iconTileBg, 13, Theme.iconTileStroke, 1));
        tile.addView(new Widgets.IconView(act, Icons.LINK, 24, Theme.iconTileFg));
        head.addView(tile, Ui.lp(Ui.dp(46), Ui.dp(46)));
        LinearLayout words = Ui.vbox(act);
        title = Ui.medium(act, "", Ui.compact(act) ? 21 : 24, Theme.text);
        detail = Ui.caption(act, "");
        detail.setTextSize(14);
        words.addView(title);
        words.addView(detail, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
        head.addView(words, Ui.weight(1, 14, 0, 0, 0));
        info.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP));

        apply = Ui.primary(act, "Apply to controller", () -> m.beginApply());
        info.addView(apply, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 14, 0, 0));
        allow = Ui.primary(act, "Allow USB access", () -> m.allowAccess());
        info.addView(allow, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 14, 0, 0));

        info.addView(Ui.divider(act), Ui.lp(Ui.MATCH, Ui.dp(1), 0, 16, 0, 10));
        kvDevice = kv(info, "Device");
        kvIds = kv(info, "Product ID");
        kvMode = kv(info, "Mode");
        kvSerial = kv(info, "Serial");

        controller = new Widgets.ControllerView(act);
        if (wide) {
            hero.setOrientation(LinearLayout.HORIZONTAL);
            hero.setGravity(Gravity.CENTER_VERTICAL);
            hero.addView(info, Ui.weight(1.15f, 0, 0, 14, 0));
            hero.addView(controller, Ui.weight(1.4f, 0, 0, 0, 0));
        } else {
            hero.addView(info, Ui.lp(Ui.MATCH, Ui.WRAP));
            hero.addView(controller, Ui.lp(Ui.MATCH, Ui.WRAP, -4, 14, -4, 0));
        }
        add(hero, 18);

        // ---- summary tiles ----
        tileButtons = tile(Theme.tile1a, Theme.tile1b, Theme.tile1Stroke, Theme.tile1Eyebrow, Theme.tile1Icon, Icons.BUTTONS, "Buttons", Host.BUTTONS);
        tileRate = tile(Theme.tile2a, Theme.tile2b, Theme.tile2Stroke, Theme.tile2Eyebrow, Theme.tile2Icon, Icons.PULSE, "Report rate", Host.CALIBRATION);
        tileLed = tile(Theme.tile3a, Theme.tile3b, Theme.tile3Stroke, Theme.tile3Eyebrow, Theme.tile3Icon, Icons.SUN, "Lighting", Host.LIGHTING);
        tileName = tile(Theme.tile1a, Theme.tile1b, Theme.tile1Stroke, Theme.tile1Eyebrow, Theme.tile1Icon, Icons.PERSON, "Appears as", Host.IDENTITY);
        LinearLayout t1 = tileButtons.view, t2 = tileRate.view, t3 = tileLed.view, t4 = tileName.view;
        LinearLayout grid = Ui.vbox(act);
        if (wide) {
            LinearLayout row = Ui.hbox(act);
            row.addView(t1, Ui.weightFill(1, 0, 0, 6, 0));
            row.addView(t2, Ui.weightFill(1, 3, 0, 3, 0));
            row.addView(t3, Ui.weightFill(1, 3, 0, 3, 0));
            row.addView(t4, Ui.weightFill(1, 6, 0, 0, 0));
            grid.addView(row);
        } else {
            LinearLayout r1 = Ui.hbox(act), r2 = Ui.hbox(act);
            r1.addView(t1, Ui.weightFill(1, 0, 0, 6, 0));
            r1.addView(t2, Ui.weightFill(1, 6, 0, 0, 0));
            r2.addView(t3, Ui.weightFill(1, 0, 0, 6, 0));
            r2.addView(t4, Ui.weightFill(1, 6, 0, 0, 0));
            grid.addView(r1);
            grid.addView(r2, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
        }
        add(grid, 14);

        // ---- profiles ----
        LinearLayout prof = Ui.vbox(act);
        prof.setBackground(Ui.gradientBox(Theme.heroA, Theme.heroB, 22, Theme.stroke, 1, GradientDrawable.Orientation.TL_BR));
        Ui.pad(prof, 18, 16, 18, 14);
        LinearLayout ph = Ui.hbox(act);
        ph.addView(new Widgets.IconView(act, Icons.LAYERS, 34, Theme.textDim));
        LinearLayout pw = Ui.vbox(act);
        pw.addView(Ui.medium(act, "Profiles", 19, Theme.text));
        pw.addView(Ui.caption(act, "Save your settings to a file, share them, or load someone else's."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 3, 0, 0));
        ph.addView(pw, Ui.weight(1, 16, 0, 0, 0));
        prof.addView(ph);
        LinearLayout pb = Ui.hbox(act);
        pb.addView(Ui.secondary(act, "Load…", host::loadProfile), Ui.weight(1, 0, 0, 5, 0));
        pb.addView(Ui.primary(act, "Save…", host::saveProfile), Ui.weight(1, 5, 0, 5, 0));
        pb.addView(Ui.ghost(act, "Reset all", () -> Ui.message(act, "Reset all settings?", "Every button, stick, trigger, lighting and calibration setting goes back to its default.", "Reset", "Cancel", m.config::resetAll)), Ui.weight(1, 5, 0, 0, 0));
        prof.addView(pb, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        add(prof, 14);
    }

    private TextView kv(LinearLayout parent, String label) {
        LinearLayout row = Ui.hbox(act);
        TextView l = Ui.caption(act, label + ":");
        row.addView(l, Ui.lp(Ui.dp(92), Ui.WRAP));
        TextView v = Ui.tv(act, "", 14, Theme.text);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(v, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        parent.addView(row, Ui.lp(Ui.MATCH, Ui.dp(28)));
        return v;
    }

    private Tile tile(int a, int b, int stroke, int eyebrowColor, int iconColor, String icon, String eyebrow, String target) {
        Tile r = new Tile();
        LinearLayout t = Ui.hbox(act);
        t.setGravity(Gravity.TOP);
        t.setBackground(Ui.ripple(Ui.gradientBox(a, b, 18, stroke, 1, GradientDrawable.Orientation.TL_BR), 18));
        Ui.pad(t, 14, 14, 10, 14);
        t.setMinimumHeight(Ui.dp(96));
        t.addView(new Widgets.IconView(act, icon, 26, iconColor), Ui.lp(Ui.WRAP, Ui.WRAP, 0, 2, 10, 0));
        LinearLayout w = Ui.vbox(act);
        TextView e = Ui.eyebrow(act, eyebrow);
        e.setTextColor(eyebrowColor);
        r.value = Ui.medium(act, "", 20, Theme.text);
        r.value.setSingleLine(true);
        r.value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.caption = Ui.caption(act, "");
        r.caption.setTextSize(12);
        r.caption.setSingleLine(true);
        r.caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        w.addView(e);
        w.addView(r.value, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
        w.addView(r.caption, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 2, 0, 0));
        t.addView(w, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        t.setClickable(true);
        t.setOnClickListener(v -> host.go(target));
        r.view = t;
        return r;
    }

    @Override protected void onUpdate() {
        controller.set(m.live, m.tele == null ? 0 : m.tele.buttonMask, m.isConnected(), m.config.get("led_mode"));
        title.setText(m.deviceTitle());
        detail.setText(m.deviceDetail());
        Ui.show(apply, m.showApplyButton() && m.device != AppModel.Device.CUSTOM_LOCKED && m.canStart());
        Ui.show(allow, m.device == AppModel.Device.CUSTOM_LOCKED && m.accessDenied);
        kvDevice.setText(m.s.product());
        kvIds.setText(m.s.vidText() + " : " + m.s.pidText());
        kvMode.setText(m.s.modeText());
        kvSerial.setText(m.serial == null ? "—" : m.serial);

        tileButtons.value.setText(String.valueOf(m.s.remappedCount()));
        tileButtons.caption.setText("remapped from default");
        tileRate.value.setText(m.s.pollHz() + " Hz");
        tileRate.caption.setText("USB polling");
        tileLed.value.setText(m.s.ledModeName());
        tileLed.caption.setText("blue status LED");
        tileName.value.setText(m.s.product());
        tileName.value.setSingleLine(false);
        tileName.value.setMaxLines(2);
        tileName.value.setTextSize(18);
        tileName.caption.setText(m.s.vidText() + " : " + m.s.pidText());
    }
}
