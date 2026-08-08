package dev.tramai.core.structured

import org.assertj.core.api.Assertions.assertThat
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
    fun `split multibyte character decodes with replacement at boundary`() {
        val input = "a".repeat(STRUCTURED_OUTPUT_DETAIL_LIMIT_BYTES - 1) + "€"

        val preview = boundedStructuredOutputDetailPreview(input)

        assertThat(preview.truncated).isTrue()
        assertThat(preview.text).endsWith("\uFFFD")
    }

    @Test
    fun `ascii preview is identical at byte limit`() {
        val input = "x".repeat(8192)

        val preview = boundedStructuredOutputDetailPreview(input)

        assertThat(preview.text).isEqualTo(input)
        assertThat(preview.truncated).isFalse()
    }
}
