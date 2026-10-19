package app.perroute.netstate;

import app.perroute.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The recent network snapshots, newest last, in a small JSON file next to PerRoute's state. It is readable by
 * the standalone recovery tool, holds no credentials, and keeps at most {@link #LIMIT} snapshots.
 */
public final class NetworkHistoryStore {
    public static final int LIMIT = 30;
    private final Path file;
    private final List<NetworkSnapshot> snapshots = new ArrayList<>();
    private boolean loaded;

    public NetworkHistoryStore(Path file) { this.file = file; }

    public synchronized List<NetworkSnapshot> all() {
        load();
        return List.copyOf(snapshots);
    }

    public synchronized NetworkSnapshot add(NetworkSnapshot.Reason reason, String version, Map<String, String> values, long now) {
        load();
        long id = Math.max(now, snapshots.isEmpty() ? 0 : snapshots.get(snapshots.size() - 1).id() + 1);
        NetworkSnapshot snapshot = new NetworkSnapshot(id, now, reason, version, values);
        snapshots.add(snapshot);
        while (snapshots.size() > LIMIT) {
            // The newest clean baseline is never dropped while newer snapshots exist, so a restore always has one.
            NetworkSnapshot keep = baselineLocked();
            int index = 0;
            while (index < snapshots.size() - 1 && snapshots.get(index) == keep) index++;
            snapshots.remove(index);
        }
        save();
        return snapshot;
    }

    public synchronized NetworkSnapshot find(long id) {
        load();
        return snapshots.stream().filter(snapshot -> snapshot.id() == id).findFirst().orElse(null);
    }

    /** The newest snapshot taken while PerRoute had changed nothing: before a connection or at a clean start. */
    public synchronized NetworkSnapshot baseline() {
        load();
        return baselineLocked();
    }

    private NetworkSnapshot baselineLocked() {
        String perRoute = NetworkStateKeys.perRouteServer();
        return snapshots.stream()
                .filter(snapshot -> snapshot.reason() == NetworkSnapshot.Reason.BEFORE_CONNECT || snapshot.reason() == NetworkSnapshot.Reason.STARTUP
                        || snapshot.reason() == NetworkSnapshot.Reason.AFTER_DISCONNECT)
                .filter(snapshot -> perRoute.isEmpty() || !NetworkStateKeys.perRouteProxy(snapshot.values(), perRoute))
                .filter(snapshot -> !NetworkStateKeys.CONFIG_PERROUTE.equals(snapshot.value(NetworkStateKeys.ENGINE_CONFIG))
                        || !NetworkStateKeys.RUNNING.equals(snapshot.value(NetworkStateKeys.ENGINE_SERVICE)))
                .max(Comparator.comparingLong(NetworkSnapshot::id)).orElse(null);
    }

    public synchronized void clear() {
        snapshots.clear();
        loaded = true;
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.isRegularFile(file)) return;
            Map<String, Object> root = Json.object(Files.readString(file, StandardCharsets.UTF_8));
            if (root.get("snapshots") instanceof List<?> list) {
                for (Object raw : list) {
                    try { snapshots.add(NetworkSnapshot.fromJson(raw)); } catch (RuntimeException ignored) {}
                }
            }
            snapshots.sort(Comparator.comparingLong(NetworkSnapshot::id));
        } catch (IOException | RuntimeException unreadable) {
            snapshots.clear();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, Json.stringify(Map.of("format", 1, "snapshots", snapshots.stream().map(NetworkSnapshot::toJson).toList())),
                    StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ignored) {
        }
    }
}
