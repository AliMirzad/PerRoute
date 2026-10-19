package app.perroute.quality;

public record QualityScore(Integer score, QualityLabel label, double confidence, long measuredAt, boolean stale,
                           boolean tunnelVerified, Components components, int sampleCount,
                           String headline, String explanation) {

    public record Components(Long latencyMillis, Integer latencyScore, Long jitterMillis, Integer jitterScore,
                             Double successRate, Integer successScore, Integer stabilityScore,
                             Long setupMillis, Integer setupScore, int recoveryPenalty, int recentFailures) {
        public static final Components EMPTY = new Components(null, null, null, null, null, null, null, null, null, 0, 0);
    }

    public static QualityScore unknown(long measuredAt, int samples, String headline, String explanation) {
        return new QualityScore(null, QualityLabel.UNKNOWN, 0.0, measuredAt, measuredAt > 0, false,
                Components.EMPTY, samples, headline, explanation);
    }

    public boolean usable() {
        return score != null && label != QualityLabel.UNKNOWN && label != QualityLabel.UNAVAILABLE;
    }

    public int confidencePercent() { return (int) Math.round(confidence * 100); }
}
