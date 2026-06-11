package dev.tramai.core.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class RegisteredModelTest {

    @Test
    fun `valid construction succeeds`() {
        val model = RegisteredModel(
            registryEntryId = "entry-1",
            providerId = "provider-1",
            modelName = "model-1",
            revision = "rev-1",
            artifactDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
            enabled = false,
        )

        assertThat(model.registryEntryId).isEqualTo("entry-1")
        assertThat(model.providerId).isEqualTo("provider-1")
        assertThat(model.modelName).isEqualTo("model-1")
        assertThat(model.revision).isEqualTo("rev-1")
        assertThat(model.enabled).isFalse()
    }

    @Test
    fun `blank registryEntryId is rejected`() {
        assertInvalid("registryEntryId", "")
    }

    @Test
    fun `blank providerId is rejected`() {
        assertInvalid("providerId", "")
    }

    @Test
    fun `blank modelName is rejected`() {
        assertInvalid("modelName", "")
    }

    @Test
    fun `blank revision is rejected`() {
        assertInvalid("revision", "")
    }

    @Test
    fun `surrounding whitespace is rejected`() {
        assertInvalid("providerId", " provider ")
    }

    @Test
    fun `control character is rejected`() {
        assertInvalid("modelName", "model\nname")
    }

    @Test
    fun `overlong value is rejected`() {
        assertInvalid("revision", "a".repeat(257))
    }

    @Test
    fun `digest defaults to null`() {
        val model = RegisteredModel(
            registryEntryId = "entry-1",
            providerId = "provider-1",
            modelName = "model-1",
            revision = "rev-1",
        )

        assertThat(model.artifactDigest).isNull()
    }

    private fun assertInvalid(fieldName: String, value: String) {
        assertThatThrownBy {
            RegisteredModel(
                registryEntryId = if (fieldName == "registryEntryId") value else "entry-1",
                providerId = if (fieldName == "providerId") value else "provider-1",
                modelName = if (fieldName == "modelName") value else "model-1",
                revision = if (fieldName == "revision") value else "rev-1",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(fieldName)
    }
}
