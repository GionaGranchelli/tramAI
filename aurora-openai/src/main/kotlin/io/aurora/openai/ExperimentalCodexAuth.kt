package io.aurora.openai

/**
 * Marks Codex/ChatGPT-backed credential reuse as experimental.
 *
 * This path is intended for local development, testing, and exploratory integrations.
 * It should not be treated as Aurora's default production authentication contract.
 */
@RequiresOptIn(
    message = "Codex/ChatGPT-backed authentication is experimental and intended for testing or local integrations.",
    level = RequiresOptIn.Level.WARNING,
)
annotation class ExperimentalCodexAuth
