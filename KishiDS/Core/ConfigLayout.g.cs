// Output of tools/gen_config.py -- do not edit by hand.
namespace KishiDS.Core;

public enum FieldKind { U8, U16, U32, Char }

public sealed record FieldDef(string Name, int Offset, FieldKind Kind, int Count, int[] Default, string DefaultText, int? Min, int? Max, string Doc, bool Locked = false)
{
    public int ByteSize => Count * (Kind switch { FieldKind.U16 => 2, FieldKind.U32 => 4, _ => 1 });
}

public static class ConfigLayout
{
    public const int Size = 256;
    public const int Version = 1;
    public const string Magic = "KISHICFG";
    public const int CrcStart = 16;

    public static readonly string[] Outputs = { "None", "Square", "Cross", "Circle", "Triangle", "L1", "R1", "L2", "R2", "Share", "Options", "L3", "R3", "PS", "Touchpad", "DpadUp", "DpadDown", "DpadLeft", "DpadRight", "TouchLeft", "TouchRight" };
    public static readonly string[] KishiButtons = { "A", "B", "X", "Y", "Up", "Down", "Left", "Right", "L1", "R1", "L3", "R3", "Right Function", "Home", "Left Function" };
    public static readonly string[] V2ProButtons = { "A", "B", "X", "Y", "Up", "Down", "Left", "Right", "L1", "R1", "L3", "R3", "Menu", "Nexus", "View", "Share", "M1", "M2" };
    public static readonly int[] V2ProButtonMap = { 2, 3, 1, 4, 15, 16, 17, 18, 5, 6, 11, 12, 10, 13, 14, 9, 19, 20, 0, 0 };
    public static readonly string[] Curves = { "Linear", "Precise", "Aggressive" };
    public static readonly string[] DpadModes = { "D-pad", "Left stick", "Right stick", "Disabled" };
    public static readonly string[] SocdModes = { "Neutral", "Up/Right wins" };
    public static readonly string[] LedModes = { "Off", "Solid", "Breathing", "While in use" };

    public static readonly FieldDef[] Fields =
    {
        new("magic", 0, FieldKind.Char, 8, new int[0], "KISHICFG", null, null, "Marker used to find the block in the image"),
        new("version", 8, FieldKind.U16, 1, new[] { 1 }, "", null, null, "Layout version"),
        new("size", 10, FieldKind.U16, 1, new[] { 256 }, "", null, null, "Block size in bytes"),
        new("crc32", 12, FieldKind.U32, 1, new[] { 0 }, "", null, null, "CRC-32 (ISO-HDLC) of bytes 16..size-1"),
        new("button_map", 16, FieldKind.U8, 16, new[] { 2, 3, 1, 4, 15, 16, 17, 18, 5, 6, 11, 12, 10, 13, 9, 0 }, "", 0, 20, "DS4 output code for each Kishi button (index = scan order)"),
        new("stick_flags", 32, FieldKind.U8, 1, new[] { 0 }, "", 0, 31, "bit0 invert LX, bit1 invert LY, bit2 invert RX, bit3 invert RY, bit4 swap sticks"),
        new("left_dead", 33, FieldKind.U8, 1, new[] { 8 }, "", 0, 50, "Left stick deadzone, % of travel"),
        new("right_dead", 34, FieldKind.U8, 1, new[] { 8 }, "", 0, 50, "Right stick deadzone, % of travel"),
        new("left_outer", 35, FieldKind.U8, 1, new[] { 90 }, "", 50, 100, "Left stick full-scale point, % of travel"),
        new("right_outer", 36, FieldKind.U8, 1, new[] { 90 }, "", 50, 100, "Right stick full-scale point, % of travel"),
        new("left_curve", 37, FieldKind.U8, 1, new[] { 0 }, "", 0, 2, "Left stick response curve"),
        new("right_curve", 38, FieldKind.U8, 1, new[] { 0 }, "", 0, 2, "Right stick response curve"),
        new("trig_thresh", 39, FieldKind.U8, 1, new[] { 10 }, "", 0, 255, "Digital L2/R2 press threshold (0-255 of analog travel)"),
        new("l2_dead", 40, FieldKind.U8, 1, new[] { 0 }, "", 0, 50, "L2 inner deadzone, %"),
        new("r2_dead", 41, FieldKind.U8, 1, new[] { 0 }, "", 0, 50, "R2 inner deadzone, %"),
        new("l2_outer", 42, FieldKind.U8, 1, new[] { 100 }, "", 50, 100, "L2 full-scale point, %"),
        new("r2_outer", 43, FieldKind.U8, 1, new[] { 100 }, "", 50, 100, "R2 full-scale point, %"),
        new("trig_flags", 44, FieldKind.U8, 1, new[] { 0 }, "", 0, 3, "bit0 swap L2/R2, bit1 digital-only triggers (0/255)"),
        new("dpad_mode", 45, FieldKind.U8, 1, new[] { 0 }, "", 0, 3, "What the D-pad buttons drive"),
        new("socd_mode", 46, FieldKind.U8, 1, new[] { 0 }, "", 0, 1, "Opposite D-pad directions pressed together"),
        new("led_mode", 47, FieldKind.U8, 1, new[] { 1 }, "", 0, 3, "Blue LED behaviour"),
        new("led_brightness", 48, FieldKind.U8, 1, new[] { 255 }, "", 0, 255, "Blue LED brightness"),
        new("led_breath", 49, FieldKind.U8, 1, new[] { 20 }, "", 5, 100, "Breathing period in 0.1 s units"),
        new("poll_ms", 50, FieldKind.U8, 1, new[] { 5 }, "", 1, 8, "USB interrupt polling interval in ms"),
        new("calib_mode", 51, FieldKind.U8, 1, new[] { 0 }, "", 0, 1, "0 = stock calibration page, 1 = calibration stored below"),
        new("rumble_level", 52, FieldKind.U8, 1, new[] { 0 }, "", 0, 100, "Rumble strength at full DS4 level, % of the actuators' full scale (0 = board default)"),
        new("led_rgb", 53, FieldKind.U8, 3, new[] { 0, 0, 0 }, "", 0, 255, "RGB LED colour (R, G, B; Kishi V2 Pro); 0, 0, 0 = blue"),
        new("cal_smax", 56, FieldKind.U16, 4, new[] { 3264, 3317, 3395, 3360 }, "", 0, 4095, "Stick max raw (RX, RY, LX, LY)"),
        new("cal_smin", 64, FieldKind.U16, 4, new[] { 566, 558, 718, 594 }, "", 0, 4095, "Stick min raw (RX, RY, LX, LY)"),
        new("cal_scenter", 72, FieldKind.U16, 4, new[] { 1905, 2000, 2036, 1971 }, "", 0, 4095, "Stick centre raw (RX, RY, LX, LY)"),
        new("cal_t_lo", 80, FieldKind.U16, 2, new[] { 579, 542 }, "", 0, 4095, "Trigger pressed raw (L2, R2)"),
        new("cal_t_hi", 84, FieldKind.U16, 2, new[] { 1905, 1806 }, "", 0, 4095, "Trigger rest raw (L2, R2)"),
        new("vid", 88, FieldKind.U16, 1, new[] { 1356 }, "", null, null, "USB vendor ID (locked: DS4)", true),
        new("pid", 90, FieldKind.U16, 1, new[] { 1476 }, "", null, null, "USB product ID (locked: DS4)", true),
        new("bcd_device", 92, FieldKind.U16, 1, new[] { 256 }, "", null, null, "USB device release (BCD) (locked)", true),
        new("reserved2", 94, FieldKind.U8, 2, new[] { 0, 0 }, "", null, null, "reserved"),
        new("manufacturer", 96, FieldKind.Char, 32, new int[0], "ElectroTamp KishiDS", null, null, "USB manufacturer string (locked)", true),
        new("product", 128, FieldKind.Char, 32, new int[0], "Wireless Controller", null, null, "USB product string (user-visible device name)"),
        new("serial", 160, FieldKind.Char, 32, new int[0], "", null, null, "USB serial string (locked: always the controller's factory serial, see kcfg_resolve_serial)", true),
        new("led_fixed", 192, FieldKind.U8, 1, new[] { 0 }, "", 0, 1, "RGB LED: 0 = games/apps may change the colour (DS4 lightbar), 1 = always led_rgb"),
        new("button_map2", 193, FieldKind.U8, 4, new[] { 0, 0, 0, 0 }, "", 0, 20, "DS4 output code for buttons 16..19 (Kishi V2 Pro: M1, M2)"),
        new("reserved3", 197, FieldKind.U8, 59, new[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 }, "", null, null, "reserved"),
    };
}
