package com.enterprise.outlook.common.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class Ids {
    private Ids() {}
    /** Deterministic UUID (idempotent Kafka redelivery => same row id). */
    public static UUID deterministic(String... parts) {
        return UUID.nameUUIDFromBytes(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
    }
    /** Well-known folder id per mailbox. */
    public static UUID folderId(UUID mailboxId, String wellKnown) { return deterministic("folder", mailboxId.toString(), wellKnown); }
}
