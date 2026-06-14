package dev.tramai.sovereign.evidence

/**
 * Summary of artifact verification for one model.
 *
 * Carries the minimum information needed to attest that a model's
 * artifact files were verified against their registered manifest
 * digests at build time.
 *
 * @property registryEntryId The approved-model registry entry that was verified.
 * @property manifestDigest String representation of the manifest's canonical digest.
 * @property modelName The local model name as declared in the manifest.
 * @property verifiedAt ISO-8601 instant of verification.
 * @property artifactCount Number of artifact files in the manifest.
 * @property totalSizeBytes Aggregate byte size of all artifact files.
 */
data class ArtifactEvidenceV1(
    val registryEntryId: String,
    val manifestDigest: String,
    val modelName: String,
    val verifiedAt: String,
    val artifactCount: Int,
    val totalSizeBytes: Long,
)
