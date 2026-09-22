package dev.tramai.engine.approval

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
 * Reserved framework metadata keys carrying governed-run attribution on an approval record (0.7.1d1).
 *
 * Deliberately NOT prefixed `tramai.`: the architecture guard (`RuntimeEventCatalogueArchitectureTest`)
 * reserves that namespace for runtime identifiers and configuration properties, and a persisted
 * metadata key is neither.
 *
 * The run id is deliberately NOT among them: `ApprovalBinding.workflowRunId` is the single stored
 * run-id source, so persistence cannot hold a second identifier that diverges from the run it
 * describes.
 */
public object ApprovalAttributionKeys {
    public const val WORKLOAD: String = "approval.identity.workload"
    public const val CONFIGURATION: String = "approval.identity.configuration"
    public const val CONFIGURATION_VERSION: String = "approval.identity.configuration_version"
    public const val ENVIRONMENT: String = "approval.identity.environment"
    public const val DEPLOYMENT: String = "approval.identity.deployment"

    /** All reserved keys, in canonical order. */
    public val ALL: List<String> =
        listOf(WORKLOAD, CONFIGURATION, CONFIGURATION_VERSION, ENVIRONMENT, DEPLOYMENT)
}

/**
 * Attribution of an approval creation path (0.7.1d1).
 *
 * Resolved by the gateway from the canonical runtime identity, never supplied by the application:
 * the factory produces an identity-blind [ApprovalGatewayPersistenceRequest] and the gateway attaches
 * this value afterwards, so the "governed run silently becomes un-attributed" downgrade cannot be
 * expressed by a caller.
 */
public sealed interface ApprovalRunAttribution {
    /** Intentionally un-attributed (legacy) approval: zero reserved keys, byte-compatible. */
    public data object Ungoverned : ApprovalRunAttribution

    /** Governed approval: the canonical identity of the run being suspended. */
    public data class Governed(
        val identity: GovernedRunIdentity,
    ) : ApprovalRunAttribution
}

/** Application input supplied a reserved framework attribution key. */
public class ApprovalAttributionCollisionException(
    message: String,
) : IllegalArgumentException(message)

/** Persisted attribution is partial, blank or otherwise unusable. */
public class ApprovalAttributionCorruptionException(
    message: String,
) : IllegalStateException(message)

/**
 * Encodes the five remaining identity components. The run id is not encoded — it is read back from
 * `ApprovalBinding.workflowRunId` at [decodeApprovalAttribution] time.
 */
public fun encodeApprovalAttribution(identity: GovernedRunIdentity): Map<String, String> {
    val deployment = identity.deployment
    return mapOf(
        ApprovalAttributionKeys.WORKLOAD to deployment.workloadId.value,
        ApprovalAttributionKeys.CONFIGURATION to deployment.configuration.id.value,
        ApprovalAttributionKeys.CONFIGURATION_VERSION to deployment.configuration.version.value,
        ApprovalAttributionKeys.ENVIRONMENT to deployment.environmentId.value,
        ApprovalAttributionKeys.DEPLOYMENT to deployment.deploymentId.value,
    )
}

/**
 * Decodes persisted approval attribution against the approval's own `workflowRunId`, which stays the
 * authoritative run id.
 *
 * Fail closed: all reserved keys absent means an intentionally un-attributed (legacy) approval; a
 * PARTIAL set, a blank value, or an unparseable value is corruption and throws rather than yielding
 * a partially-attributed identity.
 */
public fun decodeApprovalAttribution(
    workflowRunId: String,
    metadata: Map<String, String>,
): ApprovalRunAttribution {
    // Presence and validity are separate equivalence classes. Collapsing them would read a set of
    // present-but-blank reserved keys as "all absent" and downgrade malformed governed state into
    // legacy state — the silent downgrade this parser exists to prevent.
    val present = ApprovalAttributionKeys.ALL.filter(metadata::containsKey)
    if (present.isEmpty()) return ApprovalRunAttribution.Ungoverned

    val expected = ApprovalAttributionKeys.ALL
    val defect =
        when {
            present.size != expected.size -> {
                "is incomplete: all of ${expected.joinToString()} must be present, or none"
            }

            expected.any { metadata.getValue(it).isBlank() } -> {
                "is malformed: a present reserved key must carry a non-blank value"
            }

            else -> {
                null
            }
        }
    if (defect != null) {
        throw ApprovalAttributionCorruptionException(
            "Persisted approval attribution $defect for workflow run '$workflowRunId'",
        )
    }
    return try {
        ApprovalRunAttribution.Governed(
            GovernedRunIdentity(
                deployment =
                    WorkloadDeploymentIdentity(
                        workloadId = WorkloadId(metadata.component(ApprovalAttributionKeys.WORKLOAD)),
                        configuration =
                            WorkloadConfigurationIdentity(
                                id = ConfigurationId(metadata.component(ApprovalAttributionKeys.CONFIGURATION)),
                                version =
                                    ConfigurationVersion(
                                        metadata.component(ApprovalAttributionKeys.CONFIGURATION_VERSION),
                                    ),
                            ),
                        environmentId = EnvironmentId(metadata.component(ApprovalAttributionKeys.ENVIRONMENT)),
                        deploymentId = DeploymentId(metadata.component(ApprovalAttributionKeys.DEPLOYMENT)),
                    ),
                runId = RunId(workflowRunId),
            ),
        )
    } catch (error: IllegalArgumentException) {
        throw ApprovalAttributionCorruptionException(
            "Persisted approval attribution is invalid for workflow run '$workflowRunId': ${error.message}",
        ).apply { initCause(error) }
    }
}

/**
 * Merges framework attribution into the approval's stored metadata.
 *
 * Application metadata can never determine attribution: a reserved key in [applicationMetadata] is
 * rejected as a collision rather than silently overwritten, so an attribution-injection attempt is
 * observable instead of hidden.
 */
public fun mergeApprovalAttribution(
    applicationMetadata: Map<String, String>,
    attribution: ApprovalRunAttribution,
): Map<String, String> {
    val collisions = applicationMetadata.keys.filter { it in ApprovalAttributionKeys.ALL }
    if (collisions.isNotEmpty()) {
        throw ApprovalAttributionCollisionException(
            "Application approval metadata may not supply reserved governed attribution keys: " +
                collisions.sorted().joinToString(),
        )
    }
    return when (attribution) {
        ApprovalRunAttribution.Ungoverned -> applicationMetadata
        is ApprovalRunAttribution.Governed -> applicationMetadata + encodeApprovalAttribution(attribution.identity)
    }
}

private fun Map<String, String>.component(key: String): String =
    requireNotNull(get(key)?.takeIf { it.isNotBlank() }) { "missing attribution component '$key'" }
