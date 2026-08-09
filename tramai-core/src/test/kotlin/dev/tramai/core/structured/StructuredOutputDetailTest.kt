package dev.tramai.core.structured

import org.assertj.core.api.Assertions.assertThat
import java.io.ByteArrayInputStream
import kotlin.test.Test

class StructuredOutputDetailTest {
    @Test
    fun `exactly 8192 bytes is retained without truncation`() {
        val input = "a".repeat(STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES)

        val preview = boundedStructuredOutputDetailPreview(input)

        assertThat(preview.text).isEqualTo(input)
        assertThat(preview.truncated).isFalse()
    }

    @Test
    fun `8193 bytes is truncated to 8192 bytes`() {
        val preview = boundedStructuredOutputDetailPreview("a".repeat(STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES + 1))

        assertThat(preview.truncated).isTrue()
        assertThat(preview.text.toByteArray(Charsets.UTF_8)).hasSize(STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES)
    }

    @Test
    fun `split multibyte character is dropped and preview stays within byte limit`() {
        val input = "a".repeat(STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES - 1) + "€"

        val preview = boundedStructuredOutputDetailPreview(input)

        assertThat(preview.truncated).isTrue()
        assertThat(preview.text).isEqualTo("a".repeat(STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES - 1))
        assertThat(preview.text.toByteArray(Charsets.UTF_8).size)
            .isLessThanOrEqualTo(STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES)
    }

    @Test
    fun `internally malformed bytes never expand past the byte limit`() {
        // 0x80 bytes decode to U+FFFD (3 bytes each) — without the final trim the
        // re-encoded preview would exceed the limit.
        val malformed = ByteArray(STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES) { if (it < 4000) 0x80.toByte() else 0x61 }

        val preview = boundedStructuredOutputDetailPreview(ByteArrayInputStream(malformed))

        assertThat(preview.truncated).isFalse()
        assertThat(preview.text.toByteArray(Charsets.UTF_8).size)
            .isLessThanOrEqualTo(STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES)
    }

    @Test
    fun `ascii preview is identical at byte limit`() {
        val input = "x".repeat(8192)

        val preview = boundedStructuredOutputDetailPreview(input)

        assertThat(preview.text).isEqualTo(input)
        assertThat(preview.truncated).isFalse()
    }
}
