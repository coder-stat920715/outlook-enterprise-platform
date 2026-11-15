package com.enterprise.outlook.mail.service;

import com.enterprise.outlook.common.sharding.ShardExecutor;
import com.enterprise.outlook.mail.dto.Dtos.ProvisionRequest;
import com.enterprise.outlook.mail.dto.Dtos.ProvisionResponse;
import com.enterprise.outlook.mail.repo.DirectoryRepository;
import com.enterprise.outlook.mail.repo.FolderRepository;
import com.enterprise.outlook.mail.repo.MailboxRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MailboxAdminService {
    private final ShardExecutor shards;
    private final DirectoryRepository directoryRepo;
    private final DirectoryService directory;
    private final MailboxRepository mailboxes;
    private final FolderRepository folders;

    public MailboxAdminService(ShardExecutor shards, DirectoryRepository directoryRepo, DirectoryService directory,
                               MailboxRepository mailboxes, FolderRepository folders) {
        this.shards = shards; this.directoryRepo = directoryRepo; this.directory = directory; this.mailboxes = mailboxes; this.folders = folders;
    }

    /** Idempotent by (tenant, email): directory entry (control plane) + mailbox, well-known folders, sync state on its shard. */
    public ProvisionResponse provision(UUID tenant, ProvisionRequest r) {
        String email = r.email().toLowerCase();
        UUID mailboxId = directoryRepo.byEmail(tenant, email).map(DirectoryRepository.Entry::mailboxId).orElse(UUID.randomUUID());
        String type = r.mailboxType() == null ? "USER" : r.mailboxType();
        String region = r.region() == null ? "AMER" : r.region();
        directoryRepo.upsert(tenant, new DirectoryRepository.Entry(email, mailboxId, r.displayName(), region, type));
        shards.transact(tenant, mailboxId, () -> {
            mailboxes.insertMailbox(tenant, mailboxId, email, r.displayName(), type, region);
            folders.createWellKnown(tenant, mailboxId);
            return null;
        });
        directory.invalidate();
        return new ProvisionResponse(tenant, mailboxId, email);
    }
}
