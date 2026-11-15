package com.enterprise.outlook.mail.service;

import com.enterprise.outlook.common.model.*;
import com.enterprise.outlook.common.resilience.DependencyUnavailableException;
import com.enterprise.outlook.common.sharding.ShardExecutor;
import com.enterprise.outlook.common.util.Ids;
import com.enterprise.outlook.common.util.Json;
import com.enterprise.outlook.mail.dto.Dtos.*;
import com.enterprise.outlook.mail.kafka.MailPublisher;
import com.enterprise.outlook.mail.repo.*;
import com.enterprise.outlook.mail.repo.DirectoryRepository.Entry;
import com.enterprise.outlook.mail.repo.MailboxRepository.Oof;
import com.enterprise.outlook.mail.repo.MessageRepository.Cursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class MessageService {
    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private final ShardExecutor shards;
    private final MessageRepository messages;
    private final FolderRepository folders;
    private final MailboxRepository mailboxes;
    private final AttachmentRepository attachments;
    private final DeltaSyncService deltas;
    private final DirectoryService directory;
    private final MailboxAccessService access;
    private final RulesEngine rules;
    private final MailPublisher publisher;
    private final StringRedisTemplate redis;
    private final SmtpRelaySimulator relay;

    public MessageService(ShardExecutor shards, MessageRepository messages, FolderRepository folders, MailboxRepository mailboxes,
                          AttachmentRepository attachments, DeltaSyncService deltas, DirectoryService directory,
                          MailboxAccessService access, RulesEngine rules, MailPublisher publisher, StringRedisTemplate redis,
                          SmtpRelaySimulator relay) {
        this.shards = shards; this.messages = messages; this.folders = folders; this.mailboxes = mailboxes; this.attachments = attachments;
        this.deltas = deltas; this.directory = directory; this.access = access; this.rules = rules; this.publisher = publisher;
        this.redis = redis; this.relay = relay;
    }

    // =========================================================================================================== send
    public SendAccepted send(UUID tenant, UUID mailbox, UUID actor, String idempotencyKey, SendRequest req) {
        validate(req);
        Entry target = directory.resolveMailbox(tenant, mailbox).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "mailbox"));
        Entry acting = directory.resolveMailbox(tenant, actor).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "actor"));

        String senderEmail = target.email(), senderName = target.displayName(), onBehalf = null;
        if (!actor.equals(mailbox)) {                                  // delegation: SendAs beats SendOnBehalf
            Set<String> perms = access.permissions(tenant, mailbox, actor);
            if (perms.contains("SEND_AS")) { /* appears to come from the shared mailbox */ }
            else if (perms.contains("SEND_ON_BEHALF")) { senderEmail = acting.email(); senderName = acting.displayName(); onBehalf = target.email(); }
            else throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SendAs / SendOnBehalf required");
        }

        UUID messageId = UUID.randomUUID();
        String idemKey = idempotencyKey == null ? null : "idem:" + tenant + ":" + mailbox + ":" + idempotencyKey;
        if (idemKey != null) {
            Boolean first = redis.opsForValue().setIfAbsent(idemKey, messageId.toString(), Duration.ofHours(24));
            if (Boolean.FALSE.equals(first)) {
                String existing = redis.opsForValue().get(idemKey);
                return new SendAccepted(existing == null ? messageId : UUID.fromString(existing), null, "QUEUED", true);
            }
        }
        try {
            List<MailEnvelope.AttachmentRef> refs = resolveAttachments(tenant, mailbox, req.attachmentIds());
            String internetId = "<" + messageId + "@" + senderEmail.substring(senderEmail.indexOf('@') + 1) + ">";
            String inReplyTo = null;
            UUID conversationId = null;
            if (req.inReplyToMessageId() != null) {
                try {
                    var parent = shards.read(tenant, mailbox, () -> messages.locator(tenant, mailbox, req.inReplyToMessageId())
                            .flatMap(l -> messages.find(tenant, mailbox, req.inReplyToMessageId(), l.receivedAt())));
                    if (parent.isPresent()) { conversationId = parent.get().summary().conversationId(); inReplyTo = "<" + req.inReplyToMessageId() + ">"; }
                } catch (DependencyUnavailableException degraded) { log.warn("shard degraded: threading falls back to a new conversation"); }
            }
            if (conversationId == null) conversationId = ConversationResolver.resolve(tenant, internetId, null, null);

            MailEnvelope env = new MailEnvelope(messageId, tenant, mailbox, actor, senderEmail, senderName, onBehalf,
                    nz(req.to()), nz(req.cc()), nz(req.bcc()), req.subject() == null ? "" : req.subject(),
                    req.body() == null ? "" : req.body(), req.bodyType() == null ? "text" : req.bodyType(), internetId, inReplyTo,
                    conversationId, req.importance() == null ? (short) 1 : req.importance(), refs, Instant.now(), Region.parseOrDefault(target.region(), Region.AMER),
                    false, null);
            publisher.outbound(env);                                    // durable before we answer 202
            return new SendAccepted(messageId, conversationId, DeliveryState.QUEUED.name(), false);
        } catch (RuntimeException e) {
            if (idemKey != null) redis.delete(idemKey);                 // allow the client to retry with the same key
            throw e;
        }
    }

    private static <T> List<T> nz(List<T> l) { return l == null ? List.of() : l; }

    private void validate(SendRequest r) {
        int n = nz(r.to()).size() + nz(r.cc()).size() + nz(r.bcc()).size();
        if (n == 0) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "at least one recipient required");
        if (n > 500) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "too many recipients (max 500)");
        if (r.subject() != null && r.subject().length() > 998) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "subject too long");
    }

    private List<MailEnvelope.AttachmentRef> resolveAttachments(UUID tenant, UUID mailbox, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        var found = shards.read(tenant, mailbox, () -> attachments.findAll(tenant, mailbox, ids));
        if (found.size() != ids.size() || found.stream().anyMatch(a -> !"UPLOADED".equals(a.status())))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "attachment missing or upload not completed");
        return found.stream().map(a -> new MailEnvelope.AttachmentRef(a.attachmentId(), a.fileName(), a.contentType(), a.sizeBytes(),
                a.objectKey(), a.inline(), a.contentId())).toList();
    }

    // ===================================================================================== outbound (mail.outbound consumer)
    public DeliveryOutcome processOutbound(MailEnvelope env) {
        List<SyncEvent> events = shards.transact(env.tenantId(), env.senderMailboxId(), () -> {
            UUID folder = Ids.folderId(env.senderMailboxId(), "SENT");
            return store(env, env.senderMailboxId(), env.messageId(), folder, true, false, null, DeliveryState.SENDING, "SEND");
        });
        deltas.publishAfterCommit(events);

        Set<String> seen = new HashSet<>();
        List<MailEnvelope.Recipient> external = new ArrayList<>();
        int internal = 0;
        for (var r : env.allRecipients()) {
            if (r.email() == null || !seen.add(r.email().toLowerCase())) continue;
            Optional<Entry> e = directory.resolveEmail(env.tenantId(), r.email());
            if (e.isPresent()) { publisher.inbound(new InboundDelivery(env, e.get().mailboxId(), r.email().toLowerCase())); internal++; }
            else external.add(r);
        }
        if (!external.isEmpty()) relay.relay(env, external);

        List<SyncEvent> done = shards.transact(env.tenantId(), env.senderMailboxId(), () -> {
            messages.updateDeliveryState(env.tenantId(), env.senderMailboxId(), env.messageId(), env.sentAt(), DeliveryState.DELIVERED.name());
            return List.of(deltas.record(env.tenantId(), env.senderMailboxId(), "MESSAGE", env.messageId(), "UPDATED", Ids.folderId(env.senderMailboxId(), "SENT")));
        });
        deltas.publishAfterCommit(done);
        return new DeliveryOutcome.Delivered(internal + external.size());
    }

    /** Best-effort: surface DEFERRED_RETRY on the sender's Sent copy while the message waits in a retry tier. */
    public void markDeferred(MailEnvelope env) {
        try {
            shards.transact(env.tenantId(), env.senderMailboxId(), () ->
                    messages.updateDeliveryState(env.tenantId(), env.senderMailboxId(), env.messageId(), env.sentAt(), DeliveryState.DEFERRED_RETRY.name()));
        } catch (RuntimeException e) { log.debug("could not mark deferred (shard degraded): {}", e.toString()); }
    }

    // ====================================================================================== inbound (mail.inbound consumer)
    private record InboundResult(List<SyncEvent> events, Oof autoReply) {}

    public void processInbound(InboundDelivery d) {
        MailEnvelope env = d.envelope();
        UUID tenant = env.tenantId(), mbox = d.recipientMailboxId();
        UUID id = Ids.deterministic(env.messageId().toString(), mbox.toString());

        InboundResult res = shards.transact(tenant, mbox, () -> {
            if (messages.locator(tenant, mbox, id).isPresent()) return new InboundResult(List.of(), null);      // duplicate delivery
            var rr = rules.apply(mailboxes.activeRules(tenant, mbox), env, Ids.folderId(mbox, "INBOX"));
            List<SyncEvent> ev = store(env, mbox, id, rr.folderId(), rr.markRead(), rr.flag(), rr.category(), DeliveryState.DELIVERED, "DELIVER");
            Oof oof = null;
            if (!env.autoGenerated() && !env.senderEmail().equalsIgnoreCase(d.recipientEmail())) {
                Optional<Oof> active = mailboxes.activeOof(tenant, mbox, Instant.now());
                if (active.isPresent() && mailboxes.markOofReplied(tenant, mbox, env.senderEmail())) oof = active.get();
            }
            return new InboundResult(ev, oof);
        });
        deltas.publishAfterCommit(res.events());
        if (!res.events().isEmpty()) publishSearchDoc(env, mbox, id, Ids.folderId(mbox, "INBOX"));
        if (res.autoReply() != null) sendAutoReply(d, res.autoReply());
    }

    private void sendAutoReply(InboundDelivery d, Oof oof) {
        MailEnvelope in = d.envelope();
        UUID mid = UUID.randomUUID();
        String from = d.recipientEmail();
        var reply = new MailEnvelope(mid, in.tenantId(), d.recipientMailboxId(), d.recipientMailboxId(), from, null, null,
                List.of(new MailEnvelope.Recipient(in.senderEmail(), in.senderName())), List.of(), List.of(),
                oof.subject() + ": " + in.subject(), oof.replyText(), "text", "<" + mid + "@" + from.substring(from.indexOf('@') + 1) + ">",
                in.internetMessageId(), in.conversationId(), (short) 1, List.of(), Instant.now(), in.region(), true, null);
        try { publisher.outbound(reply); } catch (RuntimeException e) { log.warn("OOF auto-reply not queued: {}", e.toString()); }
    }

    /** Persists one copy of the message + counters + delta log row (must run inside a shard transaction). */
    private List<SyncEvent> store(MailEnvelope env, UUID mailbox, UUID messageId, UUID folder, boolean read, boolean flagged,
                                  String category, DeliveryState state, String auditAction) {
        UUID tenant = env.tenantId();
        String body = env.bodyContent() == null ? "" : env.bodyContent();
        var row = new MessageRepository.Row(tenant, mailbox, folder, messageId, env.conversationId(), env.internetMessageId(), env.inReplyTo(),
                env.subject(), env.senderEmail(), env.senderName(), snippet(body), Json.write(env.to()), Json.write(nz(env.cc())), body,
                env.bodyType(), read, !nz(env.attachments()).isEmpty(), env.importance(), state.name(), env.sentAt());
        boolean inserted = messages.insert(row);
        if (!inserted) return List.of();
        for (var a : nz(env.attachments())) {
            UUID attId = mailbox.equals(env.senderMailboxId()) ? a.attachmentId() : Ids.deterministic(a.attachmentId().toString(), mailbox.toString());
            attachments.insertForMessage(tenant, mailbox, attId, messageId, a.fileName(), a.contentType(), a.sizeBytes(), a.objectKey(), a.inline(), a.contentId());
        }
        folders.adjust(tenant, mailbox, folder, 1, read ? 0 : 1);
        if (flagged) mailboxes.projectFlagAsTask(tenant, mailbox, messageId, env.subject(), Instant.now().plus(Duration.ofDays(1)));
        messages.audit(tenant, mailbox, env.actingMailboxId(), auditAction, messageId, "{}", env.sentAt());
        return List.of(deltas.record(tenant, mailbox, "MESSAGE", messageId, "CREATED", folder));
    }

    private void publishSearchDoc(MailEnvelope env, UUID mailbox, UUID id, UUID folder) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("tenantId", env.tenantId()); doc.put("mailboxId", mailbox); doc.put("messageId", id); doc.put("folderId", folder);
        doc.put("subject", env.subject()); doc.put("sender", env.senderEmail());
        doc.put("snippet", snippet(env.bodyContent() == null ? "" : env.bodyContent()));
        String b = env.bodyContent() == null ? "" : env.bodyContent();
        doc.put("body", b.length() > 20_000 ? b.substring(0, 20_000) : b);
        doc.put("attachments", nz(env.attachments()).stream().map(MailEnvelope.AttachmentRef::fileName).toList());
        doc.put("receivedAt", env.sentAt());
        publisher.searchDoc(env.tenantId(), mailbox, Json.write(doc));
    }

    static String snippet(String body) {
        String s = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    // ================================================================================================================= reads
    public PageResult<MessageSummary> list(UUID tenant, UUID mailbox, UUID folder, String cursorToken, int limit, boolean unreadOnly) {
        int lim = Math.max(1, Math.min(limit, 100));
        Cursor cursor = cursorToken == null || cursorToken.isBlank() ? null : Cursor.decode(cursorToken);
        List<MessageSummary> rows = shards.read(tenant, mailbox, () -> messages.page(tenant, mailbox, folder, cursor, lim + 1, unreadOnly, Instant.now()));
        boolean more = rows.size() > lim;
        List<MessageSummary> items = more ? rows.subList(0, lim) : rows;
        String next = more ? new Cursor(items.get(items.size() - 1).receivedAt(), items.get(items.size() - 1).messageId()).encode() : null;
        return new PageResult<>(items, next);
    }

    public MessageDetail get(UUID tenant, UUID mailbox, UUID messageId) {
        return shards.read(tenant, mailbox, () -> messages.locator(tenant, mailbox, messageId)
                .flatMap(l -> messages.find(tenant, mailbox, messageId, l.receivedAt()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "message")));
    }

    public List<MessageSummary> thread(UUID tenant, UUID mailbox, UUID anyMessageInThread) {
        return shards.read(tenant, mailbox, () -> {
            var loc = messages.locator(tenant, mailbox, anyMessageInThread).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "message"));
            var d = messages.find(tenant, mailbox, anyMessageInThread, loc.receivedAt()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "message"));
            return messages.thread(tenant, mailbox, d.summary().conversationId(), loc.receivedAt());
        });
    }

    // ================================================================================================================ updates
    public MessageDetail patch(UUID tenant, UUID mailbox, UUID actor, UUID messageId, PatchRequest req) {
        List<SyncEvent> events = new ArrayList<>();
        MessageDetail updated = shards.transact(tenant, mailbox, () -> {
            var loc = messages.locator(tenant, mailbox, messageId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "message"));
            MessageDetail d = messages.find(tenant, mailbox, messageId, loc.receivedAt()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "message"));
            var s = d.summary();
            boolean read = req.isRead() != null ? req.isRead() : s.isRead();
            boolean flag = req.isFlagged() != null ? req.isFlagged() : s.isFlagged();
            boolean pin = req.isPinned() != null ? req.isPinned() : d.isPinned();
            UUID folder = req.folderId() != null ? req.folderId() : d.folderId();
            String cats = req.categories() != null ? Json.write(req.categories()) : d.categories();
            if (messages.update(tenant, mailbox, messageId, loc.receivedAt(), read, flag, pin, cats, folder, d.version()) == 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "message was modified concurrently");

            boolean moved = !folder.equals(d.folderId());
            if (moved) {
                folders.adjust(tenant, mailbox, d.folderId(), -1, s.isRead() ? 0 : -1);
                folders.adjust(tenant, mailbox, folder, 1, read ? 0 : 1);
            } else if (read != s.isRead()) {
                folders.adjust(tenant, mailbox, folder, 0, read ? -1 : 1);
            }
            if (flag && !s.isFlagged()) mailboxes.projectFlagAsTask(tenant, mailbox, messageId, s.subject(), Instant.now().plus(Duration.ofDays(1)));
            if (!flag && s.isFlagged()) mailboxes.completeTaskFor(tenant, mailbox, messageId);
            events.add(deltas.record(tenant, mailbox, "MESSAGE", messageId, moved ? "MOVED" : "UPDATED", folder));
            return messages.find(tenant, mailbox, messageId, loc.receivedAt()).orElseThrow();
        });
        deltas.publishAfterCommit(events);
        return updated;
    }
}
