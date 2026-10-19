package app.perroute.gaming;

import java.util.ArrayList;
import java.util.List;

public record GamingScore(int score, String label, boolean udpSupported, long latencyMillis, long p95Millis,
                          long jitterMillis, double lossPercent, long tcpLatencyMillis, double tcpLossPercent,
                          long measuredAt, String note) {

    public static final int UDP_UNSUPPORTED_CAP = 40;

    public static GamingScore of(GamingProbe.Result result, long now) {
        Stats tcp = Stats.of(result.tcp());
        Stats udp = Stats.of(result.udp());
        boolean udpSupported = !result.udp().failed();
        if (tcp.answered == 0 && !udpSupported) {
            String reason = !result.tcp().error().isBlank() ? result.tcp().error() : "Nothing came back through this server.";
            return new GamingScore(0, "Unavailable", false, -1, -1, -1, 100, -1, 100, now, reason);
        }
        Stats scored = udpSupported ? udp : tcp;
        int value = (int) Math.round(.4 * latencyPoints(scored.median) + .3 * jitterPoints(scored.jitter) + .3 * lossPoints(scored.loss));
        if (udpSupported && tcp.answered > 0 && tcp.loss >= 10) value = Math.min(value, 60);
        String note;
        if (!udpSupported) {
            value = Math.min(value, UDP_UNSUPPORTED_CAP);
            note = "This server did not carry UDP, which most online games need. Scored from TCP only.";
        } else if (scored.loss > 0) {
            note = String.format(java.util.Locale.ROOT, "%.0f%% of UDP packets were lost.", scored.loss);
        } else {
            note = "UDP and TCP both answered every time.";
        }
        return new GamingScore(value, label(value), udpSupported, scored.median, scored.p95, scored.jitter, scored.loss,
                tcp.median, tcp.loss, now, note);
    }

    public static String label(int score) {
        if (score >= 85) return "Excellent";
        if (score >= 70) return "Good";
        if (score >= 50) return "Fair";
        if (score > 0) return "Poor";
        return "Unavailable";
    }

    static double latencyPoints(long millis) {
        if (millis < 0) return 0;
        if (millis <= 40) return 100;
        if (millis <= 150) return 100 - (millis - 40) * 60.0 / 110;
        if (millis <= 300) return 40 - (millis - 150) * 40.0 / 150;
        return 0;
    }

    static double jitterPoints(long millis) {
        if (millis < 0) return 0;
        if (millis <= 5) return 100;
        if (millis >= 50) return 0;
        return 100 - (millis - 5) * 100.0 / 45;
    }

    static double lossPoints(double percent) {
        return Math.max(0, 100 - percent * 10);
    }

    private record Stats(int answered, long median, long p95, long jitter, double loss) {
        static Stats of(GamingProbe.Samples samples) {
            List<Long> answered = new ArrayList<>(samples.answered());
            double loss = samples.values().isEmpty() ? 100 : samples.lost() * 100.0 / samples.values().size();
            if (answered.isEmpty()) return new Stats(0, -1, -1, -1, loss);
            long jitter = 0;
            for (int index = 1; index < answered.size(); index++) jitter += Math.abs(answered.get(index) - answered.get(index - 1));
            jitter = answered.size() > 1 ? Math.round(jitter / (double) (answered.size() - 1)) : 0;
            List<Long> sorted = answered.stream().sorted().toList();
            long median = sorted.get(sorted.size() / 2);
            long p95 = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * .95) - 1));
            return new Stats(answered.size(), median, p95, jitter, loss);
        }
    }
}
