package app.perroute.subscription;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Reads a {@link SubscriptionInsight} and says, in plain words, what deserves attention. */
public final class SubscriptionHealth {
    private SubscriptionHealth() {}

    public static final long DAY = 24L * 60 * 60 * 1000;
    /** A subscription not updated for this long is reported as stale, unless its provider asks for less often. */
    public static final long STALE_AFTER = 7 * DAY;
    public static final long EXPIRING_WITHIN = 3 * DAY;

    public enum Severity { OK, INFO, WARNING, PROBLEM }

    public record Finding(Severity severity, String title, String detail) {}

    public record Report(Severity severity, String status, List<Finding> findings) {}

    public static Report analyze(SubscriptionInsight insight, long now) {
        List<Finding> findings = new ArrayList<>();
        if (insight == null || (insight.lastAttemptAt() == 0 && insight.lastSuccessAt() == 0)) {
            return new Report(Severity.INFO, "Not updated by this version yet",
                    List.of(new Finding(Severity.INFO, "No update recorded yet", "Update the subscription once to see its usage, expiry and changes.")));
        }
        SubscriptionUserInfo info = insight.userInfo();
        if (info.hasExpiry()) {
            long left = info.expireAtMillis() - now;
            if (left <= 0) findings.add(new Finding(Severity.PROBLEM, "Expired", "The provider reports that it expired on " + date(info.expireAtMillis()) +
                    ". Its servers will probably stop working; renew it with your provider."));
            else if (left <= EXPIRING_WITHIN) findings.add(new Finding(Severity.WARNING, "Expires " + inDays(left), "The provider reports it expires on " + date(info.expireAtMillis()) + "."));
        }
        if (info.hasQuota()) {
            double share = info.usedShare();
            if (share >= 1.0) findings.add(new Finding(Severity.PROBLEM, "Data used up", "All " + bytes(info.totalBytes()) + " of the plan are used. Servers may refuse connections until it renews."));
            else if (share >= .9) findings.add(new Finding(Severity.WARNING, Math.round(share * 100) + "% of the data used",
                    bytes(Math.max(0, info.totalBytes() - info.usedBytes())) + " left of " + bytes(info.totalBytes()) + "."));
        }
        if (insight.consecutiveFailures() > 0) {
            findings.add(new Finding(insight.consecutiveFailures() >= 3 ? Severity.PROBLEM : Severity.WARNING,
                    insight.consecutiveFailures() == 1 ? "The last update failed" : insight.consecutiveFailures() + " updates in a row failed",
                    insight.lastError().isBlank() ? "The existing servers were kept." : insight.lastError() + " The existing servers were kept."));
        }
        long staleAfter = insight.updateIntervalHours() > 0 ? Math.max(STALE_AFTER, insight.updateIntervalHours() * 3L * 60 * 60 * 1000) : STALE_AFTER;
        if (insight.lastSuccessAt() > 0 && now - insight.lastSuccessAt() > staleAfter) {
            findings.add(new Finding(Severity.WARNING, "Not updated for " + Math.max(1, (now - insight.lastSuccessAt()) / DAY) + " days",
                    "Servers may have changed since. Update it to get the provider's current list."));
        }
        if (insight.lastSuccessAt() > 0 && insight.serverCount() == 0) {
            findings.add(new Finding(Severity.PROBLEM, "No servers", "The last update returned no usable servers."));
        }
        SubscriptionChange change = insight.lastChange();
        if (change != null && change.unexpected()) {
            findings.add(new Finding(Severity.WARNING, "Large change in the last update",
                    change.summary() + " (" + change.before() + " before, " + change.after() + " now). Check that the link still belongs to your provider."));
        }
        if (insight.duplicatesElsewhere() > 0) {
            findings.add(new Finding(Severity.INFO, plural(insight.duplicatesElsewhere(), "server") + " also in another source",
                    "They are identical to servers you already have, so each is kept once."));
        }
        if (insight.duplicatesWithin() > 0) {
            findings.add(new Finding(Severity.INFO, plural(insight.duplicatesWithin(), "duplicate") + " in the provider's list", "Each is kept once."));
        }
        if (insight.sharedEndpoints() > 0) {
            findings.add(new Finding(Severity.INFO, plural(insight.sharedEndpoints(), "server") + " share an address",
                    "They use the same address and port as another saved server with different settings. They may be the same machine."));
        }
        findings.sort(Comparator.comparing(Finding::severity).reversed());
        Severity worst = findings.stream().map(Finding::severity).max(Comparator.naturalOrder()).orElse(Severity.OK);
        String status = switch (worst) {
            case PROBLEM, WARNING -> findings.get(0).title();
            default -> "Healthy";
        };
        return new Report(worst, status, findings);
    }

    static String inDays(long millis) {
        long hours = millis / (60L * 60 * 1000);
        if (hours < 24) return hours <= 1 ? "within an hour" : "in " + hours + " hours";
        long days = Math.round(millis / (double) DAY);
        return "in " + days + (days == 1 ? " day" : " days");
    }

    static String date(long millis) {
        return DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    public static String bytes(long value) {
        if (value < 1024) return value + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double size = value;
        int unit = -1;
        do { size /= 1024; unit++; } while (size >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, size >= 100 ? "%.0f %s" : "%.1f %s", size, units[unit]);
    }

    private static String plural(int count, String word) { return count + " " + word + (count == 1 ? "" : "s"); }
}
