package dev.tramai.core.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class LocalModelArtifactManifestV1Test {

    @Test
    fun `valid single file manifest constructs canonical bytes`() {
        val manifest = LocalModelArtifactManifestV1(
            schemaVersion = 1,
            registryEntryId = "entry-1",
            providerId = "provider-1",
            modelName = "model-1",
            revision = "rev-1",
            artifacts = listOf(
                artifact("models/model-a.gguf", 10L, "a"),
            ),
        )

        assertThat(manifest.canonicalBytes().toString(Charsets.UTF_8)).isEqualTo(
            """
            schemaVersion=1
            registryEntryId=entry-1
            providerId=provider-1
            modelName=model-1
            revision=rev-1
            artifact_count=1
              relativePath=models/model-a.gguf
              sizeBytes=10
              digest=sha256:${"a".repeat(64)}
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `valid multi file manifest canonicalizes sorted artifact order`() {
        val manifest = LocalModelArtifactManifestV1(
            schemaVersion = 1,
            registryEntryId = "entry-1",
            providerId = "provider-1",
            modelName = "model-1",
            revision = "rev-1",
            artifacts = listOf(
                artifact("z-last.gguf", 30L, "c"),
                artifact("a-first.gguf", 10L, "a"),
                artifact("m-middle.gguf", 20L, "b"),
            ),
        )

        assertThat(manifest.canonicalBytes().toString(Charsets.UTF_8)).isEqualTo(
            """
            schemaVersion=1
            registryEntryId=entry-1
            providerId=provider-1
            modelName=model-1
            revision=rev-1
            artifact_count=3
              relativePath=a-first.gguf
              sizeBytes=10
              digest=sha256:${"a".repeat(64)}
              relativePath=m-middle.gguf
              sizeBytes=20
              digest=sha256:${"b".repeat(64)}
              relativePath=z-last.gguf
              sizeBytes=30
              digest=sha256:${"c".repeat(64)}
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `duplicate paths are rejected`() {
        assertThatThrownBy {
            LocalModelArtifactManifestV1(
                schemaVersion = 1,
                registryEntryId = "entry-1",
                providerId = "provider-1",
                modelName = "model-1",
                revision = "rev-1",
                artifacts = listOf(
                    artifact("dup.gguf", 10L, "a"),
                    artifact("dup.gguf", 20L, "b"),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Duplicate artifact paths")
    }

    @Test
    fun `blank path is rejected`() {
        assertThatThrownBy {
            LocalModelArtifactManifestV1(
                schemaVersion = 1,
                registryEntryId = "entry-1",
                providerId = "provider-1",
                modelName = "model-1",
                revision = "rev-1",
                artifacts = listOf(
                    artifact(" ", 10L, "a"),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("relativePath")
    }

    @Test
    fun `absolute path is rejected`() {
        assertThatThrownBy {
            LocalModelArtifactManifestV1(
                schemaVersion = 1,
                registryEntryId = "entry-1",
                providerId = "provider-1",
                modelName = "model-1",
                revision = "rev-1",
                artifacts = listOf(
                    artifact("/tmp/model.gguf", 10L, "a"),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("absolute")
    }

    @Test
    fun `traversal path is rejected`() {
        assertThatThrownBy {
            LocalModelArtifactManifestV1(
                schemaVersion = 1,
                registryEntryId = "entry-1",
                providerId = "provider-1",
                modelName = "model-1",
                revision = "rev-1",
                artifacts = listOf(
                    artifact("../model.gguf", 10L, "a"),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("traverse")
    }

    @Test
    fun `invalid schemaVersion is rejected`() {
        assertThatThrownBy {
            LocalModelArtifactManifestV1(
                schemaVersion = 2,
                registryEntryId = "entry-1",
                providerId = "provider-1",
                modelName = "model-1",
                revision = "rev-1",
                artifacts = listOf(
                    artifact("model.gguf", 10L, "a"),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Schema version")
    }

    @Test
    fun `empty artifacts are rejected`() {
        assertThatThrownBy {
            LocalModelArtifactManifestV1(
                schemaVersion = 1,
                registryEntryId = "entry-1",
                providerId = "provider-1",
                modelName = "model-1",
                revision = "rev-1",
                artifacts = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("At least one artifact file is required")
    }

    @Test
    fun `mutable source list after construction does not affect immutable manifest`() {
        val artifactList = mutableListOf(
            artifact("original.gguf", 10L, "a"),
        )
        val manifest = LocalModelArtifactManifestV1(
            schemaVersion = 1,
            registryEntryId = "entry-1",
            providerId = "provider-1",
            modelName = "model-1",
            revision = "rev-1",
            artifacts = artifactList,
        )
        val canonicalBefore = manifest.canonicalBytes()
        artifactList.clear()
        artifactList += artifact("substituted.gguf", 99L, "b")
        val canonicalAfter = manifest.canonicalBytes()
        assertThat(canonicalBefore).isEqualTo(canonicalAfter)
        assertThat(manifest.artifacts).hasSize(1)
        assertThat(manifest.artifacts[0].relativePath).isEqualTo("original.gguf")
    }

    private fun artifact(relativePath: String, sizeBytes: Long, fill: String): LocalModelArtifactFileV1 =
        LocalModelArtifactFileV1(
            relativePath = relativePath,
            sizeBytes = sizeBytes,
            digest = ModelArtifactDigest.of("sha256:${fill.repeat(64)}"),
        )
}
