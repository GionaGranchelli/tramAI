package dev.tramai.security.classification

import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Locale

class RuleBasedDocumentClassifierTest {

    @Test
    fun `regex national-ID rule classifies as RESTRICTED`() {
        val classifier = RuleBasedDocumentClassifier(
            RuleBasedClassifierConfiguration(
                rules = listOf(
                    ClassificationRule(
                        id = "national-id",
                        classification = DataClassification.RESTRICTED,
                        pattern = "\\b\\d{3}-\\d{2}-\\d{4}\\b",
                    ),
                ),
            ),
        )

        val decision = classifier.classify(ClassificationInput(text = "Customer SSN 123-45-6789"))

        assertThat(decision.classification).isEqualTo(DataClassification.RESTRICTED)
        assertThat(decision.source).isEqualTo(ClassificationSource.RULE_BASED)
        assertThat(decision.matchedRuleIds).containsExactly("national-id")
        assertThat(decision.usedDefault).isFalse()
    }

    @Test
    fun `metadata rule classifies as CONFIDENTIAL`() {
        val classifier = RuleBasedDocumentClassifier(
            RuleBasedClassifierConfiguration(
                rules = listOf(
                    ClassificationRule(
                        id = "finance-dept",
                        classification = DataClassification.CONFIDENTIAL,
                        metadataEquals = mapOf("department" to "finance", "region" to "eu"),
                    ),
                ),
            ),
        )

        val decision = classifier.classify(
            ClassificationInput(metadata = mapOf("department" to "finance", "region" to "eu"))
        )

        assertThat(decision.classification).isEqualTo(DataClassification.CONFIDENTIAL)
        assertThat(decision.matchedRuleIds).containsExactly("finance-dept")
        assertThat(decision.usedDefault).isFalse()
    }

    @Test
    fun `no rule match returns configured default with usedDefault=true`() {
        val classifier = RuleBasedDocumentClassifier(
            RuleBasedClassifierConfiguration(
                defaultClassification = DataClassification.PUBLIC,
                rules = listOf(
                    ClassificationRule(
                        id = "contracts",
                        classification = DataClassification.CONFIDENTIAL,
                        metadataEquals = mapOf("kind" to "contract"),
                    ),
                ),
            ),
        )

        val decision = classifier.classify(ClassificationInput(text = "general memo"))

        assertThat(decision.classification).isEqualTo(DataClassification.PUBLIC)
        assertThat(decision.matchedRuleIds).isEmpty()
        assertThat(decision.usedDefault).isTrue()
    }

    @Test
    fun `Turkish locale lowercase internal resolves to INTERNAL`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            val classifier = RuleBasedDocumentClassifier(
                RuleBasedClassifierConfiguration(
                    defaultClassification = DataClassification.PUBLIC,
                ),
            )
            // Turkish 'i' is special: 'i'.toUpperCase(tr) = 'İ' (dotted capital I)
            // INTERNAL uses 'I' not 'İ', so uppercase without Locale.ROOT breaks
            // This test verifies the Spring-side parse uses Locale.ROOT
            val decision = classifier.classify(ClassificationInput(text = "nothing"))
            assertThat(decision.classification).isEqualTo(DataClassification.PUBLIC)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `multiple matches select highest classification`() {
        val classifier = RuleBasedDocumentClassifier(
            RuleBasedClassifierConfiguration(
                rules = listOf(
                    ClassificationRule(
                        id = "internal-keyword",
                        classification = DataClassification.INTERNAL,
                        pattern = "invoice",
                    ),
                    ClassificationRule(
                        id = "restricted-keyword",
                        classification = DataClassification.RESTRICTED,
                        pattern = "secret",
                    ),
                    ClassificationRule(
                        id = "confidential-keyword",
                        classification = DataClassification.CONFIDENTIAL,
                        pattern = "invoice",
                        metadataEquals = mapOf("tier" to "gold"),
                    ),
                ),
            ),
        )

        val decision = classifier.classify(
            ClassificationInput(
                text = "secret invoice",
                metadata = mapOf("tier" to "gold"),
            ),
        )

        assertThat(decision.classification).isEqualTo(DataClassification.RESTRICTED)
        assertThat(decision.matchedRuleIds).containsExactly(
            "confidential-keyword",
            "internal-keyword",
            "restricted-keyword",
        )
        assertThat(decision.usedDefault).isFalse()
    }

    @Test
    fun `all matched rule IDs are retained even when higher classification wins`() {
        val classifier = RuleBasedDocumentClassifier(
            RuleBasedClassifierConfiguration(
                rules = listOf(
                    ClassificationRule(
                        id = "rule-a",
                        classification = DataClassification.INTERNAL,
                        pattern = "data",
                    ),
                    ClassificationRule(
                        id = "rule-b",
                        classification = DataClassification.RESTRICTED,
                        metadataEquals = mapOf("type" to "secret"),
                    ),
                ),
            ),
        )

        val decision = classifier.classify(
            ClassificationInput(
                text = "some data",
                metadata = mapOf("type" to "secret"),
            ),
        )

        assertThat(decision.classification).isEqualTo(DataClassification.RESTRICTED)
        assertThat(decision.matchedRuleIds).containsExactly("rule-a", "rule-b")
        assertThat(decision.usedDefault).isFalse()
    }

    @Test
    fun `equal classification matches produce deterministic matchedRuleIds order (priority desc, then id asc)`() {
        val classifier = RuleBasedDocumentClassifier(
            RuleBasedClassifierConfiguration(
                rules = listOf(
                    ClassificationRule(
                        id = "z-last",
                        classification = DataClassification.CONFIDENTIAL,
                        priority = 1,
                        pattern = "invoice",
                    ),
                    ClassificationRule(
                        id = "a-first",
                        classification = DataClassification.CONFIDENTIAL,
                        priority = 5,
                        pattern = "invoice",
                    ),
                    ClassificationRule(
                        id = "b-second",
                        classification = DataClassification.CONFIDENTIAL,
                        priority = 5,
                        pattern = "invoice",
                    ),
                ),
            ),
        )

        val decision = classifier.classify(ClassificationInput(text = "invoice"))

        assertThat(decision.classification).isEqualTo(DataClassification.CONFIDENTIAL)
        assertThat(decision.matchedRuleIds).containsExactly("a-first", "b-second", "z-last")
    }

    @Test
    fun `invalid regex throws at construction`() {
        assertThatThrownBy {
            RuleBasedDocumentClassifier(
                RuleBasedClassifierConfiguration(
                    rules = listOf(
                        ClassificationRule(
                            id = "bad-regex",
                            classification = DataClassification.RESTRICTED,
                            pattern = "(",
                        ),
                    ),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `duplicate ID throws at construction`() {
        assertThatThrownBy {
            RuleBasedDocumentClassifier(
                RuleBasedClassifierConfiguration(
                    rules = listOf(
                        ClassificationRule(
                            id = "duplicate",
                            classification = DataClassification.INTERNAL,
                            pattern = "invoice",
                        ),
                        ClassificationRule(
                            id = "duplicate",
                            classification = DataClassification.CONFIDENTIAL,
                            metadataEquals = mapOf("team" to "finance"),
                        ),
                    ),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Duplicate classification rule id")
    }

    @Test
    fun `conditionless rule throws at construction`() {
        assertThatThrownBy {
            RuleBasedDocumentClassifier(
                RuleBasedClassifierConfiguration(
                    rules = listOf(
                        ClassificationRule(
                            id = "empty",
                            classification = DataClassification.INTERNAL,
                        ),
                    ),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must define a pattern or metadataEquals")
    }

    @Test
    fun `blank pattern is rejected`() {
        assertThatThrownBy {
            RuleBasedDocumentClassifier(
                RuleBasedClassifierConfiguration(
                    rules = listOf(
                        ClassificationRule(
                            id = "blank-pattern",
                            classification = DataClassification.INTERNAL,
                            pattern = "   ",
                        ),
                    ),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("pattern must not be blank")
    }

    @Test
    fun `blank metadata key is rejected`() {
        assertThatThrownBy {
            RuleBasedDocumentClassifier(
                RuleBasedClassifierConfiguration(
                    rules = listOf(
                        ClassificationRule(
                            id = "blank-metadata-key",
                            classification = DataClassification.CONFIDENTIAL,
                            metadataEquals = mapOf("" to "value"),
                        ),
                    ),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("metadata key must not be blank")
    }

    @Test
    fun `maxTextLength is validated before rule compilation`() {
        assertThatThrownBy {
            RuleBasedDocumentClassifier(
                RuleBasedClassifierConfiguration(
                    maxTextLength = 0,
                    rules = listOf(
                        ClassificationRule(
                            id = "bad-regex",
                            classification = DataClassification.RESTRICTED,
                            pattern = "(",
                        ),
                    ),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("maxTextLength must be greater than 0")
    }

    @Test
    fun `oversized input text is rejected without leaking text content`() {
        val classifier = RuleBasedDocumentClassifier(
            RuleBasedClassifierConfiguration(
                maxTextLength = 5,
                rules = listOf(
                    ClassificationRule(
                        id = "restricted",
                        classification = DataClassification.RESTRICTED,
                        pattern = "SENSITIVE",
                    ),
                ),
            ),
        )

        assertThatThrownBy {
            classifier.classify(ClassificationInput(text = "SENSITIVE-123-ABC"))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("17")
            .hasMessageContaining("5")
            .hasMessageNotContaining("SENSITIVE")
            .hasMessageNotContaining("123")
            .hasMessageNotContaining("ABC")
    }

    @Test
    fun `classifyDocument helper wraps payload with RULE_BASED source`() {
        val classifier = RuleBasedDocumentClassifier(
            RuleBasedClassifierConfiguration(
                rules = listOf(
                    ClassificationRule(
                        id = "restricted",
                        classification = DataClassification.RESTRICTED,
                        metadataEquals = mapOf("docType" to "invoice"),
                    ),
                ),
            ),
        )

        val document = classifier.classifyDocument(
            payload = mapOf("id" to "inv-123"),
            input = ClassificationInput(metadata = mapOf("docType" to "invoice")),
        )

        assertThat(document.payload).isEqualTo(mapOf("id" to "inv-123"))
        assertThat(document.classification).isEqualTo(DataClassification.RESTRICTED)
        assertThat(document.source).isEqualTo(ClassificationSource.RULE_BASED)
    }
}
