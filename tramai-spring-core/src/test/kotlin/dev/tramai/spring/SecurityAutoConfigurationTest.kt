package dev.tramai.spring

import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.security.classification.ClassificationInput
import dev.tramai.security.classification.DocumentClassifier
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import kotlin.test.Test

class SecurityAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                TramaiSecretResolutionAutoConfiguration::class.java,
                TramaiAutoConfiguration::class.java,
                SecurityClassificationAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `no classification config yields no DocumentClassifier bean`() {
        contextRunner.run { context ->
            assertThat(context).doesNotHaveBean(DocumentClassifier::class.java)
        }
    }

    @Test
    fun `enabled false yields no DocumentClassifier bean`() {
        contextRunner
            .withPropertyValues("tramai.security.classification.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(DocumentClassifier::class.java)
            }
    }

    @Test
    fun `enabled true with defaults creates classifier bean with INTERNAL default`() {
        contextRunner
            .withPropertyValues("tramai.security.classification.enabled=true")
            .run { context ->
                assertThat(context).hasSingleBean(DocumentClassifier::class.java)

                val decision = context.getBean(DocumentClassifier::class.java)
                    .classify(ClassificationInput(text = "general memo"))

                assertThat(decision.classification).isEqualTo(DataClassification.INTERNAL)
                assertThat(decision.source).isEqualTo(ClassificationSource.RULE_BASED)
                assertThat(decision.matchedRuleIds).isEmpty()
                assertThat(decision.usedDefault).isTrue()
            }
    }

    @Test
    fun `user DocumentClassifier bean wins over auto configuration`() {
        contextRunner
            .withUserConfiguration(CustomDocumentClassifierConfiguration::class.java)
            .withPropertyValues("tramai.security.classification.enabled=true")
            .run { context ->
                assertThat(context).hasSingleBean(DocumentClassifier::class.java)

                val classifier = context.getBean(DocumentClassifier::class.java)
                val decision = classifier.classify(ClassificationInput(text = "ignored"))

                assertThat(classifier).isSameAs(context.getBean("customDocumentClassifier"))
                assertThat(decision.classification).isEqualTo(DataClassification.PUBLIC)
                assertThat(decision.matchedRuleIds).containsExactly("custom")
                assertThat(decision.usedDefault).isFalse()
            }
    }

    @Test
    fun `enabled true with rules yields correct classification`() {
        contextRunner
            .withPropertyValues(
                "tramai.security.classification.enabled=true",
                "tramai.security.classification.default-classification=INTERNAL",
                "tramai.security.classification.rules[0].id=national-id",
                "tramai.security.classification.rules[0].classification=RESTRICTED",
                "tramai.security.classification.rules[0].pattern=\\d{3}-\\d{2}-\\d{4}",
            )
            .run { context ->
                assertThat(context).hasSingleBean(DocumentClassifier::class.java)

                val decision = context.getBean(DocumentClassifier::class.java)
                    .classify(ClassificationInput(text = "ssn 123-45-6789"))

                assertThat(decision.classification).isEqualTo(DataClassification.RESTRICTED)
                assertThat(decision.matchedRuleIds).containsExactly("national-id")
                assertThat(decision.usedDefault).isFalse()
            }
    }

    @Test
    fun `context starts without tramai-security on classpath and produces no DocumentClassifier bean`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    TramaiSecretResolutionAutoConfiguration::class.java,
                    TramaiAutoConfiguration::class.java,
                    SecurityClassificationAutoConfiguration::class.java,
                ),
            )
            .withClassLoader(
                FilteredClassLoader("dev.tramai.security"),
            )
            .run { context ->
                assertThat(context).doesNotHaveBean("documentClassifier")
                assertThat(context).doesNotHaveBean(
                    dev.tramai.security.classification.DocumentClassifier::class.java,
                )
            }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class CustomDocumentClassifierConfiguration {
        @Bean
        fun customDocumentClassifier(): DocumentClassifier = object : DocumentClassifier {
            override fun classify(input: ClassificationInput) =
                dev.tramai.security.classification.ClassificationDecision(
                    classification = DataClassification.PUBLIC,
                    source = ClassificationSource.RULE_BASED,
                    matchedRuleIds = listOf("custom"),
                    usedDefault = false,
                )
        }
    }
}
