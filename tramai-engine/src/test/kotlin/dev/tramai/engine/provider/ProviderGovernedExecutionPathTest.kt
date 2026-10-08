package dev.tramai.engine.provider

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
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
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.planning.OperationDefinitionCompiler
import dev.tramai.engine.planning.OperationFingerprintFactory
import dev.tramai.engine.planning.ServiceDefinitionCompiler
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.governance.NamedTrustZone
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.TrustZoneName
import dev.tramai.security.governance.TrustZonePolicy
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

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
    fun `a pre-open primary circuit consults the continuation policy and the viable fallback runs`() {
        runBlocking {
            val plan =
                planOf(
                    chain = listOf("alpha", "beta"),
                    providers = mapOf("alpha" to provider("alpha"), "beta" to provider("beta")),
                )
            // alpha is authorized but not viable: its circuit is already open at the snapshot, so the
            // transition past it to the viable fallback is a continuation, not an execution.
            val breaker = openCircuitFor("alpha")
            val response = coordinator(plan, breaker = breaker).execute(request())
            assertThat(response.response.content).isEqualTo("beta")
            // alpha never runs, and the excluded candidate is not restored by the continuation.
            assertThat(invoked).containsExactly("beta")
        }
    }

    @Test
    fun `an exclusion positioned after the selected candidate does not gate it`() {
        runBlocking {
            val plan =
                planOf(
                    chain = listOf("alpha", "beta"),
                    providers = mapOf("alpha" to provider("alpha"), "beta" to provider("beta")),
                )
            // beta is excluded but nothing reaches it: alpha is selected and runs, so no transition
            // past beta exists and no continuation is authorized on its behalf.
            val breaker = openCircuitFor("beta")
            val response = coordinator(plan, breaker = breaker).execute(request())
            assertThat(response.response.content).isEqualTo("alpha")
            assertThat(invoked).containsExactly("alpha")
        }
    }

    private fun openCircuitFor(providerId: String): ProviderCircuitBreaker {
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 60_000L))
        breaker.onFailure(
            (breaker.beforeCall(providerId) as CircuitBreakerAdmission.Allowed).permit,
            ProviderException("down", retryable = true),
        )
        return breaker
    }

    @Test
    fun `an unclassified synchronous request is refused and no provider is invoked`() {
        runBlocking {
            val plan =
                planOf(
                    chain = listOf("alpha", "beta"),
                    providers = mapOf("alpha" to provider("alpha"), "beta" to provider("beta")),
                )
            val unclassified =
                ProviderExecutionRequest(
                    componentOperation(0),
                    emptyList(),
                    AttemptCounter(),
                    "cid",
                    ExecutionSecurityContext(dataClassification = null, classificationSource = null),
                    ProviderRouteGate {},
                    GovernedRunIdentity(workloadIdentity, RunId("run")),
                )

            val thrown = catchThrowable { runBlocking { coordinator(plan).execute(unclassified) } }
            // A missing classification claim is not permission: it refuses fail-closed rather than
            // defaulting to PUBLIC, and no candidate reaches a provider.
            assertThat(thrown).isInstanceOf(ProviderException::class.java)
            assertThat((thrown as ProviderException).retryable).isFalse()
            assertThat(invoked).isEmpty()
        }
    }

    @Test
    fun `a pre-open primary circuit whose continuation policy denies is terminal and invokes nobody`() {
        runBlocking {
            val plan =
                planOf(
                    chain = listOf("alpha", "beta"),
                    providers = mapOf("alpha" to provider("alpha"), "beta" to provider("beta")),
                )
            val thrown =
                catchThrowable {
                    runBlocking {
                        coordinator(plan, breaker = openCircuitFor("alpha"), fallbackGate = denyingGate()).execute(request())
                    }
                }
            assertThat(thrown).isInstanceOf(PolicyViolationException::class.java)
            assertThat(invoked).isEmpty()
        }
    }

    @Test
    fun `a late circuit rejection narrows the envelope and reselects only if policy permits`() {
        runBlocking {
            val plan =
                planOf(
                    chain = listOf("alpha", "beta"),
                    providers = mapOf("alpha" to provider("alpha"), "beta" to provider("beta")),
                )
            // alpha is viable at the snapshot and rejected at beforeCall: the same continuation
            // question as a pre-open exclusion, reached by a different mechanism.
            val allowed = coordinator(plan, breaker = lateRejectingBreaker("alpha")).execute(request())
            assertThat(allowed.response.content).isEqualTo("beta")
            assertThat(invoked).containsExactly("beta")

            invoked.clear()
            val denied =
                catchThrowable {
                    runBlocking {
                        coordinator(plan, breaker = lateRejectingBreaker("alpha"), fallbackGate = denyingGate()).execute(request())
                    }
                }
            assertThat(denied).isInstanceOf(PolicyViolationException::class.java)
            assertThat(invoked).isEmpty()
        }
    }

    /** A gate that refuses continuation, reusing the existing policy refusal shape. */
    private fun denyingGate() =
        ProviderFallbackGate {
            _,
            _,
            _,
            _,
            _,
            _,
            ->
            throw PolicyViolationException(PolicyDecision.Deny("fallback denied", "TEST"))
        }

    /**
     * Viable at the viability snapshot, rejected at admission: the late race. Availability is read
     * only through [openUntilMillis] for viability, so returning null there while rejecting at
     * beforeCall models the breaker opening between selection and the call.
     */
    private fun lateRejectingBreaker(rejectProviderId: String) =
        object : ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 60_000L)) {
            override fun openUntilMillis(providerId: String): Long? = null

            override fun beforeCall(providerId: String): CircuitBreakerAdmission =
                if (providerId ==
                    rejectProviderId
                ) {
                    CircuitBreakerAdmission.Rejected(System.currentTimeMillis() + 60_000L)
                } else {
                    super.beforeCall(providerId)
                }
        }

    @Test
    fun `an unauthorized primary is never invoked and the authorized fallback is selected`() {
        runBlocking {
            // "global" sits in a zone no permitted pair allows, so authorization refuses it.
            val plan =
                planOf(
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
            val plan =
                planOf(
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
            val plan =
                planOf(
                    chain = listOf("alpha", "beta"),
                    providers =
                        mapOf(
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
            val plan =
                planOf(
                    chain = listOf("alpha", "global"),
                    providers =
                        mapOf(
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
            val plan =
                planOf(
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
            val plan =
                planOf(
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
            val plan =
                planOf(
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
            val plan =
                planOf(
                    chain = listOf("alpha", "beta"),
                    providers =
                        mapOf(
                            "alpha" to
                                provider("alpha") {
                                    if (calls++ ==
                                        0
                                    ) {
                                        throw ProviderException("transient", retryable = true)
                                    } else {
                                        ModelResponse("alpha-ok")
                                    }
                                },
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
            val plan =
                planOf(
                    chain = listOf("alpha", "beta"),
                    providers =
                        mapOf(
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
            val plan =
                planOf(
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
            val plan =
                planOf(
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

    // ---- 18. boundary discriminator: the presence of a governed topology is the switch ---------

    @Test
    fun `without a governed topology the pre-existing routing path still executes`() {
        runBlocking {
            // No governed routing topology is configured for this engine, so this execution is not a
            // governed one: routing keeps its pre-0.7.3h semantics, including walking to the fallback
            // route in configured order after a retryable failure. The governed selector is not
            // consulted and is not required.
            val plan =
                planOf(
                    chain = listOf("alpha", "beta"),
                    providers =
                        mapOf(
                            "alpha" to provider("alpha") { throw ProviderException("down", retryable = true) },
                            "beta" to provider("beta"),
                        ),
                )

            val response = coordinator(plan, governance = null).execute(request())

            assertThat(response.response.content).isEqualTo("beta")
            assertThat(invoked).containsExactly("alpha", "beta")
        }
    }

    @Test
    fun `a governed execution with a missing required fact fails closed rather than falling back to legacy`() {
        runBlocking {
            val plan = planOf(chain = listOf("alpha"), providers = mapOf("alpha" to provider("alpha")))
            // The topology exists and the execution carries an admitted run identity, so this
            // execution is governed. The candidate's deployment mapping is a required governance
            // fact; its absence must fail closed. Recovering into legacy routing would downgrade a
            // governed execution, which is exactly what is forbidden.
            val thrown =
                catchThrowable {
                    runBlocking {
                        coordinator(plan, governance = governance(deployments = emptyMap()))
                            .execute(request())
                    }
                }

            assertThat(thrown).isInstanceOf(ProviderException::class.java)
            assertThat(invoked).isEmpty()
        }
    }

    @Test
    fun `a candidate absent from the governed topology executes without one and cannot escape with one`() {
        runBlocking {
            // The same plan is executed twice. Without a topology the provider runs through the
            // legacy path; with a topology that does not carry it, the same provider does not run.
            // The pair is the proof that a governed execution cannot opt out of authority by taking
            // the legacy branch.
            val plan = planOf(chain = listOf("alpha"), providers = mapOf("alpha" to provider("alpha")))

            assertThat(coordinator(plan, governance = null).execute(request()).response.content).isEqualTo("alpha")
            assertThat(invoked).containsExactly("alpha")

            invoked.clear()
            val topologyWithoutAlpha = governance(deployments = mapOf("brand-local" to ProviderTrustZone.LOCAL))
            val thrown = catchThrowable { runBlocking { coordinator(plan, governance = topologyWithoutAlpha).execute(request()) } }

            assertThat(thrown).isInstanceOf(ProviderException::class.java)
            assertThat(invoked).isEmpty()
        }
    }

    // ---- 17. capability cannot be bypassed ------------------------------------------------------

    @Test
    fun `an incapable configured provider is not authorized so the capable candidate executes`() {
        runBlocking {
            // Both providers are configured and both would be selectable on registration alone. The
            // request carries an image, so VISION is required by the actual request facts: the
            // incapable provider must be refused at authorization and never selected, leaving the
            // capable candidate to execute. Asserting only "nobody was invoked" is satisfied by the
            // lower-level defensive check too, which is why this asserts WHICH provider ran.
            val plan =
                planOf(
                    chain = listOf("alpha", "beta"),
                    providers = mapOf("alpha" to provider("alpha", vision = false), "beta" to provider("beta", vision = true)),
                )

            val response = coordinator(plan).execute(request(withImage = true))

            assertThat(response.response.content).isEqualTo("beta")
            assertThat(invoked).containsExactly("beta")
        }
    }

    @Test
    fun `tool definitions in the actual request require TOOL_CALLING at authorization`() {
        runBlocking {
            // The operation exposes a tool, so the request facts require TOOL_CALLING. A provider that
            // cannot call tools must be refused at authorization and never selected, leaving the
            // capable candidate to execute. As with VISION, asserting only that nobody ran would be
            // satisfied by the lower-level defensive check, so this asserts WHICH provider ran.
            val plan =
                planOf(
                    chain = listOf("alpha", "beta"),
                    providers = mapOf("alpha" to provider("alpha", toolCalling = false), "beta" to provider("beta")),
                )

            val response = coordinator(plan).execute(request(operation = toolOperation()))

            assertThat(response.response.content).isEqualTo("beta")
            assertThat(invoked).containsExactly("beta")
        }
    }

    @Test
    fun `a provider missing a capability the request requires is refused at authorization`() {
        runBlocking {
            // The request carries an image, so VISION is required by the actual request facts. The
            // provider cannot serve it, so it is refused before selection and never invoked.
            val plan = planOf(chain = listOf("alpha"), providers = mapOf("alpha" to provider("alpha", vision = false)))
            val coordinator = coordinator(plan)

            catchThrowable { runBlocking { coordinator.execute(request(withImage = true)) } }

            assertThat(invoked).isEmpty()
        }
    }

    // ---- 18. absent governance ------------------------------------------------------------------

    @Test
    fun `a governed execution whose required governance mapping is absent invokes no provider`() {
        runBlocking {
            val plan = planOf(chain = listOf("alpha"), providers = mapOf("alpha" to provider("alpha")))

            // A governed topology and an admitted run identity are both present, so the governed
            // branch is taken; the required deployment mapping is absent, so the execution refuses
            // before any provider is invoked. A legacy fallback would be the downgrade this fails on.
            val thrown =
                catchThrowable {
                    runBlocking {
                        coordinator(plan, governance = governance(deployments = emptyMap()))
                            .execute(request())
                    }
                }

            assertThat(thrown).isInstanceOf(ProviderException::class.java)
            assertThat(invoked).isEmpty()
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private val selectedButForbidden = forbiddenCandidate()

    /** A message carrying image content: the actual fact that makes VISION required. */
    private val imageMessage = Message(MessageRole.USER, "", contentParts = listOf(ContentPart.ImagePart("image/png", byteArrayOf(1))))

    private fun forbiddenCandidate() =
        dev.tramai.security.governance.ProviderCandidate(
            providerId = "global",
            modelId = "model",
            deployment = deployment("global", ProviderTrustZone.GLOBAL_CLOUD),
        )

    private fun provider(
        name: String,
        vision: Boolean = true,
        toolCalling: Boolean = true,
        block: suspend () -> ModelResponse = {
            ModelResponse(name)
        },
    ) = object : ModelProvider {
        override suspend fun complete(request: ModelRequest): ModelResponse {
            invoked += name
            return block()
        }

        override fun providerId() = name

        override fun supportsCapability(capability: ProviderCapability) =
            when (capability) {
                ProviderCapability.VISION -> vision
                ProviderCapability.TOOL_CALLING -> toolCalling
                else -> true
            }
    }

    private fun deployment(
        providerId: String,
        zone: ProviderTrustZone,
    ) = ProviderDeployment("dep-$providerId", providerId, NamedTrustZone(TrustZoneName("zone-$providerId"), zone))

    private fun planOf(
        chain: List<String>,
        providers: Map<String, ModelProvider>,
    ): ProviderRoutingPlan {
        val builder = ProviderRoutingPlan.builder()
        providers.forEach { (name, instance) -> builder.provider(name, instance) }
        builder.model("model", chain.first())
        chain.drop(1).forEach { builder.fallbackProvider("model", it) }
        return builder.build()
    }

    private fun governance(
        deployments: Map<String, ProviderTrustZone> =
            mapOf(
                "alpha" to ProviderTrustZone.LOCAL,
                "beta" to ProviderTrustZone.LOCAL,
                "brand-local" to ProviderTrustZone.LOCAL,
                "brand-global" to ProviderTrustZone.GLOBAL_CLOUD,
            ),
        pairs: Set<Pair<ProviderTrustZone, ProviderTrustZone>> = setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.LOCAL),
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
        workloadZones = mapOf(workloadIdentity to ProviderTrustZone.LOCAL),
        deploymentOf = { providerId -> deployments[providerId]?.let { deployment(providerId, it) } },
    )

    private val workloadIdentity =
        WorkloadDeploymentIdentity(
            WorkloadId("workload"),
            WorkloadConfigurationIdentity(ConfigurationId("config"), ConfigurationVersion("1")),
            EnvironmentId("env"),
            DeploymentId("deployment"),
        )

    /** The tool the operation exposes: the governed path must refuse before any tool call happens. */
    private val paymentTool =
        object : ResolvedTool {
            override val name = "payment"
            override val description = "Executes a payment"
            override val inputSchemaJson = "{}"
            override val idempotent = false
            override val sideEffectLevel = SideEffectLevel.WRITE

            override suspend fun execute(
                input: Any,
                context: ToolExecutionContext,
            ): ToolResult = error("a refused capability must not reach a tool call")
        }

    /** An operation exposing a tool: the actual request fact that makes TOOL_CALLING required. */
    @AiService
    internal interface ToolExposingService {
        @Operation(prompt = "Pay", model = "model", tools = ["payment"])
        suspend fun pay(amount: Double): String
    }

    private fun toolOperation(): OperationDefinition {
        val method = ToolExposingService::class.java.methods.single { it.name == "pay" }
        val compiler =
            ServiceDefinitionCompiler(
                OperationDefinitionCompiler(ToolRegistry(mapOf(paymentTool.name to paymentTool)), null, OperationFingerprintFactory()),
            )
        return compiler
            .compile(ToolExposingService::class)
            .operations
            .getValue(method)
            .definition
            ?: error("the tool-exposing operation must compile")
    }

    private fun request(
        retries: Int = 0,
        withImage: Boolean = false,
        run: GovernedRunIdentity? = GovernedRunIdentity(workloadIdentity, RunId("run")),
        operation: OperationDefinition = componentOperation(retries),
    ) = ProviderExecutionRequest(
        operation,
        if (withImage) listOf(imageMessage) else emptyList(),
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
        governance: ProviderGovernanceConfiguration? = governance(),
        preference: ProviderSelectionPreference = ProviderSelectionPreference.CONFIGURED_ORDER,
        fallbackGate: ProviderFallbackGate = ProviderFallbackGate { _, _, _, _, _, _ -> },
    ): ProviderExecutionCoordinator {
        val observer =
            dev.tramai.core.observation
                .OperationObserver { RecordingObservation() }
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
            fallbackGate,
            governance,
            preference,
        )
    }

    /** Authorization fake that approves the requested provider and model. */
    private fun authorization() =
        ProviderAuthorizationService(
            ModelRegistryEnforcer(
                object : ModelRegistry {
                    override suspend fun findApprovedModel(
                        providerId: String,
                        modelName: String,
                    ) = RegisteredModel("id", providerId, modelName, "r1", ModelArtifactDigest.of("sha256:${"a".repeat(64)}"), true)
                },
                ModelRegistrySettings(enabled = true),
            ),
        )
}
