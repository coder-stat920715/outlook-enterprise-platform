-- Control-plane "directory" database (small, unsharded): email -> mailbox routing, GAL, resources.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE directory_mailboxes (
    tenant_id    uuid         NOT NULL,
    email        varchar(320) NOT NULL,
    mailbox_id   uuid         NOT NULL,
    display_name varchar(256) NOT NULL,
    region       varchar(8)   NOT NULL DEFAULT 'AMER',
    mailbox_type varchar(16)  NOT NULL DEFAULT 'USER',
    PRIMARY KEY (tenant_id, email)
);
CREATE UNIQUE INDEX uq_dir_mailbox ON directory_mailboxes (tenant_id, mailbox_id);
CREATE INDEX idx_gal_prefix ON directory_mailboxes (tenant_id, lower(display_name) text_pattern_ops);
CREATE INDEX idx_gal_fuzzy  ON directory_mailboxes USING gin (display_name gin_trgm_ops);

CREATE TABLE resources (
    tenant_id   uuid         NOT NULL,
    resource_id uuid         NOT NULL,
    name        varchar(256) NOT NULL,
    kind        varchar(16)  NOT NULL DEFAULT 'ROOM',
    capacity    int          NOT NULL DEFAULT 1,
    location    varchar(256),
    PRIMARY KEY (tenant_id, resource_id)
);

-- Authoritative no-double-booking guarantee (belt) + Redisson lock + version column (braces).
CREATE TABLE resource_bookings (
    tenant_id       uuid         NOT NULL,
    resource_id     uuid         NOT NULL,
    booking_id      uuid         NOT NULL,
    start_at        timestamptz  NOT NULL,
    end_at          timestamptz  NOT NULL,
    organizer_email varchar(320) NOT NULL,
    title           varchar(256) NOT NULL DEFAULT '',
    status          varchar(12)  NOT NULL DEFAULT 'CONFIRMED',
    version         bigint       NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, resource_id, booking_id),
    CHECK (end_at > start_at),
    CONSTRAINT no_double_booking EXCLUDE USING gist
        (tenant_id WITH =, resource_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&)
        WHERE (status = 'CONFIRMED')
);
