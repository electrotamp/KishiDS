package com.electrotamp.kishids.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The firmware's live-editing channel on HID feature report 0xAC (see firmware live.h).  Pure packet building and parsing; the
 * USB transfers live in the usb package.  Mirrors the desktop app's LiveProtocol.cs.
 */
public final class LiveProtocol {
    private LiveProtocol() {}

    public static final int REPORT_ID = 0xAC, SIGNATURE = 0x4B, PROTOCOL = 1;
    /** The descriptor declares 0xAC as ID + 57 bytes. */
    public static final int REPORT_SIZE = 58;
    public static final int CHUNK_SIZE = 32, CHUNKS = ConfigLayout.SIZE / CHUNK_SIZE;

    public static final int CMD_STAGE = 1, CMD_APPLY = 2, CMD_READ_SELECT = 3, CMD_SAVE = 4, CMD_ERASE = 5, CMD_REBOOT = 6;

    public static final int RESULT_OK = 0, RESULT_BAD_BLOCK = 1, RESULT_BAD_COMMAND = 2, RESULT_FLASH_FAILED = 3;

    public static final int FLAG_UNSAVED = 1, FLAG_IDENTITY_DIFFERS = 2, FLAG_FROM_SAVED = 4, FLAG_PENDING = 8;

    public static byte[] packet(int cmd, int index, byte[] data, int dataOffset, int dataLength) {
        byte[] p = new byte[REPORT_SIZE];
        p[0] = (byte) REPORT_ID;
        p[1] = (byte) SIGNATURE;
        p[2] = (byte) cmd;
        p[3] = (byte) index;
        if (data != null) System.arraycopy(data, dataOffset, p, 4, dataLength);
        return p;
    }

    public static byte[] packet(int cmd) { return packet(cmd, 0, null, 0, 0); }

    public static byte[] packet(int cmd, int index) { return packet(cmd, index, null, 0, 0); }

    /** Eight STAGE packets followed by APPLY: how a whole config block goes live. */
    public static List<byte[]> pushPackets(byte[] block) {
        if (block.length != ConfigLayout.SIZE) throw new IllegalArgumentException("config block must be 256 bytes");
        List<byte[]> list = new ArrayList<>();
        for (int i = 0; i < CHUNKS; i++) list.add(packet(CMD_STAGE, i, block, i * CHUNK_SIZE, CHUNK_SIZE));
        list.add(packet(CMD_APPLY));
        return list;
    }

    public static final class Status {
        public final int result, lastCmd, flags, readIndex;
        public final long activeCrc, persistedCrc;
        public final byte[] chunk;

        Status(int result, int lastCmd, long activeCrc, long persistedCrc, int flags, int readIndex, byte[] chunk) {
            this.result = result; this.lastCmd = lastCmd; this.activeCrc = activeCrc; this.persistedCrc = persistedCrc;
            this.flags = flags; this.readIndex = readIndex; this.chunk = chunk;
        }

        public boolean has(int flag) { return (flags & flag) != 0; }
    }

    /** Parse a GET_REPORT 0xAC reply, or null if it is something else. */
    public static Status parseStatus(byte[] buf) {
        if (buf == null || buf.length < 48 || (buf[0] & 0xFF) != REPORT_ID || (buf[1] & 0xFF) != PROTOCOL) return null;
        byte[] chunk = new byte[CHUNK_SIZE];
        System.arraycopy(buf, 16, chunk, 0, CHUNK_SIZE);
        return new Status(buf[2] & 0xFF, buf[3] & 0xFF, u32(buf, 4), u32(buf, 8), buf[12] & 0xFF, buf[13] & 0xFF, chunk);
    }

    static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    public static String describe(int result) {
        switch (result) {
            case RESULT_OK: return "ok";
            case RESULT_BAD_BLOCK: return "the controller rejected the settings block";
            case RESULT_BAD_COMMAND: return "the controller didn't understand the command";
            case RESULT_FLASH_FAILED: return "the controller couldn't write its flash";
            default: return "unknown result " + result;
        }
    }
}
