package com.enterprise.outlook.common.resilience;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Adaptive concurrency limiter combining Gradient2 (latency-gradient driven growth) with AIMD safety:
 * healthy -> limit follows gradient = tolerance * longRtt / shortRtt plus sqrt(limit) headroom;
 * drop / 5xx / scheduler lag -> multiplicative decrease; warm-up ceiling ramps 5% -> 100% of maxLimit;
 * BACKGROUND is shed at 50% of the limit, NORMAL at 80%, INTERACTIVE at 100%, CRITICAL never.
 */
public final class AimdConcurrencyLimiter {

    public static final class Config {
        private final int minLimit;
        private final int initialLimit;
        private final int maxLimit;
        private final double smoothing;
        private final double rttTolerance;
        private final double backoffRatio;
        private final long warmupMillis;
        private final double lagThresholdMillis;

        public Config(int minLimit, int initialLimit, int maxLimit, double smoothing,
                      double rttTolerance, double backoffRatio, long warmupMillis, double lagThresholdMillis) {
            this.minLimit = minLimit;
            this.initialLimit = initialLimit;
            this.maxLimit = maxLimit;
            this.smoothing = smoothing;
            this.rttTolerance = rttTolerance;
            this.backoffRatio = backoffRatio;
            this.warmupMillis = warmupMillis;
            this.lagThresholdMillis = lagThresholdMillis;
        }

        public static Config defaults() { return new Config(4, 64, 1024, 0.2, 1.5, 0.9, 60_000, 75.0); }

        public int minLimit() { return minLimit; }
        public int initialLimit() { return initialLimit; }
        public int maxLimit() { return maxLimit; }
        public double smoothing() { return smoothing; }
        public double rttTolerance() { return rttTolerance; }
        public double backoffRatio() { return backoffRatio; }
        public long warmupMillis() { return warmupMillis; }
        public double lagThresholdMillis() { return lagThresholdMillis; }
    }

    public static final class Permit {
        private final long startNanos;
        private Permit(long startNanos) { this.startNanos = startNanos; }
    }

    private final Config cfg;
    private final LongSupplier nanoClock;
    private final LongSupplier millisClock;
    private final long startedAtMillis;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong admitted = new AtomicLong();
    private volatile double limit;
    private double shortRtt = -1, longRtt = -1;          // guarded by this

    public AimdConcurrencyLimiter(Config cfg) { this(cfg, System::nanoTime, System::currentTimeMillis); }

    public AimdConcurrencyLimiter(Config cfg, LongSupplier nanoClock, LongSupplier millisClock) {
        this.cfg = cfg;
        this.nanoClock = nanoClock;
        this.millisClock = millisClock;
        this.startedAtMillis = millisClock.getAsLong();
        this.limit = cfg.initialLimit();
    }

    /** @return a permit or null when the request must be shed (respond 503 + jittered Retry-After). */
    public Permit tryAcquire(RequestPriority priority) {
        if (priority == RequestPriority.CRITICAL) {
            inFlight.incrementAndGet();
            admitted.incrementAndGet();
            return new Permit(nanoClock.getAsLong());
        }
        int cap = Math.max(1, (int) Math.ceil(effectiveLimit() * priority.admissionFraction()));
        for (;;) {
            int cur = inFlight.get();
            if (cur >= cap) { rejected.incrementAndGet(); return null; }
            if (inFlight.compareAndSet(cur, cur + 1)) { admitted.incrementAndGet(); return new Permit(nanoClock.getAsLong()); }
        }
    }

    /** Release the permit and feed the controller with the observed outcome. */
    public void onComplete(Permit permit, boolean success) {
        int before = inFlight.getAndDecrement();
        double rtt = Math.max(1, nanoClock.getAsLong() - permit.startNanos);
        update(rtt, success, before);
    }

    /** Called by the virtual-thread scheduler lag probe. */
    public synchronized void onSchedulerLag(double lagMillis) {
        if (lagMillis > cfg.lagThresholdMillis()) {
            limit = Math.max(cfg.minLimit(), limit * cfg.backoffRatio());
        }
    }

    private synchronized void update(double rtt, boolean success, int inflightAtStart) {
        if (!success) {                                    // AIMD: multiplicative decrease
            limit = Math.max(cfg.minLimit(), limit * cfg.backoffRatio());
            return;
        }
        shortRtt = shortRtt < 0 ? rtt : shortRtt * 0.8 + rtt * 0.2;
        if (longRtt < 0) longRtt = rtt;
        else if (shortRtt < longRtt) longRtt = longRtt * 0.9 + shortRtt * 0.1;   // fast decay when latency improves
        else longRtt = longRtt * 0.99 + shortRtt * 0.01;
        if (inflightAtStart < limit / 2) return;           // do not grow when the limit is not being exercised
        double gradient = Math.max(0.5, Math.min(1.0, cfg.rttTolerance() * longRtt / shortRtt));
        double queue = Math.max(1, Math.sqrt(limit));
        double next = limit * gradient + queue;
        limit = Math.min(cfg.maxLimit(), Math.max(cfg.minLimit(), limit * (1 - cfg.smoothing()) + next * cfg.smoothing()));
    }

    public double effectiveLimit() { return Math.min(limit, warmupCeiling()); }

    private double warmupCeiling() {
        long elapsed = millisClock.getAsLong() - startedAtMillis;
        double ramp = cfg.warmupMillis() <= 0 ? 1.0 : Math.min(1.0, elapsed / (double) cfg.warmupMillis());
        return Math.max(cfg.minLimit(), cfg.maxLimit() * (0.05 + 0.95 * ramp));
    }

    public int inFlight() { return inFlight.get(); }
    public long rejectedCount() { return rejected.get(); }
    public long admittedCount() { return admitted.get(); }
    public double currentLimit() { return limit; }
}
