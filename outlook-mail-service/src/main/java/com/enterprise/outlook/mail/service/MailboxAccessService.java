package com.enterprise.outlook.mail.service;

import com.enterprise.outlook.common.sharding.ShardExecutor;
import com.enterprise.outlook.mail.repo.MailboxRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/** Shared-mailbox delegation RBAC: FullAccess (read/write), SendAs, SendOnBehalf. Permission sets are cached for 30s. */
@Service
public class MailboxAccessService {
    public enum Need { FULL_ACCESS, SEND_AS, SEND_ON_BEHALF }

    private final ShardExecutor shards;
    private final MailboxRepository mailboxes;
    private final Cache<String, Set<String>> cache = Caffeine.newBuilder().maximumSize(100_000).expireAfterWrite(Duration.ofSeconds(30)).build();

    public MailboxAccessService(ShardExecutor shards, MailboxRepository mailboxes) { this.shards = shards; this.mailboxes = mailboxes; }

    public Set<String> permissions(UUID tenant, UUID target, UUID actor) {
        if (target.equals(actor)) return Set.of("OWNER");
        return cache.get(tenant + "|" + target + "|" + actor, k -> shards.read(tenant, target, () -> mailboxes.permissions(tenant, target, actor)));
    }

    public void require(UUID tenant, UUID target, UUID actor, Need need) {
        Set<String> perms = permissions(tenant, target, actor);
        if (perms.contains("OWNER") || perms.contains(need.name()) || perms.contains("FULL_ACCESS") && need == Need.FULL_ACCESS) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing " + need + " on mailbox " + target);
    }

    public void invalidate() { cache.invalidateAll(); }
}
