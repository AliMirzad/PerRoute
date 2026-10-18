package app.perroute.model;

import java.net.IDN;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns whatever the user typed for a domain rule into the site it means, so one entry covers the whole site.
 * {@code https://www.snapp.ir/login?x=1}, {@code www.snapp.ir}, {@code SNAPP.IR:443} and {@code *.snapp.ir} all
 * become {@code snapp.ir}, which matches the domain and every subdomain. Entries with an explicit Xray prefix
 * keep their meaning: {@code full:} stays exact (only cleaned of a scheme or path), and {@code keyword:},
 * {@code regexp:}, {@code geosite:} and {@code ext:} are left as written.
 */
public final class DomainEntries {
    private DomainEntries() {}

    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*://", Pattern.CASE_INSENSITIVE);
    private static final Pattern PORT = Pattern.compile(":\\d{1,5}$");
    private static final Set<String> VERBATIM = Set.of("keyword", "regexp", "geosite", "ext", "dotless");

    /** Normalizes a comma- or whitespace-separated list, drops empty entries and duplicates, keeps the order. */
    public static String normalizeList(String text) {
        if (text == null) return "";
        Set<String> entries = new LinkedHashSet<>();
        for (String raw : text.split("[,\\s]+")) {
            String entry = normalize(raw);
            if (!entry.isEmpty()) entries.add(entry);
        }
        return String.join(", ", entries);
    }

    /** One entry; an empty result means nothing usable was typed. */
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

    /** The host part of a URL or host name, lower-cased and in ASCII form. */
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
        catch (IllegalArgumentException invalid) { /* kept as typed; the core reports it if it cannot use it */ }
        return text;
    }

    /** The entries of a normalized list, for display. */
    public static List<String> entries(String normalized) {
        List<String> result = new ArrayList<>();
        for (String entry : normalized.split(",")) if (!entry.isBlank()) result.add(entry.trim());
        return result;
    }
}
