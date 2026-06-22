package dev.tramai.security

import dev.tramai.core.security.DlpContentType
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedaction
import dev.tramai.core.security.DlpResult
import dev.tramai.core.security.DlpRuleIdNormalizer
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

data class RuleBasedDlpConfiguration(
    val rules: List<DlpRule> = emptyList(),
    val maxTextLength: Int = 100_000,
)

data class DlpRule(
    val id: String,
    val pattern: String,
    val replacement: String = "[REDACTED]",
    val enabledFor: Set<DlpContentType> = setOf(DlpContentType.MODEL_OUTPUT),
    val toolNames: Set<String> = emptySet(),
)

class RuleBasedDlpInterceptor(
    configuration: RuleBasedDlpConfiguration,
) : DlpInterceptor {

    private val compiledRules: List<CompiledDlpRule>
    private val maxTextLength: Int

    init {
        require(configuration.maxTextLength > 0) { "maxTextLength must be greater than zero" }
        require(configuration.maxTextLength <= 10_000_000) { "maxTextLength exceeds maximum allowed value" }
        val ids = mutableSetOf<String>()
        compiledRules = configuration.rules.map { rule ->
            val normalizedId = DlpRuleIdNormalizer.normalize(rule.id)
            require(ids.add(normalizedId)) { "Duplicate DLP rule ID" }
            require(rule.pattern.isNotBlank()) { "DLP rule pattern must not be blank" }
            require(rule.enabledFor.isNotEmpty()) { "DLP rule must have at least one enabled content type" }
            require(rule.toolNames.none { it.isBlank() }) { "DLP rule must not contain blank tool names" }
            require(rule.toolNames.all { it == it.trim() }) { "DLP rule must not contain tool names with surrounding whitespace" }
            CompiledDlpRule(
                id = normalizedId,
                pattern = try {
                    Pattern.compile(rule.pattern)
                } catch (_: PatternSyntaxException) {
                    throw IllegalArgumentException("DLP rule pattern is invalid")
                },
                replacement = rule.replacement,
                enabledFor = rule.enabledFor.toSet(),
                toolNames = rule.toolNames.toSet(),
            )
        }
        this.maxTextLength = configuration.maxTextLength
    }

    override fun inspect(context: DlpContext, text: String): DlpResult {
        require(text.length <= maxTextLength) {
            "Input text exceeds maximum allowed length"
        }

        val redactions = mutableListOf<DlpRedaction>()
        var result = text

        for (rule in compiledRules) {
            if (!rule.enabledFor.contains(context.contentType)) continue
            if (rule.toolNames.isNotEmpty() && context.toolName !in rule.toolNames) continue

            val matcher = rule.pattern.matcher(result)
            val sb = StringBuilder()
            var count = 0

            while (matcher.find()) {
                require(matcher.start() < matcher.end()) {
                    "DLP rule produced an unsafe zero-width match"
                }
                val matchedValue = matcher.group()
                require(!rule.replacement.lowercase().contains(matchedValue.lowercase())) {
                    "DLP replacement preserves matched content"
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(rule.replacement))
                count++
            }
            matcher.appendTail(sb)

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
        val toolNames: Set<String>,
    )
}
