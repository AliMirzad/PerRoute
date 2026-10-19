package app.perroute.netstate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record NetworkSnapshot(long id, long takenAt, Reason reason, String version, Map<String, String> values) {
    public enum Reason {
        STARTUP("When PerRoute started"),
        BEFORE_CONNECT("Before connecting"),
        CONNECTED("While connected"),
        AFTER_DISCONNECT("After disconnecting"),
        MANUAL("Taken by you"),
        BEFORE_RESTORE("Before a restore");

        private final String label;
        Reason(String label) { this.label = label; }
        public String label() { return label; }
    }

    public NetworkSnapshot {
        values = Map.copyOf(new LinkedHashMap<>(values));
    }

    public String value(String key) { return values.getOrDefault(key, ""); }

    public boolean has(String key) { return values.containsKey(key); }

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("takenAt", takenAt);
        json.put("reason", reason.name());
        json.put("version", version);
        json.put("values", new LinkedHashMap<>(values));
        return json;
    }

    @SuppressWarnings("unchecked")
    public static NetworkSnapshot fromJson(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("A snapshot must be an object");
        Reason reason;
        try { reason = Reason.valueOf(String.valueOf(map.get("reason"))); }
        catch (RuntimeException unknown) { reason = Reason.MANUAL; }
        Map<String, String> values = new LinkedHashMap<>();
        if (map.get("values") instanceof Map<?, ?> stored) stored.forEach((key, value) -> values.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
        return new NetworkSnapshot(number(map.get("id")), number(map.get("takenAt")), reason,
                map.get("version") == null ? "" : String.valueOf(map.get("version")), values);
    }

    static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (RuntimeException invalid) { return 0L; }
    }

    public List<NetworkStateItem> items() {
        List<NetworkStateItem> items = new ArrayList<>();
        for (NetworkStateKeys.Key key : NetworkStateKeys.ALL) {
            if (!values.containsKey(key.id())) continue;
            String value = values.get(key.id());
            items.add(new NetworkStateItem(key, value, NetworkStateKeys.owner(key, value, values)));
        }
        return items;
    }
}
