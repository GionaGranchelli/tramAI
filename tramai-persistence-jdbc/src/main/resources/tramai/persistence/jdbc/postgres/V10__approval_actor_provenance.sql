-- ──────────────────────────────────────────────
-- V10: Approval actor provenance and evidence recovery metadata (Epic 0.7.6f)
--
-- Additive only. Nothing is backfilled, no historical row is rewritten and no
-- existing column changes meaning.
--
-- decision_actor_hash keeps its released meaning and textual form
-- (`sha256:` + 64 lowercase hex, see JdbcSovereignOpsApprovalMutationStore.sha256Hex)
-- and is never recomputed: rows written before this migration stay comparable to
-- rows written after it. The identity digest added here binds `(issuer, subject)`
-- instead, and uses a bare 64-character lowercase hex representation so a reader
-- can tell the two encodings apart.
--
-- A NULL decision_actor_issuer means "recorded before 0.7.6f; issuer unknown".
-- It is not a claim that the row shares any other row's or the current request's
-- issuer, and there is no backfill because only the subject digest was ever stored.
--
-- audit_outbox_quarantine is the metadata-level recovery representation for
-- outbox records whose payload cannot be decoded or contradicts its own stored
-- columns (contract §6.1). It holds no evidence and no key material: the
-- authoritative locator is the audit_outbox row itself, and the ciphertext stays
-- exactly where it was written.
-- ──────────────────────────────────────────────

ALTER TABLE approvals
    ADD COLUMN IF NOT EXISTS decision_actor_issuer TEXT;

ALTER TABLE approvals
    ADD COLUMN IF NOT EXISTS decision_actor_identity_digest TEXT;

ALTER TABLE approvals
    ADD CONSTRAINT ck_approvals_identity_digest_format
    CHECK (
        decision_actor_identity_digest IS NULL
        OR decision_actor_identity_digest ~ '^[0-9a-f]{64}$'
    );

CREATE TABLE IF NOT EXISTS audit_outbox_quarantine (
    outbox_id       TEXT        NOT NULL PRIMARY KEY
                    REFERENCES audit_outbox (outbox_id),
    label           TEXT        NOT NULL,
    quarantined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    failure_detail  TEXT,
    payload_digest  TEXT,
    CONSTRAINT ck_audit_outbox_quarantine_label
    CHECK (
        label IN ('undecodable-evidence', 'corrupt-evidence')
    )
);

CREATE INDEX IF NOT EXISTS idx_audit_outbox_quarantine_at
    ON audit_outbox_quarantine (quarantined_at);
