package dev.tramai.core.identity

/**
 * Identity of one exact governed configuration: a stable configuration family
 * ([ConfigurationId]) pinned to one immutable revision ([ConfigurationVersion]).
 *
 * Invariant: within an authority domain, the same `(configurationId, version)`
 * pair must never refer to two different governed configurations. This type
 * declares the invariant; collision/rebinding enforcement belongs to the
 * registration/state authority (Epic 0.7.1 candidate 0.7.1c) because it
 * requires an authoritative store.
 *
 * Note: a governed configuration is broader than a workflow definition. It may
 * later include workflow definition, policy, classification profile, provider
 * deployment configuration, trust-zone assignments and tool governance. Do not
 * equate [ConfigurationVersion] with the workflow `definitionVersion`.
 */
data class WorkloadConfigurationIdentity(
    val id: ConfigurationId,
    val version: ConfigurationVersion,
)
