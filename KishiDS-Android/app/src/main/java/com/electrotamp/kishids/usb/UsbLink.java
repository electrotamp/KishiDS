package com.electrotamp.kishids.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.electrotamp.kishids.core.ConfigBlock;
import com.electrotamp.kishids.core.ConfigLayout;
import com.electrotamp.kishids.core.DfuFlasher;
import com.electrotamp.kishids.core.LiveInput;
import com.electrotamp.kishids.core.LiveProtocol;
import com.electrotamp.kishids.core.Telemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The phone side of the Kishi's USB connection.  Android is the USB host here: there is no HID API for feature reports, so this claims
 * the controller's interface and sends the HID class control requests (GET_REPORT / SET_REPORT) directly, exactly what the desktop app
 * does through HidD_GetFeature / HidD_SetFeature.  It watches for the controller in its three guises (Razer firmware, the bootloader,
 * KishiDS firmware), streams telemetry and input reports from the KishiDS firmware, and runs the live-editing commands and the DFU flash.
 *
 * Everything blocking happens on worker threads; the public fields are volatile snapshots meant to be read from the UI thread, and
 * {@link #onChange} is posted to the main thread whenever one of them changes.
 */
public final class UsbLink {
    public enum State { DISCONNECTED, BOOTLOADER, STOCK, CUSTOM_LOCKED, CUSTOM }

    public static final int VID_RAZER = 0x27F8, PID_STOCK = 0x0BBF, PID_BOOT = 0x0BC0;
    private static final String ACTION_PERMISSION = "com.electrotamp.kishids.USB_PERMISSION";

    public static final class LiveResult {
        public final boolean ok;
        public final String message;

        LiveResult(boolean ok, String message) { this.ok = ok; this.message = message; }

        static LiveResult success() { return new LiveResult(true, "ok"); }

        static LiveResult fail(String why) { return new LiveResult(false, why); }
    }

    private final Context ctx;
    private final UsbManager mgr;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object wakeLock = new Object();
    private final Object io = new Object();
    private final List<int[]> candidates = new ArrayList<>();
    private final Set<String> asked = new HashSet<>();      // device names already shown the permission dialog this attach
    private final Set<String> foreign = new HashSet<>();    // look-alike devices that did not answer as KishiDS firmware
    private final AtomicBoolean notifyPending = new AtomicBoolean();
    private final StringBuilder logBuf = new StringBuilder();

    public volatile State state = State.DISCONNECTED;
    public volatile Telemetry latest;
    public volatile LiveInput input = LiveInput.NEUTRAL;
    public volatile String serial;
    /** The controller is there but the user has refused (or not yet answered) the USB access prompt. */
    public volatile boolean accessDenied;
    public Runnable onChange;

    private volatile boolean running, active, busy, wantBootloader;
    private volatile int generation;
    private volatile boolean rescan;
    private Thread monitor;
    private volatile UsbDevice device;
    private volatile UsbDeviceConnection conn;
    private boolean receiverRegistered;

    public UsbLink(Context context) {
        ctx = context.getApplicationContext();
        mgr = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        candidates.add(new int[] { 0x054C, 0x05C4 });
    }

    // ---------------------------------------------------------------- log

    public void log(String s) {
        synchronized (logBuf) {
            logBuf.append(String.format("%tT.%<tL  ", System.currentTimeMillis())).append(s).append('\n');
            if (logBuf.length() > 24000) logBuf.delete(0, logBuf.length() - 20000);
        }
    }

    public String logText() { synchronized (logBuf) { return logBuf.toString(); } }

    // ---------------------------------------------------------------- lifecycle

    /** Tell the link which VID/PID the KishiDS firmware might currently be using. */
    public void addCandidate(int vid, int pid) {
        synchronized (candidates) {
            for (int[] c : candidates) if (c[0] == vid && c[1] == pid) return;
            candidates.add(new int[] { vid, pid });
        }
        wake();
    }

    public void start() {
        if (running) return;
        running = true;
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else ctx.registerReceiver(receiver, f);
        receiverRegistered = true;
        monitor = new Thread(this::loop, "kishi-usb");
        monitor.setDaemon(true);
        monitor.start();
    }

    public void stop() {
        running = false;
        if (receiverRegistered) { try { ctx.unregisterReceiver(receiver); } catch (RuntimeException ignored) { } receiverRegistered = false; }
        wake();
    }

    /** Foreground: connect and talk to the controller.  Background: hand it back to Android so games can use it. */
    public void setActive(boolean on) {
        active = on;
        if (on) { asked.clear(); }
        wake();
    }

    /** True while the flash flow needs the bootloader: it is then allowed to show its permission prompt. */
    public void setWantBootloader(boolean on) {
        wantBootloader = on;
        if (on) asked.remove(device == null ? "" : device.getDeviceName());
        wake();
    }

    private void wake() { synchronized (wakeLock) { rescan = true; wakeLock.notifyAll(); } }

    private void changed() {
        if (onChange != null && notifyPending.compareAndSet(false, true)) {
            main.post(() -> { notifyPending.set(false); if (onChange != null) onChange.run(); });
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_PERMISSION.equals(a)) {
                boolean granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                log("USB access " + (granted ? "granted" : "refused"));
                if (!granted) { accessDenied = true; changed(); }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null) { asked.remove(d.getDeviceName()); foreign.remove(d.getDeviceName()); }
                log("USB device detached");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) log("USB device attached");
            wake();
        }
    };

    // ---------------------------------------------------------------- monitor

    private void loop() {
        while (running) {
            try { scan(); } catch (Throwable t) { log("scan: " + t); }
            synchronized (wakeLock) {
                if (!rescan) { try { wakeLock.wait(500); } catch (InterruptedException e) { return; } }
                rescan = false;
            }
        }
        teardown(false);
    }

    private boolean isCandidate(UsbDevice d) {
        synchronized (candidates) {
            for (int[] c : candidates) if (c[0] == d.getVendorId() && c[1] == d.getProductId()) return true;
        }
        return false;
    }

    private static boolean looksLikeKishiDs(UsbDevice d) {
        String m = d.getManufacturerName();
        return m == null || m.contains("KishiDS");   // a real DualShock reports Sony; an unreadable string gets the benefit of the doubt
    }

    private void scan() {
        if (busy) return;
        if (!active) {
            if (conn != null) teardown(true);
            return;
        }
        Map<String, UsbDevice> list = mgr.getDeviceList();
        UsbDevice boot = null, custom = null, stock = null;
        for (UsbDevice d : list.values()) {
            if (d.getVendorId() == VID_RAZER && d.getProductId() == PID_BOOT) boot = d;
            else if (d.getVendorId() == VID_RAZER && d.getProductId() == PID_STOCK) stock = d;
            else if (isCandidate(d) && looksLikeKishiDs(d) && !foreign.contains(d.getDeviceName())) custom = d;
        }

        if (boot != null) { followPlain(boot, State.BOOTLOADER); return; }
        if (custom != null) { followCustom(custom); return; }
        if (stock != null) { followPlain(stock, State.STOCK); return; }
        if (device != null || state != State.DISCONNECTED) {
            teardown(false);
            device = null;
            accessDenied = false;
            serial = null;
            state = State.DISCONNECTED;
            changed();
        }
    }

    private void followPlain(UsbDevice d, State s) {
        if (device == null || !device.getDeviceName().equals(d.getDeviceName()) || state != s) {
            teardown(false);
            device = d;
            accessDenied = false;
            serial = null;
            state = s;
            log((s == State.BOOTLOADER ? "Bootloader" : "Razer firmware") + " device found: " + String.format("%04X:%04X", d.getVendorId(), d.getProductId()));
            changed();
        }
        // Talking to these needs the prompt only when it is useful: the bootloader when a flash is under way.
        if (s == State.BOOTLOADER && wantBootloader && !mgr.hasPermission(d)) askPermission(d);
        if (s == State.BOOTLOADER) {
            boolean denied = !mgr.hasPermission(d) && asked.contains(d.getDeviceName());
            if (denied != accessDenied) { accessDenied = denied; changed(); }
        }
        if (s == State.STOCK && serial == null && mgr.hasPermission(d)) {
            try { serial = d.getSerialNumber(); changed(); } catch (SecurityException ignored) { }
        }
    }

    private void followCustom(UsbDevice d) {
        if (conn != null && device != null && device.getDeviceName().equals(d.getDeviceName()) && state == State.CUSTOM) return;   // connected and healthy
        if (device == null || !device.getDeviceName().equals(d.getDeviceName())) {
            teardown(false);
            device = d;
            serial = null;
            state = State.CUSTOM_LOCKED;
            log("Kishi found: " + String.format("%04X:%04X", d.getVendorId(), d.getProductId()) + " " + d.getProductName());
            changed();
        }
        if (!mgr.hasPermission(d)) {
            if (asked.contains(d.getDeviceName())) { if (!accessDenied) { accessDenied = true; changed(); } }
            else askPermission(d);
            return;
        }
        accessDenied = false;
        openCustom(d);
    }

    private void askPermission(UsbDevice d) {
        asked.add(d.getDeviceName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;   // the system adds extras to the intent
        Intent in = new Intent(ACTION_PERMISSION).setPackage(ctx.getPackageName());
        log("Asking for USB access");
        mgr.requestPermission(d, PendingIntent.getBroadcast(ctx, 0, in, flags));
    }

    private void openCustom(UsbDevice d) {
        UsbDeviceConnection c = mgr.openDevice(d);
        if (c == null) { log("openDevice returned null"); return; }
        UsbInterface iface = d.getInterfaceCount() > 0 ? d.getInterface(0) : null;
        if (iface == null) { c.close(); log("device has no interface"); return; }
        // The system's HID driver owns the interface; claiming it with force detaches that driver so the control requests get through.
        if (!c.claimInterface(iface, true)) { c.close(); log("claimInterface failed"); return; }
        UsbEndpoint epIn = null;
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint e = iface.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_INT && e.getDirection() == UsbConstants.USB_DIR_IN) epIn = e;
        }
        Telemetry t = null;
        for (int i = 0; i < 4 && t == null; i++) {
            t = Telemetry.parse(getFeature(c, 0xAB, 64));
            if (t == null) sleep(60);
        }
        if (t == null) {
            log("no KishiDS telemetry from " + d.getDeviceName() + " (another gamepad?); letting Android have it back");
            giveBack(c, iface);
            foreign.add(d.getDeviceName());
            return;
        }
        String sn = null;
        try { sn = c.getSerial(); } catch (RuntimeException ignored) { }
        log("Connected to KishiDS firmware, serial " + sn + ", live editing " + (t.live ? "yes" : "no"));
        final int gen = ++generation;
        conn = c;
        serial = sn;
        latest = t;
        input = LiveInput.NEUTRAL;
        state = State.CUSTOM;
        changed();
        new Thread(() -> telemetryLoop(c, gen), "kishi-telemetry").start();
        if (epIn != null) {
            final UsbEndpoint ep = epIn;
            new Thread(() -> inputLoop(c, ep, gen), "kishi-input").start();
        }
    }

    private void giveBack(UsbDeviceConnection c, UsbInterface iface) {
        try { c.releaseInterface(iface); } catch (RuntimeException ignored) { }
        // Releasing leaves the interface without a driver; a USB reset makes Android bind its HID driver to it again.
        resetQuietly(c);
        c.close();
    }

    /** UsbDeviceConnection.resetDevice() is not in the public SDK; call it by reflection and carry on if the platform refuses. */
    private void resetQuietly(UsbDeviceConnection c) {
        try {
            Object r = UsbDeviceConnection.class.getMethod("resetDevice").invoke(c);
            log("USB reset: " + r);
        } catch (Throwable t) { log("USB reset unavailable: " + t); }
    }

    /** Drop the connection.  With {@code rebind}, also hand the controller back to Android's HID driver. */
    private void teardown(boolean rebind) {
        generation++;
        UsbDeviceConnection c = conn;
        conn = null;
        latest = null;
        input = LiveInput.NEUTRAL;
        if (c == null) return;
        UsbDevice d = device;
        if (rebind && d != null && d.getInterfaceCount() > 0) {
            log("Handing the controller back to Android");
            giveBack(c, d.getInterface(0));
        } else {
            try { c.close(); } catch (RuntimeException ignored) { }
        }
        if (rebind) { state = State.CUSTOM_LOCKED; asked.remove(d == null ? "" : d.getDeviceName()); changed(); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ---------------------------------------------------------------- streaming

    private void telemetryLoop(UsbDeviceConnection c, int gen) {
        int fails = 0;
        while (gen == generation) {
            Telemetry t = Telemetry.parse(getFeature(c, 0xAB, 64));
            if (t == null) {
                if (++fails >= 6) break;
                sleep(30);
                continue;
            }
            fails = 0;
            Telemetry prev = latest;
            latest = t;
            if (!t.sameStateAs(prev)) changed();
            sleep(8);
        }
        if (gen == generation) {   // lost it rather than being told to stop
            log("Lost the controller (telemetry stopped)");
            teardown(false);
            device = null;
            state = State.DISCONNECTED;
            changed();
            wake();
        }
    }

    private void inputLoop(UsbDeviceConnection c, UsbEndpoint ep, int gen) {
        byte[] buf = new byte[Math.max(64, ep.getMaxPacketSize())];
        while (gen == generation) {
            int n = c.bulkTransfer(ep, buf, buf.length, 100);
            if (n >= 10) {
                LiveInput li = LiveInput.parse(buf, n);
                if (li != null) input = li;
            } else if (n < 0) sleep(4);
        }
    }

    // ---------------------------------------------------------------- HID feature reports (control requests)

    private static byte[] getFeature(UsbDeviceConnection c, int id, int len) {
        byte[] b = new byte[len];
        int n = c.controlTransfer(0xA1, 0x01, 0x0300 | id, 0, b, len, 1000);   // GET_REPORT, feature
        return n > 0 ? Arrays.copyOf(b, n) : null;
    }

    private static boolean setFeature(UsbDeviceConnection c, byte[] packet) {
        int n = c.controlTransfer(0x21, 0x09, 0x0300 | (packet[0] & 0xFF), 0, packet, packet.length, 1000);   // SET_REPORT, feature
        return n == packet.length;
    }

    private static LiveProtocol.Status readLiveStatus(UsbDeviceConnection c) {
        return LiveProtocol.parseStatus(getFeature(c, LiveProtocol.REPORT_ID, 64));
    }

    // ---------------------------------------------------------------- live editing (blocking; call from a worker thread)

    /** Make {@code block} the controller's active configuration (not saved to flash). */
    public LiveResult pushConfig(byte[] block) {
        synchronized (io) {
            UsbDeviceConnection c = conn;
            if (c == null) return LiveResult.fail("controller not connected");
            for (byte[] p : LiveProtocol.pushPackets(block)) if (!setFeature(c, p)) return LiveResult.fail("USB write failed");
            LiveProtocol.Status st = readLiveStatus(c);
            if (st == null) return LiveResult.fail("no reply from the controller");
            if (st.result != LiveProtocol.RESULT_OK) return LiveResult.fail(LiveProtocol.describe(st.result));
            long want = (block[12] & 0xFFL) | ((block[13] & 0xFFL) << 8) | ((block[14] & 0xFFL) << 16) | ((block[15] & 0xFFL) << 24);
            return st.activeCrc == want ? LiveResult.success() : LiveResult.fail("the controller adjusted some values");
        }
    }

    /** Write the active configuration to the controller's flash so it survives unplugging. */
    public LiveResult save() { return persistent(LiveProtocol.CMD_SAVE); }

    /** Forget the saved configuration; the controller falls back to what was flashed. */
    public LiveResult eraseSaved() { return persistent(LiveProtocol.CMD_ERASE); }

    private LiveResult persistent(int cmd) {
        synchronized (io) {
            UsbDeviceConnection c = conn;
            if (c == null) return LiveResult.fail("controller not connected");
            if (!setFeature(c, LiveProtocol.packet(cmd))) return LiveResult.fail("USB write failed");
            for (int i = 0; i < 25; i++) {   // the firmware does the flash work right after replying
                LiveProtocol.Status st = readLiveStatus(c);
                if (st != null && !st.has(LiveProtocol.FLAG_PENDING)) {
                    return st.result == LiveProtocol.RESULT_OK ? LiveResult.success() : LiveResult.fail(LiveProtocol.describe(st.result));
                }
                sleep(20);
            }
            return LiveResult.fail("the controller did not finish in time");
        }
    }

    /** Reset the controller.  It re-enumerates, which is how a new USB name/ID takes effect. */
    public LiveResult reboot() {
        synchronized (io) {
            UsbDeviceConnection c = conn;
            if (c == null) return LiveResult.fail("controller not connected");
            setFeature(c, LiveProtocol.packet(LiveProtocol.CMD_REBOOT));   // it resets before the transfer completes, so this usually reports failure
            asked.clear();
            return LiveResult.success();
        }
    }

    /** Read the configuration the controller is running right now; the second element is an error message when the first is null. */
    public Object[] readConfig() {
        synchronized (io) {
            UsbDeviceConnection c = conn;
            if (c == null) return new Object[] { null, "controller not connected" };
            byte[] block = new byte[ConfigLayout.SIZE];
            for (int i = 0; i < LiveProtocol.CHUNKS; i++) {
                if (!setFeature(c, LiveProtocol.packet(LiveProtocol.CMD_READ_SELECT, i))) return new Object[] { null, "USB write failed" };
                LiveProtocol.Status st = readLiveStatus(c);
                if (st == null || st.readIndex != i) return new Object[] { null, "no reply from the controller" };
                System.arraycopy(st.chunk, 0, block, i * LiveProtocol.CHUNK_SIZE, LiveProtocol.CHUNK_SIZE);
            }
            if (!new ConfigBlock(block).isValid()) return new Object[] { null, "the controller returned an invalid block" };
            return new Object[] { block, "" };
        }
    }

    // ---------------------------------------------------------------- firmware flashing

    /** True when the bootloader is attached and the app may talk to it. */
    public boolean bootloaderReady() {
        UsbDevice d = device;
        return state == State.BOOTLOADER && d != null && mgr.hasPermission(d);
    }

    /** Ask for access to the bootloader (the system shows its own dialog). */
    public void requestAccess() {
        UsbDevice d = device;
        if (d == null) return;
        asked.remove(d.getDeviceName());
        accessDenied = false;
        wake();
    }

    /** Flash {@code image} to the bootloader.  Blocks; run it on a worker thread. */
    public DfuFlasher.Result flash(byte[] image, DfuFlasher.Listener listener) {
        busy = true;
        UsbDeviceConnection c = null;
        try {
            UsbDevice d = null;
            for (UsbDevice x : mgr.getDeviceList().values()) if (x.getVendorId() == VID_RAZER && x.getProductId() == PID_BOOT) d = x;
            if (d == null) return fail("The Kishi bootloader isn't connected. Hold Y + B + Right Function while plugging it in.");
            if (!mgr.hasPermission(d)) return fail("KishiDS doesn't have permission to use the bootloader yet. Allow it in the prompt, then try again.");
            c = mgr.openDevice(d);
            if (c == null) return fail("Android wouldn't let the app open the bootloader.");
            UsbInterface iface = d.getInterface(0);
            if (!c.claimInterface(iface, true)) return fail("Could not claim the bootloader's USB interface.");
            final UsbDeviceConnection cc = c;
            DfuFlasher.Result r = DfuFlasher.flash((type, req, value, index, buf, len, timeout) -> cc.controlTransfer(type, req, value, index, buf, len, timeout), image, listener);
            log("Flash finished: " + (r.success ? "success" : "failed") + " - " + r.message);
            return r;
        } catch (RuntimeException e) {
            log("flash: " + e);
            return fail("The transfer failed: " + e.getMessage());
        } finally {
            if (c != null) { try { c.close(); } catch (RuntimeException ignored) { } }
            // The controller restarts into its new firmware, so every previous attach is stale.
            asked.clear();
            foreign.clear();
            busy = false;
            wake();
        }
    }

    private static DfuFlasher.Result fail(String why) {
        return new DfuFlasher.Result(false, why);
    }
}
