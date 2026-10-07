using System.Windows;
using System.Windows.Controls;
using KishiDS.Core;

namespace KishiDS.Pages;

public partial class CalibrationPage : UserControl
{
    public CalibrationPage() => InitializeComponent();

    private CalibrationWizard Wizard => ((AppModel)DataContext).Calibration;

    private void Start_Click(object sender, RoutedEventArgs e)
    {
        if (((AppModel)DataContext).Device != DeviceState.CustomFirmware)
        {
            MessageBox.Show("Connect a Kishi running KishiDS firmware to calibrate it.", "KishiDS", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        Wizard.Start();
    }

    private void Finish_Click(object sender, RoutedEventArgs e) => Wizard.Finish();
    private void Save_Click(object sender, RoutedEventArgs e) => Wizard.Save();
    private void Cancel_Click(object sender, RoutedEventArgs e) => Wizard.Cancel();
}
