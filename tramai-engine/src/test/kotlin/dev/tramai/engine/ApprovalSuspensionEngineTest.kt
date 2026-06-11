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
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.approval.ValidateResumeCommand
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

class ApprovalSuspensionEngineTest {

    private val fixedClock = Clock.fixed(
        Instant.parse("2026-06-07T12:00:00Z"),
        ZoneId.of("UTC"),
    )
    private val toolName = "test_lookup"
    private val toolArguments = """{"query":"test"}"""
    private val toolCallId = "call-1"

    /** Fake ApprovalGateCoordinator that generates deterministic approval IDs. */
    private class FakeApprovalGateCoordinator : ApprovalGateCoordinator {
        var lastCreateCommand: CreateApprovalCommand? = null
        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
            lastCreateCommand = command
            val approvalId = UUID.randomUUID().toString()
            return ApprovalChallenge(
                approvalId = approvalId,
                token = ApprovalToken.parsePresented("token-$approvalId"),
                expiresAt = command.expiresAt,
            )
        }

        override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
            error("not expected in suspension test")
        }

        override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation {
            error("not expected in suspension test")
        }

        override suspend fun cancelApproval(
            approvalId: String,
            expectedVersion: Long,
            reason: String,
        ) = Unit
    }

    /** Fake PolicyEngine that returns RequireApproval for BEFORE_TOOL_EXECUTION, Allow otherwise. */
    private class SuspensionPolicyEngine(
        private val decisionForTool: PolicyDecision = PolicyDecision.RequireApproval(
            ApprovalRequirement(
                toolName = "test_lookup",
                argumentsDigest = "sha256:32dbb74c5960541dfc053509d44ed8b8e0471ad33d4bd7c6ab5ef8c568f45bd6",
                reason = "testing",
                timeoutMillis = 60_000,
            ),
        ),
    ) : PolicyEngine {
        val evaluatedContexts = mutableListOf<PolicyContext>()

        override suspend fun evaluate(context: PolicyContext): PolicyDecision {
            evaluatedContexts.add(context)
            return if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                decisionForTool
            } else {
                PolicyDecision.Allow
            }
        }
    }

    /** A simple tool that records invocations. */
    private class RecordingTool(
        override val name: String = "test_lookup",
        override val description: String = "Test lookup tool",
        override val inputSchemaJson: String = """{"type":"object","properties":{"query":{"type":"string"}}}""",
        override val idempotent: Boolean = false,
        override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY,
        private val result: ToolResult = ToolResult.Success("""{"value":"resolved"}"""),
    ) : ResolvedTool {
        val invocations = mutableListOf<Pair<Any, ToolExecutionContext>>()

        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            invocations.add(Pair(input, context))
            return result
        }
    }

    private val tool = RecordingTool()
    private val toolRegistry = ToolRegistry(mapOf(tool.name to tool))
    private val coordinator = FakeApprovalGateCoordinator()
    private val continuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
    private val digester = Sha256ToolArgumentsDigester()
    private val suspendedInvocationStore = InMemorySuspendedInvocationStore()
    private val policyEngine = SuspensionPolicyEngine()
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

    @Test
    fun `executeTool creates approval challenge when BEFORE_TOOL_EXECUTION returns RequireApproval`() {
        val engine = createEngine()
        val service = engine.create<SuspensionTestService>()

        assertThatThrownBy {
            runBlocking { service.execute("test input") }
        }.isInstanceOf(ApprovalSuspendedException::class.java)

        // Verify approval challenge was created
        val createCommand = coordinator.lastCreateCommand
        assertThat(createCommand).isNotNull
        assertThat(createCommand!!.toolName).isEqualTo(toolName)
    }

    @Test
    fun `executeTool derives approval binding when policy requirement omits arguments digest`() {
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = SuspensionPolicyEngine(
                PolicyDecision.RequireApproval(
                    ApprovalRequirement(
                        toolName = toolName,
                        argumentsDigest = "",
                        reason = "testing",
                        timeoutMillis = 60_000,
                    ),
                ),
            ),
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
        )
        val service = engine.create<SuspensionTestService>()

        val exception = try {
            runBlocking { service.execute("test input") }
            fail("Should have thrown")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.argumentsDigest)
            .isEqualTo(digester.digest(SensitiveToolArguments.of(toolArguments)))
    }

    @Test
    fun `executeTool creates continuation in store with PENDING status`() {
        val engine = createEngine()
        val service = engine.create<SuspensionTestService>()

        val exception = try {
            runBlocking { service.execute("test input") }
            fail("Should have thrown")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)
        assertThat(continuation.approvalId).isEqualTo(exception.approvalId)
        assertThat(continuation.toolName).isEqualTo(toolName)
        assertThat(continuation.toolCallId).isEqualTo(toolCallId)
    }

    @Test
    fun `executeTool stores SuspendedInvocation in the store`() {
        val engine = createEngine()
        val service = engine.create<SuspensionTestService>()

        val exception = try {
            runBlocking { service.execute("test input") }
            fail("Should have thrown")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        val suspended = runBlocking { suspendedInvocationStore.get(exception.approvalId) }
        assertThat(suspended).isNotNull
        assertThat(suspended!!.approvalId).isEqualTo(exception.approvalId)
        assertThat(suspended.toolName).isEqualTo(toolName)
        assertThat(suspended.toolCallId).isEqualTo(toolCallId)
        // P1-4: Verify metadata fields are stored
        assertThat(suspended.conversationId).isNull()
        assertThat(suspended.historySize).isEqualTo(0)
        // tokenBudgetSnapshot is always non-null (tracker always created with default empty settings)
        assertThat(suspended.tokenBudgetSnapshot).isNotNull
        assertThat(suspended.tokenBudgetSnapshot!!.totalInputTokens).isEqualTo(0)
    }

    @Test
    fun `executeTool throws ApprovalSuspendedException`() {
        val engine = createEngine()
        val service = engine.create<SuspensionTestService>()

        assertThatThrownBy {
            runBlocking { service.execute("test input") }
        }.isInstanceOf(ApprovalSuspendedException::class.java)
    }

    @Test
    fun `exception contains approvalId workflowRunId toolCallId toolName continuationVersion`() {
        val engine = createEngine()
        val service = engine.create<SuspensionTestService>()

        val exception = try {
            runBlocking { service.execute("test input") }
            fail("Should have thrown")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        assertThat(exception.approvalId).isNotBlank()
        assertThat(exception.workflowRunId).isNotBlank()
        assertThat(exception.toolCallId).isEqualTo(toolCallId)
        assertThat(exception.toolName).isEqualTo(toolName)
        assertThat(exception.continuationVersion).isGreaterThanOrEqualTo(0)
    }

    @Test
    fun `exception does not contain raw arguments or tokens in message`() {
        val engine = createEngine()
        val service = engine.create<SuspensionTestService>()

        val exception = try {
            runBlocking { service.execute("test input") }
            fail("Should have thrown")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        val message = exception.message ?: ""
        assertThat(message).doesNotContain(toolArguments)
        assertThat(message).doesNotContain("\"query\"")
        // The token should be [REDACTED] in toString, but not appear in the exception message
        assertThat(message).doesNotContain("token-")
    }

    @Test
    fun `exception challenge has approvalId and expiry`() {
        val engine = createEngine()
        val service = engine.create<SuspensionTestService>()

        val exception = try {
            runBlocking { service.execute("test input") }
            fail("Should have thrown")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        assertThat(exception.challenge.approvalId).isEqualTo(exception.approvalId)
        assertThat(exception.challenge.expiresAt).isAfter(fixedClock.instant())
    }

    @Test
    fun `continuation status remains PENDING after suspension`() {
        val engine = createEngine()
        val service = engine.create<SuspensionTestService>()

        val exception = try {
            runBlocking { service.execute("test input") }
            fail("Should have thrown")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)
        assertThat(continuation.claimedBy).isNull()
        assertThat(continuation.claimedAt).isNull()
        assertThat(continuation.completedAt).isNull()
    }

    @Test
    fun `tool is NOT executed when suspension occurs`() {
        val engine = createEngine()
        val service = engine.create<SuspensionTestService>()

        assertThatThrownBy {
            runBlocking { service.execute("test input") }
        }.isInstanceOf(ApprovalSuspendedException::class.java)

        assertThat(tool.invocations).isEmpty()
    }

    @Test
    fun `token budget snapshot is stored in suspended invocation metadata`() {
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
            tokenBudgetSettings = TokenBudgetSettings(
                hardMaxTokensPerOperation = 10_000L,
            ),
        )
        val service = engine.create<SuspensionTestService>()

        val exception = try {
            runBlocking { service.execute("test input") }
            fail("Should have thrown")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        val suspended = runBlocking { suspendedInvocationStore.get(exception.approvalId) }
        assertThat(suspended).isNotNull
        // Verify tokenBudgetSnapshot was captured
        assertThat(suspended!!.tokenBudgetSnapshot).isNotNull
        assertThat(suspended.tokenBudgetSnapshot!!.totalInputTokens).isGreaterThanOrEqualTo(0)
    }

    // ── Service Interface ──────────────────────────────────────────

    @AiService
    @SystemPrompt("You are a helpful assistant.")
    private interface SuspensionTestService {
        @Operation(
            prompt = "Execute the test_lookup tool",
            model = "claude-sonnet-4-20250514",
            tools = ["test_lookup"],
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
