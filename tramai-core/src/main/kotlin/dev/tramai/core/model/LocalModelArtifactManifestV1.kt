package dev.tramai.core.model

import java.util.Collections

class LocalModelArtifactManifestV1(
    val schemaVersion: Int,
    val registryEntryId: String,
    val providerId: String,
    val modelName: String,
    val revision: String,
    artifacts: List<LocalModelArtifactFileV1>,
) {
    val artifacts: List<LocalModelArtifactFileV1> =
        Collections.unmodifiableList(ArrayList(artifacts))

    init {
        require(schemaVersion == 1) { "Schema version must be 1" }
        validateField("registryEntryId", registryEntryId)
        validateField("providerId", providerId)
        validateField("modelName", modelName)
        validateField("revision", revision)
        require(this.artifacts.isNotEmpty()) { "At least one artifact file is required" }
        val paths = this.artifacts.map { it.relativePath }
        require(paths.distinct().size == paths.size) {
            "Duplicate artifact paths (case-sensitive comparison)"
        }
    }

    fun canonicalBytes(): ByteArray {
        val sb = StringBuilder()
        sb.append("schemaVersion=").append(schemaVersion).append('\n')
        sb.append("registryEntryId=").append(registryEntryId).append('\n')
        sb.append("providerId=").append(providerId).append('\n')
        sb.append("modelName=").append(modelName).append('\n')
        sb.append("revision=").append(revision).append('\n')
        sb.append("artifact_count=").append(artifacts.size).append('\n')
        artifacts.sortedBy { it.relativePath }.forEach { artifact ->
            sb.append("  relativePath=").append(artifact.relativePath).append('\n')
            sb.append("  sizeBytes=").append(artifact.sizeBytes).append('\n')
            sb.append("  digest=").append(artifact.digest.value).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
