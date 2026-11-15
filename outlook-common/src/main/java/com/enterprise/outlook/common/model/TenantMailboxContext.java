package com.enterprise.outlook.common.model;

import java.util.UUID;

/** Identity of the mailbox a request operates on, plus the acting principal. */
public record TenantMailboxContext(UUID tenantId, UUID mailboxId, UUID actorMailboxId, Region region) {
    public boolean isDelegated() { return !mailboxId.equals(actorMailboxId); }
    public String routingKey() { return tenantId + ":" + mailboxId; }
}
