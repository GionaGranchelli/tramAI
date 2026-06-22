-- Sovereign Runtime persistence schema — PostgreSQL
-- V2: Approval continuation table for human-in-the-loop suspend/resume
--
-- Depends on V1 for the sovereign_persistence foundation types.
-- The approval_continuations table stores the lifecycle state for
-- continuations that need human approval before execution.

CREATE TABLE IF NOT EXISTS approval_continuations (
    approval_id             TEXT        NOT NULL PRIMARY KEY,
    status                  TEXT        NOT NULL,
    version                 BIGINT      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    approval_expires_at     TIMESTAMPTZ NOT NULL,
    claimed_by              TEXT,
    claimed_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    workflow_run_id         TEXT,
    correlation_id          TEXT,
    tool_call_id            TEXT,
    tool_name               TEXT,
    arguments_digest        TEXT        NOT NULL,
    policy_version          TEXT,
    workflow_digest         TEXT,
    recovery_resolved_by    TEXT,
    recovery_resolved_at    TIMESTAMPTZ,
    recovery_reason_code    TEXT,
    encrypted_arguments     BYTEA,
    encryption_key_id       TEXT,
    encryption_algorithm    TEXT,
    encryption_nonce        BYTEA,
    payload_digest          TEXT,
    CONSTRAINT ck_approval_continuations_encryption CHECK (
        (encrypted_arguments IS NULL AND encryption_key_id IS NULL AND encryption_algorithm IS NULL AND encryption_nonce IS NULL AND payload_digest IS NULL)
        OR (encrypted_arguments IS NOT NULL AND encryption_key_id IS NOT NULL AND encryption_algorithm IS NOT NULL AND encryption_nonce IS NOT NULL AND payload_digest IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_approval_continuations_status_version
    ON approval_continuations (status, version);

CREATE INDEX IF NOT EXISTS idx_approval_continuations_claimed_at
    ON approval_continuations (claimed_at);
