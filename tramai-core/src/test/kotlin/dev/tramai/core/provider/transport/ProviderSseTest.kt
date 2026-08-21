package dev.tramai.core.provider.transport

import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProviderSseTest {

    private fun reader(vararg lines: String): BufferedReader =
        BufferedReader(StringReader(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"))

    private open class CloseTrackingReader(reader: Reader) : BufferedReader(reader) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    @Test
    fun `data frame payload is extracted`() {
        assertThat(readSseDataPayload(reader("data: hello world"))).isEqualTo("hello world")
    }

    @Test
    fun `unrelated sse fields do not become payload`() {
        assertThat(
            readSseDataPayload(reader(": comment", "id: 1", "event: ping", "", "data: real")),
        ).isEqualTo("real")
    }

    @Test
    fun `malformed payload is returned verbatim for provider interpretation`() {
        assertThat(readSseDataPayload(reader("data: {not json"))).isEqualTo("{not json")
        assertThat(readSseDataPayload(reader("data: "))).isEqualTo("")
    }

    @Test
    fun `eof terminates cleanly with null`() {
        assertThat(readSseDataPayload(reader())).isNull()
        assertThat(readSseDataPayload(reader("event: ping", ": note", "id: 9"))).isNull()
    }

    @Test
    fun `subsequent data lines are framed after the first`() {
        val r = reader("event: message", "data: first", "data: second")
        assertThat(readSseDataPayload(r)).isEqualTo("first")
        assertThat(readSseDataPayload(r)).isEqualTo("second")
        assertThat(readSseDataPayload(r)).isNull()
    }

    @Test
    fun `sse event name is extracted only from event lines`() {
        assertThat(sseEventName("event: content_block_delta")).isEqualTo("content_block_delta")
        assertThat(sseEventName("data: x")).isNull()
        assertThat(sseEventName(": comment")).isNull()
    }

    @Test
    fun `caller use block closes the reader on completion`() {
        val r = CloseTrackingReader(reader("data: x"))
        r.use { readSseDataPayload(it) }
        assertThat(r.closed).isTrue()
    }

    @Test
    fun `exception propagates and caller use block still closes`() {
        val r = CloseTrackingReader(object : Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int = throw IOException("boom")
            override fun close() = Unit
        })
        assertThatThrownBy { r.use { readSseDataPayload(it) } }
            .isInstanceOf(IOException::class.java)
        assertThat(r.closed).isTrue()
    }
}
