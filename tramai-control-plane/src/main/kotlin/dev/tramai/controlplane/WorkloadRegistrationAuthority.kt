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
 * This authority does NOT cancel running workflows or replace [WorkflowRegistry]
 * executable-definition lookup. It implements the framework-neutral command and read
 * contracts ([WorkloadControlPlaneCommands], [WorkloadControlPlaneQueries]) as of 0.7.1e;
 * HTTP mechanics stay in the server adapter.
 */
class WorkloadRegistrationAuthority(
    private val store: WorkloadRegistrationStore,
) : WorkloadControlPlaneCommands,
    WorkloadControlPlaneQueries {
    /**
     * Resolves the authoritative registration that admits a NEW governed run (0.7.1d).
     *
     * This is the minimal internal admission lookup, NOT a query surface: the only
     * thing a caller can learn is whether its configured deployment is authoritative
     * for new runs. The caller supplies the identity it intends to run as; the
     * authority answers with the registered identity it must use.
     *
     * Rejects, never coerces:
     * - no registration for the deployment scope;
     * - the registered identity differs anywhere, including configuration
     *   (id or version) — an identity is only authoritative in full;
     * - the registration is not [WorkloadLifecycleState.ACTIVE].
     *
     * Lifecycle is checked HERE, at new-execution admission. A later transition to
     * SUSPENDED or RETIRED does not retroactively invalidate a run that was
     * already admitted, and must never be consulted for continuations.
     */
    suspend fun resolveForNewRun(expectedIdentity: WorkloadDeploymentIdentity): WorkloadDeploymentIdentity {
        val registered =
            store.find(
                expectedIdentity.workloadId,
                expectedIdentity.environmentId,
                expectedIdentity.deploymentId,
            ) ?: throw WorkloadAdmissionRejectedException("workload-deployment-not-registered")

        val rejection = admissionRejection(registered, expectedIdentity)
        if (rejection != null) {
            throw WorkloadAdmissionRejectedException(rejection)
        }
        return registered.identity
    }

    /**
     * Reads current authoritative state (0.7.1e).
     *
     * Read-only: nothing on this path mutates, so a query cannot become a mutation authority.
     * The observed version equals the registration's own state version because this read IS
     * the authority's present state, not a derived view of it.
     */
    override suspend fun authoritative(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
    ): ClassifiedRead? {
        val registration =
            store.find(workloadId, environmentId, deploymentId) ?: return null
        return ClassifiedRead(
            exposure = WorkloadExposure.from(registration),
            consistency = QueryConsistency.AUTHORITATIVE,
            observedVersion = registration.stateVersion,
        )
    }

    /**
     * Reads a projection snapshot (0.7.1e).
     *
     * In 0.7.1e the projection source is an OBSERVATION of the authoritative store — there is
     * no derived store yet — so this read is honest about what a projection is here: a
     * read-only observation stamped with the version it saw. Lag is real the moment a caller
     * holds the observation across a mutation, and it never buys mutation authority: the
     * authority still rejects a command conditioned on the observed version as
     * [MetadataUpdateOutcome.Stale] / [LifecycleTransitionOutcome.Stale].
     *
     * ponytail: no projection engine (no event bus, no materialised store, no refresh policy)
     * until a slice needs one — 0.7.4 evidence and 0.7.8 dashboard are the candidates.
     */
    override suspend fun projection(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
    ): ClassifiedRead? {
        val registration =
            store.find(workloadId, environmentId, deploymentId) ?: return null
        return ClassifiedRead(
            exposure = WorkloadExposure.from(registration),
            consistency = QueryConsistency.PROJECTION,
            observedVersion = registration.stateVersion,
        )
    }

    /**
     * Why an existing registration may not admit this deployment, or null when it may.
     * Returned rather than thrown so the reason codes stay in one place and the admission
     * path keeps a single failure surface.
     */
    private fun admissionRejection(
        registered: RegisteredWorkload,
        expectedIdentity: WorkloadDeploymentIdentity,
    ): String? =
        when {
            registered.identity != expectedIdentity -> "workload-deployment-configuration-mismatch"
            registered.lifecycle != WorkloadLifecycleState.ACTIVE -> "workload-deployment-not-active"
            else -> null
        }

    /**
     * Registers a workload deployment as ACTIVE at state version 1.
     *
     * Idempotent for an identical declaration; conflicting declarations are
     * rejected, never silently applied.
     */
    override suspend fun register(
        identity: WorkloadDeploymentIdentity,
        configurationFingerprint: ConfigurationFingerprint,
        metadata: WorkloadMetadata,
    ): RegisterOutcome {
        val existing = store.find(identity.workloadId, identity.environmentId, identity.deploymentId)
        if (existing != null) {
            return if (existing.isSameDeclaration(identity, configurationFingerprint, metadata)) {
                RegisterOutcome.AlreadyRegistered(WorkloadExposure.from(existing))
            } else {
                RegisterOutcome.Rejected(
                    existing = WorkloadExposure.from(existing),
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
            is CreateResult.Created -> {
                RegisterOutcome.Created(WorkloadExposure.from(result.registration))
            }

            is CreateResult.Idempotent -> {
                RegisterOutcome.AlreadyRegistered(WorkloadExposure.from(result.registration))
            }

            is CreateResult.Conflicting -> {
                RegisterOutcome.Rejected(WorkloadExposure.from(result.existing), result.reason)
            }
        }
    }

    /**
     * Updates owner/purpose metadata of a registration. Identity is
     * unchanged; a successful change advances the state version.
     *
     * @param expectedVersion version observed by the caller — a mutation based
     *   on a stale version fails with [MetadataUpdateOutcome.Stale].
     */
    override suspend fun updateMetadata(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
        expectedVersion: WorkloadStateVersion,
        metadata: WorkloadMetadata,
    ): MetadataUpdateOutcome {
        val current =
            store.find(workloadId, environmentId, deploymentId)
                ?: return MetadataUpdateOutcome.NotFound
        return when {
            current.stateVersion != expectedVersion -> {
                MetadataUpdateOutcome.Stale(current.stateVersion, expectedVersion)
            }

            current.metadata == metadata -> {
                MetadataUpdateOutcome.Unchanged(WorkloadExposure.from(current))
            }

            else -> {
                // next() belongs to the actual mutation: stale and no-op
                // commands are observational and must never require a future
                // version — at Long.MAX_VALUE that requirement would overflow
                // without any mutation happening.
                val updated =
                    current.copy(
                        metadata = metadata,
                        stateVersion = current.stateVersion.next(),
                    )
                if (store.compareAndSet(current, updated)) {
                    MetadataUpdateOutcome.Applied(WorkloadExposure.from(updated))
                } else {
                    // Lost the race: report the CURRENT authoritative version,
                    // not the version observed before the race (0.7.1e command
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
    }

    /**
     * Applies an authoritative lifecycle transition. RETIRED is terminal;
     * suspending a workload never touches running workflows.
     */
    override suspend fun transitionLifecycle(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
        expectedVersion: WorkloadStateVersion,
        target: WorkloadLifecycleState,
    ): LifecycleTransitionOutcome {
        val current =
            store.find(workloadId, environmentId, deploymentId)
                ?: return LifecycleTransitionOutcome.NotFound
        return when {
            current.stateVersion != expectedVersion -> {
                LifecycleTransitionOutcome.Stale(current.stateVersion, expectedVersion)
            }

            current.lifecycle == target -> {
                LifecycleTransitionOutcome.Unchanged(WorkloadExposure.from(current))
            }

            !current.lifecycle.canTransitionTo(target) -> {
                LifecycleTransitionOutcome.InvalidTransition(current.lifecycle, target)
            }

            else -> {
                // next() belongs to the actual mutation: stale, same-state and
                // invalid commands are observational and must never require a
                // future version — at Long.MAX_VALUE that requirement would
                // overflow without any mutation happening.
                val updated =
                    current.copy(
                        lifecycle = target,
                        stateVersion = current.stateVersion.next(),
                    )
                if (store.compareAndSet(current, updated)) {
                    LifecycleTransitionOutcome.Applied(WorkloadExposure.from(updated))
                } else {
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
}

/** Outcome of an authoritative [WorkloadRegistrationAuthority.register]. */
sealed interface RegisterOutcome {
    /** A new registration was created (ACTIVE, state version 1). */
    data class Created(
        val exposure: WorkloadExposure,
    ) : RegisterOutcome

    /** The identical declaration was already authoritative; nothing changed. */
    data class AlreadyRegistered(
        val exposure: WorkloadExposure,
    ) : RegisterOutcome

    /** The declaration conflicts with the authoritative record; nothing changed. */
    data class Rejected(
        val existing: WorkloadExposure,
        val reason: RegistrationConflictReason,
    ) : RegisterOutcome
}

/** Outcome of [WorkloadRegistrationAuthority.updateMetadata]. */
sealed interface MetadataUpdateOutcome {
    /** Metadata changed; identity unchanged; state version advanced. */
    data class Applied(
        val exposure: WorkloadExposure,
    ) : MetadataUpdateOutcome

    /** Requested metadata equals current metadata; no authoritative mutation occurred. */
    data class Unchanged(
        val exposure: WorkloadExposure,
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
        val exposure: WorkloadExposure,
    ) : LifecycleTransitionOutcome

    /** Target state equals current state; no authoritative mutation occurred. */
    data class Unchanged(
        val exposure: WorkloadExposure,
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
