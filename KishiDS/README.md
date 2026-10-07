# KishiDS (the app)

Windows app (.NET 9 WPF, no NuGet packages) that configures the KishiDS controller firmware live over USB and flashes it. See the
[repository README](../README.md) for what the project is and [docs/BUILDING.md](../docs/BUILDING.md) for the full build and flashing guide.

```powershell
dotnet publish -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o publish
.\publish\KishiDS.exe
```

The result, `publish\KishiDS.exe`, needs the .NET 9 Desktop Runtime. It embeds the firmware image (`firmware-research\ds4-firmware\kishi_ds4.bin`) and
`dfu-util-static.exe`, so rebuild the app after every firmware build. Settings autosave to `%LOCALAPPDATA%\KishiDS\`.

- **First run:** a setup guide opens (and can be replayed from Firmware > "Replay welcome tutorial"): welcome, save Razer's original firmware, switch the controller.
- **Razer's original firmware** can't be read back from the controller (the bootloader refuses uploads), so it comes from Razer's Android app (Razer Kishi 1.0.34
  or 1.0.66, an `.apk`) or the raw 28,108-byte `.bin`. Drag either onto the window, pick it with a file dialog, or let the app find it in Downloads, Desktop or
  Documents. It is verified by SHA-256 before it is kept in `%LOCALAPPDATA%\KishiDS\stock\`, and it is never part of this repository.
- **Self-test:** `KishiDS.exe --selftest out.txt` (offline, no controller needed).
- **Developer flags:** `--demo`, `--stress`, `--perf`, `--screenshot`, `--status`, `--live`, `--setup`, `--stockdir`, `--light`, `--dark`; described in
  [docs/ENGINEERING_NOTES.md](../docs/ENGINEERING_NOTES.md).
