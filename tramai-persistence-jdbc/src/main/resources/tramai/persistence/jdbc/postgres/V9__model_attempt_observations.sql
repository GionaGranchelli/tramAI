-- Durable model-attempt observations (Epic 0.7.5b)
--
-- Content-free telemetry for one provider attempt. Deliberately NOT part of the
-- audit chain: these rows are observed, attributed facts about what the engine
-- saw, never proof that a governance decision was enforced.
--
-- Identity: (invocation_id, attempt) is the attempt identity. The primary key
-- makes the engine's repeated terminal callbacks idempotent instead of
-- producing duplicate attempts.
--
-- Nullable columns mean "not reported": input_tokens/output_tokens/
-- thinking_tokens are NULL when the provider reported no usage, reported_model
-- is NULL when the provider named no model, and finish_reason_reported is
-- FALSE because ModelResponse defaults finish_reason to STOP and a default is
-- not a provider report.
--
-- No prompt, response, tool payload or reasoning content is storable here:
-- there is no column for it.

CREATE TABLE IF NOT EXISTS model_attempt_observations (
    invocation_id          TEXT        NOT NULL,
    attempt                INTEGER     NOT NULL,
    workflow_run_id        TEXT,
    correlation_id         TEXT,
    route_index            INTEGER,
    provider_id            TEXT        NOT NULL,
    requested_model        TEXT        NOT NULL,
    reported_model         TEXT,
    started_at             TIMESTAMPTZ NOT NULL,
    completed_at           TIMESTAMPTZ NOT NULL,
    duration_millis        BIGINT      NOT NULL,
    outcome                TEXT        NOT NULL,
    failure_category       TEXT,
    finish_reason          TEXT,
    finish_reason_reported BOOLEAN     NOT NULL,
    input_tokens           INTEGER,
    output_tokens          INTEGER,
    thinking_tokens        INTEGER,
    provenance             TEXT        NOT NULL,
    CONSTRAINT model_attempt_observations_pkey PRIMARY KEY (invocation_id, attempt),
    CONSTRAINT model_attempt_observations_duration_non_negative CHECK (duration_millis >= 0),
    CONSTRAINT model_attempt_observations_attempt_non_negative CHECK (attempt >= 0)
);

CREATE INDEX IF NOT EXISTS model_attempt_observations_run_idx
    ON model_attempt_observations (workflow_run_id);
