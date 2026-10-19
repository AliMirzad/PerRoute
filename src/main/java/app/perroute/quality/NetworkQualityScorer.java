package app.perroute.quality;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class NetworkQualityScorer {
    public static final long STALE_AFTER_MILLIS = 30 * 60_000L;
    public static final long FORGET_AFTER_MILLIS = 24 * 60 * 60_000L;
    static final long HALF_LIFE_MILLIS = 2 * 60 * 60_000L;
    static final long RECOVERY_WINDOW_MILLIS = 60 * 60_000L;
    static final int UNVERIFIED_CAP = 74;

    private NetworkQualityScorer() {}

    public static QualityScore score(List<QualitySample> history, long now) {
        List<QualitySample> samples = new ArrayList<>();
        long newest = 0L;
        if (history != null) {
            for (QualitySample sample : history) {
                if (sample == null || sample.atMillis() > now + 60_000L) continue;
                newest = Math.max(newest, sample.atMillis());
                if (now - sample.atMillis() <= FORGET_AFTER_MILLIS) samples.add(sample);
            }
        }
        samples.sort(Comparator.comparingLong(QualitySample::atMillis));
        List<QualitySample> checks = samples.stream().filter(s -> s.kind() != QualitySample.Kind.RECOVERY).toList();
        if (checks.isEmpty()) {
            return newest == 0L
                    ? QualityScore.unknown(0L, 0, "Not measured yet.", "Run a test or connect to rate this server.")
                    : QualityScore.unknown(newest, 0, "Measured too long ago.",
                    "The last measurement is older than 24 hours, so it is no longer rated.");
        }

        List<QualitySample> tunnel = checks.stream().filter(s -> s.kind() == QualitySample.Kind.TUNNEL).toList();
        List<QualitySample> reach = checks.stream().filter(s -> s.kind() == QualitySample.Kind.REACHABILITY).toList();
        boolean tunnelVerified = tunnel.stream().anyMatch(QualitySample::success);
        long lastCheck = checks.get(checks.size() - 1).atMillis();
        long age = now - lastCheck;
        boolean stale = age > STALE_AFTER_MILLIS;

        double attempts = 0, successes = 0;
        for (QualitySample sample : checks) {
            double weight = Math.pow(0.5, (double) Math.max(0L, now - sample.atMillis()) / HALF_LIFE_MILLIS)
                    * (sample.kind() == QualitySample.Kind.TUNNEL ? 2.0 : 1.0);
            attempts += weight;
            if (sample.success()) successes += weight;
        }
        double successRate = attempts <= 0 ? 0 : successes / attempts;
        int recentFailures = (int) checks.stream().filter(s -> !s.success() && now - s.atMillis() <= RECOVERY_WINDOW_MILLIS).count();

        List<QualitySample> decisive = tunnel.isEmpty() ? reach : tunnel;
        boolean lastTwoFailed = decisive.size() >= 2
                && !decisive.get(decisive.size() - 1).success() && !decisive.get(decisive.size() - 2).success();
        boolean onlyFailure = decisive.size() == 1 && !decisive.get(0).success();
        boolean mostlyFailing = checks.size() >= 3 && successRate < 0.25;
        Components parts = components(reach, tunnel, checks, samples, successRate, recentFailures, now);
        double confidence = confidence(checks, now, tunnelVerified);

        if (lastTwoFailed || onlyFailure || mostlyFailing) {
            String headline = "Not responding.";
            String detail = (lastTwoFailed ? "The last two checks failed" : onlyFailure ? "The only check failed"
                    : "Most recent checks failed") + (tunnel.isEmpty() ? " to reach the server." : " to open a connection through the tunnel.")
                    + ageSentence(age);
            return new QualityScore(0, QualityLabel.UNAVAILABLE, confidence, lastCheck, stale, tunnelVerified,
                    parts.toRecord(), checks.size(), headline, detail.trim());
        }

        double weighted = 0, weights = 0;
        weighted += 0.35 * parts.successScore; weights += 0.35;
        if (parts.latencyScore != null) { weighted += 0.25 * parts.latencyScore; weights += 0.25; }
        if (parts.jitterScore != null) { weighted += 0.15 * parts.jitterScore; weights += 0.15; }
        if (parts.stabilityScore != null) { weighted += 0.15 * parts.stabilityScore; weights += 0.15; }
        if (parts.setupScore != null) { weighted += 0.10 * parts.setupScore; weights += 0.10; }
        int score = (int) Math.round(weighted / weights) - parts.recoveryPenalty;
        if (!tunnelVerified) score = Math.min(score, UNVERIFIED_CAP);
        score = Math.max(0, Math.min(100, score));
        QualityLabel label = QualityLabel.forScore(score);
        return new QualityScore(score, label, confidence, lastCheck, stale, tunnelVerified, parts.toRecord(),
                checks.size(), headline(label, parts, tunnelVerified, stale), explanation(parts, tunnelVerified, age));
    }

    private static final class Components {
        Long latencyMillis; Integer latencyScore; Long jitterMillis; Integer jitterScore;
        double successRate; int successScore; Integer stabilityScore; Long setupMillis; Integer setupScore;
        int recoveryPenalty; int recentFailures;

        QualityScore.Components toRecord() {
            return new QualityScore.Components(latencyMillis, latencyScore, jitterMillis, jitterScore, successRate,
                    successScore, stabilityScore, setupMillis, setupScore, recoveryPenalty, recentFailures);
        }
    }

    private static Components components(List<QualitySample> reach, List<QualitySample> tunnel, List<QualitySample> checks,
                                         List<QualitySample> all, double successRate, int recentFailures, long now) {
        Components parts = new Components();
        parts.successRate = successRate;
        parts.successScore = (int) Math.round(successRate * 100);
        parts.recentFailures = recentFailures;

        List<Long> latencies = successfulLatencies(reach.isEmpty() ? tunnel : reach);
        if (!latencies.isEmpty()) {
            parts.latencyMillis = median(latencies);
            parts.latencyScore = linear(parts.latencyMillis, 40, 600);
        }
        if (latencies.size() >= 3) {
            long total = 0;
            for (int i = 1; i < latencies.size(); i++) total += Math.abs(latencies.get(i) - latencies.get(i - 1));
            parts.jitterMillis = Math.round((double) total / (latencies.size() - 1));
            parts.jitterScore = linear(parts.jitterMillis, 5, 150);
        }
        if (checks.size() >= 3) {
            int transitions = 0;
            for (int i = 1; i < checks.size(); i++) if (checks.get(i).success() != checks.get(i - 1).success()) transitions++;
            parts.stabilityScore = (int) Math.round(100.0 * (1.0 - (double) transitions / (checks.size() - 1)));
        }
        List<Long> setup = successfulLatencies(tunnel);
        if (!setup.isEmpty()) {
            parts.setupMillis = median(setup);
            parts.setupScore = linear(parts.setupMillis, 150, 2_500);
        }

        double penalty = 0;
        for (QualitySample sample : all) {
            if (sample.kind() == QualitySample.Kind.RECOVERY && now - sample.atMillis() <= RECOVERY_WINDOW_MILLIS) {
                penalty += 6.0 * (1.0 - (double) (now - sample.atMillis()) / RECOVERY_WINDOW_MILLIS);
            }
        }
        if (all.stream().noneMatch(s -> s.kind() == QualitySample.Kind.RECOVERY)) {
            for (int i = 1; i < checks.size(); i++) {
                QualitySample current = checks.get(i);
                if (current.success() && !checks.get(i - 1).success() && now - current.atMillis() <= RECOVERY_WINDOW_MILLIS) {
                    penalty += 6.0 * (1.0 - (double) (now - current.atMillis()) / RECOVERY_WINDOW_MILLIS);
                }
            }
        }
        parts.recoveryPenalty = (int) Math.min(15, Math.round(penalty));
        return parts;
    }

    static double confidence(List<QualitySample> checks, long now, boolean tunnelVerified) {
        long recent = checks.stream().filter(s -> now - s.atMillis() <= 6 * 60 * 60_000L).count();
        double amount = Math.min(1.0, recent / 8.0);
        long age = now - checks.get(checks.size() - 1).atMillis();
        double freshness;
        if (age <= 10 * 60_000L) freshness = 1.0;
        else if (age <= 6 * 60 * 60_000L) freshness = 1.0 - 0.7 * (age - 10 * 60_000L) / (6 * 60 * 60_000.0 - 10 * 60_000L);
        else freshness = 0.1;
        double evidence = tunnelVerified ? 1.0 : 0.6;
        return Math.round(Math.max(0.05, amount) * freshness * evidence * 100.0) / 100.0;
    }

    private static String headline(QualityLabel label, Components parts, boolean verified, boolean stale) {
        if (stale) return "Last result may be outdated.";
        if (!verified) return "Reachable; tunnel not verified yet.";
        boolean flaky = parts.stabilityScore != null && parts.stabilityScore < 70 || parts.recentFailures > 0;
        return switch (label) {
            case EXCELLENT -> flaky ? "Fast, recovering from failures." : "Fast, stable, recently verified.";
            case GOOD -> flaky ? "Working, with occasional failures." : "Works well.";
            case FAIR -> "Usable, but slower or less steady.";
            default -> "Slow or unreliable.";
        };
    }

    private static String explanation(Components parts, boolean verified, long age) {
        List<String> sentences = new ArrayList<>();
        if (parts.latencyMillis != null) {
            sentences.add(describe(parts.latencyScore, "Low", "Moderate", "High") + " latency (" + parts.latencyMillis + " ms)"
                    + (parts.jitterMillis == null ? "" : ", " + describe(parts.jitterScore, "minimal", "some", "high").toLowerCase() + " jitter (" + parts.jitterMillis + " ms)")
                    + ".");
        }
        sentences.add(Math.round(parts.successRate * 100) + "% of recent checks succeeded"
                + (parts.recentFailures > 0 ? ", " + parts.recentFailures + " failed in the last hour." : "."));
        if (parts.setupMillis != null) sentences.add("Connections through the tunnel open in about " + parts.setupMillis + " ms.");
        if (!verified) sentences.add("Only the server's port answered; a real tunnel test or a connection will verify it.");
        if (parts.recoveryPenalty > 0) sentences.add("It recovered from a failure recently (-" + parts.recoveryPenalty + ").");
        String agePart = ageSentence(age);
        if (!agePart.isBlank()) sentences.add(agePart.trim());
        return String.join(" ", sentences);
    }

    private static String ageSentence(long age) {
        if (age <= STALE_AFTER_MILLIS) return "";
        long minutes = age / 60_000L;
        return minutes < 120 ? " Measured " + minutes + " minutes ago." : " Measured " + (minutes / 60) + " hours ago.";
    }

    private static String describe(Integer score, String good, String middle, String bad) {
        if (score == null) return middle;
        return score >= 80 ? good : score >= 45 ? middle : bad;
    }

    private static List<Long> successfulLatencies(List<QualitySample> samples) {
        return samples.stream().filter(QualitySample::success).map(QualitySample::latencyMillis).filter(v -> v >= 0).toList();
    }

    static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2.0);
    }

    static int linear(long value, long best, long worst) {
        if (value <= best) return 100;
        if (value >= worst) return 0;
        return (int) Math.round(100.0 * (worst - value) / (worst - best));
    }
}
