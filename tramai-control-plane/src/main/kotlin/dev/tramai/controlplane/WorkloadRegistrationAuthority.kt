package dev.tramai.controlplane

import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.identity.WorkloadMetadata

/**
 * Authoritative workload-registration rules over a [WorkloadRegistrationStore].
 *
 * Every rule lives here, never in a persistence implementation:
 * - registration is NOT an upsert — establish the fact if absent, verify it if
 *   present, fail if it conflicts;
 * - a `(configurationId, version)` pair can never be rebound to a different
 *   fingerprint;
 * - one deployment scope has exactly one authoritative registration;
 * - metadata and lifecycle mutations preserve identity and advance
 *   [WorkloadStateVersion];
 * - stale writers lose (every mutation is a compare-and-set on the version
 *   the caller observed).
 *
 * This authority does NOT cancel running workflows, replace [WorkflowRegistry]
 * executable-definition lookup, or expose any REST/query surface — those
 * belong to later candidates.
 */
class WorkloadRegistrationAuthority(
    private val store: WorkloadRegistrationStore,
) {
    /**
     * Registers a workload deployment as ACTIVE at state version 1.
     *
     * Idempotent for an identical declaration; conflicting declarations are
     * rejected, never silently applied.
     */
    suspend fun register(
        identity: WorkloadDeploymentIdentity,
        configurationFingerprint: ConfigurationFingerprint,
        metadata: WorkloadMetadata,
    ): RegisterOutcome {
        val existing = store.find(identity.workloadId, identity.environmentId, identity.deploymentId)
        if (existing != null) {
            return if (existing.isSameDeclaration(identity, configurationFingerprint, metadata)) {
                RegisterOutcome.AlreadyRegistered(existing)
            } else {
                RegisterOutcome.Rejected(
                    existing = existing,
                    reason = existing.rejectionReason(identity, configurationFingerprint),
                )
            }
        }

        val desired =
            RegisteredWorkload(
                identity = identity,
                configurationFingerprint = configurationFingerprint,
                metadata = metadata,
                lifecycle = WorkloadLifecycleState.ACTIVE,
                stateVersion = WorkloadStateVersion.INITIAL,
            )
        return when (val result = store.create(desired)) {
            is CreateResult.Created -> RegisterOutcome.Created(result.registration)
            is CreateResult.Idempotent -> RegisterOutcome.AlreadyRegistered(result.registration)
            is CreateResult.Conflicting -> RegisterOutcome.Rejected(result.existing, result.reason)
        }
    }

    /**
     * Updates owner/purpose metadata of a registration. Identity is
     * unchanged; a successful change advances the state version.
     *
     * @param expectedVersion version observed by the caller — a mutation based
     *   on a stale version fails with [MetadataUpdateOutcome.Stale].
     */
    suspend fun updateMetadata(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
        expectedVersion: WorkloadStateVersion,
        metadata: WorkloadMetadata,
    ): MetadataUpdateOutcome {
        val current =
            store.find(workloadId, environmentId, deploymentId)
                ?: return MetadataUpdateOutcome.NotFound
        val updated =
            current.copy(
                metadata = metadata,
                stateVersion = current.stateVersion.next(),
            )
        return when {
            current.stateVersion != expectedVersion -> {
                MetadataUpdateOutcome.Stale(current.stateVersion, expectedVersion)
            }

            current.metadata == metadata -> {
                MetadataUpdateOutcome.Unchanged(current)
            }

            store.compareAndSet(current, updated) -> {
                MetadataUpdateOutcome.Applied(updated)
            }

            else -> {
                // Lost the race: report the CURRENT authoritative version, not
                // the version observed before the race (0.7.1e command
                // preconditions will consume exactly this value).
                val latest =
                    store.find(workloadId, environmentId, deploymentId)
                if (latest != null) {
                    MetadataUpdateOutcome.Stale(latest.stateVersion, expectedVersion)
                } else {
                    MetadataUpdateOutcome.NotFound
                }
            }
        }
    }

    /**
     * Applies an authoritative lifecycle transition. RETIRED is terminal;
     * suspending a workload never touches running workflows.
     */
    suspend fun transitionLifecycle(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
        expectedVersion: WorkloadStateVersion,
        target: WorkloadLifecycleState,
    ): LifecycleTransitionOutcome {
        val current =
            store.find(workloadId, environmentId, deploymentId)
                ?: return LifecycleTransitionOutcome.NotFound
        val updated =
            current.copy(
                lifecycle = target,
                stateVersion = current.stateVersion.next(),
            )
        return when {
            current.stateVersion != expectedVersion -> {
                LifecycleTransitionOutcome.Stale(current.stateVersion, expectedVersion)
            }

            current.lifecycle == target -> {
                LifecycleTransitionOutcome.Unchanged(current)
            }

            !current.lifecycle.canTransitionTo(target) -> {
                LifecycleTransitionOutcome.InvalidTransition(current.lifecycle, target)
            }

            store.compareAndSet(current, updated) -> {
                LifecycleTransitionOutcome.Applied(updated)
            }

            else -> {
                // Lost the race: report the CURRENT authoritative version.
                val latest =
                    store.find(workloadId, environmentId, deploymentId)
                if (latest != null) {
                    LifecycleTransitionOutcome.Stale(latest.stateVersion, expectedVersion)
                } else {
                    LifecycleTransitionOutcome.NotFound
                }
            }
        }
    }
}

/** Outcome of an authoritative [WorkloadRegistrationAuthority.register]. */
sealed interface RegisterOutcome {
    /** A new registration was created (ACTIVE, state version 1). */
    data class Created(
        val registration: RegisteredWorkload,
    ) : RegisterOutcome

    /** The identical declaration was already authoritative; nothing changed. */
    data class AlreadyRegistered(
        val registration: RegisteredWorkload,
    ) : RegisterOutcome

    /** The declaration conflicts with the authoritative record; nothing changed. */
    data class Rejected(
        val existing: RegisteredWorkload,
        val reason: RegistrationConflictReason,
    ) : RegisterOutcome
}

/** Outcome of [WorkloadRegistrationAuthority.updateMetadata]. */
sealed interface MetadataUpdateOutcome {
    /** Metadata changed; identity unchanged; state version advanced. */
    data class Applied(
        val registration: RegisteredWorkload,
    ) : MetadataUpdateOutcome

    /** Requested metadata equals current metadata; no authoritative mutation occurred. */
    data class Unchanged(
        val registration: RegisteredWorkload,
    ) : MetadataUpdateOutcome

    /** Caller's expected version is stale; the store moved on. */
    data class Stale(
        val currentVersion: WorkloadStateVersion,
        val expectedVersion: WorkloadStateVersion,
    ) : MetadataUpdateOutcome

    data object NotFound : MetadataUpdateOutcome
}

/** Outcome of [WorkloadRegistrationAuthority.transitionLifecycle]. */
sealed interface LifecycleTransitionOutcome {
    /** Lifecycle changed; state version advanced. */
    data class Applied(
        val registration: RegisteredWorkload,
    ) : LifecycleTransitionOutcome

    /** Target state equals current state; no authoritative mutation occurred. */
    data class Unchanged(
        val registration: RegisteredWorkload,
    ) : LifecycleTransitionOutcome

    /** The requested transition is outside the authoritative lifecycle graph. */
    data class InvalidTransition(
        val from: WorkloadLifecycleState,
        val to: WorkloadLifecycleState,
    ) : LifecycleTransitionOutcome

    /** Caller's expected version is stale; the store moved on. */
    data class Stale(
        val currentVersion: WorkloadStateVersion,
        val expectedVersion: WorkloadStateVersion,
    ) : LifecycleTransitionOutcome

    data object NotFound : LifecycleTransitionOutcome
}

private fun RegisteredWorkload.isSameDeclaration(
    identity: WorkloadDeploymentIdentity,
    configurationFingerprint: ConfigurationFingerprint,
    metadata: WorkloadMetadata,
): Boolean =
    this.identity == identity &&
        this.configurationFingerprint == configurationFingerprint &&
        this.metadata == metadata

private fun RegisteredWorkload.rejectionReason(
    identity: WorkloadDeploymentIdentity,
    configurationFingerprint: ConfigurationFingerprint,
): RegistrationConflictReason =
    if (this.identity.configuration == identity.configuration &&
        this.configurationFingerprint != configurationFingerprint
    ) {
        RegistrationConflictReason.CONFIGURATION_REBINDING
    } else {
        RegistrationConflictReason.CONFLICTING_REGISTRATION
    }
