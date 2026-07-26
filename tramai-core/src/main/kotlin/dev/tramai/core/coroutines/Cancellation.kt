package dev.tramai.core.coroutines

import kotlinx.coroutines.CancellationException

/**
 * Rethrows this throwable when it represents coroutine cancellation.
 *
 * Call this before wrapping, logging as a normal failure, retrying,
 * falling back, persisting failure state, or emitting failure evidence.
 *
 * Useful for custom providers, tools, and workflow integrations.
 */
fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) {
        throw this
    }
}
