-- =====================================================================================
-- V1__init_sharded_partitioned_schema.sql
-- Applied to EVERY logical shard database (shard_00 .. shard_15).
-- All mailbox-scoped tables lead with (tenant_id, mailbox_id) so each query is single-shard.
-- mail_messages / mail_audit_events: RANGE(received_at) monthly -> HASH(mailbox_id) sub-partitions.
-- =====================================================================================

CREATE TABLE mailboxes (
    tenant_id     uuid         NOT NULL,
    mailbox_id    uuid         NOT NULL,
    email         varchar(320) NOT NULL,
    display_name  varchar(256),
    mailbox_type  varchar(16)  NOT NULL DEFAULT 'USER',          -- USER | SHARED | RESOURCE
    region        varchar(8)   NOT NULL DEFAULT 'AMER',
    quota_bytes   bigint       NOT NULL DEFAULT 53687091200,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, mailbox_id)
);

CREATE TABLE mail_folders (
    tenant_id        uuid         NOT NULL,
    mailbox_id       uuid         NOT NULL,
    folder_id        uuid         NOT NULL,
    parent_folder_id uuid,
    display_name     varchar(256) NOT NULL,
    well_known       varchar(16),                                -- INBOX SENT DRAFTS ARCHIVE DELETED or NULL
    created_at       timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, mailbox_id, folder_id)
);
CREATE INDEX idx_folders_parent ON mail_folders (tenant_id, mailbox_id, parent_folder_id);

-- Folder counts are maintained transactionally: never COUNT(*) over messages.
CREATE TABLE mail_folder_counters (
    tenant_id    uuid NOT NULL,
    mailbox_id   uuid NOT NULL,
    folder_id    uuid NOT NULL,
    total_count  int  NOT NULL DEFAULT 0,
    unread_count int  NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, mailbox_id, folder_id)
);

-- -------------------------------------------------------------------------------------
-- Messages: PARTITION BY RANGE (received_at), sub-partition HASH (mailbox_id)
-- PK must contain every partition key column (received_at, mailbox_id).
-- -------------------------------------------------------------------------------------
CREATE TABLE mail_messages (
    tenant_id           uuid          NOT NULL,
    mailbox_id          uuid          NOT NULL,
    folder_id           uuid          NOT NULL,
    message_id          uuid          NOT NULL,
    conversation_id     uuid          NOT NULL,
    internet_message_id varchar(512),
    in_reply_to         varchar(512),
    subject             varchar(998)  NOT NULL DEFAULT '',
    sender_email        varchar(320)  NOT NULL,
    sender_name         varchar(256),
    snippet             varchar(255)  NOT NULL DEFAULT '',
    to_recipients       jsonb         NOT NULL DEFAULT '[]',
    cc_recipients       jsonb         NOT NULL DEFAULT '[]',
    body_content        text,
    body_type           varchar(8)    NOT NULL DEFAULT 'text',
    is_read             boolean       NOT NULL DEFAULT false,
    is_flagged          boolean       NOT NULL DEFAULT false,
    is_pinned           boolean       NOT NULL DEFAULT false,
    has_attachments     boolean       NOT NULL DEFAULT false,
    importance          smallint      NOT NULL DEFAULT 1,
    categories          jsonb         NOT NULL DEFAULT '[]',
    delivery_state      varchar(16)   NOT NULL DEFAULT 'DELIVERED',
    received_at         timestamptz   NOT NULL,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    version             bigint        NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, mailbox_id, received_at, message_id)
) PARTITION BY RANGE (received_at);

-- Covering index -> inbox listing is an Index Only Scan (no heap fetch once pages are all-visible).
CREATE INDEX idx_mailbox_folder_covering
    ON mail_messages (tenant_id, mailbox_id, folder_id, received_at DESC, message_id DESC)
    INCLUDE (conversation_id, subject, sender_email, sender_name, snippet,
             is_read, is_flagged, has_attachments, importance);

-- Partial index for "unread only" views and unread badge refresh.
CREATE INDEX idx_mailbox_unread_partial
    ON mail_messages (tenant_id, mailbox_id, folder_id, received_at DESC, message_id DESC)
    WHERE is_read = false;

-- Flagged view (to-do projection) – partial.
CREATE INDEX idx_mailbox_flagged_partial
    ON mail_messages (tenant_id, mailbox_id, received_at DESC, message_id DESC)
    WHERE is_flagged = true;

-- Conversation (thread) view.
CREATE INDEX idx_mailbox_conversation
    ON mail_messages (tenant_id, mailbox_id, conversation_id, received_at DESC, message_id DESC)
    INCLUDE (folder_id, subject, sender_email, sender_name, snippet, is_read, is_flagged, has_attachments, importance);

-- Partition factory: monthly RANGE partition + N hash sub-partitions on mailbox_id.
CREATE OR REPLACE FUNCTION create_monthly_partition(parent regclass, month_start date, hash_parts int DEFAULT 4)
RETURNS void LANGUAGE plpgsql AS $fn$
DECLARE
    part_name text;
    lo timestamptz := (month_start::timestamp AT TIME ZONE 'UTC');
    hi timestamptz := ((month_start + interval '1 month')::date::timestamp AT TIME ZONE 'UTC');
    i int;
BEGIN
    part_name := format('%s_%s', parent::text, to_char(month_start, 'YYYY_MM'));
    IF to_regclass(part_name) IS NOT NULL THEN
        RETURN;
    END IF;
    EXECUTE format('CREATE TABLE %I PARTITION OF %s FOR VALUES FROM (%L) TO (%L) PARTITION BY HASH (mailbox_id)',
                   part_name, parent, lo, hi);
    FOR i IN 0 .. hash_parts - 1 LOOP
        EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES WITH (MODULUS %s, REMAINDER %s)',
                       part_name || '_h' || i, part_name, hash_parts, i);
    END LOOP;
END
$fn$;

SELECT create_monthly_partition('mail_messages'::regclass, DATE '2026-10-01');
SELECT create_monthly_partition('mail_messages'::regclass, DATE '2026-11-01');
SELECT create_monthly_partition('mail_messages'::regclass, DATE '2026-12-01');
SELECT create_monthly_partition('mail_messages'::regclass, DATE '2027-01-01');

-- Locator: message_id -> (received_at, folder) so point lookups can still prune partitions.
CREATE TABLE message_locator (
    tenant_id   uuid        NOT NULL,
    mailbox_id  uuid        NOT NULL,
    message_id  uuid        NOT NULL,
    received_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, mailbox_id, message_id)
);

CREATE TABLE mail_attachments (
    tenant_id     uuid         NOT NULL,
    mailbox_id    uuid         NOT NULL,
    attachment_id uuid         NOT NULL,
    message_id    uuid,
    file_name     varchar(512) NOT NULL,
    content_type  varchar(255) NOT NULL,
    size_bytes    bigint       NOT NULL,
    object_key    varchar(512) NOT NULL,
    is_inline     boolean      NOT NULL DEFAULT false,
    content_id    varchar(255),
    status        varchar(12)  NOT NULL DEFAULT 'PENDING',      -- PENDING | UPLOADED
    created_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, mailbox_id, attachment_id)
);
CREATE INDEX idx_attachments_message ON mail_attachments (tenant_id, mailbox_id, message_id);

-- Delegation / RBAC
CREATE TABLE mailbox_permissions (
    tenant_id          uuid        NOT NULL,
    mailbox_id         uuid        NOT NULL,                    -- the target (e.g. shared) mailbox
    grantee_mailbox_id uuid        NOT NULL,
    permission         varchar(16) NOT NULL CHECK (permission IN ('FULL_ACCESS','SEND_AS','SEND_ON_BEHALF')),
    granted_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, mailbox_id, grantee_mailbox_id, permission)
);

CREATE TABLE inbox_rules (
    tenant_id   uuid    NOT NULL,
    mailbox_id  uuid    NOT NULL,
    rule_id     uuid    NOT NULL,
    priority    int     NOT NULL,
    name        varchar(256) NOT NULL,
    condition   jsonb   NOT NULL,
    action      jsonb   NOT NULL,
    enabled     boolean NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, mailbox_id, rule_id)
);
CREATE INDEX idx_rules_active ON inbox_rules (tenant_id, mailbox_id, priority) WHERE enabled = true;

CREATE TABLE oof_settings (
    tenant_id  uuid    NOT NULL,
    mailbox_id uuid    NOT NULL,
    enabled    boolean NOT NULL DEFAULT false,
    start_at   timestamptz,
    end_at     timestamptz,
    subject    varchar(256) NOT NULL DEFAULT 'Out of Office',
    reply_text text    NOT NULL DEFAULT '',
    PRIMARY KEY (tenant_id, mailbox_id)
);
-- Dedup: one auto-reply per sender per OOF period.
CREATE TABLE oof_replied (
    tenant_id    uuid         NOT NULL,
    mailbox_id   uuid         NOT NULL,
    sender_email varchar(320) NOT NULL,
    replied_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, mailbox_id, sender_email)
);

-- -------------------------------------------------------------------------------------
-- Delta sync
-- -------------------------------------------------------------------------------------
CREATE TABLE mailbox_sync_state (
    tenant_id        uuid   NOT NULL,
    mailbox_id       uuid   NOT NULL,
    last_seq         bigint NOT NULL DEFAULT 0,
    min_retained_seq bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, mailbox_id)
);

CREATE TABLE delta_sync_log (
    tenant_id   uuid        NOT NULL,
    mailbox_id  uuid        NOT NULL,
    change_seq  bigint      NOT NULL,
    entity_type varchar(16) NOT NULL,                            -- MESSAGE | FOLDER | EVENT | TASK
    entity_id   uuid        NOT NULL,
    change_type varchar(16) NOT NULL,                            -- CREATED | UPDATED | DELETED | MOVED
    folder_id   uuid,
    changed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, mailbox_id, change_seq)
);

-- -------------------------------------------------------------------------------------
-- Audit (also RANGE(received_at) -> HASH(mailbox_id); received_at = event time)
-- -------------------------------------------------------------------------------------
CREATE TABLE mail_audit_events (
    tenant_id   uuid         NOT NULL,
    mailbox_id  uuid         NOT NULL,
    event_id    uuid         NOT NULL,
    actor_id    uuid,
    action      varchar(32)  NOT NULL,
    entity_id   uuid,
    detail      jsonb        NOT NULL DEFAULT '{}',
    received_at timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, mailbox_id, received_at, event_id)
) PARTITION BY RANGE (received_at);
CREATE INDEX idx_audit_mailbox_time ON mail_audit_events (tenant_id, mailbox_id, received_at DESC);

SELECT create_monthly_partition('mail_audit_events'::regclass, DATE '2026-10-01');
SELECT create_monthly_partition('mail_audit_events'::regclass, DATE '2026-11-01');
SELECT create_monthly_partition('mail_audit_events'::regclass, DATE '2026-12-01');
SELECT create_monthly_partition('mail_audit_events'::regclass, DATE '2027-01-01');

-- -------------------------------------------------------------------------------------
-- Tasks, contacts, calendar (all mailbox-scoped => same shard as the mailbox)
-- -------------------------------------------------------------------------------------
CREATE TABLE tasks (
    tenant_id         uuid         NOT NULL,
    mailbox_id        uuid         NOT NULL,
    task_id           uuid         NOT NULL,
    source_message_id uuid,
    title             varchar(998) NOT NULL,
    due_at            timestamptz,
    reminder_at       timestamptz,
    priority          smallint     NOT NULL DEFAULT 1,
    status            varchar(12)  NOT NULL DEFAULT 'OPEN',
    created_at        timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, mailbox_id, task_id)
);
CREATE UNIQUE INDEX uq_task_source ON tasks (tenant_id, mailbox_id, source_message_id) WHERE source_message_id IS NOT NULL;
CREATE INDEX idx_tasks_open ON tasks (tenant_id, mailbox_id, due_at) WHERE status = 'OPEN';

CREATE TABLE contacts (
    tenant_id    uuid         NOT NULL,
    mailbox_id   uuid         NOT NULL,
    contact_id   uuid         NOT NULL,
    display_name varchar(256) NOT NULL,
    email        varchar(320),
    phone        varchar(64),
    company      varchar(256),
    PRIMARY KEY (tenant_id, mailbox_id, contact_id)
);
CREATE INDEX idx_contacts_prefix ON contacts (tenant_id, mailbox_id, lower(display_name) text_pattern_ops);

CREATE TABLE calendar_events (
    tenant_id       uuid         NOT NULL,
    mailbox_id      uuid         NOT NULL,
    event_id        uuid         NOT NULL,
    ics_uid         varchar(255) NOT NULL,
    title           varchar(998) NOT NULL,
    location        varchar(512),
    start_at        timestamptz  NOT NULL,
    end_at          timestamptz  NOT NULL,
    time_zone       varchar(64)  NOT NULL,                        -- IANA TZDB id
    all_day         boolean      NOT NULL DEFAULT false,
    rrule           varchar(512),
    exdates         jsonb        NOT NULL DEFAULT '[]',
    series_until    timestamptz,
    organizer_email varchar(320) NOT NULL,
    attendees       jsonb        NOT NULL DEFAULT '[]',
    meeting_status  varchar(12)  NOT NULL DEFAULT 'DRAFT',
    sequence_no     int          NOT NULL DEFAULT 0,
    show_as         varchar(12)  NOT NULL DEFAULT 'BUSY',
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, mailbox_id, event_id),
    CHECK (end_at > start_at AND end_at - start_at <= interval '31 days')
);
CREATE INDEX idx_events_window ON calendar_events (tenant_id, mailbox_id, start_at) INCLUDE (end_at, show_as, meeting_status)
    WHERE rrule IS NULL;
CREATE INDEX idx_events_recurring ON calendar_events (tenant_id, mailbox_id, start_at)
    WHERE rrule IS NOT NULL;
CREATE UNIQUE INDEX uq_events_uid ON calendar_events (tenant_id, mailbox_id, ics_uid);
