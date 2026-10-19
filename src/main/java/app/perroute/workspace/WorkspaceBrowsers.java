package app.perroute.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class WorkspaceBrowsers {
    private WorkspaceBrowsers() {}

    public record Browser(String name, String path) {}

    private static final Map<String, String> NAMES = names();

    private static Map<String, String> names() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("chrome.exe", "Google Chrome");
        names.put("msedge.exe", "Microsoft Edge");
        names.put("brave.exe", "Brave");
        names.put("vivaldi.exe", "Vivaldi");
        names.put("chromium.exe", "Chromium");
        return names;
    }

    public static boolean supported(String path) {
        try { return NAMES.containsKey(Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT)); }
        catch (RuntimeException invalid) { return false; }
    }

    public static String displayName(String path) {
        try { return NAMES.getOrDefault(Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT), Path.of(path).getFileName().toString()); }
        catch (RuntimeException invalid) { return path; }
    }

    public static List<Browser> find(List<String> knownApps) {
        Map<String, Browser> found = new LinkedHashMap<>();
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String localAppData = System.getenv("LOCALAPPDATA");
        List<String> candidates = new ArrayList<>();
        for (String root : new String[]{programFiles, programFilesX86, localAppData}) {
            if (root == null || root.isBlank()) continue;
            candidates.add(root + "\\Google\\Chrome\\Application\\chrome.exe");
            candidates.add(root + "\\Microsoft\\Edge\\Application\\msedge.exe");
            candidates.add(root + "\\BraveSoftware\\Brave-Browser\\Application\\brave.exe");
            candidates.add(root + "\\Vivaldi\\Application\\vivaldi.exe");
            candidates.add(root + "\\Chromium\\Application\\chromium.exe");
        }
        if (knownApps != null) candidates.addAll(knownApps);
        Set<String> seen = new java.util.HashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || !supported(candidate)) continue;
            Path path;
            try { path = Path.of(candidate).toAbsolutePath().normalize(); } catch (RuntimeException invalid) { continue; }
            if (!Files.isRegularFile(path) || !seen.add(path.toString().toLowerCase(Locale.ROOT))) continue;
            found.putIfAbsent(path.toString().toLowerCase(Locale.ROOT), new Browser(displayName(path.toString()), path.toString()));
        }
        return List.copyOf(found.values());
    }

    public static List<String> command(String browser, Path profile, int socksPort) {
        List<String> command = new ArrayList<>();
        command.add(browser);
        command.add("--user-data-dir=" + profile);
        command.add("--proxy-server=socks5://127.0.0.1:" + socksPort);
        command.add("--host-resolver-rules=MAP * ~NOTFOUND , EXCLUDE 127.0.0.1");
        command.add("--disable-quic");
        command.add("--force-webrtc-ip-handling-policy=disable_non_proxied_udp");
        command.add("--webrtc-ip-handling-policy=disable_non_proxied_udp");
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        return command;
    }
}
