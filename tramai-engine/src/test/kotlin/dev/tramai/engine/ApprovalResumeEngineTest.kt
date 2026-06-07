package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.exception.ApprovalSuspendedException
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

class ApprovalResumeEngineTest {

    private val fixedClock = Clock.fixed(
        Instant.parse("2026-06-07T12:00:00Z"),
        ZoneId.of("UTC"),
    )
    private val toolName = "test_calculator"
    private val toolArguments = """{"x":2,"y":3}"""
    private val toolCallId = "call-resume-1"
    private val resumedBy = "admin"

    /** Fake ApprovalGateCoordinator that always authorizes resume. */
    private class PermitApprovalGateCoordinator : ApprovalGateCoordinator {
        var lastAuthorizeCommand: AuthorizeResumeCommand? = null
        var lastCreateCommand: CreateApprovalCommand? = null
        var nextChallengeId: String = UUID.randomUUID().toString()

        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
            lastCreateCommand = command
            val id = nextChallengeId
            nextChallengeId = UUID.randomUUID().toString()
            return ApprovalChallenge(
                approvalId = id,
                token = ApprovalToken.parsePresented("token-$id"),
                expiresAt = command.expiresAt,
            )
        }

        override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
            lastAuthorizeCommand = command
            return ApprovalAuthorization(
                approvalId = command.approvalId,
                consumedBy = command.consumedBy,
                consumedAt = Clock.systemUTC().instant(),
                version = command.expectedVersion,
            )
        }
    }

    /**
     * PolicyEngine that returns RequireApproval for BEFORE_TOOL_EXECUTION,
     * and a configurable decision for BEFORE_WORKFLOW_RESUME.
     * Uses explicit constructor params instead of accessing outer members.
     */
    private class SelectivePolicyEngine(
        private val toolExecApprovalToolName: String,
        private val requireApprovalAtToolExec: Boolean = true,
        vararg resumeDecisions: PolicyDecision = arrayOf(PolicyDecision.Allow),
    ) : PolicyEngine {
        val evaluatedContexts = mutableListOf<PolicyContext>()
        var resumeDecision: PolicyDecision = resumeDecisions.firstOrNull() ?: PolicyDecision.Allow

        override suspend fun evaluate(context: PolicyContext): PolicyDecision {
            evaluatedContexts.add(context)
            if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION && requireApprovalAtToolExec) {
                return PolicyDecision.RequireApproval(
                    ApprovalRequirement(
                        toolName = toolExecApprovalToolName,
                        argumentsDigest = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                        reason = "testing",
                        timeoutMillis = 60_000,
                    )
                )
            }
            if (context.enforcementPoint == EnforcementPoint.BEFORE_WORKFLOW_RESUME) {
                return resumeDecision
            }
            return PolicyDecision.Allow
        }
    }

    /** A tool that records invocations and returns a predictable result. */
    private class RecordingTool(
        override val name: String = "test_calculator",
        override val description: String = "Calculator tool",
        override val inputSchemaJson: String = """{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}}}""",
        override val idempotent: Boolean = false,
        override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY,
        private val result: ToolResult = ToolResult.Success("""{"result":5}"""),
    ) : ResolvedTool {
        val invocations = mutableListOf<Pair<Any, ToolExecutionContext>>()

        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            invocations.add(Pair(input, context))
            return result
        }
    }

    private val tool = RecordingTool()
    private val toolRegistry = ToolRegistry(mapOf(tool.name to tool))
    private val coordinator = PermitApprovalGateCoordinator()
    private val continuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
    private val digester = Sha256ToolArgumentsDigester()
    private val suspendedInvocationStore = InMemorySuspendedInvocationStore()
    private val policyEngine = SelectivePolicyEngine(toolExecApprovalToolName = toolName)
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

    /**
     * Triggers a suspension via the real engine flow, captures the exception,
     * and returns the approvalId to use for resume.
     */
    private fun triggerSuspension(engine: TramaiEngine): ApprovalSuspendedException {
        val service = engine.create<SuspensionTriggerService>()
        return try {
            runBlocking { service.execute("test input") }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        }
    }

    @Test
    fun `resumeApproval loads suspended invocation by approvalId`() {
        val engine = createEngine()
        val exception = triggerSuspension(engine)
        policyEngine.resumeDecision = PolicyDecision.Allow

        runBlocking {
            val result = engine.resumeApproval(
                ResumeApprovalCommand(
                    approvalId = exception.approvalId,
                    approvalExpectedVersion = 0L,
                    continuationExpectedVersion = 0L,
                    presentedToken = exception.challenge.token,
                    resumedBy = resumedBy,
                )
            )
            assertThat(result).isNotNull
        }

        val remaining = runBlocking { suspendedInvocationStore.get(exception.approvalId) }
        assertThat(remaining).isNull()
    }

    @Test
    fun `resumeApproval authorizes through ApprovalGateCoordinator`() {
        val engine = createEngine()
        val exception = triggerSuspension(engine)
        policyEngine.resumeDecision = PolicyDecision.Allow

        runBlocking {
            engine.resumeApproval(
                ResumeApprovalCommand(
                    approvalId = exception.approvalId,
                    approvalExpectedVersion = 0L,
                    continuationExpectedVersion = 0L,
                    presentedToken = exception.challenge.token,
                    resumedBy = resumedBy,
                )
            )
        }

        assertThat(coordinator.lastAuthorizeCommand).isNotNull
        assertThat(coordinator.lastAuthorizeCommand!!.approvalId).isEqualTo(exception.approvalId)
        assertThat(coordinator.lastAuthorizeCommand!!.consumedBy).isEqualTo(resumedBy)
        assertThat(coordinator.lastAuthorizeCommand!!.toolName).isEqualTo(toolName)
    }

    @Test
    fun `resumeApproval enforces BEFORE_WORKFLOW_RESUME`() {
        val engine = createEngine()
        val exception = triggerSuspension(engine)
        policyEngine.resumeDecision = PolicyDecision.Allow

        runBlocking {
            engine.resumeApproval(
                ResumeApprovalCommand(
                    approvalId = exception.approvalId,
                    approvalExpectedVersion = 0L,
                    continuationExpectedVersion = 0L,
                    presentedToken = exception.challenge.token,
                    resumedBy = resumedBy,
                )
            )
        }

        assertThat(policyEngine.evaluatedContexts.any {
            it.enforcementPoint == EnforcementPoint.BEFORE_WORKFLOW_RESUME
        }).isTrue
    }

    @Test
    fun `resumeApproval claims continuation exactly once and completes it`() {
        val engine = createEngine()
        val exception = triggerSuspension(engine)
        policyEngine.resumeDecision = PolicyDecision.Allow

        runBlocking {
            engine.resumeApproval(
                ResumeApprovalCommand(
                    approvalId = exception.approvalId,
                    approvalExpectedVersion = 0L,
                    continuationExpectedVersion = 0L,
                    presentedToken = exception.challenge.token,
                    resumedBy = resumedBy,
                )
            )
        }

        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)
        assertThat(continuation.claimedBy).isEqualTo(resumedBy)
        assertThat(continuation.claimedAt).isNotNull
        assertThat(continuation.completedAt).isNotNull
    }

    @Test
    fun `resumeApproval executes the suspended tool`() {
        val engine = createEngine()
        val exception = triggerSuspension(engine)
        policyEngine.resumeDecision = PolicyDecision.Allow

        runBlocking {
            engine.resumeApproval(
                ResumeApprovalCommand(
                    approvalId = exception.approvalId,
                    approvalExpectedVersion = 0L,
                    continuationExpectedVersion = 0L,
                    presentedToken = exception.challenge.token,
                    resumedBy = resumedBy,
                )
            )
        }

        assertThat(tool.invocations).hasSize(1)
        val (_, context) = tool.invocations.single()
        assertThat(context.operationName).isNotBlank()
    }

    @Test
    fun `resumeApproval returns the tool result`() {
        val engine = createEngine()
        val exception = triggerSuspension(engine)
        policyEngine.resumeDecision = PolicyDecision.Allow

        val result = runBlocking {
            engine.resumeApproval(
                ResumeApprovalCommand(
                    approvalId = exception.approvalId,
                    approvalExpectedVersion = 0L,
                    continuationExpectedVersion = 0L,
                    presentedToken = exception.challenge.token,
                    resumedBy = resumedBy,
                )
            )
        }

        assertThat(result).isNotNull
    }

    @Test
    fun `second resume on same approvalId fails`() {
        val engine = createEngine()
        val exception = triggerSuspension(engine)
        policyEngine.resumeDecision = PolicyDecision.Allow

        val command = ResumeApprovalCommand(
            approvalId = exception.approvalId,
            approvalExpectedVersion = 0L,
            continuationExpectedVersion = 0L,
            presentedToken = exception.challenge.token,
            resumedBy = resumedBy,
        )

        // First resume succeeds
        runBlocking { engine.resumeApproval(command) }

        // Second resume fails - continuation is already COMPLETED or invocation removed
        assertThatThrownBy {
            runBlocking { engine.resumeApproval(command) }
        }.isInstanceOf(dev.tramai.core.exception.ApprovalNotFoundException::class.java)
    }

    // ── Service Interface ──────────────────────────────────────────

    @AiService
    @SystemPrompt("You are a helpful assistant.")
    private interface SuspensionTriggerService {
        @Operation(
            prompt = "Execute the calculator tool",
            model = "claude-sonnet-4-20250514",
            tools = ["test_calculator"],
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
