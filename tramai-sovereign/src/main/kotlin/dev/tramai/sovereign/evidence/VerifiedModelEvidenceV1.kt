package dev.tramai.sovereign.evidence

/**
 * Summarises a verified model from the approved-model registry.
 *
 * @property modelName The model name as declared in the registry.
 * @property providerId The provider serving this model.
 * @property revision The registry-declared revision string.
 * @property artifactDigestRegistered Whether the registry entry has an
 *   artifact digest recorded (true) or not (false).
 */
data class VerifiedModelEvidenceV1(
    val modelName: String,
    val providerId: String,
    val revision: String,
    val artifactDigestRegistered: Boolean,
)
