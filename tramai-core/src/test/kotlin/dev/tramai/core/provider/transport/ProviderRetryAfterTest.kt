package dev.tramai.core.provider.transport

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderRetryAfterTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneId.of("UTC"))

    @Test
    fun `numeric seconds become millis`() {
        assertThat(parseRetryAfterMillis("120")).isEqualTo(120_000)
        assertThat(parseRetryAfterMillis(" 30 ")).isEqualTo(30_000)
        assertThat(parseRetryAfterMillis("0")).isEqualTo(0)
    }

    @Test
    fun `rfc1123 future timestamp is deterministic against the supplied clock`() {
        assertThat(parseRetryAfterMillis("Fri, 21 Aug 2026 10:00:10 GMT", fixedClock)).isEqualTo(10_000)
    }

    @Test
    fun `rfc1123 timestamp at the clock instant yields zero`() {
        assertThat(parseRetryAfterMillis("Fri, 21 Aug 2026 10:00:00 GMT", fixedClock)).isEqualTo(0)
    }

    @Test
    fun `rfc1123 past timestamp yields zero`() {
        assertThat(parseRetryAfterMillis("Fri, 21 Aug 2026 09:59:50 GMT", fixedClock)).isEqualTo(0)
    }

    @Test
    fun `rfc1123 parsing is deterministic regardless of wall clock`() {
        // Same input parsed against a different fixed instant still yields a
        // stable, clock-derived result rather than depending on real time.
        val laterClock = Clock.fixed(Instant.parse("2026-08-21T10:00:05Z"), ZoneId.of("UTC"))
        assertThat(parseRetryAfterMillis("Fri, 21 Aug 2026 10:00:10 GMT", laterClock)).isEqualTo(5_000)
    }

    @Test
    fun `malformed value yields null`() {
        assertThat(parseRetryAfterMillis("not-a-date")).isNull()
        assertThat(parseRetryAfterMillis("120 seconds")).isNull()
        assertThat(parseRetryAfterMillis("")).isNull()
        assertThat(parseRetryAfterMillis("   ")).isNull()
        assertThat(parseRetryAfterMillis(null)).isNull()
    }

    @Test
    fun `negative numeric value yields null`() {
        assertThat(parseRetryAfterMillis("-5")).isNull()
    }

    @Test
    fun `huge numeric value that would overflow millis yields null`() {
        assertThat(parseRetryAfterMillis(Long.MAX_VALUE.toString())).isNull()
        assertThat(parseRetryAfterMillis("9223372036854776")).isNull()
    }
}
