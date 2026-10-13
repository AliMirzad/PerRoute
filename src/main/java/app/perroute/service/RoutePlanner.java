package app.perroute.service;

import app.perroute.model.AppRoutes;
import app.perroute.model.ConnectionMode;
import app.perroute.model.ProxyNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves which server every selected application actually uses. */
public final class RoutePlanner {
    private RoutePlanner() {}

    public enum Problem { REMOVED }

    /** One distinct server in use, with the applications that follow it. */
    public record Route(ProxyNode node, boolean main, List<String> applications) {}

    /** An assignment that is in effect but cannot be honoured. */
    public record MissingRoute(String applicationPath, String nodeKey, String label, Problem problem) {}

    public record Plan(ProxyNode main, List<String> applications, boolean advancedEffective,
                       List<ProxyNode> applicationNodes, List<Route> routes, List<MissingRoute> missing) {
        public boolean ready() { return missing.isEmpty(); }

        /** The effective server of an application, or null when it is unselected or unresolved. */
        public ProxyNode nodeFor(String applicationPath) {
            String wanted = AppRoutes.normalize(applicationPath);
            for (int index = 0; index < applications.size(); index++) {
                if (AppRoutes.normalize(applications.get(index)).equals(wanted)) return applicationNodes.get(index);
            }
            return null;
        }

        public List<Route> customRoutes() { return routes.stream().filter(route -> !route.main()).toList(); }
    }

    /**
     * Assignments only take effect when Advanced routing is on and the mode is Smart per-app. In every other
     * case they are kept but ignored, so an unresolved one cannot block a standard connection.
     */
    public static Plan plan(List<ProxyNode> nodes, String mainKey, List<String> applications,
                            boolean advancedRouting, Map<String, String> appRoutes,
                            Map<String, String> labels, ConnectionMode mode) {
        List<ProxyNode> servers = nodes == null ? List.of() : nodes;
        List<String> apps = applications == null ? List.of() : List.copyOf(applications);
        ProxyNode main = servers.stream().filter(node -> node.key().equals(mainKey)).findFirst()
                .orElse(servers.isEmpty() ? null : servers.get(0));
        boolean effective = advancedRouting && mode == ConnectionMode.NATIVE_PER_APP;
        Map<String, String> assignments = effective ? AppRoutes.normalized(appRoutes) : Map.of();

        List<ProxyNode> applicationNodes = new ArrayList<>();
        List<MissingRoute> missing = new ArrayList<>();
        List<String> mainApplications = new ArrayList<>();
        Map<String, List<String>> customApplications = new LinkedHashMap<>();
        Map<String, ProxyNode> customNodes = new LinkedHashMap<>();
        for (String app : apps) {
            String assigned = assignments.get(AppRoutes.normalize(app));
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
        return new Plan(main, apps, effective, Collections.unmodifiableList(applicationNodes),
                List.copyOf(routes), List.copyOf(missing));
    }

    /** Every saved server can be a per-app choice, embedded Xray profiles included. */
    public static boolean selectableAsCustom(ProxyNode node) {
        return node != null;
    }
}
