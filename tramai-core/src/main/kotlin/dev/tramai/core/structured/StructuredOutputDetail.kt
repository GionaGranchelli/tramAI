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
 * Returns a UTF-8-byte-bounded preview. If a multibyte character is split at
 * the byte boundary, decoding the retained bytes emits U+FFFD.
 */
fun boundedStructuredOutputDetailPreview(text: String): BoundedStructuredOutputDetail =
    boundedStructuredOutputDetailPreview(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

/**
 * Reads at most the detail limit plus one sentinel byte. If a multibyte
 * character is split at the byte boundary, decoding the retained bytes emits
 * U+FFFD.
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
    val retainedBytes = minOf(read, STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES)
    BoundedStructuredOutputDetail(
        text = String(bytes, 0, retainedBytes, Charsets.UTF_8),
        truncated = read > STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES,
    )
}
