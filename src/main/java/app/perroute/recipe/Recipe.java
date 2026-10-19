package app.perroute.recipe;

import app.perroute.model.RouteRule;

import java.util.List;
import java.util.Map;

public record Recipe(int format, String name, String description, String author, String createdWith,
                     List<Role> roles, List<AppEntry> apps, List<RuleEntry> rules, Map<String, Boolean> settings) {

    public static final int FORMAT = 1;

    public Recipe {
        name = clean(name, 60);
        description = clean(description, 400);
        author = clean(author, 60);
        createdWith = clean(createdWith, 40);
        roles = List.copyOf(roles);
        apps = List.copyOf(apps);
        rules = List.copyOf(rules);
        settings = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(settings));
    }

    public record Role(String id, String label, String hint) {
        public Role {
            id = clean(id, 40);
            label = clean(label, 40);
            hint = clean(hint, 80);
        }
    }

    public enum Route { MAIN, ROLE, AUTO }

    public record AppEntry(String executable, String label, Route route, String role, String pool,
                           long downloadBitsPerSecond, long uploadBitsPerSecond) {
        public AppEntry {
            executable = clean(executable, 120);
            label = clean(label, 60);
            route = route == null ? Route.MAIN : route;
            role = clean(role, 40);
            pool = clean(pool, 70);
            downloadBitsPerSecond = Math.max(0, downloadBitsPerSecond);
            uploadBitsPerSecond = Math.max(0, uploadBitsPerSecond);
        }
    }

    public record RuleEntry(String name, String executable, RouteRule.MatchType match, String value, RouteRule.Action action) {
        public RuleEntry {
            name = clean(name, 80);
            executable = clean(executable, 120);
            value = value == null ? "" : value.trim();
        }
    }

    public static final Map<String, String> SETTINGS = settingLabels();

    private static Map<String, String> settingLabels() {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        labels.put("perAppKillSwitch", "Per-app kill switch");
        labels.put("autoReconnect", "Auto reconnect");
        labels.put("autoFailover", "Main server failover");
        labels.put("fastestOnConnect", "Fastest main server on connect");
        return java.util.Collections.unmodifiableMap(labels);
    }

    static String clean(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").trim();
        return text.length() > max ? text.substring(0, max).trim() : text;
    }
}
