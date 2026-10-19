package app.perroute.service;

import app.perroute.model.AppRoutes;
import app.perroute.model.ConnectionMode;
import app.perroute.model.ProxyNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoutePlanner {
    private RoutePlanner() {}

    public enum Problem { REMOVED, NO_SERVER }

    public static final String AUTO_ROUTE_PREFIX = "auto:";
    public static final String TEMPORARY_ROUTE_PREFIX = "temp:";

    public record Route(ProxyNode node, boolean main, List<String> applications, boolean auto, String id) {
        public Route(ProxyNode node, boolean main, List<String> applications) {
            this(node, main, applications, false, node.key());
        }

        public boolean temporary() { return id.startsWith(TEMPORARY_ROUTE_PREFIX); }
    }

    public record MissingRoute(String applicationPath, String nodeKey, String label, Problem problem) {}

    public record Plan(ProxyNode main, List<String> applications, boolean advancedEffective,
                       List<ProxyNode> applicationNodes, List<Route> routes, List<MissingRoute> missing) {
        public boolean ready() { return missing.isEmpty(); }

        public ProxyNode nodeFor(String applicationPath) {
            String wanted = AppRoutes.normalize(applicationPath);
            for (int index = 0; index < applications.size(); index++) {
                if (AppRoutes.normalize(applications.get(index)).equals(wanted)) return applicationNodes.get(index);
            }
            return null;
        }

        public Route routeFor(String applicationPath) {
            String wanted = AppRoutes.normalize(applicationPath);
            for (Route route : routes) {
                for (String app : route.applications()) if (AppRoutes.normalize(app).equals(wanted)) return route;
            }
            return null;
        }

        public List<Route> customRoutes() { return routes.stream().filter(route -> !route.main()).toList(); }
    }

    public static Plan plan(List<ProxyNode> nodes, String mainKey, List<String> applications,
                            boolean advancedRouting, Map<String, String> appRoutes,
                            Map<String, String> labels, ConnectionMode mode) {
        return plan(nodes, mainKey, applications, advancedRouting, appRoutes, labels, mode, Map.of());
    }

    public static Plan plan(List<ProxyNode> nodes, String mainKey, List<String> applications,
                            boolean advancedRouting, Map<String, String> appRoutes,
                            Map<String, String> labels, ConnectionMode mode, Map<String, String> autoChoices) {
        return plan(nodes, mainKey, applications, advancedRouting, appRoutes, labels, mode, autoChoices, Map.of());
    }

    public static Plan plan(List<ProxyNode> nodes, String mainKey, List<String> applications,
                            boolean advancedRouting, Map<String, String> appRoutes,
                            Map<String, String> labels, ConnectionMode mode, Map<String, String> autoChoices,
                            Map<String, String> temporary) {
        List<ProxyNode> servers = nodes == null ? List.of() : nodes;
        List<String> apps = applications == null ? List.of() : List.copyOf(applications);
        ProxyNode main = servers.stream().filter(node -> node.key().equals(mainKey)).findFirst()
                .orElse(servers.isEmpty() ? null : servers.get(0));
        boolean effective = advancedRouting && mode == ConnectionMode.NATIVE_PER_APP;
        Map<String, String> assignments = effective ? AppRoutes.normalized(appRoutes) : Map.of();
        Map<String, String> auto = new LinkedHashMap<>();
        if (effective && autoChoices != null) autoChoices.forEach((path, key) -> auto.put(AppRoutes.normalize(path), key == null ? "" : key));

        List<ProxyNode> applicationNodes = new ArrayList<>();
        List<MissingRoute> missing = new ArrayList<>();
        List<String> mainApplications = new ArrayList<>();
        Map<String, List<String>> customApplications = new LinkedHashMap<>();
        Map<String, ProxyNode> customNodes = new LinkedHashMap<>();
        List<Route> autoRoutes = new ArrayList<>();
        Map<String, String> temporaries = new LinkedHashMap<>();
        if (mode == ConnectionMode.NATIVE_PER_APP && temporary != null) temporary.forEach((path, key) -> temporaries.put(AppRoutes.normalize(path), key));
        for (String app : apps) {
            String normalized = AppRoutes.normalize(app);
            String temporaryKey = temporaries.get(normalized);
            ProxyNode temporaryNode = temporaryKey == null ? null : servers.stream().filter(n -> n.key().equals(temporaryKey)).findFirst().orElse(null);
            if (temporaryNode != null) {
                applicationNodes.add(temporaryNode);
                autoRoutes.add(new Route(temporaryNode, false, List.of(app), false, TEMPORARY_ROUTE_PREFIX + normalized));
                continue;
            }
            if (auto.containsKey(normalized)) {
                String chosen = auto.get(normalized);
                ProxyNode node = chosen.isBlank() ? null : servers.stream().filter(n -> n.key().equals(chosen)).findFirst().orElse(null);
                if (node == null) {
                    applicationNodes.add(null);
                    missing.add(new MissingRoute(app, chosen, labels == null ? "" : labels.getOrDefault(chosen, ""),
                            chosen.isBlank() ? Problem.NO_SERVER : Problem.REMOVED));
                } else {
                    applicationNodes.add(node);
                    autoRoutes.add(new Route(node, false, List.of(app), true, AUTO_ROUTE_PREFIX + normalized));
                }
                continue;
            }
            String assigned = assignments.get(normalized);
            if (assigned == null || (main != null && assigned.equals(main.key()))) {
                applicationNodes.add(main);
                mainApplications.add(app);
                continue;
            }
            ProxyNode custom = servers.stream().filter(node -> node.key().equals(assigned)).findFirst().orElse(null);
            String label = labels == null ? "" : labels.getOrDefault(assigned, "");
            if (custom == null) {
                applicationNodes.add(null);
                missing.add(new MissingRoute(app, assigned, label, Problem.REMOVED));
            } else {
                applicationNodes.add(custom);
                customNodes.putIfAbsent(assigned, custom);
                customApplications.computeIfAbsent(assigned, ignored -> new ArrayList<>()).add(app);
            }
        }

        List<Route> routes = new ArrayList<>();
        if (main != null) routes.add(new Route(main, true, List.copyOf(mainApplications)));
        customNodes.forEach((key, node) -> routes.add(new Route(node, false, List.copyOf(customApplications.get(key)))));
        routes.addAll(autoRoutes);
        return new Plan(main, apps, effective, Collections.unmodifiableList(applicationNodes),
                List.copyOf(routes), List.copyOf(missing));
    }

    public static boolean selectableAsCustom(ProxyNode node) {
        return node != null;
    }
}
