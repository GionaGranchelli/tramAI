package io.aurora.testing

import io.aurora.core.model.ModelRequest

/**
 * Common contract for deterministic test providers that capture Aurora requests.
 */
interface RecordedRequestProvider {
    /** Requests observed in invocation order. */
    val requests: MutableList<ModelRequest>
}
