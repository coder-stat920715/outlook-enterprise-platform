package com.enterprise.outlook.common.model;

public final class Topics {
    private Topics() {}
    public static final String OUTBOUND = "mail.outbound";
    public static final String INBOUND = "mail.inbound";
    public static final String RETRY_TIER1 = "mail.retry.tier1";
    public static final String RETRY_TIER2 = "mail.retry.tier2";
    public static final String DLQ = "mail.dlq";
    public static final String SYNC_EVENTS = "sync.events";
    public static final String SEARCH_INDEXER = "search.indexer";
}
