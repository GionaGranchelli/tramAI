package dev.tramai.spring.sovereign.ops.outbox

/**
 * Thrown when the heartbeat coroutine detects the worker lease has been
 * lost (expired, stolen, or missing) during delegate execution.
 *
 * A non-cancellation [RuntimeException] so that it reliably cancels the
 * parent [kotlinx.coroutines.coroutineScope] — unlike
 * [kotlinx.coroutines.CancellationException], which behaves as normal
 * cancellation in child coroutines and does not always fail the parent.
 */
class SovereignOpsWorkerLeaseLostException(
    message: String,
) : RuntimeException(message)
