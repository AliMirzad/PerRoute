package app.perroute.quality;

import app.perroute.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recent quality samples per server key, at most {@link #MAX_SAMPLES_PER_SERVER} each and none older than a
 * week. Saved to a small local file at most once every {@link #SAVE_INTERVAL_MILLIS}, so background
 * measurements do not turn into a stream of disk writes. The file holds server keys, times and latencies only.
 */
public final class QualityHistoryStore implements AutoCloseable {
    public static final int MAX_SAMPLES_PER_SERVER = 64;
    static final long KEEP_MILLIS = 7L * 24 * 60 * 60_000L;
    static final long SAVE_INTERVAL_MILLIS = 60_000L;

    private final Path file;
    private final Map<String, ArrayDeque<QualitySample>> samples = new HashMap<>();
    private final Map<String, QualityScore> cache = new HashMap<>();
    private long cacheMinute = -1;
    private boolean dirty;
    private long lastSaveMillis;

    public QualityHistoryStore(Path file) {
        this.file = file;
        load();
    }

    public synchronized void record(String serverKey, QualitySample sample) {
        if (serverKey == null || serverKey.isBlank() || sample == null) return;
        ArrayDeque<QualitySample> list = samples.computeIfAbsent(serverKey, ignored -> new ArrayDeque<>());
        list.addLast(sample);
        while (list.size() > MAX_SAMPLES_PER_SERVER) list.removeFirst();
        cache.remove(serverKey);
        dirty = true;
        if (sample.atMillis() - lastSaveMillis >= SAVE_INTERVAL_MILLIS) saveQuietly(sample.atMillis());
    }

    public synchronized List<QualitySample> samples(String serverKey) {
        ArrayDeque<QualitySample> list = samples.get(serverKey);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Scores are cached per server for the current minute; a new sample invalidates that server's entry. */
    public synchronized QualityScore score(String serverKey, long now) {
        long minute = now / 60_000L;
        if (minute != cacheMinute) { cache.clear(); cacheMinute = minute; }
        return cache.computeIfAbsent(serverKey, key -> NetworkQualityScorer.score(samples(key), now));
    }

    public synchronized Map<String, QualityScore> scores(Collection<String> serverKeys, long now) {
        Map<String, QualityScore> result = new LinkedHashMap<>();
        for (String key : serverKeys) result.put(key, score(key, now));
        return result;
    }

    /** The time of the newest sample of a server, or 0. */
    public synchronized long lastMeasured(String serverKey) {
        ArrayDeque<QualitySample> list = samples.get(serverKey);
        return list == null || list.isEmpty() ? 0L : list.getLast().atMillis();
    }

    /** Forgets servers that no longer exist. */
    public synchronized void retain(Collection<String> liveKeys) {
        if (samples.keySet().retainAll(new java.util.HashSet<>(liveKeys))) {
            cache.keySet().retainAll(samples.keySet());
            dirty = true;
        }
    }

    public synchronized void clear() {
        samples.clear();
        cache.clear();
        dirty = true;
        saveQuietly(System.currentTimeMillis());
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            Map<String, Object> root = Json.object(Files.readString(file, StandardCharsets.UTF_8));
            long cutoff = System.currentTimeMillis() - KEEP_MILLIS;
            if (!(root.get("servers") instanceof Map<?, ?> servers)) return;
            for (var entry : servers.entrySet()) {
                if (!(entry.getValue() instanceof List<?> list)) continue;
                ArrayDeque<QualitySample> restored = new ArrayDeque<>();
                for (Object raw : list) {
                    if (!(raw instanceof List<?> fields) || fields.size() < 4) continue;
                    try {
                        long at = ((Number) fields.get(0)).longValue();
                        if (at < cutoff) continue;
                        QualitySample.Kind kind = QualitySample.Kind.valueOf(String.valueOf(fields.get(1)));
                        boolean success = Boolean.TRUE.equals(fields.get(2));
                        long latency = ((Number) fields.get(3)).longValue();
                        restored.addLast(new QualitySample(at, kind, success, latency, ""));
                    } catch (RuntimeException ignored) { }
                }
                while (restored.size() > MAX_SAMPLES_PER_SERVER) restored.removeFirst();
                if (!restored.isEmpty()) samples.put(String.valueOf(entry.getKey()), restored);
            }
        } catch (IOException | RuntimeException ignored) {
            // A damaged history only costs the scores their evidence; it never blocks startup.
        }
    }

    private void saveQuietly(long now) {
        if (!dirty || file == null) return;
        try {
            Map<String, Object> servers = new LinkedHashMap<>();
            for (var entry : samples.entrySet()) {
                List<Object> encoded = new ArrayList<>();
                for (QualitySample sample : entry.getValue()) {
                    encoded.add(List.of(sample.atMillis(), sample.kind().name(), sample.success(), sample.latencyMillis()));
                }
                servers.put(entry.getKey(), encoded);
            }
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, Json.stringify(Map.of("version", 1, "servers", servers)), StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            dirty = false;
            lastSaveMillis = now;
        } catch (IOException ignored) { }
    }

    @Override public synchronized void close() { saveQuietly(System.currentTimeMillis()); }
}
