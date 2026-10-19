package app.perroute.bridge;

import app.perroute.persistence.ProtectedFiles;
import app.perroute.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class BridgeStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final Path file;
    private final List<BridgeDevice> devices = new ArrayList<>();
    private boolean loaded;

    public BridgeStore(Path file) { this.file = file; }

    public synchronized List<BridgeDevice> all() {
        load();
        return List.copyOf(devices);
    }

    public synchronized BridgeDevice find(String id) {
        load();
        return devices.stream().filter(device -> device.id().equals(id)).findFirst().orElse(null);
    }

    public synchronized BridgeDevice create(String name, long now) throws IOException {
        load();
        if (devices.size() >= BridgeDevice.SLOTS) throw new IllegalStateException("PerRoute can share with up to " + BridgeDevice.SLOTS + " devices.");
        int slot = 0;
        while (true) {
            int candidate = slot;
            if (devices.stream().noneMatch(device -> device.slot() == candidate)) break;
            slot++;
        }
        BridgeDevice device = new BridgeDevice(UUID.randomUUID().toString(), name, slot, userFor(name, slot), randomText(20), now);
        devices.add(device);
        save();
        return device;
    }

    public synchronized void rename(String id, String name) throws IOException {
        load();
        for (int index = 0; index < devices.size(); index++) {
            if (devices.get(index).id().equals(id)) { devices.set(index, devices.get(index).withName(name)); save(); return; }
        }
        throw new IllegalArgumentException("That device was removed.");
    }

    public synchronized boolean remove(String id) throws IOException {
        load();
        boolean removed = devices.removeIf(device -> device.id().equals(id));
        if (removed) save();
        return removed;
    }

    public synchronized void clear() {
        devices.clear();
        loaded = true;
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
    }

    static String userFor(String name, int slot) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.length() > 16) base = base.substring(0, 16).replaceAll("-+$", "");
        return (base.isBlank() ? "device" : base) + "-" + (slot + 1);
    }

    static String randomText(int length) {
        StringBuilder text = new StringBuilder(length);
        for (int index = 0; index < length; index++) text.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return text.toString();
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.isRegularFile(file)) return;
            Map<String, Object> root = Json.object(new String(ProtectedFiles.read(file), StandardCharsets.UTF_8));
            if (root.get("devices") instanceof List<?> list) {
                for (Object raw : list) {
                    if (!(raw instanceof Map<?, ?> json)) continue;
                    try {
                        BridgeDevice device = BridgeDevice.fromJson(json);
                        boolean usable = !device.id().isBlank() && !device.user().isBlank() && device.password().length() >= 12;
                        if (usable && devices.stream().noneMatch(other -> other.slot() == device.slot())) devices.add(device);
                    } catch (RuntimeException ignored) {}
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            devices.clear();
        }
    }

    private void save() throws IOException {
        String json = Json.stringify(Map.of("format", 1, "devices", devices.stream().map(BridgeDevice::toJson).toList()));
        ProtectedFiles.write(file, json.getBytes(StandardCharsets.UTF_8));
    }
}
