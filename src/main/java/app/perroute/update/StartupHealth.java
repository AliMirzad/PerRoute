package app.perroute.update;

import app.perroute.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether this version of PerRoute starts properly. Every start is recorded before the window opens and marked
 * healthy once the window has been up for a while; starts that never got that far are counted. After an update
 * the same file also says which version was replaced. The standalone recovery tool reads it to offer a rollback
 * when PerRoute cannot open its window at all.
 */
public final class StartupHealth {
    /** Starts in a row that never became healthy before PerRoute offers to go back to the previous version. */
    public static final int FAILED_STARTS_FOR_ROLLBACK = 2;

    private final Path file;

    public StartupHealth(Path file) { this.file = file; }

    /** What the file says. */
    public record Status(String version, int failedStarts, long lastStartAt, long healthyAt, String updatedFrom, long updatedAt) {
        public boolean struggling() { return failedStarts >= FAILED_STARTS_FOR_ROLLBACK; }
    }

    /**
     * Records a start of {@code version} and returns the status before it, with the number of earlier starts of
     * this version that never became healthy.
     */
    public synchronized Status beginStart(String version, long now) {
        Status previous = read();
        boolean same = previous.version().equals(version);
        // A start that was neither marked healthy nor ended cleanly did not get the window working.
        int failed = same && previous.lastStartAt() > previous.healthyAt() ? previous.failedStarts() + 1 : 0;
        String updatedFrom = same ? previous.updatedFrom() : previous.version();
        long updatedAt = same ? previous.updatedAt() : (previous.version().isEmpty() ? 0 : now);
        Status next = new Status(version, failed, now, same ? previous.healthyAt() : 0, updatedFrom, updatedAt);
        write(next);
        return next;
    }

    /** The window has been up long enough: this version starts properly. */
    public synchronized void markHealthy(long now) {
        Status current = read();
        if (current.version().isEmpty()) return;
        write(new Status(current.version(), 0, current.lastStartAt(), now, current.updatedFrom(), current.updatedAt()));
    }

    public synchronized Status read() {
        try {
            if (!Files.isRegularFile(file)) return new Status("", 0, 0, 0, "", 0);
            Map<String, Object> json = Json.object(Files.readString(file, StandardCharsets.UTF_8));
            return new Status(text(json.get("version")), (int) number(json.get("failedStarts")), number(json.get("lastStartAt")),
                    number(json.get("healthyAt")), text(json.get("updatedFrom")), number(json.get("updatedAt")));
        } catch (IOException | RuntimeException unreadable) {
            return new Status("", 0, 0, 0, "", 0);
        }
    }

    private void write(Status status) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", status.version());
        json.put("failedStarts", status.failedStarts());
        json.put("lastStartAt", status.lastStartAt());
        json.put("healthyAt", status.healthyAt());
        json.put("updatedFrom", status.updatedFrom());
        json.put("updatedAt", status.updatedAt());
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, Json.stringify(json), StandardCharsets.UTF_8);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (RuntimeException invalid) { return 0L; }
    }
}
