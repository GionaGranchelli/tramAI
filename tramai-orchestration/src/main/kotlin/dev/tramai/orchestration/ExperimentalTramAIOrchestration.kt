package dev.tramai.orchestration

/**
 * Marks the optional TramAI orchestration module as experimental.
 *
 * The workflow runtime is shipped and tested, but the public API is still expected to evolve
 * while the module matures.
 */
@RequiresOptIn(
    message = "TramAI orchestration is experimental and its API may evolve between releases.",
    level = RequiresOptIn.Level.WARNING,
)
annotation class ExperimentalTramAIOrchestration
