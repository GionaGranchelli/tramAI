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

internal fun interface StreamingBeforeResponseReturnGate {
    suspend fun enforce(
        route: ResolvedProviderRoute,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    )
}

internal class StreamingExecutionCoordinator(
    private val identitySource: EngineIdentitySource,
    private val routingPlan: ProviderRoutingPlan,
    private val circuitBreaker: ProviderCircuitBreaker,
    private val lifecycleScope: CoroutineScope,
    private val isClosed: AtomicBoolean,
    private val serviceTypeName: String,
    private val qualifiedServiceName: String?,
    private val operationObserver: OperationObserver,
    private val operationInterceptor: OperationInterceptor,
    private val toolExposureCoordinator: ToolExposureCoordinator,
    private val conversationMemoryCoordinator: ConversationMemoryCoordinator,
    private val tokenBudgetCoordinator: TokenBudgetCoordinator,
    private val modelRegistryEnforcer: ModelRegistryEnforcer,
    private val retryPolicy: ProviderRetryPolicy,
    private val beforeResolution: ProviderResolutionGate,
    private val beforeInvocation: ProviderInvocationGate,
    private val fallbackGate: ProviderFallbackGate,
    private val beforeResponseReturn: StreamingBeforeResponseReturnGate,
    /** The governed routing topology; absent means execution refuses rather than using configured routing. */
    private val governance: ProviderGovernanceConfiguration? = null,
) {
    fun execute(request: StreamingExecutionRequest): Flow<StreamChunk> =
        // Structural branch on authoritative configuration state: `governance` is derived from the
        // engine's configured routing topology, so its absence means no governed routing topology
        // exists for this execution, not that a caller omitted a request field. A governed execution
        // never enters the legacy path: the branch is taken before governed selection and there is no
        // recovery from a governed refusal into legacy routing.
        //
        // The topology is keyed by WorkloadDeploymentIdentity, so it is only addressable for an
        // execution that carries an admitted run identity; without one there is no governed routing
        // topology *for this execution* and it keeps the pre-0.7.3h path. The identity is resolved
        // from the authoritative run scope at the construction site above, never taken from a
        // caller-supplied request field, so a governed execution cannot opt out by omitting one.
        if (governance == null || request.governedRun == null) executeLegacy(request) else executeGoverned(request)

    private fun executeLegacy(request: StreamingExecutionRequest): Flow<StreamChunk> {
        val operation = request.operation
        val arguments = request.arguments
        val tokenBudgetTracker = request.tokenBudgetTracker
        val conversationId = request.conversationId
        val securityContext = ExecutionSecurityContext.fromArguments(arguments.toTypedArray())
        val initialMessages = operation.initialMessages(arguments)
        val prepared = conversationMemoryCoordinator.prepareMessages(initialMessages, conversationId)
        val history = prepared?.history ?: emptyList()
        val effectiveMessages = prepared?.effectiveMessages ?: initialMessages

        return streamChunksToCollector { chunks ->
            val correlationId = identitySource.newCorrelationId()
            require(correlationId.isNotBlank()) { "Engine correlationId must not be blank" }
            beforeResolution.beforeResolution(operation, correlationId, securityContext)
            val candidates = routingPlan.resolveCandidates(operation.operation)
            var lastFailure: Throwable? = null
            var lastCircuitOpen: CircuitBreakerOpenException? = null
            val attemptCounter = AttemptCounter()
            val run =
                StreamingRun(
                    operation = operation,
                    arguments = arguments,
                    tokenBudgetTracker = tokenBudgetTracker,
                    conversationId = conversationId,
                    effectiveMessages = effectiveMessages,
                    historySize = history.size,
                    emitChunk = { chunks.send(it) },
                )
            val authority =
                StreamingAuthority(
                    request = request,
                    securityContext = securityContext,
                    correlationId = correlationId,
                    candidates = candidates,
                )

            for ((routeIndex, route) in candidates.withIndex()) {
                val admission =
                    handleCircuitBreakerOpenRoute(
                        route = route,
                        nextRoute = candidates.getOrNull(routeIndex + 1),
                        correlationId = correlationId,
                        securityContext = securityContext,
                    )
                if (admission is CircuitBreakerAdmission.Rejected) {
                    lastCircuitOpen = CircuitBreakerOpenException(route.providerName, admission.blockedUntilMillis)
                    continue
                }
                val permit = (admission as CircuitBreakerAdmission.Allowed).permit

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
                val candidate =
                    StreamingCandidate(route, routeIndex, candidates.getOrNull(routeIndex + 1))
                when (val outcome = attemptStreamingCandidate(run, authority, candidate, permit, attemptCounter)) {
                    is StreamingRouteAttemptOutcome.Finished -> return@streamChunksToCollector
                    is StreamingRouteAttemptOutcome.Stop -> lastFailure = outcome.error
                    StreamingRouteAttemptOutcome.Exhausted -> Unit
                }
            }

            chunks.send(noAvailableStreamingRouteChunk(operation, lastFailure, lastCircuitOpen))
        }
    }

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
    private suspend fun attemptStreamingRoute(
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
                    executeStreamingRoute(
                        template.copy(attempt = attemptCounter.next()),
                        correlationId = handoff.correlationId,
                        securityContext = handoff.securityContext,
                        arguments = arguments,
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

    /** The route to attempt, its configured index, and the successor a fallback would reach. */
    private data class StreamingCandidate(
        val route: ResolvedProviderRoute,
        val routeIndex: Int,
        val nextRoute: ResolvedProviderRoute?,
    )

    /**
     * Builds and runs one governed route's attempts: the handoff carries exactly what the gate is told,
     * the budget carries exactly this route's retry authority, and the template reuses the existing
     * route data class with a fresh attempt number. The retry decision and the permit's relinquishment
     * live in attemptStreamingRoute, which both streaming paths share.
     */
    private suspend fun attemptStreamingCandidate(
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
     * Authorizes what the configured routes propose. This path reaches a provider only through a
     * candidate selected from that envelope, exactly as the synchronous path does, and a streaming
     * request inherently requires STREAMING.
     */
    private fun governedStreamingEnvelope(
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
    private suspend fun reportStreamingExhaustion(
        run: StreamingRun,
        envelope: GovernedProviderEnvelope,
        lastFailure: Throwable?,
        lastCircuitOpen: CircuitBreakerOpenException?,
    ) {
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
     * Walks the governed envelope: authorize what the configured routes propose, consult the
     * continuation policy for every pre-open exclusion execution advances past, select each route from
     * the current remainder, attempt it, and report exhaustion with the last failure and the last
     * circuit refusal.
     *
     * Configured routes propose; the governed envelope authorizes. This path reaches a provider only
     * through a candidate selected from that envelope, exactly as the synchronous path does, and a
     * streaming request inherently requires STREAMING.
     */
    private suspend fun walkGovernedStreamingRoutes(
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
                gateExcludedStreamingCandidates(
                    envelope,
                    remaining,
                    continuationCandidate,
                    authority.correlationId,
                    authority.securityContext,
                ) ?: lastCircuitOpen
        }

        while (true) {
            val decision = envelope.selection.select(remaining, envelope.preferConfiguredOrder)
            val step =
                nextGovernedStreamingRoute(envelope, remaining, authority)
                    ?: break
            val selection = step.selection
            val admission = step.admission
            if (admission is CircuitBreakerAdmission.Rejected) {
                lastCircuitOpen =
                    CircuitBreakerOpenException(
                        selection.route.providerName,
                        admission.blockedUntilMillis,
                    )
                remaining = selection.narrowed
                continue
            }
            val permit = (admission as CircuitBreakerAdmission.Allowed).permit

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
                    attemptStreamingCandidate(
                        run,
                        authority,
                        step.candidate,
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

    /** What the walk streams: the operation, its arguments and the conversation it answers into. */
    private data class StreamingRun(
        val operation: OperationDefinition,
        val arguments: List<Any?>,
        val tokenBudgetTracker: TokenBudgetTracker,
        val conversationId: String?,
        val effectiveMessages: List<Message>,
        val historySize: Int,
        val emitChunk: suspend (StreamChunk) -> Unit,
    )

    /** Who authorizes the walk and what the configured routes proposed. */
    private data class StreamingAuthority(
        val request: StreamingExecutionRequest,
        val securityContext: ExecutionSecurityContext,
        val correlationId: String,
        val candidates: List<ResolvedProviderRoute>,
    )

    /**
     * Asks the continuation policy about every pre-open exclusion that execution actually advances
     * past, before another candidate may execute. Approval permits continuation only: it cannot
     * restore the excluded candidate, widen the envelope, or reach a route the envelope does not
     * carry, and with no continuation target there is no transition to authorize. An exclusion
     * positioned after the candidate about to run gates nothing, so it is not consulted.
     *
     * Returns the last refusal recorded, or null when no exclusion was gated.
     */
    private suspend fun gateExcludedStreamingCandidates(
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
                    correlationId,
                    excludedRoute.providerName,
                    excludedRoute.effectiveModelName,
                    envelope.routeOf(continuationCandidate).providerName,
                    "circuit-breaker-open",
                    securityContext,
                )
            } catch (policyError: PolicyViolationException) {
                policyError.addSuppressed(circuitOpen)
                throw policyError
            }
        }
        return lastOpen
    }

    /** The route governance selected, the remainder after removing it, and its successor. */
    private data class GovernedStreamingRouteSelection(
        val route: ResolvedProviderRoute,
        val routeIndex: Int,
        val narrowed: ViableCandidates,
        val nextRoute: ResolvedProviderRoute?,
    )

    /** The route governance selected, with the breaker's admission decision for it. */
    private data class GovernedStreamingStep(
        val selection: GovernedStreamingRouteSelection,
        val admission: CircuitBreakerAdmission,
    ) {
        /** The candidate those two together describe: what the attempt is handed. */
        val candidate: StreamingCandidate
            get() = StreamingCandidate(selection.route, selection.routeIndex, selection.nextRoute)
    }

    /**
     * Answers the walk's one recurring question: which route runs next, and is it admissible.
     *
     * Selection and admission belong together because the fallback handoff must be built from the
     * route the breaker actually admitted, and because a refusal is the caller's to record - the walk
     * owns the last circuit refusal and the narrowed remainder. Null means governance selected nothing,
     * which ends the walk.
     */
    private suspend fun nextGovernedStreamingRoute(
        envelope: GovernedProviderEnvelope,
        remaining: ViableCandidates,
        authority: StreamingAuthority,
    ): GovernedStreamingStep? {
        val selection = selectGovernedStreamingRoute(envelope, remaining) ?: return null
        val admission =
            handleCircuitBreakerOpenRoute(
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
    private fun selectGovernedStreamingRoute(
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
     * Maps one route result to what happens next, or null when the route should keep retrying. Kept
     * separate so the attempt loop stays flat enough to read.
     */
    private suspend fun routeOutcomeFor(
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

    /** What one route's attempts decided: either the collection leaves, or the route ends. */
    private sealed interface StreamingRouteAttemptOutcome {
        /** The route reached its end for the caller: the turn is persisted or the error forwarded. */
        object Finished : StreamingRouteAttemptOutcome

        /** The route is finished without completing; the caller records the failure and advances. */
        data class Stop(
            val error: Throwable,
        ) : StreamingRouteAttemptOutcome

        /** The attempt budget ran out with no decision; the caller advances to the next route. */
        object Exhausted : StreamingRouteAttemptOutcome
    }

    /**
     * Bridges a collection job to the caller's collector through a RENDEZVOUS channel.
     *
     * The provider collection runs as a child of the engine's OWN lifecycle job (lifecycleScope), NOT
     * the collector's job: close() cancels lifecycleJob, which cancels an in-flight collection
     * (including the provider stream's cleanup), and close() joins lifecycleJob, so the collection has
     * terminated before close() returns. Chunks are bridged to the caller's emit through a RENDEZVOUS
     * channel because emit must stay in the collector's coroutine (SafeCollector invariant), and a
     * rendezvous keeps Flow backpressure semantics: a slow caller blocks the provider instead of
     * letting it race ahead into unbounded buffering.
     *
     * A failure is surfaced to the collector WITHOUT rethrowing it here: rethrowing would let an
     * arbitrary (possibly sensitive, externally supplied) throwable reach the lifecycle scope's
     * CoroutineExceptionHandler and the normal logger. The collector rethrows it after the drain.
     * Cancellation stays cancellation.
     */
    private fun streamChunksToCollector(lifecycleBody: suspend (Channel<StreamChunk>) -> Unit): Flow<StreamChunk> =
        flow {
            check(!isClosed.get()) { "Tramai runtime is closed" }
            val chunks = Channel<StreamChunk>(Channel.RENDEZVOUS)
            val collectFailure =
                java.util.concurrent.atomic
                    .AtomicReference<Throwable?>(null)
            val collectJob =
                lifecycleScope.launch {
                    try {
                        lifecycleBody(chunks)
                    } catch (e: CancellationException) {
                        // The engine closed (or the collector stopped): terminate the collection job
                        // normally; the invokeOnCompletion below closes the channel with the cause.
                        throw e
                    } catch (failure: Throwable) {
                        failure.rethrowIfCancellation()
                        collectFailure.set(failure)
                    }
                }
            // Channel termination depends on JOB completion, not on the coroutine body having started:
            // if close() cancels lifecycleJob after the flow's open check but before this launch's body
            // runs, the body's finally never executes, but invokeOnCompletion still fires, closing the
            // channel so the collector terminates instead of hanging forever on receive.
            collectJob.invokeOnCompletion { cause -> chunks.close(cause) }
            drainStreamingChunks(chunks, collectFailure, collectJob) { chunk -> emit(chunk) }
        }

    /**
     * Forwards the route's chunks to the caller and rethrows whatever the collection job failed with.
     *
     * The channel closes whether the collection job succeeded or failed, so a failure must surface
     * here rather than letting the flow complete silently. The closed-runtime check stays per chunk,
     * and the engine-owned collection job is always stopped: if the caller stops collecting, or the
     * engine closes, that job must not keep running.
     */
    private suspend fun drainStreamingChunks(
        chunks: Channel<StreamChunk>,
        collectFailure: java.util.concurrent.atomic.AtomicReference<Throwable?>,
        collectJob: Job,
        emit: suspend (StreamChunk) -> Unit,
    ) {
        try {
            for (chunk in chunks) {
                check(!isClosed.get()) { "Tramai runtime is closed" }
                emit(chunk)
            }
            rethrowCollected(collectFailure.get())
        } finally {
            collectJob.cancel()
            collectJob.join()
        }
    }

    /**
     * Persists the assistant turn for a completed streaming route. A run without a conversation id
     * persists nothing, exactly as the inline form did.
     */
    private suspend fun persistStreamingTurn(
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
    private suspend fun handleStreamingStartupFailure(
        result: StreamingRouteResult.StartupFailure,
        handoff: FallbackHandoff,
        budget: RouteAttemptBudget,
        retryIndex: Int,
    ): Boolean {
        val decision = retryPolicy.decide(result.error, retryIndex, budget.maxAttempts)
        if (retryIndex == 0 && (decision is ProviderRetryDecision.Retry || handoff.nextRoute != null)) {
            recordStartupRetryEvent(
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

    /** What the fallback gate is told when a route fails and continuation is permitted. */
    private data class FallbackHandoff(
        val route: ResolvedProviderRoute,
        val nextRoute: ResolvedProviderRoute?,
        val correlationId: String,
        val securityContext: ExecutionSecurityContext,
    )

    /** One route's retry authority: the permit its attempts share and how many attempts it gets. */
    private data class RouteAttemptBudget(
        val permit: CircuitBreakerPermit,
        val maxAttempts: Int,
    )

    /**
     * Rethrows a failure collected by the engine-owned collection job, once its channel has closed.
     * Extracted so the governed flow keeps a bounded number of throw sites while preserving the
     * behaviour exactly: the failure the collector saw is what the caller sees.
     */
    private fun rethrowCollected(failure: Throwable?) {
        if (failure != null) {
            throw failure
        }
    }

    private fun executeGoverned(request: StreamingExecutionRequest): Flow<StreamChunk> {
        val operation = request.operation
        val arguments = request.arguments
        val tokenBudgetTracker = request.tokenBudgetTracker
        val conversationId = request.conversationId
        val securityContext = ExecutionSecurityContext.fromArguments(arguments.toTypedArray())
        val initialMessages = operation.initialMessages(arguments)
        val prepared = conversationMemoryCoordinator.prepareMessages(initialMessages, conversationId)
        val history = prepared?.history ?: emptyList()
        val effectiveMessages = prepared?.effectiveMessages ?: initialMessages

        return streamChunksToCollector { chunks ->
            val correlationId = identitySource.newCorrelationId()
            require(correlationId.isNotBlank()) { "Engine correlationId must not be blank" }
            beforeResolution.beforeResolution(operation, correlationId, securityContext)
            val candidates = routingPlan.resolveCandidates(operation.operation)
            walkGovernedStreamingRoutes(
                StreamingRun(
                    operation = operation,
                    arguments = arguments,
                    tokenBudgetTracker = tokenBudgetTracker,
                    conversationId = conversationId,
                    effectiveMessages = effectiveMessages,
                    historySize = history.size,
                    emitChunk = { chunks.send(it) },
                ),
                StreamingAuthority(
                    request = request,
                    securityContext = securityContext,
                    correlationId = correlationId,
                    candidates = routingPlan.resolveCandidates(operation.operation),
                ),
            )
        }
    }

    private data class StreamingExecutionRoute(
        val operation: OperationDefinition,
        val route: ResolvedProviderRoute,
        val routeIndex: Int,
        val attempt: Int,
        val tokenBudgetTracker: TokenBudgetTracker,
        val memoryMessages: List<Message>,
        val historySize: Int,
        val conversationId: String?,
        val emitChunk: suspend (StreamChunk) -> Unit,
        val permit: CircuitBreakerPermit,
    )

    /** A provider without streaming support is refused, releasing its permit first. */
    private fun failStreamingCapability(
        route: ResolvedProviderRoute,
        request: StreamingExecutionRoute,
        circuitBreaker: ProviderCircuitBreaker,
    ): Nothing {
        circuitBreaker.onAbandoned(request.permit)
        throw ProviderCapabilityException(route.providerName, "streaming")
    }

    private suspend fun executeStreamingRoute(
        request: StreamingExecutionRoute,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        arguments: List<Any?>,
    ): StreamingRouteResult {
        val route = request.route
        val observation = startStreamingObservation(route, request.operation, request.attempt, request.routeIndex)
        try {
            authorizeStreamingRoute(route, observation)
            beforeResponseReturn.enforce(route, correlationId, securityContext)
            toolExposureCoordinator.enforce(request.operation, correlationId, securityContext)
            beforeInvocation.invoke(route.providerName, route.effectiveModelName, correlationId, securityContext)
        } catch (error: CancellationException) {
            circuitBreaker.onAbandoned(request.permit)
            throw error
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            circuitBreaker.onAbandoned(request.permit)
            throw error
        }

        val streamCapable =
            route.provider as? StreamCapable
                ?: failStreamingCapability(route, request, circuitBreaker)
        val modelRequest = request.operation.toRequest(arguments, modelName = route.effectiveModelName)
        val memoryInjectedRequest = request.memoryMessages?.let { modelRequest.copy(messages = it) } ?: modelRequest
        return collectStreamingRoute(
            StreamingRouteCall(
                streamCapable,
                memoryInjectedRequest,
                request.operation,
                route,
                request.attempt,
                observation,
                request.tokenBudgetTracker,
                request.emitChunk,
                request.permit,
            ),
        )
    }

    private suspend fun authorizeStreamingRoute(
        route: ResolvedProviderRoute,
        observation: OperationObservation,
    ) {
        try {
            modelRegistryEnforcer.authorize(route.providerName, route.effectiveModelName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelRegistryException) {
            observation.onCallCompleted(parseSuccess = null)
            throw e
        }
    }

    private suspend fun handleCircuitBreakerOpenRoute(
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
                    correlationId,
                    route.providerName,
                    route.effectiveModelName,
                    nextRoute.providerName,
                    "circuit-breaker-open",
                    securityContext,
                )
            } catch (policyError: PolicyViolationException) {
                policyError.addSuppressed(circuitOpen)
                throw policyError
            }
        }
        return admission
    }

    private suspend fun enforceStreamingFallbackAfterFailure(
        error: Throwable,
        route: ResolvedProviderRoute,
        nextRoute: ResolvedProviderRoute?,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
        if (nextRoute == null) return
        try {
            fallbackGate.transition(
                correlationId,
                route.providerName,
                route.effectiveModelName,
                nextRoute.providerName,
                "streaming-startup-failure",
                securityContext,
            )
        } catch (policyError: PolicyViolationException) {
            policyError.addSuppressed(error)
            throw policyError
        }
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
    private fun circuitOpenExhaustion(envelope: GovernedProviderEnvelope): CircuitBreakerOpenException? {
        if (envelope.authorizedNothing) return null
        return envelope.configuredOrder
            .mapNotNull { candidate ->
                circuitBreaker.openUntilMillis(candidate.providerId)?.let { until ->
                    CircuitBreakerOpenException(candidate.providerId, until)
                }
            }.firstOrNull()
    }

    private fun noAvailableStreamingRouteChunk(
        operation: OperationDefinition,
        lastFailure: Throwable?,
        lastCircuitOpen: CircuitBreakerOpenException?,
    ): StreamChunk.Error =
        StreamChunk.Error(
            (
                lastFailure
                    ?: lastCircuitOpen
                    ?: ProviderException(
                        message = "No available streaming provider route for model '${operation.operation.model}'",
                        retryable = true,
                    )
            ) as TramaiException,
        )

    private suspend fun collectStreamingRoute(call: StreamingRouteCall): StreamingRouteResult {
        val streamCapable = call.streamCapable
        val request = call.request
        val operation = call.operation
        val route = call.route
        val attempt = call.attempt
        val observation = call.observation
        val tokenBudgetTracker = call.tokenBudgetTracker
        val emitChunk = call.emitChunk
        var emittedAnyTokens = false
        val callContext = streamingCallContext(operation, route.providerName, attempt)
        val interceptedRequest = request.copy(messages = operationInterceptor.interceptRequest(callContext, request.messages))
        val permit = call.permit
        val ctx =
            StreamingRouteContext(
                route,
                operation,
                tokenBudgetTracker,
                callContext,
                observation,
                emitChunk,
                permit,
                { emittedAnyTokens },
                { chunk ->
                    emittedAnyTokens = true
                    emitChunk(chunk)
                },
            )
        return try {
            collectStreamingRouteChunks(
                streamCapable,
                interceptedRequest,
                timeoutMillisOf(request, operation),
                ctx,
            )
            error("Streaming route completed without a terminal result")
        } catch (finished: StreamingRouteFinished) {
            finished.result
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            val timeout = streamingTimeout(route, operation, request, error)
            observation.onProviderFailure(timeout)
            handleFallbackResult(timeout, emittedAnyTokens, ctx)
        } catch (error: CancellationException) {
            observation.completeCancellation(error)
            circuitBreaker.onAbandoned(permit)
            throw error
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            val normalized = normalizeStreamingError(error, route.providerName, operation)
            observation.onProviderFailure(normalized)
            handleFallbackResult(normalized, emittedAnyTokens, ctx)
        }
    }

    private data class StreamingRouteCall(
        val streamCapable: StreamCapable,
        val request: ModelRequest,
        val operation: OperationDefinition,
        val route: ResolvedProviderRoute,
        val attempt: Int,
        val observation: OperationObservation,
        val tokenBudgetTracker: TokenBudgetTracker,
        val emitChunk: suspend (StreamChunk) -> Unit,
        val permit: CircuitBreakerPermit,
    )

    private fun streamingCallContext(
        operation: OperationDefinition,
        providerId: String,
        attempt: Int,
    ) = OperationCallContext(
        serviceInterface = serviceTypeName,
        methodName = operation.method.name,
        providerId = providerId,
        requestedModel = operation.operation.model,
        attempt = attempt,
    )

    private fun startStreamingObservation(
        route: ResolvedProviderRoute,
        operation: OperationDefinition,
        attempt: Int,
        routeIndex: Int,
    ): OperationObservation =
        operationObserver
            .onCallStarted(
                OperationCallContext(
                    serviceInterface = serviceTypeName,
                    methodName = operation.method.name,
                    providerId = route.providerName,
                    requestedModel = operation.operation.model,
                    attempt = attempt,
                ),
            ).also { observation ->
                observation.emitRuntimeEvent(
                    RuntimeEvent.of(RuntimeEvents.ROUTE_SELECTED) {
                        set(RuntimeAttributes.PROVIDER_ID, route.providerName)
                        set(RuntimeAttributes.EFFECTIVE_MODEL, route.effectiveModelName)
                        set(RuntimeAttributes.ROUTE_INDEX, routeIndex.toLong())
                        set(RuntimeAttributes.IS_FALLBACK, routeIndex > 0)
                    },
                )
            }

    private data class StreamingRouteContext(
        val route: ResolvedProviderRoute,
        val operation: OperationDefinition,
        val tokenBudgetTracker: TokenBudgetTracker,
        val callContext: OperationCallContext,
        val observation: OperationObservation,
        val emitChunk: suspend (StreamChunk) -> Unit,
        val permit: CircuitBreakerPermit,
        /** Live predicate: whether any token has been emitted for this route so far. */
        val hasEmittedTokens: () -> Boolean,
        /** The caller's token sink for this route. */
        val onToken: suspend (StreamChunk.Token) -> Unit,
    )

    private suspend fun collectStreamingRouteChunks(
        streamCapable: StreamCapable,
        request: ModelRequest,
        timeoutMillis: Long,
        ctx: StreamingRouteContext,
    ) {
        withTimeout(timeoutMillis) {
            streamCapable.stream(request).collect { chunk -> handleStreamingChunk(chunk, ctx) }
            handleStreamingTerminationWithoutTerminalChunk(ctx, ctx.hasEmittedTokens())
        }
    }

    private suspend fun handleStreamingChunk(
        chunk: StreamChunk,
        ctx: StreamingRouteContext,
    ) {
        when (chunk) {
            is StreamChunk.Token -> {
                ctx.onToken(chunk)
            }

            is StreamChunk.Complete -> {
                handleStreamingComplete(chunk, ctx)
            }

            is StreamChunk.Error -> {
                ctx.observation.onProviderFailure(chunk.cause)
                finishStreamingRoute(
                    handleFallbackResult(chunk.cause, ctx.hasEmittedTokens(), ctx, chunk),
                )
            }
        }
    }

    private suspend fun handleStreamingComplete(
        chunk: StreamChunk.Complete,
        ctx: StreamingRouteContext,
    ) {
        val response =
            ModelResponse(
                content = chunk.fullText,
                inputTokens = chunk.usage.inputTokens,
                outputTokens = chunk.usage.outputTokens,
                thinkingTokens = chunk.usage.thinkingTokens,
                modelUsed = ctx.route.effectiveModelName,
                finishReason = FinishReason.STOP,
            )
        val interceptedResponse = operationInterceptor.interceptResponse(ctx.callContext, response)
        ctx.observation.onProviderResponse(interceptedResponse)
        try {
            tokenBudgetCoordinator.enforce(
                ctx.tokenBudgetTracker,
                interceptedResponse,
                ctx.observation,
                ctx.route.providerName,
                ctx.route.effectiveModelName,
            )
        } catch (error: TokenBudgetExceededException) {
            ctx.observation.onCallCompleted(parseSuccess = null)
            circuitBreaker.onAbandoned(ctx.permit)
            throw StreamingRouteFinished(StreamingRouteResult.TerminalError(StreamChunk.Error(error)))
        }
        ctx.observation.onCallCompleted(parseSuccess = null)
        circuitBreaker.onSuccess(ctx.permit)
        val corrected =
            if (interceptedResponse.content != chunk.fullText) {
                chunk.copy(fullText = interceptedResponse.content)
            } else {
                chunk
            }
        ctx.emitChunk(corrected)
        throw StreamingRouteFinished(StreamingRouteResult.Completed(interceptedResponse.content))
    }

    private fun handleStreamingTerminationWithoutTerminalChunk(
        ctx: StreamingRouteContext,
        emittedAnyTokens: Boolean,
    ): Nothing {
        val error =
            ProviderException(
                message =
                    "Provider ${ctx.route.providerName} ended streaming without a terminal chunk " +
                        "while invoking $qualifiedServiceName.${ctx.operation.method.name}",
            )
        ctx.observation.onProviderFailure(error)
        finishStreamingRoute(handleFallbackResult(error, emittedAnyTokens, ctx))
    }

    private fun normalizeStreamingError(
        error: Throwable,
        providerName: String,
        operation: OperationDefinition,
    ): TramaiException =
        when (error) {
            is TramaiException -> {
                error
            }

            else -> {
                ProviderException(
                    message =
                        "Provider $providerName failed while streaming " +
                            "$qualifiedServiceName.${operation.method.name}",
                    cause = error,
                )
            }
        }

    private fun recordCircuitBreakerFailure(
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

    private fun recordStartupRetryEvent(
        providerName: String,
        failureType: String,
        observation: OperationObservation,
    ) {
        observation.emitRuntimeEvent(
            RuntimeEvent.of(RuntimeEvents.STREAMING_STARTUP_RETRY) {
                set(RuntimeAttributes.PROVIDER_ID, providerName)
                set(RuntimeAttributes.FAILURE_TYPE, failureType)
            },
        )
    }

    private fun handleFallbackResult(
        error: TramaiException,
        emittedAnyTokens: Boolean,
        ctx: StreamingRouteContext,
        terminalChunk: StreamChunk.Error = StreamChunk.Error(error),
    ): StreamingRouteResult {
        val result =
            if (!emittedAnyTokens && shouldFallbackFrom(error)) {
                // Retryable STARTUP failure (no token yet): the route loop decides
                // retry-vs-exhausted via ProviderRetryPolicy. The breaker is NOT
                // touched here — an intermediate retry must not record a breaker
                // failure (8.2h P0-K); the terminal exhausted failure records in
                // the route loop's Stop branch. STREAMING_STARTUP_RETRY is emitted
                // once per ctx.route by the route loop (retryIndex == 0), not here.
                StreamingRouteResult.StartupFailure(error, ctx.observation)
            } else {
                // Terminal: non-retryable, post-token failure, or fallback-disallowed.
                // This completes breaker authority for the ctx.route.
                recordCircuitBreakerFailure(ctx.permit, error, ctx.observation)
                StreamingRouteResult.TerminalError(terminalChunk)
            }
        ctx.observation.onCallCompleted(parseSuccess = null)
        return result
    }

    private fun finishStreamingRoute(result: StreamingRouteResult): Nothing = throw StreamingRouteFinished(result)

    private fun shouldFallbackFrom(error: Throwable): Boolean =
        when (error) {
            is CircuitBreakerOpenException -> true
            is TimeoutException -> true
            is ProviderException -> error.retryable
            else -> false
        }

    private fun timeoutMillisOf(
        request: ModelRequest,
        operation: OperationDefinition,
    ): Long = request.timeoutMillis ?: operation.operation.timeoutMillis

    /** The timeout failure for a route that ran out of time while streaming. */
    private fun streamingTimeout(
        route: ResolvedProviderRoute,
        operation: OperationDefinition,
        request: ModelRequest,
        error: TimeoutCancellationException,
    ): TimeoutException =
        TimeoutException(
            message = buildTimeoutMessage(route.providerName, operation, timeoutMillisOf(request, operation)),
            cause = error,
        )

    private fun buildTimeoutMessage(
        providerId: String,
        operation: OperationDefinition,
        timeoutMillis: Long,
    ): String =
        "Provider $providerId timed out after ${timeoutMillis}ms while invoking " +
            "$qualifiedServiceName.${operation.method.name}"

    private fun OperationObservation.completeCancellation(cancellation: CancellationException) {
        try {
            onCallCancelled()
        } catch (observerError: Throwable) {
            cancellation.addSuppressed(observerError)
        }
    }
}
