package com.electrotamp.kishids.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.electrotamp.kishids.core.CalibrationWizard;
import com.electrotamp.kishids.core.ConfigBlock;
import com.electrotamp.kishids.core.DfuFlasher;
import com.electrotamp.kishids.core.FirmwareImage;
import com.electrotamp.kishids.core.Json;
import com.electrotamp.kishids.core.LiveInput;
import com.electrotamp.kishids.core.Settings;
import com.electrotamp.kishids.core.StockFirmware;
import com.electrotamp.kishids.core.Telemetry;
import com.electrotamp.kishids.usb.UsbLink;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application state shared by every page: settings, live device data, and the apply/restore workflow.  The Android counterpart of the
 * desktop app's AppModel: the controller is the source of truth when live editing is on, edits are pushed as they happen (debounced)
 * and saved to the controller's flash after a quiet moment.  Main-thread only; USB work runs on worker threads and posts back.
 */
public final class AppModel {
    public enum Device { DISCONNECTED, BOOTLOADER, STOCK, CUSTOM_LOCKED, CUSTOM }

    public enum Phase { IDLE, WAITING_FOR_BOOTLOADER, READY, FLASHING, VERIFYING, DONE, FAILED }

    public enum Bar { NONE, APPLY, RESTART }

    private final Context ctx;
    private final File dataDir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "kishi-io"); t.setDaemon(true); return t; });
    private final long t0 = SystemClock.uptimeMillis();
    private final byte[] baseImage;

    public final boolean demo;
    public final ConfigBlock config = new ConfigBlock();
    public final Settings s = new Settings(config);
    public final CalibrationWizard calibration = new CalibrationWizard(config);
    public final UsbLink link;
    public final StockFirmware stock;

    /** Raised when the workflow wants the Firmware page in front. */
    public Runnable onShowFirmware;
    /** Asked before a flash that would overwrite Razer's firmware while no copy of it is saved; return false to cancel. */
    public interface Confirm { void ask(Runnable proceed); }
    public Confirm confirmWithoutOriginal;

    // ---- live device data ----
    public Device device = Device.DISCONNECTED;
    public LiveInput live = LiveInput.NEUTRAL;
    public Telemetry tele;
    public String serial;
    public boolean accessDenied;
    public long appliedCrc;

    private boolean linked, pushing, pushAgain, adopting, saving;
    private int failures;
    private String liveError;
    private long lastStatusKey = -1;

    public AppModel(Context context, boolean demo) {
        ctx = context.getApplicationContext();
        this.demo = demo;
        dataDir = ctx.getFilesDir();
        baseImage = loadBaseImage(ctx);
        stock = new StockFirmware(new File(dataDir, "stock"));
        link = new UsbLink(ctx);
        loadCurrent();
        link.addCandidate(config.get("vid"), config.get("pid"));
        config.addListener(field -> {
            saveCurrentTimer.restart(500);
            link.addCandidate(config.get("vid"), config.get("pid"));
            if (isLive() && linked) {
                debounceTimer.restart(70);          // settle a slider drag, then push
                persistTimer.cancel();
            }
        });
        if (!demo) link.start();
    }

    private static byte[] loadBaseImage(Context c) {
        try (InputStream in = c.getAssets().open("kishi_ds4.bin")) { return readAll(in); }
        catch (IOException e) { throw new IllegalStateException("embedded firmware missing", e); }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    private final Runnable releaseTask = this::releaseIfIdle;

    private void releaseIfIdle() { if (!busy()) link.setActive(false); }

    public void onForeground() {
        main.removeCallbacks(releaseTask);
        if (!demo) link.setActive(true);
    }

    /**
     * Hand the controller back to Android so games can use it, a few seconds after the app leaves the screen (a rotation briefly looks
     * like leaving) and never while a flash is under way.
     */
    public void onBackground() {
        if (demo) return;
        main.removeCallbacks(releaseTask);
        main.postDelayed(releaseTask, 3000);
    }

    // ---------------------------------------------------------------- derived status text

    public boolean isConnected() { return device != Device.DISCONNECTED; }

    /** KishiDS firmware with live editing, talking to us. */
    public boolean isLive() { return device == Device.CUSTOM && tele != null && tele.live; }

    public String deviceTitle() {
        switch (device) {
            case CUSTOM: return "Kishi connected";
            case CUSTOM_LOCKED: return "Kishi found";
            case STOCK: return "Kishi (stock firmware)";
            case BOOTLOADER: return "Bootloader ready";
            default: return "No controller";
        }
    }

    public String deviceDetail() {
        switch (device) {
            case CUSTOM:
                if (isLive()) return "Live editing is on. Changes apply instantly.";
                if (tele != null) return "Older KishiDS firmware. Apply once to switch on live editing.";
                return "Running KishiDS firmware";
            case CUSTOM_LOCKED: return accessDenied ? "KishiDS needs your OK to use the controller's USB port." : "Waiting for USB access.";
            case STOCK: return "Original Razer firmware. Apply to switch to KishiDS firmware.";
            case BOOTLOADER: return "In firmware-update mode, ready to flash";
            default: return "Plug in your Kishi V1 to get started";
        }
    }

    /** True when the controller is running exactly the settings shown here. */
    public boolean inSync() { return device == Device.CUSTOM && tele != null && tele.configCrc == config.crc(); }

    /** Settings differ from what the controller is (or last was) running. */
    public boolean dirty() {
        return device == Device.CUSTOM && tele != null ? tele.configCrc != config.crc() : config.crc() != appliedCrc;
    }

    public String syncText() {
        switch (device) {
            case CUSTOM:
                if (tele == null) return "Reading controller…";
                if (isLive() && !linked) return liveError != null ? "Couldn't read controller: " + liveError : "Reading controller settings…";
                if (isLive() && !inSync()) return liveError != null ? "Couldn't sync: " + liveError : "Applying…";
                if (isLive()) return tele.unsaved ? "Live · saving…" : "Live · saved on controller";
                return inSync() ? "Controller is up to date" : "Changes not applied yet";
            case CUSTOM_LOCKED: return accessDenied ? "Allow USB access to connect" : "Waiting for USB access";
            case DISCONNECTED: return "Settings saved on this phone";
            default: return dirty() ? "Changes not applied yet" : "Settings saved";
        }
    }

    /** What the floating bar at the bottom should offer. */
    public Bar bar() {
        if (isLive()) return linked && tele.identityDiffers ? Bar.RESTART : Bar.NONE;   // with live editing only a USB-identity change needs a restart
        return device == Device.CUSTOM && tele != null && dirty() ? Bar.APPLY : Bar.NONE;
    }

    public String barMessage() {
        return bar() == Bar.RESTART ? "The new device name or USB rate takes effect after restarting the controller" : "You have changes that aren't on the controller yet";
    }

    public String barPrimaryText() { return bar() == Bar.RESTART ? "Restart controller" : "Apply to controller"; }

    public void barPrimary() { if (bar() == Bar.RESTART) restartController(); else beginApply(); }

    public boolean showApplyButton() { return !isLive(); }

    // ---------------------------------------------------------------- per-frame update

    /** Called every frame while the app is in front. */
    public void tick() {
        if (demo) { tickDemo(); return; }
        Device d = Device.valueOf(link.state.name());
        if (d != device) {
            device = d;
            if (d != Device.CUSTOM) { live = LiveInput.NEUTRAL; tele = null; }
        }
        serial = link.serial;
        accessDenied = link.accessDenied;
        if (device == Device.CUSTOM) {
            live = link.input;
            tele = link.latest;
        }
        updateLiveLink();
        flowTick();
        calibration.update(tele, SystemClock.uptimeMillis());
    }

    /** The bits of a Kishi that are physically held, from telemetry. */
    public boolean pressed(int button) { return tele != null && (tele.buttonMask & (1 << button)) != 0; }

    private void tickDemo() {
        double t = (SystemClock.uptimeMillis() - t0) / 1000.0;
        device = Device.CUSTOM;
        serial = "SAMPLE000000001";
        live = new LiveInput(axis(Math.cos(t * 1.3) * 0.7), axis(Math.sin(t * 1.3) * 0.7), axis(Math.sin(t * 0.9) * 0.5), axis(Math.cos(t * 1.7) * 0.5),
                (int) (Math.max(0, Math.sin(t * 1.1)) * 255), (int) (Math.max(0, Math.sin(t * 0.7 + 1)) * 255));
        int mask = 1 << ((int) (t * 2) % 15);
        int[] adc = { 2000, 2100, 1963, 2041, 2130, 2144 };
        tele = new Telemetry(true, true, mask, adc, config.crc(), true, false, false, false, config.crc());
        linked = true;
        calibration.update(tele, SystemClock.uptimeMillis());
        flowTick();
    }

    private static int axis(double v) { return (int) Math.max(1, Math.min(255, 128 + v * 127)); }

    // ---------------------------------------------------------------- live editing

    private final Debounce debounceTimer = new Debounce(main, this::pushAsync);          // settle a slider drag, then push
    private final Debounce persistTimer = new Debounce(main, this::saveToControllerAsync);   // quiet period before writing flash
    private final Debounce saveCurrentTimer = new Debounce(main, this::saveCurrent);

    private void updateLiveLink() {
        if (!isLive()) { linked = false; failures = 0; liveError = null; return; }
        Telemetry t = tele;
        if (!linked) {
            if (t.configCrc == config.crc()) linked = true;                         // already identical
            else if (!adopting && failures < 3) adoptControllerSettingsAsync();     // the controller is the source of truth
            return;
        }
        boolean debouncing = debounceTimer.isPending();
        if (t.configCrc != config.crc() && !pushing && !debouncing && failures < 3) debounceTimer.restart(70);
        else if (t.configCrc == config.crc()) { failures = 0; liveError = null; }
        if (t.configCrc == config.crc() && t.unsaved && !persistTimer.isPending() && !pushing && !debouncing && !saving) persistTimer.restart(1500);
    }

    private void pushAsync() {
        if (pushing) { pushAgain = true; return; }
        pushing = true;
        runPush();
    }

    private void runPush() {
        pushAgain = false;
        byte[] block = config.toBytes();
        if (tele != null && tele.configCrc == config.crc()) { pushing = false; return; }   // already running exactly this
        io.execute(() -> {
            UsbLink.LiveResult r = link.pushConfig(block);
            main.post(() -> {
                if (r.ok) { failures = 0; liveError = null; persistTimer.restart(1500); }
                else { failures++; liveError = r.message; }
                if (pushAgain) runPush(); else pushing = false;
            });
        });
    }

    private void saveToControllerAsync() {
        if (saving || !isLive()) return;
        saving = true;
        io.execute(() -> {
            UsbLink.LiveResult r = link.save();
            main.post(() -> { saving = false; if (!r.ok) liveError = r.message; });
        });
    }

    /** On first contact, load whatever the controller is running into the app (an old autosave must not overwrite it). */
    private void adoptControllerSettingsAsync() {
        adopting = true;
        io.execute(() -> {
            Object[] res = link.readConfig();
            main.post(() -> {
                adopting = false;
                if (res[0] == null) { failures++; liveError = (String) res[1]; }
                else {
                    failures = 0; liveError = null;
                    linked = true;                      // set first: the load below is not an edit to push back
                    config.loadBytes((byte[]) res[0]);
                }
            });
        });
    }

    /** Make sure the controller has the current settings saved, then reset it so USB identity changes take effect. */
    public void restartController() {
        if (!isLive()) return;
        link.addCandidate(config.get("vid"), config.get("pid"));
        debounceTimer.cancel();
        persistTimer.cancel();
        byte[] block = config.toBytes();
        io.execute(() -> {
            UsbLink.LiveResult r = link.pushConfig(block);
            if (r.ok) r = link.save();
            if (!r.ok) { String why = r.message; main.post(() -> liveError = why); return; }
            link.reboot();
        });
    }

    // ---------------------------------------------------------------- persistence

    private File currentFile() { return new File(dataDir, "current.json"); }

    private void loadCurrent() {
        try {
            File f = currentFile();
            if (f.isFile()) {
                try (FileInputStream in = new FileInputStream(f)) { config.loadProfile(Json.parseObject(new String(readAll(in), StandardCharsets.UTF_8))); }
            }
            File a = new File(dataDir, "applied.txt");
            if (a.isFile()) {
                try (FileInputStream in = new FileInputStream(a)) { appliedCrc = Long.parseLong(new String(readAll(in), StandardCharsets.UTF_8).trim()); }
            }
        } catch (IOException | RuntimeException e) { /* a corrupt file just means defaults */ }
    }

    private void saveCurrent() {
        try (FileOutputStream o = new FileOutputStream(currentFile())) { o.write(Json.write(config.toProfile()).getBytes(StandardCharsets.UTF_8)); }
        catch (IOException e) { /* best effort */ }
    }

    private void writeApplied(long crc) {
        appliedCrc = crc;
        try (FileOutputStream o = new FileOutputStream(new File(dataDir, "applied.txt"))) { o.write(Long.toString(crc).getBytes(StandardCharsets.UTF_8)); }
        catch (IOException e) { /* best effort */ }
    }

    public void saveProfile(OutputStream out) throws IOException { out.write(Json.write(config.toProfile()).getBytes(StandardCharsets.UTF_8)); }

    /** Load a profile file (the same JSON the desktop app writes).  Returns an error message, or null. */
    public String loadProfile(InputStream in) {
        try {
            Map<String, Object> d = Json.parseObject(new String(readAll(in), StandardCharsets.UTF_8));
            config.loadProfile(d);
            return null;
        } catch (IllegalArgumentException e) { return "That file isn't a KishiDS profile."; }
        catch (IOException e) { return "Could not read that file: " + e.getMessage(); }
    }

    // first-run setup guide
    private File setupFile() { return new File(dataDir, "setup-done.txt"); }

    public boolean setupDone() { return setupFile().exists(); }

    public void markSetupDone() {
        try (FileOutputStream o = new FileOutputStream(setupFile())) { o.write('1'); } catch (IOException e) { /* optional */ }
    }

    // ---------------------------------------------------------------- stock firmware

    public boolean hasStock() { return stock.has(); }

    public String stockStatus() { return hasStock() ? "Original firmware v2.70 is saved" : "Not saved yet"; }

    /** Flashing now would replace Razer's firmware with no way back (the controller still runs it, or is in update mode). */
    public boolean needsOriginal() { return !hasStock() && device != Device.CUSTOM && device != Device.CUSTOM_LOCKED; }

    /** Import from a stream (.bin or .apk).  Returns an error message, or null on success. */
    public String importStock(InputStream in, String name) { return stock.importStream(in, name); }

    // ---------------------------------------------------------------- apply / restore workflow

    public Phase phase = Phase.IDLE;
    public double progress;
    public String flowTitle = "Ready when you are", flowDetail = "";
    public boolean restoring;
    private final StringBuilder flowLog = new StringBuilder();
    private boolean flashNow, cancelled;
    private byte[] flowImage;
    private long verifyStart;
    private int settledTries;
    private long expectedCrc;

    public boolean busy() { return phase == Phase.FLASHING || phase == Phase.VERIFYING; }

    public boolean showSteps() { return phase == Phase.WAITING_FOR_BOOTLOADER || phase == Phase.READY; }

    public boolean canStart() { return phase == Phase.IDLE || phase == Phase.DONE || phase == Phase.FAILED; }

    public String log() { return (flowLog + "\n" + link.logText()).trim(); }

    public void beginApply() {
        if (!canStart()) return;
        if (needsOriginal() && confirmWithoutOriginal != null) { confirmWithoutOriginal.ask(() -> startFlow(false)); return; }
        startFlow(false);
    }

    public void beginRestore() { if (canStart()) startFlow(true); }

    private void startFlow(boolean restore) {
        restoring = restore;
        flowLog.setLength(0);
        progress = 0;
        cancelled = false;
        flashNow = false;
        if (onShowFirmware != null) onShowFirmware.run();

        if (restore) {
            byte[] st = stock.loadStored();
            if (st == null) { fail("The original firmware isn't imported yet", "Import it from a backup file or the Razer app (APK) first."); return; }
            flowImage = st;
        } else {
            byte[] img = FirmwareImage.patch(baseImage, config);
            List<String> problems = FirmwareImage.validate(img, true);
            if (!problems.isEmpty()) { fail("This configuration can't be built", problems.get(0)); return; }
            flowImage = img;
            expectedCrc = config.crc();
            link.addCandidate(config.get("vid"), config.get("pid"));
        }
        link.setWantBootloader(true);
        link.setActive(true);
        if (device == Device.BOOTLOADER) enterReady();
        else {
            flowTitle = "Put the Kishi in update mode";
            flowDetail = "Detach it from the phone, hold Y + B + Right Function, then plug it back in while holding.";
            phase = Phase.WAITING_FOR_BOOTLOADER;
        }
    }

    private void enterReady() {
        if (demo || link.bootloaderReady()) {
            flowTitle = "Bootloader detected";
            flowDetail = restoring ? "Ready to restore the original Razer firmware." : "Ready to write your settings to the controller.";
            phase = Phase.READY;
        } else {
            flowTitle = "Bootloader detected";
            flowDetail = "Allow KishiDS to use it in the Android prompt (tap Allow access if you don't see one).";
            phase = Phase.WAITING_FOR_BOOTLOADER;
        }
    }

    public void allowAccess() { link.requestAccess(); }

    public void cancelFlow() {
        if (!showSteps()) return;
        cancelled = true;
        phase = Phase.IDLE;
        flowTitle = "Cancelled";
        flowDetail = "Nothing was changed.";
        link.setWantBootloader(false);
    }

    public void flashNow() {
        if (phase != Phase.READY) return;
        flashNow = true;
    }

    private void flowTick() {
        if (demo) return;
        switch (phase) {
            case WAITING_FOR_BOOTLOADER:
                if (device == Device.BOOTLOADER) enterReady();
                break;
            case READY:
                if (device != Device.BOOTLOADER) { phase = Phase.WAITING_FOR_BOOTLOADER; flowTitle = "Put the Kishi in update mode"; flowDetail = "The bootloader disconnected. Unplug it and enter update mode again."; }
                else if (flashNow) startFlashing();
                break;
            case VERIFYING:
                verifyTick();
                break;
            default:
                break;
        }
    }

    private void startFlashing() {
        flashNow = false;
        flowTitle = restoring ? "Restoring original firmware" : "Writing firmware";
        flowDetail = "Keep the controller connected and this screen open.";
        phase = Phase.FLASHING;
        progress = 0;
        final byte[] image = flowImage;
        io.execute(() -> {
            DfuFlasher.Result r = link.flash(image, new DfuFlasher.Listener() {
                @Override public void progress(double f) { main.post(() -> progress = f); }
                @Override public void log(String line) { synchronized (flowLog) { flowLog.append(line).append('\n'); } }
            });
            main.post(() -> {
                if (!r.success) { fail("Flashing didn't complete", r.message); return; }
                flowTitle = "Restarting controller";
                flowDetail = "Waiting for it to reconnect…";
                phase = Phase.VERIFYING;
                progress = 1;
                verifyStart = SystemClock.uptimeMillis();
                settledTries = 0;
            });
        });
    }

    private void verifyTick() {
        long waited = SystemClock.uptimeMillis() - verifyStart;
        Device want = restoring ? Device.STOCK : Device.CUSTOM;
        boolean arrived = device == want || (!restoring && device == Device.CUSTOM_LOCKED);
        if (!arrived) {
            if (waited > 30000) finishVerify(false, restoring ? "Original firmware written" : "Firmware written",
                    "Unplug and replug the controller if it doesn't reappear.");
            return;
        }
        if (restoring) {
            writeApplied(0);
            link.setWantBootloader(false);
            done("Original firmware restored", "Your Kishi is back to stock.");
            return;
        }
        if (device == Device.CUSTOM && tele != null && tele.configCrc == expectedCrc) {
            writeApplied(expectedCrc);
            link.setWantBootloader(false);
            done("Applied and verified", "The controller reports it is running exactly these settings.");
            return;
        }
        if (waited > 40000 || (device == Device.CUSTOM && waited > 12000 && ++settledTries > 20)) {
            writeApplied(expectedCrc);
            link.setWantBootloader(false);
            done("Firmware written", device == Device.CUSTOM_LOCKED
                    ? "Allow USB access for the new firmware, then check the status in the header."
                    : "The controller restarted but didn't confirm its settings yet. Unplug and replug it, then check the status in the header.");
        }
    }

    private void finishVerify(boolean ok, String title, String detail) {
        link.setWantBootloader(false);
        done(title, detail);
    }

    private void done(String title, String detail) { flowTitle = title; flowDetail = detail; phase = Phase.DONE; }

    private void fail(String title, String detail) {
        flowTitle = title;
        flowDetail = detail;
        phase = Phase.FAILED;
        link.setWantBootloader(false);
    }

    /** Developer aid for screenshots: show a given workflow state without flashing anything. */
    public void showDemoPhase(Phase p, double prog, String title, String detail) {
        progress = prog; flowTitle = title; flowDetail = detail; phase = p;
    }

    public void dispose() {
        main.removeCallbacksAndMessages(null);
        saveCurrent();
        link.stop();
        io.shutdown();
    }
}
