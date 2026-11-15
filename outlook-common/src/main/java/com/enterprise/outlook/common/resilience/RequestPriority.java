package com.enterprise.outlook.common.resilience;

/** Admission priority. Fraction = share of the current concurrency limit this class may occupy. */
public enum RequestPriority {
    CRITICAL(Double.POSITIVE_INFINITY),   // health checks, auth handshakes: never shed
    INTERACTIVE(1.0),                      // send / read / open message
    NORMAL(0.80),
    BACKGROUND(0.50);                      // delta sync polls, search, bulk

    private final double admissionFraction;
    RequestPriority(double f) { this.admissionFraction = f; }
    public double admissionFraction() { return admissionFraction; }

    public static RequestPriority classify(String method, String path, String priorityHeader) {
        if (path == null) return NORMAL;
        if (path.startsWith("/actuator")) return CRITICAL;
        if ("background".equalsIgnoreCase(priorityHeader)) return BACKGROUND;
        if (path.contains("/delta") || path.contains("/search") || path.contains("/gal") || path.contains("/freebusy")) return BACKGROUND;
        if (path.contains("/notifications") || path.contains("/ws/")) return NORMAL;
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return path.contains("/messages") || path.contains("/folders") ? INTERACTIVE : NORMAL;
        }
        return path.contains("/send") || path.contains("/messages") ? INTERACTIVE : NORMAL;
    }
}
