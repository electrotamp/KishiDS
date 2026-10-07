package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.widget.LinearLayout;

import com.electrotamp.kishids.app.AppModel;
import com.electrotamp.kishids.core.ConfigLayout;

/** Tune how each stick responds.  The preview follows the real stick. */
public final class SticksPage extends Page {
    private final Widgets.StickPreview leftPreview, rightPreview;
    private final Widgets.CurveGraph leftCurve, rightCurve;

    public SticksPage(Activity act, AppModel m, Host host) {
        super(act, m, host, "Sticks", "Tune how each stick responds. The preview follows your real stick.");
        leftPreview = new Widgets.StickPreview(act);
        rightPreview = new Widgets.StickPreview(act);
        leftCurve = new Widgets.CurveGraph(act);
        rightCurve = new Widgets.CurveGraph(act);
        LinearLayout left = stickCard("Left stick", "left", 0, 1, leftPreview, leftCurve);
        LinearLayout right = stickCard("Right stick", "right", 2, 3, rightPreview, rightCurve);
        pair(left, right, 16);
        LinearLayout swap = card();
        swap.addView(toggle("Swap left and right sticks", "The left stick drives the right stick's output and the other way round.", "stick_flags", 4));
        add(swap, 14);
    }

    private LinearLayout stickCard(String title, String side, int invertX, int invertY, Widgets.StickPreview preview, Widgets.CurveGraph curve) {
        LinearLayout card = card();
        card.addView(Ui.section(act, title));
        LinearLayout visuals = Ui.hbox(act);
        visuals.addView(preview, Ui.weight(1, 0, 0, 6, 0));
        LinearLayout resp = Ui.vbox(act);
        resp.addView(Ui.eyebrow(act, "Response"), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 6));
        resp.addView(curve);
        visuals.addView(resp, Ui.weight(1, 6, 0, 0, 0));
        card.addView(visuals, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 14, 0, 16));
        card.addView(slider("Deadzone", "%", side + "_dead", "Ignore small movements around the centre."));
        card.addView(slider("Full-scale point", "%", side + "_outer", "How far you push before the output reaches 100%."));
        card.addView(Ui.medium(act, "Curve", 15, Theme.text), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 8));
        card.addView(choice(ConfigLayout.CURVES, side + "_curve"), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 10));
        card.addView(toggle("Invert horizontal", null, "stick_flags", invertX));
        card.addView(toggle("Invert vertical", null, "stick_flags", invertY));
        return card;
    }

    @Override protected void onUpdate() {
        leftPreview.set(m.config.get("left_dead"), m.config.get("left_outer"), m.live.lx, m.live.ly);
        rightPreview.set(m.config.get("right_dead"), m.config.get("right_outer"), m.live.rx, m.live.ry);
        leftCurve.set(m.config.get("left_dead"), m.config.get("left_outer"), m.config.get("left_curve"));
        rightCurve.set(m.config.get("right_dead"), m.config.get("right_outer"), m.config.get("right_curve"));
    }
}
