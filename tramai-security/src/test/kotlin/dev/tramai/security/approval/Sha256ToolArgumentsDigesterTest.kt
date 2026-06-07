package dev.tramai.security.approval

import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class Sha256ToolArgumentsDigesterTest {

    private val digester = Sha256ToolArgumentsDigester()

    @Test
    fun `known SHA-256 vector`() {
        val digest = digester.digest(SensitiveToolArguments.of("hello"))

        assertThat(digest).isEqualTo(
            Sha256Digest.of("sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"),
        )
    }

    @Test
    fun `deterministic output`() {
        val arguments = SensitiveToolArguments.of("""{"x":1}""")

        assertThat(digester.digest(arguments)).isEqualTo(digester.digest(arguments))
    }

    @Test
    fun `whitespace changes digest`() {
        assertThat(digester.digest(SensitiveToolArguments.of("abc")))
            .isNotEqualTo(digester.digest(SensitiveToolArguments.of("abc ")))
    }

    @Test
    fun `key order changes digest`() {
        assertThat(digester.digest(SensitiveToolArguments.of("""{"a":1,"b":2}""")))
            .isNotEqualTo(digester.digest(SensitiveToolArguments.of("""{"b":2,"a":1}""")))
    }

    @Test
    fun `output typed as Sha256Digest`() {
        val digest = digester.digest(SensitiveToolArguments.of("{}"))

        assertThat(digest).isInstanceOf(Sha256Digest::class.java)
    }

    @Test
    fun `raw json absent from digest output and exceptions`() {
        val raw = """{"sensitiveField":"fixture-value"}"""
        val digest = digester.digest(SensitiveToolArguments.of(raw))

        assertThat(digest.value).doesNotContain(raw)
        assertThatCode { digester.digest(SensitiveToolArguments.of(raw)) }
            .doesNotThrowAnyException()
    }
}
