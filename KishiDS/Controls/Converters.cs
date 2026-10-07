using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;
using KishiDS.Core;

namespace KishiDS.Controls;

public sealed class InverseBoolToVisibility : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) => value is true ? Visibility.Collapsed : Visibility.Visible;
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}

public sealed class InverseBool : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) => value is not true;
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => value is not true;
}

/// <summary>Visible when the bound value equals the ConverterParameter (compared as strings).</summary>
public sealed class EqualsToVisibility : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) => string.Equals(value?.ToString(), p?.ToString(), StringComparison.OrdinalIgnoreCase) ? Visibility.Visible : Visibility.Collapsed;
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}

public sealed class DeviceStateToBrush : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) => value switch
    {
        DeviceState.CustomFirmware => new SolidColorBrush(Color.FromRgb(0x34, 0xD3, 0x99)),
        DeviceState.Bootloader => new SolidColorBrush(Color.FromRgb(0xFB, 0xBF, 0x24)),
        DeviceState.StockFirmware => new SolidColorBrush(Color.FromRgb(0x60, 0xA5, 0xFA)),
        _ => new SolidColorBrush(Color.FromRgb(0x66, 0x70, 0x84)),
    };
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}

/// <summary>Integer percent for display, e.g. 8 -> "8%".</summary>
public sealed class PercentText : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) => $"{System.Convert.ToInt32(value, c)}%";
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}

public sealed class IntToDouble : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) => System.Convert.ToDouble(value, c);
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => (int)Math.Round(System.Convert.ToDouble(value, c));
}

/// <summary>0..255 -> 0..100 percent text.</summary>
public sealed class ByteToPercentText : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) => $"{(int)Math.Round(System.Convert.ToInt32(value, c) * 100 / 255.0)}%";
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}

public sealed class PhaseToStateIndex : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) => value switch { FlashPhase.Done => 1, FlashPhase.Failed => 2, FlashPhase.Idle => 3, _ => 0 };
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}

public sealed class PhaseIsSpinning : IValueConverter
{
    public object Convert(object value, Type t, object p, CultureInfo c) => value is FlashPhase.WaitingForBootloader or FlashPhase.Verifying;
    public object ConvertBack(object value, Type t, object p, CultureInfo c) => throw new NotSupportedException();
}
