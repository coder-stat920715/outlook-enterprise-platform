package com.enterprise.outlook.gateway.auth;

public record Claims(boolean active, String subject, String tenantId, String mailboxId, String region, long expEpochSeconds) {
    public static final String ATTR = "outlook.claims";
    public long hardExpiryMillis() { return expEpochSeconds * 1000L; }
}
