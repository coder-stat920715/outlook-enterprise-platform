package com.enterprise.outlook.mail.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AttachmentRepository {
    public record Att(UUID attachmentId, String fileName, String contentType, long sizeBytes, String objectKey,
                      boolean inline, String contentId, String status) {}

    private final NamedParameterJdbcTemplate jdbc;

    public AttachmentRepository(@Qualifier("shardJdbc") NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final org.springframework.jdbc.core.RowMapper<Att> MAP = (rs, i) -> new Att(rs.getObject("attachment_id", UUID.class),
            rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("object_key"),
            rs.getBoolean("is_inline"), rs.getString("content_id"), rs.getString("status"));

    public void insertPending(UUID t, UUID m, UUID id, String name, String type, long size, String key, boolean inline, String cid) {
        jdbc.update("""
                INSERT INTO mail_attachments (tenant_id, mailbox_id, attachment_id, file_name, content_type, size_bytes, object_key, is_inline, content_id)
                VALUES (:t, :m, :id, :n, :ct, :sz, :k, :in, :cid)""",
                new MapSqlParameterSource().addValue("t", t).addValue("m", m).addValue("id", id).addValue("n", name).addValue("ct", type)
                        .addValue("sz", size).addValue("k", key).addValue("in", inline).addValue("cid", cid));
    }

    public Optional<Att> find(UUID t, UUID m, UUID id) {
        return jdbc.query("SELECT * FROM mail_attachments WHERE tenant_id = :t AND mailbox_id = :m AND attachment_id = :id",
                new MapSqlParameterSource().addValue("t", t).addValue("m", m).addValue("id", id), MAP).stream().findFirst();
    }

    public List<Att> findAll(UUID t, UUID m, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM mail_attachments WHERE tenant_id = :t AND mailbox_id = :m AND attachment_id IN (:ids)",
                new MapSqlParameterSource().addValue("t", t).addValue("m", m).addValue("ids", ids), MAP);
    }

    public void markUploaded(UUID t, UUID m, UUID id, long actualSize) {
        jdbc.update("UPDATE mail_attachments SET status = 'UPLOADED', size_bytes = :sz WHERE tenant_id = :t AND mailbox_id = :m AND attachment_id = :id",
                new MapSqlParameterSource().addValue("t", t).addValue("m", m).addValue("id", id).addValue("sz", actualSize));
    }

    /** Links an attachment to a message; for recipient mailboxes creates a row pointing at the same object (no blob copy). */
    public void insertForMessage(UUID t, UUID m, UUID id, UUID messageId, String name, String type, long size, String key, boolean inline, String cid) {
        jdbc.update("""
                INSERT INTO mail_attachments (tenant_id, mailbox_id, attachment_id, message_id, file_name, content_type, size_bytes, object_key, is_inline, content_id, status)
                VALUES (:t, :m, :id, :msg, :n, :ct, :sz, :k, :in, :cid, 'UPLOADED')
                ON CONFLICT (tenant_id, mailbox_id, attachment_id) DO UPDATE SET message_id = :msg""",
                new MapSqlParameterSource().addValue("t", t).addValue("m", m).addValue("id", id).addValue("msg", messageId).addValue("n", name)
                        .addValue("ct", type).addValue("sz", size).addValue("k", key).addValue("in", inline).addValue("cid", cid));
    }
}
