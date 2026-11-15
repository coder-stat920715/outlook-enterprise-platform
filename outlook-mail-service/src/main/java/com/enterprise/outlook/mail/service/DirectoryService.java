package com.enterprise.outlook.mail.service;

import com.enterprise.outlook.mail.repo.DirectoryRepository;
import com.enterprise.outlook.mail.repo.DirectoryRepository.Entry;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Cached directory lookups (L1 Caffeine; directory is small and slow-changing). */
@Service
public class DirectoryService {
    private final DirectoryRepository repo;
    private final Cache<String, Optional<Entry>> byEmail = Caffeine.newBuilder().maximumSize(500_000).expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<String, Optional<Entry>> byMailbox = Caffeine.newBuilder().maximumSize(500_000).expireAfterWrite(Duration.ofMinutes(5)).build();

    public DirectoryService(DirectoryRepository repo) { this.repo = repo; }

    public Optional<Entry> resolveEmail(UUID tenant, String email) {
        return byEmail.get(tenant + "|" + email.toLowerCase(), k -> repo.byEmail(tenant, email));
    }

    public Optional<Entry> resolveMailbox(UUID tenant, UUID mailbox) {
        return byMailbox.get(tenant + "|" + mailbox, k -> repo.byMailbox(tenant, mailbox));
    }

    public void invalidate() { byEmail.invalidateAll(); byMailbox.invalidateAll(); }
}
