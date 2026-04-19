package dev.tramai.testing

import dev.tramai.core.model.ModelRequest

/**
 * Common contract for deterministic test providers that capture Tramai requests.
 */
interface RecordedRequestProvider {
    /** Requests observed in invocation order. */
    val requests: MutableList<ModelRequest>
}
