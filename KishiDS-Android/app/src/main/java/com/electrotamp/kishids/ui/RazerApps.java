package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.electrotamp.kishids.app.AppModel;
import com.electrotamp.kishids.core.StockFirmware;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Razer's own Android app (Razer Kishi / Razer Gamepad) carries the original firmware inside its APK.  When it is installed on this phone
 * the image can be taken straight from the installed package, which is the phone's equivalent of the desktop app searching Downloads.
 */
final class RazerApps {
    private RazerApps() {}

    /** Paths of the installed Razer apps' APK files (empty when none is installed). */
    static List<String> installed(Activity a) {
        List<String> out = new ArrayList<>();
        PackageManager pm = a.getPackageManager();
        for (String pkg : StockFirmware.RAZER_PACKAGES) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (ai.sourceDir != null) out.add(ai.sourceDir);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) { /* not installed or not visible */ }
        }
        return out;
    }

    interface Done { void finished(String errorOrNull, boolean found); }

    /** Try each installed Razer app until one yields the original image.  Runs the scan off the main thread. */
    static void importFromInstalled(Activity a, AppModel m, Done done) {
        final List<String> paths = installed(a);
        new Thread(() -> {
            String error = null;
            boolean ok = false;
            for (String p : paths) {
                try (InputStream in = new FileInputStream(p)) {
                    String e = m.importStock(in, p.endsWith(".apk") ? "base.apk" : p);
                    if (e == null) { ok = true; break; }
                    error = e;
                } catch (IOException e) { error = "Could not read the Razer app: " + e.getMessage(); }
            }
            final boolean fOk = ok;
            final String fErr = error;
            new Handler(Looper.getMainLooper()).post(() -> done.finished(fOk ? null : fErr, !paths.isEmpty()));
        }, "kishi-find-stock").start();
    }
}
