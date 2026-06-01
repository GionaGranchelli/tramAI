package dev.tramai.core.model

import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ClassifiedDocumentTest {

    @Test
    fun `equal wrappers compare by payload classification and source`() {
        val left = ClassifiedDocument(
            payload = "payload",
            classification = DataClassification.CONFIDENTIAL,
            source = ClassificationSource.DECLARED,
        )
        val right = ClassifiedDocument(
            payload = "payload",
            classification = DataClassification.CONFIDENTIAL,
            source = ClassificationSource.DECLARED,
        )

        assertThat(left).isEqualTo(right)
        assertThat(left.hashCode()).isEqualTo(right.hashCode())
    }

    @Test
    fun `different classification produces inequality`() {
        val left = ClassifiedDocument(
            payload = "payload",
            classification = DataClassification.PUBLIC,
            source = ClassificationSource.DECLARED,
        )
        val right = ClassifiedDocument(
            payload = "payload",
            classification = DataClassification.RESTRICTED,
            source = ClassificationSource.DECLARED,
        )

        assertThat(left).isNotEqualTo(right)
    }

    @Test
    fun `different source produces inequality`() {
        val left = ClassifiedDocument(
            payload = "payload",
            classification = DataClassification.INTERNAL,
            source = ClassificationSource.DECLARED,
        )
        val right = ClassifiedDocument(
            payload = "payload",
            classification = DataClassification.INTERNAL,
            source = ClassificationSource.RULE_BASED,
        )

        assertThat(left).isNotEqualTo(right)
    }

    @Test
    fun `nullable payload is supported`() {
        val document = ClassifiedDocument<String?>(
            payload = null,
            classification = DataClassification.PUBLIC,
            source = ClassificationSource.DECLARED,
        )

        assertThat(document.payload).isNull()
    }

    @Test
    fun `toString redacts payload content`() {
        val document = ClassifiedDocument(
            payload = "sensitive",
            classification = DataClassification.RESTRICTED,
            source = ClassificationSource.DECLARED,
        )

        assertThat(document.toString()).doesNotContain("sensitive")
        assertThat(document.toString())
            .contains("classification=RESTRICTED")
            .contains("source=DECLARED")
    }
}
