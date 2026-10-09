"""Single source of truth for the Kishi firmware's 256-byte config block.

Generates:
  ds4-firmware/config_layout.h                    (C struct, defaults, clamp, static asserts)
  ../KishiDS/Core/ConfigLayout.g.cs           (C# field table, defaults, enums)
  ../KishiDS-Android/app/src/main/java/com/electrotamp/kishids/core/ConfigLayout.java   (the same table for the Android app)

Run:  python tools/gen_config.py
The block lives in the firmware image (section .kcfg, found by its magic).  The firmware
validates it (magic, version, size, CRC-32 over bytes 16..255) and falls back to built-in
defaults if anything is wrong; the KishiDS app patches it and recomputes the CRC.
"""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
C_OUT = ROOT / "ds4-firmware" / "config_layout.h"
CS_OUT = ROOT.parent / "KishiDS" / "Core" / "ConfigLayout.g.cs"
JAVA_OUT = ROOT.parent / "KishiDS-Android" / "app" / "src" / "main" / "java" / "com" / "electrotamp" / "kishids" / "core" / "ConfigLayout.java"

CONFIG_SIZE = 256
CONFIG_VERSION = 1

# DS4 output codes a Kishi button can be mapped to.
OUTPUTS = [
    "None", "Square", "Cross", "Circle", "Triangle", "L1", "R1", "L2", "R2", "Share", "Options",
    "L3", "R3", "PS", "Touchpad", "DpadUp", "DpadDown", "DpadLeft", "DpadRight", "TouchLeft", "TouchRight",
]
# Physical Kishi buttons in firmware scan order (kishi_io.h enum kishi_button).
KISHI_BUTTONS = ["A", "B", "X", "Y", "Up", "Down", "Left", "Right", "L1", "R1", "L3", "R3",
                 "Right Function", "Home", "Left Function"]
# The Kishi V2 Pro's names for the same slots, then its extras: Share in button_map[15], M1/M2 in button_map2.
V2PRO_BUTTONS = ["A", "B", "X", "Y", "Up", "Down", "Left", "Right", "L1", "R1", "L3", "R3",
                 "Menu", "Nexus", "View", "Share", "M1", "M2"]
# Its default mapping (v2pro-firmware/board_defaults.h): as the V1's, but View -> Touchpad, Share -> Share, and
# M1 / M2 -> TouchLeft / TouchRight.
V2PRO_BUTTON_MAP = [2, 3, 1, 4, 15, 16, 17, 18, 5, 6, 11, 12, 10, 13, 14, 9]
V2PRO_BUTTON_MAP2 = [19, 20, 0, 0]
CURVES = ["Linear", "Precise", "Aggressive"]
DPAD_MODES = ["D-pad", "Left stick", "Right stick", "Disabled"]
SOCD_MODES = ["Neutral", "Up/Right wins"]
LED_MODES = ["Off", "Solid", "Breathing", "While in use"]

# (name, offset, kind, count, default, min, max, doc)
#   kind: u8 | u16 | u32 | char   (count>1 => array; char arrays are strings)
F = []


def field(name, off, kind, count, default, lo=None, hi=None, doc="", locked=False):
    # locked => the firmware's clamp forces the default and the app refuses to edit it (USB identity that must stay DS4-compatible)
    F.append(dict(name=name, off=off, kind=kind, count=count, default=default, lo=lo, hi=hi, doc=doc, locked=locked))


field("magic", 0, "char", 8, "KISHICFG", doc="Marker used to find the block in the image")
field("version", 8, "u16", 1, CONFIG_VERSION, doc="Layout version")
field("size", 10, "u16", 1, CONFIG_SIZE, doc="Block size in bytes")
field("crc32", 12, "u32", 1, 0, doc="CRC-32 (ISO-HDLC) of bytes 16..size-1")
# Default mapping: A,B,X,Y,Up,Down,Left,Right,L1,R1,L3,R3,RFunc,Home,LFunc,(pad)
field("button_map", 16, "u8", 16, [2, 3, 1, 4, 15, 16, 17, 18, 5, 6, 11, 12, 10, 13, 9, 0], 0, len(OUTPUTS) - 1,
      "DS4 output code for each Kishi button (index = scan order)")
field("stick_flags", 32, "u8", 1, 0, 0, 31, "bit0 invert LX, bit1 invert LY, bit2 invert RX, bit3 invert RY, bit4 swap sticks")
field("left_dead", 33, "u8", 1, 8, 0, 50, "Left stick deadzone, % of travel")
field("right_dead", 34, "u8", 1, 8, 0, 50, "Right stick deadzone, % of travel")
field("left_outer", 35, "u8", 1, 90, 50, 100, "Left stick full-scale point, % of travel")
field("right_outer", 36, "u8", 1, 90, 50, 100, "Right stick full-scale point, % of travel")
field("left_curve", 37, "u8", 1, 0, 0, len(CURVES) - 1, "Left stick response curve")
field("right_curve", 38, "u8", 1, 0, 0, len(CURVES) - 1, "Right stick response curve")
field("trig_thresh", 39, "u8", 1, 10, 0, 255, "Digital L2/R2 press threshold (0-255 of analog travel)")
field("l2_dead", 40, "u8", 1, 0, 0, 50, "L2 inner deadzone, %")
field("r2_dead", 41, "u8", 1, 0, 0, 50, "R2 inner deadzone, %")
field("l2_outer", 42, "u8", 1, 100, 50, 100, "L2 full-scale point, %")
field("r2_outer", 43, "u8", 1, 100, 50, 100, "R2 full-scale point, %")
field("trig_flags", 44, "u8", 1, 0, 0, 3, "bit0 swap L2/R2, bit1 digital-only triggers (0/255)")
field("dpad_mode", 45, "u8", 1, 0, 0, len(DPAD_MODES) - 1, "What the D-pad buttons drive")
field("socd_mode", 46, "u8", 1, 0, 0, len(SOCD_MODES) - 1, "Opposite D-pad directions pressed together")
field("led_mode", 47, "u8", 1, 1, 0, len(LED_MODES) - 1, "Blue LED behaviour")
field("led_brightness", 48, "u8", 1, 255, 0, 255, "Blue LED brightness")
field("led_breath", 49, "u8", 1, 20, 5, 100, "Breathing period in 0.1 s units")
field("poll_ms", 50, "u8", 1, 5, 1, 8, "USB interrupt polling interval in ms")
field("calib_mode", 51, "u8", 1, 0, 0, 1, "0 = stock calibration page, 1 = calibration stored below")
field("rumble_level", 52, "u8", 1, 0, 0, 100, "Rumble strength at full DS4 level, % of the actuators' full scale (0 = board default)")
field("led_rgb", 53, "u8", 3, [0, 0, 0], 0, 255, "RGB LED colour (R, G, B; Kishi V2 Pro); 0, 0, 0 = blue")
# Calibration, indexed in ADC scan order: ch1 RX, ch2 RY, ch5 LY, ch6 LX  -> stick index 0..3 = RX, RY, LX, LY
field("cal_smax", 56, "u16", 4, [3264, 3317, 3395, 3360], 0, 4095, "Stick max raw (RX, RY, LX, LY)")
field("cal_smin", 64, "u16", 4, [566, 558, 718, 594], 0, 4095, "Stick min raw (RX, RY, LX, LY)")
field("cal_scenter", 72, "u16", 4, [1905, 2000, 2036, 1971], 0, 4095, "Stick centre raw (RX, RY, LX, LY)")
field("cal_t_lo", 80, "u16", 2, [579, 542], 0, 4095, "Trigger pressed raw (L2, R2)")
field("cal_t_hi", 84, "u16", 2, [1905, 1806], 0, 4095, "Trigger rest raw (L2, R2)")
field("vid", 88, "u16", 1, 0x054C, doc="USB vendor ID (locked: DS4)", locked=True)
field("pid", 90, "u16", 1, 0x05C4, doc="USB product ID (locked: DS4)", locked=True)
field("bcd_device", 92, "u16", 1, 0x0100, doc="USB device release (BCD) (locked)", locked=True)
field("reserved2", 94, "u8", 2, [0, 0], doc="reserved")
field("manufacturer", 96, "char", 32, "ElectroTamp KishiDS", doc="USB manufacturer string (locked)", locked=True)
field("product", 128, "char", 32, "Wireless Controller", doc="USB product string (user-visible device name)")
field("serial", 160, "char", 32, "", doc="USB serial string (locked: always the controller's factory serial, see kcfg_resolve_serial)", locked=True)
field("led_fixed", 192, "u8", 1, 0, 0, 1, "RGB LED: 0 = games/apps may change the colour (DS4 lightbar), 1 = always led_rgb")
field("button_map2", 193, "u8", 4, [0, 0, 0, 0], 0, len(OUTPUTS) - 1, "DS4 output code for buttons 16..19 (Kishi V2 Pro: M1, M2)")
field("reserved3", 197, "u8", 59, [0] * 59, doc="reserved")

SIZES = {"u8": 1, "u16": 2, "u32": 4, "char": 1}
CT = {"u8": "uint8_t", "u16": "uint16_t", "u32": "uint32_t", "char": "char"}


def check() -> None:
    pos = 0
    for f in F:
        assert f["off"] == pos, f"{f['name']}: offset {f['off']} != running {pos}"
        pos += SIZES[f["kind"]] * f["count"]
    assert pos == CONFIG_SIZE, f"layout ends at {pos}, expected {CONFIG_SIZE}"
    for f in F:
        if f["kind"] == "char":
            limit = f["count"] if f["name"] == "magic" else f["count"] - 1   # strings keep a NUL; the magic is a marker
            assert len(f["default"]) <= limit, f"{f['name']} default too long"


def c_values(f) -> str:
    d = f["default"]
    if f["kind"] == "char":
        return '"%s"' % d
    if f["count"] == 1:
        return hex(d) if f["kind"] != "u8" or d > 9 else str(d)
    return "{" + ", ".join(str(x) for x in d) + "}"


def gen_c() -> str:
    o = []
    o.append("/* Output of tools/gen_config.py -- do not edit by hand. */")
    o.append("#ifndef CONFIG_LAYOUT_H\n#define CONFIG_LAYOUT_H\n")
    o.append("#include <stddef.h>\n#include <stdint.h>\n#include <string.h>\n")
    o.append(f"#define KCFG_SIZE    {CONFIG_SIZE}u")
    o.append(f"#define KCFG_VERSION {CONFIG_VERSION}u")
    o.append("#define KCFG_MAGIC   {'K', 'I', 'S', 'H', 'I', 'C', 'F', 'G'}\n")
    o.append("/* DS4 output codes (button_map values). */\nenum kcfg_output {")
    for i, n in enumerate(OUTPUTS):
        o.append(f"\tKO_{n.upper()} = {i},")
    o.append(f"\tKO_COUNT = {len(OUTPUTS)}\n}};\n")
    o.append("/* Kishi V2 Pro default button mapping (board_defaults.h). */")
    o.append("#define KCFG_V2PRO_BUTTON_MAP  {" + ", ".join(map(str, V2PRO_BUTTON_MAP)) + "}")
    o.append("#define KCFG_V2PRO_BUTTON_MAP2 {" + ", ".join(map(str, V2PRO_BUTTON_MAP2)) + "}\n")
    o.append("struct __attribute__((packed)) kishi_config {")
    for f in F:
        arr = f"[{f['count']}]" if f["count"] > 1 else ""
        o.append(f"\t{CT[f['kind']]} {f['name']}{arr};  /* {f['off']}: {f['doc']} */")
    o.append("};\n")
    o.append(f"_Static_assert(sizeof(struct kishi_config) == {CONFIG_SIZE}, \"config size\");")
    for f in F:
        o.append(f"_Static_assert(offsetof(struct kishi_config, {f['name']}) == {f['off']}, \"{f['name']} offset\");")
    o.append("")
    o.append("/* Defaults without the magic: the fallback copy must not contain the marker bytes. */")
    o.append("#define KCFG_DEFAULTS_BODY \\")
    for f in F:
        if f["name"] in ("magic", "crc32") or f["name"].startswith("reserved"):
            continue
        o.append(f"\t.{f['name']} = {c_values(f)}, \\")
    o.append("\t.crc32 = 0\n")
    o.append("#define KCFG_DEFAULTS { .magic = KCFG_MAGIC, KCFG_DEFAULTS_BODY }\n")
    o.append("/* Clamp every ranged field into its legal range (defence against a hand-edited block). */")
    o.append("static inline void kcfg_clamp(struct kishi_config *c)\n{\n\tunsigned i;\n\t(void)i;")
    tmax = {"u8": 255, "u16": 65535, "u32": 2**32 - 1, "char": 255}
    for f in F:
        if f["locked"]:
            if f["kind"] == "char":
                o.append(f'	memset(c->{f["name"]}, 0, sizeof(c->{f["name"]}));')
                o.append(f'	memcpy(c->{f["name"]}, "{f["default"]}", {len(f["default"])});')
            else:
                o.append(f"	c->{f['name']} = {c_values(f)};")
            continue
        if f["lo"] is None:
            continue
        # Skip bounds the type already enforces (avoids always-false comparison warnings).
        checks = []
        if f["lo"] > 0:
            checks.append(("<", f["lo"]))
        if f["hi"] < tmax[f["kind"]]:
            checks.append((">", f["hi"]))
        if not checks:
            continue
        if f["count"] == 1:
            for op, v in checks:
                o.append(f"\tif (c->{f['name']} {op} {v}) c->{f['name']} = {v};")
        else:
            o.append(f"\tfor (i = 0; i < {f['count']}; i++) {{")
            for op, v in checks:
                o.append(f"\t\tif (c->{f['name']}[i] {op} {v}) c->{f['name']}[i] = {v};")
            o.append("\t}")
    o.append("}\n\n#endif")
    return "\n".join(o) + "\n"


def cs_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace('"', '\\"')


def gen_cs() -> str:
    o = []
    o.append("// Output of tools/gen_config.py -- do not edit by hand.")
    o.append("namespace KishiDS.Core;\n")
    o.append("public enum FieldKind { U8, U16, U32, Char }\n")
    o.append("public sealed record FieldDef(string Name, int Offset, FieldKind Kind, int Count, int[] Default, string DefaultText, int? Min, int? Max, string Doc, bool Locked = false)")
    o.append("{\n    public int ByteSize => Count * (Kind switch { FieldKind.U16 => 2, FieldKind.U32 => 4, _ => 1 });\n}\n")
    o.append("public static class ConfigLayout\n{")
    o.append(f"    public const int Size = {CONFIG_SIZE};")
    o.append(f"    public const int Version = {CONFIG_VERSION};")
    o.append('    public const string Magic = "KISHICFG";')
    o.append("    public const int CrcStart = 16;\n")
    def arr(name, items):
        return f"    public static readonly string[] {name} = {{ " + ", ".join('"%s"' % cs_escape(x) for x in items) + " };"
    o.append(arr("Outputs", OUTPUTS))
    o.append(arr("KishiButtons", KISHI_BUTTONS))
    o.append(arr("V2ProButtons", V2PRO_BUTTONS))
    o.append("    public static readonly int[] V2ProButtonMap = { " + ", ".join(map(str, V2PRO_BUTTON_MAP + V2PRO_BUTTON_MAP2)) + " };")
    o.append(arr("Curves", CURVES))
    o.append(arr("DpadModes", DPAD_MODES))
    o.append(arr("SocdModes", SOCD_MODES))
    o.append(arr("LedModes", LED_MODES))
    o.append("\n    public static readonly FieldDef[] Fields =\n    {")
    for f in F:
        d = f["default"]
        if f["kind"] == "char":
            dv, dt = "new int[0]", cs_escape(d)
        elif f["count"] == 1:
            dv, dt = "new[] { %d }" % d, ""
        else:
            dv, dt = "new[] { %s }" % ", ".join(str(x) for x in d), ""
        lo = "null" if f["lo"] is None else str(f["lo"])
        hi = "null" if f["hi"] is None else str(f["hi"])
        kind = f["kind"].capitalize()
        o.append(f'        new("{f["name"]}", {f["off"]}, FieldKind.{kind}, {f["count"]}, {dv}, "{dt}", {lo}, {hi}, "{cs_escape(f["doc"])}"{", true" if f["locked"] else ""}),')
    o.append("    };\n}")
    return "\n".join(o) + "\n"


def gen_java() -> str:
    o = []
    o.append("// Output of tools/gen_config.py -- do not edit by hand.")
    o.append("package com.electrotamp.kishids.core;\n")
    o.append("public final class ConfigLayout {")
    o.append("    private ConfigLayout() {}\n")
    o.append(f"    public static final int SIZE = {CONFIG_SIZE};")
    o.append(f"    public static final int VERSION = {CONFIG_VERSION};")
    o.append('    public static final String MAGIC = "KISHICFG";')
    o.append("    public static final int CRC_START = 16;\n")
    o.append("    public enum Kind { U8, U16, U32, CHAR }\n")
    o.append("    public static final class FieldDef {")
    o.append("        public final String name;")
    o.append("        public final int offset, count;")
    o.append("        public final Kind kind;")
    o.append("        public final int[] defaults;")
    o.append("        public final String defaultText, doc;")
    o.append("        public final Integer min, max;")
    o.append("        public final boolean locked;\n")
    o.append("        FieldDef(String name, int offset, Kind kind, int count, int[] defaults, String defaultText, Integer min, Integer max, String doc, boolean locked) {")
    o.append("            this.name = name; this.offset = offset; this.kind = kind; this.count = count; this.defaults = defaults;")
    o.append("            this.defaultText = defaultText; this.min = min; this.max = max; this.doc = doc; this.locked = locked;")
    o.append("        }\n")
    o.append("        public int elementSize() { return kind == Kind.U16 ? 2 : kind == Kind.U32 ? 4 : 1; }")
    o.append("        public int byteSize() { return count * elementSize(); }")
    o.append("    }\n")

    def arr(name, items):
        return f"    public static final String[] {name} = {{ " + ", ".join('"%s"' % cs_escape(x) for x in items) + " };"

    o.append(arr("OUTPUTS", OUTPUTS))
    o.append(arr("KISHI_BUTTONS", KISHI_BUTTONS))
    o.append(arr("CURVES", CURVES))
    o.append(arr("DPAD_MODES", DPAD_MODES))
    o.append(arr("SOCD_MODES", SOCD_MODES))
    o.append(arr("LED_MODES", LED_MODES))
    o.append("\n    public static final FieldDef[] FIELDS = {")
    for f in F:
        d = f["default"]
        if f["kind"] == "char":
            dv, dt = "new int[0]", cs_escape(d)
        elif f["count"] == 1:
            dv, dt = "new int[] { %d }" % d, ""
        else:
            dv, dt = "new int[] { %s }" % ", ".join(str(x) for x in d), ""
        lo = "null" if f["lo"] is None else str(f["lo"])
        hi = "null" if f["hi"] is None else str(f["hi"])
        o.append(f'        new FieldDef("{f["name"]}", {f["off"]}, Kind.{f["kind"].upper()}, {f["count"]}, {dv}, "{dt}", {lo}, {hi}, '
                 f'"{cs_escape(f["doc"])}", {"true" if f["locked"] else "false"}),')
    o.append("    };\n}")
    return "\n".join(o) + "\n"


def main() -> None:
    check()
    C_OUT.write_text(gen_c(), encoding="utf-8", newline="\n")
    CS_OUT.parent.mkdir(parents=True, exist_ok=True)
    CS_OUT.write_text(gen_cs(), encoding="utf-8", newline="\n")
    JAVA_OUT.parent.mkdir(parents=True, exist_ok=True)
    JAVA_OUT.write_text(gen_java(), encoding="utf-8", newline="\n")
    print("wrote", C_OUT)
    print("wrote", CS_OUT)
    print("wrote", JAVA_OUT)


if __name__ == "__main__":
    main()
