-- ──────────────────────────────────────────────
-- V4: Hardening for audit_outbox
-- Adds CHECK constraints and dispatchable indexes
-- ──────────────────────────────────────────────

ALTER TABLE audit_outbox
    ADD CONSTRAINT ck_audit_outbox_status
    CHECK (
        status IN (
            'PREPARED',
            'PENDING',
            'EMITTING',
            'EMITTED',
            'FAILED_RETRYABLE',
            'FAILED_PERMANENT'
        )
    );

ALTER TABLE audit_outbox
    ADD CONSTRAINT ck_audit_outbox_version_positive
    CHECK (version > 0);

ALTER TABLE audit_outbox
    ADD CONSTRAINT ck_audit_outbox_attempt_count_non_negative
    CHECK (attempt_count >= 0);

ALTER TABLE audit_outbox
    ADD CONSTRAINT ck_audit_outbox_outbox_id_non_blank
    CHECK (length(trim(outbox_id)) > 0);

ALTER TABLE audit_outbox
    ADD CONSTRAINT ck_audit_outbox_event_key_non_blank
    CHECK (length(trim(event_key)) > 0);

ALTER TABLE audit_outbox
    ADD CONSTRAINT ck_audit_outbox_claim_consistency
    CHECK (
        (status <> 'EMITTING')
        OR
        (claimed_at IS NOT NULL AND next_attempt_at IS NOT NULL)
    );

ALTER TABLE audit_outbox
    ADD CONSTRAINT ck_audit_outbox_emitted_consistency
    CHECK (
        (status <> 'EMITTED')
        OR
        (dispatched_at IS NOT NULL)
    );

CREATE INDEX IF NOT EXISTS idx_audit_outbox_dispatchable
    ON audit_outbox (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS idx_audit_outbox_status_created
    ON audit_outbox (status, created_at);
