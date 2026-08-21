package dev.tramai.testing.provider

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Wraps a response-body stream so the TCK can prove that adapters close the
 * resource on success, rejected responses, parse failures, and early stream
 * collection.
 */
class TrackingInputStream private constructor(
    delegate: InputStream,
    private val onClosed: (() -> Unit)?,
) : FilterInputStream(delegate) {

    @Volatile
    private var closeCount = 0

    @Volatile
    var closed: Boolean = false
        private set

    override fun close() {
        closeCount++
        closed = true
        onClosed?.invoke()
        super.close()
    }

    fun closeCount(): Int = closeCount

    companion object {
        /** Wraps [bytes] in a tracking stream that records every close call. */
        fun of(bytes: ByteArray): TrackingInputStream =
            TrackingInputStream(ByteArrayInputStreamOf(bytes)) { }

        /** Wraps [text] as UTF-8 bytes. */
        fun of(text: String): TrackingInputStream = of(text.toByteArray(Charsets.UTF_8))

        /** Wraps an arbitrary delegate stream. */
        fun of(delegate: InputStream): TrackingInputStream =
            TrackingInputStream(delegate) { }
    }
}

private class ByteArrayInputStreamOf(bytes: ByteArray) : java.io.ByteArrayInputStream(bytes)
