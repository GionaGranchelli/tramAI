package dev.tramai.core.structured

import java.io.ByteArrayInputStream
import java.io.InputStream

const val STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES: Int = 8192

/** A UTF-8 detail preview bounded by [STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES] bytes. */
data class BoundedStructuredOutputDetail(
    val text: String,
    val truncated: Boolean,
)
/**
 * Returns a UTF-8 byte-bounded preview. If a multibyte character is split at
 * the byte boundary, the split code point is dropped (never decoded to U+FFFD),
 * so the emitted preview always re-encodes to at most
 * [STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES] bytes.
 */
fun boundedStructuredOutputDetailPreview(text: String): BoundedStructuredOutputDetail =
    boundedStructuredOutputDetailPreview(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

/**
 * Reads at most the detail limit plus one sentinel byte and returns the longest
 * complete UTF-8 prefix that fits within [STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES]
 * encoded bytes. A multibyte character split at the boundary is dropped, and
 * internally malformed bytes are trimmed after decoding, so
 * `preview.text.toByteArray(Charsets.UTF_8).size <= STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES`
 * always holds.
 */
fun boundedStructuredOutputDetailPreview(input: InputStream): BoundedStructuredOutputDetail = input.use { stream ->
    val bytes = ByteArray(STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES + 1)
    var read = 0
    while (read < bytes.size) {
        val count = stream.read(bytes, read, bytes.size - read)
        if (count < 0) break
        if (count == 0) {
            val nextByte = stream.read()
            if (nextByte < 0) break
            bytes[read++] = nextByte.toByte()
        } else {
            read += count
        }
    }
    val truncated = read > STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES
    val retainedBytes = completeUtf8PrefixLength(bytes, minOf(read, STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES))
    var text = String(bytes, 0, retainedBytes, Charsets.UTF_8)
    // Internally malformed bytes decode to U+FFFD (3 bytes each), which can expand
    // past the limit on re-encoding. Trim to the longest prefix that stays within it.
    while (text.toByteArray(Charsets.UTF_8).size > STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES) {
        text = text.dropLastCodePoint()
    }
    BoundedStructuredOutputDetail(text = text, truncated = truncated)
}

/**
 * Longest prefix of [bytes][0, limit) that ends on a complete UTF-8 code point.
 * A code point split at the boundary is dropped entirely instead of being
 * decoded into U+FFFD, keeping the re-encoded preview within the byte limit.
 */
private fun completeUtf8PrefixLength(bytes: ByteArray, limit: Int): Int {
    var end = limit
    while (end > 0) {
        val last = bytes[end - 1].toInt() and 0xFF
        if (last < 0x80) return end // ASCII boundary — complete
        if (last in 0x80..0xBF) {
            // Continuation byte: walk back to the lead byte of this code point.
            var start = end - 1
            while (start > 0 && (bytes[start - 1].toInt() and 0xC0) == 0x80) start--
            val lead = bytes[start].toInt() and 0xFF
            val expectedLength = utf8SequenceLength(lead)
            if (expectedLength == 0 || start + expectedLength > end) return start // incomplete or invalid — drop the code point
            return end // complete code point ends exactly at the boundary
        }
        // Bare lead byte with no continuations yet: incomplete — drop it.
        return end - 1
    }
    return 0
}

private fun utf8SequenceLength(lead: Int): Int = when {
    lead in 0xC2..0xDF -> 2
    lead in 0xE0..0xEF -> 3
    lead in 0xF0..0xF4 -> 4
    else -> 0
}

private fun String.dropLastCodePoint(): String {
    var index = length
    while (index > 0) {
        val c = this[index - 1]
        if (Character.isLowSurrogate(c)) {
            index--
            continue
        }
        return if (Character.isHighSurrogate(c) && index >= 2) substring(0, index - 2) else substring(0, index - 1)
    }
    return ""
}
