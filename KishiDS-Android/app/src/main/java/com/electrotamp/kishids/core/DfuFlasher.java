package com.electrotamp.kishids.core;

/**
 * Standard USB DFU download to the Kishi's vendor bootloader (27F8:0BC0, "GV Bootloader", alternate setting 0), the same transfer
 * Razer's own Android updater performs: GET_STATUS and CLEAR_STATUS to wake the bootloader, 1,024-byte DNLOAD blocks, then a
 * zero-length DNLOAD to start the manifest.  Between blocks this polls GET_STATUS until the bootloader is idle again, as dfu-util does.
 *
 * Talks to the device through {@link Pipe}, so it runs the same on a phone (UsbDeviceConnection) and in the JVM tests (a fake bootloader).
 */
public final class DfuFlasher {
    private DfuFlasher() {}

    /** One USB control transfer on the DFU interface.  Returns the byte count, or a negative number on failure. */
    public interface Pipe {
        int control(int requestType, int request, int value, int index, byte[] buffer, int length, int timeoutMs);
    }

    public interface Listener {
        void progress(double fraction);
        void log(String line);
    }

    public static final class Result {
        public final boolean success;
        public final String message;

        public Result(boolean success, String message) { this.success = success; this.message = message; }
    }

    public static final int BLOCK = 1024;

    // Request types and requests (DFU 1.1).
    private static final int OUT = 0x21, IN = 0xA1;
    private static final int DNLOAD = 1, GETSTATUS = 3, CLRSTATUS = 4, ABORT = 6;

    // States.
    private static final int APP_IDLE = 0, DFU_IDLE = 2, DNLOAD_SYNC = 3, DNBUSY = 4, DNLOAD_IDLE = 5, MANIFEST_SYNC = 6, MANIFEST = 7, WAIT_RESET = 8, ERROR = 10;

    static final String[] STATE_NAMES = { "appIDLE", "appDETACH", "dfuIDLE", "dfuDNLOAD-SYNC", "dfuDNBUSY", "dfuDNLOAD-IDLE", "dfuMANIFEST-SYNC", "dfuMANIFEST", "dfuMANIFEST-WAIT-RESET", "dfuUPLOAD-IDLE", "dfuERROR" };
    static final String[] STATUS_NAMES = { "No error condition is present", "File is not targeted for use by this device",
            "File is for this device but fails some vendor-specific verification test", "Device is unable to write memory", "Memory erase function failed",
            "Memory erase check failed", "Program memory function failed", "Programmed memory failed verification",
            "Cannot program memory due to received address that is out of range", "Received DFU_DNLOAD with wLength = 0, but device does not think it has all of the data yet",
            "Device's firmware is corrupt. It cannot return to run-time (non-DFU) operations", "iString indicates a vendor-specific error",
            "Device detected unexpected USB reset signalling", "Device detected unexpected power on reset", "Something went wrong, but the device does not know what",
            "Device stalled an unexpected request" };

    private static final class Status {
        final int status, pollMs, state;

        Status(int status, int pollMs, int state) { this.status = status; this.pollMs = pollMs; this.state = state; }

        String text() {
            String sn = state >= 0 && state < STATE_NAMES.length ? STATE_NAMES[state] : "state " + state;
            String tn = status >= 0 && status < STATUS_NAMES.length ? STATUS_NAMES[status] : "status " + status;
            return "DFU state(" + state + ") = " + sn + ", status(" + status + ") = " + tn;
        }
    }

    private static Status getStatus(Pipe p, int timeout) {
        byte[] b = new byte[6];
        int n = p.control(IN, GETSTATUS, 0, 0, b, 6, timeout);
        if (n < 6) return null;
        return new Status(b[0] & 0xFF, (b[1] & 0xFF) | ((b[2] & 0xFF) << 8) | ((b[3] & 0xFF) << 16), b[4] & 0xFF);
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Flash {@code image}.  Blocks until the bootloader has taken it (and usually reset), or something went wrong. */
    public static Result flash(Pipe p, byte[] image, Listener l) {
        java.util.List<String> problems = FirmwareImage.validate(image, false);
        if (!problems.isEmpty()) return new Result(false, "Refusing to flash: " + problems.get(0));

        // 1. Wake the bootloader.  A freshly entered bootloader reports a sticky "firmware is corrupt" error that CLEAR_STATUS removes.
        Status st = getStatus(p, 3000);
        if (st == null) return new Result(false, "The bootloader didn't answer. Unplug the controller and enter update mode again.");
        l.log(st.text());
        if (st.state == ERROR) {
            l.log("Clearing status");
            p.control(OUT, CLRSTATUS, 0, 0, new byte[0], 0, 3000);
        } else if (st.state != DFU_IDLE) {
            p.control(OUT, ABORT, 0, 0, new byte[0], 0, 3000);
        }
        st = getStatus(p, 3000);
        if (st == null) return new Result(false, "The bootloader stopped answering.");
        l.log(st.text());
        if (st.state != DFU_IDLE) {
            // Some states need a second nudge.
            p.control(OUT, CLRSTATUS, 0, 0, new byte[0], 0, 3000);
            st = getStatus(p, 3000);
            if (st == null || st.state != DFU_IDLE) return new Result(false, "The bootloader isn't ready (" + (st == null ? "no answer" : st.text()) + ").");
            l.log(st.text());
        }

        // 2. Send the image block by block.
        int blocks = (image.length + BLOCK - 1) / BLOCK;
        l.log("Downloading " + image.length + " bytes in " + blocks + " blocks");
        for (int i = 0; i < blocks; i++) {
            int off = i * BLOCK, len = Math.min(BLOCK, image.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(image, off, chunk, 0, len);
            int n = p.control(OUT, DNLOAD, i, 0, chunk, len, i == 0 ? 10000 : 5000);   // the first block may wait on a flash erase
            if (n < len) return new Result(false, "The transfer stopped at block " + (i + 1) + " of " + blocks + " (USB error " + n + ").");
            Status s = pollUntilIdle(p);
            if (s == null) return new Result(false, "The bootloader stopped answering after block " + (i + 1) + " of " + blocks + ".");
            if (s.state == ERROR || s.status != 0) {
                l.log(s.text());
                return new Result(false, "The bootloader refused block " + (i + 1) + ": " + (s.status < STATUS_NAMES.length ? STATUS_NAMES[s.status] : "error " + s.status) + ".");
            }
            l.progress(Math.min(0.97, (i + 1) / (double) blocks * 0.97));
        }
        l.log("Download done.");

        // 3. A zero-length DNLOAD starts the manifest: the bootloader checks what it was given and resets into it.
        int z = p.control(OUT, DNLOAD, blocks, 0, new byte[0], 0, 5000);
        if (z < 0) l.log("(the bootloader restarted before acknowledging the end of the transfer)");
        boolean accepted = z >= 0;
        for (int tries = 0; tries < 40; tries++) {
            Status s = getStatus(p, 1500);
            if (s == null) {
                l.log("unable to read DFU status after completion (the controller restarted)");
                return new Result(true, "Firmware written. The controller is restarting.");
            }
            l.log(s.text());
            if (s.state == ERROR || (s.status != 0 && s.state != MANIFEST_SYNC)) {
                String why = s.status == 7 ? "The bootloader rejected the image (verification failed). Nothing was changed; the previous firmware still runs or recovery is available."
                        : "The bootloader reported: " + (s.status < STATUS_NAMES.length ? STATUS_NAMES[s.status] : "error " + s.status) + ".";
                return new Result(false, why);
            }
            if (s.state == DFU_IDLE && accepted) return new Result(true, "Firmware written.");
            sleep(Math.max(s.pollMs, 50));
        }
        return new Result(false, "The bootloader never finished. Unplug the controller and check it again.");
    }

    private static Status pollUntilIdle(Pipe p) {
        for (int i = 0; i < 200; i++) {
            Status s = getStatus(p, 5000);
            if (s == null) return null;
            if (s.state == DNBUSY || s.state == DNLOAD_SYNC) { sleep(Math.max(s.pollMs, 1)); continue; }
            return s;
        }
        return null;
    }
}
