package dev.tramai.spring

import dev.tramai.core.policy.DataClassification
import dev.tramai.security.classification.ClassificationRule
import dev.tramai.security.classification.DocumentClassifier
import dev.tramai.security.classification.RuleBasedClassifierConfiguration
import dev.tramai.security.classification.RuleBasedDocumentClassifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Optional auto-configuration for rule-based document classification.
 */
@AutoConfiguration(after = [TramaiAutoConfiguration::class])
@ConditionalOnClass(
    name = [
        "dev.tramai.security.classification.DocumentClassifier",
        "dev.tramai.security.classification.RuleBasedDocumentClassifier",
    ],
)
@ConditionalOnProperty(
    prefix = "tramai.security.classification",
    name = ["enabled"],
    havingValue = "true",
)
class SecurityClassificationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DocumentClassifier::class)
    fun documentClassifier(properties: TramaiProperties): DocumentClassifier {
        val classification = properties.security.classification
        return RuleBasedDocumentClassifier(
            RuleBasedClassifierConfiguration(
                defaultClassification = parseDataClassification(classification.defaultClassification),
                maxTextLength = classification.maxTextLength,
                rules = classification.rules.map { it.toDomain() },
            ),
        )
    }

    private fun parseDataClassification(name: String): DataClassification =
        enumValueOf(name.uppercase())

    private fun TramaiProperties.ClassificationRuleProperties.toDomain(): ClassificationRule =
        ClassificationRule(
            id = id,
            classification = parseDataClassification(classification),
            priority = priority,
            pattern = pattern,
            metadataEquals = metadataEquals,
        )
}
