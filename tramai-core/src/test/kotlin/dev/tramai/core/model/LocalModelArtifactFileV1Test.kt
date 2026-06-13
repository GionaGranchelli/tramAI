package dev.tramai.core.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class LocalModelArtifactFileV1Test {

    @Test
    fun `valid dto constructs value`() {
        val file = LocalModelArtifactFileV1(
            relativePath = "models/model.gguf",
            sizeBytes = 42L,
            digest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
        )

        assertThat(file.relativePath).isEqualTo("models/model.gguf")
        assertThat(file.sizeBytes).isEqualTo(42L)
        assertThat(file.digest.value).isEqualTo("sha256:${"a".repeat(64)}")
    }

    @Test
    fun `blank path is rejected`() {
        assertInvalidPath(" ")
    }

    @Test
    fun `absolute path is rejected`() {
        assertInvalidPath("/models/model.gguf")
    }

    @Test
    fun `traversal path is rejected`() {
        assertInvalidPath("../models/model.gguf")
    }

    @Test
    fun `negative sizeBytes is rejected`() {
        assertThatThrownBy {
            LocalModelArtifactFileV1(
                relativePath = "models/model.gguf",
                sizeBytes = -1L,
                digest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sizeBytes")
    }

    @Test
    fun `control chars are rejected`() {
        assertInvalidPath("models/\nmodel.gguf")
    }

    @Test
    fun `Windows drive prefix is rejected`() {
        assertInvalidPath("C:/models/model.gguf")
    }

    @Test
    fun `UNC path is rejected`() {
        assertInvalidPath("\\\\server\\share\\model.gguf")
    }

    @Test
    fun `backslash separator is rejected`() {
        assertInvalidPath("models\\model.gguf")
    }

    @Test
    fun `self reference segment is rejected`() {
        assertInvalidPath("models/./model.gguf")
    }

    @Test
    fun `double slash is rejected`() {
        assertInvalidPath("models//model.gguf")
    }

    @Test
    fun `trailing double-dot is rejected`() {
        assertInvalidPath("models/..")
    }

    private fun assertInvalidPath(path: String) {
        assertThatThrownBy {
            LocalModelArtifactFileV1(
                relativePath = path,
                sizeBytes = 1L,
                digest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Path")
    }
}
