package com.enterprise.outlook.mail.web;

import com.enterprise.outlook.mail.dto.Dtos.*;
import com.enterprise.outlook.mail.repo.DirectoryRepository;
import com.enterprise.outlook.mail.service.*;
import com.enterprise.outlook.mail.service.MailboxAccessService.Need;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class SyncSearchAttachmentController {
    private final DeltaSyncService delta;
    private final SearchService search;
    private final AttachmentService attachments;
    private final MailboxAccessService access;
    private final MailboxAdminService admin;
    private final DirectoryRepository directory;

    public SyncSearchAttachmentController(DeltaSyncService delta, SearchService search, AttachmentService attachments,
                                          MailboxAccessService access, MailboxAdminService admin, DirectoryRepository directory) {
        this.delta = delta; this.search = search; this.attachments = attachments; this.access = access; this.admin = admin; this.directory = directory;
    }

    private void full(UUID tenant, UUID mailbox, UUID actor) { access.require(tenant, mailbox, actor == null ? mailbox : actor, Need.FULL_ACCESS); }

    @GetMapping("/mailboxes/{mailboxId}/messages/delta")
    public DeltaResponse delta(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId,
                               @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor,
                               @RequestParam(required = false) String deltaToken, @RequestParam(defaultValue = "200") int limit) {
        full(tenant, mailboxId, actor);
        return delta.fetch(tenant, mailboxId, deltaToken, limit);
    }

    @GetMapping("/mailboxes/{mailboxId}/search")
    public List<SearchHit> search(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @RequestParam String q,
                                  @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor, @RequestParam(defaultValue = "25") int limit) {
        full(tenant, mailboxId, actor);
        return search.search(tenant, mailboxId, q, limit);
    }

    @PostMapping("/mailboxes/{mailboxId}/attachments/upload-url")
    public UploadUrlResponse uploadUrl(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId,
                                       @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor, @RequestBody UploadUrlRequest r) {
        full(tenant, mailboxId, actor);
        return attachments.createUploadUrl(tenant, mailboxId, r);
    }

    @PostMapping("/mailboxes/{mailboxId}/attachments/{attachmentId}/complete")
    public ResponseEntity<Void> complete(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @PathVariable UUID attachmentId,
                                         @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor) {
        full(tenant, mailboxId, actor);
        attachments.complete(tenant, mailboxId, attachmentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mailboxes/{mailboxId}/attachments/{attachmentId}/download-url")
    public DownloadUrlResponse download(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @PathVariable UUID attachmentId,
                                        @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor) {
        full(tenant, mailboxId, actor);
        return attachments.downloadUrl(tenant, mailboxId, attachmentId);
    }

    /** Global Address List autocomplete (prefix, then trigram fuzzy). */
    @GetMapping("/directory/gal")
    public List<Map<String, Object>> gal(@RequestHeader("X-Tenant-ID") UUID tenant, @RequestParam String q) {
        return directory.gal(tenant, q, 10).stream()
                .map(e -> Map.<String, Object>of("email", e.email(), "displayName", e.displayName(), "mailboxId", e.mailboxId())).toList();
    }

    /** Admin: provision a mailbox (platform operators / onboarding pipeline; restrict at the gateway). */
    @PostMapping("/admin/mailboxes")
    public ProvisionResponse provision(@RequestHeader("X-Tenant-ID") UUID tenant, @RequestBody ProvisionRequest r) {
        return admin.provision(tenant, r);
    }
}
