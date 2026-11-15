package com.enterprise.outlook.mail.repo;

import com.enterprise.outlook.mail.dto.Dtos.DeltaChange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class SyncRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public SyncRepository(@Qualifier("shardJdbc") NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Allocates the next per-mailbox sequence. The row lock is held until commit => change_seq order == commit order. */
    public long nextSeq(UUID tenant, UUID mailbox) {
        return jdbc.queryForObject("""
                INSERT INTO mailbox_sync_state (tenant_id, mailbox_id, last_seq) VALUES (:t, :m, 1)
                ON CONFLICT (tenant_id, mailbox_id) DO UPDATE SET last_seq = mailbox_sync_state.last_seq + 1
                RETURNING last_seq""", new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox), Long.class);
    }

    public void append(UUID tenant, UUID mailbox, long seq, String entityType, UUID entityId, String changeType, UUID folderId) {
        jdbc.update("""
                INSERT INTO delta_sync_log (tenant_id, mailbox_id, change_seq, entity_type, entity_id, change_type, folder_id)
                VALUES (:t, :m, :s, :et, :eid, :ct, :f)""",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox).addValue("s", seq).addValue("et", entityType)
                        .addValue("eid", entityId).addValue("ct", changeType).addValue("f", folderId));
    }

    /** Index range scan on the PK (tenant_id, mailbox_id, change_seq). */
    public List<DeltaChange> findAfter(UUID tenant, UUID mailbox, long afterSeq, int limit) {
        return jdbc.query("""
                SELECT change_seq, entity_type, entity_id, change_type, folder_id FROM delta_sync_log
                WHERE tenant_id = :t AND mailbox_id = :m AND change_seq > :s ORDER BY change_seq LIMIT :lim""",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox).addValue("s", afterSeq).addValue("lim", limit),
                (rs, i) -> new DeltaChange(rs.getLong("change_seq"), rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                        rs.getString("change_type"), rs.getObject("folder_id", UUID.class)));
    }

    public long lastSeq(UUID tenant, UUID mailbox) {
        List<Long> r = jdbc.query("SELECT last_seq FROM mailbox_sync_state WHERE tenant_id = :t AND mailbox_id = :m",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox), (rs, i) -> rs.getLong(1));
        return r.isEmpty() ? 0 : r.get(0);
    }

    public long minRetainedSeq(UUID tenant, UUID mailbox) {
        List<Long> r = jdbc.query("SELECT min_retained_seq FROM mailbox_sync_state WHERE tenant_id = :t AND mailbox_id = :m",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox), (rs, i) -> rs.getLong(1));
        return r.isEmpty() ? 0 : r.get(0);
    }
}
