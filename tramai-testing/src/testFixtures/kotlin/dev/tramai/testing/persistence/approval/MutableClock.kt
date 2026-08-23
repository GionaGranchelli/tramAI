package dev.tramai.testing.persistence.approval

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Deterministic [Clock] for the ApprovalStore TCK: fixed at construction,
 * advanced explicitly by the test. Never `Instant.now()` or real sleeps.
 *
 * Thread-safe: the instant is [Volatile] because the TCK concurrency cases
 * read the clock from parallel workers.
 */
class MutableClock(
    initial: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {

    @Volatile
    private var now: Instant = initial

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)

    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    fun set(newNow: Instant) {
        now = newNow
    }
}
