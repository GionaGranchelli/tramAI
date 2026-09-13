package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
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
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.engine.components.EngineComponentFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock

/**
 * 0.7.1d — the engine must not invent a second run identity inside a governed run.
 *
 * Inside a governed execution the canonical RunId is authoritative; outside one the
 * existing generated-identity behaviour must be exactly as before.
 */
class GovernedRunScopeIdentityTest {
    @AiService
    interface ScopeIdentityService {
        @Operation(prompt = "Answer", model = "logical-model")
        suspend fun answer(input: String): String
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

    private class FixedProvider : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse = ModelResponse(content = "governed answer")

        override fun providerId(): String = "primary"
    }

    private val identity =
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
            runId = RunId("governed-run-1"),
        )

    private fun buildEngine(
        source: EngineIdentitySource,
        policyEngine: PolicyEngine,
    ): TramaiEngine {
        val components =
            EngineComponentFactory.create(
                providerRegistry =
                    dev.tramai.core.provider.ProviderRegistry
                        .singleProvider(FixedProvider()),
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

    @Test
    fun `a governed execution supplies the canonical run id and the engine mints none`() {
        runBlocking {
            val source = ForbiddenRunIdSource()
            val policy = CorrelationRecordingPolicyEngine()
            val service = buildEngine(source, policy).create(ScopeIdentityService::class)

            val answer = withContext(GovernedRunScope(identity)) { service.answer("hello") }

            assertThat(answer).isEqualTo("governed answer")
            // The identity source would have thrown had the engine minted its own run id:
            // inside a governed execution the canonical RunId is the only possible source.
            assertThat(source.runIdSamples).isEqualTo(0)
            // Correlation is still sampled — it is a different concept from run identity.
            assertThat(policy.seenCorrelationIds).isNotEmpty
            assertThat(policy.seenCorrelationIds).allMatch { it == "corr-governed" }
        }
    }

    @Test
    fun `outside a governed execution the engine keeps generating its own run id`() {
        runBlocking {
            val source = GeneratedRunIdSource("legacy-run-1")
            val policy = CorrelationRecordingPolicyEngine()
            val service = buildEngine(source, policy).create(ScopeIdentityService::class)

            service.answer("hello")

            assertThat(source.runIdSamples).isEqualTo(1)
            assertThat(policy.seenCorrelationIds).allMatch { it == "corr-legacy" }
        }
    }
}
