package app.perroute.service;

import app.perroute.model.ProxyNode;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** End-to-end tests of one server through an {@link IsolatedCore}, so the running connection is never involved. */
public final class RealTunnelProbe {
    private static final AtomicInteger NEXT_PORT = new AtomicInteger(22000);
    private final ProxyHealthCheck healthCheck = new ProxyHealthCheck();
    private final TunnelSpeedTest speedTest = new TunnelSpeedTest();

    public Result probe(ProxyNode node) {
        try {
            return withTunnel(node, (core, socksPort) -> {
                long started = System.nanoTime();
                ProxyHealthCheck.Result health = healthCheck.check("127.0.0.1", socksPort);
                long elapsed = Math.max(1L, Duration.ofNanos(System.nanoTime() - started).toMillis());
                if (!core.alive()) return new Result(false, -1, "Xray rejected this server");
                return new Result(health.success(), health.success() ? elapsed : -1L, health.detail());
            });
        } catch (Exception error) {
            return new Result(false, -1L, message(error));
        }
    }

    public TunnelSpeedTest.Result speed(ProxyNode node) {
        try {
            return withTunnel(node, (core, socksPort) -> {
                ProxyHealthCheck.Result health = healthCheck.check("127.0.0.1", socksPort);
                if (!core.alive()) return new TunnelSpeedTest.Result(false, -1L, 0L, "Xray rejected this server");
                if (!health.success()) return new TunnelSpeedTest.Result(false, -1L, 0L, health.detail());
                return speedTest.measure(TunnelHttp.Route.socks(socksPort));
            });
        } catch (Exception error) {
            return new TunnelSpeedTest.Result(false, -1L, 0L, message(error));
        }
    }

    /** What a probe does with the isolated core's SOCKS port while the core runs. */
    @FunctionalInterface
    public interface TunnelTask<T> { T run(IsolatedCore core, int socksPort) throws Exception; }

    /** Runs {@code task} against an isolated core for {@code node}; the core is stopped afterwards whatever happens. */
    public <T> T withTunnel(ProxyNode node, TunnelTask<T> task) throws Exception {
        try (IsolatedCore core = IsolatedCore.forNode(node)) {
            return task.run(core, core.socksPort());
        }
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /**
     * A loopback port for an isolated core. Ports are handed out in turn from 22000 to 30000 so concurrent probes
     * never share one, and a port another program already listens on is skipped.
     */
    public static int reservePort() {
        for (int attempt = 0; attempt < 200; attempt++) {
            int value = NEXT_PORT.getAndUpdate(current -> current >= 30000 ? 22000 : current + 1);
            if (portFree(value)) return value;
        }
        throw new IllegalStateException("No free loopback port was found for an isolated test");
    }

    private static boolean portFree(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException taken) {
            return false;
        }
    }

    public record Result(boolean reachable, long milliseconds, String detail) {}
}
