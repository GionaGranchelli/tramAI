package dev.tramai.security

import dev.tramai.core.security.DlpContentType
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedaction
import dev.tramai.core.security.DlpResult
import java.util.regex.Matcher
import java.util.regex.Pattern

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
            require(rule.id.isNotBlank()) { "DLP rule ID must not be blank" }
            require(ids.add(rule.id)) { "Duplicate DLP rule ID: '${rule.id}'" }
            require(rule.pattern.isNotBlank()) { "DLP rule pattern must not be blank for rule '${rule.id}'" }
            require(rule.enabledFor.isNotEmpty()) { "DLP rule '${rule.id}' must have at least one enabled content type" }
            require(rule.toolNames.none { it.isBlank() }) { "DLP rule '${rule.id}' must not contain blank tool names" }
            CompiledDlpRule(
                id = rule.id,
                pattern = Pattern.compile(rule.pattern),
                replacement = rule.replacement,
                enabledFor = rule.enabledFor.toSet(),
                toolNames = rule.toolNames.toSet(),
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
            if (rule.toolNames.isNotEmpty() && context.toolName !in rule.toolNames) continue

            val matcher = rule.pattern.matcher(result)
            val sb = StringBuilder()
            var count = 0

            while (matcher.find()) {
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
