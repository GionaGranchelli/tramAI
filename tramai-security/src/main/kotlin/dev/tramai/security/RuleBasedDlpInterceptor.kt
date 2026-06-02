package dev.tramai.security

import dev.tramai.core.security.DlpContentType
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedaction
import dev.tramai.core.security.DlpResult
import java.util.regex.Pattern

/**
 * Configuration for [RuleBasedDlpInterceptor].
 *
 * @property rules Ordered list of DLP rules applied deterministically in declaration order.
 * @property maxTextLength Maximum allowed text length for inspection. Inputs exceeding
 *   this limit are rejected to guard against resource exhaustion from regex backtracking.
 */
data class RuleBasedDlpConfiguration(
    val rules: List<DlpRule> = emptyList(),
    val maxTextLength: Int = 100_000,
)

/**
 * A single DLP rule backed by a regex pattern.
 *
 * @property id Unique identifier for this rule.
 * @property pattern Regex pattern used to match sensitive content.
 * @property replacement Replacement string for matched content.
 * @property enabledFor The set of content types this rule applies to.
 */
data class DlpRule(
    val id: String,
    val pattern: String,
    val replacement: String = "[REDACTED]",
    val enabledFor: Set<DlpContentType> = setOf(DlpContentType.MODEL_OUTPUT),
)

/**
 * Rule-based DLP interceptor that applies a configurable set of regex rules to
 * sanitize model outputs and (optionally) tool results.
 *
 * Rules are applied deterministically in declaration order. Each rule filters by
 * [DlpContentType] based on its [DlpRule.enabledFor] set.
 *
 * ## Trust Model
 *
 * Regex patterns are trusted administrative configuration, not end-user input.
 * Administrators are responsible for ensuring patterns are well-formed and do not
 * introduce ReDoS vulnerabilities. Input text is always the untrusted party.
 *
 * ## Security Properties
 *
 * - No raw matched values or input text are included in [DlpRedaction] or exceptions.
 * - The [sanitizedText] in [DlpResult] is the only output carrying transformed content.
 * - Exceptions use fixed messages to avoid leaking input content.
 */
class RuleBasedDlpInterceptor(
    configuration: RuleBasedDlpConfiguration,
) : DlpInterceptor {

    private val compiledRules: List<CompiledDlpRule>
    private val maxTextLength: Int

    init {
        require(configuration.maxTextLength > 0) {
            "maxTextLength must be greater than zero"
        }
        require(configuration.maxTextLength <= 10_000_000) {
            "maxTextLength exceeds maximum allowed value"
        }

        val ids = mutableSetOf<String>()
        compiledRules = configuration.rules.map { rule ->
            require(rule.id.isNotBlank()) {
                "DLP rule ID must not be blank"
            }
            require(ids.add(rule.id)) {
                "Duplicate DLP rule ID: '${rule.id}'"
            }
            require(rule.pattern.isNotBlank()) {
                "DLP rule pattern must not be blank for rule '${rule.id}'"
            }
            require(rule.enabledFor.isNotEmpty()) {
                "DLP rule '${rule.id}' must have at least one enabled content type"
            }

            CompiledDlpRule(
                id = rule.id,
                pattern = Pattern.compile(rule.pattern),
                replacement = rule.replacement,
                enabledFor = rule.enabledFor.toSet(),
            )
        }
        this.maxTextLength = configuration.maxTextLength
    }

    override fun inspect(context: DlpContext, text: String): DlpResult {
        if (text.length > maxTextLength) {
            throw IllegalArgumentException("Input text exceeds maximum allowed length")
        }

        val redactions = mutableListOf<DlpRedaction>()
        var result = text

        for (rule in compiledRules) {
            if (!rule.enabledFor.contains(context.contentType)) continue

            val matcher = rule.pattern.matcher(result)
            val sb = StringBuilder()
            var count = 0
            var searchStart = 0

            while (matcher.find(searchStart)) {
                // Append text from last position to start of match
                sb.append(result, searchStart, matcher.start())
                sb.append(rule.replacement)
                count++

                val matchEnd = matcher.end()
                searchStart = if (matcher.start() == matchEnd) {
                    // Zero-width match — advance by one character to avoid infinite loop
                    matchEnd + 1
                } else {
                    matchEnd
                }
            }
            // Append remaining text after last match
            sb.append(result, searchStart, result.length)

            if (count > 0) {
                result = sb.toString()
                redactions.add(DlpRedaction(ruleId = rule.id, replacementCount = count))
            }
        }

        return DlpResult(sanitizedText = result, redactions = redactions)
    }

    private data class CompiledDlpRule(
        val id: String,
        val pattern: Pattern,
        val replacement: String,
        val enabledFor: Set<DlpContentType>,
    )
}
