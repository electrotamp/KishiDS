package com.electrotamp.kishids.ui;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.electrotamp.kishids.R;
import com.electrotamp.kishids.app.AppModel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The single activity: a navigation rail (landscape / tablets) or a bottom bar (portrait) around the page being shown, a floating
 * "apply / restart" bar, and the first-run setup guide.  Everything is refreshed once per frame from the {@link AppModel}.
 */
public final class MainActivity extends Activity implements Host {
    private static final int REQ_IMPORT = 1, REQ_LOAD = 2, REQ_SAVE = 3;

    private static AppModel model;
    private static String lastPage = Host.OVERVIEW;
    private static int guideStep;
    private static boolean guideChecked;

    private AppModel m;
    private boolean wide;
    private FrameLayout root, content;
    private Page current;
    private String currentId = Host.OVERVIEW;
    private final Map<String, Page> pages = new HashMap<>();
    private final Map<String, NavItem> navItems = new HashMap<>();
    private SetupGuide guide;
    private LinearLayout floating;
    private TextView floatMessage;
    private Ui.Button floatPrimary, floatSecondary;
    private View statusDot, sideDot;
    private TextView statusText, sideTitle, sideDetail;
    private Widgets.IconButton back, themeButton;
    private boolean running;
    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long nanos) {
            if (!running) return;
            tick();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        if (in.hasExtra("light")) Theme.apply(!in.getBooleanExtra("light", false)); else Theme.load(this);
        Ui.init(this);
        if (model == null) model = new AppModel(getApplicationContext(), in.getBooleanExtra("demo", false));
        m = model;
        m.onShowFirmware = () -> go(Host.FIRMWARE);
        m.confirmWithoutOriginal = proceed -> Ui.confirm(this, "Original firmware isn't saved",
                "Switching replaces Razer's firmware, and without a saved copy you won't be able to go back to it from this app.",
                "Save it first", () -> showSetup(2), "Continue anyway", proceed);
        wide = Ui.wide(this);
        if (in.hasExtra("page")) lastPage = in.getStringExtra("page");
        if (in.hasExtra("guide")) guideStep = in.getIntExtra("guide", 1);
        if (!guideChecked) { guideChecked = true; if (!m.demo && !m.setupDone()) guideStep = 1; }
        if (in.hasExtra("phase")) demoPhase(in.getStringExtra("phase"));
        buildUi();
        go(lastPage);
        if (guideStep > 0) showSetup(guideStep);
        handleUsbIntent(in);
    }

    private void demoPhase(String p) {
        switch (p) {
            case "waiting": m.showDemoPhase(AppModel.Phase.WAITING_FOR_BOOTLOADER, 0, "Put the Kishi in update mode", "Detach it from the phone, hold Y + B + Right Function, then plug it back in while holding."); break;
            case "ready": m.showDemoPhase(AppModel.Phase.READY, 0, "Bootloader detected", "Ready to write your settings to the controller."); break;
            case "flashing": m.showDemoPhase(AppModel.Phase.FLASHING, 0.62, "Writing firmware", "Keep the controller connected and this screen open."); break;
            case "done": m.showDemoPhase(AppModel.Phase.DONE, 1, "Applied and verified", "The controller reports it is running exactly these settings."); break;
            case "failed": m.showDemoPhase(AppModel.Phase.FAILED, 0.4, "Flashing didn't complete", "The bootloader rejected the image (verification failed). Nothing was changed."); break;
            default: break;
        }
    }

    @Override protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        handleUsbIntent(in);
    }

    private void handleUsbIntent(Intent in) {
        // The system may hand us an attach event (e.g. after the user picked this app for the device); the link rescans on its own.
        if (in != null && "android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(in.getAction())) m.link.requestAccess();
    }

    @Override protected void onStart() {
        super.onStart();
        m.onForeground();
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && Ui.compact(this)) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | (Theme.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    @Override protected void onPause() {
        running = false;
        super.onPause();
    }

    @Override protected void onStop() {
        m.onBackground();
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (guide != null && guide.getParent() != null) {
            if (guide.step() > 1) guide.setStep(guide.step() - 1); else hideGuide();
            return;
        }
        if (isSub(currentId) && !wide) { go(Host.MORE); return; }
        if (!currentId.equals(Host.OVERVIEW)) { go(Host.OVERVIEW); return; }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- layout

    private static boolean isSub(String id) {
        return id.equals(Host.DPAD) || id.equals(Host.LIGHTING) || id.equals(Host.CALIBRATION) || id.equals(Host.IDENTITY) || id.equals(Host.FIRMWARE);
    }

    private void buildUi() {
        Window w = getWindow();
        w.setBackgroundDrawable(new Widgets.StageBackground());
        w.setStatusBarColor(Theme.bg);
        w.setNavigationBarColor(Theme.dark ? Color.parseColor("#0B0E17") : Color.parseColor("#E8EDFA"));
        int flags = w.getDecorView().getSystemUiVisibility();
        if (!Theme.dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (android.os.Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        else flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (android.os.Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        w.getDecorView().setSystemUiVisibility(flags);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Ui.compact(this)) {
            // Held in the Kishi the screen is only a few centimetres tall: hide the status and navigation bars (swipe from the edge to bring them back).
            w.getDecorView().setSystemUiVisibility(w.getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        root = new FrameLayout(this);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        content = new FrameLayout(this);

        if (wide) {
            shell.addView(buildSidebar(), Ui.lp(Ui.dp(Ui.compact(this) ? 196 : 224), Ui.MATCH));
            shell.addView(content, new LinearLayout.LayoutParams(0, Ui.MATCH, 1));
        } else {
            shell.addView(buildTopBar(), Ui.lp(Ui.MATCH, Ui.dp(56)));
            shell.addView(content, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
            shell.addView(buildBottomBar(), Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        root.addView(shell, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        // floating "apply / restart" bar
        floating = Ui.hbox(this);
        floating.setBackground(Ui.box(Theme.cardHi, 18, Theme.strokeHi, 1));
        floating.setElevation(Ui.dpf(8));
        Ui.pad(floating, 16, 10, 10, 10);
        floatMessage = Ui.tv(this, "", 13.5f, Theme.text);
        floating.addView(floatMessage, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        floatSecondary = Ui.ghost(this, "Reset all", () -> m.config.resetAll());
        floatPrimary = Ui.primary(this, "Apply", () -> m.barPrimary());
        floating.addView(floatSecondary);
        floating.addView(floatPrimary, Ui.lp(Ui.WRAP, Ui.WRAP, 6, 0, 0, 0));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM);
        fp.setMargins(Ui.dp(12), 0, Ui.dp(12), Ui.dp(wide ? 14 : 10));
        floating.setVisibility(View.GONE);
        root.addView(floating, fp);

        setContentView(root);
    }

    private View buildTopBar() {
        LinearLayout bar = Ui.hbox(this);
        Ui.pad(bar, 6, 0, 6, 0);
        back = new Widgets.IconButton(this, Icons.ARROW, Theme.text, () -> go(Host.MORE));
        back.setRotation(180);
        back.setVisibility(View.GONE);
        bar.addView(back);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        bar.addView(logo, Ui.lp(Ui.dp(30), Ui.dp(30), 8, 0, 8, 0));
        TextView brand = Ui.tv(this, "KISHI", 17, Theme.text);
        brand.setTypeface(Ui.bold);
        brand.setLetterSpacing(0.04f);
        TextView ds = Ui.tv(this, "DS", 17, Theme.brand);
        ds.setTypeface(Ui.bold);
        ds.setLetterSpacing(0.04f);
        bar.addView(brand);
        bar.addView(ds);
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

        LinearLayout pill = Ui.hbox(this);
        pill.setBackground(Ui.box(Theme.alpha(Theme.cardHi, 220), 16, Theme.stroke, 1));
        Ui.pad(pill, 11, 6, 13, 6);
        statusDot = new View(this);
        pill.addView(statusDot, Ui.lp(Ui.dp(9), Ui.dp(9), 0, 0, 8, 0));
        statusText = Ui.tv(this, "", 12.5f, Theme.text);
        statusText.setSingleLine(true);
        pill.addView(statusText);
        pill.setClickable(true);
        pill.setOnClickListener(v -> go(Host.OVERVIEW));
        bar.addView(pill);

        themeButton = new Widgets.IconButton(this, Theme.dark ? Icons.SUN : Icons.MOON, Theme.textDim, this::toggleTheme);
        bar.addView(themeButton, Ui.lp(Ui.WRAP, Ui.WRAP, 2, 0, 0, 0));
        return bar;
    }

    private View buildBottomBar() {
        LinearLayout bar = Ui.hbox(this);
        bar.setBackground(Ui.box(Theme.dark ? Color.parseColor("#F00B0E17") : Color.parseColor("#F2F5FE"), 0, Theme.stroke, 0));
        bar.setGravity(Gravity.CENTER);
        Ui.pad(bar, 6, 6, 6, 6);
        String[][] items = { { Host.OVERVIEW, Icons.HOME, "Overview" }, { Host.BUTTONS, Icons.BUTTONS, "Buttons" }, { Host.STICKS, Icons.STICK, "Sticks" },
                { Host.TRIGGERS, Icons.TRIGGERS, "Triggers" }, { Host.MORE, Icons.MORE, "More" } };
        for (String[] it : items) {
            NavItem n = new NavItem(this, it[1], it[2], false);
            n.setOnClickListener(v -> go(it[0]));
            navItems.put(it[0], n);
            bar.addView(n, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        }
        return bar;
    }

    private View buildSidebar() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Theme.sidebar);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = Ui.vbox(this);
        Ui.pad(col, 12, 16, 12, 16);

        LinearLayout brandRow = Ui.hbox(this);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        brandRow.addView(logo, Ui.lp(Ui.dp(38), Ui.dp(38), 6, 0, 10, 0));
        LinearLayout words = Ui.vbox(this);
        LinearLayout nameRow = Ui.hbox(this);
        TextView b1 = Ui.tv(this, "KISHI", 17, Theme.text);
        b1.setTypeface(Ui.bold);
        TextView b2 = Ui.tv(this, "DS", 17, Theme.brand);
        b2.setTypeface(Ui.bold);
        nameRow.addView(b1);
        nameRow.addView(b2);
        words.addView(nameRow);
        words.addView(Ui.tv(this, "Controller Firmware", 11.5f, Theme.textDim), Ui.lp(Ui.WRAP, Ui.WRAP, 0, 1, 0, 0));
        brandRow.addView(words);
        col.addView(brandRow, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));

        addRailItem(col, Host.OVERVIEW, Icons.HOME);
        col.addView(Ui.eyebrow(this, "Configure"), Ui.lp(Ui.WRAP, Ui.WRAP, 10, 14, 0, 6));
        addRailItem(col, Host.BUTTONS, Icons.BUTTONS);
        addRailItem(col, Host.STICKS, Icons.STICK);
        addRailItem(col, Host.TRIGGERS, Icons.TRIGGERS);
        addRailItem(col, Host.DPAD, Icons.DPAD);
        addRailItem(col, Host.LIGHTING, Icons.SUN);
        col.addView(Ui.eyebrow(this, "Device"), Ui.lp(Ui.WRAP, Ui.WRAP, 10, 14, 0, 6));
        addRailItem(col, Host.CALIBRATION, Icons.PULSE);
        addRailItem(col, Host.IDENTITY, Icons.PERSON);
        addRailItem(col, Host.FIRMWARE, Icons.DOWNLOAD);

        LinearLayout status = Ui.vbox(this);
        status.setBackground(Ui.box(Theme.card, 16, Theme.stroke, 1));
        Ui.pad(status, 14, 12, 14, 12);
        LinearLayout sr = Ui.hbox(this);
        sideDot = new View(this);
        sr.addView(sideDot, Ui.lp(Ui.dp(9), Ui.dp(9), 0, 0, 8, 0));
        sideTitle = Ui.medium(this, "", 13.5f, Theme.text);
        sr.addView(sideTitle);
        status.addView(sr);
        sideDetail = Ui.caption(this, "");
        sideDetail.setTextSize(12);
        status.addView(sideDetail, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 4, 0, 0));
        col.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 18, 0, 8));

        themeButton = new Widgets.IconButton(this, Theme.dark ? Icons.SUN : Icons.MOON, Theme.textDim, this::toggleTheme);
        col.addView(themeButton, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, 0, 0));
        sv.addView(col, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        return sv;
    }

    private void addRailItem(LinearLayout col, String id, String icon) {
        NavItem n = new NavItem(this, icon, id, true);
        n.setOnClickListener(v -> go(id));
        navItems.put(id, n);
        col.addView(n, Ui.lp(Ui.MATCH, Ui.dp(Ui.compact(this) ? 40 : 46), 0, 1, 0, 1));
    }

    /** A navigation entry: a side-rail row (icon + label) or a bottom-bar tab (icon in a pill, label under it). */
    private static final class NavItem extends LinearLayout {
        private final Widgets.IconView icon;
        private final TextView label;
        private final String iconName;
        private final boolean rail;
        private final LinearLayout pill;

        NavItem(Activity a, String iconName, String text, boolean rail) {
            super(a);
            this.iconName = iconName;
            this.rail = rail;
            setClickable(true);
            icon = new Widgets.IconView(a, iconName, rail ? 21 : 23, Theme.textDim);
            label = Ui.medium(a, text, rail ? 14.5f : 11.5f, Theme.textDim);
            if (rail) {
                setOrientation(HORIZONTAL);
                setGravity(Gravity.CENTER_VERTICAL);
                Ui.pad(this, 14, 0, 12, 0);
                addView(icon, Ui.lp(Ui.dp(21), Ui.dp(21), 0, 0, 14, 0));
                addView(label);
                pill = null;
            } else {
                setOrientation(VERTICAL);
                setGravity(Gravity.CENTER_HORIZONTAL);
                pill = new LinearLayout(a);
                pill.setGravity(Gravity.CENTER);
                pill.addView(icon);
                addView(pill, Ui.lp(Ui.dp(58), Ui.dp(30)));
                addView(label, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 3, 0, 0));
            }
            select(false);
        }

        void select(boolean on) {
            if (rail) {
                setBackground(Ui.ripple(Ui.box(on ? Theme.navSelBg : 0, 13, 0, 0), 13));
                icon.set(iconName, on ? Theme.navSelIcon : Theme.textDim);
                label.setTextColor(on ? Theme.navSelText : Theme.textDim);
                if (on) { Ui.pad(this, 14, 0, 12, 0); }
            } else {
                pill.setBackground(Ui.ripple(Ui.box(on ? Theme.navSelBg : 0, 15, 0, 0), 15));
                icon.set(iconName, on ? Theme.navSelIcon : Theme.textDim);
                label.setTextColor(on ? Theme.text : Theme.textDim);
            }
        }
    }

    // ---------------------------------------------------------------- navigation (Host)

    private Page makePage(String id) {
        switch (id) {
            case Host.BUTTONS: return new ButtonsPage(this, m, this);
            case Host.STICKS: return new SticksPage(this, m, this);
            case Host.TRIGGERS: return new TriggersPage(this, m, this);
            case Host.DPAD: return new DpadPage(this, m, this);
            case Host.LIGHTING: return new LightingPage(this, m, this);
            case Host.CALIBRATION: return new CalibrationPage(this, m, this);
            case Host.IDENTITY: return new IdentityPage(this, m, this);
            case Host.FIRMWARE: return new FirmwarePage(this, m, this);
            case Host.MORE: return new MorePage(this, m, this);
            default: return new OverviewPage(this, m, this);
        }
    }

    @Override public void go(String id) {
        if (wide && id.equals(Host.MORE)) id = Host.OVERVIEW;
        Page p = pages.get(id);
        if (p == null) { p = makePage(id); pages.put(id, p); }
        boolean changed = current != p;
        currentId = id;
        lastPage = id;
        current = p;
        if (changed) {
            content.removeAllViews();
            content.addView(p.scroll, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
            p.onShown();
        }
        String navId = wide ? id : (isSub(id) ? Host.MORE : id);
        for (Map.Entry<String, NavItem> e : navItems.entrySet()) e.getValue().select(e.getKey().equals(navId));
        if (back != null) back.setVisibility(!wide && isSub(id) ? View.VISIBLE : View.GONE);
        p.update();
    }

    @Override public void showSetup(int step) {
        guideStep = step;
        if (guide == null) {
            guide = new SetupGuide(this, m, this);
            guide.setOnFinished(this::hideGuide);
        }
        guide.setStep(step);
        if (guide.getParent() == null) root.addView(guide, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
    }

    private void hideGuide() {
        guideStep = 0;
        if (guide != null && guide.getParent() != null) root.removeView(guide);
    }

    @Override public void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    @Override public void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (RuntimeException e) { toast("Couldn't open a browser."); }
    }

    @Override public void toggleTheme() {
        Theme.save(this, !Theme.dark);
        recreate();
    }

    // ---------------------------------------------------------------- files

    private void pick(String action, int req, String type, String name) {
        Intent i = new Intent(action);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        if (name != null) i.putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(i, req); }
        catch (RuntimeException e) { toast("No file picker is available on this phone."); }
    }

    @Override public void importOriginalFirmware() { pick(Intent.ACTION_OPEN_DOCUMENT, REQ_IMPORT, "*/*", null); }

    @Override public void loadProfile() { pick(Intent.ACTION_OPEN_DOCUMENT, REQ_LOAD, "*/*", null); }

    @Override public void saveProfile() { pick(Intent.ACTION_CREATE_DOCUMENT, REQ_SAVE, "application/json", "KishiDS-profile.json"); }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (req == REQ_SAVE) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) { m.saveProfile(out); }
                toast("Profile saved.");
            } else if (req == REQ_LOAD) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    String err = m.loadProfile(in);
                    if (err != null) Ui.message(this, "Couldn't load that profile", err, "OK", null, null); else toast("Profile loaded.");
                }
            } else if (req == REQ_IMPORT) {
                final String name = displayName(uri);
                new Thread(() -> {
                    String err;
                    try (InputStream in = getContentResolver().openInputStream(uri)) { err = m.importStock(in, name); }
                    catch (IOException | RuntimeException e) { err = "Could not read that file: " + e.getMessage(); }
                    final String fErr = err;
                    runOnUiThread(() -> {
                        if (fErr != null) Ui.message(this, "Couldn't use that file", fErr, "OK", null, null);
                        else toast("Original firmware saved.");
                    });
                }, "kishi-import").start();
            }
        } catch (IOException | RuntimeException e) {
            Ui.message(this, "Something went wrong", String.valueOf(e.getMessage()), "OK", null, null);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException e) { /* fall through */ }
        return uri.getLastPathSegment();
    }

    @Override public void importFromInstalledRazerApp() {
        toast("Looking at Razer's app…");
        RazerApps.importFromInstalled(this, m, (err, found) -> {
            if (err == null) Ui.message(this, "Original firmware saved", "KishiDS took the original Kishi firmware (v2.70) from Razer's app on this phone and kept a verified copy.", "OK", null, null);
            else if (!found) Ui.confirm(this, "Razer's app isn't installed", "Install Razer's Kishi app (version 1.0.34 or 1.0.66), or choose its .apk or the firmware .bin file yourself.",
                    "Choose a file…", this::importOriginalFirmware, "Cancel", null);
            else Ui.message(this, "Couldn't use Razer's app", err, "OK", null, null);
        });
    }

    // ---------------------------------------------------------------- per frame

    private void tick() {
        m.tick();
        if (guide != null && guide.getParent() != null) guide.update();
        if (current != null) current.update();
        Widgets.tickAll();
        updateChrome();
    }

    private String statusShort() {
        switch (m.device) {
            case CUSTOM: return "Kishi connected";
            case CUSTOM_LOCKED: return m.accessDenied ? "Allow access" : "Connecting…";
            case STOCK: return "Kishi · stock";
            case BOOTLOADER: return "Update mode";
            default: return "No controller";
        }
    }

    private int statusColor() {
        switch (m.device) {
            case CUSTOM: return Theme.good;
            case CUSTOM_LOCKED: case BOOTLOADER: return Theme.warn;
            case STOCK: return Theme.accent;
            default: return Theme.textFaint;
        }
    }

    private int lastDotColor;

    private void updateChrome() {
        int dotColor = statusColor();
        if (statusText != null) {
            statusText.setText(statusShort());
            if (dotColor != lastDotColor) statusDot.setBackground(Ui.box(dotColor, 5, 0, 0));
        }
        if (sideTitle != null) {
            sideTitle.setText(m.deviceTitle());
            sideDetail.setText(m.syncText());
            if (dotColor != lastDotColor) sideDot.setBackground(Ui.box(dotColor, 5, 0, 0));
        }
        lastDotColor = dotColor;

        // The floating bar: apply / restart, or a prompt to allow USB access.
        boolean access = m.device == AppModel.Device.CUSTOM_LOCKED && m.accessDenied;
        AppModel.Bar bar = m.bar();
        boolean show = (access || bar != AppModel.Bar.NONE) && (guide == null || guide.getParent() == null) && !currentId.equals(Host.FIRMWARE);
        if (show) {
            if (access) {
                floatMessage.setText("KishiDS needs your OK to use the controller's USB port");
                floatPrimary.setText("Allow access");
                floatPrimary.setOnClickListener(v -> m.allowAccess());
                floatSecondary.setVisibility(View.GONE);
            } else {
                floatMessage.setText(m.barMessage());
                floatPrimary.setText(m.barPrimaryText());
                floatPrimary.setOnClickListener(v -> m.barPrimary());
                floatSecondary.setVisibility(bar == AppModel.Bar.APPLY ? View.VISIBLE : View.GONE);
            }
            if (floating.getVisibility() != View.VISIBLE) {
                floating.setVisibility(View.VISIBLE);
                FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) floating.getLayoutParams();
                fp.bottomMargin = Ui.dp(wide ? 14 : 10) + (wide ? 0 : bottomBarHeight());
                floating.setLayoutParams(fp);
            }
        } else if (floating.getVisibility() != View.GONE) floating.setVisibility(View.GONE);

        if (m.busy()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private int bottomBarHeight() {
        NavItem n = navItems.get(Host.OVERVIEW);
        View bar = n == null ? null : (View) n.getParent();
        return bar == null ? Ui.dp(64) : bar.getHeight();
    }
}
