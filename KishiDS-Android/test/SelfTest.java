import com.electrotamp.kishids.core.CalibrationWizard;
import com.electrotamp.kishids.core.ConfigBlock;
import com.electrotamp.kishids.core.ConfigLayout;
import com.electrotamp.kishids.core.DfuFlasher;
import com.electrotamp.kishids.core.FirmwareImage;
import com.electrotamp.kishids.core.Json;
import com.electrotamp.kishids.core.LiveProtocol;
import com.electrotamp.kishids.core.Settings;
import com.electrotamp.kishids.core.StockFirmware;
import com.electrotamp.kishids.core.Telemetry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Plain-JVM checks of the Android app's core (no device, no Android classes), the counterpart of the desktop app's --selftest.
 * Run with: java -cp build/test-classes SelfTest <firmware image> [stock image]   (see test/run.ps1)
 */
public final class SelfTest {
    static int failures;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        byte[] image = Files.readAllBytes(new File(args[0]).toPath());
        byte[] stock = args.length > 1 ? Files.readAllBytes(new File(args[1]).toPath()) : null;

        CRC32 c = new CRC32();
        c.update("123456789".getBytes(StandardCharsets.US_ASCII));
        check("CRC-32 check vector", c.getValue() == 0xCBF43926L);

        check("embedded image validates", FirmwareImage.validate(image, true).isEmpty());
        ConfigBlock fromImage = FirmwareImage.readBlock(image);
        ConfigBlock defaults = new ConfigBlock();
        check("image's block is valid", fromImage.isValid());
        check("Java defaults == block stamped by the firmware build (layout + CRC identical)", Arrays.equals(defaults.toBytes(), fromImage.toBytes()));
        check("patching defaults leaves the image unchanged", Arrays.equals(FirmwareImage.patch(image, defaults), image));

        ConfigBlock edited = new ConfigBlock();
        edited.set("left_dead", 21);
        edited.set("button_map", 7, 0);
        edited.setString("product", "Test Pad");
        edited.set("vid", 0x1234);            // locked: must be ignored
        edited.setString("manufacturer", "Evil");
        byte[] patched = FirmwareImage.patch(image, edited);
        int diff = 0;
        for (int i = 0; i < image.length; i++) if (patched[i] != image[i]) diff++;
        check("patched image differs in the block only", patched.length == image.length && diff <= ConfigLayout.SIZE);
        ConfigBlock back = FirmwareImage.readBlock(patched);
        check("patched block round-trips", back.isValid() && back.get("left_dead") == 21 && back.get("button_map", 0) == 7 && back.getString("product").equals("Test Pad"));
        check("locked USB identity ignores edits", back.get("vid") == 0x054C && back.get("pid") == 0x05C4 && back.getString("manufacturer").equals("ElectroTamp KishiDS"));
        check("patched image still validates", FirmwareImage.validate(patched, true).isEmpty());

        edited.set("left_dead", 999);
        check("writes are clamped to the legal range", edited.get("left_dead") == 50);
        edited.setString("product", new String(new char[100]).replace('\0', 'x'));
        check("strings are truncated to fit with a NUL", edited.getString("product").length() == 31);
        edited.setString("serial", "Spoofed");
        check("locked serial ignores edits", edited.getString("serial").isEmpty());

        byte[] bad = image.clone();
        bad[4] = bad[5] = bad[6] = bad[7] = 0;
        bad[8] = (byte) 0xCD; bad[9] = (byte) 0xA0; bad[10] = 0; bad[11] = 0x08;
        check("an out-of-region vector table is rejected", !FirmwareImage.validate(bad, true).isEmpty());
        check("an image without a block is rejected", !FirmwareImage.validate(new byte[1000], true).isEmpty());

        // ---- live protocol ----
        ConfigBlock live = new ConfigBlock();
        live.set("led_mode", 2);
        live.set("button_map", 5, 3);
        List<byte[]> pk = LiveProtocol.pushPackets(live.toBytes());
        boolean framed = pk.size() == 9;
        for (byte[] p : pk) framed &= p.length == LiveProtocol.REPORT_SIZE && (p[0] & 0xFF) == 0xAC && p[1] == 0x4B;
        check("live: push is 8 stage packets + apply, 58 bytes each", framed);
        byte[] re = new byte[256];
        boolean idx = true;
        for (int i = 0; i < 8; i++) { System.arraycopy(pk.get(i), 4, re, i * 32, 32); idx &= pk.get(i)[2] == 1 && pk.get(i)[3] == i; }
        check("live: stage packets reassemble to the block", Arrays.equals(re, live.toBytes()) && idx && pk.get(8)[2] == 2);

        byte[] oldTele = new byte[58];
        oldTele[0] = (byte) 0xAB; oldTele[1] = 1; oldTele[2] = 3; oldTele[17] = 0x78; oldTele[18] = 0x56; oldTele[19] = 0x34; oldTele[20] = 0x12;
        Telemetry t0 = Telemetry.parse(oldTele);
        check("telemetry: old firmware parses and is not live", t0 != null && !t0.live && t0.configCrc == 0x12345678L && t0.configured && t0.configFromImage);
        byte[] newTele = oldTele.clone();
        newTele[2] = 1 | 0x04 | 0x08 | 0x10 | 0x20;
        newTele[21] = (byte) 0xEF; newTele[22] = (byte) 0xBE; newTele[23] = (byte) 0xAD; newTele[24] = (byte) 0xDE; newTele[25] = 1;
        Telemetry t1 = Telemetry.parse(newTele);
        check("telemetry: live firmware flags and persisted CRC", t1 != null && t1.live && t1.fromSaved && t1.unsaved && t1.identityDiffers && t1.persistedCrc == 0xDEADBEEFL);
        byte[] noProto = newTele.clone();
        noProto[25] = 0;
        check("telemetry: live bit without protocol byte is not trusted", !Telemetry.parse(noProto).live);
        byte[] adcT = oldTele.clone();
        adcT[3] = 0x05; adcT[4] = 0x01; adcT[5] = 0x34; adcT[6] = 0x12;
        Telemetry t2 = Telemetry.parse(adcT);
        check("telemetry: button mask and ADC decode", t2.buttonMask == 0x105 && t2.adc[0] == 0x1234);

        byte[] st = new byte[58];
        st[0] = (byte) 0xAC; st[1] = 1; st[2] = 1; st[3] = 2; st[4] = 1; st[8] = 2; st[12] = 5; st[13] = 7;
        for (int i = 0; i < 32; i++) st[16 + i] = (byte) i;
        LiveProtocol.Status ps = LiveProtocol.parseStatus(st);
        check("live status parses", ps != null && ps.result == 1 && ps.lastCmd == 2 && ps.activeCrc == 1 && ps.persistedCrc == 2 && ps.readIndex == 7
                && ps.has(LiveProtocol.FLAG_UNSAVED) && ps.has(LiveProtocol.FLAG_FROM_SAVED) && !ps.has(LiveProtocol.FLAG_PENDING) && ps.chunk[31] == 31);
        check("live status rejects other reports", LiveProtocol.parseStatus(new byte[58]) == null);

        ConfigBlock viaBytes = new ConfigBlock();
        viaBytes.loadBytes(live.toBytes());
        check("config can be loaded from a block read off the device", viaBytes.isValid() && viaBytes.get("led_mode") == 2 && viaBytes.get("button_map", 3) == 5);

        // ---- profiles (same JSON the desktop app writes) ----
        Map<String, Object> prof = live.toProfile();
        String json = Json.write(prof);
        ConfigBlock fromJson = new ConfigBlock();
        fromJson.loadProfile(Json.parseObject(json));
        check("profile JSON round-trips", Arrays.equals(fromJson.toBytes(), live.toBytes()));
        String desktop = "{\n  \"left_dead\": 21,\n  \"product\": \"Pad \\\"X\\\"\",\n  \"button_map\": [3, 2, 1, 4, 15, 16, 17, 18, 5, 6, 11, 12, 10, 13, 9, 0]\n}";
        ConfigBlock fromDesktop = new ConfigBlock();
        fromDesktop.loadProfile(Json.parseObject(desktop));
        check("a profile in the desktop app's format loads", fromDesktop.get("left_dead") == 21 && fromDesktop.get("button_map", 0) == 3 && fromDesktop.getString("product").equals("Pad \"X\"") && fromDesktop.isValid());
        boolean threw = false;
        try { Json.parseObject("not json"); } catch (IllegalArgumentException e) { threw = true; }
        check("garbage is not a profile", threw);

        Settings s = new Settings(new ConfigBlock());
        s.swapButtons("A", "B");
        check("quick change: swap A and B", s.output(0) == 3 && s.output(1) == 2 && s.remappedCount() == 2);
        s.cfg.resetField("button_map");
        check("reset buttons", s.remappedCount() == 0);

        // ---- stock firmware import ----
        if (stock != null) {
            check("stock image is recognised", FirmwareImage.isOfficialStock(stock));
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(bo)) {
                z.putNextEntry(new ZipEntry("assets/readme.txt")); z.write(new byte[100]); z.closeEntry();
                z.putNextEntry(new ZipEntry("assets/other.bin")); z.write(new byte[28108]); z.closeEntry();
                z.putNextEntry(new ZipEntry("assets/02.70.bin")); z.write(stock); z.closeEntry();
            }
            check("stock image is found inside an apk", StockFirmware.extract(new ByteArrayInputStream(bo.toByteArray()), "razer.apk").data != null);
            check("a raw .bin imports", StockFirmware.extract(new ByteArrayInputStream(stock), "x.bin").data != null);
            check("another file is refused", StockFirmware.extract(new ByteArrayInputStream(image), "x.bin").data == null
                    && StockFirmware.extract(new ByteArrayInputStream(new byte[50000]), "x.apk").data == null);
            File dir = Files.createTempDirectory("kishids").toFile();
            StockFirmware sf = new StockFirmware(dir);
            check("stock is not stored yet", !sf.has());
            check("stock is stored", sf.importStream(new ByteArrayInputStream(stock), "a.bin") == null && sf.has() && Arrays.equals(sf.loadStored(), stock));
            check("stock image validates for flashing", FirmwareImage.validate(stock, false).isEmpty());
        }

        // ---- calibration wizard ----
        ConfigBlock cc = new ConfigBlock();
        CalibrationWizard w = new CalibrationWizard(cc);
        w.start(0);
        long now = 0;
        int[] rest = { 1905, 2000, 1806, 1971, 2036, 1905 };
        for (int i = 0; i < 40; i++) { now += 50; w.update(tele(rest), now); }
        check("calibration: centre captured and range step reached", w.step == CalibrationWizard.Step.RANGE && Math.abs(w.center[0] - 1905) <= 1);
        w.finish();
        check("calibration: incomplete range is refused", w.step == CalibrationWizard.Step.RANGE && !w.message.isEmpty());
        int[] lo = { 566, 558, 542, 594, 718, 579 }, hi = { 3264, 3317, 1806, 3360, 3395, 1905 };
        now += 50; w.update(tele(lo), now);
        now += 50; w.update(tele(hi), now);
        now += 50; w.update(tele(rest), now);
        w.finish();
        check("calibration: full range reaches review", w.step == CalibrationWizard.Step.REVIEW);
        w.save();
        check("calibration: values written", cc.get("calib_mode") == 1 && cc.get("cal_scenter", 0) == 1905 && cc.get("cal_smax", 2) > 3300 && cc.get("cal_smin", 2) < 760 && cc.isValid());

        // ---- DFU against a fake bootloader ----
        testDfu(stock != null ? stock : image, false, false);
        testDfu(stock != null ? stock : image, true, false);
        testDfu(stock != null ? stock : image, false, true);

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static Telemetry tele(int[] adc) {
        return new Telemetry(true, true, 0, adc.clone(), 0, true, false, false, false, 0);
    }

    /** A bootloader as seen on the wire: sticky error state at first, DNBUSY after each block, and either a reset or a status 7 at manifest. */
    static final class FakeBootloader implements DfuFlasher.Pipe {
        int state = 10, status = 10, nextBlock;
        boolean busyOnce;
        final boolean reject, resetAtManifest;
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        int manifestPolls;
        boolean gone;

        FakeBootloader(boolean reject, boolean resetAtManifest) { this.reject = reject; this.resetAtManifest = resetAtManifest; }

        @Override public int control(int type, int req, int value, int index, byte[] buf, int len, int timeout) {
            if (gone) return -1;
            if (type == 0xA1 && req == 3) {   // GET_STATUS
                if (state == 3) { state = 4; busyOnce = true; }
                else if (state == 4 && busyOnce) { busyOnce = false; state = 5; }   // first poll says busy, the next says idle
                else if (state == 6) {
                    manifestPolls++;
                    if (reject) { state = 10; status = 7; }
                    else if (resetAtManifest && manifestPolls >= 2) { gone = true; return -1; }
                    else if (manifestPolls >= 2) { state = 2; }
                }
                // report the state the device is in after the transition: busy comes back as 4 once
                int shown = state;
                buf[0] = (byte) status; buf[1] = 1; buf[2] = 0; buf[3] = 0; buf[4] = (byte) shown; buf[5] = 0;
                return 6;
            }
            if (type == 0x21 && req == 4) { state = 2; status = 0; return 0; }   // CLRSTATUS
            if (type == 0x21 && req == 6) { state = 2; return 0; }              // ABORT
            if (type == 0x21 && req == 1) {                                     // DNLOAD
                if (state != 2 && state != 5) return -1;
                if (len == 0) { state = 6; return 0; }
                if (value != nextBlock) return -1;
                nextBlock++;
                received.write(buf, 0, len);
                state = 3;
                return len;
            }
            return -1;
        }
    }

    static void testDfu(byte[] img, boolean reject, boolean reset) {
        FakeBootloader bl = new FakeBootloader(reject, reset);
        double[] last = { 0 };
        StringBuilder log = new StringBuilder();
        DfuFlasher.Result r = DfuFlasher.flash(bl, img, new DfuFlasher.Listener() {
            @Override public void progress(double f) { last[0] = f; }
            @Override public void log(String l) { log.append(l).append('\n'); }
        });
        String what = reject ? "rejected image (status 7)" : reset ? "bootloader resets at manifest" : "bootloader goes idle after manifest";
        if (reject) check("dfu: " + what + " is reported as a failure", !r.success && r.message.contains("rejected"));
        else check("dfu: " + what + " is a success", r.success);
        check("dfu: " + what + " - every byte arrived in order", Arrays.equals(bl.received.toByteArray(), img));
        if (!reject) check("dfu: progress reached the end", last[0] > 0.9);
        FakeBootloader bl2 = new FakeBootloader(false, false);
        check("dfu: an out-of-region image is never sent", !DfuFlasher.flash(bl2, new byte[500], new DfuFlasher.Listener() {
            @Override public void progress(double f) { }
            @Override public void log(String l) { }
        }).success && bl2.received.size() == 0);
    }
}
