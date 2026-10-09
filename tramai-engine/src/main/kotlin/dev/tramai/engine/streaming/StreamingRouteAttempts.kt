package dev.tramai.engine.streaming

import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.provider.ResolvedProviderRoute
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.emitRuntimeEvent
import dev.tramai.engine.memory.PersistConversationTurnRequest
import dev.tramai.engine.provider.AttemptCounter
import dev.tramai.engine.provider.GovernedProviderEnvelope
import dev.tramai.engine.provider.ProviderFallbackGate
import dev.tramai.engine.provider.ProviderFallbackTransition
import dev.tramai.engine.provider.ProviderRetryDecision
import dev.tramai.engine.provider.ProviderRetryPolicy
import dev.tramai.engine.provider.excludedByAvailability
import dev.tramai.security.governance.ProviderCandidate
import dev.tramai.security.governance.ViableCandidates
import kotlinx.coroutines.delay

/**
 * One route's attempt authority: the retry budget its attempts share, the fallback handoff it
 * publishes, the breaker admission it needs, the failure transitions the gate is told about, and
 * the outcome it reports back to the path that selected it.
 *
 * Both streaming paths (legacy and governed) share it. The route execution and the
 * startup-retry runtime event stay on StreamingExecutionCoordinator, which owns the declarations
 * the certified detekt baseline pins in that file; they reach this class as the two seams below.
 */
internal class StreamingRouteAttempts(
    services: StreamingCoordinationServices,
    failurePolicy: StreamingFailurePolicy,
    private val fallbackGate: ProviderFallbackGate,
    private val executeRoute: suspend (
        StreamingExecutionRoute,
        String,
        ExecutionSecurityContext,
        List<Any?>,
    ) -> StreamingRouteResult,
    private val recordRetryEvent: (String, String, OperationObservation) -> Unit,
) {
    private val circuitBreaker: ProviderCircuitBreaker = failurePolicy.circuitBreaker
    private val retryPolicy: ProviderRetryPolicy = failurePolicy.retryPolicy
    private val conversationMemoryCoordinator = services.conversationMemoryCoordinator

    /**
     * Runs one route's attempts and reports what the caller must do next.
     *
     * The permit is relinquished in a finally here rather than at the call site, so every escape from
     * an attempt - including the two that leave the whole collection - still discharges the obligation
     * admission created. Idempotent by construction: success and recorded failures have already
     * advanced the state (CLOSED / OPEN gen+1), so this is a no-op there, and an unrecorded neutral
     * escape releases the probe.
     *
     * Provider retry budget (Epic 8.2h P0-A): transient STREAMING STARTUP failures retry the SAME
     * route before any token, honoring @Operation.providerRetries exactly like the sync path. Every
     * attempt of a route shares the SAME circuit-breaker permit (8.2g boundary): intermediate retries
     * never call onFailure, only the terminal route outcome completes breaker authority. After any
     * token, retry and fallback authority are permanently gone (handleFallbackResult's emittedAnyTokens
     * gate), and retry never changes route: fallback happens only after exhaustion.
     */
    suspend fun attemptStreamingRoute(
        template: StreamingExecutionRoute,
        handoff: FallbackHandoff,
        budget: RouteAttemptBudget,
        attemptCounter: AttemptCounter,
        arguments: List<Any?>,
    ): StreamingRouteAttemptOutcome {
        var outcome: StreamingRouteAttemptOutcome = StreamingRouteAttemptOutcome.Exhausted
        try {
            for (retryIndex in 0 until budget.maxAttempts) {
                val result =
                    executeRoute(
                        template.copy(attempt = attemptCounter.next()),
                        handoff.correlationId,
                        handoff.securityContext,
                        arguments,
                    )
                val decided = routeOutcomeFor(result, template, handoff, budget, retryIndex)
                if (decided != null) {
                    outcome = decided
                    break
                }
            }
            return outcome
        } finally {
            circuitBreaker.onAbandoned(budget.permit)
        }
    }

    /**
     * Builds and runs one governed route's attempts: the handoff carries exactly what the gate is told,
     * the budget carries exactly this route's retry authority, and the template reuses the existing
     * route data class with a fresh attempt number. The retry decision and the permit's relinquishment
     * live in attemptStreamingRoute, which both streaming paths share.
     */
    suspend fun attemptStreamingCandidate(
        run: StreamingRun,
        authority: StreamingAuthority,
        candidate: StreamingCandidate,
        permit: CircuitBreakerPermit,
        attemptCounter: AttemptCounter,
    ): StreamingRouteAttemptOutcome {
        val budget =
            RouteAttemptBudget(
                permit = permit,
                maxAttempts = run.operation.operation.providerRetries + 1,
            )
        val handoff =
            FallbackHandoff(
                route = candidate.route,
                nextRoute = candidate.nextRoute,
                correlationId = authority.correlationId,
                securityContext = authority.securityContext,
            )
        val template =
            StreamingExecutionRoute(
                operation = run.operation,
                route = candidate.route,
                routeIndex = candidate.routeIndex,
                attempt = 0,
                tokenBudgetTracker = run.tokenBudgetTracker,
                memoryMessages = run.effectiveMessages,
                historySize = run.historySize,
                conversationId = run.conversationId,
                emitChunk = run.emitChunk,
                permit = budget.permit,
            )
        return attemptStreamingRoute(template, handoff, budget, attemptCounter, run.arguments)
    }

    /**
     * Asks the continuation policy about every pre-open exclusion that execution actually advances
     * past, before another candidate may execute. Approval permits continuation only: it cannot
     * restore the excluded candidate, widen the envelope, or reach a route the envelope does not
     * carry, and with no continuation target there is no transition to authorize. An exclusion
     * positioned after the candidate about to run gates nothing, so it is not consulted.
     *
     * Returns the last refusal recorded, or null when no exclusion was gated.
     */
    suspend fun gateExcludedStreamingCandidates(
        envelope: GovernedProviderEnvelope,
        remaining: ViableCandidates,
        continuationCandidate: ProviderCandidate,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ): CircuitBreakerOpenException? {
        var lastOpen: CircuitBreakerOpenException? = null
        val continuationPosition = envelope.configuredOrder.indexOf(continuationCandidate)
        val gates =
            excludedByAvailability(
                envelope.authorized,
                remaining,
                envelope.configuredOrder,
                circuitBreaker,
            ).filter { envelope.configuredOrder.indexOf(it) < continuationPosition }
        for (excluded in gates) {
            val excludedRoute = envelope.routeOf(excluded)
            val openUntil = circuitBreaker.openUntilMillis(excluded.providerId) ?: 0L
            val circuitOpen = CircuitBreakerOpenException(excluded.providerId, openUntil)
            lastOpen = circuitOpen
            try {
                fallbackGate.transition(
                    ProviderFallbackTransition(
                        correlationId,
                        excludedRoute.providerName,
                        excludedRoute.effectiveModelName,
                        envelope.routeOf(continuationCandidate).providerName,
                        "circuit-breaker-open",
                        securityContext,
                    ),
                )
            } catch (policyError: PolicyViolationException) {
                policyError.addSuppressed(circuitOpen)
                throw policyError
            }
        }
        return lastOpen
    }

    /**
     * Maps one route result to what happens next, or null when the route should keep retrying. Kept
     * separate so the attempt loop stays flat enough to read.
     */
    suspend fun routeOutcomeFor(
        result: StreamingRouteResult,
        template: StreamingExecutionRoute,
        handoff: FallbackHandoff,
        budget: RouteAttemptBudget,
        retryIndex: Int,
    ): StreamingRouteAttemptOutcome? =
        when (result) {
            is StreamingRouteResult.Completed -> {
                persistStreamingTurn(
                    template.conversationId,
                    template.memoryMessages,
                    template.historySize,
                    result.fullText,
                )
                StreamingRouteAttemptOutcome.Finished
            }

            is StreamingRouteResult.StartupFailure -> {
                if (handleStreamingStartupFailure(result, handoff, budget, retryIndex)) {
                    StreamingRouteAttemptOutcome.Stop(result.error)
                } else {
                    null
                }
            }

            is StreamingRouteResult.TerminalError -> {
                template.emitChunk(result.errorChunk)
                StreamingRouteAttemptOutcome.Finished
            }
        }

    /**
     * Persists the assistant turn for a completed streaming route. A run without a conversation id
     * persists nothing, exactly as the inline form did.
     */
    suspend fun persistStreamingTurn(
        conversationId: String?,
        effectiveMessages: List<Message>,
        historySize: Int,
        fullText: String,
    ) {
        if (conversationId == null) return
        conversationMemoryCoordinator.persistTurn(
            PersistConversationTurnRequest(
                conversationId,
                effectiveMessages,
                historySize,
                Message(role = MessageRole.ASSISTANT, content = fullText),
            ),
        )
    }

    /**
     * Decides what a retryable streaming startup failure means for one route, and returns true when
     * the route is finished (the caller then exits it and lets the candidate loop advance once).
     *
     * STREAMING_STARTUP_RETRY is a recovery-eligible marker (8.2h P0-M, Option 1), emitted at most
     * once per route and only when a retryable pre-token failure will ACTUALLY be followed by
     * recovery: a same-route retry or a fallback to a next route. providerRetries=0 with no fallback
     * route means no recovery and therefore no event, because the name must never announce a retry
     * that cannot happen. RETRY_SCHEDULED remains the decision event for actual same-route retries.
     */
    suspend fun handleStreamingStartupFailure(
        result: StreamingRouteResult.StartupFailure,
        handoff: FallbackHandoff,
        budget: RouteAttemptBudget,
        retryIndex: Int,
    ): Boolean {
        val decision = retryPolicy.decide(result.error, retryIndex, budget.maxAttempts)
        if (retryIndex == 0 && (decision is ProviderRetryDecision.Retry || handoff.nextRoute != null)) {
            recordRetryEvent(
                handoff.route.providerName,
                result.error::class.simpleName ?: "unknown",
                result.observation,
            )
        }
        return when (decision) {
            is ProviderRetryDecision.Retry -> {
                result.observation.emitRuntimeEvent(
                    RuntimeEvent.of(RuntimeEvents.RETRY_SCHEDULED) {
                        set(RuntimeAttributes.PROVIDER_ID, handoff.route.providerName)
                        set(RuntimeAttributes.RETRY_INDEX, retryIndex.toLong())
                        set(RuntimeAttributes.DELAY_MILLIS, decision.delayMillis)
                        set(RuntimeAttributes.DELAY_SOURCE, decision.delaySource)
                    },
                )
                delay(decision.delayMillis)
                false
            }

            ProviderRetryDecision.Stop -> {
                // Stop is authoritative REGARDLESS of why it stopped (exhaustion OR classification):
                // it permanently relinquishes same-route retry authority (8.2h P0-O), and the
                // fallback gate has already had its say by the time this returns true.
                recordCircuitBreakerFailure(budget.permit, result.error, result.observation)
                enforceStreamingFallbackAfterFailure(
                    error = result.error,
                    route = handoff.route,
                    nextRoute = handoff.nextRoute,
                    correlationId = handoff.correlationId,
                    securityContext = handoff.securityContext,
                )
                true
            }
        }
    }

    suspend fun handleCircuitBreakerOpenRoute(
        route: ResolvedProviderRoute,
        nextRoute: ResolvedProviderRoute?,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ): CircuitBreakerAdmission {
        val admission = circuitBreaker.beforeCall(route.providerName)
        if (admission is CircuitBreakerAdmission.Rejected && nextRoute != null) {
            val circuitOpen = CircuitBreakerOpenException(route.providerName, admission.blockedUntilMillis)
            try {
                fallbackGate.transition(
                    ProviderFallbackTransition(
                        correlationId,
                        route.providerName,
                        route.effectiveModelName,
                        nextRoute.providerName,
                        "circuit-breaker-open",
                        securityContext,
                    ),
                )
            } catch (policyError: PolicyViolationException) {
                policyError.addSuppressed(circuitOpen)
                throw policyError
            }
        }
        return admission
    }

    suspend fun enforceStreamingFallbackAfterFailure(
        error: Throwable,
        route: ResolvedProviderRoute,
        nextRoute: ResolvedProviderRoute?,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
        if (nextRoute == null) return
        try {
            fallbackGate.transition(
                ProviderFallbackTransition(
                    correlationId,
                    route.providerName,
                    route.effectiveModelName,
                    nextRoute.providerName,
                    "streaming-startup-failure",
                    securityContext,
                ),
            )
        } catch (policyError: PolicyViolationException) {
            policyError.addSuppressed(error)
            throw policyError
        }
    }

    fun recordCircuitBreakerFailure(
        permit: CircuitBreakerPermit,
        error: Throwable,
        observation: OperationObservation,
    ) {
        val opened = circuitBreaker.onFailure(permit, error)
        if (opened) {
            observation.emitRuntimeEvent(
                RuntimeEvent.of(RuntimeEvents.CIRCUIT_OPENED) {
                    set(RuntimeAttributes.PROVIDER_ID, permit.providerId)
                },
            )
        } else {
            // Non-qualifying failure: never a breaker failure, but a HALF_OPEN
            // probe permit must still be released or recovery strands forever.
            circuitBreaker.onAbandoned(permit)
        }
    }
}
