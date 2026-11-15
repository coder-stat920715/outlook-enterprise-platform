package com.enterprise.outlook.common.model;

import java.time.Instant;

/** Wrapper stored on mail.retry.tier1 / tier2 / dlq topics (store-and-forward). */
public record RetryEnvelope(Kind kind, String payload, int attempt, Instant firstAttemptAt, Instant notBefore, String lastError) {
    public enum Kind { OUTBOUND, INBOUND }
}
