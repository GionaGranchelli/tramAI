package dev.tramai.engine.streaming

import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.TramaiException
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.model.Message
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.provider.ResolvedProviderRoute
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.budget.TokenBudgetTracker
import dev.tramai.security.governance.ViableCandidates

internal data class StreamingExecutionRequest(
    val operation: OperationDefinition,
    val arguments: List<Any?>,
    val tokenBudgetTracker: TokenBudgetTracker,
    val conversationId: String?,
    /** The governed run this execution belongs to; absent means the path refuses rather than invoking. */
    val governedRun: GovernedRunIdentity? = null,
)

internal sealed class StreamingRouteResult {
    data class Completed(
        val fullText: String,
    ) : StreamingRouteResult()

    data class StartupFailure(
        val error: TramaiException,
        val observation: OperationObservation,
    ) : StreamingRouteResult()

    data class TerminalError(
        val errorChunk: StreamChunk.Error,
    ) : StreamingRouteResult()
}

internal class StreamingRouteFinished(
    val result: StreamingRouteResult,
) : RuntimeException(null, null, false, false)

/** The route to attempt, its configured index, and the successor a fallback would reach. */
internal data class StreamingCandidate(
    val route: ResolvedProviderRoute,
    val routeIndex: Int,
    val nextRoute: ResolvedProviderRoute?,
)

/** What admitting the next route produced: the step, and the narrowed envelope it left behind. */
internal class GovernedStreamingAdmissionStep(
    val step: GovernedStreamingStep,
    val remaining: ViableCandidates,
    val lastCircuitOpen: CircuitBreakerOpenException?,
)

/** What the walk streams: the operation, its arguments and the conversation it answers into. */
internal data class StreamingRun(
    val operation: OperationDefinition,
    val arguments: List<Any?>,
    val tokenBudgetTracker: TokenBudgetTracker,
    val conversationId: String?,
    val effectiveMessages: List<Message>,
    val historySize: Int,
    val emitChunk: suspend (StreamChunk) -> Unit,
)

/** Who authorizes the walk and what the configured routes proposed. */
internal data class StreamingAuthority(
    val request: StreamingExecutionRequest,
    val securityContext: ExecutionSecurityContext,
    val correlationId: String,
    val candidates: List<ResolvedProviderRoute>,
)

/** The route governance selected, the remainder after removing it, and its successor. */
internal data class GovernedStreamingRouteSelection(
    val route: ResolvedProviderRoute,
    val routeIndex: Int,
    val narrowed: ViableCandidates,
    val nextRoute: ResolvedProviderRoute?,
)

/** The route governance selected, with the breaker's admission decision for it. */
internal data class GovernedStreamingStep(
    val selection: GovernedStreamingRouteSelection,
    val admission: CircuitBreakerAdmission,
) {
    /** The candidate those two together describe: what the attempt is handed. */
    val candidate: StreamingCandidate
        get() = StreamingCandidate(selection.route, selection.routeIndex, selection.nextRoute)
}

/** What one route's attempts decided: either the collection leaves, or the route ends. */
internal sealed interface StreamingRouteAttemptOutcome {
    /** The route reached its end for the caller: the turn is persisted or the error forwarded. */
    object Finished : StreamingRouteAttemptOutcome

    /** The route is finished without completing; the caller records the failure and advances. */
    data class Stop(
        val error: Throwable,
    ) : StreamingRouteAttemptOutcome

    /** The attempt budget ran out with no decision; the caller advances to the next route. */
    object Exhausted : StreamingRouteAttemptOutcome
}

/** What the fallback gate is told when a route fails and continuation is permitted. */
internal data class FallbackHandoff(
    val route: ResolvedProviderRoute,
    val nextRoute: ResolvedProviderRoute?,
    val correlationId: String,
    val securityContext: ExecutionSecurityContext,
)

/** One route's retry authority: the permit its attempts share and how many attempts it gets. */
internal data class RouteAttemptBudget(
    val permit: CircuitBreakerPermit,
    val maxAttempts: Int,
)

internal data class StreamingExecutionRoute(
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
