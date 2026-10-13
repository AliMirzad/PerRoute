package app.perroute.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

public final class TunnelSpeedTest {
    private static final String HOST = "speed.cloudflare.com";
    private static final long MAX_BYTES = 10_000_000L;
    private static final long MAX_NANOS = 4_000_000_000L;
    private static final long WARMUP_NANOS = 700_000_000L;

    public Result measure(TunnelHttp.Route route) {
        try (Socket socket = TunnelHttp.openGet(route, HOST, "/__down?bytes=" + MAX_BYTES, 8_000)) {
            InputStream input = socket.getInputStream();
            int status = TunnelHttp.readHeaders(input);
            if (status < 200 || status >= 300) return Result.failed("The speed endpoint answered HTTP " + status);
            return measure(input, MAX_BYTES, MAX_NANOS, WARMUP_NANOS);
        } catch (IOException | RuntimeException error) {
            return Result.failed(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    public static Result measure(InputStream input, long maxBytes, long maxNanos, long warmupNanos) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long started = System.nanoTime();
        long total = 0;
        long warmupBytes = -1;
        long warmupAt = 0;
        while (total < maxBytes) {
            long now = System.nanoTime();
            if (now - started >= maxNanos) break;
            if (warmupBytes < 0 && now - started >= warmupNanos) { warmupBytes = total; warmupAt = now; }
            int read;
            try { read = input.read(buffer); }
            catch (java.net.SocketTimeoutException stalled) { break; }
            if (read < 0) break;
            total += read;
        }
        long finished = System.nanoTime();
        if (total <= 0) return Result.failed("No data was received.");
        long bytes = warmupBytes >= 0 && total > warmupBytes ? total - warmupBytes : total;
        long nanos = Math.max(1L, warmupBytes >= 0 && total > warmupBytes ? finished - warmupAt : finished - started);
        return new Result(true, (long) (bytes * 1_000_000_000.0 / nanos), total, "");
    }

    public record Result(boolean success, long bytesPerSecond, long bytesReceived, String detail) {
        static Result failed(String detail) { return new Result(false, -1L, 0L, detail); }
    }
}
