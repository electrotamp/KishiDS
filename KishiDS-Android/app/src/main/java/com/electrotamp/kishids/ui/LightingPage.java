package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.widget.LinearLayout;

import com.electrotamp.kishids.app.AppModel;
import com.electrotamp.kishids.core.ConfigLayout;

/** The blue status light on the Kishi. */
public final class LightingPage extends Page {
    private final Widgets.LedPreview led;
    private final Rows.SliderRow pulse;

    public LightingPage(Activity act, AppModel m, Host host) {
        super(act, m, host, "Lighting", "The blue status light on your Kishi.");
        led = new Widgets.LedPreview(act);
        LinearLayout preview = card();
        Ui.pad(preview, 10, 10, 10, 10);
        preview.addView(led);

        LinearLayout settings = card();
        settings.addView(Ui.section(act, "Mode"));
        settings.addView(Ui.caption(act, "Off, always on, a slow pulse, or lit only while a game or app is using the controller."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 3, 0, 14));
        settings.addView(choice(ConfigLayout.LED_MODES, "led_mode"), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 22));
        settings.addView(slider("Brightness", "", "led_brightness", "From dim to full."));
        pulse = bind(new Rows.SliderRow(act, "Pulse length", " s", 0.5, 10, 0.1, "How long one fade in and out takes.",
                () -> m.config.get("led_breath") / 10.0, v -> m.config.set("led_breath", (int) Math.round(v * 10))));
        settings.addView(pulse);

        if (wide) {
            LinearLayout row = new LinearLayout(act);
            row.addView(preview, Ui.weight(0.8f, 0, 0, 7, 0));
            row.addView(settings, Ui.weight(1.2f, 7, 0, 0, 0));
            add(row, 16);
        } else {
            add(preview, 16);
            add(settings, 14);
        }
    }

    @Override protected void onUpdate() {
        led.set(m.config.get("led_mode"), m.config.get("led_brightness"), m.config.get("led_breath") / 10.0, m.isConnected());
        Ui.show(pulse, m.config.get("led_mode") == 2);
    }
}
