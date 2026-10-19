package app.perroute.recipe;

import app.perroute.model.RouteRule;
import app.perroute.util.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class RecipeCodec {
    private RecipeCodec() {}

    public static final String TEXT_PREFIX = "perroute-recipe:";
    public static final String FILE_EXTENSION = ".perroute-recipe";
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_RULES = 500;
    public static final int MAX_APPS = 200;
    public static final int MAX_ROLES = 12;

    public static final class RecipeException extends Exception {
        public RecipeException(String message) { super(message); }
    }

    private static final Pattern EXECUTABLE = Pattern.compile("(?i)[^\\\\/:*?\"<>|]{1,120}\\.exe");
    private static final Pattern DOMAIN = Pattern.compile("(?i)((full|domain|keyword):)?[a-z0-9*_-]+(\\.[a-z0-9*_-]+)*\\.?");
    private static final Pattern GEO_SITE = Pattern.compile("(?i)geosite:[a-z0-9@!_-]{1,60}");
    private static final Pattern GEO_IP = Pattern.compile("(?i)geoip:!?[a-z0-9_-]{1,40}");
    private static final Pattern IPV4 = Pattern.compile("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(/([0-9]|[12]\\d|3[0-2]))?");
    private static final Pattern IPV6 = Pattern.compile("(?i)[0-9a-f:]{2,39}(/(\\d|[1-9]\\d|1[01]\\d|12[0-8]))?");
    private static final Pattern PORTS = Pattern.compile("\\d{1,5}(-\\d{1,5})?(,\\d{1,5}(-\\d{1,5})?)*");

    public static String toJson(Recipe recipe) {
        Map<String, Object> body = body(recipe);
        Map<String, Object> root = new LinkedHashMap<>(body);
        root.put("checksum", checksum(body));
        return Json.stringify(root) + System.lineSeparator();
    }

    public static String toText(Recipe recipe) {
        return TEXT_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(toJson(recipe).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> body(Recipe recipe) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("perrouteRecipe", Recipe.FORMAT);
        body.put("name", recipe.name());
        body.put("description", recipe.description());
        body.put("author", recipe.author());
        body.put("createdWith", recipe.createdWith());
        List<Object> roles = new ArrayList<>();
        for (Recipe.Role role : recipe.roles()) roles.add(new LinkedHashMap<>(Map.of("id", role.id(), "label", role.label(), "hint", role.hint())));
        body.put("roles", roles);
        List<Object> apps = new ArrayList<>();
        for (Recipe.AppEntry app : recipe.apps()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("executable", app.executable());
            entry.put("label", app.label());
            entry.put("route", app.route().name().toLowerCase(Locale.ROOT));
            if (app.route() == Recipe.Route.ROLE) entry.put("role", app.role());
            if (app.route() == Recipe.Route.AUTO) entry.put("pool", app.pool().isBlank() ? "all" : app.pool());
            if (app.downloadBitsPerSecond() > 0) entry.put("downloadBps", app.downloadBitsPerSecond());
            if (app.uploadBitsPerSecond() > 0) entry.put("uploadBps", app.uploadBitsPerSecond());
            apps.add(entry);
        }
        body.put("apps", apps);
        List<Object> rules = new ArrayList<>();
        for (Recipe.RuleEntry rule : recipe.rules()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", rule.name());
            if (!rule.executable().isBlank()) entry.put("executable", rule.executable());
            entry.put("match", rule.match().name().toLowerCase(Locale.ROOT));
            entry.put("value", rule.value());
            entry.put("action", rule.action().name().toLowerCase(Locale.ROOT));
            rules.add(entry);
        }
        body.put("rules", rules);
        body.put("settings", new LinkedHashMap<>(recipe.settings()));
        return body;
    }

    static String checksum(Map<String, Object> body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Json.stringify(body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static Recipe parse(String input) throws RecipeException {
        if (input == null || input.isBlank()) throw new RecipeException("Paste a recipe or choose a recipe file.");
        if (input.length() > MAX_BYTES * 2) throw new RecipeException("This recipe is too large.");
        String text = input.trim();
        int prefix = text.indexOf(TEXT_PREFIX);
        if (prefix >= 0) {
            String encoded = text.substring(prefix + TEXT_PREFIX.length()).split("\\s", 2)[0];
            try { text = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8); }
            catch (IllegalArgumentException invalid) { throw new RecipeException("The recipe text is incomplete or damaged. Copy all of it again."); }
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new RecipeException("This recipe is too large.");
        Map<String, Object> root;
        try { root = Json.object(text); }
        catch (RuntimeException invalid) { throw new RecipeException("This is not a PerRoute recipe."); }
        if (!(root.get("perrouteRecipe") instanceof Number format)) throw new RecipeException("This is not a PerRoute recipe.");
        if (format.intValue() > Recipe.FORMAT) throw new RecipeException("This recipe was made with a newer PerRoute. Update PerRoute to open it.");
        if (format.intValue() < 1) throw new RecipeException("This recipe's format is not supported.");
        Object stored = root.remove("checksum");
        if (stored != null && !checksum(root).equals(String.valueOf(stored))) {
            throw new RecipeException("This recipe was changed or damaged after it was made. Ask for a fresh copy.");
        }

        List<Recipe.Role> roles = new ArrayList<>();
        for (Map<?, ?> raw : objects(root.get("roles"), MAX_ROLES, "roles")) {
            String id = string(raw.get("id"));
            if (!id.matches("[a-z0-9-]{1,40}")) throw new RecipeException("A server slot has an invalid name.");
            if (roles.stream().anyMatch(role -> role.id().equals(id))) throw new RecipeException("Two server slots have the same name.");
            roles.add(new Recipe.Role(id, string(raw.get("label")).isBlank() ? id : string(raw.get("label")), string(raw.get("hint"))));
        }
        List<Recipe.AppEntry> apps = new ArrayList<>();
        for (Map<?, ?> raw : objects(root.get("apps"), MAX_APPS, "apps")) {
            String executable = executable(raw.get("executable"));
            Recipe.Route route = switch (string(raw.get("route")).toLowerCase(Locale.ROOT)) {
                case "role" -> Recipe.Route.ROLE;
                case "auto" -> Recipe.Route.AUTO;
                case "main", "" -> Recipe.Route.MAIN;
                default -> throw new RecipeException(executable + " has a route this PerRoute does not know.");
            };
            String role = string(raw.get("role"));
            if (route == Recipe.Route.ROLE && roles.stream().noneMatch(known -> known.id().equals(role))) {
                throw new RecipeException(executable + " uses a server slot the recipe does not define.");
            }
            String pool = string(raw.get("pool"));
            if (route == Recipe.Route.AUTO && !(pool.isBlank() || pool.equals("all") || pool.equals("favorites") || pool.matches("tag:[^\\p{Cntrl}]{1,60}"))) {
                throw new RecipeException(executable + " has an Auto pool this PerRoute does not know.");
            }
            if (apps.stream().anyMatch(app -> app.executable().equalsIgnoreCase(executable))) continue;
            apps.add(new Recipe.AppEntry(executable, string(raw.get("label")), route, route == Recipe.Route.ROLE ? role : "",
                    route == Recipe.Route.AUTO ? (pool.isBlank() ? "all" : pool) : "", bits(raw.get("downloadBps")), bits(raw.get("uploadBps"))));
        }
        List<Recipe.RuleEntry> rules = new ArrayList<>();
        for (Map<?, ?> raw : objects(root.get("rules"), MAX_RULES, "rules")) {
            RouteRule.MatchType match;
            RouteRule.Action action;
            try {
                match = RouteRule.MatchType.valueOf(string(raw.get("match")).toUpperCase(Locale.ROOT));
                action = RouteRule.Action.valueOf(string(raw.get("action")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new RecipeException("A rule uses a match or action this PerRoute does not know.");
            }
            String executable = raw.get("executable") == null || string(raw.get("executable")).isBlank() ? "" : executable(raw.get("executable"));
            String value = validValue(match, string(raw.get("value")));
            rules.add(new Recipe.RuleEntry(string(raw.get("name")).isBlank() ? "Recipe rule" : string(raw.get("name")), executable, match, value, action));
        }
        Map<String, Boolean> settings = new LinkedHashMap<>();
        if (root.get("settings") instanceof Map<?, ?> stored2) {
            stored2.forEach((key, value) -> {
                if (Recipe.SETTINGS.containsKey(String.valueOf(key)) && value instanceof Boolean flag) settings.put(String.valueOf(key), flag);
            });
        }
        if (rules.isEmpty() && apps.isEmpty() && settings.isEmpty()) throw new RecipeException("This recipe is empty.");
        String name = string(root.get("name"));
        return new Recipe(format.intValue(), name.isBlank() ? "Untitled recipe" : name, string(root.get("description")),
                string(root.get("author")), string(root.get("createdWith")), roles, apps, rules, settings);
    }

    public static String validValue(RouteRule.MatchType match, String value) throws RecipeException {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > 4_000) throw new RecipeException("A rule has an empty or very long value.");
        List<String> parts = new ArrayList<>();
        for (String raw : text.split(",")) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            boolean valid = switch (match) {
                case DOMAIN -> GEO_SITE.matcher(part).matches() || DOMAIN.matcher(part).matches() || validRegexp(part);
                case IP -> GEO_IP.matcher(part).matches() || IPV4.matcher(part).matches() || (part.contains(":") && IPV6.matcher(part).matches());
                case PORT -> PORTS.matcher(part).matches() && portsInRange(part);
            };
            if (!valid) throw new RecipeException("The rule value \"" + shorten(part) + "\" is not a valid " + match.name().toLowerCase(Locale.ROOT) + " entry.");
            parts.add(part);
        }
        if (parts.isEmpty()) throw new RecipeException("A rule has an empty value.");
        return String.join(", ", parts);
    }

    private static boolean validRegexp(String part) {
        if (!part.regionMatches(true, 0, "regexp:", 0, 7) || part.length() > 200) return false;
        try { Pattern.compile(part.substring(7)); return true; } catch (RuntimeException invalid) { return false; }
    }

    private static boolean portsInRange(String part) {
        for (String range : part.split(",")) {
            String[] bounds = range.split("-");
            int low = Integer.parseInt(bounds[0]);
            int high = bounds.length > 1 ? Integer.parseInt(bounds[1]) : low;
            if (low < 1 || high > 65535 || low > high) return false;
        }
        return true;
    }

    private static String executable(Object raw) throws RecipeException {
        String value = string(raw);
        if (!EXECUTABLE.matcher(value).matches()) throw new RecipeException("\"" + shorten(value) + "\" is not an executable file name.");
        return value;
    }

    private static List<Map<?, ?>> objects(Object raw, int limit, String what) throws RecipeException {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new RecipeException("The recipe's " + what + " are not readable.");
        if (list.size() > limit) throw new RecipeException("The recipe has more " + what + " than PerRoute accepts (" + limit + ").");
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new RecipeException("The recipe's " + what + " are not readable.");
            result.add(map);
        }
        return result;
    }

    private static long bits(Object raw) {
        if (!(raw instanceof Number number)) return 0;
        long value = number.longValue();
        return value <= 0 ? 0 : Math.min(value, app.perroute.model.BandwidthPolicy.MAX_BITS_PER_SECOND);
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private static String shorten(String value) { return value.length() > 40 ? value.substring(0, 40) + "…" : value; }
}
