package dev.tramai.security

import dev.tramai.core.security.DlpContentType
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class RuleBasedDlpInterceptorTest {

    private fun context(
        contentType: DlpContentType = DlpContentType.MODEL_OUTPUT,
        corrId: String = "test-corr",
    ) = DlpContext(
        contentType = contentType,
        operationInterface = "TestService",
        operationMethod = "process",
        correlationId = corrId,
    )

    private data class DslConfig(
        var rules: MutableList<DlpRule> = mutableListOf(),
        var maxTextLength: Int = 100_000,
    )

    private fun interceptor(
        block: DslConfig.() -> Unit,
    ): DlpInterceptor {
        val cfg = DslConfig().apply(block)
        return RuleBasedDlpInterceptor(
            RuleBasedDlpConfiguration(rules = cfg.rules.toList(), maxTextLength = cfg.maxTextLength),
        )
    }

    private fun DslConfig.rule(
        id: String,
        pattern: String,
        replacement: String = "[REDACTED]",
        enabledFor: Set<DlpContentType> = setOf(DlpContentType.MODEL_OUTPUT),
    ) {
        rules.add(DlpRule(id = id, pattern = pattern, replacement = replacement, enabledFor = enabledFor))
    }

    @Test
    fun `email regex redacts matching text`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "email", pattern = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        }
        val result = dlp.inspect(context(), "Contact me at user@example.com for info")
        assertThat(result.sanitizedText).isEqualTo("Contact me at [REDACTED] for info")
        assertThat(result.hasRedactions).isTrue()
        assertThat(result.redactions).hasSize(1)
        assertThat(result.redactions[0].ruleId).isEqualTo("email")
        assertThat(result.redactions[0].replacementCount).isEqualTo(1)
    }

    @Test
    fun `api key regex uses custom replacement`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "api-key", pattern = "sk-[A-Za-z0-9]{10,}", replacement = "***API-KEY***")
        }
        val result = dlp.inspect(context(), "Key: sk-abc123def456ghi and more")
        assertThat(result.sanitizedText).isEqualTo("Key: ***API-KEY*** and more")
        assertThat(result.hasRedactions).isTrue()
        assertThat(result.redactions[0].replacementCount).isEqualTo(1)
    }

    @Test
    fun `multiple rules apply deterministically in order`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "email", pattern = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
            rule(id = "ssn", pattern = "\\d{3}-\\d{2}-\\d{4}")
        }
        val text = "User: alice@example.com, SSN: 123-45-6789"
        val result = dlp.inspect(context(), text)
        assertThat(result.sanitizedText).isEqualTo("User: [REDACTED], SSN: [REDACTED]")
        assertThat(result.hasRedactions).isTrue()
        assertThat(result.redactions).hasSize(2)
        assertThat(result.redactions[0].ruleId).isEqualTo("email")
        assertThat(result.redactions[1].ruleId).isEqualTo("ssn")
    }

    @Test
    fun `duplicate rule ID is rejected`() {
        assertThatThrownBy {
            RuleBasedDlpInterceptor(
                RuleBasedDlpConfiguration(
                    rules = listOf(
                        DlpRule(id = "dup", pattern = "aaa"),
                        DlpRule(id = "dup", pattern = "bbb"),
                    ),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Duplicate DLP rule ID")
            .hasMessageContaining("dup")
    }

    @Test
    fun `blank rule ID is rejected`() {
        assertThatThrownBy {
            RuleBasedDlpInterceptor(
                RuleBasedDlpConfiguration(
                    rules = listOf(DlpRule(id = "  ", pattern = "aaa")),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("DLP rule ID must not be blank")
    }

    @Test
    fun `blank pattern is rejected`() {
        assertThatThrownBy {
            RuleBasedDlpInterceptor(
                RuleBasedDlpConfiguration(
                    rules = listOf(DlpRule(id = "blank-pat", pattern = "  ")),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("DLP rule pattern must not be blank")
            .hasMessageContaining("blank-pat")
    }

    @Test
    fun `oversized input is rejected with fixed message`() {
        val dlp = RuleBasedDlpInterceptor(RuleBasedDlpConfiguration(maxTextLength = 10))
        assertThatThrownBy {
            dlp.inspect(context(), "a".repeat(11))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Input text exceeds maximum allowed length")
    }

    @Test
    fun `TOOL_RESULT only rule does not affect MODEL_OUTPUT`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "tool-secret", pattern = "SECRET", enabledFor = setOf(DlpContentType.TOOL_RESULT))
        }
        val result = dlp.inspect(context(contentType = DlpContentType.MODEL_OUTPUT), "Contains SECRET data")
        assertThat(result.sanitizedText).isEqualTo("Contains SECRET data")
        assertThat(result.hasRedactions).isFalse()
        assertThat(result.redactions).isEmpty()
    }

    @Test
    fun `MODEL_OUTPUT only rule does not affect TOOL_RESULT`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "model-secret", pattern = "MODEL_SECRET", enabledFor = setOf(DlpContentType.MODEL_OUTPUT))
        }
        val result = dlp.inspect(context(contentType = DlpContentType.TOOL_RESULT), "Contains MODEL_SECRET data")
        assertThat(result.sanitizedText).isEqualTo("Contains MODEL_SECRET data")
        assertThat(result.hasRedactions).isFalse()
    }

    @Test
    fun `rule applies to both content types when enabled for both`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "universal", pattern = "TOP_SECRET", enabledFor = setOf(DlpContentType.MODEL_OUTPUT, DlpContentType.TOOL_RESULT))
        }
        for (ct in DlpContentType.entries) {
            val result = dlp.inspect(context(contentType = ct), "This is TOP_SECRET")
            assertThat(result.sanitizedText).isEqualTo("This is [REDACTED]")
            assertThat(result.hasRedactions).isTrue()
        }
    }

    @Test
    fun `empty rules list passes text through unchanged`() {
        val dlp = interceptor { maxTextLength = 10_000 }
        val result = dlp.inspect(context(), "Anything goes through")
        assertThat(result.sanitizedText).isEqualTo("Anything goes through")
        assertThat(result.hasRedactions).isFalse()
        assertThat(result.redactions).isEmpty()
    }

    @Test
    fun `input at max text length boundary passes through`() {
        val dlp = interceptor { maxTextLength = 5 }
        val result = dlp.inspect(context(), "12345")
        assertThat(result.sanitizedText).isEqualTo("12345")
        assertThat(result.hasRedactions).isFalse()
    }

    @Test
    fun `DlpResult reports correct properties when modified`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "phone", pattern = "\\d{3}-\\d{3}-\\d{4}")
        }
        val result = dlp.inspect(context(), "Call 555-123-4567 now")
        assertThat(result.sanitizedText).isEqualTo("Call [REDACTED] now")
        assertThat(result.hasRedactions).isTrue()
        assertThat(result.redactions).hasSize(1)
        assertThat(result.redactions[0].ruleId).isEqualTo("phone")
        assertThat(result.redactions[0].replacementCount).isEqualTo(1)
    }

    @Test
    fun `multiple occurrences of same pattern are all redacted`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "email", pattern = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        }
        val result = dlp.inspect(context(), "a@b.com and c@d.com and e@f.com")
        assertThat(result.sanitizedText).isEqualTo("[REDACTED] and [REDACTED] and [REDACTED]")
        assertThat(result.redactions[0].replacementCount).isEqualTo(3)
    }

    @Test
    fun `maxTextLength of zero is rejected`() {
        assertThatThrownBy {
            RuleBasedDlpInterceptor(RuleBasedDlpConfiguration(maxTextLength = 0))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maxTextLength must be greater than zero")
    }

    @Test
    fun `maxTextLength exceeding maximum is rejected`() {
        assertThatThrownBy {
            RuleBasedDlpInterceptor(RuleBasedDlpConfiguration(maxTextLength = 10_000_001))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maxTextLength exceeds maximum allowed value")
    }

    @Test
    fun `zero-width pattern terminates safely without infinite loop`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "lookahead", pattern = "(?=\\d)")
        }
        val result = dlp.inspect(context(), "abc123def456")
        assertThat(result.sanitizedText).isEqualTo("abc[REDACTED][REDACTED][REDACTED]def[REDACTED][REDACTED][REDACTED]")
        assertThat(result.hasRedactions).isTrue()
        assertThat(result.redactions).hasSize(1)
        assertThat(result.redactions[0].replacementCount).isEqualTo(6)
    }

    @Test
    fun `replacement with dollar-group reference stays literal`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "masks", pattern = "(\\d{4})-(\\d{4})-(\\d{4})-(\\d{4})", replacement = "MASK-$1")
        }
        val result = dlp.inspect(context(), "Card: 4111-1111-1111-1111")
        assertThat(result.sanitizedText).isEqualTo("Card: MASK-$1")
        assertThat(result.hasRedactions).isTrue()
    }

    @Test
    fun `multiple replacements across overlapping rules are counted correctly`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "email", pattern = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
            rule(id = "phone", pattern = "\\d{3}-\\d{3}-\\d{4}")
        }
        val result = dlp.inspect(
            context(),
            "Contact: alice@example.com or bob@test.org, Phone: 555-123-4567",
        )
        assertThat(result.sanitizedText).isEqualTo("Contact: [REDACTED] or [REDACTED], Phone: [REDACTED]")
        assertThat(result.redactions).hasSize(2)
        assertThat(result.redactions[0].ruleId).isEqualTo("email")
        assertThat(result.redactions[0].replacementCount).isEqualTo(2)
        assertThat(result.redactions[1].ruleId).isEqualTo("phone")
        assertThat(result.redactions[1].replacementCount).isEqualTo(1)
    }

    @Test
    fun `end of string anchor terminates safely`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "end-anchor", pattern = "$")
        }
        val result = dlp.inspect(context(), "abc")
        assertThat(result.sanitizedText).isEqualTo("abc[REDACTED]")
        assertThat(result.hasRedactions).isTrue()
        assertThat(result.redactions[0].replacementCount).isEqualTo(1)
    }

    @Test
    fun `zero-width lookahead replaces each digit position`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "digit-lookahead", pattern = "(?=\\d)")
        }
        val result = dlp.inspect(context(), "abc123")
        assertThat(result.sanitizedText).isEqualTo("abc[REDACTED][REDACTED][REDACTED]")
        assertThat(result.hasRedactions).isTrue()
        assertThat(result.redactions[0].replacementCount).isEqualTo(3)
    }

    @Test
    fun `literal replacement with backslash value stays literal`() {
        val dlp = interceptor {
            maxTextLength = 10_000
            rule(id = "literal-replace", pattern = "secret", replacement = "MASK-\\value")
        }
        val result = dlp.inspect(context(), "this is secret data")
        assertThat(result.sanitizedText).isEqualTo("this is MASK-\\value data")
        assertThat(result.hasRedactions).isTrue()
    }
}
