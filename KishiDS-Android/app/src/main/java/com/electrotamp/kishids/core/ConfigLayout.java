// Output of tools/gen_config.py -- do not edit by hand.
package com.electrotamp.kishids.core;

public final class ConfigLayout {
    private ConfigLayout() {}

    public static final int SIZE = 256;
    public static final int VERSION = 1;
    public static final String MAGIC = "KISHICFG";
    public static final int CRC_START = 16;

    public enum Kind { U8, U16, U32, CHAR }

    public static final class FieldDef {
        public final String name;
        public final int offset, count;
        public final Kind kind;
        public final int[] defaults;
        public final String defaultText, doc;
        public final Integer min, max;
        public final boolean locked;

        FieldDef(String name, int offset, Kind kind, int count, int[] defaults, String defaultText, Integer min, Integer max, String doc, boolean locked) {
            this.name = name; this.offset = offset; this.kind = kind; this.count = count; this.defaults = defaults;
            this.defaultText = defaultText; this.min = min; this.max = max; this.doc = doc; this.locked = locked;
        }

        public int elementSize() { return kind == Kind.U16 ? 2 : kind == Kind.U32 ? 4 : 1; }
        public int byteSize() { return count * elementSize(); }
    }

    public static final String[] OUTPUTS = { "None", "Square", "Cross", "Circle", "Triangle", "L1", "R1", "L2", "R2", "Share", "Options", "L3", "R3", "PS", "Touchpad", "DpadUp", "DpadDown", "DpadLeft", "DpadRight", "TouchLeft", "TouchRight" };
    public static final String[] KISHI_BUTTONS = { "A", "B", "X", "Y", "Up", "Down", "Left", "Right", "L1", "R1", "L3", "R3", "Right Function", "Home", "Left Function" };
    public static final String[] CURVES = { "Linear", "Precise", "Aggressive" };
    public static final String[] DPAD_MODES = { "D-pad", "Left stick", "Right stick", "Disabled" };
    public static final String[] SOCD_MODES = { "Neutral", "Up/Right wins" };
    public static final String[] LED_MODES = { "Off", "Solid", "Breathing", "While in use" };

    public static final FieldDef[] FIELDS = {
        new FieldDef("magic", 0, Kind.CHAR, 8, new int[0], "KISHICFG", null, null, "Marker used to find the block in the image", false),
        new FieldDef("version", 8, Kind.U16, 1, new int[] { 1 }, "", null, null, "Layout version", false),
        new FieldDef("size", 10, Kind.U16, 1, new int[] { 256 }, "", null, null, "Block size in bytes", false),
        new FieldDef("crc32", 12, Kind.U32, 1, new int[] { 0 }, "", null, null, "CRC-32 (ISO-HDLC) of bytes 16..size-1", false),
        new FieldDef("button_map", 16, Kind.U8, 16, new int[] { 2, 3, 1, 4, 15, 16, 17, 18, 5, 6, 11, 12, 10, 13, 9, 0 }, "", 0, 20, "DS4 output code for each Kishi button (index = scan order)", false),
        new FieldDef("stick_flags", 32, Kind.U8, 1, new int[] { 0 }, "", 0, 31, "bit0 invert LX, bit1 invert LY, bit2 invert RX, bit3 invert RY, bit4 swap sticks", false),
        new FieldDef("left_dead", 33, Kind.U8, 1, new int[] { 8 }, "", 0, 50, "Left stick deadzone, % of travel", false),
        new FieldDef("right_dead", 34, Kind.U8, 1, new int[] { 8 }, "", 0, 50, "Right stick deadzone, % of travel", false),
        new FieldDef("left_outer", 35, Kind.U8, 1, new int[] { 90 }, "", 50, 100, "Left stick full-scale point, % of travel", false),
        new FieldDef("right_outer", 36, Kind.U8, 1, new int[] { 90 }, "", 50, 100, "Right stick full-scale point, % of travel", false),
        new FieldDef("left_curve", 37, Kind.U8, 1, new int[] { 0 }, "", 0, 2, "Left stick response curve", false),
        new FieldDef("right_curve", 38, Kind.U8, 1, new int[] { 0 }, "", 0, 2, "Right stick response curve", false),
        new FieldDef("trig_thresh", 39, Kind.U8, 1, new int[] { 10 }, "", 0, 255, "Digital L2/R2 press threshold (0-255 of analog travel)", false),
        new FieldDef("l2_dead", 40, Kind.U8, 1, new int[] { 0 }, "", 0, 50, "L2 inner deadzone, %", false),
        new FieldDef("r2_dead", 41, Kind.U8, 1, new int[] { 0 }, "", 0, 50, "R2 inner deadzone, %", false),
        new FieldDef("l2_outer", 42, Kind.U8, 1, new int[] { 100 }, "", 50, 100, "L2 full-scale point, %", false),
        new FieldDef("r2_outer", 43, Kind.U8, 1, new int[] { 100 }, "", 50, 100, "R2 full-scale point, %", false),
        new FieldDef("trig_flags", 44, Kind.U8, 1, new int[] { 0 }, "", 0, 3, "bit0 swap L2/R2, bit1 digital-only triggers (0/255)", false),
        new FieldDef("dpad_mode", 45, Kind.U8, 1, new int[] { 0 }, "", 0, 3, "What the D-pad buttons drive", false),
        new FieldDef("socd_mode", 46, Kind.U8, 1, new int[] { 0 }, "", 0, 1, "Opposite D-pad directions pressed together", false),
        new FieldDef("led_mode", 47, Kind.U8, 1, new int[] { 1 }, "", 0, 3, "Blue LED behaviour", false),
        new FieldDef("led_brightness", 48, Kind.U8, 1, new int[] { 255 }, "", 0, 255, "Blue LED brightness", false),
        new FieldDef("led_breath", 49, Kind.U8, 1, new int[] { 20 }, "", 5, 100, "Breathing period in 0.1 s units", false),
        new FieldDef("poll_ms", 50, Kind.U8, 1, new int[] { 5 }, "", 1, 8, "USB interrupt polling interval in ms", false),
        new FieldDef("calib_mode", 51, Kind.U8, 1, new int[] { 0 }, "", 0, 1, "0 = stock calibration page, 1 = calibration stored below", false),
        new FieldDef("rumble_level", 52, Kind.U8, 1, new int[] { 0 }, "", 0, 100, "Rumble strength at full DS4 level, % of the actuators' full scale (0 = board default)", false),
        new FieldDef("led_rgb", 53, Kind.U8, 3, new int[] { 0, 0, 0 }, "", 0, 255, "RGB LED colour (R, G, B; Kishi V2 Pro); 0, 0, 0 = blue", false),
        new FieldDef("cal_smax", 56, Kind.U16, 4, new int[] { 3264, 3317, 3395, 3360 }, "", 0, 4095, "Stick max raw (RX, RY, LX, LY)", false),
        new FieldDef("cal_smin", 64, Kind.U16, 4, new int[] { 566, 558, 718, 594 }, "", 0, 4095, "Stick min raw (RX, RY, LX, LY)", false),
        new FieldDef("cal_scenter", 72, Kind.U16, 4, new int[] { 1905, 2000, 2036, 1971 }, "", 0, 4095, "Stick centre raw (RX, RY, LX, LY)", false),
        new FieldDef("cal_t_lo", 80, Kind.U16, 2, new int[] { 579, 542 }, "", 0, 4095, "Trigger pressed raw (L2, R2)", false),
        new FieldDef("cal_t_hi", 84, Kind.U16, 2, new int[] { 1905, 1806 }, "", 0, 4095, "Trigger rest raw (L2, R2)", false),
        new FieldDef("vid", 88, Kind.U16, 1, new int[] { 1356 }, "", null, null, "USB vendor ID (locked: DS4)", true),
        new FieldDef("pid", 90, Kind.U16, 1, new int[] { 1476 }, "", null, null, "USB product ID (locked: DS4)", true),
        new FieldDef("bcd_device", 92, Kind.U16, 1, new int[] { 256 }, "", null, null, "USB device release (BCD) (locked)", true),
        new FieldDef("reserved2", 94, Kind.U8, 2, new int[] { 0, 0 }, "", null, null, "reserved", false),
        new FieldDef("manufacturer", 96, Kind.CHAR, 32, new int[0], "ElectroTamp KishiDS", null, null, "USB manufacturer string (locked)", true),
        new FieldDef("product", 128, Kind.CHAR, 32, new int[0], "Wireless Controller", null, null, "USB product string (user-visible device name)", false),
        new FieldDef("serial", 160, Kind.CHAR, 32, new int[0], "", null, null, "USB serial string (locked: always the controller's factory serial, see kcfg_resolve_serial)", true),
        new FieldDef("led_fixed", 192, Kind.U8, 1, new int[] { 0 }, "", 0, 1, "RGB LED: 0 = games/apps may change the colour (DS4 lightbar), 1 = always led_rgb", false),
        new FieldDef("button_map2", 193, Kind.U8, 4, new int[] { 0, 0, 0, 0 }, "", 0, 20, "DS4 output code for buttons 16..19 (Kishi V2 Pro: M1, M2)", false),
        new FieldDef("reserved3", 197, Kind.U8, 59, new int[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, "", null, null, "reserved", false),
    };
}
