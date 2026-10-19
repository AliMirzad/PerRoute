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

    /**
     * Checks a complete config with Xray without touching the running core or its config file, so a
     * rejected config can never interrupt the current connection.
     */
    public void validate(String config, boolean requiresWintun) throws IOException, InterruptedException {
        Path coreDir = AppDirectories.coreDirectory();
        Path executable = coreDir.resolve("xray.exe");
        if (!Files.isRegularFile(executable) || (requiresWintun && !Files.isRegularFile(coreDir.resolve("wintun.dll")))) {
            throw new IOException(requiresWintun
                    ? "Xray/Wintun is not installed. Use the Install core button first."
                    : "Xray is not installed. Use the Install core button first.");
        }
        Path data = AppDirectories.dataDirectory();
        Files.createDirectories(data);
        Path pending = data.resolve("pending-config.json");
        try {
            Files.writeString(pending, config, StandardCharsets.UTF_8);
            validate(executable, coreDir, pending);
        } finally {
            Files.deleteIfExists(pending);
        }
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

    /**
     * Moves one route of the running core to a new outbound without restarting it: the new outbound is added,
     * all routing rules are swapped in one call, and only then is the old outbound removed. Until the swap the
     * old route keeps working; after it, new connections use the new outbound. If adding or swapping fails,
     * whatever was added is removed again and the running core is left exactly as it was.
     *
     * @param config the complete config the core now corresponds to; it replaces current-config.json
     */
    public synchronized void applyRuntimeChange(String outbounds, String routing, String newTag, String oldTag, String config)
            throws IOException, InterruptedException {
        if (!isRunning()) throw new IOException("The connection core is not running");
        Path data = AppDirectories.dataDirectory();
        Files.createDirectories(data);
        // These files hold server credentials, exactly like current-config.json, and are removed right away.
        Path outboundFile = data.resolve("runtime-outbound.json");
        Path routingFile = data.resolve("runtime-routing.json");
        try {
            Files.writeString(outboundFile, outbounds, StandardCharsets.UTF_8);
            Files.writeString(routingFile, routing, StandardCharsets.UTF_8);
            api("ado", outboundFile.toString());
            try {
                api("adrules", routingFile.toString());
            } catch (IOException swapFailed) {
                try { api("rmo", newTag); } catch (IOException ignored) { }
                throw swapFailed;
            }
            try { api("rmo", oldTag); }
            catch (IOException stale) { fireLog("The previous outbound " + oldTag + " could not be removed and stays idle: " + stale.getMessage()); }
            Files.writeString(data.resolve("current-config.json"), config, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(outboundFile);
            Files.deleteIfExists(routingFile);
        }
    }

    /**
     * Changes the layout of the running core: new inbounds and outbounds are added, the rule list is swapped in one
     * step, and only then is everything that is no longer used removed. If adding or swapping fails, whatever was
     * added is removed again, so the core keeps working exactly as it was.
     */
    public synchronized void applyRuntimePlan(XrayConfigGenerator.RuntimePlan plan) throws IOException, InterruptedException {
        if (!isRunning()) throw new IOException("The connection core is not running");
        Path data = AppDirectories.dataDirectory();
        Files.createDirectories(data);
        // Like current-config.json these files can hold credentials; they are removed right away.
        Path inboundFile = data.resolve("runtime-inbounds.json");
        Path outboundFile = data.resolve("runtime-outbounds.json");
        Path routingFile = data.resolve("runtime-routing.json");
        java.util.List<String> addedInbounds = new java.util.ArrayList<>();
        java.util.List<String> addedOutbounds = new java.util.ArrayList<>();
        try {
            if (plan.inbounds() != null) {
                Files.writeString(inboundFile, plan.inbounds(), StandardCharsets.UTF_8);
                api("adi", inboundFile.toString());
                addedInbounds.addAll(plan.addedInboundTags());
            }
            if (plan.outbounds() != null) {
                Files.writeString(outboundFile, plan.outbounds(), StandardCharsets.UTF_8);
                api("ado", outboundFile.toString());
                addedOutbounds.addAll(plan.addedOutboundTags());
            }
            Files.writeString(routingFile, plan.routing(), StandardCharsets.UTF_8);
            api("adrules", routingFile.toString());
        } catch (IOException failed) {
            for (String tag : addedOutbounds) { try { api("rmo", tag); } catch (IOException ignored) { } }
            for (String tag : addedInbounds) { try { api("rmi", tag); } catch (IOException ignored) { } }
            throw failed;
        } finally {
            Files.deleteIfExists(inboundFile);
            Files.deleteIfExists(outboundFile);
            Files.deleteIfExists(routingFile);
        }
        for (String tag : plan.removeOutbounds()) {
            try { api("rmo", tag); } catch (IOException stale) { fireLog("The unused outbound " + tag + " could not be removed and stays idle: " + stale.getMessage()); }
        }
        for (String tag : plan.removeInbounds()) {
            try { api("rmi", tag); } catch (IOException stale) { fireLog("The unused inbound " + tag + " could not be removed and stays idle: " + stale.getMessage()); }
        }
        Files.writeString(data.resolve("current-config.json"), plan.config(), StandardCharsets.UTF_8);
    }

    private String api(String command, String argument) throws IOException, InterruptedException {
        Path coreDir = AppDirectories.coreDirectory();
        Process process = new ProcessBuilder(coreDir.resolve("xray.exe").toString(), "api", command,
                "--server=127.0.0.1:" + XrayConfigGenerator.STATS_API_PORT, argument)
                .directory(coreDir.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            try { process.getInputStream().transferTo(output); } catch (IOException ignored) { }
        });
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Xray did not answer the " + command + " request in time");
        }
        drain.join(1_000);
        String text = output.toString(StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) throw new IOException("Xray refused " + command + ": " + (text.isBlank() ? "exit " + process.exitValue() : text));
        return text;
    }

    /** Xray processes of PerRoute's core folder that this process did not start: leftovers of an earlier session. */
    public int orphanCount() {
        String executable = AppDirectories.coreDirectory().resolve("xray.exe").toString();
        java.util.Set<Long> own = ProcessHandle.current().descendants()
                .map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
        return (int) ProcessHandle.allProcesses()
                .filter(handle -> !own.contains(handle.pid()))
                .filter(handle -> handle.info().command().map(executable::equalsIgnoreCase).orElse(false))
                .count();
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
