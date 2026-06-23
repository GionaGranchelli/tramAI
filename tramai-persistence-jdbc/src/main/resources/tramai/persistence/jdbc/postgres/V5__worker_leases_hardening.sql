-- ────────────────────────────────────────────────────────────────────
-- V5: worker_leases hardening
-- ────────────────────────────────────────────────────────────────────
-- Adds domain constraints to the worker_leases table created in V1.
-- All constraints are idempotent (ALTER TABLE ... ADD CONSTRAINT IF NOT
-- EXISTS is not standard SQL, but PostgreSQL's IF NOT EXISTS works for
-- ALTER TABLE ... ADD CONSTRAINT since PG 17. For earlier PG versions,
-- DO blocks provide idempotent constraint creation.)

-- 1. Non-blank lease_name
DO $$ BEGIN
    ALTER TABLE worker_leases ADD CONSTRAINT ck_worker_leases_lease_name_non_blank
        CHECK (length(trim(lease_name)) > 0);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- 2. Positive version
DO $$ BEGIN
    ALTER TABLE worker_leases ADD CONSTRAINT ck_worker_leases_version_positive
        CHECK (version > 0);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- 3. Owner consistency: either all owner fields are NULL (unowned) or
--    all are NOT NULL (owned).
DO $$ BEGIN
    ALTER TABLE worker_leases ADD CONSTRAINT ck_worker_leases_owner_consistency
        CHECK (
            (owner_id IS NULL AND acquired_at IS NULL AND expires_at IS NULL AND heartbeat_at IS NULL)
            OR
            (owner_id IS NOT NULL AND acquired_at IS NOT NULL AND expires_at IS NOT NULL AND heartbeat_at IS NOT NULL)
        );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- 4. Expiry after acquire (when both are set)
DO $$ BEGIN
    ALTER TABLE worker_leases ADD CONSTRAINT ck_worker_leases_expiry_after_acquire
        CHECK (
            expires_at IS NULL
            OR acquired_at IS NULL
            OR expires_at > acquired_at
        );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- 5. Owner ID index (optional but useful for diagnostics)
DO $$ BEGIN
    CREATE INDEX IF NOT EXISTS idx_worker_leases_owner_id
        ON worker_leases (owner_id);
EXCEPTION WHEN duplicate_table THEN NULL;
END $$;
