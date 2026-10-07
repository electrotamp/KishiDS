package com.electrotamp.kishids.core;

/** A decoded DualShock-4 style input report from the custom firmware (stick bytes 0..255, 128 = centre; triggers 0..255). */
public final class LiveInput {
    public static final LiveInput NEUTRAL = new LiveInput(128, 128, 128, 128, 0, 0);

    public final int lx, ly, rx, ry, l2, r2;

    public LiveInput(int lx, int ly, int rx, int ry, int l2, int r2) {
        this.lx = lx; this.ly = ly; this.rx = rx; this.ry = ry; this.l2 = l2; this.r2 = r2;
    }

    /** Decode the 64-byte report with ID 0x01, or null if this is some other report. */
    public static LiveInput parse(byte[] b, int n) {
        if (n < 10 || b[0] != 0x01) return null;
        return new LiveInput(b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF, b[4] & 0xFF, b[8] & 0xFF, b[9] & 0xFF);
    }
}
