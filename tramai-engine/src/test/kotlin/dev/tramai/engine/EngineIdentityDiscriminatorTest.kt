package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.NoOpOperationObserver
import dev.tramai.core.observation.NoOpToolFailureDiagnosticObserver
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.provider.StreamCapable
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.core.memory.UuidConversationIdProvider
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.core.structured.StructuredOutputContract
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.core.structured.StructuredOutputResult
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.components.EngineComponentFactory
import dev.tramai.core.model.ToolExecutionContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.collections.ArrayDeque

/**
 * 8.3b2a — engine execution identity authority.
 *
 * Primary invariant: every engine-generated execution identity comes from one
 * explicit engine composition boundary; an invocation samples each required
 * identity exactly once, propagates it unchanged, and resume never creates
 * replacement identity.
 */
class EngineIdentityDiscriminatorTest {

    @AiService
    interface IdentityTestService {
        @Operation(prompt = "Answer", model = "logical-model")
        fun answer(input: String): String

        @Operation(prompt = "Answer", model = "logical-model")
        fun structuredAnswer(input: String): IdentityResult

        @Operation(prompt = "Answer", model = "logical-model")
        fun stream(input: String): Flow<StreamChunk>
    }

    data class IdentityResult(val value: String)

    /** Deterministic identity source; throws on exhaustion so resume cannot silently mint. */
    private class QueuedEngineIdentitySource(
        workflowRunIds: List<String>,
        correlationIds: List<String>,
    ) : EngineIdentitySource {
        private val runQueue = ArrayDeque(workflowRunIds)
        private val corrQueue = ArrayDeque(correlationIds)
        var workflowRunSamples = 0
            private set
        var correlationSamples = 0
            private set

        override fun newWorkflowRunId(): String {
            workflowRunSamples++
            return runQueue.removeFirstOrNull() ?: error("workflowRunId queue exhausted")
        }

        override fun newCorrelationId(): String {
            correlationSamples++
            return corrQueue.removeFirstOrNull() ?: error("correlationId queue exhausted")
        }
    }

    /** Records the correlationId seen at every enforcement point. */
    private class CapturingPolicyEngine(
        private val requireApprovalAtToolExec: Boolean = false,
        private val approvalToolName: String = "",
        private val approvalArgumentsDigest: String = "",
    ) : PolicyEngine {
        val seen = mutableListOf<Pair<EnforcementPoint, String>>()

        override suspend fun evaluate(context: PolicyContext): PolicyDecision {
            seen += context.enforcementPoint to context.correlationId
            if (requireApprovalAtToolExec && context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                return PolicyDecision.RequireApproval(
                    ApprovalRequirement(
                        toolName = approvalToolName,
                        argumentsDigest = approvalArgumentsDigest,
                        reason = "testing",
                        timeoutMillis = 60_000,
                    ),
                )
            }
            return PolicyDecision.Allow
        }
    }

    private class RecordingProvider(
        private val name: String = "primary",
        private val responder: (Int) -> ModelResponse,
    ) : ModelProvider, StreamCapable {
        var calls = 0
            private set
        var streamRequests = 0
            private set

        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls++
            return responder(calls)
        }

        override fun stream(request: ModelRequest): Flow<StreamChunk> {
            streamRequests++
            return flow {
                emit(StreamChunk.Token("ok"))
                emit(StreamChunk.Complete(fullText = "ok"))
            }
        }

        override fun providerId(): String = name
    }

    private class PermitApprovalGateCoordinator : ApprovalGateCoordinator {
        var nextChallengeId = "challenge-1"

        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
            val id = nextChallengeId
            nextChallengeId = "challenge-2"
            return ApprovalChallenge(
                approvalId = id,
                token = ApprovalToken.parsePresented("token-$id"),
                expiresAt = command.expiresAt,
            )
        }

        override suspend fun validateResume(command: ValidateResumeCommand): ApprovalValidation =
            ApprovalValidation(
                approvalId = command.approvalId,
                validatedBy = command.consumedBy,
                validatedAt = Clock.systemUTC().instant(),
                version = command.expectedVersion,
            )

        override suspend fun authorizeResume(command: AuthorizeResumeCommand): ApprovalAuthorization =
            ApprovalAuthorization(
                approvalId = command.approvalId,
                consumedBy = command.consumedBy,
                consumedAt = Clock.systemUTC().instant(),
                version = command.expectedVersion,
            )

        override suspend fun cancelApproval(approvalId: String, expectedVersion: Long, reason: String) = Unit
    }

    private class RecordingTool : ResolvedTool {
        override val name: String = "test_calculator"
        override val description: String = "Calculator tool"
        override val inputSchemaJson: String = """{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}}}"""
        override val idempotent: Boolean = false
        override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY
        var invocations = 0
            private set

        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            invocations++
            return ToolResult.Success("""{"result":5}""")
        }
    }

    private fun buildEngine(
        source: EngineIdentitySource,
        policyEngine: PolicyEngine,
        provider: ModelProvider,
        toolRegistry: ToolRegistry = ToolRegistry(),
        structuredOutputHandler: StructuredOutputHandler? = null,
        suspendedInvocationStore: SuspendedInvocationStore = InMemorySuspendedInvocationStore(),
        approvalContinuationStore: ApprovalContinuationStore? = null,
        approvalGateCoordinator: ApprovalGateCoordinator? = null,
    ): TramaiEngine {
        val components = EngineComponentFactory.create(
            providerRegistry = dev.tramai.core.provider.ProviderRegistry.singleProvider(provider),
            structuredOutputHandler = structuredOutputHandler,
            toolRegistry = toolRegistry,
            operationObserver = NoOpOperationObserver,
            operationInterceptor = object : dev.tramai.core.observation.OperationInterceptor {},
            responseCache = NoOpOperationResponseCache,
            modelRegistry = dev.tramai.core.model.NoOpModelRegistry,
            modelRegistrySettings = dev.tramai.core.model.ModelRegistrySettings(),
            circuitBreakerSettings = CircuitBreakerSettings(),
            retryPolicySettings = RetryPolicySettings(),
            tokenBudgetSettings = TokenBudgetSettings(),
            promptSanitizer = null,
            chatMemory = null,
            conversationIdProvider = UuidConversationIdProvider(),
            policyEngine = policyEngine,
            dlpInterceptor = NoOpDlpInterceptor,
            dlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter,
            toolResultFilteringSettings = ToolResultFilteringSettings(),
            engineEventObserver = NoOpEngineEventObserver,
            toolFailureDiagnosticObserver = NoOpToolFailureDiagnosticObserver,
            policyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = approvalContinuationStore,
            toolArgumentsDigester = if (approvalContinuationStore != null) Sha256ToolArgumentsDigester() else null,
            approvalGateCoordinator = approvalGateCoordinator,
            approvalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
            clock = Clock.systemUTC(),
            identitySource = source,
        )
        return TramaiEngine(components)
    }

    private class IdentityStructuredOutputHandler : StructuredOutputHandler {
        override fun createContract(targetType: kotlin.reflect.KType): StructuredOutputContract =
            StructuredOutputContract(targetType, """{"type":"object","properties":{"value":{"type":"string"}}}""")

        override fun analyze(rawResponse: String, targetType: kotlin.reflect.KType): StructuredOutputResult =
            StructuredOutputResult.Success(IdentityResult("structured-ok"), rawResponse)

        override fun generateSchema(type: kotlin.reflect.KType): String = """{"type":"object"}"""

        override fun deserialize(input: Any, targetType: kotlin.reflect.KType): Any =
            IdentityResult(input.toString())

        override fun serialize(value: Any): Any = value
    }

    private fun syncProvider() = RecordingProvider { call ->
        if (call == 1) {
            ModelResponse(
                content = "",
                toolCalls = listOf(
                    dev.tramai.core.model.ToolCall("call-1", "test_calculator", """{"x":2,"y":3}"""),
                ),
            )
        } else {
            ModelResponse(content = "Final result: success")
        }
    }

    private fun plainProvider() = RecordingProvider { _ -> ModelResponse(content = "plain answer") }

    @Test
    fun `P0-A RAW invocation uses the exact injected identity everywhere`() {
        runBlocking {
        val source = QueuedEngineIdentitySource(listOf("run-A"), listOf("corr-A"))
        val policy = CapturingPolicyEngine()
        val provider = plainProvider()
        val engine = buildEngine(source, policy, provider)
        val service = engine.create(IdentityTestService::class)

        assertThat(service.answer("hello")).isEqualTo("plain answer")

        assertThat(source.workflowRunSamples).isEqualTo(1)
        assertThat(source.correlationSamples).isEqualTo(1)
        assertThat(policy.seen).isNotEmpty
        assertThat(policy.seen.map { it.second }).allMatch { it == "corr-A" }
        assertThat(provider.calls).isEqualTo(1)
    }
    }

    @Test
    fun `P0-B STRUCTURED invocation samples identity at invocation entry`() {
        runBlocking {
        val source = QueuedEngineIdentitySource(listOf("run-B"), listOf("corr-B"))
        val policy = CapturingPolicyEngine()
        val engine = buildEngine(source, policy, plainProvider(), structuredOutputHandler = null)
        val service = engine.create(IdentityTestService::class)

        // No StructuredOutputHandler: contract validation fails. Option B pins that
        // identity belongs to the invocation, so it is sampled BEFORE the failure.
        assertThatThrownBy { service.structuredAnswer("x") }
            .isInstanceOf(ConfigurationException::class.java)
        assertThat(source.workflowRunSamples).isEqualTo(1)
        assertThat(source.correlationSamples).isEqualTo(1)
    }
    }

    @Test
    fun `P0-B2 structured success propagates exact injected identity everywhere`() {
        runBlocking {
            val source = QueuedEngineIdentitySource(
                workflowRunIds = listOf("test-run-identity"),
                correlationIds = listOf("test-correlation-identity"),
            )
            val policy = CapturingPolicyEngine()
            val engine = buildEngine(
                source, policy, plainProvider(),
                structuredOutputHandler = IdentityStructuredOutputHandler(),
            )
            val service = engine.create(IdentityTestService::class)

            val result = service.structuredAnswer("x")
            assertThat(result).isEqualTo(IdentityResult("structured-ok"))

            assertThat(source.workflowRunSamples).isEqualTo(1)
            assertThat(source.correlationSamples).isEqualTo(1)
            assertThat(policy.seen).isNotEmpty
            assertThat(policy.seen.map { it.second }.distinct())
                .containsExactly("test-correlation-identity")
        }
    }

    @Test
    fun `P0-C one sample per invocation`() {
        runBlocking {
        val source = QueuedEngineIdentitySource(listOf("run-A"), listOf("corr-A"))
        val provider = plainProvider()
        val engine = buildEngine(source, CapturingPolicyEngine(), provider)
        val service = engine.create(IdentityTestService::class)

        service.answer("one")
        assertThat(source.workflowRunSamples).isEqualTo(1)
        assertThat(source.correlationSamples).isEqualTo(1)

        // The source is exhausted: a second invocation must fail at the identity
        // boundary before any provider/tool side effect.
        assertThatThrownBy { service.answer("two") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("exhausted")
        assertThat(provider.calls).isEqualTo(1)
    }
    }

    @Test
    fun `P0-D every enforcement point sees the same injected correlation`() {
        runBlocking {
        val source = QueuedEngineIdentitySource(listOf("run-D"), listOf("corr-D"))
        val policy = CapturingPolicyEngine()
        val engine = buildEngine(source, policy, plainProvider())
        val service = engine.create(IdentityTestService::class)

        service.answer("hello")

        val points = policy.seen.map { it.first }.toSet()
        assertThat(points).contains(EnforcementPoint.BEFORE_PROVIDER_RESOLUTION)
        assertThat(points).contains(EnforcementPoint.BEFORE_PROVIDER_INVOCATION)
        assertThat(policy.seen.map { it.second }.distinct()).containsExactly("corr-D")
    }
    }

    @Test
    fun `P0-E suspension persists the exact complete identity`() {
        runBlocking {
        val source = QueuedEngineIdentitySource(listOf("run-E"), listOf("corr-E"))
        val policy = CapturingPolicyEngine(
            requireApprovalAtToolExec = true,
            approvalToolName = "test_calculator",
            approvalArgumentsDigest = "sha256:12e49c0f5b1f1c5a753a1e98fb8e94a06c58b35c8432b77270d412d5d295e3b9",
        )
        val tool = RecordingTool()
        val toolRegistry = ToolRegistry(mapOf(tool.name to tool))
        val gate = PermitApprovalGateCoordinator()
        val continuationStore = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val suspendedStore = InMemorySuspendedInvocationStore()
        val engine = buildEngine(
            source, policy, syncProvider(), toolRegistry,
            suspendedInvocationStore = suspendedStore,
            approvalContinuationStore = continuationStore,
            approvalGateCoordinator = gate,
        )
        val service = engine.create(IdentityTestService::class)

        val outcome = runCatching { service.answer("suspend") }.exceptionOrNull()
        require(outcome is ApprovalSuspendedException) { "expected ApprovalSuspendedException, got ${outcome?.javaClass?.name}: ${outcome?.message}" }
        val exception = outcome
        assertThat(exception.workflowRunId).isEqualTo("run-E")

        val metadata = suspendedStore.get(exception.approvalId)
        assertThat(metadata).isNotNull
        assertThat(metadata!!.identity.workflowRunId).isEqualTo("run-E")
        assertThat(metadata.correlationId).isEqualTo("corr-E")
        assertThat(metadata.identity.correlationId).isEqualTo("corr-E")
    }
    }

    @Test
    fun `P0-F resume consumes zero fresh identity`() {
        runBlocking {
        val source = QueuedEngineIdentitySource(listOf("run-F"), listOf("corr-F"))
        val policy = CapturingPolicyEngine(
            requireApprovalAtToolExec = true,
            approvalToolName = "test_calculator",
            approvalArgumentsDigest = "sha256:12e49c0f5b1f1c5a753a1e98fb8e94a06c58b35c8432b77270d412d5d295e3b9",
        )
        val tool = RecordingTool()
        val toolRegistry = ToolRegistry(mapOf(tool.name to tool))
        val gate = PermitApprovalGateCoordinator()
        val continuationStore = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val suspendedStore = InMemorySuspendedInvocationStore()
        val engine = buildEngine(
            source, policy, syncProvider(), toolRegistry,
            suspendedInvocationStore = suspendedStore,
            approvalContinuationStore = continuationStore,
            approvalGateCoordinator = gate,
        )
        val service = engine.create(IdentityTestService::class)

        val outcome = runCatching { service.answer("suspend") }.exceptionOrNull()
        require(outcome is ApprovalSuspendedException) { "expected ApprovalSuspendedException, got ${outcome?.javaClass?.name}: ${outcome?.message}" }
        val exception = outcome
        // Source is now exhausted (1+1 consumed). Resume must not sample.
        val samplesBefore = source.workflowRunSamples + source.correlationSamples

        val result = engine.resumeApproval(
            ResumeApprovalCommand(
                approvalId = exception.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = exception.challenge.token,
                resumedBy = "test",
            ),
        )

        assertThat(result).isNotNull
        assertThat(source.workflowRunSamples + source.correlationSamples).isEqualTo(samplesBefore)
    }
    }

    @Test
    fun `P0-G streaming is lazy - a never collected flow consumes zero identity`() {
        runBlocking {
        val source = QueuedEngineIdentitySource(listOf("run-G"), listOf("corr-G"))
        val provider = RecordingProvider { _ -> ModelResponse(content = "unused") }
        val engine = buildEngine(source, CapturingPolicyEngine(), provider)
        val service = engine.create(IdentityTestService::class)

        val flow = service.stream("lazy")
        assertThat(source.workflowRunSamples).isEqualTo(0)
        assertThat(source.correlationSamples).isEqualTo(0)

        flow.collect()
        assertThat(source.correlationSamples).isEqualTo(1)
    }
    }

    @Test
    fun `P0-H streaming samples exactly one correlation per collection`() {
        runBlocking {
        val source = QueuedEngineIdentitySource(
            workflowRunIds = listOf("run-H"),
            correlationIds = listOf("corr-H1", "corr-H2"),
        )
        val provider = RecordingProvider { _ -> ModelResponse(content = "unused") }
        val engine = buildEngine(source, CapturingPolicyEngine(), provider)
        val service = engine.create(IdentityTestService::class)

        val flow = service.stream("double")
        flow.collect()
        flow.collect()

        assertThat(source.correlationSamples).isEqualTo(2)
        assertThat(source.workflowRunSamples).isEqualTo(0)
    }
    }

    @Test
    fun `P0-I blank generated identities fail before side effects`() {
        runBlocking {
        val blankSource = object : EngineIdentitySource {
            override fun newWorkflowRunId(): String = "  "
            override fun newCorrelationId(): String = "corr-I"
        }
        val provider = plainProvider()
        val engine = buildEngine(blankSource, CapturingPolicyEngine(), provider)
        val service = engine.create(IdentityTestService::class)

        assertThatThrownBy { service.answer("x") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("workflowRunId")
        assertThat(provider.calls).isEqualTo(0)

        val blankCorrSource = object : EngineIdentitySource {
            override fun newWorkflowRunId(): String = "run-I"
            override fun newCorrelationId(): String = ""
        }
        val provider2 = plainProvider()
        val engine2 = buildEngine(blankCorrSource, CapturingPolicyEngine(), provider2)
        val service2 = engine2.create(IdentityTestService::class)

        assertThatThrownBy { service2.answer("x") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("correlationId")
        assertThat(provider2.calls).isEqualTo(0)
    }
    }

    @Test
    fun `P0-I2 streaming blank correlation fails before provider side effects`() {
        runBlocking {
            val blankCorrSource = object : EngineIdentitySource {
                override fun newWorkflowRunId(): String = "run-I2"
                override fun newCorrelationId(): String = ""
            }
            val provider = RecordingProvider { _ -> ModelResponse(content = "unused") }
            val engine = buildEngine(blankCorrSource, CapturingPolicyEngine(), provider)
            val service = engine.create(IdentityTestService::class)

            val flow = service.stream("blank")
            val collectFailure = runCatching { flow.collect() }.exceptionOrNull()
            assertThat(collectFailure).isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("correlationId")
            assertThat(provider.streamRequests).isEqualTo(0)
        }
    }
}
