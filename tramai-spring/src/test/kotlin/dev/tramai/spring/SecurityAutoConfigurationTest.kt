package dev.tramai.spring

import dev.tramai.security.classification.ClassificationInput
import dev.tramai.security.classification.DocumentClassifier
import dev.tramai.security.classification.RuleBasedDocumentClassifier
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.Test

class SecurityAutoConfigurationTest {

    @Test
    fun `Spring property binding creates RuleBasedDocumentClassifier bean matching programmatic config`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(TramaiAutoConfiguration::class.java),
            )
            .withPropertyValues(
                "tramai.security.classification.rules[0].id=national-id",
                "tramai.security.classification.rules[0].classification=RESTRICTED",
                "tramai.security.classification.rules[0].pattern=\\d{3}-\\d{2}-\\d{4}",
                "tramai.security.classification.default-classification=INTERNAL",
            )
            .run { context ->
                assertThat(context).hasSingleBean(DocumentClassifier::class.java)
                val classifier = context.getBean(DocumentClassifier::class.java)
                assertThat(classifier).isInstanceOf(RuleBasedDocumentClassifier::class.java)

                val decision = classifier.classify(
                    ClassificationInput(text = "ssn 123-45-6789")
                )

                assertThat(decision.classification).isEqualTo(dev.tramai.core.policy.DataClassification.RESTRICTED)
            }
    }
}
