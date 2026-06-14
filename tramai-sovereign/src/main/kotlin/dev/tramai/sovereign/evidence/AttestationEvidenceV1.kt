package dev.tramai.sovereign.evidence

/**
 * Describes GitHub Artifact Attestations linked to a sovereign evidence pack.
 *
 * Contains only repository/workflow identity, commit SHA, file names, digests,
 * and attestation types. Does **not** embed the attestation payload.
 *
 * @property schemaVersion Schema version (currently 1).
 * @property provider The attestation provider (e.g. "GitHub Artifact Attestations").
 * @property workflowName The GitHub Actions workflow name.
 * @property workflowRunId The GitHub Actions workflow run ID (numeric).
 * @property repository The GitHub repository ("owner/repo" format).
 * @property commitSha The full 40-character commit SHA.
 * @property attestedSubjects The list of attested artifact references.
 */
data class AttestationEvidenceV1(
    val schemaVersion: Int = 1,
    val provider: String,
    val workflowName: String,
    val workflowRunId: String,
    val repository: String,
    val commitSha: String,
    val attestedSubjects: List<AttestedSubjectV1>,
)

/**
 * References one attested artifact with its digest and attestation type.
 *
 * @property fileName The artifact filename (no path components).
 * @property sha256 The SHA-256 digest in "sha256:<hex>" format.
 * @property attestationType The attestation type: "build-provenance" or "sbom".
 */
data class AttestedSubjectV1(
    val fileName: String,
    val sha256: String,
    val attestationType: String,
)
