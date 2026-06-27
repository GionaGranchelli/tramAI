-- ────────────────────────────────────────────────────────────────────
-- V6: approval resume credential custody
-- ────────────────────────────────────────────────────────────────────
-- Adds an internal encrypted credential store for resume tokens.
--
-- Resume tokens are created during transactional approval-request creation
-- and are consumed exclusively by the internal runtime-owned resume path.
-- They are never exposed through inbox, REST, audit, logs, or reviewer UI.
--
-- The encrypted_resume_token is encrypted at rest using the existing
-- sovereign/JDBC encryption pattern (AES-256-GCM). The encryption columns
-- follow the same convention as approval_continuations and suspended_invocations.
-- ────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS tramai_approval_resume_credentials (
    approval_id             TEXT NOT NULL PRIMARY KEY,
    workflow_run_id         TEXT NOT NULL,
    encrypted_resume_token  BYTEA NOT NULL,
    encryption_key_id       TEXT,
    encryption_algorithm    TEXT,
    encryption_nonce        BYTEA,
    payload_digest          TEXT,
    created_at              TIMESTAMPTZ NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    version                 BIGINT NOT NULL DEFAULT 0
);

-- Index for sweeping expired credentials
CREATE INDEX IF NOT EXISTS idx_resume_credentials_expires_at
    ON tramai_approval_resume_credentials (expires_at);

-- Not-null invariant: approval_id is non-blank
DO $$ BEGIN
    ALTER TABLE tramai_approval_resume_credentials
        ADD CONSTRAINT ck_resume_credentials_approval_id_non_blank
        CHECK (length(trim(approval_id)) > 0);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- Temporal invariant: expires_at must be after created_at
DO $$ BEGIN
    ALTER TABLE tramai_approval_resume_credentials
        ADD CONSTRAINT ck_resume_credentials_expiry_after_create
        CHECK (expires_at > created_at);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
