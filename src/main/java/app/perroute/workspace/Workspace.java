package app.perroute.workspace;

import java.util.LinkedHashMap;
import java.util.Map;

public record Workspace(String id, String name, String browserPath, String nodeKey, int slot, long createdAt) {
    public static final int SLOTS = 30;

    public Workspace {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").trim();
        browserPath = browserPath == null ? "" : browserPath.trim();
        nodeKey = nodeKey == null ? "" : nodeKey.trim();
        if (slot < 0 || slot >= SLOTS) throw new IllegalArgumentException("Workspace slot out of range");
    }

    public boolean followsMain() { return nodeKey.isBlank(); }

    public Workspace withName(String value) { return new Workspace(id, value, browserPath, nodeKey, slot, createdAt); }
    public Workspace withNode(String value) { return new Workspace(id, name, browserPath, value, slot, createdAt); }
    public Workspace withBrowser(String value) { return new Workspace(id, name, value, nodeKey, slot, createdAt); }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("name", name);
        json.put("browser", browserPath);
        json.put("node", nodeKey);
        json.put("slot", slot);
        json.put("createdAt", createdAt);
        return json;
    }

    public static Workspace fromJson(Map<?, ?> json) {
        return new Workspace(text(json.get("id")), text(json.get("name")), text(json.get("browser")), text(json.get("node")),
                (int) number(json.get("slot")), number(json.get("createdAt")));
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (RuntimeException invalid) { return -1; }
    }
}
