package com.enterprise.outlook.mail.repo;

import com.enterprise.outlook.common.util.Db;
import com.enterprise.outlook.mail.dto.Dtos.MessageDetail;
import com.enterprise.outlook.mail.dto.Dtos.MessageSummary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Partition-aware repository. EVERY statement carries
 *   tenant_id = :t AND mailbox_id = :m AND received_at >= :lo AND received_at < :hi
 * so PostgreSQL prunes to a single monthly range partition and a single hash sub-partition.
 * Pagination is keyset (received_at, message_id) – OFFSET is never used.
 * Callers must run inside ShardExecutor (the shard is bound to the thread).
 */
@Repository
public class MessageRepository {
    public static final Duration WINDOW = Duration.ofDays(30);
    public static final int MAX_WINDOWS = 24;                 // ~2 years of history per page request

    public record Cursor(Instant receivedAt, UUID messageId) {
        public String encode() {
            long micros = ChronoUnit.MICROS.between(Instant.EPOCH, receivedAt);
            return Base64.getUrlEncoder().withoutPadding().encodeToString((micros + ":" + messageId).getBytes(StandardCharsets.UTF_8));
        }
        public static Cursor decode(String s) {
            String[] p = new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8).split(":");
            return new Cursor(Instant.EPOCH.plus(Long.parseLong(p[0]), ChronoUnit.MICROS), UUID.fromString(p[1]));
        }
    }

    public record Row(UUID tenantId, UUID mailboxId, UUID folderId, UUID messageId, UUID conversationId, String internetMessageId,
                      String inReplyTo, String subject, String senderEmail, String senderName, String snippet, String toJson,
                      String ccJson, String body, String bodyType, boolean isRead, boolean hasAttachments, short importance,
                      String deliveryState, Instant receivedAt) {}

    public record Locator(Instant receivedAt) {}

    private static final String SUMMARY_COLS =
            "message_id, conversation_id, subject, sender_email, sender_name, snippet, is_read, is_flagged, has_attachments, importance, received_at";

    private static final RowMapper<MessageSummary> SUMMARY = (rs, i) -> new MessageSummary(
            rs.getObject("message_id", UUID.class), rs.getObject("conversation_id", UUID.class), rs.getString("subject"),
            rs.getString("sender_email"), rs.getString("sender_name"), rs.getString("snippet"), rs.getBoolean("is_read"),
            rs.getBoolean("is_flagged"), rs.getBoolean("has_attachments"), rs.getShort("importance"), Db.instant(rs, "received_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public MessageRepository(@Qualifier("shardJdbc") NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Idempotent insert (Kafka is at-least-once). @return false when the row already existed. */
    public boolean insert(Row r) {
        int n = jdbc.update("""
                INSERT INTO mail_messages (tenant_id, mailbox_id, folder_id, message_id, conversation_id, internet_message_id, in_reply_to,
                    subject, sender_email, sender_name, snippet, to_recipients, cc_recipients, body_content, body_type,
                    is_read, has_attachments, importance, delivery_state, received_at)
                VALUES (:t, :m, :f, :id, :conv, :imid, :irt, :subject, :se, :sn, :snippet, CAST(:to AS jsonb), CAST(:cc AS jsonb),
                    :body, :bt, :read, :att, :imp, :state, :at)
                ON CONFLICT (tenant_id, mailbox_id, received_at, message_id) DO NOTHING""",
                new MapSqlParameterSource().addValue("t", r.tenantId()).addValue("m", r.mailboxId()).addValue("f", r.folderId())
                        .addValue("id", r.messageId()).addValue("conv", r.conversationId()).addValue("imid", r.internetMessageId())
                        .addValue("irt", r.inReplyTo()).addValue("subject", r.subject()).addValue("se", r.senderEmail())
                        .addValue("sn", r.senderName()).addValue("snippet", r.snippet()).addValue("to", r.toJson()).addValue("cc", r.ccJson())
                        .addValue("body", r.body()).addValue("bt", r.bodyType()).addValue("read", r.isRead())
                        .addValue("att", r.hasAttachments()).addValue("imp", r.importance()).addValue("state", r.deliveryState())
                        .addValue("at", Db.ts(r.receivedAt())));
        if (n == 1) {
            jdbc.update("""
                    INSERT INTO message_locator (tenant_id, mailbox_id, message_id, received_at) VALUES (:t, :m, :id, :at)
                    ON CONFLICT (tenant_id, mailbox_id, message_id) DO NOTHING""",
                    new MapSqlParameterSource().addValue("t", r.tenantId()).addValue("m", r.mailboxId())
                            .addValue("id", r.messageId()).addValue("at", Db.ts(r.receivedAt())));
        }
        return n == 1;
    }

    public Optional<Locator> locator(UUID tenant, UUID mailbox, UUID messageId) {
        return jdbc.query("SELECT received_at FROM message_locator WHERE tenant_id = :t AND mailbox_id = :m AND message_id = :id",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox).addValue("id", messageId),
                (rs, i) -> new Locator(Db.instant(rs, "received_at"))).stream().findFirst();
    }

    /** Keyset-paginated folder listing. Walks backwards in 30-day windows; each window query prunes partitions. */
    public List<MessageSummary> page(UUID tenant, UUID mailbox, UUID folder, Cursor cursor, int limit, boolean unreadOnly, Instant now) {
        List<MessageSummary> out = new ArrayList<>(limit);
        Instant hi = cursor == null ? now.plusSeconds(60) : cursor.receivedAt().plus(1, ChronoUnit.MICROS);
        String sql = "SELECT " + SUMMARY_COLS + " FROM mail_messages"
                + " WHERE tenant_id = :t AND mailbox_id = :m AND folder_id = :f"
                + " AND received_at >= :lo AND received_at < :hi"
                + (unreadOnly ? " AND is_read = false" : "")
                + (cursor != null ? " AND (received_at, message_id) < (:cts, :cid)" : "")
                + " ORDER BY received_at DESC, message_id DESC LIMIT :lim";
        for (int w = 0; w < MAX_WINDOWS && out.size() < limit; w++) {
            Instant lo = hi.minus(WINDOW);
            var p = new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox).addValue("f", folder)
                    .addValue("lo", Db.ts(lo)).addValue("hi", Db.ts(hi)).addValue("lim", limit - out.size());
            if (cursor != null) p.addValue("cts", Db.ts(cursor.receivedAt())).addValue("cid", cursor.messageId());
            out.addAll(jdbc.query(sql, p, SUMMARY));
            hi = lo;
        }
        return out;
    }

    public Optional<MessageDetail> find(UUID tenant, UUID mailbox, UUID messageId, Instant receivedAt) {
        return jdbc.query("""
                SELECT message_id, conversation_id, subject, sender_email, sender_name, snippet, is_read, is_flagged, has_attachments,
                       importance, received_at, folder_id, body_content, body_type, CAST(to_recipients AS text) AS to_r,
                       CAST(cc_recipients AS text) AS cc_r, is_pinned, CAST(categories AS text) AS cats, delivery_state, version
                FROM mail_messages
                WHERE tenant_id = :t AND mailbox_id = :m AND message_id = :id
                  AND received_at >= :lo AND received_at < :hi""",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox).addValue("id", messageId)
                        .addValue("lo", Db.ts(receivedAt)).addValue("hi", Db.ts(receivedAt.plus(1, ChronoUnit.MICROS))),
                (rs, i) -> new MessageDetail(SUMMARY.mapRow(rs, i), rs.getObject("folder_id", UUID.class), rs.getString("body_content"),
                        rs.getString("body_type"), rs.getString("to_r"), rs.getString("cc_r"), rs.getBoolean("is_pinned"),
                        rs.getString("cats"), rs.getString("delivery_state"), rs.getLong("version"))).stream().findFirst();
    }

    /** Thread view: all messages of a conversation within +/-180 days of an anchor message. */
    public List<MessageSummary> thread(UUID tenant, UUID mailbox, UUID conversationId, Instant anchor) {
        return jdbc.query("SELECT " + SUMMARY_COLS + """
                 FROM mail_messages
                WHERE tenant_id = :t AND mailbox_id = :m AND conversation_id = :c
                  AND received_at >= :lo AND received_at < :hi
                ORDER BY received_at DESC, message_id DESC LIMIT 200""",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox).addValue("c", conversationId)
                        .addValue("lo", Db.ts(anchor.minus(180, ChronoUnit.DAYS))).addValue("hi", Db.ts(anchor.plus(180, ChronoUnit.DAYS))),
                SUMMARY);
    }

    public int update(UUID tenant, UUID mailbox, UUID messageId, Instant receivedAt, boolean isRead, boolean isFlagged,
                      boolean isPinned, String categoriesJson, UUID folderId, long expectedVersion) {
        return jdbc.update("""
                UPDATE mail_messages SET is_read = :read, is_flagged = :flag, is_pinned = :pin, categories = CAST(:cats AS jsonb),
                       folder_id = :f, updated_at = now(), version = version + 1
                WHERE tenant_id = :t AND mailbox_id = :m AND message_id = :id
                  AND received_at >= :lo AND received_at < :hi AND version = :v""",
                new MapSqlParameterSource().addValue("read", isRead).addValue("flag", isFlagged).addValue("pin", isPinned)
                        .addValue("cats", categoriesJson).addValue("f", folderId).addValue("t", tenant).addValue("m", mailbox)
                        .addValue("id", messageId).addValue("lo", Db.ts(receivedAt))
                        .addValue("hi", Db.ts(receivedAt.plus(1, ChronoUnit.MICROS))).addValue("v", expectedVersion));
    }

    public int updateDeliveryState(UUID tenant, UUID mailbox, UUID messageId, Instant receivedAt, String state) {
        return jdbc.update("""
                UPDATE mail_messages SET delivery_state = :s, updated_at = now()
                WHERE tenant_id = :t AND mailbox_id = :m AND message_id = :id AND received_at >= :lo AND received_at < :hi""",
                new MapSqlParameterSource().addValue("s", state).addValue("t", tenant).addValue("m", mailbox)
                        .addValue("id", messageId).addValue("lo", Db.ts(receivedAt))
                        .addValue("hi", Db.ts(receivedAt.plus(1, ChronoUnit.MICROS))));
    }

    public void audit(UUID tenant, UUID mailbox, UUID actor, String action, UUID entityId, String detailJson, Instant at) {
        jdbc.update("""
                INSERT INTO mail_audit_events (tenant_id, mailbox_id, event_id, actor_id, action, entity_id, detail, received_at)
                VALUES (:t, :m, :e, :a, :act, :ent, CAST(:d AS jsonb), :at)""",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox).addValue("e", UUID.randomUUID())
                        .addValue("a", actor).addValue("act", action).addValue("ent", entityId).addValue("d", detailJson)
                        .addValue("at", Db.ts(at)));
    }
}
