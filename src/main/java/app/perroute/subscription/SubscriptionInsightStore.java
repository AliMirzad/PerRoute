package app.perroute.subscription;

import app.perroute.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class SubscriptionInsightStore {
    private final Path file;
    private final Map<String, SubscriptionInsight> insights = new LinkedHashMap<>();
    private boolean loaded;

    public SubscriptionInsightStore(Path file) { this.file = file; }

    public static String keyOf(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public synchronized SubscriptionInsight get(String url) {
        load();
        return insights.get(keyOf(url));
    }

    public synchronized void put(String url, SubscriptionInsight insight) {
        load();
        insights.put(keyOf(url), insight);
        save();
    }

    public synchronized void remove(String url) {
        load();
        if (insights.remove(keyOf(url)) != null) save();
    }

    public synchronized void retain(java.util.Collection<String> urls) {
        load();
        java.util.Set<String> keep = new java.util.HashSet<>();
        urls.forEach(url -> keep.add(keyOf(url)));
        if (insights.keySet().retainAll(keep)) save();
    }

    public synchronized void clear() {
        insights.clear();
        loaded = true;
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.isRegularFile(file)) return;
            Map<String, Object> root = Json.object(Files.readString(file, StandardCharsets.UTF_8));
            if (root.get("subscriptions") instanceof Map<?, ?> stored) {
                stored.forEach((key, value) -> {
                    if (value instanceof Map<?, ?> json) {
                        try { insights.put(String.valueOf(key), SubscriptionInsight.fromJson(json)); } catch (RuntimeException ignored) {}
                    }
                });
            }
        } catch (IOException | RuntimeException unreadable) {
            insights.clear();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Map<String, Object> stored = new LinkedHashMap<>();
            insights.forEach((key, value) -> stored.put(key, value.toJson()));
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, Json.stringify(Map.of("format", 1, "subscriptions", stored)), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
