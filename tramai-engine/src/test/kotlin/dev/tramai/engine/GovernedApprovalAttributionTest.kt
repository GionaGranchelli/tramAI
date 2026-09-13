@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.ApprovalAuthorization
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalValidation
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.GovernedRunContinuityException
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.GovernedRunScope
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.memory.UuidConversationIdProvider
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.NoOpOperationObserver
import dev.tramai.core.observation.NoOpToolFailureDiagnosticObserver
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.engine.components.EngineComponentFactory
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.7.1d governed approval attribution.
 *
 * The durable suspension record is the authority for a continuation: it carries the whole
 * [GovernedRunIdentity], and the engine identity inside it must agree with the canonical
 * run id. A standalone resume recovers that identity and installs it; a caller-supplied
 * identity is only a consistency precondition, so a substitution of ANY component — not
 * just the run id — must be rejected before the continuation is claimed or executed.
 */
class GovernedApprovalAttributionTest {
    private companion object {
        const val TOOL_NAME = "test_calculator"
        const val ARGUMENTS_DIGEST = "sha256:12e49c0f5b1f1c5a753a1e98fb8e94a06c58b35c8432b77270d412d5d295e3b9"
    }

    @AiService
    interface ApprovalIdentityService {
        @Operation(prompt = "Answer", model = "logical-model")
        suspend fun answer(input: String): String
    }

    private class ForbiddenRunIdSource : EngineIdentitySource {
        var runIdSamples = 0
            private set

        override fun newWorkflowRunId(): String {
            runIdSamples++
            error("engine must not generate a workflow run id inside a governed execution")
        }

        override fun newCorrelationId(): String = "corr-governed"
    }

    private class RunIdSource(
        private val runId: String,
    ) : EngineIdentitySource {
        var runIdSamples = 0
            private set

        override fun newWorkflowRunId(): String {
            runIdSamples++
            return runId
        }

        override fun newCorrelationId(): String = "corr-legacy"
    }

    /** Requires approval at tool execution for the calculator tool. */
    private class ApprovalPolicyEngine : PolicyEngine {
        override suspend fun evaluate(context: PolicyContext): PolicyDecision =
            if (context.enforcementPoint == EnforcementPoint.BEFORE_TOOL_EXECUTION) {
                PolicyDecision.RequireApproval(
                    ApprovalRequirement(
                        toolName = TOOL_NAME,
                        argumentsDigest = ARGUMENTS_DIGEST,
                        reason = "testing",
                        timeoutMillis = 60_000,
                    ),
                )
            } else {
                PolicyDecision.Allow
            }
    }

    private class RecordingProvider : ModelProvider {
        var calls = 0
            private set

        override suspend fun complete(request: ModelRequest): ModelResponse {
            calls++
            return if (calls == 1) {
                ModelResponse(
                    content = "",
                    toolCalls = listOf(ToolCall("call-1", TOOL_NAME, """{"x":2,"y":3}""")),
                )
            } else {
                ModelResponse(content = "Final result: success")
            }
        }

        override fun providerId(): String = "primary"
    }

    /** Records the governed identity in force when the tool finally executes. */
    private class ScopeObservingTool : ResolvedTool {
        override val name: String = TOOL_NAME
        override val description: String = "Calculator tool"
        override val inputSchemaJson: String = ""
        override val idempotent: Boolean = false
        override val sideEffectLevel: SideEffectLevel = SideEffectLevel.READ_ONLY
        val observedIdentities = CopyOnWriteArrayList<GovernedRunIdentity?>()
        var invocations = 0
            private set

        override suspend fun execute(
            input: Any,
            context: ToolExecutionContext,
        ): ToolResult {
            invocations++
            observedIdentities += GovernedRunScope.currentThreadIdentity()
            return ToolResult.Success("""{"result":5}""")
        }
    }

    private class PermitApprovalGateCoordinator : ApprovalGateCoordinator {
        var createdApprovals = 0
            private set
        private var nextChallengeId = "challenge-1"

        override suspend fun createApproval(command: CreateApprovalCommand): ApprovalChallenge {
            createdApprovals++
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

        override suspend fun cancelApproval(
            approvalId: String,
            expectedVersion: Long,
            reason: String,
        ) = Unit
    }

    /**
     * A legacy-only store: implements [SuspendedInvocationStore] but NOT the governed
     * capability, exactly like a third-party implementation written before 0.7.1d.
     */
    private class LegacyOnlySuspendedInvocationStore : SuspendedInvocationStore {
        private val delegate = InMemorySuspendedInvocationStore()

        override suspend fun create(
            metadata: SuspendedInvocationMetadata,
            replayEnvelope: SensitiveReplayEnvelope,
        ) = delegate.create(metadata, replayEnvelope)

        override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = delegate.get(approvalId)

        override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? = delegate.revealReplayEnvelope(approvalId)

        override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? = delegate.remove(approvalId)
    }

    private class Fixture(
        val suspendedStore: SuspendedInvocationStore = InMemorySuspendedInvocationStore(),
        val source: EngineIdentitySource = ForbiddenRunIdSource(),
    ) {
        val provider = RecordingProvider()
        val tool = ScopeObservingTool()
        val gate = PermitApprovalGateCoordinator()
        val continuationStore = InMemoryApprovalContinuationStore(clock = Clock.systemUTC())
        val engine =
            TramaiEngine(
                EngineComponentFactory.create(
                    providerRegistry =
                        dev.tramai.core.provider.ProviderRegistry
                            .singleProvider(provider),
                    structuredOutputHandler = null,
                    toolRegistry = ToolRegistry(mapOf(tool.name to tool)),
                    operationObserver = NoOpOperationObserver,
                    operationInterceptor = object : dev.tramai.core.observation.OperationInterceptor {},
                    responseCache = NoOpOperationResponseCache,
                    modelRegistry = dev.tramai.core.model.NoOpModelRegistry,
                    modelRegistrySettings =
                        dev.tramai.core.model
                            .ModelRegistrySettings(),
                    circuitBreakerSettings = CircuitBreakerSettings(),
                    retryPolicySettings = RetryPolicySettings(),
                    tokenBudgetSettings = TokenBudgetSettings(),
                    promptSanitizer = null,
                    chatMemory = null,
                    conversationIdProvider = UuidConversationIdProvider(),
                    policyEngine = ApprovalPolicyEngine(),
                    dlpInterceptor = NoOpDlpInterceptor,
                    dlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter,
                    toolResultFilteringSettings = ToolResultFilteringSettings(),
                    engineEventObserver = NoOpEngineEventObserver,
                    toolFailureDiagnosticObserver = NoOpToolFailureDiagnosticObserver,
                    policyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
                    suspendedInvocationStore = suspendedStore,
                    approvalContinuationStore = continuationStore,
                    toolArgumentsDigester = Sha256ToolArgumentsDigester(),
                    approvalGateCoordinator = gate,
                    approvalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
                    clock = Clock.systemUTC(),
                    identitySource = source,
                ),
            )

        val service = engine.create(ApprovalIdentityService::class)

        suspend fun suspendGoverned(identity: GovernedRunIdentity): ApprovalSuspendedException {
            val failure =
                runCatching {
                    withContext(GovernedRunScope(identity)) { service.answer("suspend") }
                }.exceptionOrNull()
            require(failure is ApprovalSuspendedException) {
                "expected ApprovalSuspendedException, got ${failure?.javaClass?.name}: ${failure?.message}"
            }
            return failure
        }

        suspend fun suspendLegacy(): ApprovalSuspendedException {
            val failure = runCatching { service.answer("suspend") }.exceptionOrNull()
            require(failure is ApprovalSuspendedException) {
                "expected ApprovalSuspendedException, got ${failure?.javaClass?.name}: ${failure?.message}"
            }
            return failure
        }

        fun resumeCommand(exception: ApprovalSuspendedException): ResumeApprovalCommand =
            ResumeApprovalCommand(
                approvalId = exception.approvalId,
                approvalExpectedVersion = 0L,
                continuationExpectedVersion = 0L,
                presentedToken = exception.challenge.token,
                resumedBy = "operator",
            )
    }

    private fun identity(
        runId: String = "governed-approval-run",
        workload: String = "claims",
        configurationId: String = "claims-prod",
        configurationVersion: String = "17",
        environment: String = "production",
        deploymentId: String = "eu-west-amsterdam-01",
    ): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId(workload),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId(configurationId),
                            version = ConfigurationVersion(configurationVersion),
                        ),
                    environmentId = EnvironmentId(environment),
                    deploymentId = DeploymentId(deploymentId),
                ),
            runId = RunId(runId),
        )

    // ── Suspension ──────────────────────────────────────────────────

    @Test
    fun `a governed suspension persists the whole identity and its engine identity agrees`() {
        runBlocking {
            val fixture = Fixture()
            val expected = identity("governed-approval-run")

            val exception = fixture.suspendGoverned(expected)

            val metadata = fixture.suspendedStore.get(exception.approvalId)
            assertThat(metadata).isNotNull
            // The bidirectional invariant: the engine identity the suspension recorded and
            // the canonical run identity must be the same run.
            assertThat(metadata!!.identity.workflowRunId)
                .withFailMessage("engine identity and canonical run identity must agree")
                .isEqualTo(expected.runId.value)
            val persisted =
                (fixture.suspendedStore as GovernedSuspendedInvocationStore)
                    .governedRunIdentity(exception.approvalId)
            assertThat(persisted).isEqualTo(expected)
            // Nothing was generated: the canonical identity came from the governed scope.
            assertThat((fixture.source as ForbiddenRunIdSource).runIdSamples).isEqualTo(0)
        }
    }

    @Test
    fun `a governed suspension fails closed when the store lacks the governed capability`() {
        runBlocking {
            val fixture = Fixture(suspendedStore = LegacyOnlySuspendedInvocationStore())

            assertThatThrownBy {
                runBlocking {
                    withContext(GovernedRunScope(identity())) { fixture.service.answer("suspend") }
                }
            }.isInstanceOf(ConfigurationException::class.java)
                .hasMessageContaining("GovernedSuspendedInvocationStore")
            // Failed BEFORE any durable approval/continuation state was created: the
            // degradation this prevents is "governed until the first approval boundary".
            assertThat(fixture.gate.createdApprovals).isEqualTo(0)
        }
    }

    // ── Continuation ────────────────────────────────────────────────

    @Test
    fun `a standalone resume recovers and installs the persisted identity`() {
        runBlocking {
            val fixture = Fixture()
            val expected = identity("governed-standalone-run")
            val exception = fixture.suspendGoverned(expected)

            // No governed scope in force: the durable witness is the authority.
            val result = fixture.engine.resumeApproval(fixture.resumeCommand(exception))

            assertThat(result).isNotNull()
            assertThat(fixture.tool.invocations).isEqualTo(1)
            assertThat(fixture.tool.observedIdentities)
                .withFailMessage("the resumed execution must carry the persisted identity")
                .containsExactly(expected)
        }
    }

    @Test
    fun `resume inside the identical governed scope proceeds`() {
        runBlocking {
            val fixture = Fixture()
            val expected = identity("governed-identical-run")
            val exception = fixture.suspendGoverned(expected)

            val result =
                withContext(GovernedRunScope(expected)) {
                    fixture.engine.resumeApproval(fixture.resumeCommand(exception))
                }

            assertThat(result).isNotNull()
            assertThat(fixture.tool.observedIdentities).containsExactly(expected)
        }
    }

    @Test
    fun `every substituted identity component is rejected before the continuation is claimed`() {
        runBlocking {
            val substitutions =
                mapOf(
                    "workloadId" to identity(workload = "payments"),
                    "configurationId" to identity(configurationId = "claims-staging"),
                    "configurationVersion" to identity(configurationVersion = "18"),
                    "environmentId" to identity(environment = "staging"),
                    "deploymentId" to identity(deploymentId = "eu-central-frankfurt-01"),
                    "runId" to identity(runId = "another-run"),
                )

            substitutions.forEach { (component, substituted) ->
                val fixture = Fixture()
                val persisted = identity()
                val exception = fixture.suspendGoverned(persisted)

                assertThatThrownBy {
                    runBlocking {
                        withContext(GovernedRunScope(substituted)) {
                            fixture.engine.resumeApproval(fixture.resumeCommand(exception))
                        }
                    }
                }.isInstanceOf(GovernedRunContinuityException::class.java)
                    .withFailMessage("substituting $component must be rejected")
                    .hasMessageContaining(exception.approvalId)
                assertThat(fixture.tool.invocations)
                    .withFailMessage("a rejected resume must not execute the tool ($component)")
                    .isEqualTo(0)
            }
        }
    }

    @Test
    fun `a legacy suspension cannot be resumed from a governed execution`() {
        runBlocking {
            val fixture = Fixture(source = RunIdSource("legacy-run"))
            val exception = fixture.suspendLegacy()

            assertThatThrownBy {
                runBlocking {
                    withContext(GovernedRunScope(identity(runId = "legacy-run"))) {
                        fixture.engine.resumeApproval(fixture.resumeCommand(exception))
                    }
                }
            }.isInstanceOf(GovernedRunContinuityException::class.java)
            assertThat(fixture.tool.invocations).isEqualTo(0)
        }
    }

    @Test
    fun `a legacy suspension still resumes as before`() {
        runBlocking {
            val fixture = Fixture(source = RunIdSource("legacy-run"))
            val exception = fixture.suspendLegacy()

            val result = fixture.engine.resumeApproval(fixture.resumeCommand(exception))

            assertThat(result).isNotNull()
            assertThat(fixture.tool.observedIdentities).containsExactly(null)
        }
    }
}
