package app.perroute.service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class XrayProcessManager implements AutoCloseable {
    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Boolean>> stateListeners = new CopyOnWriteArrayList<>();
    private volatile Process process;

    public void addLogListener(Consumer<String> listener) { logListeners.add(listener); }
    public void addStateListener(Consumer<Boolean> listener) { stateListeners.add(listener); }
    public boolean isRunning() { return process != null && process.isAlive(); }

    public synchronized void start(String config) throws IOException, InterruptedException {
        start(config, false);
    }

    public synchronized void start(String config, boolean requiresWintun) throws IOException, InterruptedException {
        if (isRunning()) return;
        Path coreDir = AppDirectories.coreDirectory();
        Path executable = coreDir.resolve("xray.exe");
        Path wintun = coreDir.resolve("wintun.dll");
        if (!Files.isRegularFile(executable) || (requiresWintun && !Files.isRegularFile(wintun))) {
            throw new IOException(requiresWintun
                    ? "Xray/Wintun is not installed. Use the Install core button first."
                    : "Xray is not installed. Use the Install core button first.");
        }
        Path data = AppDirectories.dataDirectory();
        Files.createDirectories(data);
        Path configPath = data.resolve("current-config.json");
        Files.writeString(configPath, config, StandardCharsets.UTF_8);
        validate(executable, coreDir, configPath);

        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "run", "-config", configPath.toString());
        builder.directory(coreDir.toFile());
        builder.redirectErrorStream(true);
        process = builder.start();
        fireState(true);
        Process started = process;
        Thread.ofPlatform().daemon().name("xray-output").start(() -> readOutput(started));
        started.onExit().thenRun(() -> {
            boolean wasCurrent;
            synchronized (this) {
                wasCurrent = process == started;
                if (wasCurrent) process = null;
            }
            if (wasCurrent) fireState(false);
        });
    }

    private void validate(Path executable, Path coreDir, Path config) throws IOException, InterruptedException {
        Process validation = new ProcessBuilder(executable.toString(), "run", "-test", "-config", config.toString())
                .directory(coreDir.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = Thread.ofPlatform().daemon().name("xray-validation-output").start(() -> {
            try { validation.getInputStream().transferTo(captured); } catch (IOException ignored) {}
        });
        boolean done = validation.waitFor(15, TimeUnit.SECONDS);
        if (!done) {
            validation.destroyForcibly();
            throw new IOException("Xray config validation timed out");
        }
        try { drain.join(2_000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        String output = captured.toString(StandardCharsets.UTF_8).strip();
        if (validation.exitValue() != 0) throw new IOException("Xray rejected the generated config: " + output);
        if (!output.isBlank()) fireLog(output);
    }

    private void readOutput(Process source) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) fireLog(line);
        } catch (IOException e) {
            if (source.isAlive()) fireLog("Log reader error: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        Process current = process;
        if (current == null) return;
        process = null;
        current.destroy();
        try {
            if (!current.waitFor(5, TimeUnit.SECONDS)) current.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
        fireState(false);
    }

    public void stopOrphan() {
        String executable = AppDirectories.coreDirectory().resolve("xray.exe").toString();
        java.util.Set<Long> own = ProcessHandle.current().descendants()
                .map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
        ProcessHandle.allProcesses()
                .filter(handle -> !own.contains(handle.pid()))
                .filter(handle -> handle.info().command().map(executable::equalsIgnoreCase).orElse(false))
                .forEach(handle -> {
                    handle.destroyForcibly();
                    fireLog("Stopped an Xray process left over from a previous session.");
                });
    }

    private void fireLog(String line) { logListeners.forEach(listener -> listener.accept(line)); }
    private void fireState(boolean running) { stateListeners.forEach(listener -> listener.accept(running)); }
    @Override public void close() { stop(); }
}
