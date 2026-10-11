package dev.tramai.examples.springaiboundary

import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * The declared XR1 boundary: a Spring AI [ToolCallback] whose invocation is authorized by TramAI before
 * the delegate can produce its side effect.
 *
 * Enforcement is caller-side and authoritative **at this declared boundary**: the framework dispatches
 * tool calls through this callback, and the callback consults the engine before delegating. Nothing is
 * re-decided here; [PolicyEngine] remains the only decision source.
 *
 * Both [call] overloads are overridden on purpose. Governing only the single-argument overload would
 * leave the two-argument path a bypass, which is exactly the kind of hole this proof exists to exclude.
 */
class GovernedToolCallback(
    private val delegate: ToolCallback,
    private val engine: PolicyEngine,
    private val recorder: BoundaryEvidenceRecorder,
    private val context: BoundaryContext,
) : ToolCallback {
    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun call(toolInput: String): String = govern(toolInput) { delegate.call(it) }

    override fun call(
        toolInput: String,
        toolContext: ToolContext?,
    ): String = govern(toolInput) { delegate.call(it, toolContext) }

    private fun govern(
        toolInput: String,
        execute: (String) -> String,
    ): String {
        val toolName = delegate.toolDefinition.name()
        // ponytail: the Spring AI callback API is synchronous, so the coroutine-based engine is bridged
        // with runBlocking here. Ceiling: one blocking hop per governed invocation. Upgrade when Spring
        // AI exposes a suspend/asynchronous callback contract.
        // Fail closed: an authority that cannot be consulted is not an authority that said yes. Cancellation
        // is not a governance outcome, and an Error is rethrown rather than being read as permission.
        val decision =
            runCatching { runBlocking { engine.evaluate(contextFor(toolName)) } }
                .getOrElse { failure ->
                    if (failure is CancellationException || failure is Error) throw failure
                    recorder.recordRefusal(toolName, UNAVAILABLE_REASON_CODE)
                    return refusal(
                        toolName = toolName,
                        reasonCode = UNAVAILABLE_REASON_CODE,
                        reason = "governance evaluation unavailable: ${failure::class.simpleName}",
                        outcome = BoundaryOutcome.REFUSED_GOVERNANCE_UNAVAILABLE,
                    )
                }

        return when (decision) {
            PolicyDecision.Allow -> {
                // Record the authorization BEFORE the effect. If the audit store cannot accept the
                // decision, the action must not run at all — an action that ran with no evidence it was
                // authorized is exactly the gap this boundary exists to close.
                recorder.recordAllow(toolName)
                execute(toolInput)
            }

            is PolicyDecision.Deny -> {
                recorder.recordRefusal(toolName, decision.reasonCode)
                refusal(toolName, decision.reasonCode, decision.reason, BoundaryOutcome.REFUSED_DENY)
            }

            is PolicyDecision.RequireApproval -> {
                recorder.recordRequireApproval(toolName, REQUIRE_APPROVAL_REASON_CODE)
                refusal(
                    toolName = toolName,
                    reasonCode = REQUIRE_APPROVAL_REASON_CODE,
                    reason = "human approval required; this boundary performs no continuation",
                    outcome = BoundaryOutcome.REFUSED_REQUIRE_APPROVAL,
                )
            }
        }
    }

    /**
     * The engine's own context type, populated with what this boundary actually knows. The governed run
     * identity is carried, never re-minted, and the correlation id is the caller's.
     */
    private fun contextFor(toolName: String): PolicyContext =
        PolicyContext(
            enforcementPoint = EnforcementPoint.BEFORE_TOOL_EXECUTION,
            workflowRunId = context.runId,
            correlationId = context.correlationId,
            actorId = context.actorId,
            toolName = toolName,
            toolSecurity = context.security,
            policyVersion = context.policyVersion,
        )

    private fun refusal(
        toolName: String,
        reasonCode: String,
        reason: String,
        outcome: BoundaryOutcome,
    ): String =
        BoundaryRefusal(
            outcome = outcome,
            toolName = toolName,
            reasonCode = reasonCode,
            reason = reason,
            correlationId = context.correlationId,
            runId = context.runId,
        ).toJson()

    internal companion object {
        /** Not a policy reason code: this is the boundary's own code for "authority unavailable". */
        const val UNAVAILABLE_REASON_CODE = "governance-unavailable"

        const val REQUIRE_APPROVAL_REASON_CODE = "require-approval-no-continuation"
    }
}
