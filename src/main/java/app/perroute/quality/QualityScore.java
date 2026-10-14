package app.perroute.quality;

/**
 * The rated quality of one server at one moment, with everything needed to explain it.
 *
 * @param score       0 to 100, or null when there is not enough evidence to rate the server
 * @param confidence  0 to 1: how much the score can be trusted (amount, freshness and kind of evidence)
 * @param measuredAt  time of the newest sample, or 0 when never measured
 * @param stale       true when the newest sample is older than the freshness limit
 * @param tunnelVerified true when at least one connection through the tunnel itself succeeded recently
 */
public record QualityScore(Integer score, QualityLabel label, double confidence, long measuredAt, boolean stale,
                           boolean tunnelVerified, Components components, int sampleCount,
                           String headline, String explanation) {

    /** Each part of the score; a null value means that part could not be measured and was left out. */
    public record Components(Long latencyMillis, Integer latencyScore, Long jitterMillis, Integer jitterScore,
                             Double successRate, Integer successScore, Integer stabilityScore,
                             Long setupMillis, Integer setupScore, int recoveryPenalty, int recentFailures) {
        public static final Components EMPTY = new Components(null, null, null, null, null, null, null, null, null, 0, 0);
    }

    public static QualityScore unknown(long measuredAt, int samples, String headline, String explanation) {
        return new QualityScore(null, QualityLabel.UNKNOWN, 0.0, measuredAt, measuredAt > 0, false,
                Components.EMPTY, samples, headline, explanation);
    }

    /** True when the score is backed by evidence and the server is not known to be down. */
    public boolean usable() {
        return score != null && label != QualityLabel.UNKNOWN && label != QualityLabel.UNAVAILABLE;
    }

    public int confidencePercent() { return (int) Math.round(confidence * 100); }
}
