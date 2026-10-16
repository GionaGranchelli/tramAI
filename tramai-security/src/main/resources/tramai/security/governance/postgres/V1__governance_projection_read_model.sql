-- 0.7.4d — durable, rebuildable governance read model.
--
-- The first migration this module owns. It lives beside the code that owns the projection types
-- because those types are `internal` to :tramai-security and cannot be named from the persistence
-- modules: :tramai-persistence-jdbc already depends on :tramai-security (api(project(":tramai-security"))),
-- so the reverse edge would be a cycle, and making the projection public would change a published
-- module's API surface. See docs/roadmap/0.7.4/TASK-0.7.4d-MATERIALIZED-READ-MODEL.md section 4.
--
-- What this schema deliberately does not store: prompts, completions, tool arguments or results,
-- credentials, document bodies, raw DLP matches, provider or model names, and arbitrary audit
-- metadata. There is no column for them, so their presence is a schema change rather than a filter
-- that can be forgotten. Registration *content* is not duplicated either: the read model keeps the
-- registration fact's provenance (authority, freshness, version) and never a second copy of the
-- authoritative registration record.

CREATE TABLE IF NOT EXISTS governance_projection_snapshot (
    projection_key          TEXT        PRIMARY KEY,
    format_version          TEXT        NOT NULL,
    classification_revision TEXT        NOT NULL,
    workload_id             TEXT        NOT NULL,
    environment_id          TEXT        NOT NULL,
    deployment_id           TEXT        NOT NULL,
    workflow_run_id         TEXT,
    audit_stream_id         TEXT,
    -- Source provenance, per source. The two sources are read independently, so their versions are
    -- recorded separately and never merged into one notional snapshot point.
    -- Ordering surface for concurrent and out-of-order publishes, compared lexicographically:
    -- audit head first, registration version second. Zero means "not established" in both, because
    -- authoritative registration versions and audit positions both start at one, so zero is not a
    -- value either source can produce.
    registration_version    BIGINT      NOT NULL,
    audit_head_position     BIGINT      NOT NULL,
    audit_head_hash         TEXT,
    -- Digest of the semantic content only. Write timestamps are excluded, so rebuilding the same
    -- source facts twice yields the same digest and a rebuild can be compared rather than eyeballed.
    semantic_digest         TEXT        NOT NULL,
    materialized_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT governance_projection_versions_nonnegative CHECK (
        registration_version >= 0 AND audit_head_position >= 0
    ),
    CONSTRAINT governance_projection_head_hash_requires_position CHECK (
        audit_head_hash IS NULL OR audit_head_position > 0
    )
);

CREATE TABLE IF NOT EXISTS governance_projection_fact (
    projection_key   TEXT    NOT NULL REFERENCES governance_projection_snapshot (projection_key) ON DELETE CASCADE,
    fact             TEXT    NOT NULL,
    authority        TEXT    NOT NULL,
    freshness        TEXT    NOT NULL,
    established      TEXT    NOT NULL,
    -- One entry per line, order preserved. These are statements authored in code, never source data.
    must_not_infer   TEXT    NOT NULL,
    value_present    BOOLEAN NOT NULL,
    observed_version BIGINT,
    source_version   BIGINT,
    watermark        BIGINT,
    PRIMARY KEY (projection_key, fact),
    CONSTRAINT governance_projection_fact_name CHECK (
        fact IN ('registration', 'declared_run_identity', 'telemetry')
    )
);

CREATE TABLE IF NOT EXISTS governance_projection_decision (
    projection_key           TEXT    NOT NULL REFERENCES governance_projection_snapshot (projection_key) ON DELETE CASCADE,
    kind                     TEXT    NOT NULL,
    -- Raw audit-stream coverage. Independent of the decision-record claim below it.
    verified_range_first     BIGINT,
    verified_range_last      BIGINT,
    observed_head_hash       TEXT,
    accounted_positions      BIGINT  NOT NULL,
    unrecorded_positions     TEXT    NOT NULL,
    unclassified_positions   TEXT    NOT NULL,
    classification_revision  TEXT    NOT NULL,
    -- Decision-record coverage. Never derived from the raw range, and the raw range is never derived
    -- from the count of recognised decisions.
    completeness             TEXT    NOT NULL,
    duplicates_ignored       INTEGER NOT NULL,
    stale_ignored            INTEGER NOT NULL,
    -- The fact itself, narrow by construction: no argument, result or payload field exists here.
    latest_decision          TEXT,
    latest_reason_code       TEXT,
    latest_boundary_id       TEXT,
    latest_boundary_strength TEXT,
    latest_authority         TEXT    NOT NULL,
    latest_freshness         TEXT    NOT NULL,
    latest_established       TEXT    NOT NULL,
    latest_must_not_infer    TEXT    NOT NULL,
    latest_observed_version  BIGINT,
    latest_source_version    BIGINT,
    latest_watermark         BIGINT,
    PRIMARY KEY (projection_key, kind),
    CONSTRAINT governance_projection_decision_kind CHECK (
        kind IN ('POLICY_DECISION', 'TOOL_PERMISSION', 'PROVIDER_ROUTE')
    ),
    CONSTRAINT governance_projection_decision_completeness CHECK (
        completeness IN ('COMPLETE', 'GAPS_PRESENT', 'UNKNOWN')
    ),
    CONSTRAINT governance_projection_decision_range_ordered CHECK (
        verified_range_first IS NULL
            OR verified_range_last IS NULL
            OR verified_range_first <= verified_range_last
    )
);

CREATE INDEX IF NOT EXISTS governance_projection_snapshot_scope_idx
    ON governance_projection_snapshot (workload_id, environment_id, deployment_id, workflow_run_id);
