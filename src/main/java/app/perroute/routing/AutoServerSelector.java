package app.perroute.routing;

import app.perroute.quality.QualityLabel;
import app.perroute.quality.QualityScore;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class AutoServerSelector {
    private AutoServerSelector() {}

    public record Policy(int switchMargin, long cooldownMillis, long failoverIntervalMillis, double minConfidence) {
        public static final Policy DEFAULT = new Policy(10, 10 * 60_000L, 60_000L, 0.25);

        public Policy {
            switchMargin = Math.max(1, switchMargin);
            cooldownMillis = Math.max(0L, cooldownMillis);
            failoverIntervalMillis = Math.max(0L, failoverIntervalMillis);
            minConfidence = Math.max(0.0, Math.min(1.0, minConfidence));
        }
    }

    public record Candidate(String key, String name, QualityScore score, boolean routeFailed) {
        public Candidate {
            Objects.requireNonNull(key, "key");
            name = name == null ? key : name;
        }
    }

    public record Current(String key, long lastChangeMillis) {}

    public enum Reason {
        INITIAL_BEST, KEPT_STABLE, KEPT_COOLDOWN, KEPT_MARGIN, SWITCHED_BETTER, FAILOVER,
        PROVISIONAL_MAIN, KEPT_NO_ALTERNATIVE, NO_SERVER
    }

    public record Decision(String nodeKey, Reason reason, boolean changed, Integer score, String summary, String explanation) {
        public boolean hasServer() { return nodeKey != null; }
    }

    public static Decision decide(List<Candidate> candidates, Current current, String mainKey, String mainName,
                                  Policy policy, long now) {
        Policy rules = policy == null ? Policy.DEFAULT : policy;
        List<Candidate> pool = candidates == null ? List.of() : candidates;
        Candidate best = pool.stream()
                .filter(candidate -> eligible(candidate, rules))
                .max(Comparator.<Candidate>comparingInt(c -> c.score().score())
                        .thenComparingDouble(c -> c.score().confidence())
                        .thenComparing(c -> current != null && c.key().equals(current.key())))
                .orElse(null);
        Candidate present = current == null ? null
                : pool.stream().filter(candidate -> candidate.key().equals(current.key())).findFirst().orElse(null);

        if (present == null) {
            if (best != null) {
                return new Decision(best.key(), Reason.INITIAL_BEST, current == null || !best.key().equals(current.key()),
                        best.score().score(), "Best quality · " + best.score().score(),
                        best.name() + " has the best measured quality among the eligible servers ("
                                + best.score().score() + ", " + best.score().label().text() + ")."
                                + (current != null ? " The previous choice is no longer available." : ""));
            }
            if (mainKey != null && !mainKey.isBlank()) {
                return new Decision(mainKey, Reason.PROVISIONAL_MAIN, current == null || !mainKey.equals(current.key()), null,
                        "Main server until measured",
                        "No eligible server has enough quality data yet, so " + mainName
                                + " (the main server) is used until measurements are available.");
            }
            return new Decision(null, Reason.NO_SERVER, false, null, "No server available",
                    "There is no measured server to choose and no main server to fall back to.");
        }

        QualityScore currentScore = present.score();
        boolean currentFailed = present.routeFailed() || currentScore.label() == QualityLabel.UNAVAILABLE;
        long sinceChange = current.lastChangeMillis() <= 0 ? Long.MAX_VALUE : now - current.lastChangeMillis();

        if (currentFailed) {
            if (best != null && !best.key().equals(present.key())) {
                if (sinceChange < rules.failoverIntervalMillis()) {
                    return keep(present, Reason.KEPT_COOLDOWN, "Waiting before failing over",
                            present.name() + " is failing, but Auto changed this app's server "
                                    + seconds(sinceChange) + " ago. It waits " + seconds(rules.failoverIntervalMillis())
                                    + " between failovers to avoid flapping.");
                }
                return new Decision(best.key(), Reason.FAILOVER, true, best.score().score(), "Failover · " + best.score().score(),
                        present.name() + " stopped working, so the app moved to " + best.name()
                                + " (" + best.score().score() + ", " + best.score().label().text() + ").");
            }
            return keep(present, Reason.KEPT_NO_ALTERNATIVE, "No working alternative",
                    present.name() + " is failing and no other eligible server is known to work. "
                            + "The app keeps this server and is not released to the direct route.");
        }

        if (best == null || best.key().equals(present.key())) {
            return keep(present, Reason.KEPT_STABLE, currentScore.usable() ? "Best quality · " + currentScore.score() : "Current choice kept",
                    currentScore.usable()
                            ? present.name() + " is still the best eligible server (" + currentScore.score() + ", " + currentScore.label().text() + ")."
                            : present.name() + " is kept: no other server has enough quality data to replace it.");
        }
        if (sinceChange < rules.cooldownMillis()) {
            return keep(present, Reason.KEPT_COOLDOWN, "Stable route kept",
                    best.name() + " scores higher, but this app's server changed " + minutes(sinceChange)
                            + " ago. Auto waits " + minutes(rules.cooldownMillis()) + " between changes.");
        }
        if (currentScore.usable()) {
            int gain = best.score().score() - currentScore.score();
            if (gain < rules.switchMargin()) {
                return keep(present, Reason.KEPT_MARGIN, "Best quality · " + currentScore.score(),
                        present.name() + " is kept (" + currentScore.score() + "). " + best.name() + " is only " + gain
                                + " points better; Auto changes servers for " + rules.switchMargin() + " or more.");
            }
        } else if (best.score().label() != QualityLabel.EXCELLENT && best.score().label() != QualityLabel.GOOD) {
            return keep(present, Reason.KEPT_MARGIN, "Current choice kept",
                    present.name() + " has no recent measurement, and no measured alternative is rated Good or better.");
        }
        return new Decision(best.key(), Reason.SWITCHED_BETTER, true, best.score().score(), "Best quality · " + best.score().score(),
                best.name() + " is clearly better (" + best.score().score() + " vs "
                        + (currentScore.usable() ? currentScore.score() : "unmeasured") + "), so the app moved to it.");
    }

    static boolean eligible(Candidate candidate, Policy policy) {
        return !candidate.routeFailed() && candidate.score() != null && candidate.score().usable()
                && candidate.score().confidence() >= policy.minConfidence();
    }

    private static Decision keep(Candidate present, Reason reason, String summary, String explanation) {
        QualityScore score = present.score();
        return new Decision(present.key(), reason, false, score != null && score.usable() ? score.score() : null, summary, explanation);
    }

    private static String seconds(long millis) { return Math.max(1, millis / 1_000L) + " s"; }

    private static String minutes(long millis) {
        long minutes = millis / 60_000L;
        return minutes < 1 ? seconds(millis) : minutes + " min";
    }
}
