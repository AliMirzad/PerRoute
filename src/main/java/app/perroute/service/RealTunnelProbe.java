package app.perroute.service;

import app.perroute.model.ProxyNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class RealTunnelProbe {
    private static final AtomicInteger NEXT_PORT = new AtomicInteger(22000);
    private final XrayConfigGenerator generator = new XrayConfigGenerator();
    private final ProxyHealthCheck healthCheck = new ProxyHealthCheck();
    private final TunnelSpeedTest speedTest = new TunnelSpeedTest();

    public Result probe(ProxyNode node) {
        try {
            return withTunnel(node, (process, socksPort) -> {
                long started = System.nanoTime();
                ProxyHealthCheck.Result health = healthCheck.check("127.0.0.1", socksPort);
                long elapsed = Math.max(1L, Duration.ofNanos(System.nanoTime() - started).toMillis());
                if (!process.isAlive()) return new Result(false, -1, "Xray rejected this server");
                return new Result(health.success(), health.success() ? elapsed : -1L, health.detail());
            });
        } catch (Exception error) {
            return new Result(false, -1L, message(error));
        }
    }

    public TunnelSpeedTest.Result speed(ProxyNode node) {
        try {
            return withTunnel(node, (process, socksPort) -> {
                ProxyHealthCheck.Result health = healthCheck.check("127.0.0.1", socksPort);
                if (!process.isAlive()) return new TunnelSpeedTest.Result(false, -1L, 0L, "Xray rejected this server");
                if (!health.success()) return new TunnelSpeedTest.Result(false, -1L, 0L, health.detail());
                return speedTest.measure(TunnelHttp.Route.socks(socksPort));
            });
        } catch (Exception error) {
            return new TunnelSpeedTest.Result(false, -1L, 0L, message(error));
        }
    }

    @FunctionalInterface
    private interface TunnelTask<T> { T run(Process process, int socksPort) throws Exception; }

    private <T> T withTunnel(ProxyNode node, TunnelTask<T> task) throws Exception {
        int socksPort = reservePort();
        int apiPort = reservePort();
        Path config = AppDirectories.dataDirectory().resolve("probes").resolve("probe-" + UUID.randomUUID() + ".json");
        Process process = null;
        try {
            Path executable = AppDirectories.coreDirectory().resolve("xray.exe");
            if (!Files.isRegularFile(executable)) throw new IOException("Xray is not installed");
            Files.createDirectories(config.getParent());
            Files.writeString(config, generator.generateProbe(node, socksPort, apiPort), StandardCharsets.UTF_8);
            process = new ProcessBuilder(executable.toString(), "run", "-config", config.toString())
                    .directory(AppDirectories.coreDirectory().toFile()).redirectErrorStream(true).start();
            Process running = process;
            Thread.ofVirtual().start(() -> {
                try { running.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); }
                catch (IOException ignored) {}
            });
            return task.run(process, socksPort);
        } finally {
            if (process != null) {
                process.destroy();
                try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            }
            try { Files.deleteIfExists(config); } catch (IOException ignored) {}
        }
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static int reservePort() {
        int value = NEXT_PORT.getAndIncrement();
        if (value > 30000) { NEXT_PORT.compareAndSet(value + 1, 22000); return 22000; }
        return value;
    }

    public record Result(boolean reachable, long milliseconds, String detail) {}
}
