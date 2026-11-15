package com.enterprise.outlook.common.model;

import java.time.Instant;
import java.util.UUID;

/** Change notification pushed to clients (sync.events topic, Redis Pub/Sub, SSE/WebSocket). */
public record SyncEvent(UUID tenantId, UUID mailboxId, long changeSeq, String entityType, UUID entityId,
                        String changeType, UUID folderId, Instant at) {}
