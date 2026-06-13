package dev.tramai.security.verification

import dev.tramai.core.model.LocalModelArtifactFileV1
import dev.tramai.core.model.LocalModelArtifactManifestV1
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelArtifactVerifier
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.model.VerifiedLocalModelArtifact
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock

class FileSystemModelArtifactVerifier(
    allowedRootDirectories: Set<Path>,
    manifests: Map<String, LocalModelArtifactManifestV1>,
    private val clock: Clock = Clock.systemUTC(),
) : ModelArtifactVerifier {

    private val allowedRoots = allowedRootDirectories
        .map { it.toAbsolutePath().normalize() }
        .toSet()

    private val manifests = manifests.toMap()

    override suspend fun verify(registeredModel: RegisteredModel): VerifiedLocalModelArtifact? {
        val manifest = manifests[registeredModel.registryEntryId] ?: return null

        ensureIdentityMatches(manifest, registeredModel)
        val manifestDigest = sha256Digest(manifest.canonicalBytes())

        // Verify aggregate digest only when the registry pins one
        registeredModel.artifactDigest?.let { expected ->
            check(expected == manifestDigest) {
                "artifact-aggregate-digest-mismatch"
            }
        }

        var totalSizeBytes = 0L
        manifest.artifacts.toList().forEach { artifact ->
            verifyArtifactFile(artifact)
            totalSizeBytes = try {
                Math.addExact(totalSizeBytes, artifact.sizeBytes)
            } catch (_: ArithmeticException) {
                error("artifact-total-size-overflow")
            }
        }

        return VerifiedLocalModelArtifact(
            registryEntryId = registeredModel.registryEntryId,
            manifestDigest = manifestDigest,
            modelName = registeredModel.modelName,
            verifiedAt = clock.instant(),
            artifactCount = manifest.artifacts.size,
            totalSizeBytes = totalSizeBytes,
        )
    }

    private fun ensureIdentityMatches(
        manifest: LocalModelArtifactManifestV1,
        registeredModel: RegisteredModel,
    ) {
        check(manifest.registryEntryId == registeredModel.registryEntryId) {
            "artifact-manifest-identity-drift"
        }
        check(manifest.providerId == registeredModel.providerId) {
            "artifact-manifest-identity-drift"
        }
        check(manifest.modelName == registeredModel.modelName) {
            "artifact-manifest-identity-drift"
        }
        check(manifest.revision == registeredModel.revision) {
            "artifact-manifest-identity-drift"
        }
    }

    private fun verifyArtifactFile(artifact: LocalModelArtifactFileV1) {
        for (root in allowedRoots) {
            val candidate = root.resolve(artifact.relativePath)
            val normalizedCandidate = candidate.toAbsolutePath().normalize()
            if (!normalizedCandidate.startsWith(root)) {
                continue // traversal — try next root
            }

            if (!Files.exists(normalizedCandidate)) {
                continue
            }

            // Strict symlink policy: reject any symlink in the path chain
            val realPath = runCatching { normalizedCandidate.toRealPath() }
                .getOrElse { error("artifact-file-symlink-rejected") }
            if (realPath != normalizedCandidate.toAbsolutePath().normalize()) {
                error("artifact-file-symlink-rejected")
            }

            if (!Files.isRegularFile(normalizedCandidate)) {
                check(!Files.isDirectory(normalizedCandidate)) {
                    "artifact-directory-substituted-for-file"
                }
                error("artifact-not-a-regular-file")
            }

            val actualSizeBeforeHash = Files.size(normalizedCandidate)
            check(actualSizeBeforeHash == artifact.sizeBytes) { "artifact-file-size-mismatch" }

            val actualDigest = hashFile(normalizedCandidate)
            check(actualDigest == artifact.digest) { "artifact-file-digest-mismatch" }

            val actualSizeAfterHash = Files.size(normalizedCandidate)
            check(actualSizeAfterHash == artifact.sizeBytes) { "artifact-file-size-mismatch" }
            return
        }

        error("artifact-file-not-found")
    }

    private fun hashFile(path: Path): ModelArtifactDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return ModelArtifactDigest.of("sha256:${digest.digest().toHexString()}")
    }

    private fun sha256Digest(bytes: ByteArray): ModelArtifactDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        return ModelArtifactDigest.of("sha256:${digest.digest(bytes).toHexString()}")
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }

    private companion object {
        const val HASH_BUFFER_SIZE = 64 * 1024
    }
}
