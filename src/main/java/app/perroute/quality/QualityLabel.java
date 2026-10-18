package app.perroute.quality;

public enum QualityLabel {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
    UNAVAILABLE("Unavailable"),
    UNKNOWN("Unknown");

    private final String text;

    QualityLabel(String text) { this.text = text; }

    public String text() { return text; }

    public static QualityLabel forScore(int score) {
        if (score >= 85) return EXCELLENT;
        if (score >= 70) return GOOD;
        if (score >= 50) return FAIR;
        return POOR;
    }
}
