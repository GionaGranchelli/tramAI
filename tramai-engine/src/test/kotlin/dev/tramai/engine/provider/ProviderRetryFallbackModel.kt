package dev.tramai.engine.provider

/**
 * Epic 8.2h — pure retry/fallback lifecycle model.
 *
 * The model is the authoritative oracle for the provider retry/fallback
 * decision lattice. It deliberately models ATTEMPT OUTCOMES and ROUTE
 * DISPOSITIONS, not exception classes and not emitted events (events are
 * observational; the disposition is the decision).
 *
 * Frozen vocabulary (reviewer round 3, 8.2h):
 *  - [AttemptOutcome] × [OutputVisibility] are ORTHOGONAL: the same failure
 *    yields a different disposition solely because output became visible.
 *    There is no STREAMING_FAILURE_BEFORE_TOKEN / AFTER_TOKEN outcome pair.
 *  - [RouteDisposition] is the single authoritative decision per attempt:
 *    retry same route, fallback, succeed, fail, or cancel.
 *  - Circuit-open is a ROUTE ADMISSION outcome, never an attempt outcome
 *    (P0-F: it consumes zero provider attempts).
 *  - "No route" / configuration failure is OUTSIDE the normal action lattice
 *    (resolution failure, not an in-loop route state).
 *  - Breaker accounting: one admitted route = at most ONE authoritative
 *    terminal completion (P0-K). Intermediate retryable attempts never count.
 *    A route terminating in a non-qualifying (permanent/policy/DLP) failure
 *    is a NEUTRAL terminal completion: zero qualifying breaker failures.
 */
internal sealed interface AttemptOutcome {
    data object Success : AttemptOutcome
    data object RetryableFailure : AttemptOutcome
    data object RetryableFailureWithRetryAfter : AttemptOutcome
    data object Timeout : AttemptOutcome
    data object PermanentProviderFailure : AttemptOutcome
    data object CapabilityFailure : AttemptOutcome
    data object ModelRegistryRejection : AttemptOutcome
    data object DlpRejection : AttemptOutcome
    data object PolicyRejection : AttemptOutcome
    data object OtherTerminalFailure : AttemptOutcome
    data object Cancellation : AttemptOutcome
}

/** Orthogonal, irreversible streaming visibility (8.2h OUTPUT_VISIBLE). */
internal enum class OutputVisibility { NONE, VISIBLE }

/** Route admission outcome — circuit-open is here, NOT in AttemptOutcome. */
internal sealed interface RouteAdmission {
    data object Allowed : RouteAdmission
    data class CircuitOpen(val routeIndex: Int) : RouteAdmission
}

internal enum class FailureKind {
    RETRYABLE_EXHAUSTED,
    PERMANENT,
    CAPABILITY,
    MODEL_REGISTRY,
    DLP,
    POLICY,
    OTHER_TERMINAL,
    CIRCUIT_OPEN_ONLY,
    NO_ROUTE,
}

/** The single authoritative disposition produced per attempt (8.2h invariant). */
internal sealed interface RouteDisposition {
    data class RetrySameRoute(val nextRetryIndex: Int) : RouteDisposition
    data class Fallback(val nextRouteIndex: Int) : RouteDisposition
    data class Succeeded(val routeIndex: Int) : RouteDisposition
    data class Failed(val failure: FailureKind) : RouteDisposition
    data object Cancelled : RouteDisposition
}

/** Terminal outcome carrying the retained preceding failure identity. */
internal sealed interface TerminalOutcome {
    data object Success : TerminalOutcome
    data class Failure(val failure: FailureKind) : TerminalOutcome
    data object Cancelled : TerminalOutcome

    /**
     * The fallback gate denied the transition. The deny error is authoritative;
     * the preceding provider failure identity is retained (suppressed in
     * reality). Distinct from attempt-time [FailureKind.POLICY] — this is a
     * different authority boundary (8.2h 4.4).
     */
    data class FallbackDenied(val precedingFailure: FailureKind) : TerminalOutcome
}

/**
 * The SEMANTIC breaker disposition of one admitted route (8.2h P14).
 * Distinct from raw onSuccess/onFailure/onAbandoned invocations: the
 * coordinator may legally invoke an idempotent cleanup (onAbandoned) after
 * the authoritative completion. The model counts dispositions, not calls.
 */
internal enum class BreakerDisposition { SUCCESS, QUALIFYING_FAILURE, NEUTRAL }

/** Pure model state. */
internal data class ProviderRetryFallbackModel(
    val routeCount: Int = 2,
    val providerRetries: Int = 1,
    val fallbackGateDenies: Boolean = false,
    val routeIndex: Int = 0,
    val retryIndex: Int = 0,
    val globalAttempt: Int = 0,
    val visibility: OutputVisibility = OutputVisibility.NONE,
    val retryTransitions: Int = 0,
    val fallbackTransitions: Int = 0,
    val terminalOutcome: TerminalOutcome? = null,
    val breakerQualifyingFailures: Int = 0,
    val breakerSuccesses: Int = 0,
    val breakerDispositions: List<BreakerDisposition> = emptyList(),
    val lastCircuitOpen: Boolean = false,
) {
    init {
        require(routeCount >= 1) { "routeCount must be >= 1" }
        require(providerRetries >= 0) { "providerRetries must be >= 0" }
    }

    val hasNextRoute: Boolean get() = routeIndex + 1 < routeCount
    val isTerminal: Boolean get() = terminalOutcome != null

    /** One model step. Returns the next state + the authoritative disposition (null if no attempt ran). */
    fun apply(admission: RouteAdmission, outcome: AttemptOutcome): ModelStepResult {
        require(!isTerminal) { "model already terminal" }
        return when (admission) {
            is RouteAdmission.CircuitOpen -> stepCircuitOpen(admission)
            RouteAdmission.Allowed -> stepAttempt(outcome)
        }
    }

    private fun stepCircuitOpen(admission: RouteAdmission.CircuitOpen): ModelStepResult {
        require(admission.routeIndex == routeIndex) { "circuit-open admission for a different route" }
        return if (hasNextRoute) {
            if (fallbackGateDenies) {
                // Circuit-open route owns NO permit and NO breaker disposition;
                // the denial still fails the invocation (deny error authoritative).
                val next = copy(terminalOutcome = TerminalOutcome.FallbackDenied(FailureKind.CIRCUIT_OPEN_ONLY), lastCircuitOpen = true)
                ModelStepResult(next, RouteDisposition.Failed(FailureKind.CIRCUIT_OPEN_ONLY), admission = admission)
            } else {
                // The circuit-open skip advances through the fallback gate in
                // reality (policy.fallback transition) — count it symmetrically.
                val next = copy(routeIndex = routeIndex + 1, retryIndex = 0, fallbackTransitions = fallbackTransitions + 1, lastCircuitOpen = true)
                ModelStepResult(next, RouteDisposition.Fallback(next.routeIndex), admission = admission)
            }
        } else {
            val next = copy(terminalOutcome = TerminalOutcome.Failure(FailureKind.CIRCUIT_OPEN_ONLY), lastCircuitOpen = true)
            ModelStepResult(next, RouteDisposition.Failed(FailureKind.CIRCUIT_OPEN_ONLY), admission = admission)
        }
    }

    private fun stepAttempt(outcome: AttemptOutcome): ModelStepResult {
        if (outcome == AttemptOutcome.Cancellation) {
            // Cancellation abandons the permit: NEUTRAL, no qualifying accounting.
            val next = copy(terminalOutcome = TerminalOutcome.Cancelled, breakerDispositions = breakerDispositions + BreakerDisposition.NEUTRAL)
            return ModelStepResult(next, RouteDisposition.Cancelled, admission = RouteAdmission.Allowed)
        }
        if (outcome == AttemptOutcome.Success) {
            // Success is never retry/fallback — it terminates authority even
            // after output became visible (P12 removes retry/fallback
            // authority, not the success path).
            val next = copy(
                terminalOutcome = TerminalOutcome.Success,
                breakerSuccesses = breakerSuccesses + 1,
                breakerDispositions = breakerDispositions + BreakerDisposition.SUCCESS,
            )
            return ModelStepResult(next, RouteDisposition.Succeeded(routeIndex), admission = RouteAdmission.Allowed)
        }
        if (visibility == OutputVisibility.VISIBLE) {
            // OUTPUT_VISIBLE is irreversible: no retry, no fallback — the
            // failure is terminal even if retryable (8.2h P0-D / P12).
            val failure = if (outcome in retryableOutcomes) FailureKind.RETRYABLE_EXHAUSTED else outcome.failureKind()
            val disposition = if (outcome in retryableOutcomes) BreakerDisposition.QUALIFYING_FAILURE else BreakerDisposition.NEUTRAL
            val next = copy(
                terminalOutcome = TerminalOutcome.Failure(failure),
                breakerDispositions = breakerDispositions + disposition,
                breakerQualifyingFailures = breakerQualifyingFailures + if (disposition == BreakerDisposition.QUALIFYING_FAILURE) 1 else 0,
            )
            return ModelStepResult(next, RouteDisposition.Failed(failure), admission = RouteAdmission.Allowed)
        }
        return when (outcome) {
            is AttemptOutcome.RetryableFailure,
            is AttemptOutcome.RetryableFailureWithRetryAfter,
            is AttemptOutcome.Timeout,
            -> {
                if (retryIndex < providerRetries) {
                    val next = copy(retryIndex = retryIndex + 1, globalAttempt = globalAttempt + 1, retryTransitions = retryTransitions + 1)
                    ModelStepResult(next, RouteDisposition.RetrySameRoute(next.retryIndex), admission = RouteAdmission.Allowed)
                } else if (hasNextRoute && fallbackGateDenies) {
                    // Fallback DENIED: the deny error is authoritative, the
                    // preceding failure identity is retained. The exhausted
                    // route still completed its permit: one QUALIFYING_FAILURE.
                    // The fallback gate transition WAS invoked (its hook fires
                    // before the denial throws) — count it like reality does.
                    val next = copy(
                        terminalOutcome = TerminalOutcome.FallbackDenied(FailureKind.RETRYABLE_EXHAUSTED),
                        fallbackTransitions = fallbackTransitions + 1,
                        breakerQualifyingFailures = breakerQualifyingFailures + 1,
                        breakerDispositions = breakerDispositions + BreakerDisposition.QUALIFYING_FAILURE,
                    )
                    ModelStepResult(next, RouteDisposition.Failed(FailureKind.RETRYABLE_EXHAUSTED), admission = RouteAdmission.Allowed)
                } else if (hasNextRoute) {
                    // Exhausted retryable failure is the TERMINAL outcome for
                    // THIS route: it completes breaker authority for the route's
                    // permit (one qualifying failure), THEN the execution
                    // advances to the next route (a fresh admission, own permit).
                    val next = copy(
                        routeIndex = routeIndex + 1,
                        retryIndex = 0,
                        globalAttempt = globalAttempt + 1,
                        fallbackTransitions = fallbackTransitions + 1,
                        breakerQualifyingFailures = breakerQualifyingFailures + 1,
                        breakerDispositions = breakerDispositions + BreakerDisposition.QUALIFYING_FAILURE,
                    )
                    ModelStepResult(next, RouteDisposition.Fallback(next.routeIndex), admission = RouteAdmission.Allowed)
                } else {
                    val next = copy(
                        terminalOutcome = TerminalOutcome.Failure(FailureKind.RETRYABLE_EXHAUSTED),
                        breakerQualifyingFailures = breakerQualifyingFailures + 1,
                        breakerDispositions = breakerDispositions + BreakerDisposition.QUALIFYING_FAILURE,
                    )
                    ModelStepResult(next, RouteDisposition.Failed(FailureKind.RETRYABLE_EXHAUSTED), admission = RouteAdmission.Allowed)
                }
            }
            else -> {
                // Permanent/non-retryable terminal: NEVER retry, NEVER fallback.
                // A neutral terminal completion — zero qualifying breaker
                // failures (8.2h P0-K third trace).
                val failure = outcome.failureKind()
                val next = copy(
                    terminalOutcome = TerminalOutcome.Failure(failure),
                    breakerDispositions = breakerDispositions + BreakerDisposition.NEUTRAL,
                )
                ModelStepResult(next, RouteDisposition.Failed(failure), admission = RouteAdmission.Allowed)
            }
        }
    }

    private fun AttemptOutcome.failureKind(): FailureKind = when (this) {
        AttemptOutcome.PermanentProviderFailure -> FailureKind.PERMANENT
        AttemptOutcome.CapabilityFailure -> FailureKind.CAPABILITY
        AttemptOutcome.ModelRegistryRejection -> FailureKind.MODEL_REGISTRY
        AttemptOutcome.DlpRejection -> FailureKind.DLP
        AttemptOutcome.PolicyRejection -> FailureKind.POLICY
        AttemptOutcome.OtherTerminalFailure -> FailureKind.OTHER_TERMINAL
        else -> FailureKind.OTHER_TERMINAL
    }

    private val retryableOutcomes = setOf(
        AttemptOutcome.RetryableFailure,
        AttemptOutcome.RetryableFailureWithRetryAfter,
        AttemptOutcome.Timeout,
    )

    /** Emit a token: visibility flips to VISIBLE and is irreversible. */
    fun emitToken(): ProviderRetryFallbackModel = copy(visibility = OutputVisibility.VISIBLE)
}

internal data class ModelStepResult(
    val next: ProviderRetryFallbackModel,
    val disposition: RouteDisposition,
    val admission: RouteAdmission,
)
