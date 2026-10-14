using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("PerRoute Uninstaller")]
[assembly: AssemblyDescription("System uninstaller for PerRoute")]
[assembly: AssemblyCompany("Ali Mirzad")]
[assembly: AssemblyProduct("PerRoute")]
[assembly: AssemblyVersion("3.0.0.0")]
[assembly: AssemblyFileVersion("3.0.0.0")]

namespace PerRouteSetup
{
    internal static class UninstallerProgram
    {
        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            DialogResult answer = MessageBox.Show(
                "Remove PerRoute from this computer?\r\n\r\nThe shared per-app network driver will be kept for safety.",
                "Uninstall PerRoute",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question,
                MessageBoxDefaultButton.Button2);
            if (answer != DialogResult.Yes) return;

            string installDirectory = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
            TryDelete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu), "Programs", "PerRoute.lnk"));
            TryDelete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory), "PerRoute.lnk"));
            try
            {
                using (RegistryKey machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                    machine.DeleteSubKeyTree(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\PerRoute", false);
            }
            catch { }

            string cleanup = Path.Combine(Path.GetTempPath(), "PerRoute-cleanup-" + Guid.NewGuid().ToString("N") + ".cmd");
            File.WriteAllText(cleanup,
                "@echo off\r\n" +
                "timeout /t 2 /nobreak >nul\r\n" +
                "rmdir /s /q \"" + installDirectory + "\"\r\n" +
                "del /f /q \"%~f0\"\r\n");
            Process.Start(new ProcessStartInfo("cmd.exe", "/c \"\"" + cleanup + "\"\"") {
                CreateNoWindow = true,
                UseShellExecute = false,
                WindowStyle = ProcessWindowStyle.Hidden
            });
            MessageBox.Show("PerRoute has been removed.", "Uninstall complete", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        private static void TryDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { }
        }
    }
}
