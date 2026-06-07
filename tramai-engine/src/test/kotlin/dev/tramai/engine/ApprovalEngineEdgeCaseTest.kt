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
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.engine.ToolRegistry
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.fail
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test

class ApprovalEngineEdgeCaseTest {

    private val fixedClock = Clock.fixed(
        Instant.parse("2026-06-07T12:00:00Z"),
        ZoneId.of("UTC"),
    )
    private val toolName = "test_edge"
    private val toolArguments = """{"key":"value"}"""
    private val toolCallId = "call-edge-1"

    /** Fake ApprovalGateCoordinator that can be configured to fail and tracks last approval. */
    private class ConfigurableApprovalGateCoordinator : ApprovalGateCoordinator {
        var failCreate: Boolean = false
        var failAuthorize: Boolean = false
        var lastCreateCommand: CreateApprovalCommand? = null
        var lastAuthorizeCommand: AuthorizeResumeCommand? = null
        var lastCreatedApprovalId: String? = null

        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
            lastCreateCommand = command
            if (failCreate) throw RuntimeException("coordinator create failed")
            val id = UUID.randomUUID().toString()
            val challenge = ApprovalChallenge(
                approvalId = id,
                token = ApprovalToken.parsePresented("token-$id"),
                expiresAt = command.expiresAt,
            )
            lastCreatedApprovalId = challenge.approvalId
            return challenge
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

    /** Fake PolicyEngine that returns RequireApproval for tool execution, configurable for resume. */
    private class EdgeCasePolicyEngine : PolicyEngine {
        var workflowResumeDecision: PolicyDecision = PolicyDecision.Allow
        var toolExecDecision: PolicyDecision = PolicyDecision.RequireApproval(
            ApprovalRequirement(
                toolName = "test_edge",
                argumentsDigest = "sha256:e43abcf3375244839c012f9633f95862d232a95b00d5bc7348b3098b9fed7f32",
                reason = "testing",
                timeoutMillis = 60_000,
            ),
        )

        override suspend fun evaluate(context: dev.tramai.core.policy.PolicyContext): PolicyDecision {
            return when (context.enforcementPoint) {
                dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_EXECUTION -> toolExecDecision
                dev.tramai.core.policy.EnforcementPoint.BEFORE_WORKFLOW_RESUME -> workflowResumeDecision
                else -> PolicyDecision.Allow
            }
        }
    }

    /** A tool that records invocations. */
    private class RecordingTool(
        override val name: String = "test_edge",
        override val description: String = "Edge case tool",
        override val inputSchemaJson: String = """{"type":"object","properties":{"key":{"type":"string"}}}""",
        override val idempotent: Boolean = false,
        override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY,
        private val result: ToolResult = ToolResult.Success("""{"result":"done"}"""),
    ) : ResolvedTool {
        val invocations = mutableListOf<Pair<Any, ToolExecutionContext>>()

        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            invocations.add(Pair(input, context))
            return result
        }
    }

    private val tool = RecordingTool()
    private val toolRegistry = ToolRegistry(mapOf(tool.name to tool))
    private val coordinator = ConfigurableApprovalGateCoordinator()
    private val continuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
    private val digester = Sha256ToolArgumentsDigester()
    private val suspendedInvocationStore = InMemorySuspendedInvocationStore()
    private val policyEngine = EdgeCasePolicyEngine()
    private val provider = object : dev.tramai.core.provider.ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse {
            return ModelResponse(
                content = "",
                toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
            )
        }
        override fun providerId(): String = "test"
    }

    @Test
    fun `BEFORE_WORKFLOW_RESUME deny prevents tool execution and cancels continuation`() {
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
        )

        val exception = triggerSuspension(engine)
        policyEngine.workflowResumeDecision = PolicyDecision.Deny(
            reason = "resume denied",
            reasonCode = "TOOL_NOT_ALLOWED",
        )

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
        }.isInstanceOf(dev.tramai.core.exception.PolicyViolationException::class.java)

        // Tool was never invoked
        assertThat(tool.invocations).isEmpty()

        // Continuation is CANCELLED and payload scrubbed
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CANCELLED)
    }

    @Test
    fun `nonexistent approvalId on resume throws appropriate exception`() {
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
        )

        // Create a service proxy to set the resumeHandler
        engine.create<TriggerService>()

        assertThatThrownBy {
            runBlocking {
                engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = "nonexistent-id",
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = ApprovalToken.parsePresented("fake-token"),
                        resumedBy = "admin",
                    )
                )
            }
        }.isInstanceOf(dev.tramai.core.exception.ApprovalNotFoundException::class.java)
    }

    @Test
    fun `claim succeeds but tool fails - continuation stays CLAIMED`() {
        val failingTool = object : ResolvedTool {
            override val name: String = "test_edge"
            override val description: String = "Failing tool"
            override val inputSchemaJson: String = """{"type":"object"}"""
            override val idempotent: Boolean = false
            override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY
            val invocations = mutableListOf<Pair<Any, ToolExecutionContext>>()

            override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
                invocations.add(Pair(input, context))
                throw RuntimeException("tool execution failed")
            }
        }
        val failingToolRegistry = ToolRegistry(mapOf(failingTool.name to failingTool))
        val failingProvider = object : dev.tramai.core.provider.ModelProvider {
                override suspend fun complete(request: ModelRequest): ModelResponse {
                    return ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                }
                override fun providerId(): String = "test"
            }

        val engine = TramaiEngine(
            provider = failingProvider,
            toolRegistry = failingToolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
        )

        val exception = triggerSuspension(engine)
        policyEngine.workflowResumeDecision = PolicyDecision.Allow

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

        // Tool was invoked once (claimed completed)
        assertThat(failingTool.invocations).hasSize(1)

        // Continuation stays CLAIMED (not COMPLETED)
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
    }

    @Test
    fun `suspension store failure cancels continuation and scrubs payload`() {
        val fragileStore = object : SuspendedInvocationStore {
            override suspend fun create(
                metadata: SuspendedInvocationMetadata,
                sensitiveContext: SensitiveResumeContext,
            ) {
                throw RuntimeException("simulated suspension store failure")
            }
            override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = null
            override suspend fun revealSensitiveContext(approvalId: String): SensitiveResumeContext? = null
            override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? = null
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

        val service = engine.create<TriggerService>()

        assertThatThrownBy {
            runBlocking { service.execute("input") }
        }.isInstanceOf(RuntimeException::class.java)

        // The continuation was created before the fragile store was called
        // Compensation should have cancelled it
        val approvalId = coordinator.lastCreatedApprovalId
        assertThat(approvalId).isNotNull

        val continuation = runBlocking { continuationStore.get(approvalId!!) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CANCELLED)
        assertThat(continuation.claimedBy).isNull()
        assertThat(continuation.claimedAt).isNull()
        assertThat(continuation.completedAt).isNull()
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun triggerSuspension(engine: TramaiEngine): ApprovalSuspendedException {
        val service = engine.create<TriggerService>()
        return try {
            runBlocking { service.execute("input") }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        } catch (e: Exception) {
            fail("Unexpected exception type: ${e::class.simpleName}: ${e.message}")
        }
    }

    // ── Service Interface ──────────────────────────────────────────

    @AiService
    @SystemPrompt("You are a helpful assistant.")
    private interface TriggerService {
        @Operation(
            prompt = "Execute the edge case tool",
            model = "claude-sonnet-4-20250514",
            tools = ["test_edge"],
        )
        suspend fun execute(input: String): String
    }
}
