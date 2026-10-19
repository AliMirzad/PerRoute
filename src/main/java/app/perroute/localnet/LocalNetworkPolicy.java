package app.perroute.localnet;

import app.perroute.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LocalNetworkPolicy {
    public static final List<String> LOCAL_NAME_RULES = List.of("domain:local", "domain:lan", "domain:home.arpa", "domain:internal",
            "domain:localdomain", "domain:home", "regexp:^[^.]+$");

    public enum Trust { TRUSTED, UNTRUSTED }

    public record Rules(boolean localNamesDirect, List<String> allowed, List<String> blocked) {
        public boolean blocking() { return !blocked.isEmpty(); }
    }

    private final Path file;
    private boolean localNamesDirect = true;
    private final Map<String, Trust> trust = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    private boolean loaded;

    public LocalNetworkPolicy(Path file) { this.file = file; }

    public synchronized boolean localNamesDirect() { load(); return localNamesDirect; }

    public synchronized void setLocalNamesDirect(boolean value) { load(); localNamesDirect = value; save(); }

    public synchronized Trust trust(String networkId) { load(); return trust.getOrDefault(networkId, Trust.TRUSTED); }

    public synchronized void setTrust(String networkId, String label, Trust value) {
        load();
        if (value == Trust.TRUSTED) { trust.remove(networkId); names.remove(networkId); }
        else { trust.put(networkId, value); names.put(networkId, label == null ? "" : label); }
        save();
    }

    public synchronized Map<String, String> untrusted() { load(); return Map.copyOf(names); }

    public synchronized void clear() {
        localNamesDirect = true;
        trust.clear();
        names.clear();
        loaded = true;
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
    }

    public synchronized Rules rules(List<LocalNetwork> networks) {
        load();
        Set<String> allowed = new LinkedHashSet<>();
        List<String> blocked = new ArrayList<>();
        for (LocalNetwork network : networks) {
            if (trust(network.id()) != Trust.UNTRUSTED) continue;
            blocked.add(network.cidr());
            if (!network.gateway().isBlank()) allowed.add(network.gateway() + "/32");
            network.dnsServers().forEach(server -> allowed.add(server + "/32"));
        }
        return new Rules(localNamesDirect, List.copyOf(allowed), List.copyOf(blocked));
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.isRegularFile(file)) return;
            Map<String, Object> root = Json.object(Files.readString(file, StandardCharsets.UTF_8));
            localNamesDirect = !Boolean.FALSE.equals(root.get("localNamesDirect"));
            if (root.get("untrusted") instanceof Map<?, ?> stored) {
                stored.forEach((id, label) -> { trust.put(String.valueOf(id), Trust.UNTRUSTED); names.put(String.valueOf(id), String.valueOf(label)); });
            }
        } catch (IOException | RuntimeException unreadable) {
            trust.clear();
            names.clear();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("format", 1);
            root.put("localNamesDirect", localNamesDirect);
            root.put("untrusted", new LinkedHashMap<>(names));
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, Json.stringify(root), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
