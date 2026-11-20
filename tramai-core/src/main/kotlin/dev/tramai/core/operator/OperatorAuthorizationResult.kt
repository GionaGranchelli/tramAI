package dev.tramai.core.operator

/**
 * The result of checking one [OperatorCapability] against an [OperatorPrincipal] (0.7.6b).
 *
 * Typed rather than boolean so a denial names the capability that was missing. A `Boolean` would let
 * an adversarial test pass on an unrelated failure — "the call did not succeed" is not evidence that
 * authorization refused it. 0.7.6g asserts the specific [Denied.missing] value and that authoritative
 * state did not change.
 */
sealed interface OperatorAuthorizationResult {
    /** The principal holds the requested capability. */
    data object Authorized : OperatorAuthorizationResult

    /** The principal does not hold [missing]. */
    data class Denied(
        val missing: OperatorCapability,
    ) : OperatorAuthorizationResult
}
