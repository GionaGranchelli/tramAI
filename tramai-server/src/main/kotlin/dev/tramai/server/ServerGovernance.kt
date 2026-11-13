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
internal class ServerGovernance internal constructor(
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
                    "Run was created by governed deployment '$persisted' and cannot be continued by an ungoverned " +
                        "server",
                )
        if (configured != persisted) {
            throw WorkflowConflictException(
                "Run was created by governed deployment '$persisted' and cannot be continued by deployment " +
                    "'$configured'",
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
    }
}

/**
 * The five governed-server configuration components exactly as declared in Spring
 * configuration, all optional. Deliberately dumb: it holds strings and nothing else, so
 * the all-or-nothing rule lives in one parser instead of being spread over the bindings.
 */
internal data class ServerGovernanceProperties(
    val workloadId: String?,
    val configurationId: String?,
    val configurationVersion: String?,
    val environmentId: String?,
    val deploymentId: String?,
)

/**
 * Resolves the governed server configuration. Pure — no Spring types — so the
 * fail-closed rule is provable without a container.
 *
 * All-or-nothing by design: starting *ungoverned* because a deployment id was misspelled
 * would silently strip attribution from every run of that server, so a partial
 * configuration is a startup failure rather than a default.
 */
internal fun serverGovernanceFrom(
    properties: ServerGovernanceProperties,
    authority: WorkloadRegistrationAuthority?,
): ServerGovernance {
    val values =
        mapOf(
            "tramai.server.governed.workload-id" to properties.workloadId,
            "tramai.server.governed.configuration-id" to properties.configurationId,
            "tramai.server.governed.configuration-version" to properties.configurationVersion,
            "tramai.server.governed.environment-id" to properties.environmentId,
            "tramai.server.governed.deployment-id" to properties.deploymentId,
        )
    val configured = values.filterValues { !it.isNullOrBlank() }
    if (configured.isEmpty()) return ServerGovernance.UNSET

    val missing = values.keys - configured.keys
    require(missing.isEmpty()) {
        "Governed server configuration is incomplete, missing: ${missing.sorted()}"
    }
    val registeredAuthority =
        authority
            ?: error("Governed server configuration requires a WorkloadRegistrationAuthority bean")
    return ServerGovernance.of(
        identity = properties.completeIdentity(),
        authority = registeredAuthority,
    )
}

/** Every component is present and non-blank; the parser has already enforced that. */
private fun ServerGovernanceProperties.completeIdentity(): WorkloadDeploymentIdentity =
    WorkloadDeploymentIdentity(
        workloadId = WorkloadId(requireNotNull(workloadId)),
        configuration =
            WorkloadConfigurationIdentity(
                id = ConfigurationId(requireNotNull(configurationId)),
                version = ConfigurationVersion(requireNotNull(configurationVersion)),
            ),
        environmentId = EnvironmentId(requireNotNull(environmentId)),
        deploymentId = DeploymentId(requireNotNull(deploymentId)),
    )
