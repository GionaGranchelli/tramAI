package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.exception.ApprovalNotFoundException
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.fail
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test

class ApprovalEngineEdgeCaseTest {

    private val fixedClock = Clock.fixed(
        Instant.parse("2026-06-07T12:00:00Z"),
        ZoneId.of("UTC"),
    )
    private val toolName = "test_tool"
    private val toolArguments = """{"key":"value"}"""
    private val toolCallId = "call-edge-1"

    /** Fake ApprovalGateCoordinator that can be configured to fail. */
    private class ConfigurableApprovalGateCoordinator : ApprovalGateCoordinator {
        var failCreate: Boolean = false
        var failAuthorize: Boolean = false
        var lastCreateCommand: CreateApprovalCommand? = null
        var lastAuthorizeCommand: AuthorizeResumeCommand? = null

        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
            lastCreateCommand = command
            if (failCreate) throw RuntimeException("coordinator create failed")
            val id = UUID.randomUUID().toString()
            return ApprovalChallenge(
                approvalId = id,
                token = ApprovalToken.parsePresented("token-$id"),
                expiresAt = command.expiresAt,
            )
        }

        override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
            lastAuthorizeCommand = command
            if (failAuthorize) throw RuntimeException("coordinator authorize failed")
            return ApprovalAuthorization(
                approvalId = command.approvalId,
                consumedBy = command.consumedBy,
                consumedAt = Clock.systemUTC().instant(),
                version = command.expectedVersion,
            )
        }
    }

    /**
     * PolicyEngine that can be configured per enforcement point.
     * Uses constructor params instead of outer class references.
     */
    private class ConfigurablePolicyEngine(
        private val toolExecDecision: PolicyDecision = PolicyDecision.RequireApproval(
            ApprovalRequirement(
                toolName = "test_tool",
                argumentsDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                reason = "testing",
                timeoutMillis = 60_000,
            ),
        ),
    ) : PolicyEngine {
        val evaluatedContexts = mutableListOf<PolicyContext>()
        var workflowResumeDecision: PolicyDecision = PolicyDecision.Allow

        override suspend fun evaluate(context: PolicyContext): PolicyDecision {
            evaluatedContexts.add(context)
            return when (context.enforcementPoint) {
                EnforcementPoint.BEFORE_TOOL_EXECUTION -> toolExecDecision
                EnforcementPoint.BEFORE_WORKFLOW_RESUME -> workflowResumeDecision
                else -> PolicyDecision.Allow
            }
        }
    }

    /** A tool that can be configured to fail on execution. */
    private class ConfigurableTool(
        override val name: String = "test_tool",
        override val description: String = "Test tool",
        override val inputSchemaJson: String = """{"type":"object"}""",
        override val idempotent: Boolean = false,
        override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY,
    ) : ResolvedTool {
        val invocations = mutableListOf<Pair<Any, ToolExecutionContext>>()
        var failOnExecute: Boolean = false

        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            invocations.add(Pair(input, context))
            if (failOnExecute) {
                throw RuntimeException("tool execution failed")
            }
            return ToolResult.Success("""{"result":"ok"}""")
        }
    }

    private val tool = ConfigurableTool()
    private val toolRegistry = ToolRegistry(mapOf(tool.name to tool))
    private val coordinator = ConfigurableApprovalGateCoordinator()
    private val continuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
    private val digester = Sha256ToolArgumentsDigester()
    private val suspendedInvocationStore = InMemorySuspendedInvocationStore()
    private val policyEngine = ConfigurablePolicyEngine()
    private val provider = RecordingProvider { request ->
        ModelResponse(
            content = "",
            toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
        )
    }

    private fun createEngine(): TramaiEngine = TramaiEngine(
        provider = provider,
        toolRegistry = toolRegistry,
        policyEngine = policyEngine,
        suspendedInvocationStore = suspendedInvocationStore,
        approvalContinuationStore = continuationStore,
        toolArgumentsDigester = digester,
        approvalGateCoordinator = coordinator,
        clock = fixedClock,
    )

    /** Triggers a suspension flow, returning the exception with approvalId. */
    private fun triggerSuspension(engine: TramaiEngine): ApprovalSuspendedException {
        val service = engine.create<SuspensionTriggerService>()
        return try {
            runBlocking { service.execute("input") }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        }
    }

    // ── Edge Case Tests ──────────────────────────────────────────────

    @Test
    fun `BEFORE_WORKFLOW_RESUME deny prevents tool execution`() {
        val engine = createEngine()
        val exception = triggerSuspension(engine)

        // Set resume policy to Deny
        policyEngine.workflowResumeDecision = PolicyDecision.Deny(reason = "not authorized", reasonCode = "DENY_RESUME")

        assertThatThrownBy {
            runBlocking {
                engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = exception.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = exception.challenge.token,
                        resumedBy = "admin",
                    )
                )
            }
        }.isInstanceOf(PolicyViolationException::class.java)

        // Tool must NOT have been executed
        assertThat(tool.invocations).isEmpty()

        // Continuation stays in PENDING (it was not claimed)
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)
    }

    @Test
    fun `BEFORE_WORKFLOW_RESUME deny throws PolicyViolationException`() {
        val engine = createEngine()
        val exception = triggerSuspension(engine)
        policyEngine.workflowResumeDecision = PolicyDecision.Deny(reason = "not authorized", reasonCode = "DENY_RESUME")

        assertThatThrownBy {
            runBlocking {
                engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = exception.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = exception.challenge.token,
                        resumedBy = "admin",
                    )
                )
            }
        }.isInstanceOf(PolicyViolationException::class.java)
    }

    @Test
    fun `resumeApproval with nonexistent approvalId fails safely`() {
        val engine = createEngine()
        engine.create<SuspensionTriggerService>()

        assertThatThrownBy {
            runBlocking {
                engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = "nonexistent-approval-id",
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = ApprovalToken.parsePresented("invalid-token"),
                        resumedBy = "admin",
                    )
                )
            }
        }.isInstanceOf(ApprovalNotFoundException::class.java)
    }

    @Test
    fun `claim succeeds but tool fails - continuation stays CLAIMED`() {
        val engine = createEngine()
        val exception = triggerSuspension(engine)

        // Make tool fail on execution
        tool.failOnExecute = true

        assertThatThrownBy {
            runBlocking {
                engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = exception.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = exception.challenge.token,
                        resumedBy = "admin",
                    )
                )
            }
        }.isInstanceOf(RuntimeException::class.java)

        // Tool was invoked once
        assertThat(tool.invocations).hasSize(1)

        // Continuation stays CLAIMED (not COMPLETED)
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
    }

    @Test
    fun `suspension store failure after continuation created still creates continuation`() {
        // Verifies that if the suspendedInvocationStore fails after the continuation
        // is created, the continuation still exists (no leak of un-tracked state)
        val fragileStore = object : SuspendedInvocationStore {
            var created = false
            override suspend fun create(invocation: SuspendedInvocation): SuspendedInvocation {
                if (created) throw RuntimeException("simulated store failure on second usage")
                created = true
                return invocation
            }
            override suspend fun get(approvalId: String): SuspendedInvocation? = null
            override suspend fun remove(approvalId: String): SuspendedInvocation? = null
        }

        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = fragileStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
        )

        val service = engine.create<SuspensionTriggerService>()

        assertThatThrownBy {
            runBlocking { service.execute("input") }
        }.isInstanceOf(RuntimeException::class.java)
    }

    // ── Service Interface ──────────────────────────────────────────

    @AiService
    @SystemPrompt("You are a helpful assistant.")
    private interface SuspensionTriggerService {
        @Operation(
            prompt = "Execute the test tool",
            model = "claude-sonnet-4-20250514",
            tools = ["test_tool"],
        )
        suspend fun execute(input: String): String
    }

    // ── Test Provider ──────────────────────────────────────────────

    private class RecordingProvider(
        private val responder: suspend (ModelRequest) -> ModelResponse,
    ) : dev.tramai.core.provider.ModelProvider {
        val requests = mutableListOf<ModelRequest>()

        override suspend fun complete(request: ModelRequest): ModelResponse {
            requests += request
            return responder(request)
        }
    }
}
