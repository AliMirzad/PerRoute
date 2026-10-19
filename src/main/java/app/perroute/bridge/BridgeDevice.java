package app.perroute.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

public record BridgeDevice(String id, String name, int slot, String user, String password, long createdAt) {
    public static final int SLOTS = 16;

    public BridgeDevice {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").trim();
        user = user == null ? "" : user.trim();
        password = password == null ? "" : password;
        if (slot < 0 || slot >= SLOTS) throw new IllegalArgumentException("Device slot out of range");
    }

    public String inboundTag() { return "perroute-bridge-" + slot; }

    public BridgeDevice withName(String value) { return new BridgeDevice(id, value, slot, user, password, createdAt); }

    Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("name", name);
        json.put("slot", slot);
        json.put("user", user);
        json.put("password", password);
        json.put("createdAt", createdAt);
        return json;
    }

    static BridgeDevice fromJson(Map<?, ?> json) {
        return new BridgeDevice(text(json.get("id")), text(json.get("name")), (int) number(json.get("slot")),
                text(json.get("user")), text(json.get("password")), number(json.get("createdAt")));
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (RuntimeException invalid) { return -1; }
    }
}
