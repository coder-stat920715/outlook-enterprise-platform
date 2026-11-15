# 03 – Database Design

## 1. Topology
* **16 logical shards** `shard_00 … shard_15` (database per shard); shard = consistent-hash(`tenant_id:mailbox_id`) over a 256-vnode ring (`ConsistentHashRing`). Logical→physical mapping is configuration (`hosts[i*n/16]`) so a hot host can be split by moving databases, never by re-hashing mailboxes.
* **`directory`** (unsharded control plane): e-mail → mailbox routing, GAL (btree prefix + `pg_trgm` fuzzy), resources and `resource_bookings` (with the exclusion constraint).
* Every mailbox-owned table leads with `(tenant_id, mailbox_id)` ⇒ all mailbox queries are **single-shard**.
* Schemas live in `outlook-common/src/main/resources/db/{shard,directory}` and are applied by `SchemaMigrator` (Flyway, advisory-locked so replicas can race safely).

## 2. Tables (shard schema)
`mailboxes`, `mail_folders`, `mail_folder_counters`, **`mail_messages`** (partitioned), `message_locator`, `mail_attachments`, `mailbox_permissions`, `inbox_rules`, `oof_settings`, `oof_replied`, `mailbox_sync_state`, `delta_sync_log`, **`mail_audit_events`** (partitioned), `tasks`, `contacts`, `calendar_events`.

### Partitioning
```sql
CREATE TABLE mail_messages (...,
  PRIMARY KEY (tenant_id, mailbox_id, received_at, message_id)
) PARTITION BY RANGE (received_at);            -- monthly
-- each month: PARTITION BY HASH (mailbox_id)  -- 4 sub-partitions
SELECT create_monthly_partition('mail_messages'::regclass, DATE '2026-11-01');
```
`create_monthly_partition()` (PL/pgSQL, idempotent) is called by the migration for Oct 2026–Jan 2027 and every 6 h by `PartitionMaintenanceJob` for the next 3 months on all shards. The primary key contains both partition key columns (a PostgreSQL requirement). There is deliberately **no DEFAULT partition** (it would defeat pruning and hide missing-partition bugs).
Retention = `DETACH PARTITION` + archive/drop of whole months (O(1), no vacuum debt).

### Indexes on `mail_messages`
| Index | Definition | Serves |
|---|---|---|
| `idx_mailbox_folder_covering` | `(tenant_id, mailbox_id, folder_id, received_at DESC, message_id DESC) INCLUDE (conversation_id, subject, sender_email, sender_name, snippet, is_read, is_flagged, has_attachments, importance)` | folder listing = **Index Only Scan** |
| `idx_mailbox_unread_partial` | same key, `WHERE is_read = false` | unread view / badge when unread ratio is small |
| `idx_mailbox_flagged_partial` | `(tenant,mailbox,received_at DESC,message_id DESC) WHERE is_flagged` | flagged / to-do view |
| `idx_mailbox_conversation` | `(tenant,mailbox,conversation_id,received_at DESC,message_id DESC) INCLUDE (...)` | thread view |

Counters (`mail_folder_counters`) are maintained in the same transaction as the message change; `COUNT(*)` over partitions is never used.

## 3. Verified query plans (PostgreSQL 16, real run)
Test data: 480 000 messages (2 tenants × 40 mailboxes), Oct–Dec 2026, `VACUUM (ANALYZE)` executed. Reproduce with `PartitionPruningIT` or the SQL in this document.

### 3.1 Inbox page (keyset) – static plan
```
480000
Limit (actual rows=51 loops=1)
  Buffers: shared hit=1 read=5
  ->  Index Only Scan using mail_messages_2026_11_h3_tenant_id_mailbox_id_folder_id_rec_idx on mail_messages_2026_11_h3 mail_messages (actual rows=51 loops=1)
        Index Cond: ((tenant_id = '00000000-0000-0000-0000-000000000001'::uuid) AND (mailbox_id = '10000000-0000-0000-0000-000000000007'::uuid) AND (folder_id = 'f0000000-0000-0000-0000-0000
        Heap Fetches: 0
        Buffers: shared hit=1 read=5
Planning:
  Buffers: shared hit=70 read=1 dirtied=2
Planning Time: 0.379 ms
Execution Time: 0.120 ms
Limit (actual rows=51 loops=1)
  Buffers: shared hit=13 read=74
  ->  Sort (actual rows=51 loops=1)
        Sort Key: mail_messages.received_at DESC, mail_messages.message_id DESC
        Sort Method: top-N heapsort  Memory: 31kB
        Buffers: shared hit=13 read=74
        ->  Append (actual rows=1936 loops=1)
              Buffers: shared hit=7 read=74
              Subplans Removed: 15
              ->  Index Only Scan using mail_messages_2026_11_h3_tenant_id_mailbox_id_folder_id_rec_idx on mail_messages_2026_11_h3 mail_messages_1 (actual rows=1936 loops=1)
                    Index Cond: ((tenant_id = $1) AND (mailbox_id = $2) AND (folder_id = $3) AND (received_at >= $4) AND (received_at < $5) AND (ROW(received_at, message_id) < ROW($6, $7)))
                    Heap Fetches: 0
                    Buffers: shared hit=7 read=74
Planning:
  Buffers: shared hit=521 dirtied=6
Planning Time: 1.660 ms
Execution Time: 204.891 ms
```
Reading the first plan: **only** `mail_messages_2026_11_h3` appears – the other 3 monthly partitions and the 3 other hash sub-partitions of November were pruned at plan time; the node is an *Index Only Scan* with `Heap Fetches: 0` and the `LIMIT` stops after 51 rows (a handful of buffers). The second plan is a **generic** (prepared-statement) plan: partitions cannot be removed at plan time, so PostgreSQL removes them at executor start (`Subplans Removed`). Both exclude all non-matching partitions from execution.

### 3.2 Other access paths and the OFFSET anti-pattern
```
=== UNREAD (partial index)
Limit (actual rows=51 loops=1)
  Buffers: shared hit=4 read=2
  ->  Index Only Scan using mail_messages_2026_11_h3_tenant_id_mailbox_id_folder_id_rec_idx on mail_messages_2026_11_h3 mail_messages (actual rows=51 loops=1)
        Index Cond: ((tenant_id = '00000000-0000-0000-0000-000000000001'::uuid) AND (mailbox_id = '10000000-0000-0000-0000-000000000007'::uuid) AND (folder_id = 'f0000000-0000-0000-0000-0000
        Filter: (NOT is_read)
        Rows Removed by Filter: 26
        Heap Fetches: 0
        Buffers: shared hit=4 read=2
Planning:
  Buffers: shared hit=675
=== DELTA (PK range scan)
Limit (actual rows=100 loops=1)
  Buffers: shared hit=8
  ->  Sort (actual rows=100 loops=1)
        Sort Key: change_seq
        Sort Method: quicksort  Memory: 32kB
        Buffers: shared hit=8
        ->  Bitmap Heap Scan on delta_sync_log (actual rows=100 loops=1)
              Recheck Cond: ((tenant_id = '00000000-0000-0000-0000-000000000001'::uuid) AND (mailbox_id = '10000000-0000-0000-0000-000000000007'::uuid) AND (change_seq > 4900))
              Heap Blocks: exact=2
              Buffers: shared hit=8
              ->  Bitmap Index Scan on delta_sync_log_pkey (actual rows=100 loops=1)
                    Index Cond: ((tenant_id = '00000000-0000-0000-0000-000000000001'::uuid) AND (mailbox_id = '10000000-0000-0000-0000-000000000007'::uuid) AND (change_seq > 4900))
                    Buffers: shared hit=6
Planning:
  Buffers: shared hit=41
=== OFFSET anti-pattern for contrast
Limit (actual rows=50 loops=1)
  ->  Append (actual rows=1550 loops=1)
        ->  Index Only Scan using mail_messages_2027_01_h3_tenant_id_mailbox_id_folder_id_rec_idx on mail_messages_2027_01_h3 mail_messages_4 (actual rows=0 loops=1)
              Index Cond: ((tenant_id = '00000000-0000-0000-0000-000000000001'::uuid) AND (mailbox_id = '10000000-0000-0000-0000-000000000007'::uuid) AND (folder_id = 'f0000000-0000-0000-000
              Heap Fetches: 0
        ->  Index Only Scan using mail_messages_2026_12_h3_tenant_id_mailbox_id_folder_id_rec_idx on mail_messages_2026_12_h3 mail_messages_3 (actual rows=0 loops=1)
              Index Cond: ((tenant_id = '00000000-0000-0000-0000-000000000001'::uuid) AND (mailbox_id = '10000000-0000-0000-0000-000000000007'::uuid) AND (folder_id = 'f0000000-0000-0000-000
              Heap Fetches: 0
        ->  Index Only Scan using mail_messages_2026_11_h3_tenant_id_mailbox_id_folder_id_rec_idx on mail_messages_2026_11_h3 mail_messages_2 (actual rows=1550 loops=1)
              Index Cond: ((tenant_id = '00000000-0000-0000-0000-000000000001'::uuid) AND (mailbox_id = '10000000-0000-0000-0000-000000000007'::uuid) AND (folder_id = 'f0000000-0000-0000-000
              Heap Fetches: 0
        ->  Index Only Scan using mail_messages_2026_10_h3_tenant_id_mailbox_id_folder_id_rec_idx on mail_messages_2026_10_h3 mail_messages_1 (never executed)
              Index Cond: ((tenant_id = '00000000-0000-0000-0000-000000000001'::uuid) AND (mailbox_id = '10000000-0000-0000-0000-000000000007'::uuid) AND (folder_id = 'f0000000-0000-0000-000
              Heap Fetches: 0
Planning Time: 1.412 ms
Execution Time: 0.508 ms
```
Honest notes:
* With ~⅓ of rows unread, the planner prefers the covering index with `Filter: (NOT is_read)` (still Index Only Scan, 0 heap fetches) over the partial index; the partial index wins when unread rows are a small fraction of the folder (typical for real inboxes).
* At 5 000 log rows the delta query uses a bitmap scan; on large logs the planner switches to a plain index range scan on the PK — either way it is a PK range, not a table scan.
* The OFFSET plan exists only for contrast: it must produce and discard 1 500 rows to return 50 (and the cost grows linearly with the offset). The API never issues it.

## 4. Sync, audit and idempotency tables
* `mailbox_sync_state.last_seq` is the per-mailbox change counter; `delta_sync_log` PK `(tenant,mailbox,change_seq)`; purge advances `min_retained_seq` (clients behind it get 410 and full-resync).
* `mail_audit_events` is partitioned exactly like messages (its `received_at` column holds the event time).
* Idempotency: deterministic UUIDs per (envelope, recipient mailbox) + `ON CONFLICT DO NOTHING`; `message_locator` PK blocks duplicate delivery; Redis `Idempotency-Key` for client retries.

## 5. Directory DB
```sql
CONSTRAINT no_double_booking EXCLUDE USING gist
  (tenant_id WITH =, resource_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&)
  WHERE (status = 'CONFIRMED')
```
`idx_gal_prefix (tenant_id, lower(display_name) text_pattern_ops)` for prefix autocomplete, `idx_gal_fuzzy` GIN `gin_trgm_ops` for typo-tolerant fallback.

## 6. Operational notes
* Autovacuum: tune per-partition (`autovacuum_vacuum_scale_factor` low on current month) so the visibility map stays fresh – Index Only Scans depend on it.
* Backups/PITR per physical host; cross-region replicas read-only for DR.
* Add `pg_partman`-style automation or keep `PartitionMaintenanceJob` (already idempotent).
* Re-sharding: move whole logical shard databases between hosts (logical replication), update `hosts` mapping.
