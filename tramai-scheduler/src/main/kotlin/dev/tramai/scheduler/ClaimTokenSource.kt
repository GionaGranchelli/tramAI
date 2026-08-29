package dev.tramai.scheduler

import java.util.UUID

/**
 * Authority for scheduler claim-token capability generation (Epic 8.3d).
 *
 * Every newly-created tick/delay-wakeup claim token originates from this
 * source; neither the in-memory nor the JDBC scheduler store manufactures
 * claim tokens itself. A claim token is a fencing/capability token: it must
 * be opaque and unpredictable, and its generation belongs to exactly one
 * composition-boundary source.
 */
fun interface ClaimTokenSource {
    fun newClaimToken(): String
}

object DefaultClaimTokenSource : ClaimTokenSource {
    override fun newClaimToken(): String = UUID.randomUUID().toString()
}
