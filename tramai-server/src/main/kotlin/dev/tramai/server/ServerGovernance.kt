package dev.tramai.server

import dev.tramai.controlplane.WorkloadRegistrationAuthority
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId

/**
 * Server-level governed deployment (0.7.1d).
 *
 * One server instance runs as ONE workload deployment. Unset is the default and means
 * "ungoverned": every legacy path stays byte-identical.
 *
 * The two halves of the rule are deliberately different:
 *
 * | | required to match |
 * |---|---|
 * | new execution | the authoritative registration (resolveForNewRun) |
 * | continuation | the identity persisted with the existing run |
 *
 * A continuation never re-consults the registration: continuation is not a new
 * execution, so a later SUSPENDED/RETIRED transition cannot invalidate a run that was
 * already admitted, and the persisted identity — not server configuration — is what a
 * resumed execution is attributed to.
 */
class ServerGovernance internal constructor(
    /** The deployment this server instance runs as, or null for an ungoverned server. */
    val configuredIdentity: WorkloadDeploymentIdentity?,
    private val authority: WorkloadRegistrationAuthority?,
) {
    val isGoverned: Boolean get() = configuredIdentity != null

    /**
     * Authorizes a NEW governed execution, or returns null when this server is ungoverned.
     *
     * Returns the AUTHORITATIVE identity, which is what the run must be attributed to.
     */
    suspend fun authorizeNewRun(): WorkloadDeploymentIdentity? {
        val configured = configuredIdentity ?: return null
        val authority =
            authority
                ?: error("governed server has no WorkloadRegistrationAuthority")
        return authority.resolveForNewRun(configured)
    }

    /**
     * Authorizes continuing an EXISTING run.
     *
     * A legacy run stays legacy even on a governed server. A governed run may only be
     * continued by a server configured with the same deployment — a different (or absent)
     * configured deployment cannot prove it is entitled to continue this run.
     */
    fun authorizeContinuation(persisted: WorkloadDeploymentIdentity?) {
        if (persisted == null) return
        val configured =
            configuredIdentity
                ?: throw WorkflowConflictException(
                    "Run was created by governed deployment '$persisted' and cannot be continued by an ungoverned server",
                )
        if (configured != persisted) {
            throw WorkflowConflictException(
                "Run was created by governed deployment '$persisted' and cannot be continued by deployment '$configured'",
            )
        }
    }

    companion object {
        /** Ungoverned server: legacy behaviour everywhere. */
        val UNSET = ServerGovernance(configuredIdentity = null, authority = null)

        fun of(
            identity: WorkloadDeploymentIdentity,
            authority: WorkloadRegistrationAuthority,
        ): ServerGovernance = ServerGovernance(identity, authority)

        /**
         * Builds from the server's governed configuration properties.
         *
         * All-or-nothing: a partial configuration fails closed at startup. Starting
         * ungoverned because a deployment id was misspelled would silently strip attribution
         * from every run of that server.
         */
        fun fromProperties(
            workloadId: String?,
            configurationId: String?,
            configurationVersion: String?,
            environmentId: String?,
            deploymentId: String?,
            authority: WorkloadRegistrationAuthority?,
        ): ServerGovernance {
            val present =
                mapOf(
                    "tramai.server.governed.workload-id" to workloadId,
                    "tramai.server.governed.configuration-id" to configurationId,
                    "tramai.server.governed.configuration-version" to configurationVersion,
                    "tramai.server.governed.environment-id" to environmentId,
                    "tramai.server.governed.deployment-id" to deploymentId,
                )
            val configured = present.filterValues { !it.isNullOrBlank() }
            if (configured.isEmpty()) return UNSET

            val missing = present.filterKeys { it !in configured.keys }.keys
            require(missing.isEmpty()) {
                "Governed server configuration is incomplete, missing: ${missing.sorted()}"
            }
            val authority =
                authority
                    ?: throw IllegalStateException(
                        "Governed server configuration requires a WorkloadRegistrationAuthority bean",
                    )
            return of(
                identity =
                    WorkloadDeploymentIdentity(
                        workloadId = WorkloadId(requireNotNull(workloadId)),
                        configuration =
                            WorkloadConfigurationIdentity(
                                id = ConfigurationId(requireNotNull(configurationId)),
                                version = ConfigurationVersion(requireNotNull(configurationVersion)),
                            ),
                        environmentId = EnvironmentId(requireNotNull(environmentId)),
                        deploymentId = DeploymentId(requireNotNull(deploymentId)),
                    ),
                authority = authority,
            )
        }
    }
}
