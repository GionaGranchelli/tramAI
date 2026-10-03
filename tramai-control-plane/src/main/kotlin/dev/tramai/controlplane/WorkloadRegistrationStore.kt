package dev.tramai.controlplane

import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId

/**
 * Persistence boundary for the authoritative workload registration. Storage
 * semantics only — registration, configuration-rebinding, lifecycle and
 * metadata rules live in [WorkloadRegistrationAuthority] so no persistence
 * implementation ever re-implements the state machine.
 *
 * Implementations must provide atomic storage semantics:
 * - [create] is atomic — two concurrent creates for the same deployment scope
 *   produce exactly one authoritative outcome;
 * - [compareAndSet] is atomic — a stale writer never overwrites newer state.
 */
interface WorkloadRegistrationStore {
    /** Returns the authoritative registration for the deployment scope, if any. */
    suspend fun find(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
    ): RegisteredWorkload?

    /**
     * Creates the registration atomically. Never an upsert: a conflicting
     * existing registration or configuration binding must be reported, not
     * silently replaced.
     */
    suspend fun create(registration: RegisteredWorkload): CreateResult

    /**
     * Atomically replaces the record stored at [expected]'s deployment scope
     * with [updated] iff:
     *
     * - the stored record still carries [expected]'s state version
     *   (deployment scope + state version are the CAS concurrency token), AND
     * - the stored immutable authority matches the witness carried by
     *   [expected] — identity and configuration fingerprint. A caller that
     *   fabricated a different configuration or fingerprint for the scope
     *   must not gain mutation authority merely by guessing the version.
     *
     * Mutable fields carried by [expected] (metadata, lifecycle) are not
     * re-verified; only the token and the immutable witness are. Returns
     * false when the scope is absent, the stored version has moved on, or the
     * stored immutable authority differs from [expected]'s.
     *
     * The immutable portion — identity and configuration fingerprint — must
     * match between [expected] and [updated]; implementations reject a
     * violation with [IllegalArgumentException].
     */
    suspend fun compareAndSet(
        expected: RegisteredWorkload,
        updated: RegisteredWorkload,
    ): Boolean
}

/** Outcome of an atomic [WorkloadRegistrationStore.create]. */
sealed interface CreateResult {
    /** The record was inserted. */
    data class Created(
        val registration: RegisteredWorkload,
    ) : CreateResult

    /** An identical record (same scope, configuration, fingerprint, metadata) already exists. */
    data class Idempotent(
        val registration: RegisteredWorkload,
    ) : CreateResult

    /** An existing record blocks creation; nothing was written. */
    data class Conflicting(
        val existing: RegisteredWorkload,
        val reason: RegistrationConflictReason,
    ) : CreateResult
}

/**
 * Why an authoritative registration was rejected. The configuration binding is
 * global: if `(configurationId, version)` was ever registered anywhere with a
 * fingerprint, re-registering the same pair with a different fingerprint fails
 * even from a different deployment scope.
 */
enum class RegistrationConflictReason {
    /** Same `(configurationId, version)` pair, different fingerprint — the binding is fixed forever. */
    CONFIGURATION_REBINDING,

    /** Same deployment scope, different configuration, fingerprint or metadata. */
    CONFLICTING_REGISTRATION,
}

/**
 * The registration slot: one authoritative registration per deployment scope.
 * This is a store-level key, not a second canonical identity — the
 * authoritative identity remains [WorkloadDeploymentIdentity].
 */
data class WorkloadDeploymentScope(
    val workloadId: WorkloadId,
    val environmentId: EnvironmentId,
    val deploymentId: DeploymentId,
)
