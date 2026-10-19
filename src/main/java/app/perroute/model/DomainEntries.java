package app.perroute.model;

import java.net.IDN;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class DomainEntries {
    private DomainEntries() {}

    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*://", Pattern.CASE_INSENSITIVE);
    private static final Pattern PORT = Pattern.compile(":\\d{1,5}$");
    private static final Set<String> VERBATIM = Set.of("keyword", "regexp", "geosite", "ext", "dotless");

    public static String normalizeList(String text) {
        if (text == null) return "";
        Set<String> entries = new LinkedHashSet<>();
        for (String raw : text.split("[,\\s]+")) {
            String entry = normalize(raw);
            if (!entry.isEmpty()) entries.add(entry);
        }
        return String.join(", ", entries);
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String text = raw.trim().replaceAll("^[\"'<]+|[\"'>]+$", "");
        if (text.isEmpty()) return "";
        if (!SCHEME.matcher(text).find()) {
            int colon = text.indexOf(':');
            if (colon > 0) {
                String prefix = text.substring(0, colon).toLowerCase(Locale.ROOT);
                String rest = text.substring(colon + 1).trim();
                if (VERBATIM.contains(prefix)) return rest.isEmpty() ? "" : prefix + ":" + rest;
                if (prefix.equals("full")) { String host = host(rest, false); return host.isEmpty() ? "" : "full:" + host; }
                if (prefix.equals("domain")) return host(rest, true);
            }
        }
        return host(text, true);
    }

    private static String host(String value, boolean wholeSite) {
        String text = SCHEME.matcher(value.trim()).replaceFirst("");
        int end = text.length();
        for (char stop : new char[]{'/', '?', '#', '\\'}) {
            int index = text.indexOf(stop);
            if (index >= 0) end = Math.min(end, index);
        }
        text = text.substring(0, end);
        int at = text.lastIndexOf('@');
        if (at >= 0) text = text.substring(at + 1);
        text = PORT.matcher(text).replaceFirst("");
        text = text.replaceAll("^[*.]+", "").replaceAll("\\.+$", "").toLowerCase(Locale.ROOT);
        if (wholeSite && text.startsWith("www.") && text.indexOf('.', 4) > 0) text = text.substring(4);
        if (text.isEmpty()) return "";
        try { text = IDN.toASCII(text, IDN.ALLOW_UNASSIGNED); }
        catch (IllegalArgumentException invalid) { }
        return text;
    }

    public static List<String> entries(String normalized) {
        List<String> result = new ArrayList<>();
        for (String entry : normalized.split(",")) if (!entry.isBlank()) result.add(entry.trim());
        return result;
    }
}
