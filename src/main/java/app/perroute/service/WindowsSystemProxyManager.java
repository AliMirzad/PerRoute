package app.perroute.service;

import app.perroute.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WindowsSystemProxyManager {
    private static final String REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
    private static final List<String> VALUES = List.of("ProxyEnable", "ProxyServer", "ProxyOverride", "AutoConfigURL");
    private final Path backupFile;

    public WindowsSystemProxyManager(Path dataDirectory) {
        backupFile = dataDirectory.resolve("system-proxy-backup.json");
    }

    public synchronized void apply() throws IOException, InterruptedException {
        restore();
        Files.createDirectories(backupFile.getParent());
        Map<String, Object> backup = new LinkedHashMap<>();
        for (String name : VALUES) backup.put(name, readValue(name));
        writeAtomically(backupFile, Json.stringify(backup));
        registerRecovery();

        try {
            writeValue("ProxyEnable", "REG_DWORD", "1");
            writeValue("ProxyServer", "REG_SZ", perRouteProxyServer());
            writeValue("ProxyOverride", "REG_SZ", "<local>;localhost;127.*;[::1]");
            deleteValue("AutoConfigURL");
            notifyWindows();
            startGuard();
        } catch (IOException | InterruptedException error) {
            try { restore(); } catch (Exception suppressed) { error.addSuppressed(suppressed); }
            throw error;
        }
    }

    public enum RestoreOutcome { NOTHING_TO_RESTORE, RESTORED, LEFT_ALONE }

    public static String perRouteProxyServer() {
        return "http=127.0.0.1:" + AppProxyLauncher.HTTP_PORT + ";https=127.0.0.1:" + AppProxyLauncher.HTTP_PORT +
                ";socks=127.0.0.1:" + AppProxyLauncher.SOCKS_PORT;
    }

    public boolean perRouteProxyActive() throws IOException, InterruptedException {
        Map<String, Object> enabled = readValue("ProxyEnable");
        Map<String, Object> server = readValue("ProxyServer");
        return Boolean.TRUE.equals(enabled.get("present")) && String.valueOf(enabled.get("data")).trim().endsWith("1")
                && Boolean.TRUE.equals(server.get("present")) && perRouteProxyServer().equals(String.valueOf(server.get("data")).trim());
    }

    public synchronized void restore() throws IOException, InterruptedException {
        restoreSafely();
    }

    public synchronized RestoreOutcome restoreSafely() throws IOException, InterruptedException {
        if (!Files.isRegularFile(backupFile)) return RestoreOutcome.NOTHING_TO_RESTORE;
        Map<String, Object> server = readValue("ProxyServer");
        String current = Boolean.TRUE.equals(server.get("present")) ? String.valueOf(server.get("data")).trim() : "";
        if (!current.isEmpty() && !current.equals(perRouteProxyServer())) {
            Files.deleteIfExists(backupFile);
            unregisterRecovery();
            return RestoreOutcome.LEFT_ALONE;
        }
        Map<String, Object> root = Json.object(Files.readString(backupFile, StandardCharsets.UTF_8));
        IOException failure = null;
        for (String name : VALUES) {
            try {
                Object raw = root.get(name);
                if (raw instanceof Map<?, ?> value && Boolean.TRUE.equals(value.get("present"))) {
                    writeValue(name, String.valueOf(value.get("type")), String.valueOf(value.get("data")));
                } else {
                    deleteValue(name);
                }
            } catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
        Files.deleteIfExists(backupFile);
        unregisterRecovery();
        notifyWindows();
        return RestoreOutcome.RESTORED;
    }

    public Map<String, Object> pendingBackup() {
        try {
            return Files.isRegularFile(backupFile) ? Json.object(Files.readString(backupFile, StandardCharsets.UTF_8)) : Map.of();
        } catch (IOException | RuntimeException unreadable) {
            return Map.of();
        }
    }

    public Map<String, Map<String, Object>> currentValues() throws IOException, InterruptedException {
        Map<String, Map<String, Object>> values = new LinkedHashMap<>();
        for (String name : VALUES) values.put(name, readValue(name));
        return values;
    }

    public synchronized void resetLeftover(Map<String, String> baseline) throws IOException, InterruptedException {
        if (!perRouteProxyActive() && !perRouteProxyServer().equals(String.valueOf(readValue("ProxyServer").get("data")).trim())) return;
        if (baseline != null) {
            String enable = baseline.getOrDefault("wininet.ProxyEnable", "");
            writeValue("ProxyEnable", "REG_DWORD", enable.trim().endsWith("1") ? "1" : "0");
            writeOrDelete("ProxyServer", baseline.getOrDefault("wininet.ProxyServer", ""));
            writeOrDelete("ProxyOverride", baseline.getOrDefault("wininet.ProxyOverride", ""));
            writeOrDelete("AutoConfigURL", baseline.getOrDefault("wininet.AutoConfigURL", ""));
        } else {
            writeValue("ProxyEnable", "REG_DWORD", "0");
            deleteValue("ProxyServer");
            if ("<local>;localhost;127.*;[::1]".equals(String.valueOf(readValue("ProxyOverride").get("data")).trim())) deleteValue("ProxyOverride");
        }
        Files.deleteIfExists(backupFile);
        unregisterRecovery();
        notifyWindows();
    }

    private void writeOrDelete(String name, String data) throws IOException, InterruptedException {
        if (data == null || data.isBlank()) deleteValue(name);
        else writeValue(name, "REG_SZ", data.trim());
    }

    public boolean recoveryEntryRegistered() {
        try { return run(Duration.ofSeconds(5), "reg.exe", "query", RUN_ONCE_KEY, "/v", RUN_ONCE_VALUE).exitCode == 0; }
        catch (Exception unknown) { return false; }
    }

    public void removeStaleRecoveryEntry() {
        if (!hasPendingRestore()) unregisterRecovery();
    }

    private static final String RUN_ONCE_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce";
    private static final String RUN_ONCE_VALUE = "PerRouteRecover";

    private void registerRecovery() {
        try {
            String executable = ProcessHandle.current().info().command().orElse("");
            if (!executable.toLowerCase().endsWith("perroute.exe")) return;
            run(Duration.ofSeconds(5), "reg.exe", "add", RUN_ONCE_KEY, "/v", RUN_ONCE_VALUE,
                    "/t", "REG_SZ", "/d", "\"" + executable + "\" --recover", "/f");
        } catch (Exception ignored) {
        }
    }

    private void startGuard() {
        try {
            String executable = ProcessHandle.current().info().command().orElse("");
            if (!executable.toLowerCase().endsWith("perroute.exe")) return;
            new ProcessBuilder(executable, "--guard", String.valueOf(ProcessHandle.current().pid()))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Exception ignored) {
        }
    }

    public boolean hasPendingRestore() {
        return Files.isRegularFile(backupFile);
    }

    private void unregisterRecovery() {
        try { run(Duration.ofSeconds(5), "reg.exe", "delete", RUN_ONCE_KEY, "/v", RUN_ONCE_VALUE, "/f"); }
        catch (Exception ignored) {}
    }

    private Map<String, Object> readValue(String name) throws IOException, InterruptedException {
        Result result = run(Duration.ofSeconds(5), "reg.exe", "query", REGISTRY_KEY, "/v", name);
        Map<String, Object> value = new LinkedHashMap<>();
        if (result.exitCode != 0) {
            value.put("present", false);
            return value;
        }
        Pattern pattern = Pattern.compile("(?im)^\\s*" + Pattern.quote(name) + "\\s+(REG_[A-Z0-9_]+)\\s+(.*)$");
        Matcher matcher = pattern.matcher(result.output);
        if (!matcher.find()) {
            value.put("present", false);
            return value;
        }
        value.put("present", true);
        value.put("type", matcher.group(1));
        value.put("data", matcher.group(2).strip());
        return value;
    }

    private void writeValue(String name, String type, String data) throws IOException, InterruptedException {
        Result result = run(Duration.ofSeconds(5), "reg.exe", "add", REGISTRY_KEY, "/v", name,
                "/t", type, "/d", data, "/f");
        if (result.exitCode != 0) throw new IOException("Could not update Windows system proxy: " + result.output.strip());
    }

    private void deleteValue(String name) throws IOException, InterruptedException {
        Result result = run(Duration.ofSeconds(5), "reg.exe", "delete", REGISTRY_KEY, "/v", name, "/f");
        if (result.exitCode != 0 && !result.output.toLowerCase().contains("unable to find") &&
                !result.output.toLowerCase().contains("not found")) {
            if (readValue(name).get("present").equals(Boolean.TRUE)) {
                throw new IOException("Could not clear Windows proxy value " + name + ": " + result.output.strip());
            }
        }
    }

    private void notifyWindows() {
        String command = "$sig='[DllImport(\"wininet.dll\")] public static extern bool InternetSetOption(IntPtr h,int o,IntPtr b,int l);';" +
                "Add-Type -MemberDefinition $sig -Name Native -Namespace PerRoute -ErrorAction SilentlyContinue;" +
                "[PerRoute.Native]::InternetSetOption([IntPtr]::Zero,39,[IntPtr]::Zero,0)|Out-Null;" +
                "[PerRoute.Native]::InternetSetOption([IntPtr]::Zero,37,[IntPtr]::Zero,0)|Out-Null";
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    private static Result run(Duration timeout, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException(command[0] + " timed out");
        }
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void writeAtomically(Path target, String text) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Result(int exitCode, String output) {}
}
