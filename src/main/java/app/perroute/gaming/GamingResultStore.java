package app.perroute.gaming;

import app.perroute.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class GamingResultStore {
    private final Path file;
    private final Map<String, GamingScore> results = new LinkedHashMap<>();
    private boolean loaded;

    public GamingResultStore(Path file) { this.file = file; }

    public synchronized Map<String, GamingScore> all() {
        load();
        return Map.copyOf(results);
    }

    public synchronized GamingScore get(String key) {
        load();
        return results.get(key);
    }

    public synchronized void put(String key, GamingScore score) {
        load();
        results.put(key, score);
        save();
    }

    public synchronized void retain(Collection<String> keys) {
        load();
        Set<String> keep = new HashSet<>(keys);
        if (results.keySet().retainAll(keep)) save();
    }

    public synchronized void clear() {
        results.clear();
        loaded = true;
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.isRegularFile(file)) return;
            Map<String, Object> root = Json.object(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root.get("servers") instanceof Map<?, ?> servers)) return;
            servers.forEach((key, raw) -> {
                if (!(raw instanceof Map<?, ?> json)) return;
                results.put(String.valueOf(key), new GamingScore((int) number(json.get("score")), String.valueOf(json.get("label")),
                        Boolean.TRUE.equals(json.get("udp")), number(json.get("latency")), number(json.get("p95")), number(json.get("jitter")),
                        decimal(json.get("loss")), number(json.get("tcpLatency")), decimal(json.get("tcpLoss")), number(json.get("at")),
                        json.get("note") == null ? "" : String.valueOf(json.get("note"))));
            });
        } catch (IOException | RuntimeException unreadable) {
            results.clear();
        }
    }

    private void save() {
        Map<String, Object> servers = new LinkedHashMap<>();
        results.forEach((key, score) -> {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("score", score.score());
            json.put("label", score.label());
            json.put("udp", score.udpSupported());
            json.put("latency", score.latencyMillis());
            json.put("p95", score.p95Millis());
            json.put("jitter", score.jitterMillis());
            json.put("loss", score.lossPercent());
            json.put("tcpLatency", score.tcpLatencyMillis());
            json.put("tcpLoss", score.tcpLossPercent());
            json.put("at", score.measuredAt());
            json.put("note", score.note());
            servers.put(key, json);
        });
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, Json.stringify(Map.of("format", 1, "servers", servers)), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (RuntimeException invalid) { return -1; }
    }

    private static double decimal(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); } catch (RuntimeException invalid) { return 0; }
    }
}
