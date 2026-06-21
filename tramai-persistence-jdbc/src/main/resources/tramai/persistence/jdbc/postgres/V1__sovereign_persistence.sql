-- Sovereign Runtime persistence schema — PostgreSQL
-- V1: Initial sovereign persistence tables
--
-- This schema reflects the production-hardening design defined in
-- docs/architecture/sovereign-jdbc-persistence-design.md.
-- It is the first implementation foundation; full JDBC stores are not
-- implemented yet at this schema version.

-- ──────────────────────────────────────────────
-- approvals
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS approvals (
    approval_id             TEXT        NOT NULL PRIMARY KEY,
    status                  TEXT        NOT NULL,
    required_role           TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at              TIMESTAMPTZ,
    decision_actor_hash     TEXT,
    decision_type           TEXT,
    sanitized_metadata      JSONB,
    encrypted_payload       BYTEA,
    encryption_key_id       TEXT,
    encryption_algorithm    TEXT,
    encryption_nonce        BYTEA,
    payload_digest          TEXT,
    version                 BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT ck_approvals_encryption CHECK (
        (encrypted_payload IS NULL AND encryption_key_id IS NULL AND encryption_algorithm IS NULL AND encryption_nonce IS NULL AND payload_digest IS NULL)
        OR (encrypted_payload IS NOT NULL AND encryption_key_id IS NOT NULL AND encryption_algorithm IS NOT NULL AND encryption_nonce IS NOT NULL AND payload_digest IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_approvals_status_created_at
    ON approvals (status, created_at);

-- ──────────────────────────────────────────────
-- suspended_invocations
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS suspended_invocations (
    invocation_id               TEXT        NOT NULL PRIMARY KEY,
    service_key                 TEXT,
    operation_key               TEXT,
    descriptor_hash             TEXT,
    status                      TEXT        NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resumed_at                  TIMESTAMPTZ,
    replay_envelope_digest      TEXT        NOT NULL,
    encrypted_replay_envelope   BYTEA,
    encryption_key_id           TEXT,
    encryption_algorithm        TEXT,
    encryption_nonce            BYTEA,
    payload_digest              TEXT,
    version                     BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT ck_suspended_invocations_encryption CHECK (
        (encrypted_replay_envelope IS NULL AND encryption_key_id IS NULL AND encryption_algorithm IS NULL AND encryption_nonce IS NULL AND payload_digest IS NULL)
        OR (encrypted_replay_envelope IS NOT NULL AND encryption_key_id IS NOT NULL AND encryption_algorithm IS NOT NULL AND encryption_nonce IS NOT NULL AND payload_digest IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_suspended_invocations_digest
    ON suspended_invocations (replay_envelope_digest);

CREATE INDEX IF NOT EXISTS idx_suspended_invocations_status_created_at
    ON suspended_invocations (status, created_at);

-- ──────────────────────────────────────────────
-- audit_events
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_events (
    stream_id               TEXT        NOT NULL,
    sequence_number         BIGINT      NOT NULL,
    event_id                TEXT        NOT NULL,
    event_type              TEXT        NOT NULL,
    event_hash              TEXT        NOT NULL,
    previous_event_hash     TEXT,
    occurred_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    sanitized_actor         TEXT,
    encrypted_payload       BYTEA,
    encryption_key_id       TEXT,
    encryption_algorithm    TEXT,
    encryption_nonce        BYTEA,
    payload_digest          TEXT,
    schema_version          TEXT        NOT NULL,

    PRIMARY KEY (stream_id, sequence_number),
    CONSTRAINT ck_audit_events_encryption CHECK (
        (encrypted_payload IS NULL AND encryption_key_id IS NULL AND encryption_algorithm IS NULL AND encryption_nonce IS NULL AND payload_digest IS NULL)
        OR (encrypted_payload IS NOT NULL AND encryption_key_id IS NOT NULL AND encryption_algorithm IS NOT NULL AND encryption_nonce IS NOT NULL AND payload_digest IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_audit_events_event_id
    ON audit_events (event_id);

-- ──────────────────────────────────────────────
-- audit_outbox
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_outbox (
    outbox_id               TEXT        NOT NULL PRIMARY KEY,
    event_key               TEXT        NOT NULL,
    status                  TEXT        NOT NULL,
    correlation_key_hash    TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at              TIMESTAMPTZ,
    dispatched_at           TIMESTAMPTZ,
    attempt_count           INTEGER     NOT NULL DEFAULT 0,
    last_failure_type       TEXT,
    next_attempt_at         TIMESTAMPTZ,
    encrypted_payload       BYTEA,
    encryption_key_id       TEXT,
    encryption_algorithm    TEXT,
    encryption_nonce        BYTEA,
    payload_digest          TEXT,
    version                 BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT ck_audit_outbox_encryption CHECK (
        (encrypted_payload IS NULL AND encryption_key_id IS NULL AND encryption_algorithm IS NULL AND encryption_nonce IS NULL AND payload_digest IS NULL)
        OR (encrypted_payload IS NOT NULL AND encryption_key_id IS NOT NULL AND encryption_algorithm IS NOT NULL AND encryption_nonce IS NOT NULL AND payload_digest IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_audit_outbox_event_key
    ON audit_outbox (event_key);

CREATE INDEX IF NOT EXISTS idx_audit_outbox_status_next_attempt
    ON audit_outbox (status, next_attempt_at);

CREATE INDEX IF NOT EXISTS idx_audit_outbox_claimed_at
    ON audit_outbox (claimed_at);

-- ──────────────────────────────────────────────
-- worker_leases
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS worker_leases (
    lease_name      TEXT        NOT NULL PRIMARY KEY,
    owner_id        TEXT,
    acquired_at     TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    heartbeat_at    TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_worker_leases_expires_at
    ON worker_leases (expires_at);
