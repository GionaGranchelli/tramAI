package dev.tramai.security.verification

import dev.tramai.core.model.LocalModelArtifactFileV1
import dev.tramai.core.model.LocalModelArtifactManifestV1
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.RegisteredModel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException

class FileSystemModelArtifactVerifierTest {

    @Test
    fun `valid single file passes`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        val content = "hello verifier".toByteArray(StandardCharsets.UTF_8)
        root.resolve("model.bin").writeBytes(content)
        val manifest = manifestFor(root, "model.bin", "entry-1", "provider-1", "model-1", "rev-1")
        val registeredModel = registeredModelFor(manifest)
        val clock = Clock.fixed(Instant.parse("2026-06-13T10:15:30Z"), ZoneOffset.UTC)

        val receipt = FileSystemModelArtifactVerifier(
            allowedRootDirectories = setOf(root),
            manifests = mapOf(manifest.registryEntryId to manifest),
            clock = clock,
        ).verify(registeredModel)

        assertThat(receipt).isNotNull
        assertThat(receipt?.registryEntryId).isEqualTo("entry-1")
        assertThat(receipt?.modelName).isEqualTo("model-1")
        assertThat(receipt?.artifactCount).isEqualTo(1)
        assertThat(receipt?.totalSizeBytes).isEqualTo(content.size.toLong())
        assertThat(receipt?.verifiedAt).isEqualTo(clock.instant())
        assertThat(receipt?.manifestDigest).isEqualTo(manifestDigest(manifest))
    }
    }

    @Test
    fun `modified byte rejects with digest mismatch`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        val path = root.resolve("model.bin")
        path.writeBytes("original".toByteArray(StandardCharsets.UTF_8))
        val manifest = manifestFor(root, "model.bin")
        path.writeBytes("originaL".toByteArray(StandardCharsets.UTF_8))

        assertThatIllegalStateException()
            .isThrownBy {
                runBlocking {
                    verifier(root, manifest).verify(registeredModelFor(manifest))
                }
            }
            .withMessage("artifact-file-digest-mismatch")
    }
    }

    @Test
    fun `missing file rejects with file not found`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        val path = createFile(root, "missing.bin", byteArrayOf(1))
        val manifest = manifestFor(root, "missing.bin")
        Files.deleteIfExists(path)

        assertThatIllegalStateException()
            .isThrownBy {
                runBlocking {
                    verifier(root, manifest).verify(registeredModelFor(manifest))
                }
            }
            .withMessage("artifact-file-not-found")
    }
    }

    @Test
    fun `empty file passes`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        root.resolve("empty.bin").writeBytes(byteArrayOf())
        val manifest = manifestFor(root, "empty.bin")

        val receipt = verifier(root, manifest).verify(registeredModelFor(manifest))

        assertThat(receipt?.totalSizeBytes).isEqualTo(0)
        assertThat(receipt?.artifactCount).isEqualTo(1)
    }
    }

    @Test
    fun `file size mismatch rejects`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        val path = root.resolve("model.bin")
        path.writeBytes("abc".toByteArray(StandardCharsets.UTF_8))
        val digest = fileDigest(path)
        val manifest = manifestWithFile(
            artifact = LocalModelArtifactFileV1(
                relativePath = "model.bin",
                sizeBytes = 99,
                digest = digest,
            ),
        )

        assertThatIllegalStateException()
            .isThrownBy {
                runBlocking {
                    verifier(root, manifest).verify(registeredModelFor(manifest))
                }
            }
            .withMessage("artifact-file-size-mismatch")
    }
    }

    @Test
    fun `directory substitution rejects`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        root.resolve("model-dir").createDirectories()
        val manifest = manifestWithFile(
            artifact = LocalModelArtifactFileV1(
                relativePath = "model-dir",
                sizeBytes = 0,
                digest = digestOfBytes(byteArrayOf()),
            ),
        )

        assertThatIllegalStateException()
            .isThrownBy {
                runBlocking {
                    verifier(root, manifest).verify(registeredModelFor(manifest))
                }
            }
            .withMessage("artifact-directory-substituted-for-file")
    }
    }

    @Test
    fun `symlink within allowed root rejects`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        val target = createFile(root, "real/model.bin", "allowed".toByteArray(StandardCharsets.UTF_8))
        val link = root.resolve("links/model.bin")
        Files.createDirectories(link.parent)
        Files.createSymbolicLink(link, link.parent.relativize(target))
        val manifest = manifestWithFile(
            artifact = LocalModelArtifactFileV1(
                relativePath = "links/model.bin",
                sizeBytes = Files.size(target),
                digest = fileDigest(target),
            ),
        )

        assertThatIllegalStateException()
            .isThrownBy {
                runBlocking {
                    verifier(root, manifest).verify(registeredModelFor(manifest))
                }
            }
            .withMessage("artifact-file-symlink-rejected")
    }
    }

    @Test
    fun `symlink escaping root rejects`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        val outsideRoot = Files.createTempDirectory("artifact-outside")
        val target = createFile(outsideRoot, "escape.bin", "escape".toByteArray(StandardCharsets.UTF_8))
        Files.createSymbolicLink(root.resolve("escape.bin"), target)
        val manifest = manifestWithFile(
            artifact = LocalModelArtifactFileV1(
                relativePath = "escape.bin",
                sizeBytes = Files.size(target),
                digest = fileDigest(target),
            ),
        )

        assertThatIllegalStateException()
            .isThrownBy {
                runBlocking {
                    verifier(root, manifest).verify(registeredModelFor(manifest))
                }
            }
            .withMessage("artifact-file-symlink-rejected")
    }
    }

    @Test
    fun `parent symlink rejects when target stays within allowed root`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        val targetDir = root.resolve("actual")
        Files.createDirectories(targetDir)
        val target = createFile(root, "actual/model.bin", "parent-link".toByteArray(StandardCharsets.UTF_8))
        Files.createSymbolicLink(root.resolve("linked"), root.relativize(targetDir))
        val manifest = manifestWithFile(
            artifact = LocalModelArtifactFileV1(
                relativePath = "linked/model.bin",
                sizeBytes = Files.size(target),
                digest = fileDigest(target),
            ),
        )

        assertThatIllegalStateException()
            .isThrownBy {
                runBlocking {
                    verifier(root, manifest).verify(registeredModelFor(manifest))
                }
            }
            .withMessage("artifact-file-symlink-rejected")
    }
    }

    @Test
    fun `digest optional mode verifies bytes without registry pinned digest`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        val content = "transitional bytes".toByteArray(StandardCharsets.UTF_8)
        root.resolve("model.bin").writeBytes(content)
        val manifest = manifestFor(root, "model.bin", "entry-1", "provider-1", "model-1", "rev-1")
        val registeredModel = registeredModelFor(manifest).copy(artifactDigest = null)

        val receipt = verifier(root, manifest).verify(registeredModel)

        assertThat(receipt).isNotNull
        assertThat(receipt?.artifactCount).isEqualTo(1)
        assertThat(receipt?.totalSizeBytes).isEqualTo(content.size.toLong())
        assertThat(receipt?.manifestDigest).isEqualTo(manifestDigest(manifest))
    }
    }

    @Test
    fun `total size overflow rejects`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        val firstContent = byteArrayOf(1)
        val secondContent = byteArrayOf(2)
        createFile(root, "first.bin", firstContent)
        createFile(root, "second.bin", secondContent)
        val manifest = LocalModelArtifactManifestV1(
            schemaVersion = 1,
            registryEntryId = "entry-1",
            providerId = "provider-1",
            modelName = "model-1",
            revision = "rev-1",
            artifacts = listOf(
                LocalModelArtifactFileV1(
                    relativePath = "first.bin",
                    sizeBytes = Long.MAX_VALUE,
                    digest = digestOfBytes(firstContent),
                ),
                LocalModelArtifactFileV1(
                    relativePath = "second.bin",
                    sizeBytes = 1,
                    digest = digestOfBytes(secondContent),
                ),
            ),
        )

        assertThatIllegalStateException()
            .isThrownBy {
                runBlocking {
                    verifier(root, manifest).verify(registeredModelFor(manifest))
                }
            }
            .withMessage("artifact-total-size-overflow")
    }
    }

    @Test
    fun `unknown manifest returns null`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        val manifest = manifestForExistingBytes(
            relativePath = "model.bin",
            content = "bytes".toByteArray(StandardCharsets.UTF_8),
            root = root,
            registryEntryId = "entry-1",
        )

        val result = FileSystemModelArtifactVerifier(
            allowedRootDirectories = setOf(root),
            manifests = emptyMap(),
        ).verify(registeredModelFor(manifest))

        assertThat(result).isNull()
    }
    }

    @Test
    fun `identity drift rejects`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        val manifest = manifestForExistingBytes(
            relativePath = "model.bin",
            content = "bytes".toByteArray(StandardCharsets.UTF_8),
            root = root,
        )
        val registeredModel = registeredModelFor(manifest).copy(providerId = "provider-2")

        assertThatIllegalStateException()
            .isThrownBy {
                runBlocking {
                    verifier(root, manifest).verify(registeredModel)
                }
            }
            .withMessage("artifact-manifest-identity-drift")
    }
    }

    @Test
    fun `aggregate digest mismatch rejects`() { runBlocking {
        val root = Files.createTempDirectory("artifact-root")
        val manifest = manifestForExistingBytes(
            relativePath = "model.bin",
            content = "bytes".toByteArray(StandardCharsets.UTF_8),
            root = root,
        )
        val registeredModel = registeredModelFor(manifest).copy(
            artifactDigest = ModelArtifactDigest.of("sha256:${"f".repeat(64)}"),
        )

        assertThatIllegalStateException()
            .isThrownBy {
                runBlocking {
                    verifier(root, manifest).verify(registeredModel)
                }
            }
            .withMessage("artifact-aggregate-digest-mismatch")
    }
    }

    private fun verifier(
        root: Path,
        manifest: LocalModelArtifactManifestV1,
    ) = FileSystemModelArtifactVerifier(
        allowedRootDirectories = setOf(root),
        manifests = mapOf(manifest.registryEntryId to manifest),
        clock = Clock.fixed(Instant.parse("2026-06-13T00:00:00Z"), ZoneOffset.UTC),
    )

    private fun manifestFor(
        root: Path,
        relativePath: String,
        registryEntryId: String = "entry-1",
        providerId: String = "provider-1",
        modelName: String = "model-1",
        revision: String = "rev-1",
    ): LocalModelArtifactManifestV1 {
        val path = root.resolve(relativePath)
        return manifestForExistingPath(path, relativePath, registryEntryId, providerId, modelName, revision)
    }

    private fun manifestForExistingBytes(
        relativePath: String,
        content: ByteArray,
        root: Path,
        registryEntryId: String = "entry-1",
        providerId: String = "provider-1",
        modelName: String = "model-1",
        revision: String = "rev-1",
    ): LocalModelArtifactManifestV1 {
        val path = createFile(root, relativePath, content)
        return manifestForExistingPath(path, relativePath, registryEntryId, providerId, modelName, revision)
    }

    private fun manifestForExistingPath(
        path: Path,
        relativePath: String,
        registryEntryId: String,
        providerId: String,
        modelName: String,
        revision: String,
    ): LocalModelArtifactManifestV1 = manifestWithFile(
        registryEntryId = registryEntryId,
        providerId = providerId,
        modelName = modelName,
        revision = revision,
        artifact = LocalModelArtifactFileV1(
            relativePath = relativePath,
            sizeBytes = Files.size(path),
            digest = fileDigest(path),
        ),
    )

    private fun manifestWithFile(
        artifact: LocalModelArtifactFileV1,
        registryEntryId: String = "entry-1",
        providerId: String = "provider-1",
        modelName: String = "model-1",
        revision: String = "rev-1",
    ): LocalModelArtifactManifestV1 {
        return LocalModelArtifactManifestV1(
            schemaVersion = 1,
            registryEntryId = registryEntryId,
            providerId = providerId,
            modelName = modelName,
            revision = revision,
            artifacts = listOf(artifact),
        )
    }

    private fun registeredModelFor(manifest: LocalModelArtifactManifestV1): RegisteredModel = RegisteredModel(
        registryEntryId = manifest.registryEntryId,
        providerId = manifest.providerId,
        modelName = manifest.modelName,
        revision = manifest.revision,
        artifactDigest = manifestDigest(manifest),
    )

    private fun manifestDigest(manifest: LocalModelArtifactManifestV1): ModelArtifactDigest =
        digestOfBytes(manifest.canonicalBytes())

    private fun fileDigest(path: Path): ModelArtifactDigest =
        digestOfBytes(Files.readAllBytes(path))

    private fun digestOfBytes(bytes: ByteArray): ModelArtifactDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        return ModelArtifactDigest.of("sha256:${digest.digest(bytes).toHexString()}")
    }

    private fun createFile(root: Path, relativePath: String, content: ByteArray): Path {
        val path = root.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.write(path, content)
        return path
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }
}
