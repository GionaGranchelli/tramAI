package dev.tramai.testing.persistence.approval

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Deterministic [Clock] for the ApprovalStore TCK: fixed at construction,
 * advanced explicitly by the test. Never `Instant.now()` or real sleeps.
 */
class MutableClock(initial: Instant) : Clock() {

    private var now: Instant = initial

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}
