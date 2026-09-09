package dev.tramai.controlplane

import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId

/**
 * In-memory [WorkloadRegistrationStore] reference implementation.
 *
 * Single lock for the whole store keeps every operation atomic and the
 * semantics trivially auditable; the store is an authority contract reference,
 * not a throughput path.
 *
 * ponytail: single global lock; per-scope locks would matter only if this
 * store ever leaves the test/reference role.
 */
class InMemoryWorkloadRegistrationStore : WorkloadRegistrationStore {
    private val lock = Any()
    private val registrations = LinkedHashMap<WorkloadDeploymentScope, RegisteredWorkload>()
    private val configurationBindings = HashMap<ConfigurationBindingKey, ConfigurationFingerprint>()

    private data class ConfigurationBindingKey(
        val configurationId: String,
        val configurationVersion: String,
    )

    private fun WorkloadConfigurationIdentity.toBindingKey(): ConfigurationBindingKey = ConfigurationBindingKey(id.value, version.value)

    override suspend fun find(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
    ): RegisteredWorkload? =
        synchronized(lock) {
            registrations[WorkloadDeploymentScope(workloadId, environmentId, deploymentId)]
        }

    override suspend fun create(registration: RegisteredWorkload): CreateResult =
        synchronized(lock) {
            val scope = registration.identity.toScope()
            val bindingKey = registration.identity.configuration.toBindingKey()

            // 1) The (configurationId, version) binding is fixed forever, globally:
            //    the same pair with a different fingerprint fails from ANY scope.
            configurationBindings[bindingKey]?.let { boundFingerprint ->
                if (boundFingerprint != registration.configurationFingerprint) {
                    val context =
                        registrations[scope]
                            ?: registrations.entries
                                .firstOrNull {
                                    it.value.identity.configuration
                                        .toBindingKey() == bindingKey
                                }?.value
                            ?: error("Corrupt in-memory store: configuration binding exists without an owning registration")
                    return@synchronized CreateResult.Conflicting(context, RegistrationConflictReason.CONFIGURATION_REBINDING)
                }
            }

            // 2) One authoritative registration per deployment scope. Never an
            //    upsert: identical declaration is idempotent, anything else is a
            //    conflict.
            registrations[scope]?.let { existing ->
                return@synchronized if (existing.isSameDeclaration(registration)) {
                    CreateResult.Idempotent(existing)
                } else {
                    CreateResult.Conflicting(existing, RegistrationConflictReason.CONFLICTING_REGISTRATION)
                }
            }

            // 3) Insert both records atomically under the store lock.
            configurationBindings[bindingKey] = registration.configurationFingerprint
            registrations[scope] = registration
            CreateResult.Created(registration)
        }

    override suspend fun compareAndSet(
        expected: RegisteredWorkload,
        updated: RegisteredWorkload,
    ): Boolean =
        synchronized(lock) {
            val scope = expected.identity.toScope()
            require(updated.identity.toScope() == scope) {
                "compareAndSet must not change the deployment scope of a registration"
            }
            val current = registrations[scope]
            if (current != expected) {
                false
            } else {
                registrations[scope] = updated
                true
            }
        }

    private fun WorkloadDeploymentIdentity.toScope(): WorkloadDeploymentScope =
        WorkloadDeploymentScope(workloadId, environmentId, deploymentId)

    private fun RegisteredWorkload.isSameDeclaration(other: RegisteredWorkload): Boolean =
        identity == other.identity &&
            configurationFingerprint == other.configurationFingerprint &&
            metadata == other.metadata
}
