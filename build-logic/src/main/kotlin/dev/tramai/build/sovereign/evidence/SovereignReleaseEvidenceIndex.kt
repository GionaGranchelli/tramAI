package dev.tramai.build.sovereign.evidence

/**
 * Sovereign release evidence index model (schema sovereign-release-evidence-index-v1).
 * Moved verbatim from the historical root build script — field names and
 * serialization shape are a frozen evidence contract (9.2b does not revise it).
 */
data class SovereignReleaseEvidenceIndexV1(
    val schemaVersion: String,
    val generatedAt: String,
    val repository: String,
    val commitSha: String,
    val refName: String,
    val version: String,
    val remotePublish: Boolean,
    val tagCreated: Boolean,
    val releaseCandidate: Boolean,
    val artifacts: List<EvidenceArtifact>,
    val checks: EvidenceChecks,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "schemaVersion" to schemaVersion,
        "generatedAt" to generatedAt,
        "repository" to repository,
        "commitSha" to commitSha,
        "refName" to refName,
        "version" to version,
        "remotePublish" to remotePublish,
        "tagCreated" to tagCreated,
        "releaseCandidate" to releaseCandidate,
        "artifacts" to artifacts.map { it.toMap() },
        "checks" to checks.toMap(),
    )
}

data class EvidenceArtifact(
    val id: String,
    val path: String,
    val type: String,
    val required: Boolean,
    val sha256: String? = null,
    val fileCount: Int? = null,
    val sha256Tree: String? = null,
) {
    fun toMap(): Map<String, Any> =
        buildMap {
            put("id", id)
            put("path", path)
            put("type", type)
            put("required", required)
            sha256?.let { put("sha256", it) }
            fileCount?.let { put("fileCount", it) }
            sha256Tree?.let { put("sha256Tree", it) }
        }
}

data class EvidenceChecks(
    val releaseReadiness: EvidenceCheck,
    val sovereignRuntimePublication: EvidenceCheck,
    val sovereignRuntimeSignedBundle: EvidenceCheck,
    val consumerSmoke: ConsumerSmokeEvidenceCheck,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "releaseReadiness" to releaseReadiness.toMap(),
        "sovereignRuntimePublication" to sovereignRuntimePublication.toMap(),
        "sovereignRuntimeSignedBundle" to sovereignRuntimeSignedBundle.toMap(),
        "consumerSmoke" to consumerSmoke.toMap(),
    )
}

data class EvidenceCheck(
    val status: String,
    val taskPath: String,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "status" to status,
        "taskPath" to taskPath,
    )
}

data class ConsumerSmokeEvidenceCheck(
    val status: String,
    val taskPath: String,
    val executes: String,
    val devTramaiResolutionPolicy: DevTramaiResolutionPolicy,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "status" to status,
        "taskPath" to taskPath,
        "executes" to executes,
        "devTramaiResolutionPolicy" to devTramaiResolutionPolicy.toMap(),
    )
}

data class DevTramaiResolutionPolicy(
    val allowedRepositories: List<String>,
    val blockedRepositories: List<String>,
    val coverage: String,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "allowedRepositories" to allowedRepositories,
        "blockedRepositories" to blockedRepositories,
        "coverage" to coverage,
    )
}
