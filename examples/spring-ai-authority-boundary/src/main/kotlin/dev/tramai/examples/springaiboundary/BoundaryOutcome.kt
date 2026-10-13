package dev.tramai.examples.springaiboundary

import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.policy.ToolSecurityMetadata

/**
 * The governance inputs this boundary carries into every evaluation.
 *
 * Grouped rather than passed as five loose parameters: a caller cannot then supply the run identity
 * without the policy version it is being decided under, and the adapter cannot drift from the identity
 * the evidence is recorded against.
 */
data class BoundaryContext(
    val identity: GovernedRunIdentity,
    val security: ToolSecurityMetadata,
    val policyVersion: String,
    val actorId: String,
    val correlationId: String,
) {
    /** The governed run id, never re-minted at the boundary. */
    val runId: String get() = identity.runId.value

    /**
     * The deployment tuple as the reserved `identity.*` keys — the single place this boundary
     * translates its typed identity, so the evaluator's context and the recorded evidence cannot
     * drift from one another.
     *
     * The key names are repeated literal-for-literal because the validator that defines them
     * (`RuntimeEvidenceAttribution`) is internal to `tramai-security` and this example is a separate
     * module, so they cannot be imported. A shared public constant would remove the duplication, and
     * would also be an API change to a contract this slice must not touch — hence the carriage
     * test, which fails if a key is renamed here rather than silently downgrading a governed
     * context to an ungoverned one.
     *
     * The run component is deliberately absent: the canonical run identifier is `workflowRunId`,
     * and duplicating it here would create a second run identifier that could disagree with it.
     */
    internal val identityAttribution: Map<String, String>
        get() =
            mapOf(
                "identity.workloadId" to identity.deployment.workloadId.value,
                "identity.configurationId" to identity.deployment.configuration.id.value,
                "identity.configurationVersion" to identity.deployment.configuration.version.value,
                "identity.environmentId" to identity.deployment.environmentId.value,
                "identity.deploymentId" to identity.deployment.deploymentId.value,
            )
}

/**
 * The declared external boundary of XR1-0001.
 *
 * ## What is governed
 *
 * A Spring AI tool invocation, intercepted at the framework's own callback dispatch
 * (`org.springframework.ai.tool.ToolCallback`), which is the point where the operation can actually be
 * prevented. The application stays Spring AI-owned: it decides which tools exist and asks the model,
 * and TramAI decides only whether a proposed invocation is permitted.
 *
 * ## Why the adapter decides nothing
 *
 * Every decision comes from [dev.tramai.core.policy.PolicyEngine.evaluate] at
 * [dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_EXECUTION]. There is no Spring-specific policy
 * here — only translation of a framework intent into a
 * [dev.tramai.core.policy.PolicyContext] and enforcement of the result. A denial
 * ([dev.tramai.core.policy.PolicyDecision.Deny]) is not a provider failure: it is a governance refusal
 * with a stable reason code, and it is never retryable.
 *
 * ## Enforcement strength
 *
 * This boundary is [EnforcementStrength.AUTHORITATIVE] *for the declared callback path only*. It says
 * nothing about any other way the host application might reach the same side effect — which is exactly
 * why the boundary is declared rather than assumed.
 */
enum class EnforcementStrength {
    /** The boundary can prevent the operation, and does. */
    AUTHORITATIVE,

    /** The boundary observes but cannot prevent. */
    OBSERVED,
}

/** Outcome of one governed external invocation, as observable by the caller. */
enum class BoundaryOutcome {
    /** Authorization allowed the operation and the delegate ran exactly once. */
    EXECUTED,

    /** Authorization refused. The delegate was never invoked. */
    REFUSED_DENY,

    /** Authorization requires human approval; this slice performs no continuation. Delegate not invoked. */
    REFUSED_REQUIRE_APPROVAL,

    /**
     * Governance could not be consulted, or failed. The delegate was never invoked and the failure was
     * not interpreted as permission.
     */
    REFUSED_GOVERNANCE_UNAVAILABLE,
}

/**
 * Structured refusal returned in place of a tool result when the operation is not permitted.
 *
 * Deliberately *not* an exception and deliberately not shaped like a provider error: a refusal must not
 * be retried as if the provider had failed, and no caller may read it as permission.
 */
data class BoundaryRefusal(
    val outcome: BoundaryOutcome,
    val toolName: String,
    val reasonCode: String,
    val reason: String,
    val correlationId: String,
    val runId: String,
    val enforcementStrength: EnforcementStrength = EnforcementStrength.AUTHORITATIVE,
) {
    /** Stable, machine-readable shape; every JSON-required escape is applied to free-form values. */
    fun toJson(): String =
        buildString {
            append("{\"governanceOutcome\":\"").append(outcome.name).append('"')
            append(",\"toolName\":\"").append(escape(toolName)).append('"')
            append(",\"reasonCode\":\"").append(escape(reasonCode)).append('"')
            append(",\"reason\":\"").append(escape(reason)).append('"')
            append(",\"correlationId\":\"").append(escape(correlationId)).append('"')
            append(",\"runId\":\"").append(escape(runId)).append('"')
            append(",\"enforcementStrength\":\"").append(enforcementStrength.name).append('"')
            append(",\"executed\":false}")
        }

    /**
     * Escapes every character JSON requires in a string, including the full control-character range, so a
     * reason or identifier carrying one cannot produce a malformed refusal.
     */
    private fun escape(value: String): String =
        buildString {
            value.forEach { char ->
                when {
                    char == '\\' -> append("\\\\")
                    char == '"' -> append("\\\"")
                    char == '\n' -> append("\\n")
                    char == '\r' -> append("\\r")
                    char == '\t' -> append("\\t")
                    char == '\b' -> append("\\b")
                    char == '\u000C' -> append("\\f")
                    char < ' ' -> append(unicodeEscape(char))
                    else -> append(char)
                }
            }
        }

    private fun unicodeEscape(char: Char): String = "\\u" + char.code.toString(HEX_RADIX).padStart(ESCAPE_WIDTH, ZERO)

    private companion object {
        const val HEX_RADIX = 16

        const val ESCAPE_WIDTH = 4

        const val ZERO = '0'
    }
}
