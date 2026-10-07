package dev.tramai.engine.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.governance.NamedTrustZone
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.TrustZonePolicy
import dev.tramai.security.governance.TrustZoneName
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.catchThrowable

/**
 * 0.7.3h execution-path proof.
 *
 * Every case drives the real [ProviderExecutionCoordinator], not the security stages in isolation,
 * and asserts on provider invocation counts: the invariant under test is that no provider is invoked
 * unless that exact candidate was selected from a viable envelope derived for the same request.
 */
class ProviderGovernedExecutionPathTest {
    private val invoked = mutableListOf<String>()

    // ---- 1. happy path ---------------------------------------------------------------------------

    @Test
    fun `a single authorized viable candidate is selected and invoked exactly once`() {
        runBlocking {
            val plan = planOf(chain = listOf("alpha"), providers = mapOf("alpha" to provider("alpha")))
            val coordinator = coordinator(plan)

            assertThat(coordinator.execute(request()).response.content).isEqualTo("alpha")
            assertThat(invoked).containsExactly("alpha")
        }
    }

    // ---- 2. unauthorized primary ----------------------------------------------------------------

    @Test
    fun `an unauthorized primary is never invoked and the authorized fallback is selected`() {
        runBlocking {
            // "global" sits in a zone no permitted pair allows, so authorization refuses it.
            val plan = planOf(
                chain = listOf("global", "alpha"),
                providers = mapOf("global" to provider("global"), "alpha" to provider("alpha")),
            )

            assertThat(coordinator(plan).execute(request()).response.content).isEqualTo("alpha")
            assertThat(invoked).containsExactly("alpha")
        }
    }

    // ---- 3. authorized but unavailable ----------------------------------------------------------

    @Test
    fun `an authorized but unavailable primary is never invoked and the viable candidate is used`() {
        runBlocking {
            val plan = planOf(
                chain = listOf("beta", "alpha"),
                providers = mapOf("beta" to provider("beta"), "alpha" to provider("alpha")),
            )
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(true, 1, 60_000)) { 0L }
            breaker.onFailure(
                (breaker.beforeCall("beta") as CircuitBreakerAdmission.Allowed).permit,
                ProviderException("down", retryable = true),
            )
            invoked.clear()

            assertThat(coordinator(plan, breaker = breaker).execute(request()).response.content).isEqualTo("alpha")
            assertThat(invoked).containsExactly("alpha")
        }
    }

    // ---- 4. nothing authorized ------------------------------------------------------------------

    @Test
    fun `nothing authorized fails closed with zero provider invocations`() {
        runBlocking {
            // No permitted workload-zone to provider-zone pair at all: every candidate is refused.
            val plan = planOf(chain = listOf("alpha"), providers = mapOf("alpha" to provider("alpha")))
            val coordinator = coordinator(plan, governance = governance(pairs = emptySet()))

            val thrown = catchThrowable { runBlocking { coordinator.execute(request()) } }

            assertThat(thrown).isInstanceOf(ProviderException::class.java)
            assertThat(thrown.message).contains("No provider candidate is authorized")
            assertThat(invoked).isEmpty()
        }
    }

    // ---- 5. nothing viable ----------------------------------------------------------------------

    @Test
    fun `nothing viable fails closed with zero provider invocations`() {
        runBlocking {
            val plan = planOf(chain = listOf("alpha"), providers = mapOf("alpha" to provider("alpha")))
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(true, 1, 60_000)) { 0L }
            breaker.onFailure(
                (breaker.beforeCall("alpha") as CircuitBreakerAdmission.Allowed).permit,
                ProviderException("down", retryable = true),
            )
            invoked.clear()
            val coordinator = coordinator(plan, breaker = breaker)

            val thrown = catchThrowable { runBlocking { coordinator.execute(request()) } }

            assertThat(thrown).isNotNull()
            assertThat(invoked).isEmpty()
        }
    }

    // ---- 6. fallback narrowing ------------------------------------------------------------------

    @Test
    fun `fallback narrows the envelope and reselects from it`() {
        runBlocking {
            val plan = planOf(
                chain = listOf("alpha", "beta"),
                providers = mapOf(
                    "alpha" to provider("alpha") { throw ProviderException("down", retryable = true) },
                    "beta" to provider("beta"),
                ),
            )

            assertThat(coordinator(plan).execute(request()).response.content).isEqualTo("beta")
            assertThat(invoked).containsExactly("alpha", "beta")
        }
    }

    // ---- 7. forbidden configured fallback -------------------------------------------------------

    @Test
    fun `a configured next route outside the envelope is never invoked`() {
        runBlocking {
            // Configured order is alpha then global; global is forbidden by zone. Fallback must
            // narrow to nothing, never advance to the next configured route.
            val plan = planOf(
                chain = listOf("alpha", "global"),
                providers = mapOf(
                    "alpha" to provider("alpha") { throw ProviderException("down", retryable = true) },
                    "global" to provider("global"),
                ),
            )

            catchThrowable { runBlocking { coordinator(plan).execute(request()) } }

            assertThat(invoked).containsExactly("alpha")
            assertThat(invoked).doesNotContain("global")
        }
    }

    // ---- 8. preference cannot inject ------------------------------------------------------------

    @Test
    fun `a preference for a forbidden candidate cannot inject it`() {
        runBlocking {
            val plan = planOf(
                chain = listOf("global", "alpha"),
                providers = mapOf("global" to provider("global"), "alpha" to provider("alpha")),
            )
            // The preference asks for the forbidden candidate first, every time.
            val hostile = ProviderSelectionPreference { _, _ -> selectedButForbidden }

            val thrown = catchThrowable { runBlocking { coordinator(plan, preference = hostile).execute(request()) } }

            assertThat(thrown).isNotNull()
            assertThat(invoked).isEmpty()
        }
    }

    // ---- 9. circuit opens before execution ------------------------------------------------------

    @Test
    fun `an unavailable candidate is never invoked and the next candidate comes from selection`() {
        runBlocking {
            val plan = planOf(
                chain = listOf("beta", "alpha"),
                providers = mapOf("beta" to provider("beta"), "alpha" to provider("alpha")),
            )
            // beta's circuit is open before execution starts.
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(true, 1, 60_000)) { 0L }
            breaker.beforeCall("beta")
            breaker.onFailure(
                (breaker.beforeCall("beta") as CircuitBreakerAdmission.Allowed).permit,
                ProviderException("down", retryable = true),
            )
            invoked.clear()

            assertThat(coordinator(plan, breaker = breaker).execute(request()).response.content).isEqualTo("alpha")
            assertThat(invoked).containsExactly("alpha")
        }
    }

    // ---- 10. circuit state changes after selection ----------------------------------------------

    @Test
    fun `a selected candidate that loses admission narrows the envelope and reselects`() {
        runBlocking {
            val plan = planOf(
                chain = listOf("alpha", "beta"),
                providers = mapOf("alpha" to provider("alpha"), "beta" to provider("beta")),
            )
            // Viability observes the circuit as available, admission then rejects the first
            // candidate: the race must narrow authority, never widen it.
            val rejecting =
                object : ProviderCircuitBreaker(CircuitBreakerSettings(true, 1, 60_000), { 0L }) {
                    private var rejectNext = true

                    override fun beforeCall(providerId: String): CircuitBreakerAdmission =
                        if (rejectNext) {
                            rejectNext = false
                            CircuitBreakerAdmission.Rejected(1L)
                        } else {
                            super.beforeCall(providerId)
                        }

                    override fun openUntilMillis(providerId: String): Long? = null
                }

            assertThat(coordinator(plan, breaker = rejecting).execute(request()).response.content).isEqualTo("beta")
            assertThat(invoked).containsExactly("beta")
        }
    }

    // ---- 11. same-route retry --------------------------------------------------------------------

    @Test
    fun `retry of the selected route does not select another candidate`() {
        runBlocking {
            var calls = 0
            val plan = planOf(
                chain = listOf("alpha", "beta"),
                providers = mapOf(
                    "alpha" to provider("alpha") { if (calls++ == 0) throw ProviderException("transient", retryable = true) else ModelResponse("alpha-ok") },
                    "beta" to provider("beta"),
                ),
            )

            assertThat(coordinator(plan).execute(request(retries = 1)).response.content).isEqualTo("alpha-ok")
            assertThat(invoked).containsExactly("alpha", "alpha")
        }
    }

    // ---- 12. retry exhaustion then fallback -----------------------------------------------------

    @Test
    fun `retry exhaustion narrows and the next candidate is selected from the envelope`() {
        runBlocking {
            val plan = planOf(
                chain = listOf("alpha", "beta"),
                providers = mapOf(
                    "alpha" to provider("alpha") { throw ProviderException("down", retryable = true) },
                    "beta" to provider("beta"),
                ),
            )

            assertThat(coordinator(plan).execute(request(retries = 1)).response.content).isEqualTo("beta")
            assertThat(invoked).containsExactly("alpha", "alpha", "beta")
        }
    }

    // ---- 13. strategy outside viable ------------------------------------------------------------

    @Test
    fun `an escape attempt outside the viable envelope invokes no provider`() {
        runBlocking {
            val plan = planOf(chain = listOf("alpha"), providers = mapOf("alpha" to provider("alpha")))
            val escape = ProviderSelectionPreference { _, _ -> selectedButForbidden }

            val thrown = catchThrowable { runBlocking { coordinator(plan, preference = escape).execute(request()) } }

            assertThat(thrown).isInstanceOf(ProviderException::class.java)
            assertThat(thrown.message).contains("STRATEGY_OUTSIDE_VIABLE_SET")
            assertThat(invoked).isEmpty()
        }
    }

    // ---- 14. strategy throws --------------------------------------------------------------------

    @Test
    fun `a throwing preference fails closed without fallback or invocation`() {
        runBlocking {
            val plan = planOf(
                chain = listOf("alpha", "beta"),
                providers = mapOf("alpha" to provider("alpha"), "beta" to provider("beta")),
            )
            val throwing = ProviderSelectionPreference { _, _ -> throw IllegalStateException("hostile preference") }

            val thrown = catchThrowable { runBlocking { coordinator(plan, preference = throwing).execute(request()) } }

            assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
            assertThat(invoked).isEmpty()
        }
    }

    // ---- 15. candidate to route mapping ---------------------------------------------------------

    @Test
    fun `two deployments of one brand do not collapse`() {
        runBlocking {
            // Same brand, two registrations, two zones. Only the LOCAL one may be used for a
            // LOCAL workload, and the invoked route must be the one that was selected.
            val plan = planOf(
                chain = listOf("brand-global", "brand-local"),
                providers = mapOf("brand-global" to provider("brand-global"), "brand-local" to provider("brand-local")),
            )

            assertThat(coordinator(plan).execute(request()).response.content).isEqualTo("brand-local")
            assertThat(invoked).containsExactly("brand-local")
        }
    }

    // ---- 16. registration cannot be bypassed ----------------------------------------------------

    @Test
    fun `a route without an authoritative deployment never executes`() {
        runBlocking {
            val plan = planOf(chain = listOf("unknown"), providers = mapOf("unknown" to provider("unknown")))
            // The routing plan names the provider, but the composition has no deployment for it.
            val coordinator = coordinator(plan, governance = governance(deployments = emptyMap()))

            catchThrowable { runBlocking { coordinator.execute(request()) } }

            assertThat(invoked).isEmpty()
        }
    }

    // ---- 17. capability cannot be bypassed ------------------------------------------------------

    @Test
    fun `a provider missing a required capability never executes`() {
        runBlocking {
            val plan = planOf(chain = listOf("alpha"), providers = mapOf("alpha" to provider("alpha")))
            val coordinator =
                coordinator(
                    plan,
                    governance = governance(capabilities = setOf(ProviderCapability.TOOL_CALLING)),
                )

            catchThrowable { runBlocking { coordinator.execute(request()) } }

            assertThat(invoked).isEmpty()
        }
    }

    // ---- 18. absent governance ------------------------------------------------------------------

    @Test
    fun `execution without governance inputs invokes no provider`() {
        runBlocking {
            val plan = planOf(chain = listOf("alpha"), providers = mapOf("alpha" to provider("alpha")))

            val thrown = catchThrowable { runBlocking { coordinator(plan).execute(request(run = null)) } }

            assertThat(thrown).isInstanceOf(ProviderException::class.java)
            assertThat(invoked).isEmpty()
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private val selectedButForbidden = forbiddenCandidate()

    private fun forbiddenCandidate() =
        dev.tramai.security.governance.ProviderCandidate(
            providerId = "global",
            modelId = "model",
            deployment = deployment("global", ProviderTrustZone.GLOBAL_CLOUD),
        )

    private fun provider(name: String, block: suspend () -> ModelResponse = { ModelResponse(name) }) =
        object : ModelProvider {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                invoked += name
                return block()
            }

            override fun providerId() = name
        }

    private fun deployment(providerId: String, zone: ProviderTrustZone) =
        ProviderDeployment("dep-$providerId", providerId, NamedTrustZone(TrustZoneName("zone-$providerId"), zone))

    private fun planOf(chain: List<String>, providers: Map<String, ModelProvider>): ProviderRoutingPlan {
        val builder = ProviderRoutingPlan.builder()
        providers.forEach { (name, instance) -> builder.provider(name, instance) }
        builder.model("model", chain.first())
        chain.drop(1).forEach { builder.fallbackProvider("model", it) }
        return builder.build()
    }

    private fun governance(
        deployments: Map<String, ProviderTrustZone> = mapOf("alpha" to ProviderTrustZone.LOCAL, "beta" to ProviderTrustZone.LOCAL, "brand-local" to ProviderTrustZone.LOCAL, "brand-global" to ProviderTrustZone.GLOBAL_CLOUD),
        pairs: Set<Pair<ProviderTrustZone, ProviderTrustZone>> = setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.LOCAL),
        capabilities: Set<ProviderCapability> = emptySet(),
    ) = ProviderGovernanceConfiguration(
        rules =
            mapOf(
                DataClassification.INTERNAL to
                    ClassificationRoutingRule(
                        allowedZones = setOf(ProviderTrustZone.LOCAL),
                        allowedFallbackZones = emptySet(),
                    ),
            ),
        trustZonePolicy = TrustZonePolicy(pairs),
        deploymentOf = { providerId -> deployments[providerId]?.let { deployment(providerId, it) } },
        requiredCapabilities = capabilities,
    )

    private val workloadIdentity =
        WorkloadDeploymentIdentity(
            WorkloadId("workload"),
            WorkloadConfigurationIdentity(ConfigurationId("config"), ConfigurationVersion("1")),
            EnvironmentId("env"),
            DeploymentId("deployment"),
        )

    private fun request(
        retries: Int = 0,
        run: ProviderRunGovernance? = ProviderRunGovernance(workloadIdentity, ProviderTrustZone.LOCAL),
    ) = ProviderExecutionRequest(
        componentOperation(retries),
        emptyList<Message>(),
        AttemptCounter(),
        "cid",
        ExecutionSecurityContext(
            dataClassification = DataClassification.INTERNAL,
            classificationSource = ClassificationSource.DECLARED,
        ),
        ProviderRouteGate {},
        run,
    )

    private fun coordinator(
        plan: ProviderRoutingPlan,
        breaker: ProviderCircuitBreaker = ProviderCircuitBreaker(CircuitBreakerSettings()),
        governance: ProviderGovernanceConfiguration = governance(),
        preference: ProviderSelectionPreference = ProviderSelectionPreference.CONFIGURED_ORDER,
    ): ProviderExecutionCoordinator {
        val observer = dev.tramai.core.observation.OperationObserver { RecordingObservation() }
        val attempt =
            ProviderAttemptExecutor(
                "service",
                observer,
                object : dev.tramai.core.observation.OperationInterceptor {},
                breaker,
                ProviderRetryPolicy(ProviderRetryDelayPolicy(RetryPolicySettings(jitterRatio = 0.0)) { 0.0 }),
                authorization(),
                ProviderInvocationGate { _, _, _, _ -> },
                ProviderResponseSanitizer { response, _, _, _, _, _, _ -> response },
            )
        return ProviderExecutionCoordinator(
            plan,
            breaker,
            attempt,
            ProviderFallbackPolicy(),
            ProviderResolutionGate { _, _, _ -> },
            ProviderFallbackGate { _, _, _, _, _, _ -> },
            governance,
            preference,
        )
    }

    /** Authorization fake that approves the requested provider and model. */
    private fun authorization() =
        ProviderAuthorizationService(
            ModelRegistryEnforcer(
                object : ModelRegistry {
                    override suspend fun findApprovedModel(providerId: String, modelName: String) =
                        RegisteredModel("id", providerId, modelName, "r1", ModelArtifactDigest.of("sha256:${"a".repeat(64)}"), true)
                },
                ModelRegistrySettings(enabled = true),
            ),
        )
}
