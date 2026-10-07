package com.electrotamp.kishids.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Loads, patches and validates firmware images for the Kishi's application region.  Mirrors the desktop app's FirmwareImage.cs. */
public final class FirmwareImage {
    private FirmwareImage() {}

    public static final long APP_BASE = 0x08003000L;
    public static final long APP_LIMIT = 0x0800E000L;     // diagnostic-log page; the stock settings page is 0x0800F800
    public static final long STACK_TOP = 0x20004000L;
    public static final String STOCK_SHA256 = "AA915363C858E5A06FB1AD15A82D059D50FD22A625D0663A3CE99FCAB24F69BE";
    /** Size in bytes of Razer's v2.70 application image. */
    public static final int STOCK_LENGTH = 28108;

    private static final byte[] MARKER = buildMarker();

    private static byte[] buildMarker() {
        byte[] m = new byte[12];
        byte[] magic = ConfigLayout.MAGIC.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, m, 0, magic.length);
        m[8] = (byte) ConfigLayout.VERSION;
        m[9] = (byte) (ConfigLayout.VERSION >> 8);
        m[10] = (byte) ConfigLayout.SIZE;
        m[11] = (byte) (ConfigLayout.SIZE >> 8);
        return m;
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Offset of the config block, or -1 if the marker is missing or ambiguous. */
    public static int findBlock(byte[] image) {
        int first = indexOf(image, MARKER, 0);
        if (first < 0) return -1;
        if (indexOf(image, MARKER, first + 1) >= 0) return -1;
        return first + ConfigLayout.SIZE <= image.length ? first : -1;
    }

    public static ConfigBlock readBlock(byte[] image) {
        int at = findBlock(image);
        if (at < 0) throw new IllegalArgumentException("This image has no KishiDS config block.");
        byte[] b = new byte[ConfigLayout.SIZE];
        System.arraycopy(image, at, b, 0, b.length);
        return new ConfigBlock(b);
    }

    /** A copy of the image with its config block replaced (CRC recomputed). */
    public static byte[] patch(byte[] image, ConfigBlock block) {
        int at = findBlock(image);
        if (at < 0) throw new IllegalArgumentException("This image has no KishiDS config block.");
        byte[] copy = image.clone();
        ConfigBlock b = block.copy();
        b.recomputeCrc();
        System.arraycopy(b.toBytes(), 0, copy, at, ConfigLayout.SIZE);
        return copy;
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    /**
     * Everything that must hold before an image is sent to the bootloader.  Returns an empty list when the image is safe:
     * vector table inside the application region, sane size, and (for custom images) a valid block.
     */
    public static List<String> validate(byte[] image, boolean requireConfigBlock) {
        List<String> problems = new ArrayList<>();
        if (image.length < 0xC0 || image.length > APP_LIMIT - APP_BASE) {
            problems.add("Image size " + image.length + " bytes is outside the allowed range.");
            return problems;
        }
        long end = APP_BASE + image.length;
        long sp = u32(image, 0);
        if (sp != STACK_TOP) problems.add(String.format("Initial stack pointer %08X is not %08X.", sp, STACK_TOP));
        for (int i = 1; i < 48; i++) {
            long v = u32(image, i * 4);
            if (v == 0) continue;
            if ((v & 1) == 0 || (v & ~1L) < APP_BASE || (v & ~1L) >= end) {
                problems.add(String.format("Vector %d (%08X) points outside the image at %08X-%08X; the bootloader would reject it.", i, v, APP_BASE, end));
                break;
            }
        }
        if (requireConfigBlock) {
            int at = findBlock(image);
            if (at < 0) problems.add("No unique config block found.");
            else {
                byte[] b = new byte[ConfigLayout.SIZE];
                System.arraycopy(image, at, b, 0, b.length);
                if (!new ConfigBlock(b).isValid()) problems.add("The config block fails its own checksum.");
            }
        }
        return problems;
    }

    public static String sha256(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte x : h) sb.append(String.format("%02X", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static boolean isOfficialStock(byte[] data) {
        return data.length == STOCK_LENGTH && sha256(data).equalsIgnoreCase(STOCK_SHA256);
    }
}
