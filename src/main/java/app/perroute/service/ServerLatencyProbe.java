package app.perroute.service;

import app.perroute.model.ProxyNode;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;

public final class ServerLatencyProbe {
    private static final int DEFAULT_TIMEOUT_MILLIS = 1_500;

    public Result probe(ProxyNode node) {
        return probe(node, DEFAULT_TIMEOUT_MILLIS);
    }

    public Result probe(ProxyNode node, int timeoutMillis) {
        Objects.requireNonNull(node, "node");
        if (timeoutMillis < 100) throw new IllegalArgumentException("timeoutMillis is too small");
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(node.address(), node.port()), timeoutMillis);
            long elapsed = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
            return new Result(true, elapsed, "");
        } catch (Exception error) {
            String detail = error.getMessage();
            return new Result(false, -1L,
                    detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail);
        }
    }

    public record Result(boolean reachable, long milliseconds, String detail) {}
}
