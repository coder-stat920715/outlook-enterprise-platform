package com.enterprise.outlook.mail.web;

import com.enterprise.outlook.common.sharding.ShardExecutor;
import com.enterprise.outlook.mail.dto.Dtos.*;
import com.enterprise.outlook.mail.repo.FolderRepository;
import com.enterprise.outlook.mail.repo.MailboxRepository;
import com.enterprise.outlook.mail.service.MailboxAccessService;
import com.enterprise.outlook.mail.service.MailboxAccessService.Need;
import com.enterprise.outlook.mail.service.MessageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mailbox REST API. Identity headers (X-Tenant-ID, X-User-Mailbox-ID) are injected by the gateway from the validated token. */
@RestController
@RequestMapping("/v1/mailboxes/{mailboxId}")
public class MailboxController {
    private final MessageService messages;
    private final MailboxAccessService access;
    private final ShardExecutor shards;
    private final FolderRepository folders;
    private final MailboxRepository mailboxes;

    public MailboxController(MessageService messages, MailboxAccessService access, ShardExecutor shards,
                             FolderRepository folders, MailboxRepository mailboxes) {
        this.messages = messages; this.access = access; this.shards = shards; this.folders = folders; this.mailboxes = mailboxes;
    }

    private UUID actor(UUID actor, UUID mailbox) { return actor == null ? mailbox : actor; }

    private void full(UUID tenant, UUID mailbox, UUID actor) { access.require(tenant, mailbox, actor(actor, mailbox), Need.FULL_ACCESS); }

    // ---- send ---------------------------------------------------------------------------------------------------------
    @PostMapping("/messages/send")
    public ResponseEntity<SendAccepted> send(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId,
                                             @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                                             @RequestBody SendRequest req) {
        SendAccepted a = messages.send(tenant, mailboxId, actor(actor, mailboxId), idem, req);
        return ResponseEntity.status(a.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED).header("Idempotent-Replayed", String.valueOf(a.replayed())).body(a);
    }

    // ---- folders & messages ----------------------------------------------------------------------------------------------
    @GetMapping("/folders")
    public List<FolderView> folders(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId,
                                    @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor) {
        full(tenant, mailboxId, actor);
        return shards.read(tenant, mailboxId, () -> folders.list(tenant, mailboxId));
    }

    @PostMapping("/folders")
    public Map<String, UUID> createFolder(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId,
                                          @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor, @RequestBody CreateFolderRequest r) {
        full(tenant, mailboxId, actor);
        return Map.of("folderId", shards.transact(tenant, mailboxId, () -> folders.create(tenant, mailboxId, r.displayName(), r.parentFolderId())));
    }

    @GetMapping("/folders/{folderId}/messages")
    public PageResult<MessageSummary> list(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @PathVariable UUID folderId,
                                           @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor,
                                           @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String cursor,
                                           @RequestParam(defaultValue = "false") boolean unreadOnly) {
        full(tenant, mailboxId, actor);
        return messages.list(tenant, mailboxId, folderId, cursor, limit, unreadOnly);
    }

    @GetMapping("/messages/{messageId}")
    public MessageDetail get(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @PathVariable UUID messageId,
                             @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor) {
        full(tenant, mailboxId, actor);
        return messages.get(tenant, mailboxId, messageId);
    }

    @GetMapping("/messages/{messageId}/thread")
    public List<MessageSummary> thread(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @PathVariable UUID messageId,
                                       @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor) {
        full(tenant, mailboxId, actor);
        return messages.thread(tenant, mailboxId, messageId);
    }

    @PatchMapping("/messages/{messageId}")
    public MessageDetail patch(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @PathVariable UUID messageId,
                               @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor, @RequestBody PatchRequest req) {
        full(tenant, mailboxId, actor);
        return messages.patch(tenant, mailboxId, actor(actor, mailboxId), messageId, req);
    }

    // ---- delegation, rules, OOF, contacts, tasks ---------------------------------------------------------------------------
    @PutMapping("/delegates")
    public ResponseEntity<Void> grant(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId,
                                      @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor, @RequestBody GrantRequest r) {
        if (!actor(actor, mailboxId).equals(mailboxId)) access.require(tenant, mailboxId, actor, Need.FULL_ACCESS);
        shards.transact(tenant, mailboxId, () -> { mailboxes.grant(tenant, mailboxId, r.granteeMailboxId(), r.permission()); return null; });
        access.invalidate();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/delegates/{granteeId}/{permission}")
    public ResponseEntity<Void> revoke(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @PathVariable UUID granteeId,
                                       @PathVariable String permission, @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor) {
        full(tenant, mailboxId, actor);
        shards.transact(tenant, mailboxId, () -> { mailboxes.revoke(tenant, mailboxId, granteeId, permission); return null; });
        access.invalidate();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rules")
    public Map<String, UUID> addRule(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId,
                                     @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor, @RequestBody RuleRequest r) {
        full(tenant, mailboxId, actor);
        return Map.of("ruleId", shards.transact(tenant, mailboxId, () -> mailboxes.saveRule(tenant, mailboxId, r)));
    }

    @PutMapping("/settings/oof")
    public ResponseEntity<Void> oof(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId,
                                    @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor, @RequestBody OofRequest r) {
        full(tenant, mailboxId, actor);
        shards.transact(tenant, mailboxId, () -> { mailboxes.upsertOof(tenant, mailboxId, r); return null; });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/contacts")
    public Map<String, UUID> addContact(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId,
                                        @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor, @RequestBody ContactRequest r) {
        full(tenant, mailboxId, actor);
        return Map.of("contactId", shards.transact(tenant, mailboxId, () -> mailboxes.addContact(tenant, mailboxId, r)));
    }

    @GetMapping("/contacts")
    public List<ContactRequest> contacts(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId, @RequestParam String prefix,
                                         @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor) {
        full(tenant, mailboxId, actor);
        return shards.read(tenant, mailboxId, () -> mailboxes.contactsByPrefix(tenant, mailboxId, prefix, 20));
    }

    @GetMapping("/tasks")
    public List<TaskView> tasks(@RequestHeader("X-Tenant-ID") UUID tenant, @PathVariable UUID mailboxId,
                                @RequestHeader(value = "X-User-Mailbox-ID", required = false) UUID actor) {
        full(tenant, mailboxId, actor);
        return shards.read(tenant, mailboxId, () -> mailboxes.openTasks(tenant, mailboxId));
    }
}
