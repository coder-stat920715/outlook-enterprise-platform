package com.enterprise.outlook.mail;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the inbox query (a) prunes to ONE hash sub-partition of ONE monthly partition and (b) is an Index Only Scan
 * (covering index, no heap fetches, no Seq Scan) against the real shard schema.
 */
@Testcontainers(disabledWithoutDocker = true)
class PartitionPruningIT {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void load() throws Exception {
        Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).locations("classpath:db/shard").load().migrate();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
            s.execute("""
                    INSERT INTO mail_messages (tenant_id, mailbox_id, folder_id, message_id, conversation_id, subject, sender_email, snippet, received_at)
                    SELECT '00000000-0000-0000-0000-000000000001', ('10000000-0000-0000-0000-0000000000' || lpad(m::text, 2, '0'))::uuid,
                           'f0000000-0000-0000-0000-000000000001', gen_random_uuid(), gen_random_uuid(), 'S' || g, 'a@b.c', 'x',
                           TIMESTAMPTZ '2026-10-01' + g * interval '13 minutes'
                    FROM generate_series(1, 20) m, generate_series(1, 6000) g
                    WHERE TIMESTAMPTZ '2026-10-01' + g * interval '13 minutes' < TIMESTAMPTZ '2027-01-01'""");
            s.execute("VACUUM (ANALYZE) mail_messages");
        }
    }

    @Test
    void inboxQueryIsPartitionPrunedIndexOnlyScan() throws Exception {
        String explain = """
                EXPLAIN SELECT message_id, conversation_id, subject, sender_email, sender_name, snippet, is_read, is_flagged, has_attachments, importance, received_at
                FROM mail_messages
                WHERE tenant_id = '00000000-0000-0000-0000-000000000001' AND mailbox_id = '10000000-0000-0000-0000-000000000007'
                  AND folder_id = 'f0000000-0000-0000-0000-000000000001'
                  AND received_at >= TIMESTAMPTZ '2026-11-01' AND received_at < TIMESTAMPTZ '2026-12-01'
                ORDER BY received_at DESC, message_id DESC LIMIT 51""";
        StringBuilder plan = new StringBuilder();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(explain)) {
            while (rs.next()) plan.append(rs.getString(1)).append('\n');
        }
        String p = plan.toString();
        assertTrue(p.contains("Index Only Scan"), p);
        assertFalse(p.contains("Seq Scan"), p);
        assertFalse(p.contains("2026_10") || p.contains("2026_12") || p.contains("2027_01"), "other monthly partitions must be pruned:\n" + p);
        assertEquals(1, p.split("Index Only Scan", -1).length - 1, "exactly one hash sub-partition must be scanned:\n" + p);
    }
}
