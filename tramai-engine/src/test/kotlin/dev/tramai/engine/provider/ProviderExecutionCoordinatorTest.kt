package dev.tramai.engine.provider

import dev.tramai.core.exception.ModelRegistryContractViolationException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.governance.NamedTrustZone
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.TrustZoneName
import dev.tramai.security.governance.TrustZonePolicy
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderExecutionCoordinatorTest {
    @Test
    fun `primary success returns without fallback and publishes route event`() {
        runBlocking {
            val observation = RecordingObservation()
            val primary = FakeProvider { ModelResponse("primary") }
            val coordinator = coordinator(plan(primary), observation)
            assertThat(coordinator.execute(executionRequest()).response.content).isEqualTo("primary")
            assertThat(observation.events.single().first).isEqualTo("tramai.route.selected")
            assertThat(
                observation.events.single().second,
            ).containsEntry("provider_id", "primary")
                .containsEntry("route_index", 0L)
                .containsEntry("is_fallback", false)
        }
    }

    @Test
    fun `retry succeeds before fallback`() {
        runBlocking {
            var calls = 0
            val primary =
                FakeProvider {
                    if (calls++ ==
                        0
                    ) {
                        throw ProviderException("transient", retryable = true)
                    } else {
                        ModelResponse("ok")
                    }
                }
            assertThat(
                coordinator(plan(primary), RecordingObservation())
                    .execute(executionRequest(retries = 1))
                    .response
                    .content,
            ).isEqualTo("ok")
            assertThat(calls).isEqualTo(2)
        }
    }

    @Test
    fun `exhausted primary invokes fallback with ordered transition and continuous attempts`() {
        runBlocking {
            val order = mutableListOf<String>()
            val primary =
                FakeProvider {
                    order += "primary"
                    throw ProviderException("down", retryable = true)
                }
            val secondary =
                FakeProvider {
                    order +=
                        "secondary"
                    ; ModelResponse("fallback")
                }
            val observations = mutableListOf<RecordingObservation>()
            val coordinator =
                coordinator(
                    plan(primary, secondary),
                    observerFactory = { RecordingObservation().also(observations::add) },
                    fallback =
                        ProviderFallbackGate { _, _, _, _, _, _ ->
                            order +=
                                "fallback-gate"
                        },
                )
            assertThat(coordinator.execute(executionRequest(retries = 1)).response.content).isEqualTo("fallback")
            assertThat(order).containsExactly("primary", "primary", "fallback-gate", "secondary")
            assertThat(observations.map { it.routeSelected()["route_index"] }).containsExactly(0L, 0L, 1L)
        }
    }

    @Test
    fun `circuit open primary skips provider and invokes fallback`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(true, 1, 60_000), { now })
            breaker.onFailure(
                (breaker.beforeCall("primary") as dev.tramai.engine.CircuitBreakerAdmission.Allowed).permit,
                ProviderException("down", retryable = true),
            )
            var primaryCalls = 0
            val coordinator =
                coordinator(
                    plan(
                        FakeProvider {
                            primaryCalls++
                            ModelResponse("bad")
                        },
                        FakeProvider { ModelResponse("fallback") },
                    ),
                    RecordingObservation(),
                    breaker,
                )
            assertThat(coordinator.execute(executionRequest()).response.content).isEqualTo("fallback")
            assertThat(primaryCalls).isZero()
        }
    }

    @Test
    fun `no route throws configuration exception unchanged from resolution`() {
        runBlocking {
            val coordinator = coordinator(ProviderRoutingPlan.builder().build())
            val thrown =
                try {
                    coordinator.execute(executionRequest())
                    null
                } catch (t: Throwable) {
                    t
                }
            assertThat(thrown).isInstanceOf(dev.tramai.core.exception.ConfigurationException::class.java)
            assertThat(thrown!!.message).contains("No provider is registered for model")
        }
    }

    @Test
    fun `fallback route events carry is_fallback true and shared attempt numbering continues`() {
        runBlocking {
            val observations = mutableListOf<RecordingObservation>()
            val reasons = mutableListOf<String>()
            val primary = FakeProvider { throw ProviderException("down", retryable = true) }
            val secondary = FakeProvider { ModelResponse("fallback") }
            val coordinator =
                coordinator(
                    plan(primary, secondary),
                    observerFactory = { RecordingObservation().also(observations::add) },
                    fallback = ProviderFallbackGate { _, _, _, _, reason, _ -> reasons += reason },
                )
            assertThat(coordinator.execute(executionRequest(retries = 1)).response.content).isEqualTo("fallback")
            assertThat(observations.map { it.routeSelected()["is_fallback"] }).containsExactly(false, false, true)
            assertThat(observations.map { it.routeSelected()["route_index"] }).containsExactly(0L, 0L, 1L)
            // The transition is attributed to the retryable provider failure that caused it, never to a
            // circuit-open exclusion: the reason travels with the transition, so a misattributed reason
            // is a wrong explanation of why execution moved.
            assertThat(reasons).containsExactly("provider-failure")
        }
    }

    @Test
    fun `fallback denial retains original failure`() {
        val original = ProviderException("down", retryable = true)
        val coordinator =
            coordinator(
                plan(FakeProvider { throw original }, FakeProvider { ModelResponse("never") }),
                RecordingObservation(),
                fallback =
                    ProviderFallbackGate {
                        _,
                        _,
                        _,
                        _,
                        _,
                        _,
                        ->
                        throw PolicyViolationException(PolicyDecision.Deny("denied", "denied"))
                    },
            )
        val thrown =
            org.assertj.core.api.Assertions
                .catchThrowable { runBlocking { coordinator.execute(executionRequest()) } }
        assertThat(thrown).isInstanceOf(PolicyViolationException::class.java)
        assertThat(thrown!!.suppressed).contains(original)
    }

    private fun plan(
        primary: FakeProvider,
        secondary: FakeProvider? = null,
    ) = ProviderRoutingPlan
        .builder()
        .provider("primary", primary)
        .apply {
            if (secondary !=
                null
            ) {
                provider("secondary", secondary)
            }
        }.model("model", "primary")
        .apply {
            if (secondary !=
                null
            ) {
                fallbackProvider("model", "secondary")
            }
        }.build()

    // The governed facts the execution path authorizes from. Configured capability alone no longer
    // grants authority: these tests declare the workload deployment's zone and its provider
    // deployments explicitly, and the engine is expected to refuse without them.
    private val workloadIdentity =
        WorkloadDeploymentIdentity(
            WorkloadId("workload"),
            WorkloadConfigurationIdentity(ConfigurationId("config"), ConfigurationVersion("1")),
            EnvironmentId("env"),
            DeploymentId("deployment"),
        )
    private val governedRun = GovernedRunIdentity(workloadIdentity, RunId("run"))
    private val governedDeployments =
        listOf("primary", "secondary").associateWith { id ->
            ProviderDeployment("dep-$id", id, NamedTrustZone(TrustZoneName("zone-$id"), ProviderTrustZone.LOCAL))
        }
    private val governance =
        ProviderGovernanceConfiguration(
            workloadZones = mapOf(workloadIdentity to ProviderTrustZone.LOCAL),
            rules =
                mapOf(
                    DataClassification.INTERNAL to
                        ClassificationRoutingRule(setOf(ProviderTrustZone.LOCAL), emptySet()),
                ),
            trustZonePolicy = TrustZonePolicy(setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.LOCAL)),
            deploymentOf = { governedDeployments[it] },
        )

    private fun executionRequest(retries: Int = 0) =
        ProviderExecutionRequest(
            componentOperation(
                retries,
            ),
            emptyList(),
            AttemptCounter(),
            "cid",
            ExecutionSecurityContext(
                dataClassification = DataClassification.INTERNAL,
                classificationSource = ClassificationSource.DECLARED,
            ),
            ProviderRouteGate {
            },
            governedRun,
        )

    private fun coordinator(
        plan: ProviderRoutingPlan,
        observation: RecordingObservation = RecordingObservation(),
        breaker: ProviderCircuitBreaker = ProviderCircuitBreaker(CircuitBreakerSettings()),
        observerFactory: (() -> RecordingObservation)? = null,
        fallback: ProviderFallbackGate = ProviderFallbackGate { _, _, _, _, _, _ -> },
    ): ProviderExecutionCoordinator {
        val observer =
            dev.tramai.core.observation
                .OperationObserver { observerFactory?.invoke() ?: observation }
        val attempt =
            ProviderAttemptExecutor(
                "service",
                observer,
                object : dev.tramai.core.observation.OperationInterceptor {},
                breaker,
                ProviderRetryPolicy(
                    dev.tramai.engine.provider.ProviderRetryDelayPolicy(
                        dev.tramai.engine.RetryPolicySettings(jitterRatio = 0.0),
                    ) {
                        0.0
                    },
                ),
                permissiveAuthorization(),
                ProviderInvocationGate {
                    _,
                    _,
                    _,
                    _,
                    ->
                },
                ProviderResponseSanitizer { response, _, _, _, _, _, _ -> response },
            )
        return ProviderExecutionCoordinator(
            dependencies =
                ProviderCoordinationDependencies(
                    plan,
                    breaker,
                    attempt,
                    ProviderFallbackPolicy(),
                    ProviderResolutionGate {
                        _,
                        _,
                        _,
                        ->
                    },
                    fallback,
                    governance,
                ),
        )
    }

    /** Authorization fake returning a model that always matches the requested provider/model. */
    private fun permissiveAuthorization() =
        ProviderAuthorizationService(
            ModelRegistryEnforcer(
                object : ModelRegistry {
                    override suspend fun findApprovedModel(
                        providerId: String,
                        modelName: String,
                    ) = RegisteredModel(
                        "id",
                        providerId,
                        modelName,
                        "r1",
                        ModelArtifactDigest
                            .of("sha256:${"a".repeat(64)}"),
                        true,
                    )
                },
                ModelRegistrySettings(enabled = true),
            ),
        )
}
