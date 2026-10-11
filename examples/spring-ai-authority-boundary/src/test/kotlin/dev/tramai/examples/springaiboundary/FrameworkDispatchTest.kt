package dev.tramai.examples.springaiboundary

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.InMemoryAuditStore
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The same two outcomes, but dispatched by Spring AI rather than by the test.
 *
 * Calling `GovernedToolCallback.call` directly proves the adapter behaves. Handing the callback to the
 * framework's tool-calling machinery and letting *it* dispatch proves the boundary sits on the
 * framework's real invocation path. Only the second supports a claim about a Spring AI application.
 *
 * ## Why the manager is driven directly instead of through `ChatClient`
 *
 * Measured, not assumed. `ChatClient.create(model)` obtains its tool-calling manager from a Spring
 * application context; with no context — which this slice deliberately has, since a starter is a
 * checkpoint non-goal — nothing dispatches: the ChatClient path made exactly one model call, recorded
 * zero decisions and invoked the tool zero times, even though the request carried
 * `DefaultToolCallingChatOptions` with internal execution enabled and the governed callback present.
 *
 * [ToolCallingManager] is the machinery `ChatClient` delegates to, and `DefaultToolCallingManager` is
 * its production implementation. Driving it with real `Prompt`, `ChatResponse` and governed-callback
 * types exercises the framework's own dispatch, tool resolution and exception handling. The model
 * provider is the deterministic local stub the checkpoint permits; the enforcement boundary is real.
 */
class FrameworkDispatchTest {
    private val toolName = SideEffectfulToolCallback.TOOL_NAME
    private val runId = "run-xr1-0002"
    private val correlationId = "corr-xr1-0002"

    @Test
    fun `a denied tool is prevented on the framework dispatch path`() {
        val tool = SideEffectfulToolCallback()
        val recorder = recorder()
        val boundary = boundary(tool, DefaultPolicyEngine(PolicyConfiguration()), recorder)

        val history = dispatch(boundary)

        assertTrue(tool.invocations().isEmpty(), "the framework's dispatch was prevented, not merely observed")
        assertEquals(1, recorder.events().size, "exactly one authoritative decision was made")
        assertEquals("DENY", recorder.events().single().decision, "the framework path consulted the engine")
        assertEquals(
            "unknown-tool",
            recorder.events().single().reasonCode,
            "the engine's own reason code reached the evidence",
        )
        val toolResults = history.filterIsInstance<ToolResponseMessage>().flatMap { it.responses }
        assertTrue(toolResults.isNotEmpty(), "the framework carried a tool result back into the conversation")
        assertTrue(
            toolResults.any { it.responseData().contains("\"governanceOutcome\":\"REFUSED_DENY\"") },
            "the refusal the boundary returned is what the framework recorded as the tool result",
        )
    }

    @Test
    fun `an allowed tool executes exactly once on the framework dispatch path`() {
        val tool = SideEffectfulToolCallback()
        val recorder = recorder()
        val boundary = boundary(tool, allowedEngine(), recorder)

        val history = dispatch(boundary)

        assertEquals(1, tool.invocations().size, "the permitted action ran exactly once")
        assertEquals("ALLOW", recorder.events().single().decision, "the authorization is on the evidence")
        val toolResults = history.filterIsInstance<ToolResponseMessage>().flatMap { it.responses }
        assertTrue(
            toolResults.any { it.responseData().contains("\"applied\":true") },
            "the tool's own result is what the framework recorded",
        )
    }

    /**
     * The full framework path: the model proposes an action, Spring AI resolves and dispatches it, and the
     * conversation history is what the framework produced.
     */
    private fun dispatch(boundary: GovernedToolCallback): List<org.springframework.ai.chat.messages.Message> {
        val options =
            ToolCallingChatOptions
                .builder()
                .toolCallbacks(boundary)
                .internalToolExecutionEnabled(true)
                .build()
        val prompt = Prompt(UserMessage("append a record to the ledger"), options)
        val model = StubToolRequestingChatModel(toolName, """{"value":"requested-by-model"}""")

        // The model proposes; it does not execute.
        val response = model.call(prompt)
        assertTrue(response.result.output.hasToolCalls(), "the model's response proposes a tool call")

        // Spring AI resolves the callback by name and dispatches it.
        return ToolCallingManager
            .builder()
            .build()
            .executeToolCalls(prompt, response)
            .conversationHistory()
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun recorder(): BoundaryEvidenceRecorder {
        // Block body on purpose: a single-expression body is collapsed back onto one line by
        // spotlessApply, and the collapsed line exceeds the static-analysis ceiling.
        return BoundaryEvidenceRecorder(AuditEngine(InMemoryAuditStore()), boundaryContext())
    }

    /** One source of governance identity for the evaluation and for the evidence recorded about it. */
    private fun boundaryContext(): BoundaryContext =
        BoundaryContext(
            identity =
                GovernedRunIdentity(
                    deployment =
                        WorkloadDeploymentIdentity(
                            workloadId = WorkloadId("workload-xr1"),
                            configuration =
                                WorkloadConfigurationIdentity(
                                    id = ConfigurationId("configuration-xr1"),
                                    version = ConfigurationVersion("v1"),
                                ),
                            environmentId = EnvironmentId("environment-local"),
                            deploymentId = DeploymentId("deployment-xr1-1"),
                        ),
                    runId = RunId(runId),
                ),
            security = toolSecurity(),
            policyVersion = "policy-xr1-v1",
            actorId = "spring-ai-workload",
            correlationId = correlationId,
        )

    private fun toolSecurity(): ToolSecurityMetadata =
        ToolSecurityMetadata(
            permission = "ledger.append",
            risk = RiskLevel.LOW,
            approval = ApprovalMode.AUTO,
            managedNetworkEgress = ManagedNetworkEgress.ALLOW,
            audit = AuditDetail.DECISION_ONLY,
        )

    private fun allowedEngine(): PolicyEngine =
        DefaultPolicyEngine(
            PolicyConfiguration(allowedTools = setOf(toolName), allowedPermissions = setOf("ledger.append")),
        )

    private fun boundary(
        tool: SideEffectfulToolCallback,
        engine: PolicyEngine,
        recorder: BoundaryEvidenceRecorder,
    ): GovernedToolCallback =
        GovernedToolCallback(
            delegate = tool,
            engine = engine,
            recorder = recorder,
            context = boundaryContext(),
        )
}
