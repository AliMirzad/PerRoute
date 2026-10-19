package app.perroute.quality;

import java.util.Objects;

public record QualitySample(long atMillis, Kind kind, boolean success, long latencyMillis, String detail) {
    public enum Kind {
        REACHABILITY,
        TUNNEL,
        RECOVERY
    }

    public QualitySample {
        Objects.requireNonNull(kind, "kind");
        detail = detail == null ? "" : detail;
        if (!success) latencyMillis = -1L;
        else if (kind != Kind.RECOVERY && latencyMillis < 0) throw new IllegalArgumentException("A success needs a latency");
    }

    public static QualitySample reachability(long atMillis, long latencyMillis) {
        return latencyMillis >= 0 ? new QualitySample(atMillis, Kind.REACHABILITY, true, latencyMillis, "")
                : new QualitySample(atMillis, Kind.REACHABILITY, false, -1L, "Server endpoint did not answer");
    }

    public static QualitySample tunnel(long atMillis, boolean success, long latencyMillis, String detail) {
        return new QualitySample(atMillis, Kind.TUNNEL, success, success ? latencyMillis : -1L, detail);
    }

    public static QualitySample recovery(long atMillis) {
        return new QualitySample(atMillis, Kind.RECOVERY, true, -1L, "Route recovered");
    }
}
