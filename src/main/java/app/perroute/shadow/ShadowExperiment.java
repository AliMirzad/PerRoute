package app.perroute.shadow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A side-by-side test of a live route and a candidate server, run without changing the live route. Both sides
 * open the same HTTPS destinations, alternating which goes first in every round so neither profits from a warm
 * cache. Synthetic requests show how the paths compare; they cannot promise how every connection of an app will
 * behave, and the result says so.
 */
public final class ShadowExperiment {
    private ShadowExperiment() {}

    /** Times one HTTPS request to {@code host} through a loopback SOCKS port, in milliseconds. */
    @FunctionalInterface
    public interface Timer { long time(int socksPort, String host) throws IOException; }

    /** Results of one destination; -1 marks a request that failed. */
    public record Destination(String host, List<Long> live, List<Long> candidate) {
        public long liveMedian() { return median(live); }
        public long candidateMedian() { return median(candidate); }
        public int liveFailures() { return (int) live.stream().filter(value -> value < 0).count(); }
        public int candidateFailures() { return (int) candidate.stream().filter(value -> value < 0).count(); }
    }

    public enum Outcome {
        BETTER("The candidate looks better"),
        SIMILAR("No clear difference"),
        WORSE("The live route looks better"),
        CANDIDATE_FAILED("The candidate did not work"),
        INCONCLUSIVE("Not enough answers to compare");

        private final String headline;
        Outcome(String headline) { this.headline = headline; }
        public String headline() { return headline; }
    }

    public record Verdict(Outcome outcome, String detail, long liveMillis, long candidateMillis,
                          int liveFailures, int candidateFailures, int requests, int candidateWins, int compared) {}

    public record Report(List<Destination> destinations, Verdict verdict, boolean cancelled) {}

    /** Requests per destination and side. */
    public static final int ROUNDS = 3;
    /** A difference smaller than both of these is noise. */
    public static final long MIN_DIFFERENCE_MILLIS = 20;
    public static final double MIN_DIFFERENCE_SHARE = .15;

    public static Report run(Timer timer, int livePort, int candidatePort, List<String> hosts, int rounds, BooleanSupplier cancelled) {
        List<List<Long>> live = new ArrayList<>(), candidate = new ArrayList<>();
        hosts.forEach(host -> { live.add(new ArrayList<>()); candidate.add(new ArrayList<>()); });
        boolean stopped = false;
        outer:
        for (int round = 0; round < rounds; round++) {
            for (int index = 0; index < hosts.size(); index++) {
                if (cancelled.getAsBoolean()) { stopped = true; break outer; }
                boolean liveFirst = (round + index) % 2 == 0;
                if (liveFirst) { live.get(index).add(measure(timer, livePort, hosts.get(index))); candidate.get(index).add(measure(timer, candidatePort, hosts.get(index))); }
                else { candidate.get(index).add(measure(timer, candidatePort, hosts.get(index))); live.get(index).add(measure(timer, livePort, hosts.get(index))); }
            }
        }
        List<Destination> destinations = new ArrayList<>();
        for (int index = 0; index < hosts.size(); index++) destinations.add(new Destination(hosts.get(index), List.copyOf(live.get(index)), List.copyOf(candidate.get(index))));
        return new Report(List.copyOf(destinations), judge(destinations), stopped);
    }

    private static long measure(Timer timer, int port, String host) {
        try { return timer.time(port, host); } catch (IOException | RuntimeException failed) { return -1L; }
    }

    public static Verdict judge(List<Destination> destinations) {
        int requests = destinations.stream().mapToInt(destination -> destination.live().size()).sum();
        int liveFailures = destinations.stream().mapToInt(Destination::liveFailures).sum();
        int candidateFailures = destinations.stream().mapToInt(Destination::candidateFailures).sum();
        List<Long> liveMedians = new ArrayList<>(), candidateMedians = new ArrayList<>();
        int wins = 0, compared = 0;
        for (Destination destination : destinations) {
            long l = destination.liveMedian(), c = destination.candidateMedian();
            if (l < 0 || c < 0) continue;
            compared++;
            liveMedians.add(l);
            candidateMedians.add(c);
            if (c < l) wins++;
        }
        long liveMillis = median(liveMedians), candidateMillis = median(candidateMedians);
        if (requests == 0) return new Verdict(Outcome.INCONCLUSIVE, "The test was stopped before any request finished.", -1, -1, 0, 0, 0, 0, 0);
        if (candidateFailures == requests) {
            return new Verdict(Outcome.CANDIDATE_FAILED, "None of the " + requests + " requests through the candidate came back. It may be down or blocked from here.",
                    -1, -1, liveFailures, candidateFailures, requests, 0, 0);
        }
        if (liveFailures == requests) {
            return new Verdict(Outcome.BETTER, "The live route did not answer any request, while the candidate answered " + (requests - candidateFailures) + " of " + requests + ".",
                    -1, median(candidateMediansOf(destinations)), liveFailures, candidateFailures, requests, compared, compared);
        }
        if (compared == 0) return new Verdict(Outcome.INCONCLUSIVE, "No destination answered on both sides, so they cannot be compared.", -1, -1, liveFailures, candidateFailures, requests, 0, 0);
        long difference = liveMillis - candidateMillis;
        double share = liveMillis > 0 ? difference / (double) liveMillis : 0;
        String timing = "Typical answer " + liveMillis + " ms on the live route and " + candidateMillis + " ms on the candidate, faster on " +
                wins + " of " + compared + (compared == 1 ? " destination." : " destinations.");
        String failures = candidateFailures > liveFailures ? " The candidate failed " + candidateFailures + " of " + requests + " requests (live: " + liveFailures + ")."
                : candidateFailures < liveFailures ? " The live route failed " + liveFailures + " of " + requests + " requests (candidate: " + candidateFailures + ")." : "";
        Outcome outcome;
        if (candidateFailures > liveFailures + Math.max(1, requests / 10)) outcome = Outcome.WORSE;
        else if (difference >= MIN_DIFFERENCE_MILLIS && share >= MIN_DIFFERENCE_SHARE && candidateFailures <= liveFailures) outcome = Outcome.BETTER;
        else if (-difference >= MIN_DIFFERENCE_MILLIS && -share >= MIN_DIFFERENCE_SHARE) outcome = Outcome.WORSE;
        else if (liveFailures > candidateFailures + Math.max(1, requests / 10)) outcome = Outcome.BETTER;
        else outcome = Outcome.SIMILAR;
        return new Verdict(outcome, timing + failures, liveMillis, candidateMillis, liveFailures, candidateFailures, requests, wins, compared);
    }

    private static List<Long> candidateMediansOf(List<Destination> destinations) {
        return destinations.stream().map(Destination::candidateMedian).filter(value -> value >= 0).toList();
    }

    static long median(List<Long> values) {
        List<Long> answered = values.stream().filter(value -> value >= 0).sorted().toList();
        if (answered.isEmpty()) return -1;
        int middle = answered.size() / 2;
        return answered.size() % 2 == 1 ? answered.get(middle) : (answered.get(middle - 1) + answered.get(middle)) / 2;
    }
}
