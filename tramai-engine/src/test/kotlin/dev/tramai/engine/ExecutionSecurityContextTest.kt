package dev.tramai.engine

import dev.tramai.core.model.ClassifiedDocument
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ExecutionSecurityContextTest {

    @Test
    fun `no classified arguments yields empty context`() {
        val context = ExecutionSecurityContext.fromArguments(arrayOf<Any?>("plain", 42, null))

        assertThat(context.dataClassification).isNull()
        assertThat(context.classificationSource).isNull()
    }

    @Test
    fun `single restricted argument preserves classification and source`() {
        val context = ExecutionSecurityContext.fromArguments(
            arrayOf(
                ClassifiedDocument(
                    payload = "secret",
                    classification = DataClassification.RESTRICTED,
                    source = ClassificationSource.DECLARED,
                ),
            ),
        )

        assertThat(context.dataClassification).isEqualTo(DataClassification.RESTRICTED)
        assertThat(context.classificationSource).isEqualTo(ClassificationSource.DECLARED)
    }

    @Test
    fun `highest classification wins across multiple classified arguments`() {
        val context = ExecutionSecurityContext.fromArguments(
            arrayOf(
                ClassifiedDocument(
                    payload = "public",
                    classification = DataClassification.PUBLIC,
                    source = ClassificationSource.DECLARED,
                ),
                ClassifiedDocument(
                    payload = "confidential",
                    classification = DataClassification.CONFIDENTIAL,
                    source = ClassificationSource.RULE_BASED,
                ),
            ),
        )

        assertThat(context.dataClassification).isEqualTo(DataClassification.CONFIDENTIAL)
    }

    @Test
    fun `highest classification source is preserved`() {
        val context = ExecutionSecurityContext.fromArguments(
            arrayOf(
                ClassifiedDocument(
                    payload = "internal",
                    classification = DataClassification.INTERNAL,
                    source = ClassificationSource.DECLARED,
                ),
                ClassifiedDocument(
                    payload = "restricted",
                    classification = DataClassification.RESTRICTED,
                    source = ClassificationSource.LOCAL_MODEL_ASSISTED,
                ),
            ),
        )

        assertThat(context.classificationSource).isEqualTo(ClassificationSource.LOCAL_MODEL_ASSISTED)
    }

    @Test
    fun `equal classification prefers declared source over less authoritative sources`() {
        val context = ExecutionSecurityContext.fromArguments(
            arrayOf(
                ClassifiedDocument(
                    payload = "internal-local",
                    classification = DataClassification.INTERNAL,
                    source = ClassificationSource.LOCAL_MODEL_ASSISTED,
                ),
                ClassifiedDocument(
                    payload = "internal-declared",
                    classification = DataClassification.INTERNAL,
                    source = ClassificationSource.DECLARED,
                ),
            ),
        )

        assertThat(context.dataClassification).isEqualTo(DataClassification.INTERNAL)
        assertThat(context.classificationSource).isEqualTo(ClassificationSource.DECLARED)
    }
}
