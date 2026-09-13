package dev.tramai.orchestration

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId

/**
 * Reserved framework metadata keys carrying governed-run attribution (0.7.1d).
 *
 * This is a PERSISTENCE ENCODING, not an authority: the authority is the
 * canonical [GovernedRunIdentity] of the running execution. The reserved keys are
 * owned by the framework — application checkpoint metadata must never override
 * them, and the framework always writes them last.
 */
internal const val GOVERNED_RUN_WORKLOAD_KEY: String = "tramai.identity.workload"
internal const val GOVERNED_RUN_CONFIGURATION_KEY: String = "tramai.identity.configuration"
internal const val GOVERNED_RUN_CONFIGURATION_VERSION_KEY: String = "tramai.identity.configuration_version"
internal const val GOVERNED_RUN_ENVIRONMENT_KEY: String = "tramai.identity.environment"
internal const val GOVERNED_RUN_DEPLOYMENT_KEY: String = "tramai.identity.deployment"

private val GOVERNED_RUN_ATTRIBUTION_KEYS: List<String> =
    listOf(
        GOVERNED_RUN_WORKLOAD_KEY,
        GOVERNED_RUN_CONFIGURATION_KEY,
        GOVERNED_RUN_CONFIGURATION_VERSION_KEY,
        GOVERNED_RUN_ENVIRONMENT_KEY,
        GOVERNED_RUN_DEPLOYMENT_KEY,
    )

/**
 * Encodes attribution into the reserved checkpoint metadata keys.
 *
 * The run id is deliberately NOT encoded: the checkpoint's own `workflowId` IS the
 * run id, so persistence cannot store a second identifier that diverges from the
 * run it describes. Absent attribution encodes to an empty map, which keeps
 * ungoverned (legacy) checkpoints byte-compatible with records written before
 * attribution existed.
 */
internal fun encodeGovernedRunAttribution(identity: GovernedRunIdentity?): Map<String, String> {
    if (identity == null) return emptyMap()
    val deployment = identity.deployment
    return mapOf(
        GOVERNED_RUN_WORKLOAD_KEY to deployment.workloadId.value,
        GOVERNED_RUN_CONFIGURATION_KEY to deployment.configuration.id.value,
        GOVERNED_RUN_CONFIGURATION_VERSION_KEY to deployment.configuration.version.value,
        GOVERNED_RUN_ENVIRONMENT_KEY to deployment.environmentId.value,
        GOVERNED_RUN_DEPLOYMENT_KEY to deployment.deploymentId.value,
    )
}

/**
 * Decodes persisted attribution against the checkpoint's own `workflowId`, which is
 * the authoritative run id.
 *
 * Fail closed: all reserved keys absent means an intentionally ungoverned (legacy)
 * checkpoint; a PARTIAL set is corruption and throws instead of resuming
 * un-attributed. Never returns a partially-attributed identity.
 */
internal fun decodeGovernedRunAttribution(
    workflowId: String,
    metadata: Map<String, String>,
): GovernedRunIdentity? {
    val values = GOVERNED_RUN_ATTRIBUTION_KEYS.map { key -> metadata[key]?.takeIf { it.isNotBlank() } }
    if (values.all { it == null }) return null
    if (values.any { it == null }) {
        throw WorkflowCheckpointCorruptionException(
            "Persisted governed run attribution is incomplete for workflow run '$workflowId': " +
                "all of ${GOVERNED_RUN_ATTRIBUTION_KEYS.joinToString()} must be present, or none",
        )
    }
    return try {
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId(values[0]!!),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId(values[1]!!),
                            version = ConfigurationVersion(values[2]!!),
                        ),
                    environmentId = EnvironmentId(values[3]!!),
                    deploymentId = DeploymentId(values[4]!!),
                ),
            runId = RunId(workflowId),
        )
    } catch (error: IllegalArgumentException) {
        throw WorkflowCheckpointCorruptionException(
            "Persisted governed run attribution is invalid for workflow run '$workflowId': ${error.message}",
        )
    }
}

/**
 * Continuity gate at every reconstruction boundary (0.7.1d).
 *
 * A governed run has exactly one canonical [GovernedRunIdentity]; resume must
 * restore the persisted one unchanged or refuse. Comparing the run id alone is
 * explicitly insufficient: a run id can be replayed against another workload,
 * configuration, environment or deployment.
 *
 * - both absent → intentionally ungoverned (legacy) run, resume proceeds;
 * - exactly one absent → fails closed (a governed run cannot become un-attributed,
 *   and an ungoverned run cannot be resumed as governed);
 * - both present and equal → continuity proven;
 * - both present and different → substitution attempt, rejected.
 */
internal fun requireGovernedRunAttributionContinuity(
    workflowName: String,
    workflowId: String,
    persisted: GovernedRunIdentity?,
    requested: GovernedRunIdentity?,
) {
    if (persisted == requested) return
    val detail =
        when {
            persisted == null -> "checkpoint carries no governed attribution"
            requested == null -> "resume carries no governed attribution"
            else -> "attribution was substituted (${describeAttributionSubstitution(persisted, requested)})"
        }
    throw WorkflowResumeException(
        "Governed run attribution is not continuous for workflow '$workflowName' and workflowId='$workflowId': $detail",
    )
}

private fun describeAttributionSubstitution(
    persisted: GovernedRunIdentity,
    requested: GovernedRunIdentity,
): String =
    buildList {
        if (persisted.runId != requested.runId) {
            add("runId '${persisted.runId}' != '${requested.runId}'")
        }
        val expected = persisted.deployment
        val actual = requested.deployment
        if (expected.workloadId != actual.workloadId) {
            add("workloadId '${expected.workloadId}' != '${actual.workloadId}'")
        }
        if (expected.configuration.id != actual.configuration.id) {
            add("configurationId '${expected.configuration.id}' != '${actual.configuration.id}'")
        }
        if (expected.configuration.version != actual.configuration.version) {
            add("configurationVersion '${expected.configuration.version}' != '${actual.configuration.version}'")
        }
        if (expected.environmentId != actual.environmentId) {
            add("environmentId '${expected.environmentId}' != '${actual.environmentId}'")
        }
        if (expected.deploymentId != actual.deploymentId) {
            add("deploymentId '${expected.deploymentId}' != '${actual.deploymentId}'")
        }
    }.joinToString(", ").ifEmpty { "identity differs" }
