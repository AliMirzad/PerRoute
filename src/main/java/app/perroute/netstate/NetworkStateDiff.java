package app.perroute.netstate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class NetworkStateDiff {
    private NetworkStateDiff() {}

    public enum Kind {
        BY_PERROUTE("Changed by PerRoute"),
        PUT_BACK("PerRoute's change was undone"),
        ELSEWHERE("Changed by Windows or another program"),
        UNCHANGED("Unchanged");

        private final String label;
        Kind(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Change(NetworkStateKeys.Key key, String before, String after, Kind kind) {
        public String beforeDisplay() { return NetworkStateKeys.display(key.id(), before); }
        public String afterDisplay() { return NetworkStateKeys.display(key.id(), after); }
    }

    public static List<Change> compare(NetworkSnapshot before, NetworkSnapshot after, boolean includeUnchanged) {
        List<Change> changes = new ArrayList<>();
        for (NetworkStateKeys.Key key : NetworkStateKeys.ALL) {
            if (!before.has(key.id()) && !after.has(key.id())) continue;
            String was = before.value(key.id());
            String now = after.value(key.id());
            Kind kind;
            if (Objects.equals(normalize(key.id(), was), normalize(key.id(), now))) kind = Kind.UNCHANGED;
            else {
                NetworkStateKeys.Owner ownerAfter = NetworkStateKeys.owner(key, now, after.values());
                NetworkStateKeys.Owner ownerBefore = NetworkStateKeys.owner(key, was, before.values());
                if (ownerAfter == NetworkStateKeys.Owner.PERROUTE) kind = Kind.BY_PERROUTE;
                else if (ownerAfter == NetworkStateKeys.Owner.OTHER) kind = Kind.ELSEWHERE;
                else if (ownerBefore == NetworkStateKeys.Owner.PERROUTE) kind = Kind.PUT_BACK;
                else kind = Kind.ELSEWHERE;
            }
            if (kind != Kind.UNCHANGED || includeUnchanged) changes.add(new Change(key, was, now, kind));
        }
        return changes;
    }

    private static String normalize(String id, String value) {
        String text = value == null ? "" : value.trim();
        if (id.equals(NetworkStateKeys.PROXY_ENABLE)) return NetworkStateKeys.enabled(text) ? "on" : "off";
        if (id.equals(NetworkStateKeys.ENGINE_APPS) || id.equals(NetworkStateKeys.CORE_PROCESSES)) return text.isEmpty() ? "0" : text;
        if (id.equals(NetworkStateKeys.PROXY_BACKUP) || id.equals(NetworkStateKeys.ENGINE_BACKUP) || id.equals(NetworkStateKeys.RUN_ONCE)
                || id.equals(NetworkStateKeys.SESSION) || id.equals(NetworkStateKeys.ENGINE_INSTALLED)) return NetworkStateKeys.yes(text) ? "yes" : "no";
        return text;
    }
}
