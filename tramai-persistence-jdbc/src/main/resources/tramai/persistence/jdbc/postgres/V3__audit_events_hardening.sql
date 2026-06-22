-- Sovereign Runtime persistence schema — PostgreSQL
-- V3: Audit stream heads table and audit_events hardening
--
-- Depends on V1 (audit_events foundation) and V2 (approval_continuations).
-- The audit_stream_heads table provides a stream-level mutex for
-- transactional append serialization. The audit_events CHECK constraints
-- enforce invariants that previously relied solely on application code.

-- ──────────────────────────────────────────────
-- audit_stream_heads
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_stream_heads (
    stream_id           TEXT        NOT NULL PRIMARY KEY,
    latest_sequence     BIGINT      NOT NULL DEFAULT 0,
    latest_event_id     TEXT,
    latest_event_hash   TEXT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ──────────────────────────────────────────────
-- audit_events hardening
-- ──────────────────────────────────────────────
ALTER TABLE audit_events
    ADD CONSTRAINT ck_audit_events_sequence_positive
    CHECK (sequence_number > 0);

ALTER TABLE audit_events
    ADD CONSTRAINT ck_audit_events_schema_version
    CHECK (schema_version = '1');

ALTER TABLE audit_events
    ADD CONSTRAINT ck_audit_events_event_hash_non_blank
    CHECK (length(trim(event_hash)) > 0);

ALTER TABLE audit_events
    ADD CONSTRAINT ck_audit_events_event_id_non_blank
    CHECK (length(trim(event_id)) > 0);

ALTER TABLE audit_events
    ADD CONSTRAINT ck_audit_events_stream_id_non_blank
    CHECK (length(trim(stream_id)) > 0);

CREATE INDEX IF NOT EXISTS idx_audit_events_stream_desc
    ON audit_events (stream_id, sequence_number DESC);
