package app.perroute.service;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ResourceConditions {
    public record Snapshot(boolean onBattery, boolean batterySaver, boolean metered) {
        public static final Snapshot NORMAL = new Snapshot(false, false, false);

        public boolean constrained() { return onBattery || batterySaver || metered; }

        public String reason() {
            if (metered && (onBattery || batterySaver)) return "on battery and a metered network";
            if (metered) return "on a metered network";
            if (batterySaver) return "in battery saver";
            if (onBattery) return "on battery";
            return "";
        }
    }

    private static final String COST_SCRIPT =
            "$p=[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime]::GetInternetConnectionProfile();" +
            "if($p){$c=$p.GetConnectionCost();\"$($c.NetworkCostType)|$($c.Roaming)|$($c.OverDataLimit)\"}";

    private ResourceConditions() {}

    public static Snapshot read() {
        boolean battery = false, saver = false;
        try {
            SystemPowerStatus status = new SystemPowerStatus();
            if (Kernel32.INSTANCE.GetSystemPowerStatus(status)) {
                boolean hasBattery = (status.BatteryFlag & 0xff) != 128 && (status.BatteryFlag & 0xff) != 255;
                battery = hasBattery && status.ACLineStatus == 0;
                saver = (status.SystemStatusFlag & 1) == 1;
            }
        } catch (LinkageError | RuntimeException unavailable) {
        }
        return new Snapshot(battery, saver, metered());
    }

    public static boolean meteredFrom(String output) {
        if (output == null) return false;
        String[] parts = output.trim().split("\\|");
        if (parts.length < 3) return false;
        String cost = parts[0].trim();
        return cost.equalsIgnoreCase("Fixed") || cost.equalsIgnoreCase("Variable")
                || parts[1].trim().equalsIgnoreCase("True") || parts[2].trim().equalsIgnoreCase("True");
    }

    private static boolean metered() {
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", COST_SCRIPT)
                    .redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); return false; }
            return meteredFrom(new String(output, StandardCharsets.UTF_8).lines().reduce("", (a, b) -> b.isBlank() ? a : b));
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    public static class SystemPowerStatus extends Structure {
        public byte ACLineStatus;
        public byte BatteryFlag;
        public byte BatteryLifePercent;
        public byte SystemStatusFlag;
        public int BatteryLifeTime;
        public int BatteryFullLifeTime;

        @Override protected List<String> getFieldOrder() {
            return List.of("ACLineStatus", "BatteryFlag", "BatteryLifePercent", "SystemStatusFlag", "BatteryLifeTime", "BatteryFullLifeTime");
        }
    }

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);
        boolean GetSystemPowerStatus(SystemPowerStatus status);
    }
}
