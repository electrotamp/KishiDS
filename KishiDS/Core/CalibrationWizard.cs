namespace KishiDS.Core;

public enum CalStep { Idle, Center, Range, Review }

/// <summary>
/// Walks the user through capturing each stick's centre and range and each trigger's rest/pressed values from the
/// firmware's raw ADC telemetry, then writes them into the config block (and switches to custom calibration).
/// ADC order (firmware): 0 RX, 1 RY, 2 R2, 3 LY, 4 LX, 5 L2.
/// </summary>
public sealed class CalibrationWizard : ObservableObject
{
    private static readonly string[] Names = { "Right stick X", "Right stick Y", "Right trigger", "Left stick Y", "Left stick X", "Left trigger" };
    private readonly ConfigBlock _cfg;
    private readonly double[] _sum = new double[6];
    private int _samples;
    private DateTime _centerStart;
    private CalStep _step;
    private string _message = "";

    public CalibrationWizard(ConfigBlock cfg)
    {
        _cfg = cfg;
        Reset();
    }

    public int[] Live { get; private set; } = new int[6];
    public int[] Min { get; private set; } = new int[6];
    public int[] Max { get; private set; } = new int[6];
    public int[] Center { get; private set; } = new int[6];
    public static string[] ChannelNames => Names;

    public CalStep Step
    {
        get => _step;
        private set
        {
            if (!Set(ref _step, value)) return;
            Raise(nameof(IsIdle)); Raise(nameof(IsCenter)); Raise(nameof(IsRange)); Raise(nameof(IsReview)); Raise(nameof(IsRunning));
            Raise(nameof(Title)); Raise(nameof(Instruction));
        }
    }

    public string Message { get => _message; private set => Set(ref _message, value); }
    public double CenterProgress { get; private set; }
    public bool IsIdle => Step == CalStep.Idle;
    public bool IsCenter => Step == CalStep.Center;
    public bool IsRange => Step == CalStep.Range;
    public bool IsReview => Step == CalStep.Review;
    public bool IsRunning => Step != CalStep.Idle;

    public string Title => Step switch
    {
        CalStep.Center => "Step 1 of 2: Centre",
        CalStep.Range => "Step 2 of 2: Range",
        CalStep.Review => "Review",
        _ => "Calibrate your controller",
    };

    public string Instruction => Step switch
    {
        CalStep.Center => "Let go of both sticks and triggers and leave the controller still. This takes a moment.",
        CalStep.Range => "Roll both sticks slowly around their full edge a few times, then squeeze both triggers all the way down and release.",
        CalStep.Review => "Here is what was measured. Save it to use these values instead of the factory ones.",
        _ => "Stick and trigger sensors vary between controllers. Calibrating teaches the firmware your exact centre and range, so full deflection reads 100% and rest reads 0.",
    };

    public void Reset()
    {
        Min = Enumerable.Repeat(4095, 6).ToArray();
        Max = new int[6];
        Center = new int[6];
        Array.Clear(_sum);
        _samples = 0;
        CenterProgress = 0;
        Message = "";
        Step = CalStep.Idle;
        Raise(nameof(Min)); Raise(nameof(Max)); Raise(nameof(CenterProgress));
    }

    public void Start()
    {
        Reset();
        _centerStart = DateTime.UtcNow;
        Step = CalStep.Center;
    }

    public void Cancel() => Reset();

    private ushort[]? _lastAdc;

    public void Update(Telemetry? t)
    {
        if (t is not { } tele || tele.Adc.Length < 6) return;
        bool running = Step is CalStep.Center or CalStep.Range;
        if (!running && _lastAdc is not null && tele.Adc.AsSpan().SequenceEqual(_lastAdc)) return;   // idle and nothing moved
        _lastAdc = tele.Adc;
        Live = tele.Adc.Select(v => (int)v).ToArray();
        if (Step == CalStep.Center)
        {
            double elapsed = (DateTime.UtcNow - _centerStart).TotalSeconds;
            for (int i = 0; i < 6; i++) _sum[i] += tele.Adc[i];
            _samples++;
            CenterProgress = Math.Min(1, elapsed / 1.5);
            if (elapsed >= 1.5 && _samples > 10)
            {
                for (int i = 0; i < 6; i++)
                {
                    Center[i] = (int)Math.Round(_sum[i] / _samples);
                    Min[i] = Max[i] = Center[i];
                }
                Step = CalStep.Range;
            }
        }
        else if (Step == CalStep.Range)
        {
            for (int i = 0; i < 6; i++)
            {
                Min[i] = Math.Min(Min[i], tele.Adc[i]);
                Max[i] = Math.Max(Max[i], tele.Adc[i]);
            }
        }
        Raise(nameof(Live)); Raise(nameof(Min)); Raise(nameof(Max)); Raise(nameof(CenterProgress));
    }

    /// <summary>Whether enough movement was captured to trust the result.</summary>
    public bool RangeLooksComplete(out string problem)
    {
        foreach (int i in new[] { 0, 1, 3, 4 })
        {
            if (Max[i] - Min[i] < 1400) { problem = $"{Names[i]} hasn't been moved through its full range yet."; return false; }
        }
        foreach (int i in new[] { 2, 5 })
        {
            if (Center[i] - Min[i] < 500) { problem = $"{Names[i]} hasn't been pressed all the way yet."; return false; }
        }
        problem = "";
        return true;
    }

    public void Finish()
    {
        if (!RangeLooksComplete(out var problem)) { Message = problem; return; }
        Message = "";
        Step = CalStep.Review;
    }

    /// <summary>Write the measured values into the config block.</summary>
    public void Save()
    {
        // Stick order in the config: RX, RY, LX, LY  <-  ADC 0, 1, 4, 3.
        int[] adcFor = { 0, 1, 4, 3 };
        for (int s = 0; s < 4; s++)
        {
            int a = adcFor[s];
            int c = Center[a];
            _cfg.Set("cal_scenter", c, s);
            _cfg.Set("cal_smax", c + (int)Math.Round((Max[a] - c) * 0.97), s);
            _cfg.Set("cal_smin", c - (int)Math.Round((c - Min[a]) * 0.97), s);
        }
        // Triggers: config index 0 = L2 (ADC 5), 1 = R2 (ADC 2).  They read high at rest and fall when pressed.
        int[] trigAdc = { 5, 2 };
        for (int t = 0; t < 2; t++)
        {
            int a = trigAdc[t];
            int rest = Center[a], pressed = Min[a];
            _cfg.Set("cal_t_hi", rest - (int)Math.Round((rest - pressed) * 0.04), t);
            _cfg.Set("cal_t_lo", pressed + (int)Math.Round((rest - pressed) * 0.03), t);
        }
        _cfg.Set("calib_mode", 1);
        Reset();
        Message = "Calibration saved.";
    }
}
