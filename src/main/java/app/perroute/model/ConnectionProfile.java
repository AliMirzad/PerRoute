package app.perroute.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ConnectionProfile(
        String id,
        String name,
        List<String> applicationPaths,
        String nodeKey,
        ConnectionMode mode,
        List<RouteRule> rules,
        boolean advancedRouting,
        Map<String, String> appRoutes,
        Set<String> autoApps
) {
    public ConnectionProfile {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        name = Objects.requireNonNullElse(name, "Profile").trim();
        applicationPaths = List.copyOf(applicationPaths == null ? List.of() : applicationPaths);
        nodeKey = Objects.requireNonNullElse(nodeKey, "");
        mode = Objects.requireNonNullElse(mode, ConnectionMode.SYSTEM_PROXY);
        rules = List.copyOf(rules == null ? List.of() : rules);
        appRoutes = Collections.unmodifiableMap(new LinkedHashMap<>(AppRoutes.normalized(appRoutes)));
        Set<String> normalizedAuto = new LinkedHashSet<>();
        if (autoApps != null) autoApps.forEach(path -> { String key = AppRoutes.normalize(path); if (!key.isBlank()) normalizedAuto.add(key); });
        autoApps = Collections.unmodifiableSet(normalizedAuto);
    }

    public ConnectionProfile(String id, String name, List<String> applicationPaths, String nodeKey,
                             ConnectionMode mode, List<RouteRule> rules, boolean advancedRouting, Map<String, String> appRoutes) {
        this(id, name, applicationPaths, nodeKey, mode, rules, advancedRouting, appRoutes, Set.of());
    }

    public ConnectionProfile(String id, String name, List<String> applicationPaths, String nodeKey,
                             ConnectionMode mode, List<RouteRule> rules) {
        this(id, name, applicationPaths, nodeKey, mode, rules, false, Map.of(), Set.of());
    }
}
