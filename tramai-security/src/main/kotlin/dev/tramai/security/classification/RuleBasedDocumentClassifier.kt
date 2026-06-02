package dev.tramai.security.classification

import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification

data class RuleBasedClassifierConfiguration(
    val defaultClassification: DataClassification = DataClassification.INTERNAL,
    val maxTextLength: Int = 100_000,
    val rules: List<ClassificationRule> = emptyList(),
)

class RuleBasedDocumentClassifier(
    private val configuration: RuleBasedClassifierConfiguration,
) : DocumentClassifier {

    init {
        require(configuration.maxTextLength > 0) {
            "maxTextLength must be greater than 0"
        }
    }

    private val compiledRules = compileRules(configuration)

    override fun classify(input: ClassificationInput): ClassificationDecision {
        val text = input.text
        if (text != null && text.length > configuration.maxTextLength) {
            throw IllegalArgumentException(
                "Input text length ${text.length} exceeds maximum ${configuration.maxTextLength}"
            )
        }

        val matches = compiledRules
            .asSequence()
            .filter { it.matches(input) }
            .map { MatchRecord(it.id, it.classification, it.priority) }
            .toList()

        if (matches.isEmpty()) {
            return ClassificationDecision(
                classification = configuration.defaultClassification,
                source = ClassificationSource.RULE_BASED,
                matchedRuleIds = emptyList(),
                usedDefault = true,
            )
        }

        val winning = highest(matches)

        return ClassificationDecision(
            classification = winning.classification,
            source = ClassificationSource.RULE_BASED,
            matchedRuleIds = matches.map { it.id },
            usedDefault = false,
        )
    }

    private fun compileRules(
        configuration: RuleBasedClassifierConfiguration,
    ): List<CompiledRule> {
        val seenIds = mutableSetOf<String>()
        return configuration.rules
            .map { rule ->
                require(rule.id.isNotBlank()) { "Classification rule id must not be blank" }
                require(seenIds.add(rule.id)) { "Duplicate classification rule id '${rule.id}'" }
                require(rule.pattern?.isBlank() != true) {
                    "Classification rule '${rule.id}' pattern must not be blank"
                }
                rule.metadataEquals.keys.forEach { key ->
                    require(key.isNotBlank()) {
                        "Classification rule '${rule.id}' metadata key must not be blank"
                    }
                }
                require(rule.priority >= 0) { "Classification rule '${rule.id}' priority must be >= 0" }
                require(rule.pattern != null || rule.metadataEquals.isNotEmpty()) {
                    "Classification rule '${rule.id}' must define a pattern or metadataEquals"
                }

                val compiledPattern = rule.pattern?.let { Regex(it) }
                CompiledRule(
                    id = rule.id,
                    classification = rule.classification,
                    priority = rule.priority,
                    pattern = rule.pattern,
                    metadataEquals = rule.metadataEquals,
                    compiledPattern = compiledPattern,
                )
            }
            .sortedWith(compareByDescending<CompiledRule> { it.priority }.thenBy { it.id })
    }

    private fun highest(records: List<MatchRecord>): MatchRecord =
        records.maxByOrNull { it.classification.rank }!!

    private data class CompiledRule(
        val id: String,
        val classification: DataClassification,
        val priority: Int,
        val pattern: String?,
        val metadataEquals: Map<String, String>,
        val compiledPattern: Regex?,
    ) {
        fun matches(input: ClassificationInput): Boolean {
            val textMatches = compiledPattern?.let { pattern ->
                input.text?.let(pattern::containsMatchIn) ?: false
            } ?: true
            val metadataMatches = metadataEquals.all { (key, value) ->
                input.metadata[key] == value
            }
            return textMatches && metadataMatches
        }
    }

    private data class MatchRecord(
        val id: String,
        val classification: DataClassification,
        val priority: Int,
    )

    private val DataClassification.rank: Int
        get() = when (this) {
            DataClassification.PUBLIC -> 0
            DataClassification.INTERNAL -> 1
            DataClassification.CONFIDENTIAL -> 2
            DataClassification.RESTRICTED -> 3
        }
}
