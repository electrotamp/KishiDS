package com.electrotamp.kishids.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Locating the official v2.70 image used to restore the Kishi to its original firmware.  The app does not bundle Razer's image:
 * it is imported from a backup file or from the user's own copy of the Razer app (APK), then kept after a SHA-256 check.
 */
public final class StockFirmware {
    public static final String FILE_NAME = "kishi-0290-stock-v2.70.bin";
    /** Razer's own Android apps that carry the image (Razer Kishi and the older Razer Gamepad app). */
    public static final String[] RAZER_PACKAGES = { "com.razer.mobilegamepad.en", "com.razer.gamepad.en" };

    private final File dir;

    public StockFirmware(File dir) { this.dir = dir; }

    private File file() { return new File(dir, FILE_NAME); }

    /** The imported stock image, if present and genuine. */
    public byte[] loadStored() {
        try {
            File f = file();
            if (!f.isFile() || f.length() != FirmwareImage.STOCK_LENGTH) return null;
            byte[] data = readAll(new FileInputStream(f), FirmwareImage.STOCK_LENGTH + 1);
            return FirmwareImage.isOfficialStock(data) ? data : null;
        } catch (IOException e) { return null; }
    }

    public boolean has() { return loadStored() != null; }

    /** Keep a stock image (a .bin or an .apk/.zip stream).  Returns an error message, or null on success. */
    public String importStream(InputStream in, String nameHint) {
        Result r = extract(in, nameHint);
        if (r.data == null) return r.error;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return "Could not create the folder for the firmware.";
            try (FileOutputStream o = new FileOutputStream(file())) { o.write(r.data); }
            return null;
        } catch (IOException e) { return "Could not save the firmware: " + e.getMessage(); }
    }

    public static final class Result {
        public final byte[] data;
        public final String error;
        Result(byte[] data, String error) { this.data = data; this.error = error; }
    }

    /** The official v2.70 image inside a .bin / .apk stream, or an error in plain language. */
    public static Result extract(InputStream in, String nameHint) {
        try {
            String n = nameHint == null ? "" : nameHint.toLowerCase();
            byte[] head = new byte[4];
            PushbackInputStream p = new PushbackInputStream(in, 4);
            int got = 0;
            while (got < 4) {
                int r = p.read(head, got, 4 - got);
                if (r < 0) break;
                got += r;
            }
            p.unread(head, 0, got);
            boolean zip = got == 4 && head[0] == 'P' && head[1] == 'K';
            if (zip || n.endsWith(".apk") || n.endsWith(".zip")) {
                if (!zip) return new Result(null, "That file isn't a valid .apk (it may be an incomplete download).");
                try (ZipInputStream z = new ZipInputStream(p)) {
                    ZipEntry e;
                    while ((e = z.getNextEntry()) != null) {
                        if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".bin")) continue;
                        if (e.getSize() >= 0 && e.getSize() != FirmwareImage.STOCK_LENGTH) continue;
                        byte[] d = readAll(z, FirmwareImage.STOCK_LENGTH + 1);
                        if (FirmwareImage.isOfficialStock(d)) return new Result(d, null);
                    }
                }
                return new Result(null, "That file doesn't contain the Kishi (RZ06-0290) v2.70 firmware. Try version 1.0.34 or 1.0.66 of the Razer Kishi app.");
            }
            byte[] d = readAll(p, FirmwareImage.STOCK_LENGTH + 1);
            if (FirmwareImage.isOfficialStock(d)) return new Result(d, null);
            return new Result(null, "That isn't the official Kishi v2.70 image (the checksum doesn't match), so it was not imported.");
        } catch (java.util.zip.ZipException e) {
            return new Result(null, "That file isn't a valid .apk (it may be an incomplete download).");
        } catch (IOException e) {
            return new Result(null, "Could not read that file: " + e.getMessage());
        }
    }

    /** Reads at most {@code limit} bytes (a longer stream is cut, which then simply fails the length check). */
    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int total = 0, n;
        while (total < limit && (n = in.read(buf, 0, Math.min(buf.length, limit - total))) > 0) {
            bo.write(buf, 0, n);
            total += n;
        }
        return bo.toByteArray();
    }
}
