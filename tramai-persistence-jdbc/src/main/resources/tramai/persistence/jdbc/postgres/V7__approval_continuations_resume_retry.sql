-- ────────────────────────────────────────────────────────────────────
-- V7: approval_continuations resume retry tracking
-- ────────────────────────────────────────────────────────────────────
-- Adds retry-state columns to approval_continuations so the approved
-- continuation resume worker can persist reason codes, backoff timers,
-- and attempt counts for transient failures.
--
-- These columns are used exclusively by the runtime-owned resume worker
-- (PR #112). They are never exposed through inbox, REST, audit, or UI.
-- ────────────────────────────────────────────────────────────────────

DO $$ BEGIN
    ALTER TABLE approval_continuations
        ADD COLUMN IF NOT EXISTS resume_last_error_code TEXT;
EXCEPTION WHEN duplicate_column THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE approval_continuations
        ADD COLUMN IF NOT EXISTS resume_next_attempt_at TIMESTAMPTZ;
EXCEPTION WHEN duplicate_column THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE approval_continuations
        ADD COLUMN IF NOT EXISTS resume_attempt_count BIGINT NOT NULL DEFAULT 0;
EXCEPTION WHEN duplicate_column THEN NULL;
END $$;

-- Index for finding retry-eligible rows
CREATE INDEX IF NOT EXISTS idx_approval_continuations_resume_attempt
    ON approval_continuations (resume_next_attempt_at)
    WHERE resume_next_attempt_at IS NOT NULL
      AND status = 'PENDING';
