package dev.tramai.core.approval

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class SensitiveToolArgumentsTest {

    @Test
    fun `toString returns redacted`() {
        val arguments = SensitiveToolArguments.of("""{"sensitiveField":"fixture-value"}""")

        assertThat(arguments.toString()).isEqualTo("[REDACTED]")
    }

    @Test
    fun `reveal returns exact original string`() {
        val raw = """{"a":1,"b":"x"}"""

        assertThat(SensitiveToolArguments.of(raw).reveal()).isEqualTo(raw)
    }

    @Test
    fun `whitespace is preserved exactly`() {
        val raw = " {\n  \"a\": 1 \t}\n"

        assertThat(SensitiveToolArguments.of(raw).reveal()).isEqualTo(raw)
    }

    @Test
    fun `empty string permitted`() {
        assertThat(SensitiveToolArguments.of("").reveal()).isEmpty()
    }

    @Test
    fun `oversized payload rejected`() {
        val oversized = "\u4e2d".repeat(500_000)

        assertThatIllegalArgumentException()
            .isThrownBy { SensitiveToolArguments.of(oversized) }
            .withMessage("Tool arguments exceed maximum UTF-8 byte length")
    }

    @Test
    fun `multibyte payload at byte boundary accepted`() {
        val raw = "\u4e2d".repeat(333_333)

        assertThatCode { SensitiveToolArguments.of(raw) }.doesNotThrowAnyException()
    }

    @Test
    fun `raw json absent from toString`() {
        val raw = """{"sensitiveField":"fixture-redaction-marker"}"""

        assertThat(SensitiveToolArguments.of(raw).toString()).doesNotContain(raw)
    }
}
