package com.enterprise.outlook.mail.repo;

import com.enterprise.outlook.common.util.Db;
import com.enterprise.outlook.common.util.Json;
import com.enterprise.outlook.mail.dto.Dtos.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Shard-local mailbox data: mailbox row, delegation, inbox rules, OOF, contacts, tasks. */
@Repository
public class MailboxRepository {
    public record RuleRow(UUID ruleId, int priority, RuleCondition condition, RuleAction action) {}
    public record Oof(boolean enabled, Instant startAt, Instant endAt, String subject, String replyText) {}

    private final NamedParameterJdbcTemplate jdbc;

    public MailboxRepository(@Qualifier("shardJdbc") NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static MapSqlParameterSource p(UUID t, UUID m) { return new MapSqlParameterSource().addValue("t", t).addValue("m", m); }

    public void insertMailbox(UUID t, UUID m, String email, String name, String type, String region) {
        jdbc.update("""
                INSERT INTO mailboxes (tenant_id, mailbox_id, email, display_name, mailbox_type, region)
                VALUES (:t, :m, :e, :n, :ty, :r) ON CONFLICT DO NOTHING""",
                p(t, m).addValue("e", email).addValue("n", name).addValue("ty", type).addValue("r", region));
        jdbc.update("INSERT INTO mailbox_sync_state (tenant_id, mailbox_id) VALUES (:t, :m) ON CONFLICT DO NOTHING", p(t, m));
    }

    public Optional<String> emailOf(UUID t, UUID m) {
        return jdbc.query("SELECT email FROM mailboxes WHERE tenant_id = :t AND mailbox_id = :m", p(t, m), (rs, i) -> rs.getString(1)).stream().findFirst();
    }

    // ---- delegation -------------------------------------------------------------------------------------------------
    public Set<String> permissions(UUID t, UUID target, UUID grantee) {
        return Set.copyOf(jdbc.query("""
                SELECT permission FROM mailbox_permissions WHERE tenant_id = :t AND mailbox_id = :m AND grantee_mailbox_id = :g""",
                p(t, target).addValue("g", grantee), (rs, i) -> rs.getString(1)));
    }

    public void grant(UUID t, UUID target, UUID grantee, String permission) {
        jdbc.update("""
                INSERT INTO mailbox_permissions (tenant_id, mailbox_id, grantee_mailbox_id, permission) VALUES (:t, :m, :g, :p)
                ON CONFLICT DO NOTHING""", p(t, target).addValue("g", grantee).addValue("p", permission));
    }

    public void revoke(UUID t, UUID target, UUID grantee, String permission) {
        jdbc.update("DELETE FROM mailbox_permissions WHERE tenant_id = :t AND mailbox_id = :m AND grantee_mailbox_id = :g AND permission = :p",
                p(t, target).addValue("g", grantee).addValue("p", permission));
    }

    // ---- inbox rules ---------------------------------------------------------------------------------------------------
    public List<RuleRow> activeRules(UUID t, UUID m) {
        return jdbc.query("""
                SELECT rule_id, priority, CAST(condition AS text) AS c, CAST(action AS text) AS a FROM inbox_rules
                WHERE tenant_id = :t AND mailbox_id = :m AND enabled = true ORDER BY priority""", p(t, m),
                (rs, i) -> new RuleRow(rs.getObject("rule_id", UUID.class), rs.getInt("priority"),
                        Json.read(rs.getString("c"), RuleCondition.class), Json.read(rs.getString("a"), RuleAction.class)));
    }

    public UUID saveRule(UUID t, UUID m, RuleRequest r) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inbox_rules (tenant_id, mailbox_id, rule_id, priority, name, condition, action, enabled)
                VALUES (:t, :m, :id, :pr, :n, CAST(:c AS jsonb), CAST(:a AS jsonb), :en)""",
                p(t, m).addValue("id", id).addValue("pr", r.priority()).addValue("n", r.name())
                        .addValue("c", Json.write(r.condition())).addValue("a", Json.write(r.action())).addValue("en", r.enabled()));
        return id;
    }

    // ---- out of office ---------------------------------------------------------------------------------------------------
    public void upsertOof(UUID t, UUID m, OofRequest r) {
        jdbc.update("""
                INSERT INTO oof_settings (tenant_id, mailbox_id, enabled, start_at, end_at, subject, reply_text)
                VALUES (:t, :m, :en, :s, :e, :sub, :txt)
                ON CONFLICT (tenant_id, mailbox_id) DO UPDATE SET enabled = :en, start_at = :s, end_at = :e, subject = :sub, reply_text = :txt""",
                p(t, m).addValue("en", r.enabled()).addValue("s", Db.ts(r.startAt())).addValue("e", Db.ts(r.endAt()))
                        .addValue("sub", r.subject() == null ? "Out of Office" : r.subject()).addValue("txt", r.replyText() == null ? "" : r.replyText()));
        jdbc.update("DELETE FROM oof_replied WHERE tenant_id = :t AND mailbox_id = :m", p(t, m));   // new OOF period => fresh dedupe set
    }

    public Optional<Oof> activeOof(UUID t, UUID m, Instant now) {
        return jdbc.query("""
                SELECT enabled, start_at, end_at, subject, reply_text FROM oof_settings
                WHERE tenant_id = :t AND mailbox_id = :m AND enabled = true
                  AND (start_at IS NULL OR start_at <= :now) AND (end_at IS NULL OR end_at > :now)""",
                p(t, m).addValue("now", Db.ts(now)),
                (rs, i) -> new Oof(true, Db.instant(rs, "start_at"), Db.instant(rs, "end_at"), rs.getString("subject"), rs.getString("reply_text")))
                .stream().findFirst();
    }

    /** Dedup: true only the FIRST time this sender is auto-replied to during the OOF period. */
    public boolean markOofReplied(UUID t, UUID m, String senderEmail) {
        return jdbc.update("INSERT INTO oof_replied (tenant_id, mailbox_id, sender_email) VALUES (:t, :m, :s) ON CONFLICT DO NOTHING",
                p(t, m).addValue("s", senderEmail.toLowerCase())) == 1;
    }

    // ---- contacts & tasks ----------------------------------------------------------------------------------------------
    public UUID addContact(UUID t, UUID m, ContactRequest c) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO contacts (tenant_id, mailbox_id, contact_id, display_name, email, phone, company) VALUES (:t, :m, :id, :n, :e, :ph, :c)",
                p(t, m).addValue("id", id).addValue("n", c.displayName()).addValue("e", c.email()).addValue("ph", c.phone()).addValue("c", c.company()));
        return id;
    }

    public List<ContactRequest> contactsByPrefix(UUID t, UUID m, String prefix, int limit) {
        return jdbc.query("""
                SELECT display_name, email, phone, company FROM contacts
                WHERE tenant_id = :t AND mailbox_id = :m AND lower(display_name) LIKE :pfx ORDER BY lower(display_name) LIMIT :lim""",
                p(t, m).addValue("pfx", prefix.toLowerCase().replace("%", "").replace("_", "") + "%").addValue("lim", limit),
                (rs, i) -> new ContactRequest(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    }

    /** Flagged e-mail is projected as a To-Do task (idempotent via the partial unique index). */
    public void projectFlagAsTask(UUID t, UUID m, UUID messageId, String subject, Instant due) {
        jdbc.update("""
                INSERT INTO tasks (tenant_id, mailbox_id, task_id, source_message_id, title, due_at, reminder_at)
                VALUES (:t, :m, :id, :src, :title, :due, :rem)
                ON CONFLICT (tenant_id, mailbox_id, source_message_id) WHERE source_message_id IS NOT NULL
                DO UPDATE SET status = 'OPEN'""",
                p(t, m).addValue("id", UUID.randomUUID()).addValue("src", messageId).addValue("title", "Follow up: " + subject)
                        .addValue("due", Db.ts(due)).addValue("rem", Db.ts(due.minusSeconds(3600))));
    }

    public void completeTaskFor(UUID t, UUID m, UUID messageId) {
        jdbc.update("UPDATE tasks SET status = 'DONE' WHERE tenant_id = :t AND mailbox_id = :m AND source_message_id = :src",
                p(t, m).addValue("src", messageId));
    }

    public List<TaskView> openTasks(UUID t, UUID m) {
        return jdbc.query("""
                SELECT task_id, source_message_id, title, due_at, priority, status FROM tasks
                WHERE tenant_id = :t AND mailbox_id = :m AND status = 'OPEN' ORDER BY due_at NULLS LAST LIMIT 200""", p(t, m),
                (rs, i) -> new TaskView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), Db.instant(rs, "due_at"),
                        rs.getShort(5), rs.getString(6)));
    }
}
