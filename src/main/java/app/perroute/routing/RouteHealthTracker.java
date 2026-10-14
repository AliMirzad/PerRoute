package app.perroute.routing;

import java.util.ArrayDeque;
import java.util.List;

/**
 * The health of one live route, driven only by checks of that route. It decides when the next check is
 * due and what recovery step to take; it never touches the network itself, so it can be tested with a
 * fake clock.
 *
 * <p>A healthy route is checked every {@link Policy#healthyIntervalMillis()}. The first failure starts
 * recovery: retries back off exponentially ({@code base * 2^(attempt-1)}, capped), the route's outbound is
 * rebuilt once at {@link Policy#refreshAtAttempt()}, and after {@link Policy#maxAttempts()} the route is
 * marked failed. A failed route is then only checked every {@link Policy#failedIntervalMillis()}, so there is
 * never an endless restart loop, and it becomes healthy again by itself as soon as a check passes.
 */
public final class RouteHealthTracker {
    public enum State { CHECKING, HEALTHY, RECOVERING, FAILED }

    public enum EventType { CHECK_PASSED, CHECK_FAILED, RETRY_SCHEDULED, REFRESHED, RECOVERED, EXHAUSTED, MANUAL_RETRY, FAILOVER }

    public record Event(long atMillis, EventType type, String title, String detail) {}

    /** What the caller must do after a result was recorded. */
    public enum Action { NONE, REFRESH_ROUTE, EXHAUSTED, RECOVERED }

    public record Policy(long healthyIntervalMillis, long backoffBaseMillis, long backoffMaxMillis, int maxAttempts,
                         int refreshAtAttempt, long failedIntervalMillis) {
        public static final Policy DEFAULT = new Policy(60_000L, 2_000L, 32_000L, 5, 2, 120_000L);

        public Policy {
            healthyIntervalMillis = Math.max(1_000L, healthyIntervalMillis);
            backoffBaseMillis = Math.max(100L, backoffBaseMillis);
            backoffMaxMillis = Math.max(backoffBaseMillis, backoffMaxMillis);
            maxAttempts = Math.max(1, maxAttempts);
            failedIntervalMillis = Math.max(1_000L, failedIntervalMillis);
        }

        public long backoff(int attempt) {
            long delay = backoffBaseMillis << Math.min(20, Math.max(0, attempt - 1));
            return Math.min(backoffMaxMillis, delay);
        }
    }

    /** An immutable view for the screen. */
    public record Snapshot(String routeId, State state, int attempt, int maxAttempts, long nextCheckAt,
                           long lastPassedAt, long lastFailedAt, Long lastLatencyMillis, String lastError,
                           List<Event> events) {
        public boolean affected() { return state == State.RECOVERING || state == State.FAILED; }
    }

    static final int MAX_EVENTS = 12;

    private final String routeId;
    private final String routeName;
    private final Policy policy;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private State state = State.CHECKING;
    private int attempt;
    private long nextCheckAt;
    private long lastPassedAt;
    private long lastFailedAt;
    private Long lastLatency;
    private String lastError = "";

    public RouteHealthTracker(String routeId, String routeName, Policy policy, long now) {
        this.routeId = routeId;
        this.routeName = routeName == null ? routeId : routeName;
        this.policy = policy == null ? Policy.DEFAULT : policy;
        this.nextCheckAt = now;
    }

    public synchronized boolean due(long now) { return now >= nextCheckAt; }

    public synchronized long nextCheckAt() { return nextCheckAt; }

    public synchronized State state() { return state; }

    /** Records the outcome of one check of this route and returns the recovery step to take. */
    public synchronized Action record(boolean passed, long latencyMillis, String detail, long now) {
        if (passed) {
            boolean wasAffected = state == State.RECOVERING || state == State.FAILED;
            lastPassedAt = now;
            lastLatency = latencyMillis >= 0 ? latencyMillis : null;
            attempt = 0;
            nextCheckAt = now + policy.healthyIntervalMillis();
            if (wasAffected) {
                state = State.HEALTHY;
                add(now, EventType.RECOVERED, "Recovered", routeName + " answers again" + (lastLatency == null ? "." : " (" + lastLatency + " ms)."));
                return Action.RECOVERED;
            }
            if (state == State.CHECKING) add(now, EventType.CHECK_PASSED, "Check passed", lastLatency == null ? "" : lastLatency + " ms");
            state = State.HEALTHY;
            return Action.NONE;
        }

        lastFailedAt = now;
        lastError = detail == null || detail.isBlank() ? "No answer" : detail;
        if (state == State.FAILED) {
            nextCheckAt = now + policy.failedIntervalMillis();
            return Action.NONE;
        }
        attempt = state == State.RECOVERING ? attempt + 1 : 1;
        if (attempt == 1) add(now, EventType.CHECK_FAILED, "Failure detected", lastError);
        if (attempt > policy.maxAttempts()) {
            state = State.FAILED;
            attempt = policy.maxAttempts();
            nextCheckAt = now + policy.failedIntervalMillis();
            add(now, EventType.EXHAUSTED, "Retries exhausted",
                    "Checked again every " + (policy.failedIntervalMillis() / 1_000L) + " s; other routes are not affected.");
            return Action.EXHAUSTED;
        }
        state = State.RECOVERING;
        long delay = policy.backoff(attempt);
        nextCheckAt = now + delay;
        add(now, EventType.RETRY_SCHEDULED, "Retry scheduled",
                "Attempt " + attempt + " of " + policy.maxAttempts() + " (backoff " + (delay / 1_000L) + " s)");
        if (attempt == policy.refreshAtAttempt()) {
            add(now, EventType.REFRESHED, "Route refreshed", "The route's outbound is rebuilt without touching other routes.");
            return Action.REFRESH_ROUTE;
        }
        return Action.NONE;
    }

    /** The user asked to retry now: a failed route gets a fresh set of attempts. */
    public synchronized void retryNow(long now) {
        if (state == State.FAILED) { state = State.RECOVERING; attempt = 0; }
        nextCheckAt = now;
        add(now, EventType.MANUAL_RETRY, "Retry requested", "Checking " + routeName + " now.");
    }

    public synchronized void note(long now, EventType type, String title, String detail) {
        add(now, type, title, detail);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(routeId, state, attempt, policy.maxAttempts(), nextCheckAt, lastPassedAt, lastFailedAt,
                lastLatency, lastError, List.copyOf(events));
    }

    private void add(long now, EventType type, String title, String detail) {
        events.addLast(new Event(now, type, title, detail == null ? "" : detail));
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }
}
