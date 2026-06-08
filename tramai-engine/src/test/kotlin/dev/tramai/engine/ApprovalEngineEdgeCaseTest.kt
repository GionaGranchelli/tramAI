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
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ValidateResumeCommand
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
        var failValidate: Boolean = false
        var lastCreateCommand: CreateApprovalCommand? = null
        var lastValidateCommand: ValidateResumeCommand? = null
        var lastAuthorizeCommand: AuthorizeResumeCommand? = null
        var lastCreatedApprovalId: String? = null
        var lastCancelledApprovalId: String? = null

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

        override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation {
            lastValidateCommand = command
            if (failValidate) throw RuntimeException("coordinator validate failed")
            return ApprovalValidation(
                approvalId = command.approvalId,
                validatedBy = command.consumedBy,
                validatedAt = Clock.systemUTC().instant(),
                version = command.expectedVersion,
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

        override suspend fun cancelApproval(
            approvalId: String,
            expectedVersion: Long,
            reason: String,
        ) {
            lastCancelledApprovalId = approvalId
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

        // P1-5: Verify cancelApproval was called on the coordinator as part of saga compensation
        assertThat(coordinator.lastCancelledApprovalId).isEqualTo(approvalId)
    }

    // ════════════════════════════════════════════════════════════════
    // P1-2: Provider loop failure test during resume
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `provider failure after resumed tool execution keeps continuation CLAIMED`() {
        var providerCallCount = 0
        val failOnSecondCallProvider = object : dev.tramai.core.provider.ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                providerCallCount++
                if (providerCallCount == 1) {
                    return ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                }
                throw RuntimeException("provider failed on second call")
            }
            override fun providerId(): String = "test"
        }

        val auditEvents = mutableListOf<String>()
        val auditEmitter = object : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter by dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter {
            override suspend fun onToolExecutionSuspended(
                approvalId: String, workflowRunId: String, toolName: String,
                toolCallId: String, correlationId: String,
                argumentsDigest: dev.tramai.core.approval.Sha256Digest,
                expiresAt: java.time.Instant,
            ) = Unit
            override suspend fun onToolExecutionResumed(
                approvalId: String, workflowRunId: String,
                toolName: String, resumedBy: String,
            ) = Unit
            override suspend fun onToolExecutionCompleted(
                approvalId: String, workflowRunId: String,
                toolName: String, completedBy: String,
            ) = Unit
            override suspend fun onUncertainOutcome(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) { auditEvents.add("uncertain:$reason") }
            override suspend fun onSuspensionCancelled(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) = Unit
        }

        val engine = TramaiEngine(
            provider = failOnSecondCallProvider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
            approvalLifecycleAuditEmitter = auditEmitter,
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

        // Continuation stays CLAIMED (not COMPLETED)
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)

        // onUncertainOutcome was emitted
        assertThat(auditEvents.any { it.startsWith("uncertain:") }).isTrue
    }

    // ════════════════════════════════════════════════════════════════
    // P1-3: Payload integrity mismatch test
    // ════════════════════════════════════════════════════════════════

    /**
     * Wraps an ApprovalContinuationStore to return modified arguments on claim,
     * triggering the digest integrity check.
     */
    private class TamperingContinuationStoreWrapper(
        private val delegate: InMemoryApprovalContinuationStore,
    ) : dev.tramai.core.approval.ApprovalContinuationStore by delegate {
        override suspend fun claimForExecution(
            approvalId: String,
            expectedVersion: Long,
            claimedBy: String,
        ): dev.tramai.core.approval.ClaimedApprovalContinuation {
            val claimed = delegate.claimForExecution(approvalId, expectedVersion, claimedBy)
            // Return with modified arguments that won't match the stored digest
            val tampered = dev.tramai.core.approval.SensitiveToolArguments.of("""{"key":"tampered"}""")
            return dev.tramai.core.approval.ClaimedApprovalContinuation(
                continuation = claimed.continuation,
                arguments = tampered,
            )
        }
    }

    @Test
    fun `payload integrity mismatch on resume throws ConfigurationException and keeps CLAIMED`() {
        val tamperingStore = TamperingContinuationStoreWrapper(continuationStore)
        val auditEvents = mutableListOf<String>()
        val auditEmitter = object : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter by dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter {
            override suspend fun onToolExecutionSuspended(
                approvalId: String, workflowRunId: String, toolName: String,
                toolCallId: String, correlationId: String,
                argumentsDigest: dev.tramai.core.approval.Sha256Digest,
                expiresAt: java.time.Instant,
            ) = Unit
            override suspend fun onToolExecutionResumed(
                approvalId: String, workflowRunId: String,
                toolName: String, resumedBy: String,
            ) = Unit
            override suspend fun onToolExecutionCompleted(
                approvalId: String, workflowRunId: String,
                toolName: String, completedBy: String,
            ) = Unit
            override suspend fun onUncertainOutcome(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) { auditEvents.add("uncertain:$reason") }
            override suspend fun onSuspensionCancelled(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) = Unit
        }

        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = tamperingStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
            approvalLifecycleAuditEmitter = auditEmitter,
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
        }.isInstanceOf(dev.tramai.core.exception.ConfigurationException::class.java)
            .hasMessageContaining("integrity")

        // Continuation stays CLAIMED
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)

        // onUncertainOutcome was emitted with payload-integrity-mismatch
        assertThat(auditEvents.any { it.contains("payload-integrity-mismatch") }).isTrue
    }

    // ════════════════════════════════════════════════════════════════
    // P1-4: Recursive approval test
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `BEFORE_WORKFLOW_RESUME RequireApproval throws ConfigurationException with recursive-approval-not-supported`() {
        val auditEvents = mutableListOf<String>()
        val auditEmitter = object : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter by dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter {
            override suspend fun onToolExecutionSuspended(
                approvalId: String, workflowRunId: String, toolName: String,
                toolCallId: String, correlationId: String,
                argumentsDigest: dev.tramai.core.approval.Sha256Digest,
                expiresAt: java.time.Instant,
            ) = Unit
            override suspend fun onToolExecutionResumed(
                approvalId: String, workflowRunId: String,
                toolName: String, resumedBy: String,
            ) = Unit
            override suspend fun onToolExecutionCompleted(
                approvalId: String, workflowRunId: String,
                toolName: String, completedBy: String,
            ) = Unit
            override suspend fun onUncertainOutcome(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) = Unit
            override suspend fun onSuspensionCancelled(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) { auditEvents.add("cancelled:$reason") }
        }

        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
            approvalLifecycleAuditEmitter = auditEmitter,
        )

        val exception = triggerSuspension(engine)
        // BEFORE_WORKFLOW_RESUME returns RequireApproval → recursive approval
        policyEngine.workflowResumeDecision = PolicyDecision.RequireApproval(
            ApprovalRequirement(
                toolName = toolName,
                argumentsDigest = "sha256:e43abcf3375244839c012f9633f95862d232a95b00d5bc7348b3098b9fed7f32",
                reason = "recursive",
                timeoutMillis = 60_000,
            ),
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
        }.isInstanceOf(dev.tramai.core.exception.NestedApprovalNotSupportedException::class.java)
            .hasMessage("Nested approval not supported: use the original approval challenge")

        // Continuation is CANCELLED
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CANCELLED)

        // onSuspensionCancelled audit emitted with nested-approval-not-supported reason
        assertThat(auditEvents.any { it.contains("nested-approval-not-supported") }).isTrue
    }

    // ════════════════════════════════════════════════════════════════
    // Fix 4: Missing sensitive context after claim
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `missing sensitive context after claim emits uncertain outcome once`() {
        val auditEvents = mutableListOf<String>()
        val auditEmitter = object : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter by dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter {
            override suspend fun onToolExecutionSuspended(
                approvalId: String, workflowRunId: String, toolName: String,
                toolCallId: String, correlationId: String,
                argumentsDigest: dev.tramai.core.approval.Sha256Digest,
                expiresAt: java.time.Instant,
            ) = Unit
            override suspend fun onToolExecutionResumed(
                approvalId: String, workflowRunId: String,
                toolName: String, resumedBy: String,
            ) = Unit
            override suspend fun onToolExecutionCompleted(
                approvalId: String, workflowRunId: String,
                toolName: String, completedBy: String,
            ) = Unit
            override suspend fun onUncertainOutcome(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) { auditEvents.add("uncertain:$reason") }
            override suspend fun onSuspensionCancelled(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) = Unit
        }

        // Store that returns null from revealSensitiveContext after a successful create
        val fragileStore = object : SuspendedInvocationStore {
            private var created = false
            override suspend fun create(
                metadata: SuspendedInvocationMetadata,
                sensitiveContext: SensitiveResumeContext,
            ) {
                created = true
                suspendedInvocationStore.create(metadata, sensitiveContext)
            }
            override suspend fun get(approvalId: String): SuspendedInvocationMetadata? =
                suspendedInvocationStore.get(approvalId).also {
                    if (created && it != null) {
                        // After creation, return the metadata normally for get()
                    }
                }
            override suspend fun revealSensitiveContext(approvalId: String): SensitiveResumeContext? {
                // Always return null to simulate missing sensitive context after claim
                return null
            }
            override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? =
                suspendedInvocationStore.remove(approvalId)
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
            approvalLifecycleAuditEmitter = auditEmitter,
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
        }.isInstanceOf(dev.tramai.core.exception.ConfigurationException::class.java)
            .hasMessageContaining("Sensitive resume context not found")

        // Continuation stays CLAIMED
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)

        // onUncertainOutcome was emitted exactly once
        assertThat(auditEvents).hasSize(1)
        assertThat(auditEvents.single()).startsWith("uncertain:")
    }

    // ════════════════════════════════════════════════════════════════
    // Fix 5: Security-ordering regression tests
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `invalid token cannot cancel or scrub continuation`() {
        val auditEvents = mutableListOf<String>()
        val auditEmitter = object : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter by dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter {
            override suspend fun onToolExecutionSuspended(
                approvalId: String, workflowRunId: String, toolName: String,
                toolCallId: String, correlationId: String,
                argumentsDigest: dev.tramai.core.approval.Sha256Digest,
                expiresAt: java.time.Instant,
            ) = Unit
            override suspend fun onToolExecutionResumed(
                approvalId: String, workflowRunId: String,
                toolName: String, resumedBy: String,
            ) = Unit
            override suspend fun onToolExecutionCompleted(
                approvalId: String, workflowRunId: String,
                toolName: String, completedBy: String,
            ) = Unit
            override suspend fun onUncertainOutcome(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) { auditEvents.add("uncertain:$reason") }
            override suspend fun onSuspensionCancelled(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) { auditEvents.add("cancelled:$reason") }
        }

        // Coordinator that throws on invalid token — simulates a real invalid token
        val rejectingCoordinator = object : ApprovalGateCoordinator {
            override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
                val id = UUID.randomUUID().toString()
                return ApprovalChallenge(
                    approvalId = id,
                    token = ApprovalToken.parsePresented("token-$id"),
                    expiresAt = command.expiresAt,
                )
            }
            override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation {
                throw dev.tramai.core.exception.ApprovalTokenRejectedException(
                    approvalId = command.approvalId,
                )
            }
            override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
                throw dev.tramai.core.exception.ApprovalTokenRejectedException(
                    approvalId = command.approvalId,
                )
            }
            override suspend fun cancelApproval(
                approvalId: String, expectedVersion: Long, reason: String,
            ) { auditEvents.add("coordinator-cancelled:$approvalId") }
        }

        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = rejectingCoordinator,
            clock = fixedClock,
            approvalLifecycleAuditEmitter = auditEmitter,
        )

        val exception = triggerSuspension(engine)
        policyEngine.workflowResumeDecision = PolicyDecision.Allow

        // Capture continuation state before resume attempt
        val preContinuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(preContinuation).isNotNull
        assertThat(preContinuation!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)

        assertThatThrownBy {
            runBlocking {
                engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = exception.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 0L,
                        presentedToken = ApprovalToken.parsePresented("invalid-token"),
                        resumedBy = "admin",
                    )
                )
            }
        }.isInstanceOf(dev.tramai.core.exception.ApprovalTokenRejectedException::class.java)

        // Continuation was NOT cancelled (still PENDING)
        val postContinuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(postContinuation).isNotNull
        assertThat(postContinuation!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)

        // Suspended invocation was NOT removed
        val metadata = runBlocking { suspendedInvocationStore.get(exception.approvalId) }
        assertThat(metadata).isNotNull

        // No cancellation audit was emitted
        assertThat(auditEvents.none { it.startsWith("cancelled:") }).isTrue
    }

    @Test
    fun `cancellation conflict preserves suspended context`() {
        val auditEvents = mutableListOf<String>()
        val auditEmitter = object : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter by dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter {
            override suspend fun onToolExecutionSuspended(
                approvalId: String, workflowRunId: String, toolName: String,
                toolCallId: String, correlationId: String,
                argumentsDigest: dev.tramai.core.approval.Sha256Digest,
                expiresAt: java.time.Instant,
            ) = Unit
            override suspend fun onToolExecutionResumed(
                approvalId: String, workflowRunId: String,
                toolName: String, resumedBy: String,
            ) = Unit
            override suspend fun onToolExecutionCompleted(
                approvalId: String, workflowRunId: String,
                toolName: String, completedBy: String,
            ) = Unit
            override suspend fun onUncertainOutcome(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) { auditEvents.add("uncertain:$reason") }
            override suspend fun onSuspensionCancelled(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) { auditEvents.add("cancelled:$reason") }
        }

        // Continuation store that throws on cancel (stale version conflict)
        val fragileContinuationStore = object : dev.tramai.core.approval.ApprovalContinuationStore {
            private val delegate = InMemoryApprovalContinuationStore(clock = fixedClock)
            override suspend fun create(continuation: ApprovalContinuation, arguments: dev.tramai.core.approval.SensitiveToolArguments): ApprovalContinuation =
                delegate.create(continuation, arguments)
            override suspend fun get(approvalId: String): ApprovalContinuation? = delegate.get(approvalId)
            override suspend fun claimForExecution(approvalId: String, expectedVersion: Long, claimedBy: String): dev.tramai.core.approval.ClaimedApprovalContinuation =
                delegate.claimForExecution(approvalId, expectedVersion, claimedBy)
            override suspend fun complete(approvalId: String, expectedVersion: Long, completedBy: String): ApprovalContinuation =
                delegate.complete(approvalId, expectedVersion, completedBy)
            override suspend fun expire(approvalId: String, expectedVersion: Long): ApprovalContinuation =
                delegate.expire(approvalId, expectedVersion)
            override suspend fun cancel(approvalId: String, expectedVersion: Long): ApprovalContinuation {
                throw dev.tramai.core.exception.ApprovalAuthorizationException(
                    approvalId = approvalId,
                )
            }
            override suspend fun sweepExpired(): Int = delegate.sweepExpired()
            override suspend fun findStaleClaimed(claimedBefore: java.time.Instant, limit: Int): List<ApprovalContinuation> = delegate.findStaleClaimed(claimedBefore, limit)
            override suspend fun forceCancelClaimed(approvalId: String, expectedVersion: Long, cancelledBy: String, reasonCode: String): ApprovalContinuation = delegate.forceCancelClaimed(approvalId, expectedVersion, cancelledBy, reasonCode)
        }

        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = fragileContinuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
            approvalLifecycleAuditEmitter = auditEmitter,
        )

        val exception = triggerSuspension(engine)
        // BEFORE_WORKFLOW_RESUME returns Deny → triggers cancel path
        policyEngine.workflowResumeDecision = PolicyDecision.Deny(
            reason = "denied",
            reasonCode = "POLICY_DENIED",
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
        }.isInstanceOf(dev.tramai.core.exception.ApprovalAuthorizationException::class.java)

        // Suspended context was NOT removed (cancel failed, so remove was never called)
        val metadata = runBlocking { suspendedInvocationStore.get(exception.approvalId) }
        assertThat(metadata).isNotNull

        // No cancellation audit was emitted
        assertThat(auditEvents.none { it.startsWith("cancelled:") }).isTrue
    }

    @Test
    fun `missing digester does not consume token`() {
        // Engine without digester — suspension will fail early because
        // tool execution requires a digester for approval binding validation.
        val coordinator = ConfigurableApprovalGateCoordinator()
        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            // No digester
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
        )

        val service = engine.create<TriggerService>()

        // The initial tool execution should fail with ConfigurationException
        // because the digester is required for approval binding validation.
        assertThatThrownBy {
            runBlocking { service.execute("input") }
        }.isInstanceOf(dev.tramai.core.exception.ConfigurationException::class.java)
            .hasMessageContaining("ToolArgumentsDigester")

        // Coordinator was NEVER called — suspension failed before any approval
        assertThat(coordinator.lastCreateCommand).isNull()
        assertThat(coordinator.lastAuthorizeCommand).isNull()
    }

    @Test
    fun `stale version does not consume token`() {
        val coordinator = ConfigurableApprovalGateCoordinator()
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
        policyEngine.workflowResumeDecision = PolicyDecision.Allow

        // Use wrong continuationExpectedVersion (999 instead of 0)
        assertThatThrownBy {
            runBlocking {
                engine.resumeApproval(
                    ResumeApprovalCommand(
                        approvalId = exception.approvalId,
                        approvalExpectedVersion = 0L,
                        continuationExpectedVersion = 999L,
                        presentedToken = exception.challenge.token,
                        resumedBy = "admin",
                    )
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("version mismatch")

        // Coordinator was NOT called — failure happened before authorizeResume
        assertThat(coordinator.lastAuthorizeCommand).isNull()
    }

    @Test
    fun `post-completion observer failure does not emit uncertain outcome or change result`() {
        val auditEvents = mutableListOf<String>()
        val auditEmitter = object : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter by dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter {
            override suspend fun onToolExecutionSuspended(
                approvalId: String, workflowRunId: String, toolName: String,
                toolCallId: String, correlationId: String,
                argumentsDigest: dev.tramai.core.approval.Sha256Digest,
                expiresAt: java.time.Instant,
            ) = Unit
            override suspend fun onToolExecutionResumed(
                approvalId: String, workflowRunId: String,
                toolName: String, resumedBy: String,
            ) = Unit
            override suspend fun onToolExecutionCompleted(
                approvalId: String, workflowRunId: String,
                toolName: String, completedBy: String,
            ) { throw RuntimeException("audit emitter failed") }
            override suspend fun onUncertainOutcome(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) { auditEvents.add("uncertain:$reason") }
            override suspend fun onSuspensionCancelled(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) = Unit
        }
        val observerEvents = mutableListOf<String>()

        // Provider that returns tool calls on first call, then content on subsequent calls
        var callCount = 0
        val resumingProvider = object : dev.tramai.core.provider.ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                callCount++
                return if (callCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "Final result: success")
                }
            }
            override fun providerId(): String = "test"
        }

        val engine = TramaiEngine(
            provider = resumingProvider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
            approvalLifecycleAuditEmitter = auditEmitter,
            engineEventObserver = EngineEventObserver { name, _ ->
                observerEvents.add(name)
                throw RuntimeException("observer failed")
            },
        )

        val exception = triggerSuspension(engine)
        policyEngine.workflowResumeDecision = PolicyDecision.Allow

        // Resume should succeed — the audit failure during cleanup is caught separately
        val result = runBlocking {
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

        assertThat(result).isEqualTo("Final result: success")

        // Continuation IS COMPLETED (the authoritative transition succeeded)
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)

        // No uncertain outcome was emitted
        assertThat(auditEvents.none { it.startsWith("uncertain:") }).isTrue
        assertThat(observerEvents).containsExactly("resume-cleanup-failure")
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
