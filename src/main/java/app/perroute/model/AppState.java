package app.perroute.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

public final class AppState {
    private final List<ProxyNode> nodes = new ArrayList<>();
    private final List<String> appPaths = new ArrayList<>();
    private final List<String> subscriptionUrls = new ArrayList<>();
    private final Map<String, String> nodeSources = new LinkedHashMap<>();
    private final Set<String> favoriteNodeKeys = new LinkedHashSet<>();
    private final Set<String> hiddenNodeKeys = new LinkedHashSet<>();
    private final Map<String, String> nodeTags = new LinkedHashMap<>();
    private final List<RouteRule> routeRules = new ArrayList<>();
    private final List<ConnectionProfile> profiles = new ArrayList<>();
    private final Map<String, String> appRoutes = new LinkedHashMap<>();
    private final Map<String, String> appRouteLabels = new LinkedHashMap<>();
    private String activeProfileId = "";
    private ConnectionMode connectionMode = ConnectionMode.SYSTEM_PROXY;
    private RoutingMode routingMode = RoutingMode.INCLUDE_SELECTED;
    private String selectedNodeKey = "";
    private boolean advancedRouting;

    public List<ProxyNode> nodes() { return nodes; }
    public List<String> appPaths() { return appPaths; }
    public List<String> subscriptionUrls() { return subscriptionUrls; }
    public Map<String, String> nodeSources() { return nodeSources; }
    public Set<String> favoriteNodeKeys() { return favoriteNodeKeys; }
    public Set<String> hiddenNodeKeys() { return hiddenNodeKeys; }
    public Map<String, String> nodeTags() { return nodeTags; }
    public List<RouteRule> routeRules() { return routeRules; }
    public List<ConnectionProfile> profiles() { return profiles; }
    /** Custom server per application: {@link AppRoutes#normalize normalized path} to server key. */
    public Map<String, String> appRoutes() { return appRoutes; }
    /** Last known display name of every assigned server, kept so a removed server can still be named. */
    public Map<String, String> appRouteLabels() { return appRouteLabels; }
    public String activeProfileId() { return activeProfileId; }
    public void setActiveProfileId(String value) { activeProfileId = value == null ? "" : value; }
    public ConnectionMode connectionMode() { return connectionMode; }
    public void setConnectionMode(ConnectionMode value) { connectionMode = value == null ? ConnectionMode.SYSTEM_PROXY : value; }
    public RoutingMode routingMode() { return routingMode; }
    public void setRoutingMode(RoutingMode value) { routingMode = value == null ? RoutingMode.INCLUDE_SELECTED : value; }
    public String selectedNodeKey() { return selectedNodeKey; }
    public void setSelectedNodeKey(String value) { selectedNodeKey = value == null ? "" : value; }
    public boolean advancedRouting() { return advancedRouting; }
    public void setAdvancedRouting(boolean value) { advancedRouting = value; }
}
