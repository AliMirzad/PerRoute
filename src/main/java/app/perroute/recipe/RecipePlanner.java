package app.perroute.recipe;

import app.perroute.model.AppRoutes;
import app.perroute.model.DomainEntries;
import app.perroute.model.RouteRule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RecipePlanner {
    private RecipePlanner() {}

    public static final String RULE_PREFIX = "Recipe · ";

    public static String ruleName(Recipe recipe, Recipe.RuleEntry rule) {
        return RULE_PREFIX + recipe.name() + " · " + rule.name();
    }

    public enum RuleStatus {
        ADD("Added"),
        DUPLICATE("Already in your rules"),
        CONFLICT("Your rule decides first");

        private final String label;
        RuleStatus(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record RulePlan(Recipe.RuleEntry rule, RuleStatus status, String conflictsWith, String applicationPath) {}

    public record AppPlan(Recipe.AppEntry app, String path, String currentMode, boolean configured) {}

    public record SettingPlan(String key, String label, boolean from, boolean to) {}

    public record Plan(Recipe recipe, List<RulePlan> rules, List<AppPlan> apps, List<SettingPlan> settings) {
        public int rulesToAdd() { return (int) rules.stream().filter(rule -> rule.status() != RuleStatus.DUPLICATE).count(); }
        public List<AppPlan> missingApps() { return apps.stream().filter(app -> app.path() == null).toList(); }
        public boolean usesRoles() { return apps.stream().anyMatch(app -> app.app().route() == Recipe.Route.ROLE); }
    }

    public static Plan plan(Recipe recipe, List<RouteRule> rules, Collection<String> selected, Collection<String> known,
                            Map<String, String> modes, Map<String, Boolean> preferences) {
        List<AppPlan> apps = new ArrayList<>();
        for (Recipe.AppEntry app : recipe.apps()) {
            String path = find(app.executable(), selected, known);
            String mode = path == null ? "Not found" : modes.getOrDefault(AppRoutes.normalize(path), "Not selected");
            boolean selectedNow = path != null && selected.stream().anyMatch(candidate -> AppRoutes.normalize(candidate).equals(AppRoutes.normalize(path)));
            boolean differs = selectedNow && !mode.equalsIgnoreCase(describe(app.route()));
            apps.add(new AppPlan(app, path, mode, differs));
        }
        List<RulePlan> plans = new ArrayList<>();
        for (Recipe.RuleEntry entry : recipe.rules()) {
            String path = entry.executable().isBlank() ? "" : find(entry.executable(), selected, known);
            RuleStatus status = RuleStatus.ADD;
            String conflict = "";
            for (RouteRule existing : rules) {
                if (!existing.enabled() || existing.matchType() != entry.match()) continue;
                if (!sameApplication(existing.applicationPath(), entry.executable(), path)) continue;
                Set<String> overlap = overlap(entry.match(), existing.value(), entry.value());
                if (overlap.isEmpty()) continue;
                if (existing.action() == entry.action() && covers(entry.match(), existing.value(), entry.value())) { status = RuleStatus.DUPLICATE; conflict = ""; break; }
                if (existing.action() != entry.action() && status == RuleStatus.ADD) { status = RuleStatus.CONFLICT; conflict = existing.name(); }
            }
            plans.add(new RulePlan(entry, status, conflict, path == null ? "" : path));
        }
        List<SettingPlan> settings = new ArrayList<>();
        recipe.settings().forEach((key, value) -> settings.add(new SettingPlan(key, Recipe.SETTINGS.get(key),
                Boolean.TRUE.equals(preferences.get(key)), value)));
        return new Plan(recipe, List.copyOf(plans), List.copyOf(apps), List.copyOf(settings));
    }

    public static String describe(Recipe.Route route) {
        return switch (route) {
            case MAIN -> "Main";
            case ROLE -> "Manual";
            case AUTO -> "Auto";
        };
    }

    static String find(String executable, Collection<String> selected, Collection<String> known) {
        for (Collection<String> source : List.of(selected, known)) {
            for (String path : source) {
                Path file = safeFileName(path);
                if (file != null && file.toString().equalsIgnoreCase(executable)) return path;
            }
        }
        return null;
    }

    private static Path safeFileName(String path) {
        try { return Path.of(path).getFileName(); } catch (RuntimeException invalid) { return null; }
    }

    private static boolean sameApplication(String existingPath, String executable, String resolved) {
        if (existingPath.isBlank() || executable.isBlank()) return existingPath.isBlank() && executable.isBlank();
        if (resolved != null && !resolved.isBlank()) return AppRoutes.normalize(existingPath).equals(AppRoutes.normalize(resolved));
        Path name = safeFileName(existingPath);
        return name != null && name.toString().equalsIgnoreCase(executable);
    }

    private static Set<String> entries(RouteRule.MatchType match, String value) {
        String normalized = match == RouteRule.MatchType.DOMAIN ? DomainEntries.normalizeList(value) : value;
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(normalized.split(",")).map(part -> part.trim().toLowerCase(Locale.ROOT)).filter(part -> !part.isEmpty())
                .map(part -> match == RouteRule.MatchType.DOMAIN && part.startsWith("domain:") ? part.substring(7) : part)
                .forEach(result::add);
        return result;
    }

    private static Set<String> overlap(RouteRule.MatchType match, String existing, String incoming) {
        Set<String> result = new HashSet<>(entries(match, existing));
        result.retainAll(entries(match, incoming));
        return result;
    }

    private static boolean covers(RouteRule.MatchType match, String existing, String incoming) {
        return entries(match, existing).containsAll(entries(match, incoming));
    }

    public static Recipe export(String name, String description, String author, String createdWith,
                                List<Recipe.Role> roles, List<Recipe.AppEntry> apps, List<RouteRule> rules,
                                Map<String, Boolean> settings, Set<String> skippedRuleIds) {
        List<Recipe.RuleEntry> entries = new ArrayList<>();
        for (RouteRule rule : rules) {
            if (!rule.enabled() || skippedRuleIds.contains(rule.id())) continue;
            Path file = rule.applicationPath().isBlank() ? null : safeFileName(rule.applicationPath());
            entries.add(new Recipe.RuleEntry(stripPrefixes(rule.name()), file == null ? "" : file.toString(), rule.matchType(), rule.value(), rule.action()));
        }
        return new Recipe(Recipe.FORMAT, name, description, author, createdWith, roles, apps, entries, settings);
    }

    static String stripPrefixes(String name) {
        String text = Objects.requireNonNullElse(name, "");
        for (String prefix : List.of("Scenario · ", RULE_PREFIX)) {
            if (text.startsWith(prefix)) {
                String rest = text.substring(prefix.length());
                int separator = rest.indexOf(" · ");
                return separator >= 0 ? rest.substring(separator + 3) : rest;
            }
        }
        return text;
    }
}
