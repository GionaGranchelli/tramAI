package dev.tramai.engine.provider

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.NoOpOperationObserver
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.ProviderId
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderRoutingConfiguration
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.governance.MinimizationObligation
import dev.tramai.security.governance.MinimizationOutcome
import dev.tramai.security.governance.MinimizationRequirement
import dev.tramai.security.governance.NamedTrustZone
import dev.tramai.security.governance.ObligationMinimizer
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.TrustZoneName
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProviderInputTransportGateTest {
    private val workloadId =
        WorkloadDeploymentIdentity(
            WorkloadId("wl-1"),
            WorkloadConfigurationIdentity(ConfigurationId("cfg-1"), ConfigurationVersion("v1")),
            EnvironmentId("prod"),
            DeploymentId("dep-1"),
        )
    private val deployment =
        ProviderDeployment("eu-prod", "openai", NamedTrustZone(TrustZoneName("eu"), ProviderTrustZone.EU_CLOUD))

    private val minimizer =
        ObligationMinimizer { text ->
            val replaced = text.replace("sk-secret-12345", "[REDACTED]")
            MinimizationOutcome(replaced, if (replaced != text) 1 else 0, inspectionCompleted = true)
        }

    private fun baseRouting(): ProviderRoutingConfiguration =
        ProviderRoutingConfiguration(
            enabled = true,
            workloadZones = mapOf(workloadId to ProviderTrustZone.LOCAL),
            providerDeployments = mapOf("openai" to deployment),
            allowedZonePairs = setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.EU_CLOUD),
            rules =
                mapOf(
                    DataClassification.INTERNAL to
                        ClassificationRoutingRule(
                            allowedZones = setOf(ProviderTrustZone.LOCAL, ProviderTrustZone.EU_CLOUD),
                            allowedFallbackZones = emptySet(),
                        ),
                ),
        )

    @Test
    fun `production configuration without minimization policy fails closed`() {
        val config = ProviderGovernanceConfiguration.from(baseRouting())!!
        val gate = ProviderInputTransportGate(config)
        val request = ModelRequest(model = "gpt-4o", messages = listOf(Message.text("sk-secret-12345")))

        assertThrows<ProviderInputReleaseException> {
            gate.authorizeAndProject(request, deployment, DataClassification.INTERNAL, ProviderTrustZone.LOCAL)
        }
    }

    @Test
    fun `production configuration withMinimization projects secret and copy reverts to default-deny`() {
        val configured =
            baseRouting().withMinimization(
                minimizationPolicy = { _, _ ->
                    MinimizationRequirement.Required(setOf(MinimizationObligation.REDACT_SECRETS))
                },
                minimizers = mapOf(MinimizationObligation.REDACT_SECRETS to minimizer),
            )
        val gate = ProviderInputTransportGate(ProviderGovernanceConfiguration.from(configured)!!)
        val request = ModelRequest(model = "gpt-4o", messages = listOf(Message.text("leaked sk-secret-12345")))

        val projected =
            gate.authorizeAndProject(request, deployment, DataClassification.INTERNAL, ProviderTrustZone.LOCAL)
        assertEquals("leaked [REDACTED]", projected.messages.single().content)
        assertFalse(projected.toString().contains("sk-secret-12345"))

        val copied = configured.copy(enabled = true)
        val copiedGate = ProviderInputTransportGate(ProviderGovernanceConfiguration.from(copied)!!)
        assertThrows<ProviderInputReleaseException> {
            copiedGate.authorizeAndProject(request, deployment, DataClassification.INTERNAL, ProviderTrustZone.LOCAL)
        }
    }

    private fun syncCoordinator(
        plan: ProviderRoutingPlan,
        governance: ProviderGovernanceConfiguration,
        interceptor: OperationInterceptor = object : OperationInterceptor {},
        breaker: ProviderCircuitBreaker = ProviderCircuitBreaker(CircuitBreakerSettings()),
        observer: dev.tramai.core.observation.OperationObserver = NoOpOperationObserver,
    ): ProviderExecutionCoordinator {
        val retryPolicy =
            ProviderRetryPolicy(ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.0)) { 0.0 })
        val executor =
            ProviderAttemptExecutor(
                "service",
                observer,
                interceptor,
                breaker,
                retryPolicy,
                ProviderAuthorizationService(
                    dev.tramai.engine.ModelRegistryEnforcer(
                        object : dev.tramai.core.model.ModelRegistry {
                            override suspend fun findApprovedModel(
                                providerId: String,
                                modelName: String,
                            ) = dev.tramai.core.model.RegisteredModel(
                                "id",
                                providerId,
                                modelName,
                                "r1",
                                dev.tramai.core.model.ModelArtifactDigest
                                    .of("sha256:${"a".repeat(64)}"),
                                true,
                            )
                        },
                        dev.tramai.core.model
                            .ModelRegistrySettings(enabled = true),
                    ),
                ),
                ProviderInvocationGate { _, _, _, _ -> },
                ProviderResponseSanitizer { response, _, _, _, _ -> response },
            )
        return ProviderExecutionCoordinator(
            ProviderCoordinationDependencies(
                plan,
                breaker,
                executor,
                ProviderFallbackPolicy(),
                ProviderResolutionGate { _, _, _ -> },
                ProviderFallbackGate { _ -> },
                governance,
            ),
        )
    }

    private fun syncRequest(retries: Int = 0) =
        ProviderExecutionRequest(
            componentOperation(retries),
            listOf(Message.text("hello")),
            AttemptCounter(),
            "cid",
            ExecutionSecurityContext(
                dataClassification = DataClassification.INTERNAL,
                classificationSource = ClassificationSource.DECLARED,
            ),
            ProviderRouteGate { },
            dev.tramai.core.identity.GovernedRunIdentity(
                workloadId,
                dev.tramai.core.identity
                    .RunId("run-1"),
            ),
        )

    @Test
    fun `sync coordinator projects interceptor-injected secret before provider complete`() {
        runBlocking {
            val captured = mutableListOf<ModelRequest>()
            val provider =
                object : ModelProvider {
                    override suspend fun complete(request: ModelRequest): ModelResponse {
                        captured.add(request)
                        return ModelResponse("ok")
                    }
                }
            val configured =
                baseRouting().withMinimization(
                    minimizationPolicy = { _, _ ->
                        MinimizationRequirement.Required(setOf(MinimizationObligation.REDACT_SECRETS))
                    },
                    minimizers = mapOf(MinimizationObligation.REDACT_SECRETS to minimizer),
                )
            val injectingInterceptor =
                object : OperationInterceptor {
                    override fun interceptRequest(
                        context: OperationCallContext,
                        messages: List<Message>,
                    ): List<Message> = messages + Message.text("leaked sk-secret-12345")
                }
            val plan =
                ProviderRoutingPlan
                    .builder()
                    .provider("openai", provider)
                    .defaultProvider("openai")
                    .build()
            val coord =
                syncCoordinator(plan, ProviderGovernanceConfiguration.from(configured)!!, injectingInterceptor)

            coord.execute(syncRequest())
            assertEquals(1, captured.size)
            assertEquals(
                "leaked [REDACTED]",
                captured
                    .single()
                    .messages
                    .last()
                    .content,
            )
            assertFalse(captured.single().toString().contains("sk-secret-12345"))
        }
    }

    @Test
    fun `sync coordinator missing policy makes zero provider calls without retry or breaker poisoning`() {
        runBlocking {
            val captured = mutableListOf<ModelRequest>()
            val provider =
                object : ModelProvider {
                    override suspend fun complete(request: ModelRequest): ModelResponse {
                        captured.add(request)
                        return ModelResponse("ok")
                    }
                }
            val plan =
                ProviderRoutingPlan
                    .builder()
                    .provider("openai", provider)
                    .defaultProvider("openai")
                    .build()
            val sink = OrderedSink()
            val observer = AttemptRecordingObserver(sink)
            val breaker = RecordingCircuitBreaker(CircuitBreakerSettings())
            val coord =
                syncCoordinator(
                    plan,
                    ProviderGovernanceConfiguration.from(baseRouting())!!,
                    breaker = breaker,
                    observer = observer,
                )

            assertThrows<ProviderInputReleaseException> {
                coord.execute(syncRequest(retries = 2))
            }
            assertEquals(0, captured.size)
            assertEquals(1, observer.attempts.size)
            assertEquals(null, breaker.openUntilMillis("openai"))
        }
    }

    @Test
    fun `sync retry reevaluates release policy and projection on every attempt`() {
        runBlocking {
            val captured = mutableListOf<ModelRequest>()
            var policyEvaluations = 0
            var minimizerCalls = 0
            val countingMinimizer =
                ObligationMinimizer { text ->
                    minimizerCalls++
                    minimizer.minimize(text)
                }
            val injectingInterceptor =
                object : OperationInterceptor {
                    override fun interceptRequest(
                        context: OperationCallContext,
                        messages: List<Message>,
                    ): List<Message> = messages + Message.text("leaked sk-secret-12345")
                }
            val provider =
                object : ModelProvider {
                    override suspend fun complete(request: ModelRequest): ModelResponse {
                        captured.add(request)
                        if (captured.size == 1) {
                            throw dev.tramai.core.exception
                                .ProviderException("transient", retryable = true)
                        }
                        return ModelResponse("ok")
                    }
                }
            val configured =
                baseRouting().withMinimization(
                    minimizationPolicy = { _, _ ->
                        policyEvaluations++
                        MinimizationRequirement.Required(setOf(MinimizationObligation.REDACT_SECRETS))
                    },
                    minimizers = mapOf(MinimizationObligation.REDACT_SECRETS to countingMinimizer),
                )
            val plan =
                ProviderRoutingPlan
                    .builder()
                    .provider("openai", provider)
                    .defaultProvider("openai")
                    .build()
            val coord =
                syncCoordinator(plan, ProviderGovernanceConfiguration.from(configured)!!, injectingInterceptor)

            coord.execute(syncRequest(retries = 1))
            assertEquals(2, captured.size)
            assertEquals(2, policyEvaluations)
            assertEquals(4, minimizerCalls)
            captured.forEach { req ->
                assertEquals("leaked [REDACTED]", req.messages.last().content)
                assertFalse(req.toString().contains("sk-secret-12345"))
            }
        }
    }
}
