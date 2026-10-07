using System.Globalization;
using System.Windows.Data;
using KishiDS.Core;

namespace KishiDS.Controls;

/// <summary>
/// The whole window is drawn at a fixed design size inside a Viewbox, so this holds the current window-to-design scale.
/// Popups (combo drop-downs, tooltips) live in their own HWND and don't inherit the Viewbox transform, so their
/// templates scale themselves with this value.
/// </summary>
public sealed class UiScale : ObservableObject
{
    public static UiScale Instance { get; } = new();

    private double _value = 1;
    public double Value { get => _value; set => Set(ref _value, value); }
}

/// <summary>Splits text at the first " · " and returns the head (parameter "0") or the tail (parameter "1").</summary>
public sealed class SplitDot : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c)
    {
        string s = value as string ?? "";
        int i = s.IndexOf(" · ", StringComparison.Ordinal);
        bool tail = string.Equals(p?.ToString(), "1", StringComparison.Ordinal);
        if (i < 0) return tail ? "" : s;
        return tail ? "· " + s[(i + 3)..].Trim() : s[..i];
    }
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}

/// <summary>Puts each sentence of a short description on its own line ("A. B." becomes two lines).</summary>
public sealed class SentenceLines : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) => (value as string ?? "").Replace(". ", ".\n");
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}
