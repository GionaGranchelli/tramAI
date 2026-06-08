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
import dev.tramai.core.structured.StructuredOutputResult
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
                        argumentsDigest = "sha256:12e49c0f5b1f1c5a753a1e98fb8e94a06c58b35c8432b77270d412d5d295e3b9",
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
    /**
     * Tracks provider call count so the first call triggers suspension (via tool calls)
     * and subsequent calls during resume return a final response without tool calls.
     */
    private var providerCallCount = 0

    private val provider = RecordingProvider { _ ->
        providerCallCount++
        if (providerCallCount == 1) {
            // First call: return tool calls to trigger suspension
            ModelResponse(
                content = "",
                toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
            )
        } else {
            // Subsequent calls during resume: return a final content-only response
            ModelResponse(content = "Final result: success")
        }
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

    // ════════════════════════════════════════════════════════════════
    // P0-1: Structured output resume tests
    // ════════════════════════════════════════════════════════════════

    data class StatusResult(val success: Boolean, val message: String)

    @AiService
    @SystemPrompt("You are a helpful assistant.")
    private interface StructuredResumeTriggerService {
        @Operation(
            prompt = "Execute the structured tool",
            model = "claude-sonnet-4-20250514",
            tools = ["test_calculator"],
        )
        suspend fun execute(input: String): StatusResult
    }

    /** Fake StructuredOutputHandler that returns a configurable result. */
    private class FakeStructuredOutputHandler(
        private val parseResult: StructuredOutputResult,
    ) : dev.tramai.core.structured.StructuredOutputHandler {
        override fun createContract(targetType: kotlin.reflect.KType): dev.tramai.core.structured.StructuredOutputContract {
            return dev.tramai.core.structured.StructuredOutputContract(
                schemaJson = "{}",
                targetType = targetType,
            )
        }
        override fun analyze(
            rawResponse: String,
            targetType: kotlin.reflect.KType,
        ): StructuredOutputResult = parseResult

        override fun generateSchema(type: kotlin.reflect.KType): String = "{}"
        override fun deserialize(input: Any, targetType: kotlin.reflect.KType): Any = input
        override fun serialize(value: Any): Any = value
    }

    @Test
    fun `resumeApproval structured happy path parses and returns typed result`() {
        val structuredHandler = FakeStructuredOutputHandler(
            StructuredOutputResult.Success(
                value = StatusResult(success = true, message = "done"),
                rawResponse = """{"success":true,"message":"done"}""",
            ),
        )
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
            structuredOutputHandler = structuredHandler,
        )

        // Trigger suspension using the StructuredResumeTriggerService (StatusResult return type)
        val structuredService = engine.create<StructuredResumeTriggerService>()
        val exception = try {
            runBlocking { structuredService.execute("test") }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        }

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

        // The fake handler always returns our StatusResult, so we should get it back
        assertThat(result).isInstanceOf(StatusResult::class.java)
        val typed = result as StatusResult
        assertThat(typed.success).isTrue
        assertThat(typed.message).isEqualTo("done")
    }

    @Test
    fun `resumeApproval structured parse failure throws StructuredOutputException`() {
        val structuredHandler = FakeStructuredOutputHandler(
            StructuredOutputResult.Failure(
                rawResponse = "unparseable garbage",
                errorSummary = "failed to parse",
                feedbackMessage = "Please provide a valid JSON response",
            ),
        )
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
            structuredOutputHandler = structuredHandler,
        )

        // Trigger suspension using the StructuredResumeTriggerService
        val structuredService = engine.create<StructuredResumeTriggerService>()
        val exception = try {
            runBlocking { structuredService.execute("test") }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        policyEngine.resumeDecision = PolicyDecision.Allow

        assertThatThrownBy {
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
        }.isInstanceOf(dev.tramai.core.exception.StructuredOutputException::class.java)
    }

    @Test
    fun `resumeApproval structured without handler throws ConfigurationException`() {
        // First, create a working engine with a handler to perform the suspension
        val workingHandler = FakeStructuredOutputHandler(
            StructuredOutputResult.Success(
                value = StatusResult(success = true, message = "done"),
                rawResponse = """{"success":true,"message":"done"}""",
            ),
        )
        val workingEngine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
            structuredOutputHandler = workingHandler,
        )

        // Trigger suspension using the working engine
        val structuredService = workingEngine.create<StructuredResumeTriggerService>()
        val exception = try {
            runBlocking { structuredService.execute("test") }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        policyEngine.resumeDecision = PolicyDecision.Allow

        // Now create a separate engine without structuredOutputHandler for resume
        // This should throw ConfigurationException when trying to resume a structured operation
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
            // No structuredOutputHandler — will cause ConfigurationException on resume
        )

        assertThatThrownBy {
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
        }.isInstanceOf(dev.tramai.core.exception.ConfigurationException::class.java)
    }

    // ════════════════════════════════════════════════════════════════
    // P1-6: Multi-tool batch index test
    // ════════════════════════════════════════════════════════════════

    private inner class MultiToolProvider : dev.tramai.core.provider.ModelProvider {
        val requests = mutableListOf<ModelRequest>()
        var callCount = 0

        override suspend fun complete(request: ModelRequest): ModelResponse {
            requests += request
            callCount++
            return if (callCount == 1) {
                // First call: return 2 tool calls — only the first triggers approval suspension
                // First tool uses same args/digest as SelectivePolicyEngine expects
                ModelResponse(
                    content = "",
                    toolCalls = listOf(
                        ToolCall("call-multi-0", "test_calculator", toolArguments),
                        ToolCall("call-multi-1", "test_calculator", """{"x":3,"y":4}"""),
                    ),
                )
            } else {
                // Subsequent calls during resume: return final content
                ModelResponse(content = "Multi-tool resume complete")
            }
        }
    }

    private class MultiToolPolicyEngine : PolicyEngine {
        var firstCall = true
        override suspend fun evaluate(context: PolicyContext): PolicyDecision {
            if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION && firstCall) {
                firstCall = false
                return PolicyDecision.RequireApproval(
                    ApprovalRequirement(
                        toolName = "test_calculator",
                        argumentsDigest = "sha256:12e49c0f5b1f1c5a753a1e98fb8e94a06c58b35c8432b77270d412d5d295e3b9",
                        reason = "testing",
                        timeoutMillis = 60_000,
                    )
                )
            }
            return PolicyDecision.Allow
        }
    }

    @Test
    fun `resumeApproval executes sibling tools after resumed tool in batch`() {
        val mtTool = RecordingTool(name = "test_calculator")
        val mtToolRegistry = ToolRegistry(mapOf(mtTool.name to mtTool))
        val mtPolicyEngine = MultiToolPolicyEngine()
        val mtProvider = MultiToolProvider()
        val mtCoordinator = PermitApprovalGateCoordinator()

        val engine = TramaiEngine(
            provider = mtProvider,
            toolRegistry = mtToolRegistry,
            policyEngine = mtPolicyEngine,
            suspendedInvocationStore = InMemorySuspendedInvocationStore(),
            approvalContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock),
            toolArgumentsDigester = digester,
            approvalGateCoordinator = mtCoordinator,
            clock = fixedClock,
        )

        // Trigger suspension — first tool call (index 0) triggers approval
        val service = engine.create<SuspensionTriggerService>()
        val exception = try {
            runBlocking { service.execute("test") }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        // Resume: the suspended tool (index 0) will execute, then sibling (index 1) should also execute
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

        // Both tools should have been invoked
        assertThat(mtTool.invocations).hasSize(2)
        val (_, ctx0) = mtTool.invocations[0]
        val (_, ctx1) = mtTool.invocations[1]
        assertThat(ctx0.operationName).isNotBlank()
        assertThat(ctx1.operationName).isNotBlank()
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
