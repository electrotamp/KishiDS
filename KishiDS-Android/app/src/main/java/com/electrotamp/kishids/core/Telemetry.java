package com.electrotamp.kishids.core;

/**
 * Firmware telemetry (feature report 0xAB): raw buttons/ADC and which config the firmware is running.
 * The live/saved/unsaved/identity fields come from firmware with live editing; older firmware leaves them false.
 */
public final class Telemetry {
    public final boolean configured, configFromImage, live, fromSaved, unsaved, identityDiffers;
    public final int buttonMask;
    /** Raw ADC readings, firmware order: RX, RY, R2, LY, LX, L2 (0..4095). */
    public final int[] adc;
    public final long configCrc, persistedCrc;

    public Telemetry(boolean configured, boolean configFromImage, int buttonMask, int[] adc, long configCrc,
                     boolean live, boolean fromSaved, boolean unsaved, boolean identityDiffers, long persistedCrc) {
        this.configured = configured; this.configFromImage = configFromImage; this.buttonMask = buttonMask; this.adc = adc;
        this.configCrc = configCrc; this.live = live; this.fromSaved = fromSaved; this.unsaved = unsaved;
        this.identityDiffers = identityDiffers; this.persistedCrc = persistedCrc;
    }

    /** Parse a GET_REPORT 0xAB reply, or null if it is not one. */
    public static Telemetry parse(byte[] b) {
        if (b == null || b.length < 26 || (b[0] & 0xFF) != 0xAB || b[1] != 1) return null;
        int[] adc = new int[6];
        for (int i = 0; i < 6; i++) adc[i] = (b[5 + 2 * i] & 0xFF) | ((b[6 + 2 * i] & 0xFF) << 8);
        int flags = b[2] & 0xFF;
        boolean live = (flags & 0x04) != 0 && (b[25] & 0xFF) == LiveProtocol.PROTOCOL;   // flag + protocol byte: older firmware has neither
        return new Telemetry((flags & 1) != 0, (flags & 2) != 0, (b[3] & 0xFF) | ((b[4] & 0xFF) << 8), adc, LiveProtocol.u32(b, 17),
                live, live && (flags & 0x08) != 0, live && (flags & 0x10) != 0, live && (flags & 0x20) != 0, live ? LiveProtocol.u32(b, 21) : 0);
    }

    /** True when the other reading differs in anything but the analogue values (so bindings need re-evaluating). */
    public boolean sameStateAs(Telemetry o) {
        return o != null && buttonMask == o.buttonMask && configCrc == o.configCrc && configured == o.configured && live == o.live
                && unsaved == o.unsaved && identityDiffers == o.identityDiffers && fromSaved == o.fromSaved;
    }
}
