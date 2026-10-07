package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.widget.LinearLayout;

import com.electrotamp.kishids.app.AppModel;
import com.electrotamp.kishids.core.ConfigLayout;

/** Decide what the four D-pad buttons do. */
public final class DpadPage extends Page {
    public DpadPage(Activity act, AppModel m, Host host) {
        super(act, m, host, "D-pad", "Decide what the four D-pad buttons do.");
        LinearLayout mode = card();
        mode.addView(Ui.section(act, "D-pad acts as"));
        mode.addView(Ui.caption(act, "Use it as a normal D-pad, or have it drive one of the sticks (handy for games that only read sticks)."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 3, 0, 14));
        mode.addView(choice(ConfigLayout.DPAD_MODES, "dpad_mode"));
        add(mode, 16);

        LinearLayout socd = card();
        socd.addView(Ui.section(act, "Opposite directions together"));
        socd.addView(Ui.caption(act, "What happens when Up and Down (or Left and Right) are pressed at the same time."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 3, 0, 14));
        socd.addView(choice(ConfigLayout.SOCD_MODES, "socd_mode"));
        socd.addView(Ui.caption(act, "Neutral cancels both so nothing moves. Up/Right wins keeps Up over Down and Right over Left, which some fighting-game setups prefer."), Ui.lp(Ui.MATCH, Ui.WRAP, 2, 12, 0, 0));
        add(socd, 14);
    }
}
