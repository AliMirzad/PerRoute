package app.perroute.service;

import java.util.concurrent.TimeUnit;

public final class AdminCheck {
    private AdminCheck() {}

    public static boolean isElevated() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) return false;
        try {
            Process process = new ProcessBuilder("whoami.exe", "/groups").redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) return false;
            String groups = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return groups.contains("S-1-16-12288") || groups.contains("S-1-16-16384");
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean relaunchPackagedAppElevated() {
        try {
            String command = ProcessHandle.current().info().command().orElse("");
            if (command.isBlank() || !command.toLowerCase(java.util.Locale.ROOT).endsWith("perroute.exe")) return false;
            String escaped = command.replace("'", "''");
            String script = "Start-Process -FilePath '" + escaped + "' -Verb RunAs";
            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).start();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
