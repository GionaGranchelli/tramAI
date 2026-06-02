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
        val sanitized = sanitize(context, text)
        if (sanitized !== text && sanitized != text) {
            // Compute redactions by re-running rules to count replacements
            val redactions = mutableListOf<DlpRedaction>()
            var working = text
            for (rule in compiledRules) {
                if (rule.enabledFor.contains(context.contentType)) {
                    val count = countMatches(rule.pattern, working)
                    if (count > 0) {
                        val matcher = rule.pattern.matcher(working)
                        working = matcher.replaceAll(rule.replacement)
                        redactions.add(DlpRedaction(ruleId = rule.id, replacementCount = count))
                    }
                }
            }
            return DlpResult(sanitizedText = working, redactions = redactions)
        }
        return DlpResult(text)
    }

    private fun sanitize(context: DlpContext, text: String): String {
        if (text.length > maxTextLength) {
            throw IllegalArgumentException("Input text exceeds maximum allowed length")
        }

        var result = text
        for (rule in compiledRules) {
            if (rule.enabledFor.contains(context.contentType)) {
                val matcher = rule.pattern.matcher(result)
                result = matcher.replaceAll(rule.replacement)
            }
        }
        return result
    }

    private fun countMatches(pattern: Pattern, text: String): Int {
        var count = 0
        var matcher = pattern.matcher(text)
        var start = 0
        while (matcher.find(start)) {
            count++
            start = matcher.end()
            if (start >= text.length) break
        }
        return count
    }

    private data class CompiledDlpRule(
        val id: String,
        val pattern: Pattern,
        val replacement: String,
        val enabledFor: Set<DlpContentType>,
    )
}
