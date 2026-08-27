package dev.tramai.orchestration

/**
 * Epic 8.3a — elapsed-time seam.
 *
 * A monotonic source answers "how much time elapsed?" and must never be
 * derived from the wall clock: NTP/operator adjustments can make
 * `System.currentTimeMillis()` jump forward or even backwards, which would
 * corrupt uptime, drain budgets, and timeout authority. The default
 * [NanoTime] implementation is nanoTime-backed.
 *
 * Wall-clock absolute timestamps are a separate semantic dimension and stay
 * on `java.time.Clock` (see 8.3a P0-C/P0-D); this seam exists ONLY for
 * elapsed-time decisions.
 */
internal fun interface MonotonicTimeSource {
    fun markNow(): MonotonicMark

    companion object {
        /** nanoTime-backed monotonic source (JVM monotonic clock). */
        val NanoTime: MonotonicTimeSource = NanoTimeSource()
    }
}

/**
 * Monotonic source whose raw reading is supplied by [nanoTime]; production
 * composes [System::nanoTime] at the boundary. The injected supplier makes
 * the elapsed ARITHMETIC deterministically testable (8.3a rule: external
 * entropy may exist at the boundary; every assertion about domain behavior
 * consumes controlled values).
 */
internal class NanoTimeSource(
    private val nanoTime: () -> Long = System::nanoTime,
) : MonotonicTimeSource {
    override fun markNow(): MonotonicMark = object : MonotonicMark {
        private val startNanos = nanoTime()
        // coerceAtLeast(0): hypervisor/TSC jitter can make a single
        // reading negative; an uptime/drain delta must never go below 0.
        override fun elapsedMillis(): Long = ((nanoTime() - startNanos) / 1_000_000).coerceAtLeast(0L)
    }
}

/** A captured monotonic instant; elapsed is measured against the SAME source. */
internal interface MonotonicMark {
    fun elapsedMillis(): Long
}
