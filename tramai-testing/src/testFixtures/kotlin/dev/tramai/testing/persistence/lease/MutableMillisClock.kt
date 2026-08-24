package dev.tramai.testing.persistence.lease

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe deterministic millis clock for the lease TCKs: fixed at
 * construction, advanced/set explicitly by the test. Never
 * `System.currentTimeMillis()`.
 *
 * Fits the built-in stores' `clockMillis: () -> Long` slot. Backed by
 * [AtomicLong] because the concurrency cases read the clock from parallel
 * workers.
 */
class MutableMillisClock(
    initial: Long = 1_800_000_000_000L,
) : () -> Long {

    private val now = AtomicLong(initial)

    override fun invoke(): Long = now.get()

    fun set(millis: Long) {
        now.set(millis)
    }

    fun advance(millis: Long) {
        now.addAndGet(millis)
    }
}
