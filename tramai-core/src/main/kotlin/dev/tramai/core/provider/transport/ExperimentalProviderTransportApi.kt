package dev.tramai.core.provider.transport

/**
 * Marks provider-transport utilities that are public only because the
 * built-in provider adapters consume them across module boundaries — NOT
 * stable application-facing API.
 *
 * These helpers centralise transport invariants (rejected-response
 * handling, JSON request framing, SSE line framing, `Retry-After` parsing)
 * for the adapters shipped in `tramai-openai`, `tramai-azure-openai`,
 * `tramai-anthropic`, `tramai-gemini`, and `tramai-ollama`. They are
 * intentionally not part of Tramai's stable application-facing surface; the
 * annotation makes that contract explicit and opt-in (same precedent as
 * [dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi],
 * scoped to the transport domain).
 */
@RequiresOptIn(
    message = "Tramai provider-transport utilities are internal implementation API for the built-in " +
        "provider adapters, not application-facing API. They may change or move in any release.",
    level = RequiresOptIn.Level.WARNING,
)
annotation class ExperimentalProviderTransportApi
