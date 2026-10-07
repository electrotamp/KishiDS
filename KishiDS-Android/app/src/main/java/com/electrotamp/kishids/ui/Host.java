package com.electrotamp.kishids.ui;

/** What the pages need from the activity: navigation, file pickers, the setup guide. */
public interface Host {
    String OVERVIEW = "Overview", BUTTONS = "Buttons", STICKS = "Sticks", TRIGGERS = "Triggers", DPAD = "D-pad", LIGHTING = "Lighting",
            CALIBRATION = "Calibration", IDENTITY = "Identity", FIRMWARE = "Firmware", MORE = "More";

    void go(String page);

    /** Ask for a file with the system picker; the result is delivered to the model by the activity. */
    void importOriginalFirmware();

    void loadProfile();

    void saveProfile();

    void showSetup(int step);

    /** Look for Razer's own app installed on this phone and import the firmware from it. */
    void importFromInstalledRazerApp();

    void toast(String message);

    void openUrl(String url);

    void toggleTheme();
}
