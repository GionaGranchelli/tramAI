package dev.tramai.engine.provider

/**
 * Epic 8.2h — deterministic retry/fallback script generator.
 *
 * The SCRIPT is the single independent specification of the scenario. Every
 * action declares its OWN route index; the model never decides which provider
 * a response belongs to (oracle-independence requirement, reviewer round 4).
 * The generator reasons about the retry budget itself (a route can consume at
 * most providerRetries + 1 attempts before exhaustion) so every script's
 * declared route progression is consistent with the model's decisions; the
 * property harness then validates that consistency with require() on every
 * action.
 *
 * Forced archetypes (semantic coverage guard — never rely on random
 * probability): retry->success, retry->retry->exhausted, exhausted->fallback->
 * success, exhausted->fallback->exhausted, primary circuit-open->fallback, all
 * routes circuit-open, retry-after, cancellation during retry, streaming
 * failure after token, permanent failure, mixed provider failure + later
 * circuit-open, explicit provider, fallback denial, retry->permanent neutral,
 * double fallback traversal.
 */
internal sealed interface RetryFallbackScriptAction {
    data class Admit(val routeIndex: Int, val circuitOpen: Boolean = false) : RetryFallbackScriptAction
    data class Attempt(val routeIndex: Int, val outcome: AttemptOutcome) : RetryFallbackScriptAction
    data class EmitToken(val tokenCount: Int = 1) : RetryFallbackScriptAction
}

internal data class RetryFallbackScript(
    val providerRetries: Int,
    val routeCount: Int,
    val actions: List<RetryFallbackScriptAction>,
    val explicitProvider: Boolean = false,
    val fallbackDenied: Boolean = false,
) {
    init {
        require(routeCount >= 1)
        require(providerRetries >= 0)
    }
}

internal object ProviderRetryFallbackActionGenerator {

    fun generate(seed: Long, providerRetries: Int = 1, routeCount: Int = 2): RetryFallbackScript {
        val rnd = java.util.Random(seed)
        val archetype = (seed % ARCHETYPES.size).toInt()
        return when (ARCHETYPES[archetype]) {
            "retry-success" -> script(providerRetries, routeCount) {
                admit(0); attempt(0, Retryable)
                if (providerRetries >= 1) attempt(0, Success)
                else if (routeCount > 1) { admit(1); attempt(1, Success) }
            }
            "retry-retry-exhausted" -> script(providerRetries, 1) {
                admit(0); repeat(providerRetries + 1) { attempt(0, Retryable) }
            }
            "exhausted-fallback-success" -> script(providerRetries, routeCount) {
                admit(0); repeat(providerRetries + 1) { attempt(0, Retryable) }; admit(1); attempt(1, Success)
            }
            "exhausted-fallback-exhausted" -> script(providerRetries, routeCount) {
                admit(0); repeat(providerRetries + 1) { attempt(0, Retryable) }; admit(1); repeat(providerRetries + 1) { attempt(1, Retryable) }
            }
            "primary-open-fallback" -> script(providerRetries, routeCount) {
                admitOpen(0); admit(1); attempt(1, Success)
            }
            "all-open" -> script(providerRetries, routeCount) {
                admitOpen(0); if (routeCount > 1) admitOpen(1)
            }
            "retry-after" -> script(providerRetries, routeCount) {
                admit(0); attempt(0, RetryableAfter)
                if (providerRetries >= 1) attempt(0, Success)
                else if (routeCount > 1) { admit(1); attempt(1, Success) }
            }
            "cancel-during-retry" -> script(providerRetries, routeCount) {
                admit(0); attempt(0, Retryable)
                if (providerRetries >= 1) attempt(0, Cancellation)
                else if (routeCount > 1) { admit(1); attempt(1, Cancellation) }
            }
            "token-then-failure" -> script(providerRetries, routeCount) {
                admit(0); emitToken(); attempt(0, Retryable)
            }
            "permanent-no-recovery" -> script(providerRetries, routeCount) {
                admit(0); attempt(0, Permanent)
            }
            "mixed-failure-then-open" -> script(providerRetries, routeCount) {
                admit(0); repeat(providerRetries + 1) { attempt(0, Timeout) }; admitOpen(1)
            }
            "explicit-provider" -> script(1, 1, explicit = true) {
                // The explicit-provider fixture (@Operation(provider = "p0",
                // providerRetries = 1)) fixes the budget at 1 regardless of the
                // requested corpus budget — the annotation cannot vary.
                admit(0); attempt(0, Retryable); attempt(0, Retryable)
            }
            "fallback-denied" -> script(providerRetries, routeCount, denied = true) {
                admit(0); repeat(providerRetries + 1) { attempt(0, Retryable) }
            }
            "retry-permanent-neutral" -> script(providerRetries, 1) {
                admit(0); attempt(0, Retryable); attempt(0, Permanent)
            }
            "double-fallback-success" -> script(providerRetries, 3) {
                admit(0); repeat(providerRetries + 1) { attempt(0, Retryable) }
                admit(1); repeat(providerRetries + 1) { attempt(1, Retryable) }
                admit(2); attempt(2, Success)
            }
            "random" -> script(providerRetries, routeCount) {
                var route = 0
                var retry = 0
                val maxAttempts = providerRetries + 1
                repeat(2 + rnd.nextInt(4)) {
                    val outcome = randomOutcome(rnd)
                    val visibilityVisible = rnd.nextBoolean()
                    if (outcome == Cancellation) { attempt(route, Cancellation); return@script }
                    if (visibilityVisible && outcome in retryableOutcomes) {
                        emitToken(); attempt(route, outcome); return@script
                    }
                    attempt(route, outcome)
                    when (outcome) {
                        Success -> return@script
                        in retryableOutcomes -> {
                            retry++
                            if (retry >= maxAttempts) {
                                if (route + 1 < routeCount) { route++; retry = 0; admit(route) } else return@script
                            }
                        }
                        else -> return@script
                    }
                }
                // Loop fell off mid-retry (budget not exhausted): force a
                // terminal outcome so the script always ends terminal. The model
                // driver breaks at terminal, so this is inert when already so.
                attempt(route, Success)
            }
            else -> script(providerRetries, routeCount) { admit(0); attempt(0, Success) }
        }
    }

    private fun randomOutcome(rnd: java.util.Random): AttemptOutcome = when (rnd.nextInt(10)) {
        0 -> AttemptOutcome.Success
        1, 2, 3 -> AttemptOutcome.RetryableFailure
        4 -> AttemptOutcome.RetryableFailureWithRetryAfter
        5 -> AttemptOutcome.Timeout
        6 -> AttemptOutcome.PermanentProviderFailure
        7 -> AttemptOutcome.DlpRejection
        8 -> AttemptOutcome.PolicyRejection
        else -> AttemptOutcome.Cancellation
    }

    private val retryableOutcomes = setOf(
        AttemptOutcome.RetryableFailure,
        AttemptOutcome.RetryableFailureWithRetryAfter,
        AttemptOutcome.Timeout,
    )

    private val Retryable = AttemptOutcome.RetryableFailure
    private val RetryableAfter = AttemptOutcome.RetryableFailureWithRetryAfter
    private val Timeout = AttemptOutcome.Timeout
    private val Success = AttemptOutcome.Success
    private val Permanent = AttemptOutcome.PermanentProviderFailure
    private val Cancellation = AttemptOutcome.Cancellation

    private fun script(providerRetries: Int, routeCount: Int, explicit: Boolean = false, denied: Boolean = false, build: ScriptBuilder.() -> Unit): RetryFallbackScript {
        val b = ScriptBuilder()
        b.build()
        return RetryFallbackScript(providerRetries, routeCount, b.actions, explicitProvider = explicit, fallbackDenied = denied)
    }

    private class ScriptBuilder {
        val actions = mutableListOf<RetryFallbackScriptAction>()
        fun admit(routeIndex: Int) { actions += RetryFallbackScriptAction.Admit(routeIndex) }
        fun admitOpen(routeIndex: Int) { actions += RetryFallbackScriptAction.Admit(routeIndex, circuitOpen = true) }
        fun attempt(routeIndex: Int, outcome: AttemptOutcome) { actions += RetryFallbackScriptAction.Attempt(routeIndex, outcome) }
        fun emitToken() { actions += RetryFallbackScriptAction.EmitToken(1) }
    }

    private val ARCHETYPES = listOf(
        "retry-success", "retry-retry-exhausted", "exhausted-fallback-success",
        "exhausted-fallback-exhausted", "primary-open-fallback", "all-open",
        "retry-after", "cancel-during-retry", "token-then-failure",
        "permanent-no-recovery", "mixed-failure-then-open", "explicit-provider",
        "fallback-denied", "retry-permanent-neutral", "double-fallback-success",
        "random",
    )
}
