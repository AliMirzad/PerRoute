package app.perroute.quality;

import java.util.Objects;

/**
 * One observation of one server. Only what was actually measured is stored: when, how, whether it
 * succeeded and how long it took. Nothing here names a destination or carries a credential.
 *
 * @param latencyMillis round-trip or setup time of a successful measurement, or -1
 */
public record QualitySample(long atMillis, Kind kind, boolean success, long latencyMillis, String detail) {
    public enum Kind {
        /** A TCP connection to the server's own endpoint. Proves the port answers, not that the proxy works. */
        REACHABILITY,
        /** A connection opened through the tunnel itself. The latency is the time to establish it. */
        TUNNEL,
        /** A route that had failed answered again. */
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
