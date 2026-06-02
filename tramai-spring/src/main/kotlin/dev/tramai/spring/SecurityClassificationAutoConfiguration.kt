package dev.tramai.spring

import dev.tramai.core.policy.DataClassification
import dev.tramai.security.classification.DocumentClassifier
import dev.tramai.security.classification.RuleBasedClassifierConfiguration
import dev.tramai.security.classification.RuleBasedDocumentClassifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.core.env.ConfigurableEnvironment

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(DocumentClassifier::class, RuleBasedDocumentClassifier::class)
@Conditional(SecurityClassificationConfigurationPresentCondition::class)
class SecurityClassificationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DocumentClassifier::class)
    fun documentClassifier(properties: TramaiProperties): DocumentClassifier {
        val classification = properties.security.classification
        return RuleBasedDocumentClassifier(
            RuleBasedClassifierConfiguration(
                defaultClassification = classification.defaultClassification,
                maxTextLength = classification.maxTextLength,
                rules = classification.rules,
            ),
        )
    }
}

class SecurityClassificationConfigurationPresentCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val environment = context.environment
        val propertySources = (environment as? ConfigurableEnvironment)?.propertySources
        val ruleConfigured = propertySources?.any { propertySource ->
            when (val source = propertySource.source) {
                is Map<*, *> -> source.keys.any { key ->
                    key is String && key.startsWith("tramai.security.classification.rules[")
                }
                else -> false
            }
        } ?: false
        if (ruleConfigured) {
            return true
        }

        val maxTextLength = environment.getProperty("tramai.security.classification.max-text-length")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.toIntOrNull()
        if (maxTextLength != null && maxTextLength != 100_000) {
            return true
        }

        val defaultClassification = environment.getProperty("tramai.security.classification.default-classification")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.uppercase()
        return defaultClassification != null && defaultClassification != DataClassification.INTERNAL.name
    }
}
