package dev.tramai.core.retry

/**
 * Authority for retry-jitter randomness (Epic 8.3b1; relocated to tramai-core
 * in 8.3d so that engine provider-retry policy and orchestration HTTP-step
 * backoff share exactly one manufacture point).
 *
 * Every production retry-jitter sample is drawn through this source; no
 * module manufactures retry randomness itself.
 */
fun interface RetryJitterSource {
    fun nextDouble(): Double
}

/** Default jitter authority backed by the process-wide [Random]. */
object DefaultRetryJitterSource : RetryJitterSource {
    override fun nextDouble(): Double = kotlin.random.Random.nextDouble()
}
