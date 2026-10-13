package app.perroute.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ConnectionProfile(
        String id,
        String name,
        List<String> applicationPaths,
        String nodeKey,
        ConnectionMode mode,
        List<RouteRule> rules
) {
    public ConnectionProfile {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        name = Objects.requireNonNullElse(name, "Profile").trim();
        applicationPaths = List.copyOf(applicationPaths == null ? List.of() : applicationPaths);
        nodeKey = Objects.requireNonNullElse(nodeKey, "");
        mode = Objects.requireNonNullElse(mode, ConnectionMode.SYSTEM_PROXY);
        rules = List.copyOf(rules == null ? List.of() : rules);
    }
}
