package dev.tramai.core.provider.transport

/**
 * Byte-bounded body read result.
 *
 * [text] holds at most [limitBytes] UTF-8 bytes of the source body; [truncated]
 * reports whether the source contained more than the limit. A truncated result
 * is a diagnostic signal — callers decide whether to fail loud or surface the
 * preview.
 */
@ExperimentalProviderTransportApi
data class BoundedBody(
    val text: String,
    val truncated: Boolean,
)
