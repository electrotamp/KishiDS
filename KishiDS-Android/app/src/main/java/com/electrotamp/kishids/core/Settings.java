package com.electrotamp.kishids.core;

/**
 * Typed view of the config block for the pages (the Android counterpart of the desktop app's Settings class).  Every setter goes
 * through {@link ConfigBlock#set}, so values are clamped and listeners fire.
 */
public final class Settings {
    public final ConfigBlock cfg;

    public Settings(ConfigBlock cfg) { this.cfg = cfg; }

    // Output names as shown in the mapping pickers.
    public static final String[] OUTPUT_LABELS = prettyOutputs();

    private static String[] prettyOutputs() {
        String[] o = new String[ConfigLayout.OUTPUTS.length];
        for (int i = 0; i < o.length; i++) {
            switch (ConfigLayout.OUTPUTS[i]) {
                case "None": o[i] = "Disabled"; break;
                case "DpadUp": o[i] = "D-pad Up"; break;
                case "DpadDown": o[i] = "D-pad Down"; break;
                case "DpadLeft": o[i] = "D-pad Left"; break;
                case "DpadRight": o[i] = "D-pad Right"; break;
                case "PS": o[i] = "PS button"; break;
                case "Touchpad": o[i] = "Touchpad click"; break;
                default: o[i] = ConfigLayout.OUTPUTS[i];
            }
        }
        return o;
    }

    // ---- Buttons ----
    public static final int BUTTON_COUNT = ConfigLayout.KISHI_BUTTONS.length;

    public int output(int button) { return cfg.get("button_map", button); }

    public void setOutput(int button, int value) { cfg.set("button_map", value, button); }

    public boolean isDefault(int button) { return output(button) == ConfigBlock.field("button_map").defaults[button]; }

    public int remappedCount() {
        int n = 0;
        for (int i = 0; i < BUTTON_COUNT; i++) if (!isDefault(i)) n++;
        return n;
    }

    public void resetButtons() {
        for (int i = 0; i < BUTTON_COUNT; i++) setOutput(i, ConfigBlock.field("button_map").defaults[i]);
    }

    /** Swap what two physical buttons do (by name from {@link ConfigLayout#KISHI_BUTTONS}). */
    public void swapButtons(String a, String b) {
        int ia = indexOfButton(a), ib = indexOfButton(b);
        int oa = output(ia), ob = output(ib);
        setOutput(ia, ob);
        setOutput(ib, oa);
    }

    public static int indexOfButton(String name) {
        for (int i = 0; i < BUTTON_COUNT; i++) if (ConfigLayout.KISHI_BUTTONS[i].equals(name)) return i;
        throw new IllegalArgumentException(name);
    }

    // ---- Sticks ----
    public int get(String field) { return cfg.get(field); }

    public void set(String field, int v) { cfg.set(field, v); }

    public boolean bit(String field, int bit) { return cfg.getBit(field, bit); }

    public void setBit(String field, int bit, boolean on) { cfg.setBit(field, bit, on); }

    // ---- LED ----
    public String ledModeName() { return ConfigLayout.LED_MODES[Math.max(0, Math.min(get("led_mode"), ConfigLayout.LED_MODES.length - 1))]; }

    public double ledBreathSeconds() { return get("led_breath") / 10.0; }

    // ---- USB identity ----
    public String manufacturer() { return cfg.getString("manufacturer"); }

    public String product() { return cfg.getString("product"); }

    public void setProduct(String s) { cfg.setString("product", s); }

    public int pollMs() { return get("poll_ms"); }

    public int pollHz() { return 1000 / Math.max(1, pollMs()); }

    public String vidText() { return String.format("%04X", cfg.get("vid")); }

    public String pidText() { return String.format("%04X", cfg.get("pid")); }

    public String modeText() { return vidText().equals("054C") && pidText().equals("05C4") ? "DualShock 4 (DS4)" : "Custom device"; }

    public boolean useCustomCalibration() { return cfg.get("calib_mode") == 1; }
}
