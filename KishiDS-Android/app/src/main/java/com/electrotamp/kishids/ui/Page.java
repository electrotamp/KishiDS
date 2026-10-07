package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.electrotamp.kishids.app.AppModel;

import java.util.ArrayList;
import java.util.List;

/** One screen of the app: a scrolling column built once, then refreshed every frame from the model. */
public abstract class Page {
    protected final Activity act;
    protected final Context c;
    protected final AppModel m;
    protected final Host host;
    protected final boolean wide;
    protected final List<Ui.Refreshable> bound = new ArrayList<>();
    public final ScrollView scroll;
    protected final LinearLayout column;

    protected Page(Activity act, AppModel m, Host host, String title, String caption) {
        this.act = act;
        this.c = act;
        this.m = m;
        this.host = host;
        this.wide = Ui.wide(act);
        scroll = new ScrollView(act);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        column = Ui.vbox(act);
        scroll.addView(column, Ui.lp(Ui.MATCH, Ui.WRAP));
        boolean compact = Ui.compact(act);
        float side = wide ? 20 : 16;
        Ui.pad(column, side, compact ? 8 : wide ? 18 : 12, side, compact ? 80 : 120);
        if (title != null) {
            TextView t = Ui.pageTitle(act, title);
            if (compact) t.setTextSize(24);
            column.addView(t);
            if (caption != null) column.addView(Ui.caption(act, caption), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
        }
    }

    /** Add a view with a top gap (dp). */
    protected <T extends View> T add(T v, float topDp) {
        column.addView(v, Ui.lp(Ui.MATCH, Ui.WRAP, 0, topDp, 0, 0));
        return v;
    }

    /** Two cards side by side on wide screens, stacked on phones. */
    protected LinearLayout pair(View a, View b, float topDp) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (wide) {
            row.addView(a, Ui.weight(1, 0, 0, 7, 0));
            row.addView(b, Ui.weight(1, 7, 0, 0, 0));
        } else {
            row.addView(a, Ui.lp(Ui.MATCH, Ui.WRAP));
            row.addView(b, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        }
        add(row, topDp);
        return row;
    }

    protected <T extends View & Ui.Refreshable> T bind(T v) {
        bound.add(v);
        return v;
    }

    protected LinearLayout card() { return Ui.card(act); }

    protected Rows.SliderRow slider(String label, String unit, String field, String hint) {
        return bind(Rows.SliderRow.field(act, m.config, label, unit, field, hint));
    }

    protected Rows.ToggleRow toggle(String label, String hint, String field, int bit) {
        return bind(new Rows.ToggleRow(act, label, hint, () -> m.config.getBit(field, bit), v -> m.config.setBit(field, bit, v)));
    }

    protected Rows.ChoiceRow choice(String[] options, String field) {
        return bind(new Rows.ChoiceRow(act, options, () -> m.config.get(field), v -> m.config.set(field, v)));
    }

    /** Called every frame. */
    public void update() {
        for (int i = 0; i < bound.size(); i++) bound.get(i).refresh();
        onUpdate();
    }

    protected void onUpdate() { }

    /** Called when the page becomes the visible one. */
    public void onShown() { scroll.scrollTo(0, 0); }
}
