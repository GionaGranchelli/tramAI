package dev.tramai.core.policy

/**
 * Context passed to [PolicyEngine.evaluate] at every enforcement point.
 *
 * Not all fields are populated for every enforcement point. The engine
 * provides whatever context is available at the call site.
 *
 * Field availability by enforcement point:
 * - providerId/modelName: populated at PROVIDER_RESOLUTION, PROVIDER_INVOCATION, FALLBACK
 * - dataClassification/classificationSource: populated at all points when data is classified
 * - toolName/toolSecurity: populated at TOOL_EXPOSURE, TOOL_EXECUTION, TOOL_RESULT_REINJECTION
 * - targetDestination: populated at PROVIDER_INVOCATION, TOOL_EXECUTION when egress is checked
 * - workflowDigest: guaranteed non-null at enforcement points that produce approvals;
 *   nullable elsewhere. ApprovalRequest construction asserts non-null.
 */
data class PolicyContext @JvmOverloads constructor(
    val enforcementPoint: EnforcementPoint,
    /** Stable workflow definition identifier. */
    val workflowId: String? = null,
    /** Identifier for one concrete execution run. */
    val workflowRunId: String? = null,
    /** Correlation ID linking related operations. */
    val correlationId: String,
    /** Identity of the caller or service account. */
    val actorId: String,
    // Provider context
    val providerId: String? = null,
    val modelName: String? = null,
    val fallbackProviderId: String? = null,
    // Data context
    val dataClassification: DataClassification? = null,
    val classificationSource: ClassificationSource? = null,
    // Tool context
    val toolName: String? = null,
    val toolSecurity: ToolSecurityMetadata? = null,
    // Network context
    val targetDestination: String? = null,
    // State context
    val policyVersion: String,
    val workflowDigest: String? = null,
    // Extensibility
    val attributes: Map<String, String> = emptyMap(),
)
