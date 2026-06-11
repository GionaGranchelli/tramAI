package dev.tramai.core.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class ModelArtifactDigestTest {

    @Test
    fun `valid digest constructs value`() {
        val digest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}")

        assertThat(digest.value).isEqualTo("sha256:${"a".repeat(64)}")
    }

    @Test
    fun `missing sha256 prefix is rejected`() {
        assertThatThrownBy { ModelArtifactDigest.of("a".repeat(64)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sha256")
    }

    @Test
    fun `wrong prefix is rejected`() {
        assertThatThrownBy { ModelArtifactDigest.of("sha1:${"a".repeat(64)}") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("sha256")
    }

    @Test
    fun `uppercase hex is rejected`() {
        assertThatThrownBy { ModelArtifactDigest.of("sha256:${"A".repeat(64)}") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("lowercase")
    }

    @Test
    fun `blank digest is rejected`() {
        assertThatThrownBy { ModelArtifactDigest.of(" ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("blank")
    }

    @Test
    fun `boundary f digest is valid`() {
        val digest = ModelArtifactDigest.of("sha256:${"f".repeat(64)}")

        assertThat(digest.value).isEqualTo("sha256:${"f".repeat(64)}")
    }
}
