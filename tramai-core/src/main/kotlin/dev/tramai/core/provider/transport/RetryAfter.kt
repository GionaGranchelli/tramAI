package dev.tramai.core.provider.transport

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Parses an HTTP `Retry-After` header value into a retry delay in milliseconds.
 *
 * Accepts:
 * - a non-negative integer number of seconds (`Retry-After: 120`);
 * - an RFC-1123 HTTP-date (`Retry-After: Wed, 21 Oct 2026 07:28:00 GMT`),
 *   evaluated against the supplied [clock] so tests are deterministic.
 *
 * Returns:
 * - `0` for a date in the past (retry immediately);
 * - `null` for a malformed value, a blank value, or a negative number of
 *   seconds (no retry-after information).
 */
fun parseRetryAfterMillis(
    value: String?,
    clock: Clock = Clock.systemUTC(),
): Long? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    trimmed.toLongOrNull()?.let { seconds ->
        return if (seconds >= 0) seconds * 1_000 else null
    }

    val retryAt = runCatching {
        Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).parse(trimmed))
    }.getOrNull() ?: return null

    return (retryAt.toEpochMilli() - clock.millis()).coerceAtLeast(0L)
}
