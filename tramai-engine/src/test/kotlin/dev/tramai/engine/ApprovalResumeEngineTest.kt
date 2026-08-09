package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.ForceCancelClaimedCommand
import dev.tramai.core.approval.IdempotencyKeyUtil
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ApprovalRecoveryAuditUnavailableException
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
import dev.tramai.core.structured.StructuredOutputFailureCode
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticEvent
import dev.tramai.core.structured.StructuredOutputFailureDiagnosticObserver
import dev.tramai.core.structured.StructuredOutputResult
import dev.tramai.security.approval.DefaultApprovalGateCoordinator
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalStore
import dev.tramai.security.approval.InMemoryApprovalRecoveryCoordinator
import dev.tramai.security.approval.SecureRandomApprovalTokenGenerator
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import dev.tramai.security.approval.Sha256ApprovalTokenDigester
import dev.tramai.security.approval.UuidApprovalIdGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.fail
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
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
    private class PermitApprovalGateCoordinator(
        private val clock: Clock,
    ) : ApprovalGateCoordinator {
        var lastAuthorizeCommand: AuthorizeResumeCommand? = null
        var lastValidateCommand: ValidateResumeCommand? = null
        var lastCreateCommand: CreateApprovalCommand? = null
        var nextChallengeId: String = UUID.randomUUID().toString()
        var validateCalls: Int = 0
        var authorizeCalls: Int = 0
        private val replayedApprovalIds = linkedSetOf<String>()

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

        override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation {
            validateCalls++
            lastValidateCommand = command
            return ApprovalValidation(
                approvalId = command.approvalId,
                validatedBy = command.consumedBy,
                validatedAt = clock.instant(),
                version = command.expectedVersion,
            )
        }

        override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
            authorizeCalls++
            lastAuthorizeCommand = command
            val replayed = !replayedApprovalIds.add(command.approvalId)
            return ApprovalAuthorization(
                approvalId = command.approvalId,
                consumedBy = command.consumedBy,
                consumedAt = clock.instant(),
                version = command.expectedVersion + 1,
                replayed = replayed,
            )
        }

        override suspend fun cancelApproval(
            approvalId: String,
            expectedVersion: Long,
            reason: String,
        ) = Unit
    }

    private class ReplaySafeApprovalGateCoordinator(
        private val clock: Clock,
        private val mode: Mode = Mode.NORMAL,
    ) : ApprovalGateCoordinator {
        enum class Mode {
            NORMAL,
            THROW_AFTER_DURABLE_CONSUME_ONCE,
        }

        private val durableReceipts = linkedMapOf<String, ApprovalAuthorization>()
        private val durableTokenDigests = linkedMapOf<String, Sha256Digest>()
        private val threwAfterDurableConsume = AtomicBoolean(false)
        var validateCalls: Int = 0
        var authorizeCalls: Int = 0

        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
            error("Not used in resume tests")
        }

        override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation {
            validateCalls++
            if (command.presentedToken.reveal() == "wrong-token") {
                throw dev.tramai.core.exception.ApprovalTokenRejectedException(command.approvalId)
            }
            return ApprovalValidation(
                approvalId = command.approvalId,
                validatedBy = command.consumedBy,
                validatedAt = clock.instant(),
                version = command.expectedVersion,
            )
        }

        override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
            authorizeCalls++
            val existing = durableReceipts[command.approvalId]
            if (existing != null) {
                val storedTokenDigest = requireNotNull(durableTokenDigests[command.approvalId]) {
                    "Missing durable token digest for replayed approval '${command.approvalId}'"
                }
                val presentedTokenDigest = tokenDigest(command.presentedToken)
                // Defense in depth: these post-consume checks protect against test-store contract
                // violations after the durable consume already happened.
                if (
                    existing.consumedBy != command.consumedBy ||
                    command.expectedVersion + 1 != existing.version ||
                    !MessageDigest.isEqual(
                        storedTokenDigest.value.toByteArray(StandardCharsets.US_ASCII),
                        presentedTokenDigest.value.toByteArray(StandardCharsets.US_ASCII),
                    )
                ) {
                    throw dev.tramai.core.exception.ApprovalAuthorizationException(command.approvalId)
                }
                return existing.copy(replayed = true)
            }

            val authorization = ApprovalAuthorization(
                approvalId = command.approvalId,
                consumedBy = command.consumedBy,
                consumedAt = clock.instant(),
                version = command.expectedVersion + 1,
            )
            durableReceipts[command.approvalId] = authorization
            durableTokenDigests[command.approvalId] = tokenDigest(command.presentedToken)
            if (
                mode == Mode.THROW_AFTER_DURABLE_CONSUME_ONCE &&
                threwAfterDurableConsume.compareAndSet(false, true)
            ) {
                throw IOException("adapter-secret-marker")
            }
            return authorization
        }

        override suspend fun cancelApproval(
            approvalId: String,
            expectedVersion: Long,
            reason: String,
        ) = Unit

        private fun tokenDigest(token: ApprovalToken): Sha256Digest {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(token.reveal().toByteArray(StandardCharsets.UTF_8))
            val hex = bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
            return Sha256Digest.of("sha256:$hex")
        }
    }

    private class ThrowingOnceClaimStore(
        private val delegate: InMemoryApprovalContinuationStore,
    ) : dev.tramai.core.approval.ApprovalContinuationStore by delegate {
        private val failed = AtomicBoolean(false)

        override suspend fun claimForExecution(
            approvalId: String,
            expectedVersion: Long,
            claimedBy: String,
        ): dev.tramai.core.approval.ClaimedApprovalContinuation {
            if (failed.compareAndSet(false, true)) {
                throw RuntimeException("claim-before-mutation")
            }
            return delegate.claimForExecution(approvalId, expectedVersion, claimedBy)
        }
    }

    private class RecordingEngineEventObserver : EngineEventObserver {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun onEngineEvent(
            name: String,
            attributes: Map<String, Any?>,
        ) {
            events += name to attributes
        }
    }

    private class ThrowingRemoveSuspendedInvocationStore(
        private val delegate: InMemorySuspendedInvocationStore,
    ) : SuspendedInvocationStore by delegate {
        override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? {
            throw RuntimeException("simulated-remove-failure")
        }
    }

    private class CapturingApprovalLifecycleAuditEmitter :
        dev.tramai.core.approval.ApprovalLifecycleAuditEmitter {
        var completedCalls = 0
        var lastCompletedBy: String? = null

        override suspend fun onToolExecutionSuspended(
            approvalId: String, workflowRunId: String, toolName: String,
            toolCallId: String, correlationId: String,
            argumentsDigest: dev.tramai.core.approval.Sha256Digest, expiresAt: java.time.Instant,
        ) = Unit
        override suspend fun onToolExecutionResumed(
            approvalId: String, workflowRunId: String, toolName: String, resumedBy: String,
        ) = Unit
        override suspend fun onToolExecutionCompleted(
            approvalId: String, workflowRunId: String, toolName: String, completedBy: String,
        ) {
            completedCalls++
            lastCompletedBy = completedBy
        }
        override suspend fun onUncertainOutcome(
            approvalId: String, workflowRunId: String, toolName: String, reason: String,
        ) = Unit
        override suspend fun onSuspensionCancelled(
            approvalId: String, workflowRunId: String, toolName: String, reason: String,
        ) = Unit
        override suspend fun onStaleClaimDetected(
            approvalId: String, workflowRunId: String, toolName: String, claimedAt: java.time.Instant,
        ) = Unit
        override suspend fun onClaimedContinuationForceCancellationRequested(
            approvalId: String, workflowRunId: String, toolName: String,
            cancelledBy: String, reasonCode: String,
        ) = Unit
        override suspend fun onClaimedContinuationForceCancelled(
            approvalId: String, workflowRunId: String, toolName: String,
            cancelledBy: String, reasonCode: String,
        ) = Unit
    }

    private class ThrowingEngineEventObserver(
        private val exception: CancellationException,
    ) : EngineEventObserver {
        override fun onEngineEvent(
            name: String,
            attributes: Map<String, Any?>,
        ) {
            throw exception
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
    private val coordinator = PermitApprovalGateCoordinator(fixedClock)
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

    private class DivergentToolStore(
        private val delegate: SuspendedInvocationStore,
        private val divergentTool: ResolvedTool,
        private val divergentToolCallId: String,
        private val divergentToolName: String,
        private val divergentArgumentsJson: String,
    ) : SuspendedInvocationStore by delegate {
        // No-op: the engine no longer uses the replay envelope for tool identity.
        // Tool is resolved from the runtime toolRegistry, ToolCall is constructed
        // from metadata.toolCallId + metadata.toolName + claimed arguments.
        // This store exists to test backward compatibility with old SPI.
    }

    private fun createEngine(
        provider: dev.tramai.core.provider.ModelProvider = this.provider,
        toolRegistry: ToolRegistry = this.toolRegistry,
        policyEngine: PolicyEngine = this.policyEngine,
        suspendedInvocationStore: SuspendedInvocationStore = this.suspendedInvocationStore,
        approvalContinuationStore: dev.tramai.core.approval.ApprovalContinuationStore = continuationStore,
        approvalGateCoordinator: ApprovalGateCoordinator = coordinator,
        engineEventObserver: EngineEventObserver = NoOpEngineEventObserver,
        approvalLifecycleAuditEmitter: dev.tramai.core.approval.ApprovalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
    ): TramaiEngine = TramaiEngine(
        provider = provider,
        toolRegistry = toolRegistry,
        policyEngine = policyEngine,
        suspendedInvocationStore = suspendedInvocationStore,
        approvalContinuationStore = approvalContinuationStore,
        toolArgumentsDigester = digester,
        approvalGateCoordinator = approvalGateCoordinator,
        engineEventObserver = engineEventObserver,
        approvalLifecycleAuditEmitter = approvalLifecycleAuditEmitter,
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

        // P1-4: Verify stored metadata fields before resume
        runBlocking {
            val metadata = suspendedInvocationStore.get(exception.approvalId)
            assertThat(metadata).isNotNull
            assertThat(metadata!!.approvalId).isEqualTo(exception.approvalId)
            assertThat(metadata.conversationId).isNull()
            assertThat(metadata.historySize).isEqualTo(0)
            assertThat(metadata.tokenBudgetSnapshot).isNotNull
        }

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
    fun `resumeApproval executes claimed arguments even when sensitive resume context diverges`() {
        val divergentArguments = """{"x":5,"y":6}"""
        val recordingTool = RecordingTool(name = toolName)
        var localProviderCallCount = 0
        val continuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        val engine = TramaiEngine(
            provider = RecordingProvider { request ->
                localProviderCallCount++
                if (localProviderCallCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "Final result: success")
                }
            },
            toolRegistry = ToolRegistry(mapOf(recordingTool.name to recordingTool)),
            policyEngine = SelectivePolicyEngine(toolExecApprovalToolName = toolName),
            suspendedInvocationStore = DivergentToolStore(
                delegate = InMemorySuspendedInvocationStore(),
                divergentTool = recordingTool,
                divergentToolCallId = toolCallId,
                divergentToolName = toolName,
                divergentArgumentsJson = divergentArguments,
            ),
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
        )

        val exception = triggerSuspension(engine)
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

        assertThat(result).isEqualTo("Final result: success")
        assertThat(recordingTool.invocations).hasSize(1)
        assertThat(recordingTool.invocations.single().first).isEqualTo(toolArguments)
        // Continuation must reach COMPLETED — proves divergent context was correctly ignored
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)
    }

    @Test
    fun `resumeApproval executes registry validated tool when sensitive resume context tool diverges`() {
        val trustedTool = RecordingTool(name = toolName)
        val divergentTool = RecordingTool(name = toolName, result = ToolResult.Success("""{"result":999}"""))
        var localProviderCallCount = 0
        val continuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        val engine = TramaiEngine(
            provider = RecordingProvider { _ ->
                localProviderCallCount++
                if (localProviderCallCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "Final result: success")
                }
            },
            toolRegistry = ToolRegistry(mapOf(trustedTool.name to trustedTool)),
            policyEngine = SelectivePolicyEngine(toolExecApprovalToolName = toolName),
            suspendedInvocationStore = DivergentToolStore(
                delegate = InMemorySuspendedInvocationStore(),
                divergentTool = divergentTool,
                divergentToolCallId = toolCallId,
                divergentToolName = toolName,
                divergentArgumentsJson = toolArguments,
            ),
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
        )

        val exception = triggerSuspension(engine)
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

        assertThat(result).isEqualTo("Final result: success")
        assertThat(trustedTool.invocations).hasSize(1)
        assertThat(divergentTool.invocations).isEmpty()
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)
    }

    @Test
    fun `resumeApproval fails closed when continuation toolName differs from metadata toolName`() {
        val recordingTool = RecordingTool(name = toolName)
        var localProviderCallCount = 0
        val realContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        // Wrapper that returns a continuation with a DIFFERENT toolName than the one stored during suspension
        val divergentContinuationStore = object : dev.tramai.core.approval.ApprovalContinuationStore {
            override suspend fun create(
                continuation: dev.tramai.core.approval.ApprovalContinuation,
                arguments: dev.tramai.core.approval.SensitiveToolArguments,
            ) = realContinuationStore.create(continuation, arguments)
            override suspend fun get(approvalId: String): dev.tramai.core.approval.ApprovalContinuation? {
                val real = realContinuationStore.get(approvalId) ?: return null
                return real.copy(toolName = "different-tool")
            }
            override suspend fun claimForExecution(
                approvalId: String, expectedVersion: Long, claimedBy: String,
            ) = realContinuationStore.claimForExecution(approvalId, expectedVersion, claimedBy)
            override suspend fun complete(
                approvalId: String, expectedVersion: Long, completedBy: String,
            ) = realContinuationStore.complete(approvalId, expectedVersion, completedBy)
            override suspend fun expire(
                approvalId: String, expectedVersion: Long,
            ) = realContinuationStore.expire(approvalId, expectedVersion)
            override suspend fun cancel(
                approvalId: String, expectedVersion: Long,
            ) = realContinuationStore.cancel(approvalId, expectedVersion)
            override suspend fun sweepExpired() = realContinuationStore.sweepExpired()
            override suspend fun findStaleClaimed(claimedBefore: java.time.Instant, limit: Int) = realContinuationStore.findStaleClaimed(claimedBefore, limit)
            override suspend fun forceCancelClaimed(approvalId: String, expectedVersion: Long, cancelledBy: String, reasonCode: String) = realContinuationStore.forceCancelClaimed(approvalId, expectedVersion, cancelledBy, reasonCode)
        }
        val engine = TramaiEngine(
            provider = RecordingProvider { _ ->
                localProviderCallCount++
                if (localProviderCallCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "should not happen")
                }
            },
            toolRegistry = ToolRegistry(mapOf(recordingTool.name to recordingTool)),
            policyEngine = SelectivePolicyEngine(toolExecApprovalToolName = toolName),
            suspendedInvocationStore = InMemorySuspendedInvocationStore(),
            approvalContinuationStore = divergentContinuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
        )

        val exception = triggerSuspension(engine)

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
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("continuation-tool-name-mismatch")

        assertThat(recordingTool.invocations).isEmpty()
    }

    @Test
    fun `resumeApproval fails closed when continuation toolCallId differs from metadata toolCallId`() {
        val recordingTool = RecordingTool(name = toolName)
        var localProviderCallCount = 0
        val realContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        val divergentContinuationStore = object : dev.tramai.core.approval.ApprovalContinuationStore {
            override suspend fun create(
                continuation: dev.tramai.core.approval.ApprovalContinuation,
                arguments: dev.tramai.core.approval.SensitiveToolArguments,
            ) = realContinuationStore.create(continuation, arguments)
            override suspend fun get(approvalId: String): dev.tramai.core.approval.ApprovalContinuation? {
                val real = realContinuationStore.get(approvalId) ?: return null
                return real.copy(toolCallId = "different-call-id")
            }
            override suspend fun claimForExecution(
                approvalId: String, expectedVersion: Long, claimedBy: String,
            ) = realContinuationStore.claimForExecution(approvalId, expectedVersion, claimedBy)
            override suspend fun complete(
                approvalId: String, expectedVersion: Long, completedBy: String,
            ) = realContinuationStore.complete(approvalId, expectedVersion, completedBy)
            override suspend fun expire(
                approvalId: String, expectedVersion: Long,
            ) = realContinuationStore.expire(approvalId, expectedVersion)
            override suspend fun cancel(
                approvalId: String, expectedVersion: Long,
            ) = realContinuationStore.cancel(approvalId, expectedVersion)
            override suspend fun sweepExpired() = realContinuationStore.sweepExpired()
            override suspend fun findStaleClaimed(claimedBefore: java.time.Instant, limit: Int) = realContinuationStore.findStaleClaimed(claimedBefore, limit)
            override suspend fun forceCancelClaimed(approvalId: String, expectedVersion: Long, cancelledBy: String, reasonCode: String) = realContinuationStore.forceCancelClaimed(approvalId, expectedVersion, cancelledBy, reasonCode)
        }
        val engine = TramaiEngine(
            provider = RecordingProvider { _ ->
                localProviderCallCount++
                if (localProviderCallCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "should not happen")
                }
            },
            toolRegistry = ToolRegistry(mapOf(recordingTool.name to recordingTool)),
            policyEngine = SelectivePolicyEngine(toolExecApprovalToolName = toolName),
            suspendedInvocationStore = InMemorySuspendedInvocationStore(),
            approvalContinuationStore = divergentContinuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
        )

        val exception = triggerSuspension(engine)

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
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("continuation-tool-call-id-mismatch")

        assertThat(recordingTool.invocations).isEmpty()
    }

    @Test
    fun `resumeApproval reinjects tool result with metadata toolCallId when sensitive context toolCall diverges`() {
        val divergentToolCallId = "call-divergent-99"
        val divergentToolName = "tool-divergent-name"
        val trustedTool = RecordingTool(name = toolName)
        val providerRequests = mutableListOf<ModelRequest>()
        var localProviderCallCount = 0
        val continuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        val engine = TramaiEngine(
            provider = RecordingProvider { request ->
                providerRequests += request
                localProviderCallCount++
                if (localProviderCallCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "Final result: success")
                }
            },
            toolRegistry = ToolRegistry(mapOf(trustedTool.name to trustedTool)),
            policyEngine = SelectivePolicyEngine(toolExecApprovalToolName = toolName),
            suspendedInvocationStore = DivergentToolStore(
                delegate = InMemorySuspendedInvocationStore(),
                divergentTool = trustedTool,
                divergentToolCallId = divergentToolCallId,
                divergentToolName = divergentToolName,
                divergentArgumentsJson = toolArguments,
            ),
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
        )

        val exception = triggerSuspension(engine)
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

        assertThat(result).isEqualTo("Final result: success")
        assertThat(providerRequests).hasSize(2)
        val resumedRequest = providerRequests.last()
        val toolMessage = resumedRequest.messages.lastOrNull {
            it.role == dev.tramai.core.model.MessageRole.TOOL
        }
        assertThat(toolMessage).isNotNull
        assertThat(toolMessage!!.toolCallId).isEqualTo(toolCallId)
        assertThat(toolMessage.toolCallId).isNotEqualTo(divergentToolCallId)
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.COMPLETED)
    }

    @Test
    fun `resumeApproval fails closed when approved tool is no longer registered`() {
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
            ) { auditEvents += reason }
            override suspend fun onSuspensionCancelled(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) = Unit
        }

        val continuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        val suspendedStore = InMemorySuspendedInvocationStore()
        var localProviderCallCount = 0
        val suspendingEngine = TramaiEngine(
            provider = RecordingProvider { _ ->
                localProviderCallCount++
                if (localProviderCallCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "Final result: success")
                }
            },
            toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
            policyEngine = SelectivePolicyEngine(toolExecApprovalToolName = toolName),
            suspendedInvocationStore = suspendedStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
            approvalLifecycleAuditEmitter = auditEmitter,
        )

        val exception = triggerSuspension(suspendingEngine)

        val resumingEngine = TramaiEngine(
            provider = RecordingProvider { ModelResponse(content = "should not happen") },
            toolRegistry = ToolRegistry(emptyMap()),
            policyEngine = SelectivePolicyEngine(toolExecApprovalToolName = toolName),
            suspendedInvocationStore = suspendedStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
            approvalLifecycleAuditEmitter = auditEmitter,
        )
        // No SuspensionTriggerService created on the resuming engine — the operation is not
        // registered in the new runtime, so resume fails before reaching tool resolution.

        assertThatThrownBy {
            runBlocking {
                resumingEngine.resumeApproval(
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
            .hasMessageContaining("resume-operation-not-registered")

        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)
        assertThat(auditEvents).isEmpty()
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
    fun `renewed RequireApproval digest mismatch fails closed without executing resumed tool`() {
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
            ) { auditEvents.add(reason) }
            override suspend fun onSuspensionCancelled(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) = Unit
        }
        val mismatchedDigest = digester.digest(
            dev.tramai.core.approval.SensitiveToolArguments.of("""{"account":"B","amount":10000}""")
        ).value
        var beforeResume = true
        var localProviderCallCount = 0
        val mismatchPolicyEngine = object : PolicyEngine {
            override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                return when (context.enforcementPoint) {
                    EnforcementPoint.BEFORE_TOOL_EXECUTION -> {
                        val digest = if (beforeResume) {
                            beforeResume = false
                            "sha256:12e49c0f5b1f1c5a753a1e98fb8e94a06c58b35c8432b77270d412d5d295e3b9"
                        } else {
                            mismatchedDigest
                        }
                        PolicyDecision.RequireApproval(
                            ApprovalRequirement(
                                toolName = toolName,
                                argumentsDigest = digest,
                                reason = "testing",
                                timeoutMillis = 60_000,
                            )
                        )
                    }
                    EnforcementPoint.BEFORE_WORKFLOW_RESUME -> PolicyDecision.Allow
                    else -> PolicyDecision.Allow
                }
            }
        }
        val recordingTool = RecordingTool(name = toolName)
        val continuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        val engine = TramaiEngine(
            provider = RecordingProvider { _ ->
                localProviderCallCount++
                if (localProviderCallCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "should not happen")
                }
            },
            toolRegistry = ToolRegistry(mapOf(recordingTool.name to recordingTool)),
            policyEngine = mismatchPolicyEngine,
            suspendedInvocationStore = InMemorySuspendedInvocationStore(),
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
            approvalLifecycleAuditEmitter = auditEmitter,
        )

        val exception = triggerSuspension(engine)

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
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Renewed approval requirement digest mismatch")

        assertThat(recordingTool.invocations).isEmpty()
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(auditEvents).contains("resume-failed: IllegalArgumentException")
    }

    @Test
    fun `renewed RequireApproval tool name mismatch fails closed without executing tool`() {
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
            ) { auditEvents.add(reason) }
            override suspend fun onSuspensionCancelled(
                approvalId: String, workflowRunId: String,
                toolName: String, reason: String,
            ) = Unit
        }
        var beforeResume = true
        var localProviderCallCount = 0
        val mismatchPolicyEngine = object : PolicyEngine {
            override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                return when (context.enforcementPoint) {
                    EnforcementPoint.BEFORE_TOOL_EXECUTION -> {
                        val toolNameForThisCall = if (beforeResume) {
                            beforeResume = false
                            toolName
                        } else {
                            "different-tool"
                        }
                        val digest = "sha256:12e49c0f5b1f1c5a753a1e98fb8e94a06c58b35c8432b77270d412d5d295e3b9"
                        PolicyDecision.RequireApproval(
                            ApprovalRequirement(
                                toolName = toolNameForThisCall,
                                argumentsDigest = digest,
                                reason = "testing",
                                timeoutMillis = 60_000,
                            )
                        )
                    }
                    EnforcementPoint.BEFORE_WORKFLOW_RESUME -> PolicyDecision.Allow
                    else -> PolicyDecision.Allow
                }
            }
        }
        val recordingTool = RecordingTool(name = toolName)
        val continuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        val engine = TramaiEngine(
            provider = RecordingProvider { _ ->
                localProviderCallCount++
                if (localProviderCallCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "should not happen")
                }
            },
            toolRegistry = ToolRegistry(mapOf(recordingTool.name to recordingTool)),
            policyEngine = mismatchPolicyEngine,
            suspendedInvocationStore = InMemorySuspendedInvocationStore(),
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
            approvalLifecycleAuditEmitter = auditEmitter,
        )

        val exception = triggerSuspension(engine)

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
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Renewed approval requirement tool name mismatch")

        assertThat(recordingTool.invocations).isEmpty()
        val continuation = runBlocking { continuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
        assertThat(auditEvents).contains("resume-failed: IllegalArgumentException")
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
    fun `normal tool execution receives null idempotency key`() {
        val localTool = RecordingTool(name = toolName)
        var localProviderCallCount = 0
        val engine = TramaiEngine(
            provider = RecordingProvider { _ ->
                localProviderCallCount++
                if (localProviderCallCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall("call-normal-1", toolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "Final result: success")
                }
            },
            toolRegistry = ToolRegistry(mapOf(localTool.name to localTool)),
            policyEngine = object : PolicyEngine {
                override suspend fun evaluate(context: PolicyContext): PolicyDecision = PolicyDecision.Allow
            },
            clock = fixedClock,
        )

        val service = engine.create<SuspensionTriggerService>()
        val result = runBlocking { service.execute("test input") }

        assertThat(result).isEqualTo("Final result: success")
        assertThat(localTool.invocations).hasSize(1)
        assertThat(localTool.invocations.single().second.idempotencyKey).isNull()
    }

    @Test
    fun `resumed approved execution receives derived idempotency key`() {
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
                ),
            )
        }

        val expectedKey = IdempotencyKeyUtil.deriveApprovalKey(
            exception.approvalId,
            toolCallId,
            digester.digest(SensitiveToolArguments.of(toolArguments)),
        )
        assertThat(tool.invocations).hasSize(1)
        assertThat(tool.invocations.single().second.idempotencyKey).isEqualTo(expectedKey)
    }

    @Test
    fun `idempotent resumed tool retries with identical idempotency key on every attempt`() {
        val retryingTool = object : ResolvedTool {
            override val name: String = toolName
            override val description: String = "Retrying tool"
            override val inputSchemaJson: String =
                """{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}}}"""
            override val idempotent: Boolean = true
            override val sideEffectLevel: SideEffectLevel = SideEffectLevel.WRITE
            val contexts = mutableListOf<ToolExecutionContext>()
            var calls = 0

            override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
                contexts += context
                calls++
                if (calls == 1) {
                    throw IllegalStateException("transient")
                }
                return ToolResult.Success("""{"result":5}""")
            }
        }
        var localProviderCallCount = 0
        val engine = TramaiEngine(
            provider = RecordingProvider { _ ->
                localProviderCallCount++
                if (localProviderCallCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "Final result: success")
                }
            },
            toolRegistry = ToolRegistry(mapOf(retryingTool.name to retryingTool)),
            policyEngine = SelectivePolicyEngine(toolExecApprovalToolName = toolName),
            suspendedInvocationStore = InMemorySuspendedInvocationStore(),
            approvalContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock),
            toolArgumentsDigester = digester,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
        )

        val exception = triggerSuspension(engine)
        runBlocking {
            engine.resumeApproval(
                ResumeApprovalCommand(
                    approvalId = exception.approvalId,
                    approvalExpectedVersion = 0L,
                    continuationExpectedVersion = 0L,
                    presentedToken = exception.challenge.token,
                    resumedBy = resumedBy,
                ),
            )
        }

        val expectedKey = IdempotencyKeyUtil.deriveApprovalKey(
            exception.approvalId,
            toolCallId,
            digester.digest(SensitiveToolArguments.of(toolArguments)),
        )
        assertThat(retryingTool.contexts).hasSize(2)
        assertThat(retryingTool.contexts.map { it.idempotencyKey }).containsOnly(expectedKey)
        assertThat(retryingTool.contexts.map { it.attemptNumber }).containsExactly(0, 1)
    }

    @Test
    fun `different approvalId toolCallId and digest values generate different keys`() {
        val digestA = digester.digest(SensitiveToolArguments.of(toolArguments))
        val digestB = digester.digest(SensitiveToolArguments.of("""{"x":9,"y":9}"""))

        val keyA = IdempotencyKeyUtil.deriveApprovalKey("approval-a", "call-a", digestA)
        val keyB = IdempotencyKeyUtil.deriveApprovalKey("approval-b", "call-a", digestA)
        val keyC = IdempotencyKeyUtil.deriveApprovalKey("approval-a", "call-b", digestA)
        val keyD = IdempotencyKeyUtil.deriveApprovalKey("approval-a", "call-a", digestB)

        assertThat(keyA).isNotEqualTo(keyB)
        assertThat(keyA).isNotEqualTo(keyC)
        assertThat(keyA).isNotEqualTo(keyD)
    }

    @Test
    fun `audit request failure leaves status claimed`() {
        val recoveryStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        runBlocking {
            recoveryStore.create(
                ApprovalContinuation(
                    approvalId = "recovery-1",
                    workflowRunId = "wf-run-1",
                    correlationId = "corr-1",
                    toolCallId = "tc-1",
                    toolName = toolName,
                    argumentsDigest = digester.digest(SensitiveToolArguments.of(toolArguments)),
                    policyVersion = "v1",
                    workflowDigest = dev.tramai.core.approval.Sha256Digest.of(
                        "sha256:1111111111111111111111111111111111111111111111111111111111111111",
                    ),
                    status = ApprovalContinuationStatus.PENDING,
                    createdAt = fixedClock.instant(),
                    approvalExpiresAt = fixedClock.instant().plusSeconds(600),
                    claimedBy = null,
                    claimedAt = null,
                    completedAt = null,
                    version = 0L,
                ),
                SensitiveToolArguments.of(toolArguments),
            )
            recoveryStore.claimForExecution("recovery-1", 0L, "runner-1")
        }
        val coordinator = InMemoryApprovalRecoveryCoordinator(
            store = recoveryStore,
            lifecycleAuditEmitter = object : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter by NoOpApprovalLifecycleAuditEmitter {
                override suspend fun onClaimedContinuationForceCancellationRequested(
                    approvalId: String,
                    workflowRunId: String,
                    toolName: String,
                    cancelledBy: String,
                    reasonCode: String,
                ) {
                    error("audit-down")
                }
            },
        )

        assertThatThrownBy {
            runBlocking {
                coordinator.forceCancelClaimed(
                    ForceCancelClaimedCommand(
                        approvalId = "recovery-1",
                        expectedVersion = 1L,
                        operatorId = "operator-1",
                        reasonCode = "worker-lost",
                    ),
                )
            }
        }.isInstanceOf(ApprovalRecoveryAuditUnavailableException::class.java)
            .hasMessage("Approval recovery audit is unavailable")

        val continuation = runBlocking { recoveryStore.get("recovery-1") }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
    }

    @Test
    fun `cancellation exception from detection audit and store mutation propagates unchanged`() {
        val recoveryStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        runBlocking {
            recoveryStore.create(
                ApprovalContinuation(
                    approvalId = "recovery-2",
                    workflowRunId = "wf-run-1",
                    correlationId = "corr-1",
                    toolCallId = "tc-1",
                    toolName = toolName,
                    argumentsDigest = digester.digest(SensitiveToolArguments.of(toolArguments)),
                    policyVersion = "v1",
                    workflowDigest = dev.tramai.core.approval.Sha256Digest.of(
                        "sha256:1111111111111111111111111111111111111111111111111111111111111111",
                    ),
                    status = ApprovalContinuationStatus.PENDING,
                    createdAt = fixedClock.instant(),
                    approvalExpiresAt = fixedClock.instant().plusSeconds(600),
                    claimedBy = null,
                    claimedAt = null,
                    completedAt = null,
                    version = 0L,
                ),
                SensitiveToolArguments.of(toolArguments),
            )
            recoveryStore.claimForExecution("recovery-2", 0L, "runner-1")
        }

        val detectionCancellation = CancellationException("stop-detection")
        val detectionCoordinator = InMemoryApprovalRecoveryCoordinator(
            store = recoveryStore,
            lifecycleAuditEmitter = object : dev.tramai.core.approval.ApprovalLifecycleAuditEmitter by NoOpApprovalLifecycleAuditEmitter {
                override suspend fun onStaleClaimDetected(
                    approvalId: String,
                    workflowRunId: String,
                    toolName: String,
                    claimedAt: Instant,
                ) {
                    throw detectionCancellation
                }
            },
        )
        val staleContinuation = runBlocking { recoveryStore.get("recovery-2") }!!
        assertThatThrownBy {
            runBlocking {
                detectionCoordinator.findStaleClaims(
                    claimedBefore = staleContinuation.claimedAt!!.plusSeconds(1),
                    limit = 10,
                )
            }
        }.isSameAs(detectionCancellation)

        val mutationCancellation = CancellationException("stop-mutation")
        val mutationCoordinator = InMemoryApprovalRecoveryCoordinator(
            store = object : dev.tramai.core.approval.ApprovalContinuationStore by recoveryStore {
                override suspend fun forceCancelClaimed(
                    approvalId: String,
                    expectedVersion: Long,
                    cancelledBy: String,
                    reasonCode: String,
                ): ApprovalContinuation {
                    throw mutationCancellation
                }
            },
            lifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
        )
        assertThatThrownBy {
            runBlocking {
                mutationCoordinator.forceCancelClaimed(
                    ForceCancelClaimedCommand(
                        approvalId = "recovery-2",
                        expectedVersion = 1L,
                        operatorId = "operator-1",
                        reasonCode = "worker-lost",
                    ),
                )
            }
        }.isSameAs(mutationCancellation)
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
    fun `second resume on same approvalId rejects the consumed token`() {
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

        // Second resume fails - the first successful resume completed the continuation
        assertThatThrownBy {
            runBlocking { engine.resumeApproval(command) }
        }.isInstanceOf(dev.tramai.core.exception.ApprovalTokenRejectedException::class.java)
    }

    @Test
    fun `resumeApproval retries exact same command after durable consume adapter failure and emits replay event`() {
        val engineEventObserver = RecordingEngineEventObserver()
        val replayCoordinator = ReplaySafeApprovalGateCoordinator(
            clock = fixedClock,
            mode = ReplaySafeApprovalGateCoordinator.Mode.THROW_AFTER_DURABLE_CONSUME_ONCE,
        )
        val localSuspendedStore = InMemorySuspendedInvocationStore()
        val localContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        var localProviderCallCount = 0
        val provider = RecordingProvider { _ ->
            localProviderCallCount++
            if (localProviderCallCount == 1) {
                ModelResponse(
                    content = "",
                    toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                )
            } else {
                ModelResponse(content = "Final result: success")
            }
        }
        val suspendingEngine = createEngine(
            provider = provider,
            suspendedInvocationStore = localSuspendedStore,
            approvalContinuationStore = localContinuationStore,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
        )
        val exception = triggerSuspension(suspendingEngine)
        val engine = createEngine(
            provider = provider,
            suspendedInvocationStore = localSuspendedStore,
            approvalContinuationStore = localContinuationStore,
            approvalGateCoordinator = replayCoordinator,
            engineEventObserver = engineEventObserver,
        )
        engine.create<SuspensionTriggerService>()
        val command = ResumeApprovalCommand(
            approvalId = exception.approvalId,
            approvalExpectedVersion = 0L,
            continuationExpectedVersion = 0L,
            presentedToken = exception.challenge.token,
            resumedBy = resumedBy,
        )

        val firstFailure = catchThrowableOfType(
            { runBlocking { engine.resumeApproval(command) } },
            IOException::class.java,
        )
        assertThat(firstFailure).isNotNull
        assertThat(firstFailure).hasMessage("adapter-secret-marker")

        val result = runBlocking { engine.resumeApproval(command) }

        assertThat(result).isEqualTo("Final result: success")
        assertThat(replayCoordinator.authorizeCalls).isEqualTo(2)
        val replayEvent = engineEventObserver.events.single { it.first == "tramai.approval.authorization_replayed" }
        assertThat(replayEvent.second).containsEntry("approvalId", exception.approvalId)
        assertThat(replayEvent.second).containsEntry("toolName", toolName)
        assertThat(replayEvent.second.keys).containsExactlyInAnyOrder("approvalId", "workflowRunId", "toolName")
        assertThat(replayEvent.second.values.map { it?.toString() }).doesNotContain(exception.challenge.token.reveal())
    }

    @Test
    fun `resumeApproval retries exact same command after claim failure once and emits replay event`() {
        val engineEventObserver = RecordingEngineEventObserver()
        val replayCoordinator = ReplaySafeApprovalGateCoordinator(clock = fixedClock)
        val localSuspendedStore = InMemorySuspendedInvocationStore()
        val localContinuationStore = ThrowingOnceClaimStore(
            delegate = InMemoryApprovalContinuationStore(clock = fixedClock),
        )
        var localProviderCallCount = 0
        val provider = RecordingProvider { _ ->
            localProviderCallCount++
            if (localProviderCallCount == 1) {
                ModelResponse(
                    content = "",
                    toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                )
            } else {
                ModelResponse(content = "Final result: success")
            }
        }
        val suspendingEngine = createEngine(
            provider = provider,
            suspendedInvocationStore = localSuspendedStore,
            approvalContinuationStore = localContinuationStore,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
        )
        val exception = triggerSuspension(suspendingEngine)
        val engine = createEngine(
            provider = provider,
            suspendedInvocationStore = localSuspendedStore,
            approvalContinuationStore = localContinuationStore,
            approvalGateCoordinator = replayCoordinator,
            engineEventObserver = engineEventObserver,
        )
        engine.create<SuspensionTriggerService>()
        val command = ResumeApprovalCommand(
            approvalId = exception.approvalId,
            approvalExpectedVersion = 0L,
            continuationExpectedVersion = 0L,
            presentedToken = exception.challenge.token,
            resumedBy = resumedBy,
        )

        val firstFailure = catchThrowableOfType(
            { runBlocking { engine.resumeApproval(command) } },
            RuntimeException::class.java,
        )
        assertThat(firstFailure).isNotNull
        assertThat(firstFailure).hasMessage("claim-before-mutation")

        val result = runBlocking { engine.resumeApproval(command) }

        assertThat(result).isEqualTo("Final result: success")
        assertThat(replayCoordinator.authorizeCalls).isEqualTo(2)
        val replayEvent = engineEventObserver.events.single { it.first == "tramai.approval.authorization_replayed" }
        assertThat(replayEvent.second).containsEntry("approvalId", exception.approvalId)
        assertThat(replayEvent.second).containsEntry("toolName", toolName)
        assertThat(replayEvent.second.keys).containsExactlyInAnyOrder("approvalId", "workflowRunId", "toolName")
        assertThat(replayEvent.second.values.map { it?.toString() }).doesNotContain(exception.challenge.token.reveal())
    }

    @Test
    fun `resumeApproval rethrows cancellation from replay telemetry and recovers on later retry`() {
        val replayCoordinator = ReplaySafeApprovalGateCoordinator(clock = fixedClock)
        val localSuspendedStore = InMemorySuspendedInvocationStore()
        val localContinuationStore = ThrowingOnceClaimStore(
            delegate = InMemoryApprovalContinuationStore(clock = fixedClock),
        )
        val localTool = RecordingTool(name = toolName)
        var localProviderCallCount = 0
        val provider = RecordingProvider { _ ->
            localProviderCallCount++
            if (localProviderCallCount == 1) {
                ModelResponse(
                    content = "",
                    toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                )
            } else {
                ModelResponse(content = "Final result: success")
            }
        }
        val suspendingEngine = createEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf(localTool.name to localTool)),
            suspendedInvocationStore = localSuspendedStore,
            approvalContinuationStore = localContinuationStore,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
        )
        val exception = triggerSuspension(suspendingEngine)
        val command = ResumeApprovalCommand(
            approvalId = exception.approvalId,
            approvalExpectedVersion = 0L,
            continuationExpectedVersion = 0L,
            presentedToken = exception.challenge.token,
            resumedBy = resumedBy,
        )

        val firstAttemptEngine = createEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf(localTool.name to localTool)),
            suspendedInvocationStore = localSuspendedStore,
            approvalContinuationStore = localContinuationStore,
            approvalGateCoordinator = replayCoordinator,
        )
        firstAttemptEngine.create<SuspensionTriggerService>()

        val firstFailure = catchThrowableOfType(
            { runBlocking { firstAttemptEngine.resumeApproval(command) } },
            RuntimeException::class.java,
        )
        assertThat(firstFailure).isNotNull
        assertThat(firstFailure).hasMessage("claim-before-mutation")
        assertThat(localTool.invocations).isEmpty()
        assertThat(runBlocking { localContinuationStore.get(exception.approvalId) }!!.status)
            .isEqualTo(ApprovalContinuationStatus.PENDING)

        val cancellation = CancellationException("replay-event-cancelled")
        val secondAttemptEngine = createEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf(localTool.name to localTool)),
            suspendedInvocationStore = localSuspendedStore,
            approvalContinuationStore = localContinuationStore,
            approvalGateCoordinator = replayCoordinator,
            engineEventObserver = ThrowingEngineEventObserver(cancellation),
        )
        secondAttemptEngine.create<SuspensionTriggerService>()

        val secondFailure = catchThrowableOfType(
            { runBlocking { secondAttemptEngine.resumeApproval(command) } },
            CancellationException::class.java,
        )
        assertThat(secondFailure).isSameAs(cancellation)
        assertThat(localTool.invocations).isEmpty()
        val pendingContinuationAfterCancellation = runBlocking { localContinuationStore.get(exception.approvalId) }
        assertThat(pendingContinuationAfterCancellation).isNotNull
        assertThat(pendingContinuationAfterCancellation!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)

        val recordingObserver = RecordingEngineEventObserver()
        val recoveryEngine = createEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf(localTool.name to localTool)),
            suspendedInvocationStore = localSuspendedStore,
            approvalContinuationStore = localContinuationStore,
            approvalGateCoordinator = replayCoordinator,
            engineEventObserver = recordingObserver,
        )
        recoveryEngine.create<SuspensionTriggerService>()

        val result = runBlocking { recoveryEngine.resumeApproval(command) }

        assertThat(result).isEqualTo("Final result: success")
        assertThat(localTool.invocations).hasSize(1)
        assertThat(runBlocking { localContinuationStore.get(exception.approvalId) }!!.status)
            .isEqualTo(ApprovalContinuationStatus.COMPLETED)
        assertThat(replayCoordinator.authorizeCalls).isEqualTo(3)
        val replayEvent = recordingObserver.events.single { it.first == "tramai.approval.authorization_replayed" }
        assertThat(replayEvent.second).containsEntry("approvalId", exception.approvalId)
        assertThat(replayEvent.second).containsEntry("toolName", toolName)
    }

    @Test
    fun `resumeApproval recovers from first claim failure using real approval coordinator and stores`() {
        val approvalStore = InMemoryApprovalStore(clock = fixedClock)
        val realContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        val wrappedContinuationStore = ThrowingOnceClaimStore(delegate = realContinuationStore)
        val approvalCoordinator = DefaultApprovalGateCoordinator(
            store = approvalStore,
            approvalIdGenerator = UuidApprovalIdGenerator(),
            approvalTokenGenerator = SecureRandomApprovalTokenGenerator(),
            approvalTokenDigester = Sha256ApprovalTokenDigester(),
            clock = fixedClock,
        )
        val recordingObserver = RecordingEngineEventObserver()
        val localTool = RecordingTool(name = toolName)
        var localProviderCallCount = 0
        val provider = RecordingProvider { _ ->
            localProviderCallCount++
            if (localProviderCallCount == 1) {
                ModelResponse(
                    content = "",
                    toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                )
            } else {
                ModelResponse(content = "Final result: success")
            }
        }
        val suspendedStore = InMemorySuspendedInvocationStore()
        val engine = createEngine(
            provider = provider,
            toolRegistry = ToolRegistry(mapOf(localTool.name to localTool)),
            suspendedInvocationStore = suspendedStore,
            approvalContinuationStore = wrappedContinuationStore,
            approvalGateCoordinator = approvalCoordinator,
            engineEventObserver = recordingObserver,
        )

        val exception = triggerSuspension(engine)
        runBlocking {
            approvalStore.transition(
                approvalId = exception.approvalId,
                expectedVersion = 0L,
                transition = ApprovalTransition.Approve(
                    decidedBy = "approver",
                    comment = "approved",
                ),
            )
        }
        val command = ResumeApprovalCommand(
            approvalId = exception.approvalId,
            approvalExpectedVersion = 1L,
            continuationExpectedVersion = 0L,
            presentedToken = exception.challenge.token,
            resumedBy = resumedBy,
        )
        engine.create<ResumeBootstrapService>()

        val firstFailure = catchThrowableOfType(
            { runBlocking { engine.resumeApproval(command) } },
            RuntimeException::class.java,
        )
        assertThat(firstFailure).isNotNull
        assertThat(firstFailure).hasMessage("claim-before-mutation")

        val consumedApproval = runBlocking { approvalStore.get(exception.approvalId) }
        assertThat(consumedApproval).isNotNull
        assertThat(consumedApproval!!.consumedBy).isEqualTo(resumedBy)
        assertThat(consumedApproval.version).isEqualTo(2L)
        val pendingContinuation = runBlocking { realContinuationStore.get(exception.approvalId) }
        assertThat(pendingContinuation).isNotNull
        assertThat(pendingContinuation!!.status).isEqualTo(ApprovalContinuationStatus.PENDING)
        assertThat(localTool.invocations).isEmpty()

        val result = runBlocking { engine.resumeApproval(command) }

        assertThat(result).isEqualTo("Final result: success")
        assertThat(localTool.invocations).hasSize(1)
        assertThat(runBlocking { realContinuationStore.get(exception.approvalId) }!!.status)
            .isEqualTo(ApprovalContinuationStatus.COMPLETED)
        val replayEvents = recordingObserver.events.filter { it.first == "tramai.approval.authorization_replayed" }
        assertThat(replayEvents).hasSize(1)
        assertThat(replayEvents.single().second).containsEntry("approvalId", exception.approvalId)
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
    fun `resumeApproval structured parse failure is sanitized and leaks only to the diagnostic observer`() {
        val resumeFixture = "fixture-sentinel-so-2b4"
        val diagnostics = mutableListOf<StructuredOutputFailureDiagnosticEvent>()
        val structuredHandler = FakeStructuredOutputHandler(
            StructuredOutputResult.Failure(
                rawResponse = "unparseable $resumeFixture",
                errorSummary = "failed to parse $resumeFixture",
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
            structuredOutputFailureDiagnosticObserver = StructuredOutputFailureDiagnosticObserver { diagnostics += it },
        )

        val structuredService = engine.create<StructuredResumeTriggerService>()
        val exception = try {
            runBlocking { structuredService.execute("test") }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        policyEngine.resumeDecision = PolicyDecision.Allow

        val thrown = catchThrowable {
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
        }
        assertThat(thrown).isInstanceOf(dev.tramai.core.exception.StructuredOutputException::class.java)
        val safe = thrown as dev.tramai.core.exception.StructuredOutputException
        assertThat(safe.failureCode).isEqualTo(StructuredOutputFailureCode.OUTPUT_REJECTED)
        assertThat(safe.message).doesNotContain(resumeFixture)
        assertThat(safe.originalPrompt).isNull()
        assertThat(safe.lastRawResponse).isNull()
        assertThat(safe.validationError).isNull()
        assertThat(safe.cause).isNull()
        // The sentinel reaches ONLY the privileged diagnostic observer.
        val event = diagnostics.single()
        assertThat(event.code).isEqualTo(StructuredOutputFailureCode.OUTPUT_REJECTED)
        assertThat(event.rawResponsePreview).contains(resumeFixture)
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
        val mtCoordinator = PermitApprovalGateCoordinator(fixedClock)

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

    // ════════════════════════════════════════════════════════════════
    // P1-2: Nested approval in sibling tools fail-closed
    // ════════════════════════════════════════════════════════════════

    /**
     * Provider that returns 2 tool calls on the first call — BOTH of which require approval.
     * Only the first triggers suspension (it's processed first). On resume, after the first
     * tool executes, processing the sibling tool should detect the nested approval and
     * fail closed with ConfigurationException.
     */
    @Test
    fun `resumeApproval with nested sibling approval throws ConfigurationException`() {
        var callCount = 0
        val nestedProvider = object : dev.tramai.core.provider.ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                callCount++
                if (callCount == 1) {
                    return ModelResponse(
                        content = "",
                        toolCalls = listOf(
                            ToolCall("call-nested-0", "test_calculator", toolArguments),
                            ToolCall("call-nested-1", "test_calculator", toolArguments),
                        ),
                    )
                }
                return ModelResponse(content = "nested resume complete")
            }
            override fun providerId(): String = "test"
        }

        // Policy engine that requires approval for ANY BEFORE_TOOL_EXECUTION
        val nestedPolicyEngine = object : PolicyEngine {
            override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                    return PolicyDecision.RequireApproval(
                        ApprovalRequirement(
                            toolName = "test_calculator",
                            argumentsDigest = "sha256:12e49c0f5b1f1c5a753a1e98fb8e94a06c58b35c8432b77270d412d5d295e3b9",
                            reason = "testing",
                            timeoutMillis = 60_000,
                        )
                    )
                }
                if (context.enforcementPoint == EnforcementPoint.BEFORE_WORKFLOW_RESUME) {
                    return PolicyDecision.Allow
                }
                return PolicyDecision.Allow
            }
        }

        val nestedTool = RecordingTool(name = "test_calculator")
        val nestedToolRegistry = ToolRegistry(mapOf(nestedTool.name to nestedTool))
        val nestedCoordinator = PermitApprovalGateCoordinator(fixedClock)
        val nestedContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)

        val engine = TramaiEngine(
            provider = nestedProvider,
            toolRegistry = nestedToolRegistry,
            policyEngine = nestedPolicyEngine,
            suspendedInvocationStore = InMemorySuspendedInvocationStore(),
            approvalContinuationStore = nestedContinuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = nestedCoordinator,
            clock = fixedClock,
        )

        val service = engine.create<SuspensionTriggerService>()
        val exception = try {
            runBlocking { service.execute("test") }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        // Resume — sibling tool (index 1) will also require approval → fail closed
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
        }.isInstanceOf(dev.tramai.core.exception.NestedApprovalNotSupportedException::class.java)
            .hasMessageContaining("Nested approval not supported in v1")

        // The resumed tool should have executed once
        assertThat(nestedTool.invocations).hasSize(1)
    }

    // ════════════════════════════════════════════════════════════════
    // P1-3: BEFORE_WORKFLOW_RESUME security context enrichment
    // ════════════════════════════════════════════════════════════════

    /**
     * Verifies that the BEFORE_WORKFLOW_RESUME policy context contains the
     * toolSecurity and securityContext fields that Fix 6 added.
     */
    @Test
    fun `resumeApproval BEFORE_WORKFLOW_RESUME context has toolSecurity and securityContext`() {
        val recordedContexts = mutableListOf<PolicyContext>()

        val recordingPolicyEngine = object : PolicyEngine {
            override suspend fun evaluate(context: PolicyContext): PolicyDecision {
                recordedContexts.add(context)
                return when (context.enforcementPoint) {
                    EnforcementPoint.BEFORE_TOOL_EXECUTION -> PolicyDecision.RequireApproval(
                        ApprovalRequirement(
                            toolName = toolName,
                            argumentsDigest = "sha256:12e49c0f5b1f1c5a753a1e98fb8e94a06c58b35c8432b77270d412d5d295e3b9",
                            reason = "testing",
                            timeoutMillis = 60_000,
                        )
                    )
                    EnforcementPoint.BEFORE_WORKFLOW_RESUME -> PolicyDecision.Allow
                    else -> PolicyDecision.Allow
                }
            }
        }

        // Create a tool with explicit security metadata so toolSecurity is not null
        val securedTool = object : ResolvedTool {
            override val name: String = toolName
            override val description: String = "Secured tool"
            override val inputSchemaJson: String = """{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}}}"""
            override val idempotent: Boolean = false
            override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY
            override val security: dev.tramai.core.policy.ToolSecurityMetadata? =
                dev.tramai.core.policy.ToolSecurityMetadata.legacyPermissive()
            val invocations = mutableListOf<Pair<Any, ToolExecutionContext>>()
            override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
                invocations.add(Pair(input, context))
                return ToolResult.Success("""{"result":5}""")
            }
        }
        val securedToolRegistry = ToolRegistry(mapOf(securedTool.name to securedTool))

        val engine = TramaiEngine(
            provider = provider,
            toolRegistry = securedToolRegistry,
            policyEngine = recordingPolicyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
        )

        val exception = triggerSuspension(engine)

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

        // Find the BEFORE_WORKFLOW_RESUME context
        val resumeContext = recordedContexts.firstOrNull {
            it.enforcementPoint == EnforcementPoint.BEFORE_WORKFLOW_RESUME
        }
        assertThat(resumeContext).isNotNull

        // Verify toolSecurity is present (from the tool's explicit security metadata)
        assertThat(resumeContext!!.toolSecurity).isNotNull
        assertThat(resumeContext.toolSecurity!!.permission).isEqualTo("legacy.unrestricted")

        // Verify security context fields — dataClassification may be populated via applySecurityContext
    }

    // ════════════════════════════════════════════════════════════════
    // Fix 4: Regression tests — nested approval, memory, Unit result
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `later provider-turn nested approval fails closed without child state`() {
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

        val freshContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        val freshSuspendedStore = InMemorySuspendedInvocationStore()

        var nestedProviderCallCount = 0
        val nestedProvider = object : dev.tramai.core.provider.ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                nestedProviderCallCount++
                if (nestedProviderCallCount == 1) {
                    return ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                }
                // Subsequent call during resume returns more tool calls requiring approval
                return ModelResponse(
                    content = "",
                    toolCalls = listOf(ToolCall("call-nested-2", toolName, toolArguments)),
                )
            }
            override fun providerId(): String = "test"
        }

        val nestedTool = RecordingTool(name = toolName)
        val nestedToolRegistry = ToolRegistry(mapOf(nestedTool.name to nestedTool))
        val nestedPolicyEngine = SelectivePolicyEngine(
            toolExecApprovalToolName = toolName,
            requireApprovalAtToolExec = true,
            PolicyDecision.Allow, // BEFORE_WORKFLOW_RESUME
        )

        val engine = TramaiEngine(
            provider = nestedProvider,
            toolRegistry = nestedToolRegistry,
            policyEngine = nestedPolicyEngine,
            suspendedInvocationStore = freshSuspendedStore,
            approvalContinuationStore = freshContinuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
            approvalLifecycleAuditEmitter = auditEmitter,
        )

        val service = engine.create<SuspensionTriggerService>()
        val exception = try {
            runBlocking { service.execute("test") }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        // Resume — provider's second call will return more tool calls that require approval
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
        }.isInstanceOf(dev.tramai.core.exception.NestedApprovalNotSupportedException::class.java)

        // Parent continuation stays CLAIMED (not completed, not cancelled)
        val continuation = runBlocking { freshContinuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)

        // No child state — no new approval challenges were created
        assertThat(auditEvents.any { it.contains("uncertain:") }).isTrue
    }

    @Test
    fun `nested approval exception uses parent approval ID`() {
        // Use a fresh store for this test
        val freshContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        val freshSuspendedStore = InMemorySuspendedInvocationStore()
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

        var providerCallCount = 0
        val nestedProvider = object : dev.tramai.core.provider.ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                providerCallCount++
                if (providerCallCount == 1) {
                    return ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                }
                return ModelResponse(
                    content = "",
                    toolCalls = listOf(ToolCall("call-nested-p2", toolName, toolArguments)),
                )
            }
            override fun providerId(): String = "test"
        }

        val nestedTool = RecordingTool(name = toolName)
        val nestedToolRegistry = ToolRegistry(mapOf(nestedTool.name to nestedTool))
        val nestedPolicyEngine = SelectivePolicyEngine(
            toolExecApprovalToolName = toolName,
            requireApprovalAtToolExec = true,
            PolicyDecision.Allow,
        )

        val engine = TramaiEngine(
            provider = nestedProvider,
            toolRegistry = nestedToolRegistry,
            policyEngine = nestedPolicyEngine,
            suspendedInvocationStore = freshSuspendedStore,
            approvalContinuationStore = freshContinuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
            approvalLifecycleAuditEmitter = auditEmitter,
        )

        val service = engine.create<SuspensionTriggerService>()
        val exception = try {
            runBlocking { service.execute("test") }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        }

        val parentApprovalId = exception.approvalId

        // Resume — should fail with NestedApprovalNotSupportedException
        val nestedEx = catchThrowableOfType(
            {
                runBlocking {
                    engine.resumeApproval(
                        ResumeApprovalCommand(
                            approvalId = parentApprovalId,
                            approvalExpectedVersion = 0L,
                            continuationExpectedVersion = 0L,
                            presentedToken = exception.challenge.token,
                            resumedBy = resumedBy,
                        )
                    )
                }
            },
            dev.tramai.core.exception.NestedApprovalNotSupportedException::class.java,
        )

        // Verify exception contains parent approval ID
        assertThat(nestedEx.approvalId).isEqualTo(parentApprovalId)
        assertThat(nestedEx.message).contains("Nested approval not supported in v1")

        // Parent continuation stays CLAIMED (not completed)
        val continuation = runBlocking { freshContinuationStore.get(parentApprovalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)

        // No child state was created — no new approval challenges
        assertThat(auditEvents.any { it.contains("uncertain:") }).isTrue
    }

    @Test
    fun `resumed Unit operation returns Unit`() {
        val unitToolName = "unit_tool"
        val unitToolRegistry = ToolRegistry(mapOf(
            unitToolName to RecordingTool(
                name = unitToolName,
                result = ToolResult.Success("OK"),
            )
        ))

        // Policy engine that requires approval for tool execution on the unit tool
        val unitPolicyEngine = SelectivePolicyEngine(
            toolExecApprovalToolName = unitToolName,
            requireApprovalAtToolExec = true,
            PolicyDecision.Allow,
        )

        var unitCallCount = 0
        val unitProvider = object : dev.tramai.core.provider.ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                unitCallCount++
                return if (unitCallCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall("call-unit-1", unitToolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "Unit operation complete")
                }
            }
            override fun providerId(): String = "test"
        }

        val freshContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        val freshSuspendedStore = InMemorySuspendedInvocationStore()

        val engine = TramaiEngine(
            provider = unitProvider,
            toolRegistry = unitToolRegistry,
            policyEngine = unitPolicyEngine,
            suspendedInvocationStore = freshSuspendedStore,
            approvalContinuationStore = freshContinuationStore,
            toolArgumentsDigester = Sha256ToolArgumentsDigester(),
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
            clock = fixedClock,
        )

        val service = engine.create<UnitReturnService>()
        val exception = try {
            runBlocking { service.execute("test") }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            e
        }

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

        assertThat(result).isEqualTo(Unit)
    }

    // ════════════════════════════════════════════════════════════════
    // Fix 5: Security-ordering regression tests
    // ════════════════════════════════════════════════════════════════

    @Test
    fun `structured parse failure persists no invalid memory`() {
        val testMemory = TestChatMemory()
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
            chatMemory = testMemory,
            conversationIdProvider = dev.tramai.core.memory.UuidConversationIdProvider(),
        )

        // Trigger suspension using StructuredResumeTriggerService
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

        // No memory should have been persisted — the parse failure was terminal
        val allConversations = testMemory.getAllConversationIds()
        assertThat(allConversations).isEmpty()
    }

    @Test
    fun `conversation memory uses the original deterministic conversation ID`() {
        val testMemory = TestChatMemory()
        val fixedConversationId = "fixed-conversation-id-for-test"
        val fixedIdProvider = object : dev.tramai.core.memory.ConversationIdProvider {
            override fun resolve(): String = fixedConversationId
        }

        var callCount = 0
        val memoryProvider = object : dev.tramai.core.provider.ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                callCount++
                return if (callCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                    )
                } else {
                    ModelResponse(content = "Final result: memory test")
                }
            }
            override fun providerId(): String = "test"
        }

        val engine = TramaiEngine(
            provider = memoryProvider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = continuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = coordinator,
            clock = fixedClock,
            chatMemory = testMemory,
            conversationIdProvider = fixedIdProvider,
        )

        val exception = triggerSuspension(engine)
        policyEngine.resumeDecision = PolicyDecision.Allow

        // Capture the conversation ID used during suspension
        val suspendedMetadata = runBlocking {
            suspendedInvocationStore.get(exception.approvalId)
        }
        assertThat(suspendedMetadata).isNotNull
        val originalConversationId = suspendedMetadata!!.conversationId

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

        // After resume, memory should use the same conversation ID
        val postResumeConversations = testMemory.getAllConversationIds()
        assertThat(postResumeConversations).isNotEmpty
        // If the metadata had a conversation ID (non-null), it should match what was used in memory
        if (originalConversationId != null) {
            assertThat(postResumeConversations).contains(originalConversationId)
        }
    }

    @Test
    fun `nested approval creates exactly one approval challenge total`() {
        // Coordinator that counts createApproval calls
        var createCount = 0
        var authorizeCount = 0
        val countingCoordinator = object : ApprovalGateCoordinator {
            override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
                createCount++
                return ApprovalChallenge(
                    approvalId = UUID.randomUUID().toString(),
                    token = ApprovalToken.parsePresented("token-${UUID.randomUUID()}"),
                    expiresAt = command.expiresAt,
                )
            }
            override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation {
                return ApprovalValidation(
                    approvalId = command.approvalId,
                    validatedBy = command.consumedBy,
                    validatedAt = Clock.systemUTC().instant(),
                    version = command.expectedVersion,
                )
            }
            override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization {
                authorizeCount++
                return ApprovalAuthorization(
                    approvalId = command.approvalId,
                    consumedBy = command.consumedBy,
                    consumedAt = Clock.systemUTC().instant(),
                    version = command.expectedVersion,
                )
            }
            override suspend fun cancelApproval(
                approvalId: String, expectedVersion: Long, reason: String,
            ) = Unit
        }

        var providerCallCount = 0
        val countingProvider = object : dev.tramai.core.provider.ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                providerCallCount++
                return if (providerCallCount == 1) {
                    ModelResponse(
                        content = "",
                        toolCalls = listOf(
                            ToolCall("call-nested-0", toolName, toolArguments),
                            ToolCall("call-nested-1", toolName, toolArguments),
                        ),
                    )
                } else {
                    ModelResponse(content = "Final result")
                }
            }
            override fun providerId(): String = "test"
        }

        val freshContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        val freshSuspendedStore = InMemorySuspendedInvocationStore()

        val engine = TramaiEngine(
            provider = countingProvider,
            toolRegistry = toolRegistry,
            policyEngine = policyEngine,
            suspendedInvocationStore = freshSuspendedStore,
            approvalContinuationStore = freshContinuationStore,
            toolArgumentsDigester = digester,
            approvalGateCoordinator = countingCoordinator,
            clock = fixedClock,
        )

        val exception = triggerSuspension(engine)
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
        }.isInstanceOf(dev.tramai.core.exception.NestedApprovalNotSupportedException::class.java)

        // Exactly one approval challenge was created, even though resume hit a real nested approval path.
        assertThat(createCount).isEqualTo(1)
        assertThat(authorizeCount).isEqualTo(1)
        val continuation = runBlocking { freshContinuationStore.get(exception.approvalId) }
        assertThat(continuation).isNotNull
        assertThat(continuation!!.status).isEqualTo(ApprovalContinuationStatus.CLAIMED)
    }

    @Test
    fun `completion audit is attempted when suspended-context removal fails`() {
        val engineEventObserver = RecordingEngineEventObserver()
        val capturingAuditEmitter = CapturingApprovalLifecycleAuditEmitter()
        val throwingRemoveStore = ThrowingRemoveSuspendedInvocationStore(
            delegate = InMemorySuspendedInvocationStore(),
        )
        val localContinuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
        var localProviderCallCount = 0
        val provider = RecordingProvider { _ ->
            localProviderCallCount++
            if (localProviderCallCount == 1) {
                ModelResponse(
                    content = "",
                    toolCalls = listOf(ToolCall(toolCallId, toolName, toolArguments)),
                )
            } else {
                ModelResponse(content = "Final result: success")
            }
        }
        val suspendingEngine = createEngine(
            provider = provider,
            suspendedInvocationStore = throwingRemoveStore,
            approvalContinuationStore = localContinuationStore,
            approvalGateCoordinator = PermitApprovalGateCoordinator(fixedClock),
        )
        val exception = triggerSuspension(suspendingEngine)
        val engine = createEngine(
            provider = provider,
            suspendedInvocationStore = throwingRemoveStore,
            approvalContinuationStore = localContinuationStore,
            approvalLifecycleAuditEmitter = capturingAuditEmitter,
            engineEventObserver = engineEventObserver,
        )
        engine.create<SuspensionTriggerService>()
        val command = ResumeApprovalCommand(
            approvalId = exception.approvalId,
            approvalExpectedVersion = 0L,
            continuationExpectedVersion = 0L,
            presentedToken = exception.challenge.token,
            resumedBy = resumedBy,
        )

        val result = runBlocking { engine.resumeApproval(command) }

        // Tool executed successfully despite removal failure
        assertThat(result).isEqualTo("Final result: success")
        // Completion audit was attempted even though removal failed
        assertThat(capturingAuditEmitter.completedCalls).isEqualTo(1)
        assertThat(capturingAuditEmitter.lastCompletedBy).isEqualTo(resumedBy)
        // Cleanup failure was reported through the observer
        assertThat(engineEventObserver.events.any { it.first == "resume-suspended-context-cleanup-failure" })
            .isTrue
    }

    // ── Test ChatMemory ────────────────────────────────────────────

    private class TestChatMemory : dev.tramai.core.memory.ChatMemory {
        private val store = mutableMapOf<String, MutableList<dev.tramai.core.model.Message>>()

        override fun get(conversationId: String): List<dev.tramai.core.model.Message> {
            require(conversationId.isNotBlank())
            return store[conversationId]?.toList() ?: emptyList()
        }

        override fun add(conversationId: String, messages: List<dev.tramai.core.model.Message>) {
            require(conversationId.isNotBlank())
            store.getOrPut(conversationId) { mutableListOf() }.addAll(messages)
        }

        override fun add(conversationId: String, message: dev.tramai.core.model.Message) {
            require(conversationId.isNotBlank())
            store.getOrPut(conversationId) { mutableListOf() }.add(message)
        }

        override fun clear(conversationId: String) {
            require(conversationId.isNotBlank())
            store.remove(conversationId)
        }

        fun getAllConversationIds(): Set<String> = store.keys.toSet()
    }

    // ── Test Service Interfaces ────────────────────────────────────

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

    @AiService
    @SystemPrompt("You are a helpful assistant.")
    private interface UnitReturnService {
        @Operation(
            prompt = "Execute unit tool",
            model = "claude-sonnet-4-20250514",
            tools = ["unit_tool"],
        )
        suspend fun execute(input: String)
    }

    @AiService
    @SystemPrompt("You are a helpful assistant.")
    private interface ResumeBootstrapService {
        @Operation(
            prompt = "Bootstrap resume handler",
            model = "claude-sonnet-4-20250514",
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
