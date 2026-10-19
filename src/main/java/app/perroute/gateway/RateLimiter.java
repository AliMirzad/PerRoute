package app.perroute.gateway;

import java.util.concurrent.locks.LockSupport;

public final class RateLimiter {
    private volatile long bytesPerSecond;
    private double tokens;
    private long lastRefillNanos = System.nanoTime();
    private final NanoClock clock;

    interface NanoClock {
        long nanoTime();
        void park(long nanos) throws InterruptedException;
    }

    private static final NanoClock SYSTEM = new NanoClock() {
        @Override public long nanoTime() { return System.nanoTime(); }
        @Override public void park(long nanos) throws InterruptedException {
            LockSupport.parkNanos(nanos);
            if (Thread.interrupted()) throw new InterruptedException();
        }
    };

    public RateLimiter() { this(0L, SYSTEM); }

    RateLimiter(long bytesPerSecond, NanoClock clock) {
        this.clock = clock;
        this.lastRefillNanos = clock.nanoTime();
        setRate(bytesPerSecond);
    }

    public long rate() { return bytesPerSecond; }

    public synchronized void setRate(long value) {
        long next = Math.max(0L, value);
        if (next == bytesPerSecond) return;
        refill(clock.nanoTime());
        bytesPerSecond = next;
        tokens = next == 0 ? 0 : burst(next);
    }

    public boolean limited() { return bytesPerSecond > 0; }

    public void acquire(int bytes) throws InterruptedException {
        if (bytes <= 0) return;
        while (true) {
            long rate = bytesPerSecond;
            if (rate <= 0) return;
            long waitNanos;
            synchronized (this) {
                if (bytesPerSecond != rate) continue;
                long now = clock.nanoTime();
                refill(now);
                double needed = Math.min(bytes, burst(rate));
                if (tokens >= needed) {
                    tokens -= bytes;
                    return;
                }
                waitNanos = (long) Math.ceil((needed - tokens) * 1_000_000_000.0 / rate);
            }
            clock.park(Math.max(50_000L, Math.min(waitNanos, 250_000_000L)));
        }
    }

    private void refill(long now) {
        long rate = bytesPerSecond;
        long elapsed = now - lastRefillNanos;
        lastRefillNanos = now;
        if (rate <= 0 || elapsed <= 0) return;
        tokens = Math.min(burst(rate), tokens + elapsed * (double) rate / 1_000_000_000.0);
    }

    static double burst(long rate) {
        return Math.max(4_096.0, rate / 10.0);
    }
}
