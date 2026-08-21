package dev.tramai.core.observation.secondary

/**
 * Marks secondary-failure machinery that is public only because it crosses
 * module boundaries — NOT application-facing API.
 *
 * The failure-isolating wrappers, the safe diagnostic, and the authority /
 * policy model exist so the engine, orchestration, and scheduler modules can
 * enforce one common failure boundary at their composition points. They are
 * intentionally not part of Tramai's stable application-facing surface; the
 * annotation makes that contract explicit and opt-in (same precedent as
 * [dev.tramai.openai.ExperimentalCodexAuth]).
 */
@RequiresOptIn(
    message = "Tramai secondary-failure machinery is internal implementation API for cross-module " +
        "composition, not application-facing API. It may change or move in any release.",
    level = RequiresOptIn.Level.WARNING,
)
annotation class ExperimentalTramaiInternalApi
