package app.perroute.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StoreAppPaths {
    private StoreAppPaths() {}

    private static final Pattern PACKAGE = Pattern.compile(
            "(?i)^(.*\\\\windowsapps\\\\)([^_\\\\]+)_([0-9][0-9.]*)_([^_\\\\]*)_([^_\\\\]*)_([a-z0-9]+)(\\\\.+)$");

    record Package(String name, String version, String architecture, String publisher, String inner) {
        boolean sameApp(Package other) {
            return name.equalsIgnoreCase(other.name) && architecture.equalsIgnoreCase(other.architecture)
                    && publisher.equalsIgnoreCase(other.publisher);
        }
    }

    static Package parse(String path) {
        if (path == null) return null;
        Matcher matcher = PACKAGE.matcher(path.replace('/', '\\'));
        if (!matcher.matches()) return null;
        return new Package(matcher.group(2), matcher.group(3), matcher.group(4), matcher.group(6), matcher.group(7));
    }

    public static boolean isStorePath(String path) { return parse(path) != null; }

    public static String resolve(String path, Collection<String> candidates, java.util.function.Predicate<String> exists) {
        if (path == null || path.isBlank()) return path;
        Package wanted = parse(path);
        if (wanted == null || exists.test(path)) return path;
        String best = null;
        Package bestPackage = null;
        int bestRank = 0;
        for (String candidate : candidates) {
            Package found = parse(candidate);
            if (found == null || !found.sameApp(wanted)) continue;
            int rank = found.inner.equalsIgnoreCase(wanted.inner) ? 2
                    : fileName(found.inner).equalsIgnoreCase(fileName(wanted.inner)) ? 1 : 0;
            if (rank == 0 || !exists.test(candidate)) continue;
            if (rank > bestRank || (rank == bestRank && compareVersions(found.version, bestPackage.version) > 0)) {
                best = candidate;
                bestPackage = found;
                bestRank = rank;
            }
        }
        return best == null ? path : best;
    }

    private static String fileName(String inner) {
        int slash = inner.lastIndexOf('\\');
        return slash < 0 ? inner : inner.substring(slash + 1);
    }

    static int compareVersions(String a, String b) {
        String[] left = a.split("\\."), right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            long x = i < left.length ? parseLong(left[i]) : 0, y = i < right.length ? parseLong(right[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }

    private static long parseLong(String text) {
        try { return Long.parseLong(text); } catch (NumberFormatException e) { return 0; }
    }

    private static final Set<String> KNOWN = ConcurrentHashMap.newKeySet();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    public static void remember(Collection<String> paths) {
        boolean added = false;
        for (String path : paths) if (isStorePath(path) && KNOWN.add(path)) added = true;
        if (added) LISTENERS.forEach(Runnable::run);
    }

    public static void onChange(Runnable listener) { LISTENERS.add(listener); }

    public static String current(String path) {
        return resolve(path, KNOWN, candidate -> Files.isRegularFile(Path.of(candidate)));
    }

    public static boolean stale(String path) {
        return isStorePath(path) && !Files.isRegularFile(Path.of(path));
    }

    static void forgetAll() { KNOWN.clear(); }

}
