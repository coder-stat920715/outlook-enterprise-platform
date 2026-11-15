package com.enterprise.outlook.common.resilience;

import java.util.function.DoubleConsumer;

/**
 * Virtual-thread queue-depth monitor. The VT scheduler (a ForkJoinPool) exposes no queue length, so we probe it:
 * a virtual thread sleeps 10ms and measures how late it is rescheduled. Growing lag == runnable-VT queue growing.
 */
public final class VirtualThreadLagMonitor implements AutoCloseable {
    private volatile boolean running = true;
    private volatile double lagMillis;
    private final Thread probe;

    public VirtualThreadLagMonitor(DoubleConsumer onLag) {
        this.probe = Thread.ofVirtual().name("vt-lag-probe").unstarted(() -> {
            while (running) {
                long t0 = System.nanoTime();
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
                double lag = Math.max(0, (System.nanoTime() - t0) / 1e6 - 10.0);
                lagMillis = lagMillis * 0.7 + lag * 0.3;
                onLag.accept(lagMillis);
            }
        });
        probe.start();
    }

    public double lagMillis() { return lagMillis; }

    @Override public void close() { running = false; probe.interrupt(); }
}
