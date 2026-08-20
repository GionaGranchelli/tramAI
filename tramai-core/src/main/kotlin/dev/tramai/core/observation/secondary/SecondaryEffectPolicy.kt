package dev.tramai.core.observation.secondary

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Epic 5.3 — explicit failure contract for secondary (non-business) effects.
 *
 * The classification axis is AUTHORITATIVE vs NON_AUTHORITATIVE first
 * (authoritative audit and evidence may stop a governed operation; telemetry
 * must not), and observer-vs-audit-vs-evidence second. This model is the
 * internal extension-point machinery behind the public
 * [dev.tramai.core.observation.event.RuntimeEventFailurePolicy]: it is public
 * only because the failure-isolating observer wrappers live in several
 * modules, and is not part of the stable application-facing API.
 */
@ExperimentalTramaiInternalApi
enum class SecondaryEffectAuthority {
    /** A governed audit/evidence effect: failure blocks the operation (fail-closed). */
    AUTHORITATIVE,

    /** Ordinary telemetry: failure must never change the business outcome. */
    NON_AUTHORITATIVE,
}

/**
 * What happens when a secondary effect fails, at the extension point.
 *
 * - [FAIL_CLOSED]: the failure propagates and the operation fails. Reserved for
 *   authoritative audit/evidence surfaces (and catalogue events declared
 *   `FAIL_CLOSED`).
 * - [FAIL_OPEN_DIAGNOSTIC]: the failure is contained and a safe diagnostic is
 *   emitted. Default for observers, metrics, and adapters.
 * - [RETRY]: the effect is retained and attempted later (outbox dispatch:
 *   at-least-once, business outcome untouched).
 * - [BUFFERED]: the effect is buffered and delivered asynchronously
 *   (outbox enqueue: fail-closed at enqueue, at-least-once at delivery).
 * - [IGNORE]: explicitly non-authoritative best-effort (optional diagnostics).
 */
@ExperimentalTramaiInternalApi
enum class SecondaryFailureDisposition {
    FAIL_CLOSED,
    FAIL_OPEN_DIAGNOSTIC,
    RETRY,
    BUFFERED,
    IGNORE,
}

/**
 * Safe diagnostic for contained secondary failures.
 *
 * Carries only stable identifiers — extension point, callback name, error
 * type, policy, authority. NEVER error messages, stack traces, workflow state,
 * prompts, or tool arguments. The sink is itself guarded: a diagnostic failure
 * can never resurrect into the business path.
 *
 * This type is public only because the failure-isolating wrappers live in
 * several modules; it is not part of the stable application-facing API.
 */
@ExperimentalTramaiInternalApi
object SecondaryFailureDiagnostic {
    private val logger = Logger.getLogger("dev.tramai.core.observation.secondary")

    fun report(
        extensionPoint: String,
        callback: String,
        errorType: String,
        failurePolicy: String,
        authority: String,
    ) {
        try {
            logger.log(
                Level.WARNING,
                "Secondary effect failure contained: extensionPoint={0} callback={1} errorType={2} failurePolicy={3} authority={4}",
                arrayOf(extensionPoint, callback, errorType, failurePolicy, authority),
            )
        } catch (_: Throwable) {
            // ponytail: the diagnostic must never resurrect into the business
            // path; add an out-of-band sink only if JUL is replaced.
        }
    }
}
