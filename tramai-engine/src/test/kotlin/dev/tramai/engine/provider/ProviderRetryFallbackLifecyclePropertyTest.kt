package dev.tramai.engine.provider

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
import dev.tramai.core.model.ClassifiedDocument
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.UsageMetrics
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.core.provider.StreamCapable
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.DefaultEngineIdentitySource
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.PolicyEnforcementHelper
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.ProviderRetryDelayPolicy
import dev.tramai.engine.RetryPolicySettings
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.budget.TokenBudgetCoordinator
import dev.tramai.engine.memory.ConversationMemoryCoordinator
import dev.tramai.engine.planning.OperationDefinitionCompiler
import dev.tramai.engine.planning.OperationFingerprintFactory
import dev.tramai.engine.planning.ServiceDefinitionCompiler
import dev.tramai.engine.provider.ProviderGovernanceConfiguration
import dev.tramai.engine.streaming.StreamingBeforeResponseReturnGate
import dev.tramai.engine.streaming.StreamingExecutionCoordinator
import dev.tramai.engine.streaming.StreamingExecutionRequest
import dev.tramai.engine.tool.ToolExposureCoordinator
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.governance.NamedTrustZone
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.TrustZoneName
import dev.tramai.security.governance.TrustZonePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

/**
 * Epic 8.2h — provider retry/fallback lifecycle property suite (P1–P14).
 *
 * The SCRIPT is the single independent specification: every action declares
 * its own route, admission, and outcome. Model and reality consume the SAME
 * script independently — the model is never used to configure the real
 * coordinator's inputs (oracle independence). The generator's budget-aware
 * archetypes guarantee the script's declared route progression is consistent,
 * and [runModel] enforces it with require() on every action.
 *
 * Reality corpus: 32 seeds × retry budgets {0,1,2} = 96 coordinator executions
 * (plus forced archetypes and P13/P14 fixtures). Semantic coverage guard:
 * 32 × 3 budgets × 3 route counts = 288 model scripts.
 *
 * Deterministic only: injectable clock, zero retry jitter, no Thread.sleep.
 * Retry jitter is deterministic (jitterRatio = 0.0); provider
 * retryAfterMillis = 0 yields zero delay, while timeout failures retain the
 * retry policy's deterministic exponential backoff.
 */
class ProviderRetryFallbackLifecyclePropertyTest {
    /**
     * The model consumes the SCRIPT's declared routes authoritatively. Every
     * action's routeIndex must equal where the model's own decisions have led;
     * a mismatch means either the generator produced an inconsistent script or
     * the model's routing decision is wrong (the property then fails loudly).
     */
    private fun runModel(script: RetryFallbackScript): ModelTrace = ModelRun(script).walk()

    private fun runReality(script: RetryFallbackScript): RealityTrace {
        val sink = OrderedSink()
        val responses = buildResponses(script)
        val realityRouteCount = if (script.explicitProvider) 3 else script.routeCount
        val providers =
            (0 until realityRouteCount)
                .associate { "p$it" to ScriptedProvider("p$it", sink, responses["p$it"] ?: emptyList()) }
        val breaker =
            RecordingCircuitBreaker(
                CircuitBreakerSettings(
                    enabled = true,
                    failureThreshold = 1,
                    openDurationMillis = 1_000L,
                ),
            )
        // Pre-open circuit for circuit-open admissions (harness setup — excluded
        // from the recorded trace).
        for (action in script.actions) {
            if (action is RetryFallbackScriptAction.Admit && action.circuitOpen) {
                val name = "p${action.routeIndex}"
                breaker.onFailure(
                    (breaker.beforeCall(name) as CircuitBreakerAdmission.Allowed).permit,
                    ProviderException("down", retryable = true),
                )
            }
        }
        breaker.resetRecording()
        val observer = AttemptRecordingObserver(sink)
        val c =
            coordinator(
                plan(realityRouteCount, providers),
                breaker,
                sink,
                observer,
                denyFallback = script.fallbackDenied,
            )
        val request =
            if (script.explicitProvider) {
                StreamingExecutionRequest(
                    explicitOperation(),
                    listOf(classifiedInput),
                    TokenBudgetCoordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 20)).createTracker(),
                    null,
                    governedRun,
                )
            } else {
                StreamingExecutionRequest(
                    operation(script.providerRetries),
                    listOf(classifiedInput),
                    TokenBudgetCoordinator(TokenBudgetSettings(hardMaxTokensPerOperation = 20)).createTracker(),
                    null,
                    governedRun,
                )
            }
        var terminalComplete = false
        var terminalErrorClass: String? = null
        var cancelled = false
        var fallbackDenied = false
        try {
            val chunks = runBlocking { c.execute(request).toList() }
            val last = chunks.lastOrNull()
            when (last) {
                is StreamChunk.Complete -> terminalComplete = true
                is StreamChunk.Error -> terminalErrorClass = last.cause::class.simpleName
                else -> Unit
            }
        } catch (e: CancellationException) {
            cancelled = true
        } catch (e: PolicyViolationException) {
            // Fallback-gate denial: deny error is authoritative (8.2h 4.4 / P0-G).
            fallbackDenied = true
        }
        val fallbackEdges =
            sink.events.filter { it.startsWith("fallback-edge:") }.mapNotNull { edge ->
                val body = edge.removePrefix("fallback-edge:")
                val parts = body.split("->")
                if (parts.size == 2) {
                    val from = parts[0].substringAfter("p").toIntOrNull()
                    val to = parts[1].substringAfter("p").toIntOrNull()
                    if (from != null && to != null) from to to else null
                } else {
                    null
                }
            }
        val firstTokenIndex = sink.events.indexOfFirst { it == "stream.token" }.takeIf { it >= 0 }
        val afterToken =
            if (firstTokenIndex != null) {
                sink.events.drop(firstTokenIndex + 1)
            } else {
                emptyList()
            }
        return RealityTrace(
            observedAttempts = observer.attempts,
            fallbackEdges = fallbackEdges,
            retryEvents = sink.count("observation.engine-event:tramai.retry.scheduled"),
            fallbackEvents = sink.count("policy.fallback"),
            circuitOpenedEvents = sink.count("observation.engine-event:tramai.circuit.opened"),
            breakerDispositions = breaker.dispositions,
            terminalComplete = terminalComplete,
            terminalErrorClass = terminalErrorClass,
            cancelled = cancelled,
            fallbackDenied = fallbackDenied,
            firstTokenIndex = firstTokenIndex,
            perRouteAttempts = providers.mapValues { (_, p) -> p.streamRequests.size },
            retryEventsAfterToken = afterToken.count { it == "observation.engine-event:tramai.retry.scheduled" },
            fallbackEventsAfterToken = afterToken.count { it == "policy.fallback" },
            circuitOpenedEventsAfterToken = afterToken.count { it == "observation.engine-event:tramai.circuit.opened" },
        )
    }

    private fun driveScript(
        script: RetryFallbackScript,
        label: String,
    ) {
        val model = runModel(script)
        assertModelInvariants(model, script, label)

        val reality = runReality(script)

        // P1: ordered attempt-trace equivalence — model (route, globalAttempt)
        // vs reality (provider, attempt). A swapped provider distribution can
        // keep the same TOTAL; the ordered trace cannot.
        val modelAttempts = model.attemptTrace.map { "p${it.routeIndex}" to it.globalAttempt }
        // Diagnostics only: the failure message carries both traces so the staged contract
        // (configured -> authorized -> viable -> selected/attempted) can be compared with what the
        // reference model expects, without re-running under a debugger.
        assertThat(reality.observedAttempts)
            .withFailMessage(
                "$label P1 ordered attempt trace | model=$modelAttempts " +
                    "reality=${reality.observedAttempts} script=$script",
            ).containsExactlyElementsOf(modelAttempts)
        // P1: ordered fallback edges (P10: routes strictly advance, never revisited).
        // Diagnostics only: edges and attempts together, because an edge discrepancy can be either
        // a missing/extra transition between legitimately selected candidates, or secondary to a
        // different candidate visit.
        assertThat(reality.fallbackEdges)
            .withFailMessage(
                "$label P1 fallback edges | modelEdges=${model.fallbackEdges} realityEdges=${reality.fallbackEdges} " +
                    "modelAttempts=$modelAttempts realityAttempts=${reality.observedAttempts} script=$script",
            ).containsExactlyElementsOf(model.fallbackEdges)
        // P1: retry/fallback/breaker totals.
        assertThat(reality.retryEvents).withFailMessage("$label P1 retries").isEqualTo(model.retryTransitions)
        assertThat(reality.fallbackEvents).withFailMessage("$label P1 fallbacks").isEqualTo(model.fallbackTransitions)
        assertThat(reality.circuitOpenedEvents)
            .withFailMessage("$label P1 breaker failures")
            .isEqualTo(model.breakerQualifyingFailures)
        // P14: reality observes the SAME semantic breaker dispositions as the model.
        assertThat(
            reality.breakerDispositions,
        ).withFailMessage("$label P14 reality breaker dispositions").containsExactlyElementsOf(
            model
                .breakerDispositions,
        )
        // P12: after the first REAL emitted token, no retry/fallback authority
        // remains. The breaker failure event is the TERMINAL disposition
        // recording (allowed after the token) — only recovery actions are
        // forbidden: RETRY_SCHEDULED and the fallback gate.
        if (reality.firstTokenIndex != null) {
            assertThat(reality.retryEventsAfterToken + reality.fallbackEventsAfterToken)
                .withFailMessage("$label P12 retry/fallback after first token")
                .isZero()
        }

        when (model.terminalOutcome) {
            TerminalOutcome.Success -> {
                assertThat(reality.terminalComplete).withFailMessage("$label P1 success").isTrue()
                assertThat(reality.cancelled).isFalse()
                assertThat(reality.fallbackDenied).isFalse()
            }

            is TerminalOutcome.Cancelled -> {
                assertThat(reality.cancelled).withFailMessage("$label P1 cancelled").isTrue()
            }

            is TerminalOutcome.FallbackDenied -> {
                // Deny error is authoritative; the invocation fails with the
                // gate's PolicyViolationException (P0-G).
                assertThat(reality.fallbackDenied).withFailMessage("$label P1 fallback denied").isTrue()
                assertThat(reality.cancelled).isFalse()
            }

            is TerminalOutcome.Failure -> {
                assertThat(reality.terminalErrorClass).withFailMessage("$label P1 error").isNotNull()
                assertThat(reality.cancelled).isFalse()
                assertThat(reality.fallbackDenied).isFalse()
            }

            null -> {
                Unit
            }
        }
    }

    @Test
    fun `P1-P14 retry fallback lifecycle properties over deterministic corpus`() {
        val seeds = 0L until 32L
        for (seed in seeds) {
            for (retries in listOf(0, 1, 2)) {
                val script =
                    ProviderRetryFallbackActionGenerator.generate(
                        seed,
                        providerRetries = retries,
                        routeCount = 2,
                    )
                driveScript(script, "seed=$seed retries=$retries")
            }
        }
    }

    @Test
    fun `P13 explicit provider resolution keeps route cardinality one in reality`() {
        // The plan contains p0+p1+p2 (fallback chain), but the operation
        // declares @Operation(provider = "p0"): reality must run ONLY p0 —
        // model fallbacks are bypassed by explicit-provider resolution (P0-H).
        val script = ProviderRetryFallbackActionGenerator.generate(11, providerRetries = 1, routeCount = 1)
        assertThat(script.explicitProvider).isTrue()
        val model = runModel(script)
        assertThat(model.attemptTrace.map { it.routeIndex }.distinct()).containsExactly(0)
        assertThat(model.fallbackEdges).isEmpty()
        assertModelInvariants(model, script, "explicit-provider")

        val reality = runReality(script)
        assertThat(reality.observedAttempts.map { it.first }).withFailMessage("P13 only p0 executes").containsExactly(
            "p0",
            "p0",
        )
        assertThat(reality.perRouteAttempts["p1"] ?: 0).withFailMessage("P13 p1 never executes").isZero()
        assertThat(reality.perRouteAttempts["p2"] ?: 0).withFailMessage("P13 p2 never executes").isZero()
        assertThat(reality.fallbackEdges).isEmpty()
    }

    @Test
    fun `P14 breaker composition traces over forced archetypes`() {
        // retryable -> retry -> success: SEMANTIC disposition SUCCESS,
        // 0 qualifying failures (the authoritative route result is success).
        val successScript =
            RetryFallbackScript(
                providerRetries = 1,
                routeCount = 1,
                actions =
                    listOf(
                        RetryFallbackScriptAction.Admit(0),
                        RetryFallbackScriptAction.Attempt(0, AttemptOutcome.RetryableFailure),
                        RetryFallbackScriptAction.Attempt(0, AttemptOutcome.Success),
                    ),
            )
        val successModel = runModel(successScript)
        assertThat(successModel.breakerQualifyingFailures).isZero()
        assertThat(successModel.breakerSuccesses).isEqualTo(1)
        assertThat(successModel.breakerDispositions).containsExactly(BreakerDisposition.SUCCESS)
        val successReality = runReality(successScript)
        assertThat(successReality.breakerDispositions)
            .withFailMessage("P14 reality SUCCESS")
            .containsExactly(BreakerDisposition.SUCCESS)

        // retryable -> retry -> exhausted retryable: QUALIFYING_FAILURE, 1.
        val exhaustedScript =
            RetryFallbackScript(
                providerRetries = 1,
                routeCount = 1,
                actions =
                    listOf(
                        RetryFallbackScriptAction.Admit(0),
                        RetryFallbackScriptAction.Attempt(0, AttemptOutcome.RetryableFailure),
                        RetryFallbackScriptAction.Attempt(0, AttemptOutcome.RetryableFailure),
                    ),
            )
        val exhaustedModel = runModel(exhaustedScript)
        assertThat(exhaustedModel.breakerQualifyingFailures).isEqualTo(1)
        assertThat(exhaustedModel.breakerDispositions).containsExactly(BreakerDisposition.QUALIFYING_FAILURE)
        val exhaustedReality = runReality(exhaustedScript)
        assertThat(
            exhaustedReality.breakerDispositions,
        ).withFailMessage("P14 reality QUALIFYING_FAILURE").containsExactly(BreakerDisposition.QUALIFYING_FAILURE)

        // retryable -> retry -> permanent: NEUTRAL, zero qualifying failures.
        val permanentScript =
            RetryFallbackScript(
                providerRetries = 1,
                routeCount = 1,
                actions =
                    listOf(
                        RetryFallbackScriptAction.Admit(0),
                        RetryFallbackScriptAction.Attempt(0, AttemptOutcome.RetryableFailure),
                        RetryFallbackScriptAction.Attempt(0, AttemptOutcome.PermanentProviderFailure),
                    ),
            )
        val permanentModel = runModel(permanentScript)
        assertThat(permanentModel.breakerQualifyingFailures).isZero()
        assertThat(permanentModel.breakerDispositions).containsExactly(BreakerDisposition.NEUTRAL)
        assertThat(permanentModel.terminalOutcome).isEqualTo(TerminalOutcome.Failure(FailureKind.PERMANENT))
        val permanentReality = runReality(permanentScript)
        assertThat(permanentReality.breakerDispositions)
            .withFailMessage("P14 reality NEUTRAL")
            .containsExactly(BreakerDisposition.NEUTRAL)

        // primary exhausted -> fallback success: TWO route dispositions —
        // primary permit QUALIFYING_FAILURE, fallback permit SUCCESS. Not
        // "one invocation completion": each admitted route owns its permit.
        val fallbackScript =
            RetryFallbackScript(
                providerRetries = 0,
                routeCount = 2,
                actions =
                    listOf(
                        RetryFallbackScriptAction.Admit(0),
                        RetryFallbackScriptAction.Attempt(0, AttemptOutcome.RetryableFailure),
                        RetryFallbackScriptAction.Admit(1),
                        RetryFallbackScriptAction.Attempt(1, AttemptOutcome.Success),
                    ),
            )
        val fallbackModel = runModel(fallbackScript)
        assertThat(fallbackModel.breakerQualifyingFailures).isEqualTo(1)
        assertThat(fallbackModel.breakerSuccesses).isEqualTo(1)
        assertThat(fallbackModel.breakerDispositions).containsExactly(
            BreakerDisposition.QUALIFYING_FAILURE,
            BreakerDisposition.SUCCESS,
        )
        val fallbackReality = runReality(fallbackScript)
        assertThat(fallbackReality.breakerDispositions)
            .withFailMessage(
                "P14 reality two route dispositions",
            ).containsExactly(BreakerDisposition.QUALIFYING_FAILURE, BreakerDisposition.SUCCESS)

        // circuit-open route -> fallback gate invoked -> gate DENIES: the deny
        // error is authoritative (FallbackDenied(CIRCUIT_OPEN_ONLY)); the gate
        // transition WAS exercised before the denial threw, so it counts as a
        // fallback transition in both model and reality (8.2h P0-O lane).
        val circuitOpenDeniedScript =
            RetryFallbackScript(
                providerRetries = 1,
                routeCount = 2,
                fallbackDenied = true,
                actions =
                    listOf(
                        RetryFallbackScriptAction.Admit(0, circuitOpen = true),
                    ),
            )
        val circuitOpenDeniedModel = runModel(circuitOpenDeniedScript)
        assertThat(circuitOpenDeniedModel.terminalOutcome)
            .withFailMessage("P14 denied circuit-open terminal")
            .isEqualTo(TerminalOutcome.FallbackDenied(FailureKind.CIRCUIT_OPEN_ONLY))
        assertThat(circuitOpenDeniedModel.fallbackTransitions)
            .withFailMessage("P14 denied circuit-open counts the gate transition")
            .isEqualTo(1)
        assertThat(circuitOpenDeniedModel.breakerDispositions)
            .withFailMessage("P14 denied circuit-open owns no permit")
            .isEmpty()
        assertThat(circuitOpenDeniedModel.fallbackEdges).containsExactly(0 to 1)
        val circuitOpenDeniedReality = runReality(circuitOpenDeniedScript)
        assertThat(circuitOpenDeniedReality.fallbackDenied)
            .withFailMessage(
                "P14 reality deny authoritative | modelTerminal=${circuitOpenDeniedModel.terminalOutcome} " +
                    "realityFallbackEvents=${circuitOpenDeniedReality.fallbackEvents} " +
                    "realityAttempts=${circuitOpenDeniedReality.observedAttempts} " +
                    "realityDispositions=${circuitOpenDeniedReality.breakerDispositions}",
            ).isTrue()
        assertThat(circuitOpenDeniedReality.fallbackEvents)
            .withFailMessage("P14 reality gate invoked once on denied circuit-open")
            .isEqualTo(1)
        assertThat(circuitOpenDeniedReality.observedAttempts)
            .withFailMessage("P14 denied circuit-open consumes zero attempts")
            .isEmpty()
        assertThat(circuitOpenDeniedReality.breakerDispositions)
            .withFailMessage("P14 reality denied circuit-open owns no permit")
            .isEmpty()
    }

    private fun recordLane(
        model: ModelTrace,
        script: RetryFallbackScript,
        lanes: MutableSet<String>,
    ) {
        recordRetryLanes(model, script, lanes)
        recordFallbackLanes(model, script, lanes)
        recordTerminalLanes(model, script, lanes)
    }

    @Test
    fun `semantic coverage guard corpus reaches every retry fallback lane`() {
        // Mechanical guard: a generator refactor must never silently drop a
        // semantic lane. Runs the FULL corpus (32 seeds x retries 0/1/2 x
        // route counts 1/2/3) through the model and requires every lane.
        val lanes = mutableSetOf<String>()
        val budgetsWithRecovery = mutableSetOf<Int>()
        for (seed in 0L until 32L) {
            for (retries in listOf(0, 1, 2)) {
                for (routeCount in listOf(1, 2, 3)) {
                    val script =
                        ProviderRetryFallbackActionGenerator.generate(
                            seed,
                            providerRetries = retries,
                            routeCount = routeCount,
                        )
                    val model = runModel(script)
                    recordLane(model, script, lanes)
                    if (model.retryTransitions > 0 || model.fallbackTransitions > 0) budgetsWithRecovery += retries
                }
            }
        }
        val required =
            setOf(
                "same-route-retry",
                "retry-success",
                "retry-exhaustion",
                "retry-after-retry",
                "fallback-after-exhaustion",
                "multi-fallback-traversal",
                "circuit-open-fallback",
                "all-routes-open",
                "fallback-denial",
                "explicit-provider",
                "output-visible-terminal",
                "cancellation",
                "neutral-terminal-after-retry",
            )
        val missing = required - lanes
        assertThat(missing)
            .withFailMessage("semantic coverage guard: corpus never reached lanes $missing")
            .isEmpty()
        // providerRetries = 0 AND providerRetries > 0 must both drive recovery.
        assertThat(budgetsWithRecovery).contains(0)
        assertThat(budgetsWithRecovery).contains(1)
        // "different effective fallback model" is owned by the P0-I discriminator
        // (routing-plan resolution, outside the retry/fallback disposition lattice).
    }
}
