-- Control-plane workload registration (Epic 0.7.1c)
--
-- Two tables:
--   tramai_configuration_revision — global authority over
--       (configuration_id, configuration_version) -> fingerprint. A pair can
--       never be rebound to a different fingerprint, from any deployment.
--   tramai_workload_registration — one authoritative registration per
--       deployment scope (workload_id, environment_id, deployment_id), bound
--       to the exact governed configuration revision.

CREATE TABLE IF NOT EXISTS tramai_configuration_revision (
    configuration_id      TEXT NOT NULL,
    configuration_version TEXT NOT NULL,
    fingerprint           TEXT NOT NULL,
    PRIMARY KEY (configuration_id, configuration_version),
    CONSTRAINT ck_configuration_revision_fingerprint CHECK (
        length(fingerprint) BETWEEN 1 AND 128
    )
);

CREATE TABLE IF NOT EXISTS tramai_workload_registration (
    workload_id           TEXT NOT NULL,
    environment_id        TEXT NOT NULL,
    deployment_id         TEXT NOT NULL,
    configuration_id      TEXT NOT NULL,
    configuration_version TEXT NOT NULL,
    owner                 TEXT NOT NULL,
    purpose               TEXT NOT NULL,
    lifecycle_state       TEXT NOT NULL,
    state_version         BIGINT NOT NULL,
    PRIMARY KEY (workload_id, environment_id, deployment_id),
    CONSTRAINT fk_workload_registration_configuration
        FOREIGN KEY (configuration_id, configuration_version)
        REFERENCES tramai_configuration_revision (configuration_id, configuration_version),
    CONSTRAINT ck_workload_registration_lifecycle CHECK (
        lifecycle_state IN ('ACTIVE', 'SUSPENDED', 'RETIRED')
    ),
    CONSTRAINT ck_workload_registration_state_version CHECK (
        state_version >= 1
    )
);

CREATE INDEX IF NOT EXISTS idx_workload_registration_configuration
    ON tramai_workload_registration (configuration_id, configuration_version);
