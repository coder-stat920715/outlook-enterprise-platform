package com.enterprise.outlook.common.model;

/** Deployment regions used for phased regional drain / restore. */
public enum Region {
    APAC, EMEA, AMER;

    public static Region parseOrDefault(String s, Region fallback) {
        if (s == null) return fallback;
        try { return valueOf(s.trim().toUpperCase()); } catch (IllegalArgumentException e) { return fallback; }
    }
}
