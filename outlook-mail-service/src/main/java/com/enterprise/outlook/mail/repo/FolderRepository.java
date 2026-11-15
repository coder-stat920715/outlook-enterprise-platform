package com.enterprise.outlook.mail.repo;

import com.enterprise.outlook.common.util.Ids;
import com.enterprise.outlook.mail.dto.Dtos.FolderView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class FolderRepository {
    public static final List<String> WELL_KNOWN = List.of("INBOX", "SENT", "DRAFTS", "ARCHIVE", "DELETED");
    private final NamedParameterJdbcTemplate jdbc;

    public FolderRepository(@Qualifier("shardJdbc") NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void createWellKnown(UUID tenant, UUID mailbox) {
        for (String wk : WELL_KNOWN) {
            UUID id = Ids.folderId(mailbox, wk);
            jdbc.update("""
                    INSERT INTO mail_folders (tenant_id, mailbox_id, folder_id, display_name, well_known)
                    VALUES (:t, :m, :f, :n, :wk) ON CONFLICT DO NOTHING""",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox).addValue("f", id)
                            .addValue("n", wk.charAt(0) + wk.substring(1).toLowerCase()).addValue("wk", wk));
            jdbc.update("INSERT INTO mail_folder_counters (tenant_id, mailbox_id, folder_id) VALUES (:t, :m, :f) ON CONFLICT DO NOTHING",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox).addValue("f", id));
        }
    }

    public UUID create(UUID tenant, UUID mailbox, String name, UUID parent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO mail_folders (tenant_id, mailbox_id, folder_id, parent_folder_id, display_name) VALUES (:t, :m, :f, :p, :n)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox).addValue("f", id).addValue("p", parent).addValue("n", name));
        jdbc.update("INSERT INTO mail_folder_counters (tenant_id, mailbox_id, folder_id) VALUES (:t, :m, :f) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox).addValue("f", id));
        return id;
    }

    /** Counts come from the counter table – never COUNT(*) over the message partitions. */
    public List<FolderView> list(UUID tenant, UUID mailbox) {
        return jdbc.query("""
                SELECT f.folder_id, f.parent_folder_id, f.display_name, f.well_known, c.total_count, c.unread_count
                FROM mail_folders f
                JOIN mail_folder_counters c ON c.tenant_id = f.tenant_id AND c.mailbox_id = f.mailbox_id AND c.folder_id = f.folder_id
                WHERE f.tenant_id = :t AND f.mailbox_id = :m ORDER BY f.display_name""",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox),
                (rs, i) -> new FolderView(rs.getObject("folder_id", UUID.class), rs.getObject("parent_folder_id", UUID.class),
                        rs.getString("display_name"), rs.getString("well_known"), rs.getInt("total_count"), rs.getInt("unread_count")));
    }

    public void adjust(UUID tenant, UUID mailbox, UUID folder, int totalDelta, int unreadDelta) {
        jdbc.update("""
                INSERT INTO mail_folder_counters (tenant_id, mailbox_id, folder_id, total_count, unread_count)
                VALUES (:t, :m, :f, GREATEST(:td, 0), GREATEST(:ud, 0))
                ON CONFLICT (tenant_id, mailbox_id, folder_id) DO UPDATE
                SET total_count = GREATEST(mail_folder_counters.total_count + :td, 0),
                    unread_count = GREATEST(mail_folder_counters.unread_count + :ud, 0)""",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox).addValue("f", folder)
                        .addValue("td", totalDelta).addValue("ud", unreadDelta));
    }
}
