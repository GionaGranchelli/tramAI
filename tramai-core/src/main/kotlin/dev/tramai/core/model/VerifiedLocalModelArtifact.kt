package dev.tramai.core.model

import java.time.Instant

data class VerifiedLocalModelArtifact(
    val registryEntryId: String,
    val manifestDigest: ModelArtifactDigest,
    val modelName: String,
    val verifiedAt: Instant,
    val artifactCount: Int,
    val totalSizeBytes: Long,
)
