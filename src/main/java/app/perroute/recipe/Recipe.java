package app.perroute.recipe;

import app.perroute.model.RouteRule;

import java.util.List;
import java.util.Map;

/**
 * A shareable routing setup: rules, and which route each app takes, without anything private. Apps are named
 * by their executable file name only (no folders, which would reveal a user name), servers are replaced by
 * neutral slots ("roles") that whoever imports the recipe points at servers of their own, and no subscription
 * link, credential, address or server name is ever included.
 */
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
        // Insertion order is kept so a recipe always serializes the same way.
        settings = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(settings));
    }

    /** A server slot. {@code hint} describes what the slot is for, such as "low latency" or "streaming". */
    public record Role(String id, String label, String hint) {
        public Role {
            id = clean(id, 40);
            label = clean(label, 40);
            hint = clean(hint, 80);
        }
    }

    public enum Route { MAIN, ROLE, AUTO }

    /**
     * @param executable file name such as {@code chrome.exe}
     * @param role       the role id for {@link Route#ROLE}
     * @param pool       Auto's candidate pool for {@link Route#AUTO}: "all", "favorites" or "tag:name"
     * @param downloadBitsPerSecond 0 for unlimited
     */
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

    /** {@code executable} is blank for a rule that applies to every routed app. */
    public record RuleEntry(String name, String executable, RouteRule.MatchType match, String value, RouteRule.Action action) {
        public RuleEntry {
            name = clean(name, 80);
            executable = clean(executable, 120);
            value = value == null ? "" : value.trim();
        }
    }

    /** The automation switches a recipe may carry, with their labels. */
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
