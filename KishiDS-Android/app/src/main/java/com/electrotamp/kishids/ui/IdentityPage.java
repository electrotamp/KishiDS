package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.content.Context;
import android.text.InputFilter;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.electrotamp.kishids.app.AppModel;

/** How the controller introduces itself to a phone or PC. */
public final class IdentityPage extends Page {
    private final EditText name;
    private final TextView vid, pid, maker, serial;

    public IdentityPage(Activity act, AppModel m, Host host) {
        super(act, m, host, "Identity", "How the controller introduces itself to your PC or phone.");

        LinearLayout names = card();
        names.addView(Ui.section(act, "Names"), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));
        names.addView(Ui.medium(act, "Device name", 15, Theme.text), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 8));
        name = new EditText(act);
        name.setSingleLine(true);
        name.setFilters(new InputFilter[] { new InputFilter.LengthFilter(31) });
        name.setTextSize(15);
        name.setTextColor(Theme.text);
        name.setHintTextColor(Theme.textFaint);
        name.setTypeface(Ui.regular);
        name.setBackground(Ui.box(Theme.field, 12, Theme.stroke, 1));
        Ui.pad(name, 14, 12, 14, 12);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        name.setOnEditorActionListener((v, actionId, e) -> { commit(); return false; });
        name.setOnFocusChangeListener((v, focus) -> { if (!focus) commit(); });
        names.addView(name, Ui.lp(Ui.MATCH, Ui.WRAP));
        names.addView(Ui.caption(act, "Changing it takes effect after the controller restarts. KishiDS will offer to restart it."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));
        add(names, 16);

        LinearLayout fixed = card();
        fixed.addView(Ui.section(act, "Fixed by the firmware"));
        fixed.addView(Ui.caption(act, "These can't be changed. The USB IDs make the controller identify as a standard wired gamepad, and changing them would break that. The serial number always comes from the controller itself."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 3, 0, 16));
        LinearLayout ids = Ui.hbox(act);
        LinearLayout v = Ui.vbox(act), p = Ui.vbox(act);
        v.addView(Ui.medium(act, "Vendor ID", 14.5f, Theme.text));
        vid = Ui.monoText(act, "", 14, Theme.textDim);
        v.addView(vid, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 2, 0, 0));
        p.addView(Ui.medium(act, "Product ID", 14.5f, Theme.text));
        pid = Ui.monoText(act, "", 14, Theme.textDim);
        p.addView(pid, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 2, 0, 0));
        ids.addView(v, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        ids.addView(p, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        fixed.addView(ids);
        fixed.addView(Ui.medium(act, "Manufacturer", 14.5f, Theme.text), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 14, 0, 2));
        maker = Ui.tv(act, "", 14, Theme.textDim);
        fixed.addView(maker);
        fixed.addView(Ui.medium(act, "Serial number", 14.5f, Theme.text), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 14, 0, 2));
        serial = Ui.monoText(act, "", 14, Theme.textDim);
        fixed.addView(serial);
        fixed.addView(Ui.caption(act, "This is your controller's own serial number, the one printed on the sticker on its back."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
        add(fixed, 14);

        add(Ui.ghost(act, "Reset device name to default", () -> m.config.resetField("product")), 10);
    }

    private void commit() {
        String s = name.getText().toString();
        if (!s.equals(m.s.product())) m.s.setProduct(s);
    }

    @Override protected void onUpdate() {
        if (!name.hasFocus() && !name.getText().toString().equals(m.s.product())) name.setText(m.s.product());
        vid.setText(m.s.vidText());
        pid.setText(m.s.pidText());
        maker.setText(m.s.manufacturer());
        serial.setText(m.serial == null ? "Shown when your Kishi is connected" : m.serial);
    }

    @Override public void onShown() {
        super.onShown();
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(scroll.getWindowToken(), 0);
    }
}
