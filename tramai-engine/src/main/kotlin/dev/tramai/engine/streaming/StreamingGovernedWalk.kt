package dev.tramai.engine.streaming

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.ModelRegistryException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderCapabilityException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.exception.TramaiException
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.core.provider.ResolvedProviderRoute
import dev.tramai.core.provider.StreamCapable
import dev.tramai.core.provider.resolveCandidates
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.EngineIdentitySource
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.budget.TokenBudgetCoordinator
import dev.tramai.engine.budget.TokenBudgetTracker
import dev.tramai.engine.emitRuntimeEvent
import dev.tramai.engine.memory.ConversationMemoryCoordinator
import dev.tramai.engine.memory.PersistConversationTurnRequest
import dev.tramai.engine.provider.AttemptCounter
import dev.tramai.engine.provider.GovernedExecutionInput
import dev.tramai.engine.provider.GovernedProviderEnvelope
import dev.tramai.engine.provider.ProviderFallbackGate
import dev.tramai.engine.provider.ProviderFallbackTransition
import dev.tramai.engine.provider.ProviderGovernanceConfiguration
import dev.tramai.engine.provider.ProviderInvocationGate
import dev.tramai.engine.provider.ProviderResolutionGate
import dev.tramai.engine.provider.ProviderRetryDecision
import dev.tramai.engine.provider.ProviderRetryPolicy
import dev.tramai.engine.provider.deriveRequiredCapabilities
import dev.tramai.engine.provider.excludedByAvailability
import dev.tramai.engine.provider.governProviderExecution
import dev.tramai.engine.tool.ToolExposureCoordinator
import dev.tramai.security.governance.CandidateSelectionDecision
import dev.tramai.security.governance.ProviderCandidate
import dev.tramai.security.governance.ViableCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The governed streaming walk: authorize what the configured routes propose, select each route
 * from the current remainder, admit it through the breaker, attempt it, and report exhaustion.
 *
 * The route attempt authority stays on StreamingRouteAttempts and the flow plumbing on
 * StreamingExecutionCoordinator (both are the objects the composition already built); this class
 * only sequences the walk. It invokes no observer callback.
 */
internal class StreamingGovernedWalk(
    runtime: StreamingEngineRuntime,
    failurePolicy: StreamingFailurePolicy,
    private val routeAttempts: StreamingRouteAttempts,
    private val governance: ProviderGovernanceConfiguration?,
) {
    private val routingPlan = runtime.routingPlan
    private val circuitBreaker = failurePolicy.circuitBreaker

    /**
     * Authorizes what the configured routes propose. This path reaches a provider only through a
     * candidate selected from that envelope, exactly as the synchronous path does, and a streaming
     * request inherently requires STREAMING.
     */
    fun governedStreamingEnvelope(
        run: StreamingRun,
        authority: StreamingAuthority,
    ): GovernedProviderEnvelope =
        governProviderExecution(
            GovernedExecutionInput(
                routes = authority.candidates,
                routingPlan = routingPlan,
                securityContext = authority.securityContext,
                requiredCapabilities =
                    deriveRequiredCapabilities(
                        run.operation,
                        run.effectiveMessages,
                        streaming = true,
                    ),
            ),
            configuration = governance,
            governedRun = authority.request.governedRun,
            circuitBreaker = circuitBreaker,
        )

    /** Reports why no route could serve the request: the last failure and the last circuit refusal. */
    suspend fun reportStreamingExhaustion(
        run: StreamingRun,
        envelope: GovernedProviderEnvelope,
        lastFailure: Throwable?,
        lastCircuitOpen: CircuitBreakerOpenException?,
    ) {
        // Authorization admitting nothing is a governance refusal, not an exhausted attempt: no
        // candidate was admitted, so no provider was reached, nothing failed, and retrying cannot
        // change the identity, zone, classification, registration or capability that refused it. The
        // no-route fallback would report it as retryable runtime unavailability, which it is not.
        if (envelope.authorizedNothing) {
            run.emitChunk(StreamChunk.Error(unauthorizedStreamingRefusal()))
            return
        }
        run.emitChunk(
            noAvailableStreamingRouteChunk(
                run.operation,
                lastFailure,
                // A walk that never recorded a refusal still reports the circuit state it ended on.
                lastCircuitOpen ?: circuitOpenExhaustion(envelope),
            ),
        )
    }

    /**
     * The next governed streaming route that admission allows.
     *
     * A route the circuit breaker refuses is not a failure: the refusal narrows the envelope and the walk
     * selects again from what remains, exactly as configured order would. Returns null when no candidate
     * is left to select, carrying the narrowed envelope and the last refusal.
     */
    suspend fun admitNextGovernedStreamingRoute(
        envelope: GovernedProviderEnvelope,
        remaining: ViableCandidates,
        authority: StreamingAuthority,
        lastCircuitOpen: CircuitBreakerOpenException?,
    ): GovernedStreamingAdmissionStep? {
        var viable = remaining
        var open = lastCircuitOpen
        while (true) {
            val step = nextGovernedStreamingRoute(envelope, viable, authority) ?: return null
            val admission = step.admission
            if (admission !is CircuitBreakerAdmission.Rejected) {
                return GovernedStreamingAdmissionStep(step, viable, open)
            }
            open =
                CircuitBreakerOpenException(
                    step.selection.route.providerName,
                    admission.blockedUntilMillis,
                )
            viable = step.selection.narrowed
        }
    }

    /**
     * Walks the governed envelope: authorize what the configured routes propose, consult the
     * continuation policy for every pre-open exclusion execution advances past, select each route from
     * the current remainder, attempt it, and report exhaustion with the last failure and the last
     * circuit refusal.
     *
     * Configured routes propose; the governed envelope authorizes. This path reaches a provider only
     * through a candidate selected from that envelope, exactly as the synchronous path does, and a
     * streaming request inherently requires STREAMING.
     */
    suspend fun walkGovernedStreamingRoutes(
        run: StreamingRun,
        authority: StreamingAuthority,
    ) {
        var lastFailure: Throwable? = null
        var lastCircuitOpen: CircuitBreakerOpenException? = null
        val attemptCounter = AttemptCounter()

        val envelope = governedStreamingEnvelope(run, authority)
        var remaining = envelope.viable

        // Pre-open exclusions are still transitions execution advances past, so the
        // continuation policy is consulted for each one before another candidate may
        // execute — the same question the rejection below asks, for the same reason.
        // Approval permits continuation only: it cannot restore the excluded candidate,
        // widen the envelope, or reach a route the envelope does not carry. With no
        // continuation target there is no transition to authorize.
        // Only an exclusion execution actually advances PAST gates a continuation: an
        // excluded candidate positioned after the candidate about to run does not gate
        // it, so no transition exists and the gate is not consulted on its behalf.
        val continuationCandidate = remaining.orderedBy(envelope.configuredOrder).firstOrNull()
        if (continuationCandidate != null) {
            lastCircuitOpen =
                routeAttempts.gateExcludedStreamingCandidates(
                    envelope,
                    remaining,
                    continuationCandidate,
                    authority.correlationId,
                    authority.securityContext,
                ) ?: lastCircuitOpen
        }

        while (true) {
            val admitted =
                admitNextGovernedStreamingRoute(
                    envelope,
                    remaining,
                    authority,
                    lastCircuitOpen,
                ) ?: break
            remaining = admitted.remaining
            lastCircuitOpen = admitted.lastCircuitOpen
            val selection = admitted.step.selection
            val permit = (admitted.step.admission as CircuitBreakerAdmission.Allowed).permit

            // Provider retry budget (Epic 8.2h P0-A): transient
            // STREAMING STARTUP failures retry the SAME route
            // before any token, honoring @Operation.providerRetries
            // exactly like the sync path — maxAttempts =
            // providerRetries + 1, same ProviderRetryPolicy, same
            // retry-after cap / backoff / jitter. Retry never
            // changes route; fallback only after exhaustion.
            // Every attempt of a route shares the SAME circuit-
            // breaker permit (8.2g boundary): intermediate retries
            // never call onFailure — only the terminal route
            // outcome completes breaker authority.
            // After any token, retry/fallback authority is
            // permanently gone (handleFallbackResult's
            // emittedAnyTokens gate).
            when (
                val outcome =
                    routeAttempts.attemptStreamingCandidate(
                        run,
                        authority,
                        admitted.step.candidate,
                        permit,
                        attemptCounter,
                    )
            ) {
                is StreamingRouteAttemptOutcome.Finished -> {
                    return
                }

                is StreamingRouteAttemptOutcome.Stop -> {
                    lastFailure = outcome.error
                    // Narrow and reselect rather than advancing to the next configured route. The candidate
                    // loop advances because Stop ended the route's attempts, not the walk.
                    remaining = selection.narrowed
                }

                StreamingRouteAttemptOutcome.Exhausted -> {
                    Unit
                }
            }
        }

        reportStreamingExhaustion(run, envelope, lastFailure, lastCircuitOpen)
    }

    /**
     * Answers the walk's one recurring question: which route runs next, and is it admissible.
     *
     * Selection and admission belong together because the fallback handoff must be built from the
     * route the breaker actually admitted, and because a refusal is the caller's to record - the walk
     * owns the last circuit refusal and the narrowed remainder. Null means governance selected nothing,
     * which ends the walk.
     */
    suspend fun nextGovernedStreamingRoute(
        envelope: GovernedProviderEnvelope,
        remaining: ViableCandidates,
        authority: StreamingAuthority,
    ): GovernedStreamingStep? {
        val selection = selectGovernedStreamingRoute(envelope, remaining) ?: return null
        val admission =
            routeAttempts.handleCircuitBreakerOpenRoute(
                route = selection.route,
                nextRoute = selection.nextRoute,
                correlationId = authority.correlationId,
                securityContext = authority.securityContext,
            )
        return GovernedStreamingStep(selection, admission)
    }

    /**
     * Selects the next route from the CURRENT remainder and reports it with its successor.
     *
     * Narrow the current remainder, never the envelope's viable snapshot: subtracting from the
     * snapshot would restore a candidate removed by an earlier iteration and let a route be
     * revisited. The successor is asked for after narrowing, so the fallback gate is never told about
     * a route governance has not selected.
     */
    fun selectGovernedStreamingRoute(
        envelope: GovernedProviderEnvelope,
        remaining: ViableCandidates,
    ): GovernedStreamingRouteSelection? {
        val chosen =
            when (val decision = envelope.selection.select(remaining, envelope.preferConfiguredOrder)) {
                is CandidateSelectionDecision.Selected -> decision.candidate
                is CandidateSelectionDecision.NoSelection -> null
            } ?: return null
        val narrowed = remaining.without(chosen)
        return GovernedStreamingRouteSelection(
            route = envelope.routeOf(chosen),
            routeIndex = envelope.routeIndexOf(envelope.routeOf(chosen)),
            narrowed = narrowed,
            nextRoute = envelope.nextSelected(narrowed),
        )
    }

    /**
     * Read-only classification of an exhausted universe. Viability removes circuit-open candidates
     * before selection, so when every candidate is circuit-open nothing is ever rejected inside the
     * loop and no circuit fact is observed there. This reports what viability already observed,
     * consuming no permit and admitting nothing.
     *
     * A governance refusal is not an exhausted attempt: when authorization admitted nothing, the
     * terminal stays the refusal it is rather than being relabelled as circuit state.
     */
    fun circuitOpenExhaustion(envelope: GovernedProviderEnvelope): CircuitBreakerOpenException? {
        if (envelope.authorizedNothing) return null
        return envelope.configuredOrder
            .mapNotNull { candidate ->
                circuitBreaker.openUntilMillis(candidate.providerId)?.let { until ->
                    CircuitBreakerOpenException(candidate.providerId, until)
                }
            }.firstOrNull()
    }
}
