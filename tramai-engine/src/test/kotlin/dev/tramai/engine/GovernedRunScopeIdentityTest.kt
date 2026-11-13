@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.exception.ProviderException
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
import dev.tramai.core.observation.NoOpOperationObserver
import dev.tramai.core.observation.NoOpToolFailureDiagnosticObserver
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.engine.components.EngineComponentFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.7.1d — governed run identity reaches the engine on BOTH supported proxy paths.
 *
 * The engine must never mint a second run id inside a governed execution. Suspend
 * invocations inherit the caller's coroutine context; blocking invocations start their
 * own runBlocking context, so their attribution is bridged across the thread boundary.
 *
 * The observing provider runs INSIDE the engine's execution, so what it sees is the
 * identity the engine itself is about to use. Together with an identity source that
 * throws when sampled, each test establishes:
 * `EngineExecutionIdentity.workflowRunId == the canonical RunId` on the governed path.
 */
class GovernedRunScopeIdentityTest {
    @AiService
    interface ScopeIdentityService {
        @Operation(prompt = "Answer", model = "logical-model")
        suspend fun answerSuspend(input: String): String

        @Operation(prompt = "Answer", model = "logical-model")
        fun answerBlocking(input: String): String
    }

    /** Fails loudly if the engine tries to mint a run id inside a governed execution. */
    private class ForbiddenRunIdSource : EngineIdentitySource {
        var runIdSamples = 0
            private set

        override fun newWorkflowRunId(): String {
            runIdSamples++
            error("engine must not generate a workflow run id inside a governed execution")
        }

        override fun newCorrelationId(): String = "corr-governed"
    }

    private class GeneratedRunIdSource(
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

    private class CorrelationRecordingPolicyEngine : PolicyEngine {
        val seenCorrelationIds = mutableListOf<String>()

        override suspend fun evaluate(context: PolicyContext): PolicyDecision {
            seenCorrelationIds += context.correlationId
            return PolicyDecision.Allow
        }
    }

    /** Records the governed identity in force while the engine invokes the provider. */
    private class ObservingProvider(
        private val failWith: Throwable? = null,
    ) : ModelProvider {
        val observedIdentities = CopyOnWriteArrayList<GovernedRunIdentity?>()

        override suspend fun complete(request: ModelRequest): ModelResponse {
            observedIdentities += GovernedRunScope.currentThreadIdentity()
            failWith?.let { throw it }
            return ModelResponse(content = "answer")
        }

        override fun providerId(): String = "primary"
    }

    private fun identity(runId: String): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("claims"),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("claims-prod"),
                            version = ConfigurationVersion("17"),
                        ),
                    environmentId = EnvironmentId("production"),
                    deploymentId = DeploymentId("eu-west-amsterdam-01"),
                ),
            runId = RunId(runId),
        )

    private fun buildEngine(
        source: EngineIdentitySource,
        provider: ModelProvider,
        policyEngine: PolicyEngine = CorrelationRecordingPolicyEngine(),
    ): TramaiEngine {
        val components =
            EngineComponentFactory.create(
                providerRegistry =
                    dev.tramai.core.provider.ProviderRegistry
                        .singleProvider(provider),
                structuredOutputHandler = null,
                toolRegistry = ToolRegistry(),
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
                policyEngine = policyEngine,
                dlpInterceptor = NoOpDlpInterceptor,
                dlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter,
                toolResultFilteringSettings = ToolResultFilteringSettings(),
                engineEventObserver = NoOpEngineEventObserver,
                toolFailureDiagnosticObserver = NoOpToolFailureDiagnosticObserver,
                policyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
                suspendedInvocationStore = InMemorySuspendedInvocationStore(),
                approvalContinuationStore = null,
                toolArgumentsDigester = null,
                approvalGateCoordinator = null,
                approvalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
                clock = Clock.systemUTC(),
                identitySource = source,
            )
        return TramaiEngine(components)
    }

    // ── Propagation on both proxy paths ─────────────────────────────

    @Test
    fun `suspend proxy invocation sees the exact governed identity`() {
        runBlocking {
            val source = ForbiddenRunIdSource()
            val provider = ObservingProvider()
            val service = buildEngine(source, provider).create(ScopeIdentityService::class)
            val expected = identity("governed-run-suspend")

            val answer = withContext(GovernedRunScope(expected)) { service.answerSuspend("hello") }

            assertThat(answer).isEqualTo("answer")
            assertThat(provider.observedIdentities).containsExactly(expected)
            // Nothing was generated: the canonical RunId is the only possible source.
            assertThat(source.runIdSamples).isEqualTo(0)
        }
    }

    @Test
    fun `blocking proxy invocation sees the exact governed identity`() {
        runBlocking {
            val source = ForbiddenRunIdSource()
            val provider = ObservingProvider()
            val service = buildEngine(source, provider).create(ScopeIdentityService::class)
            val expected = identity("governed-run-blocking")

            val answer = withContext(GovernedRunScope(expected)) { service.answerBlocking("hello") }

            assertThat(answer).isEqualTo("answer")
            assertThat(provider.observedIdentities).containsExactly(expected)
            assertThat(source.runIdSamples).isEqualTo(0)
        }
    }

    @Test
    fun `legacy blocking invocation has no governed attribution and still generates its run id`() {
        runBlocking {
            val source = GeneratedRunIdSource("legacy-run-1")
            val provider = ObservingProvider()
            val service = buildEngine(source, provider).create(ScopeIdentityService::class)

            service.answerBlocking("hello")

            assertThat(provider.observedIdentities).containsExactly(null)
            assertThat(source.runIdSamples).isEqualTo(1)
        }
    }

    @Test
    fun `dispatcher switch preserves the governed identity on the blocking path`() {
        runBlocking {
            val source = ForbiddenRunIdSource()
            val provider = ObservingProvider()
            val service = buildEngine(source, provider).create(ScopeIdentityService::class)
            val expected = identity("governed-run-dispatcher")

            service.let {
                withContext(Dispatchers.IO + GovernedRunScope(expected)) {
                    it.answerBlocking("hello")
                }
            }

            assertThat(provider.observedIdentities).containsExactly(expected)
            assertThat(source.runIdSamples).isEqualTo(0)
        }
    }

    // ── No leakage between executions ───────────────────────────────

    @Test
    fun `consecutive governed runs on the same thread cannot leak identity`() {
        runBlocking {
            val source = ForbiddenRunIdSource()
            val provider = ObservingProvider()
            val service = buildEngine(source, provider).create(ScopeIdentityService::class)
            val first = identity("governed-run-first")
            val second = identity("governed-run-second")

            withContext(GovernedRunScope(first)) { service.answerBlocking("one") }
            assertThat(GovernedRunScope.currentThreadIdentity())
                .withFailMessage("the bridge must be clear once the governed execution ended")
                .isNull()

            withContext(GovernedRunScope(second)) { service.answerBlocking("two") }
            assertThat(GovernedRunScope.currentThreadIdentity()).isNull()

            assertThat(provider.observedIdentities).containsExactly(first, second)
        }
    }

    @Test
    fun `concurrent governed runs cannot cross-contaminate`() {
        runBlocking {
            val leftSource = ForbiddenRunIdSource()
            val leftProvider = ObservingProvider()
            val leftService = buildEngine(leftSource, leftProvider).create(ScopeIdentityService::class)
            val left = identity("governed-run-left")

            val rightSource = ForbiddenRunIdSource()
            val rightProvider = ObservingProvider()
            val rightService = buildEngine(rightSource, rightProvider).create(ScopeIdentityService::class)
            val right = identity("governed-run-right")

            val leftJob =
                launch(Dispatchers.Default) {
                    withContext(GovernedRunScope(left)) { leftService.answerSuspend("l") }
                }
            val rightJob =
                launch(Dispatchers.Default) {
                    withContext(GovernedRunScope(right)) { rightService.answerSuspend("r") }
                }
            leftJob.join()
            rightJob.join()

            assertThat(leftProvider.observedIdentities).containsExactly(left)
            assertThat(rightProvider.observedIdentities).containsExactly(right)
        }
    }

    @Test
    fun `a suspended governed run does not attribute an unrelated call on the same thread`() {
        runBlocking {
            val source = GeneratedRunIdSource("legacy-run-2")
            val provider = ObservingProvider()
            val service = buildEngine(source, provider).create(ScopeIdentityService::class)

            // A governed execution that is suspended mid-flight on this thread's event loop.
            val suspended =
                launch {
                    withContext(GovernedRunScope(identity("governed-run-suspended"))) { awaitCancellation() }
                }
            delay(20)

            // ... must not leak its identity into an unrelated, ungoverned blocking call.
            service.answerBlocking("unrelated")

            assertThat(provider.observedIdentities).containsExactly(null)
            assertThat(source.runIdSamples).isEqualTo(1)
            suspended.cancelAndJoin()
        }
    }

    @Test
    fun `failure restores the previous thread context`() {
        runBlocking {
            val source = ForbiddenRunIdSource()
            val provider = ObservingProvider(failWith = IllegalStateException("provider exploded"))
            val service = buildEngine(source, provider).create(ScopeIdentityService::class)

            assertThatThrownBy {
                runBlocking {
                    withContext(GovernedRunScope(identity("governed-run-failing"))) { service.answerSuspend("boom") }
                }
            }.isInstanceOf(ProviderException::class.java)
                .hasCauseInstanceOf(IllegalStateException::class.java)

            assertThat(GovernedRunScope.currentThreadIdentity())
                .withFailMessage("a failed governed execution must not leave its identity installed")
                .isNull()
        }
    }

    @Test
    fun `cancellation restores the previous thread context`() {
        runBlocking {
            val cancelled =
                launch {
                    withContext(GovernedRunScope(identity("governed-run-cancelled"))) { awaitCancellation() }
                }
            delay(20)
            cancelled.cancelAndJoin()

            assertThat(GovernedRunScope.currentThreadIdentity())
                .withFailMessage("a cancelled governed execution must not leave its identity installed")
                .isNull()
        }
    }
}
