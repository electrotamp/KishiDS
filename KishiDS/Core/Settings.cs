using System.Collections.ObjectModel;
using System.Globalization;
using System.Runtime.CompilerServices;

namespace KishiDS.Core;

/// <summary>One row of the button-mapping table: a physical Kishi button and the DS4 output it triggers.</summary>
public sealed class ButtonRow : ObservableObject
{
    private readonly ConfigBlock _cfg;
    private bool _pressed;

    public ButtonRow(ConfigBlock cfg, int index, string name, int defaultOutput)
    {
        _cfg = cfg;
        Index = index;
        Name = name;
        DefaultOutput = defaultOutput;
    }

    /// <summary>Button bit / mapping slot: 0..15 in button_map, 16..19 in button_map2.</summary>
    public int Index { get; }
    public string Name { get; }
    public int DefaultOutput { get; }
    private string Field => Index < 16 ? "button_map" : "button_map2";
    private int Slot => Index < 16 ? Index : Index - 16;
    private static readonly string[] Pretty = ConfigLayout.Outputs.Select(o => o switch
    {
        "None" => "Disabled",
        "DpadUp" => "D-pad Up",
        "DpadDown" => "D-pad Down",
        "DpadLeft" => "D-pad Left",
        "DpadRight" => "D-pad Right",
        "PS" => "PS button",
        "Touchpad" => "Touchpad click",
        "TouchLeft" => "Touchpad left click",
        "TouchRight" => "Touchpad right click",
        _ => o,
    }).ToArray();

    public IReadOnlyList<string> Options => Pretty;

    public int Output
    {
        get => _cfg.Get(Field, Slot);
        set { _cfg.Set(Field, value, Slot); Raise(); Raise(nameof(IsDefault)); }
    }

    public bool IsDefault => Output == DefaultOutput;

    /// <summary>True while the physical button is held (from firmware telemetry).</summary>
    public bool Pressed { get => _pressed; set => Set(ref _pressed, value); }

    public void Refresh() { Raise(nameof(Output)); Raise(nameof(IsDefault)); }
}

/// <summary>Typed, bindable view of the config block.  Property names map to schema fields (LeftDead -> left_dead).</summary>
public sealed class Settings : ObservableObject
{
    public ConfigBlock Cfg { get; }
    public ObservableCollection<ButtonRow> Buttons { get; } = new();

    /// <summary>True when the connected controller is a Kishi V2 Pro (telemetry flag): its button names and extras.</summary>
    public bool IsV2Pro { get; private set; }

    public void SetBoard(bool v2Pro)
    {
        if (v2Pro == IsV2Pro && Buttons.Count > 0) return;
        IsV2Pro = v2Pro;
        Buttons.Clear();
        var names = v2Pro ? ConfigLayout.V2ProButtons : ConfigLayout.KishiButtons;
        var v1Defaults = ConfigBlock.Field("button_map").Default;
        for (int i = 0; i < names.Length; i++)
            Buttons.Add(new ButtonRow(Cfg, i, names[i], v2Pro ? ConfigLayout.V2ProButtonMap[i] : v1Defaults[i]));
        Raise(nameof(IsV2Pro));
        Raise(nameof(RemappedCount));
    }

    /// <summary>Every button back to this board's default mapping.</summary>
    public void ResetButtons()
    {
        foreach (var b in Buttons) b.Output = b.DefaultOutput;
    }

    public ButtonRow? Button(string name) => Buttons.FirstOrDefault(b => b.Name == name);

    public Settings(ConfigBlock cfg)
    {
        Cfg = cfg;
        SetBoard(false);
        cfg.Changed += _ =>
        {
            Raise(string.Empty);                  // every property may have changed
            foreach (var b in Buttons) b.Refresh();
        };
    }

    private static string Snake(string pascal)
    {
        var sb = new System.Text.StringBuilder();
        for (int i = 0; i < pascal.Length; i++)
        {
            char c = pascal[i];
            if (char.IsUpper(c) && i > 0) sb.Append('_');
            sb.Append(char.ToLowerInvariant(c));
        }
        return sb.ToString();
    }

    private int Num([CallerMemberName] string? p = null) => Cfg.Get(Snake(p!));
    private void Num(int v, [CallerMemberName] string? p = null) => Cfg.Set(Snake(p!), v);
    private bool Bit(int bit, string field) => Cfg.GetBit(field, bit);
    private void Bit(int bit, string field, bool on) => Cfg.SetBit(field, bit, on);

    public int RemappedCount => Buttons.Count(b => !b.IsDefault);
    public string LedModeName => ConfigLayout.LedModes[Math.Clamp(LedMode, 0, ConfigLayout.LedModes.Length - 1)];

    // ---- Sticks ----
    public int LeftDead { get => Num(); set => Num(value); }
    public int RightDead { get => Num(); set => Num(value); }
    public int LeftOuter { get => Num(); set => Num(value); }
    public int RightOuter { get => Num(); set => Num(value); }
    public int LeftCurve { get => Num(); set => Num(value); }
    public int RightCurve { get => Num(); set => Num(value); }
    public bool InvertLx { get => Bit(0, "stick_flags"); set => Bit(0, "stick_flags", value); }
    public bool InvertLy { get => Bit(1, "stick_flags"); set => Bit(1, "stick_flags", value); }
    public bool InvertRx { get => Bit(2, "stick_flags"); set => Bit(2, "stick_flags", value); }
    public bool InvertRy { get => Bit(3, "stick_flags"); set => Bit(3, "stick_flags", value); }
    public bool SwapSticks { get => Bit(4, "stick_flags"); set => Bit(4, "stick_flags", value); }

    // ---- Triggers ----
    public int TrigThresh { get => Num(); set => Num(value); }
    public int L2Dead { get => Num(); set => Num(value); }
    public int R2Dead { get => Num(); set => Num(value); }
    public int L2Outer { get => Num(); set => Num(value); }
    public int R2Outer { get => Num(); set => Num(value); }
    public bool SwapTriggers { get => Bit(0, "trig_flags"); set => Bit(0, "trig_flags", value); }
    public bool DigitalTriggers { get => Bit(1, "trig_flags"); set => Bit(1, "trig_flags", value); }

    // ---- D-pad ----
    public int DpadMode { get => Num(); set => Num(value); }
    public int SocdMode { get => Num(); set => Num(value); }

    // ---- LED ----
    public int LedMode { get => Num(); set => Num(value); }
    public int LedBrightness { get => Num(); set => Num(value); }
    public int LedBreath { get => Num(); set => Num(value); }
    public double LedBreathSeconds { get => LedBreath / 10.0; set => LedBreath = (int)Math.Round(value * 10); }
    public bool LedBreathVisible => LedMode == 2;

    // ---- RGB LED colour (Kishi V2 Pro; the V1's LED is blue only) ----
    public int LedRed { get => Cfg.Get("led_rgb", 0); set => Cfg.Set("led_rgb", value, 0); }
    public int LedGreen { get => Cfg.Get("led_rgb", 1); set => Cfg.Set("led_rgb", value, 1); }
    public int LedBlue { get => Cfg.Get("led_rgb", 2); set => Cfg.Set("led_rgb", value, 2); }
    /// <summary>True: the colour stays even when a game or app sets the DS4 lightbar.</summary>
    public bool LedFixed { get => Cfg.Get("led_fixed") == 1; set => Cfg.Set("led_fixed", value ? 1 : 0); }
    /// <summary>The colour the firmware shows: 0, 0, 0 in the config means its default, blue.</summary>
    public System.Windows.Media.Color LedColor => LedRed == 0 && LedGreen == 0 && LedBlue == 0
        ? System.Windows.Media.Color.FromRgb(0, 0, 255)
        : System.Windows.Media.Color.FromRgb((byte)LedRed, (byte)LedGreen, (byte)LedBlue);
    public System.Windows.Media.Brush LedColorBrush => new System.Windows.Media.SolidColorBrush(LedColor);

    public void SetLedColor(byte r, byte g, byte b) { LedRed = r; LedGreen = g; LedBlue = b; }

    // ---- Rumble (Kishi V2 Pro; the V1 has no actuators) ----
    public int RumbleLevel { get => Num(); set => Num(value); }
    /// <summary>The strength the firmware uses: rumble_level 0 means its default, 80 %.</summary>
    public int RumblePercent { get => RumbleLevel == 0 ? 80 : RumbleLevel; set => RumbleLevel = Math.Clamp(value, 1, 100); }

    // ---- USB identity ----
    public string Manufacturer => Cfg.GetString("manufacturer");
    public string Product { get => Cfg.GetString("product"); set => Cfg.SetString("product", value); }
    public int PollMs { get => Num(); set => Num(value); }
    public int PollHz => 1000 / Math.Max(1, PollMs);

    // VID/PID are locked in the firmware (they must stay a DualShock 4); shown read-only on the Overview.
    public string VidText => Cfg.Get("vid").ToString("X4");
    public string PidText => Cfg.Get("pid").ToString("X4");
    public string ModeText => VidText == "054C" && PidText == "05C4" ? "DualShock 4 (DS4)" : "Custom device";

    // ---- Calibration ----
    public bool UseCustomCalibration { get => Cfg.Get("calib_mode") == 1; set => Cfg.Set("calib_mode", value ? 1 : 0); }
}
