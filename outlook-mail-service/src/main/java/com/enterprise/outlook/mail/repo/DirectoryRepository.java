package com.enterprise.outlook.mail.repo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Control-plane directory (unsharded): e-mail -> mailbox routing and the Global Address List. */
@Repository
public class DirectoryRepository {
    public record Entry(String email, UUID mailboxId, String displayName, String region, String mailboxType) {}

    private final NamedParameterJdbcTemplate jdbc;

    public DirectoryRepository(@Qualifier("directoryJdbc") NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final org.springframework.jdbc.core.RowMapper<Entry> MAP = (rs, i) -> new Entry(rs.getString("email"),
            rs.getObject("mailbox_id", UUID.class), rs.getString("display_name"), rs.getString("region"), rs.getString("mailbox_type"));

    public void upsert(UUID tenant, Entry e) {
        jdbc.update("""
                INSERT INTO directory_mailboxes (tenant_id, email, mailbox_id, display_name, region, mailbox_type)
                VALUES (:t, :e, :m, :n, :r, :ty) ON CONFLICT (tenant_id, email) DO UPDATE SET display_name = :n""",
                new MapSqlParameterSource().addValue("t", tenant).addValue("e", e.email().toLowerCase()).addValue("m", e.mailboxId())
                        .addValue("n", e.displayName()).addValue("r", e.region()).addValue("ty", e.mailboxType()));
    }

    public Optional<Entry> byEmail(UUID tenant, String email) {
        return jdbc.query("SELECT * FROM directory_mailboxes WHERE tenant_id = :t AND email = :e",
                new MapSqlParameterSource().addValue("t", tenant).addValue("e", email.toLowerCase()), MAP).stream().findFirst();
    }

    public Optional<Entry> byMailbox(UUID tenant, UUID mailbox) {
        return jdbc.query("SELECT * FROM directory_mailboxes WHERE tenant_id = :t AND mailbox_id = :m",
                new MapSqlParameterSource().addValue("t", tenant).addValue("m", mailbox), MAP).stream().findFirst();
    }

    /** GAL autocomplete: btree prefix match (text_pattern_ops) first; trigram fuzzy match only when the prefix yields nothing. */
    public List<Entry> gal(UUID tenant, String q, int limit) {
        String clean = q.toLowerCase().replace("%", "").replace("_", "");
        var p = new MapSqlParameterSource().addValue("t", tenant).addValue("pfx", clean + "%").addValue("q", clean).addValue("lim", limit);
        List<Entry> prefix = jdbc.query("""
                SELECT * FROM directory_mailboxes WHERE tenant_id = :t AND lower(display_name) LIKE :pfx
                ORDER BY lower(display_name) LIMIT :lim""", p, MAP);
        if (!prefix.isEmpty() || clean.length() < 3) return prefix;
        return jdbc.query("""
                SELECT * FROM directory_mailboxes WHERE tenant_id = :t AND display_name % :q
                ORDER BY similarity(display_name, :q) DESC LIMIT :lim""", p, MAP);
    }
}
