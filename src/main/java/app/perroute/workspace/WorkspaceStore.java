package app.perroute.workspace;

import app.perroute.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkspaceStore {
    private final Path file;
    private final List<Workspace> workspaces = new ArrayList<>();
    private boolean loaded;

    public WorkspaceStore(Path file) { this.file = file; }

    public synchronized List<Workspace> all() {
        load();
        return List.copyOf(workspaces);
    }

    public synchronized Workspace find(String id) {
        load();
        return workspaces.stream().filter(workspace -> workspace.id().equals(id)).findFirst().orElse(null);
    }

    public synchronized Workspace create(String name, String browser, String nodeKey, long now) {
        load();
        if (workspaces.size() >= Workspace.SLOTS) throw new IllegalStateException("PerRoute supports up to " + Workspace.SLOTS + " workspaces.");
        int slot = 0;
        while (true) {
            int candidate = slot;
            if (workspaces.stream().noneMatch(workspace -> workspace.slot() == candidate)) break;
            slot++;
        }
        Workspace workspace = new Workspace(UUID.randomUUID().toString(), name, browser, nodeKey, slot, now);
        workspaces.add(workspace);
        save();
        return workspace;
    }

    public synchronized void update(Workspace workspace) {
        load();
        for (int index = 0; index < workspaces.size(); index++) {
            if (workspaces.get(index).id().equals(workspace.id())) { workspaces.set(index, workspace); save(); return; }
        }
        throw new IllegalArgumentException("That workspace no longer exists.");
    }

    public synchronized boolean remove(String id) {
        load();
        boolean removed = workspaces.removeIf(workspace -> workspace.id().equals(id));
        if (removed) save();
        return removed;
    }

    public synchronized void clear() {
        workspaces.clear();
        loaded = true;
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.isRegularFile(file)) return;
            Map<String, Object> root = Json.object(Files.readString(file, StandardCharsets.UTF_8));
            if (root.get("workspaces") instanceof List<?> list) {
                for (Object raw : list) {
                    if (!(raw instanceof Map<?, ?> json)) continue;
                    try {
                        Workspace workspace = Workspace.fromJson(json);
                        if (!workspace.id().isBlank() && workspaces.stream().noneMatch(other -> other.slot() == workspace.slot())) workspaces.add(workspace);
                    } catch (RuntimeException ignored) {}
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            workspaces.clear();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, Json.stringify(Map.of("format", 1, "workspaces", workspaces.stream().map(Workspace::toJson).toList())), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
