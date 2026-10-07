package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.widget.LinearLayout;

import com.electrotamp.kishids.app.AppModel;

/** Squeeze a trigger to see it move.  The yellow marker is where it counts as a click. */
public final class TriggersPage extends Page {
    private final Widgets.TriggerBar l2 = new Widgets.TriggerBar(act, "L2"), r2 = new Widgets.TriggerBar(act, "R2");

    public TriggersPage(Activity act, AppModel m, Host host) {
        super(act, m, host, "Triggers", "Squeeze a trigger to see it move. The yellow marker is where it counts as a click.");
        LinearLayout bars = card();
        bars.addView(l2, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 6));
        bars.addView(r2);
        add(bars, 16);

        LinearLayout left = card();
        left.addView(Ui.section(act, "Left trigger"), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));
        left.addView(slider("Dead travel at the top", "%", "l2_dead", "Ignore the first part of the pull."));
        left.addView(slider("Full-press point", "%", "l2_outer", "Reach 100% before the trigger bottoms out."));
        LinearLayout right = card();
        right.addView(Ui.section(act, "Right trigger"), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));
        right.addView(slider("Dead travel at the top", "%", "r2_dead", "Ignore the first part of the pull."));
        right.addView(slider("Full-press point", "%", "r2_outer", "Reach 100% before the trigger bottoms out."));
        pair(left, right, 14);

        LinearLayout behaviour = card();
        behaviour.addView(Ui.section(act, "Behaviour"), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));
        behaviour.addView(slider("Click threshold", "", "trig_thresh", "How far a trigger must be pulled before it also counts as a button press (0–255)."));
        behaviour.addView(toggle("Digital triggers", "Report each trigger as fully released or fully pressed, with no analogue range.", "trig_flags", 1));
        behaviour.addView(toggle("Swap triggers", "The left trigger acts as the right one and the other way round.", "trig_flags", 0));
        add(behaviour, 14);
    }

    @Override protected void onUpdate() {
        int t = m.config.get("trig_thresh");
        l2.set(m.live.l2, t);
        r2.set(m.live.r2, t);
    }
}
