package com.electrotamp.kishids.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The firmware's 256-byte settings block, addressed by the field names in the generated {@link ConfigLayout}.
 * All writes are clamped to the schema's legal range, so the block can never hold a value the firmware would reject.
 * Mirrors the desktop app's ConfigBlock.cs; profile files written by either app load in the other.
 */
public final class ConfigBlock {
    public interface Listener { void onChanged(String field); }

    private static final Map<String, ConfigLayout.FieldDef> BY_NAME = new HashMap<>();

    static {
        for (ConfigLayout.FieldDef f : ConfigLayout.FIELDS) BY_NAME.put(f.name, f);
    }

    private final byte[] data = new byte[ConfigLayout.SIZE];
    private final List<Listener> listeners = new ArrayList<>();

    public ConfigBlock() { resetAll(false); }

    public ConfigBlock(byte[] bytes) {
        if (bytes.length != ConfigLayout.SIZE) throw new IllegalArgumentException("config block must be 256 bytes");
        System.arraycopy(bytes, 0, data, 0, data.length);
        enforceLocked();
    }

    public static ConfigLayout.FieldDef field(String name) {
        ConfigLayout.FieldDef f = BY_NAME.get(name);
        if (f == null) throw new IllegalArgumentException(name);
        return f;
    }

    public void addListener(Listener l) { listeners.add(l); }

    private void fire(String field) {
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).onChanged(field);
    }

    public byte[] toBytes() { return data.clone(); }

    public ConfigBlock copy() { return new ConfigBlock(data); }

    /** Replace every byte (e.g. with the block read back from a controller) and notify once. */
    public void loadBytes(byte[] bytes) {
        if (bytes.length != ConfigLayout.SIZE) throw new IllegalArgumentException("config block must be 256 bytes");
        System.arraycopy(bytes, 0, data, 0, data.length);
        enforceLocked();
        fire("*");
    }

    /** Force locked fields (USB VID/PID/bcdDevice, manufacturer, serial) back to their built-in values, exactly as the firmware's clamp does. */
    private void enforceLocked() {
        for (ConfigLayout.FieldDef f : ConfigLayout.FIELDS) if (f.locked) writeDefault(f);
        recomputeCrc();
    }

    /** Load all defaults (and a valid CRC). */
    public void resetAll() { resetAll(true); }

    private void resetAll(boolean notify) {
        for (ConfigLayout.FieldDef f : ConfigLayout.FIELDS) writeDefault(f);
        recomputeCrc();
        if (notify) fire("*");
    }

    public void resetField(String name) {
        writeDefault(field(name));
        recomputeCrc();
        fire(name);
    }

    private void writeDefault(ConfigLayout.FieldDef f) {
        if (f.name.equals("magic")) {
            // The marker fills all 8 bytes (no NUL), unlike the string fields.
            byte[] m = f.defaultText.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(m, 0, data, f.offset, Math.min(m.length, f.count));
            return;
        }
        if (f.kind == ConfigLayout.Kind.CHAR) { writeString(f, f.defaultText); return; }
        for (int i = 0; i < f.count; i++) writeRaw(f, i, f.defaults[i]);
    }

    private void writeRaw(ConfigLayout.FieldDef f, int index, long value) {
        int off = f.offset + index * f.elementSize();
        for (int b = 0; b < f.elementSize(); b++) data[off + b] = (byte) (value >> (8 * b));
    }

    private long readRaw(ConfigLayout.FieldDef f, int index) {
        int off = f.offset + index * f.elementSize();
        long v = 0;
        for (int b = 0; b < f.elementSize(); b++) v |= (long) (data[off + b] & 0xFF) << (8 * b);
        return v;
    }

    public int get(String name) { return get(name, 0); }

    public int get(String name, int index) { return (int) readRaw(field(name), index); }

    public void set(String name, int value) { set(name, value, 0); }

    public void set(String name, int value, int index) {
        ConfigLayout.FieldDef f = field(name);
        if (f.locked) return;
        if (f.min != null && value < f.min) value = f.min;
        if (f.max != null && value > f.max) value = f.max;
        if (readRaw(f, index) == value) return;
        writeRaw(f, index, value);
        recomputeCrc();
        fire(name);
    }

    public boolean getBit(String name, int bit) { return (get(name) & (1 << bit)) != 0; }

    public void setBit(String name, int bit, boolean on) {
        int v = get(name);
        set(name, on ? v | (1 << bit) : v & ~(1 << bit));
    }

    public String getString(String name) {
        ConfigLayout.FieldDef f = field(name);
        int n = 0;
        while (n < f.count && data[f.offset + n] != 0) n++;
        return new String(data, f.offset, n, StandardCharsets.US_ASCII);
    }

    /** Printable ASCII only, truncated to fit with the terminating NUL. */
    public void setString(String name, String value) {
        ConfigLayout.FieldDef f = field(name);
        if (f.locked) return;
        writeString(f, value);
        recomputeCrc();
        fire(name);
    }

    private void writeString(ConfigLayout.FieldDef f, String value) {
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c < 0x7F) clean.append(c);
        }
        if (clean.length() > f.count - 1) clean.setLength(f.count - 1);
        Arrays.fill(data, f.offset, f.offset + f.count, (byte) 0);
        byte[] b = clean.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, data, f.offset, b.length);
    }

    // ---- checksum ----

    /** The CRC stored in the block (bytes 12..15, little endian), as an unsigned value. */
    public long crc() {
        return (data[12] & 0xFFL) | ((data[13] & 0xFFL) << 8) | ((data[14] & 0xFFL) << 16) | ((data[15] & 0xFFL) << 24);
    }

    public static long crcOf(byte[] block) {
        CRC32 c = new CRC32();
        c.update(block, ConfigLayout.CRC_START, block.length - ConfigLayout.CRC_START);
        return c.getValue();
    }

    public long computeCrc() { return crcOf(data); }

    public void recomputeCrc() {
        long c = computeCrc();
        for (int i = 0; i < 4; i++) data[12 + i] = (byte) (c >> (8 * i));
    }

    public boolean isValid() {
        String magic = new String(data, 0, 8, StandardCharsets.US_ASCII);
        return magic.equals(ConfigLayout.MAGIC) && get("version") == ConfigLayout.VERSION && get("size") == ConfigLayout.SIZE && crc() == computeCrc();
    }

    // ---- profiles ----

    private static boolean isHeader(String name) {
        return name.equals("magic") || name.equals("version") || name.equals("size") || name.equals("crc32") || name.startsWith("reserved");
    }

    /** The settings a user can change (everything except the header), as name -> value (Integer, String or int[]), for profile files. */
    public Map<String, Object> toProfile() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        for (ConfigLayout.FieldDef f : ConfigLayout.FIELDS) {
            if (isHeader(f.name)) continue;
            if (f.kind == ConfigLayout.Kind.CHAR) d.put(f.name, getString(f.name));
            else if (f.count == 1) d.put(f.name, get(f.name));
            else {
                int[] a = new int[f.count];
                for (int i = 0; i < a.length; i++) a[i] = get(f.name, i);
                d.put(f.name, a);
            }
        }
        return d;
    }

    /** Apply a parsed profile (see {@link Json}); unknown or locked fields are ignored. */
    public void loadProfile(Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            ConfigLayout.FieldDef f = BY_NAME.get(e.getKey());
            if (f == null || isHeader(f.name)) continue;
            Object v = e.getValue();
            if (f.kind == ConfigLayout.Kind.CHAR) {
                if (v instanceof String) setStringQuiet(f, (String) v);
            } else if (f.count == 1) {
                if (v instanceof Number) setQuiet(f, 0, ((Number) v).intValue());
            } else if (v instanceof List) {
                List<?> list = (List<?>) v;
                for (int i = 0; i < list.size() && i < f.count; i++) if (list.get(i) instanceof Number) setQuiet(f, i, ((Number) list.get(i)).intValue());
            }
        }
        recomputeCrc();
        fire("*");
    }

    private void setQuiet(ConfigLayout.FieldDef f, int index, int value) {
        if (f.locked) return;
        if (f.min != null && value < f.min) value = f.min;
        if (f.max != null && value > f.max) value = f.max;
        writeRaw(f, index, value);
    }

    private void setStringQuiet(ConfigLayout.FieldDef f, String value) {
        if (!f.locked) writeString(f, value);
    }
}
