package app.perroute.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AppProxyLauncher {
    public static final int SOCKS_PORT = 17890;
    public static final int HTTP_PORT = 17891;

    public enum Adapter { CHROMIUM, JETBRAINS, ENVIRONMENT, UNSUPPORTED }

    public record Compatibility(boolean supported, String label, String detail, Adapter adapter) { }

    public record LaunchPlan(List<String> command, Map<String, String> environment,
                             Compatibility compatibility) { }

    private static final Set<String> CHROMIUM_EXECUTABLES = Set.of(
            "brave.exe", "chrome.exe", "msedge.exe", "chromium.exe",
            "vivaldi.exe", "opera.exe", "opera_gx.exe",
            "code.exe", "code-insiders.exe", "cursor.exe", "windsurf.exe",
            "discord.exe", "slack.exe", "postman.exe", "insomnia.exe",
            "obsidian.exe", "notion.exe", "teams.exe", "ms-teams.exe"
    );

    private static final Set<String> JETBRAINS_EXECUTABLES = Set.of(
            "idea.exe", "idea64.exe", "pycharm.exe", "pycharm64.exe",
            "webstorm.exe", "webstorm64.exe", "clion.exe", "clion64.exe",
            "rider.exe", "rider64.exe", "goland.exe", "goland64.exe",
            "datagrip.exe", "datagrip64.exe", "phpstorm.exe", "phpstorm64.exe",
            "rubymine.exe", "rubymine64.exe", "rustrover.exe", "rustrover64.exe",
            "dataspell.exe", "dataspell64.exe", "studio.exe", "studio64.exe",
            "fleet.exe", "fleet64.exe"
    );

    private static final Set<String> UNSUPPORTED_EXECUTABLES = Set.of(
            "firefox.exe", "thunderbird.exe"
    );

    public Compatibility compatibility(String applicationPath) {
        final Path path;
        try {
            path = Path.of(applicationPath).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return new Compatibility(false, "Invalid path", "The application path is invalid.", Adapter.UNSUPPORTED);
        }
        String name = executableName(path);
        if (CHROMIUM_EXECUTABLES.contains(name)) {
            return new Compatibility(true, "Chromium / Electron",
                    "Uses the application's native --proxy-server switch.", Adapter.CHROMIUM);
        }
        if (JETBRAINS_EXECUTABLES.contains(name)) {
            return new Compatibility(true, "JetBrains / JVM",
                    "Uses JVM HTTP proxy properties and standard proxy environment variables.", Adapter.JETBRAINS);
        }
        if (UNSUPPORTED_EXECUTABLES.contains(name)) {
            return new Compatibility(false, "Not supported",
                    "This app does not accept a per-launch proxy. Use System proxy or configure it inside the app.",
                    Adapter.UNSUPPORTED);
        }
        return new Compatibility(true, "Environment proxy",
                "Uses HTTP_PROXY, HTTPS_PROXY and ALL_PROXY. The app must support these standard variables.",
                Adapter.ENVIRONMENT);
    }

    public List<String> validate(List<String> applicationPaths) {
        List<String> problems = new ArrayList<>();
        for (String rawPath : applicationPaths) {
            final Path path;
            try {
                path = Path.of(rawPath).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                problems.add("Invalid application path: " + rawPath);
                continue;
            }
            if (!Files.isRegularFile(path)) {
                problems.add("Application was not found: " + path);
                continue;
            }
            Compatibility compatibility = compatibility(path.toString());
            if (!compatibility.supported()) problems.add(path.getFileName() + ": " + compatibility.detail());
        }
        return problems;
    }

    public List<String> runningApplications(List<String> applicationPaths) {
        List<Path> selected = applicationPaths.stream().map(path -> Path.of(path).toAbsolutePath().normalize()).toList();
        List<String> running = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(process -> process.info().command().ifPresent(command -> {
            Path commandPath;
            try { commandPath = Path.of(command).toAbsolutePath().normalize(); }
            catch (RuntimeException ignored) { return; }
            for (Path expected : selected) {
                if (commandPath.toString().equalsIgnoreCase(expected.toString())) {
                    String display = expected.getFileName() == null ? expected.toString() : expected.getFileName().toString();
                    if (running.stream().noneMatch(display::equalsIgnoreCase)) running.add(display);
                }
            }
        }));
        return running;
    }

    public List<String> stoppedApplications(List<String> applicationPaths) {
        Set<String> running = new HashSet<>();
        ProcessHandle.allProcesses().forEach(process -> process.info().command().ifPresent(command -> {
            try { running.add(Path.of(command).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT)); }
            catch (RuntimeException ignored) { }
        }));
        return applicationPaths.stream()
                .map(path -> Path.of(path).toAbsolutePath().normalize().toString())
                .filter(path -> !running.contains(path.toLowerCase(Locale.ROOT)))
                .toList();
    }

    public LaunchPlan plan(String rawPath) throws IOException {
        Path executable = Path.of(rawPath).toAbsolutePath().normalize();
        Compatibility compatibility = compatibility(executable.toString());
        if (!compatibility.supported()) throw new IOException(executable.getFileName() + ": " + compatibility.detail());

        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        if (compatibility.adapter() == Adapter.CHROMIUM) {
            command.add("--proxy-server=http://127.0.0.1:" + HTTP_PORT);
            command.add("--proxy-bypass-list=localhost;127.0.0.1;[::1]");
            command.add("--disable-quic");
            command.add("--no-first-run");
        }

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HTTP_PROXY", "http://127.0.0.1:" + HTTP_PORT);
        environment.put("HTTPS_PROXY", "http://127.0.0.1:" + HTTP_PORT);
        environment.put("ALL_PROXY", "socks5://127.0.0.1:" + SOCKS_PORT);
        environment.put("NO_PROXY", "localhost,127.0.0.1,::1");
        if (compatibility.adapter() == Adapter.JETBRAINS) {
            environment.put("JAVA_TOOL_OPTIONS", String.join(" ",
                    "-Dhttp.proxyHost=127.0.0.1",
                    "-Dhttp.proxyPort=" + HTTP_PORT,
                    "-Dhttps.proxyHost=127.0.0.1",
                    "-Dhttps.proxyPort=" + HTTP_PORT,
                    "-Dhttp.nonProxyHosts=localhost|127.*|[::1]"));
        }
        return new LaunchPlan(List.copyOf(command), Map.copyOf(environment), compatibility);
    }

    public List<Process> launch(List<String> applicationPaths) throws IOException {
        List<Process> launched = new ArrayList<>();
        try {
            for (String rawPath : applicationPaths) {
                Path executable = Path.of(rawPath).toAbsolutePath().normalize();
                LaunchPlan plan = plan(executable.toString());
                ProcessBuilder builder = new ProcessBuilder(plan.command());
                Path parent = executable.getParent();
                if (parent != null) builder.directory(parent.toFile());
                Map<String, String> targetEnvironment = builder.environment();
                plan.environment().forEach((key, value) -> {
                    if ("JAVA_TOOL_OPTIONS".equals(key)) {
                        String existing = targetEnvironment.get(key);
                        targetEnvironment.put(key, existing == null || existing.isBlank() ? value : existing + " " + value);
                    } else {
                        targetEnvironment.put(key, value);
                    }
                });
                launched.add(builder.start());
            }
            return launched;
        } catch (IOException e) {
            launched.stream().filter(Process::isAlive).forEach(Process::destroy);
            throw e;
        }
    }

    public List<Process> launchNormally(List<String> applicationPaths) throws IOException {
        List<Process> launched = new ArrayList<>();
        try {
            for (String rawPath : applicationPaths) {
                Path executable = Path.of(rawPath).toAbsolutePath().normalize();
                if (!Files.isRegularFile(executable)) throw new IOException("Application was not found: " + executable);
                ProcessBuilder builder = new ProcessBuilder(executable.toString());
                Path parent = executable.getParent();
                if (parent != null) builder.directory(parent.toFile());
                launched.add(builder.start());
            }
            return launched;
        } catch (IOException e) {
            launched.stream().filter(Process::isAlive).forEach(Process::destroy);
            throw e;
        }
    }

    private static String executableName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
    }
}
