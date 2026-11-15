package com.enterprise.outlook.common.health;

/** Anti-flapping: N consecutive failures to go DOWN, M consecutive successes to come back UP. */
public final class HysteresisGate {
    private final int failThreshold;
    private final int successThreshold;
    private boolean up = true;
    private int fails, successes;

    public HysteresisGate(int failThreshold, int successThreshold) {
        this.failThreshold = failThreshold; this.successThreshold = successThreshold;
    }

    public synchronized boolean report(boolean ok) {
        if (ok) { successes++; fails = 0; if (!up && successes >= successThreshold) up = true; }
        else { fails++; successes = 0; if (up && fails >= failThreshold) up = false; }
        return up;
    }

    public synchronized boolean isUp() { return up; }
}
