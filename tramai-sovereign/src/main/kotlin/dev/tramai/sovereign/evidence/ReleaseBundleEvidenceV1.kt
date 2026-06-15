package dev.tramai.sovereign.evidence

/**
 * Release-bundle evidence capturing the JAR artifacts built by Gradle
 * for a sovereign TramAI deployment.
 *
 * This DTO links the build-time release artifact manifest into the evidence pack
 * so that auditors can trace the exact JARs shipped with the deployment.
 *
 * @property schemaVersion Schema version (currently 1).
 * @property buildTool The build tool that produced the artifacts (e.g. "Gradle").
 * @property javaVersion The Java runtime version used during the build.
 * @property gradleVersion The Gradle version used during the build.
 * @property artifacts The list of individual release artifacts.
 */
data class ReleaseBundleEvidenceV1(
    val schemaVersion: Int = 1,
    val buildTool: String,
    val javaVersion: String,
    val gradleVersion: String,
    val artifacts: List<ReleaseArtifactEvidenceV1>,
)

/**
 * Describes a single release artifact with its Maven coordinates, file identity,
 * SHA-256 digest, and size.
 *
 * @property groupId The Maven group ID (e.g. "dev.tramai").
 * @property artifactId The Maven artifact ID (e.g. "tramai-core").
 * @property version The artifact version (e.g. "1.0.0").
 * @property classifier Optional classifier (e.g. "sources", "javadoc", or null).
 * @property extension The file extension (e.g. "jar").
 * @property fileName The exact file name (e.g. "tramai-core-1.0.0.jar").
 * @property sha256 The SHA-256 digest in "sha256:<hex>" format.
 * @property sizeBytes The file size in bytes.
 */
data class ReleaseArtifactEvidenceV1(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val classifier: String?,
    val extension: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
)
