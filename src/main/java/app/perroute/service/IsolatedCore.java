package app.perroute.service;

import app.perroute.model.ProxyNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class IsolatedCore implements AutoCloseable {
    private static final int LOG_LINES = 40;
    private final Process process;
    private final Path config;
    private final int socksPort;
    private final ArrayDeque<String> lastLines = new ArrayDeque<>();

    private IsolatedCore(Process process, Path config, int socksPort) {
        this.process = process;
        this.config = config;
        this.socksPort = socksPort;
        Thread.ofVirtual().name("perroute-isolated-core-log").start(this::readOutput);
    }

    public static IsolatedCore forNode(ProxyNode node) throws IOException {
        int socksPort = RealTunnelProbe.reservePort();
        int apiPort = RealTunnelProbe.reservePort();
        return start(new XrayConfigGenerator().generateProbe(node, socksPort, apiPort), socksPort);
    }

    public static IsolatedCore start(String configJson, int socksPort) throws IOException {
        Path executable = AppDirectories.coreDirectory().resolve("xray.exe");
        if (!Files.isRegularFile(executable)) throw new IOException("Xray is not installed");
        Path config = AppDirectories.dataDirectory().resolve("probes").resolve("isolated-" + UUID.randomUUID() + ".json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, configJson, StandardCharsets.UTF_8);
        try {
            Process process = new ProcessBuilder(executable.toString(), "run", "-config", config.toString())
                    .directory(AppDirectories.coreDirectory().toFile()).redirectErrorStream(true).start();
            return new IsolatedCore(process, config, socksPort);
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(config);
            throw error;
        }
    }

    public int socksPort() { return socksPort; }

    public boolean alive() { return process.isAlive(); }

    public long pid() { return process.pid(); }

    public List<String> lastLines() {
        synchronized (lastLines) { return List.copyOf(lastLines); }
    }

    private void readOutput() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (lastLines) {
                    if (lastLines.size() >= LOG_LINES) lastLines.removeFirst();
                    lastLines.addLast(line);
                }
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try { Files.deleteIfExists(config); } catch (IOException ignored) {}
    }
}
