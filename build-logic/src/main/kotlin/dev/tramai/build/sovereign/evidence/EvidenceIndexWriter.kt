package dev.tramai.build.sovereign.evidence

import java.io.File

/**
 * Serializes the sovereign release evidence index to JSON and Markdown
 * (9.2b extraction). Schema sovereign-release-evidence-index-v1 is preserved.
 */
object EvidenceIndexWriter {

    fun writeJson(index: SovereignReleaseEvidenceIndexV1): String {
        val json = buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": \"${Hashing.jsonEscape(index.schemaVersion)}\",")
            appendLine("  \"generatedAt\": \"${Hashing.jsonEscape(index.generatedAt)}\",")
            appendLine("  \"repository\": \"${Hashing.jsonEscape(index.repository)}\",")
            appendLine("  \"commitSha\": \"${Hashing.jsonEscape(index.commitSha)}\",")
            appendLine("  \"refName\": \"${Hashing.jsonEscape(index.refName)}\",")
            appendLine("  \"version\": \"${Hashing.jsonEscape(index.version)}\",")
            appendLine("  \"remotePublish\": ${index.remotePublish},")
            appendLine("  \"tagCreated\": ${index.tagCreated},")
            appendLine("  \"releaseCandidate\": ${index.releaseCandidate},")
            appendLine("  \"artifacts\": [")
            for ((i, artifact) in index.artifacts.withIndex()) {
                append("    ")
                append(writeArtifact(artifact))
                if (i < index.artifacts.lastIndex) append(",")
                appendLine()
            }
            appendLine("  ],")
            appendLine("  \"checks\": {")
            appendLine("    \"releaseReadiness\": ${writeCheck(index.checks.releaseReadiness)},")
            appendLine("    \"sovereignRuntimePublication\": ${writeCheck(index.checks.sovereignRuntimePublication)},")
            appendLine("    \"sovereignRuntimeSignedBundle\": ${writeCheck(index.checks.sovereignRuntimeSignedBundle)},")
            appendLine("    \"consumerSmoke\": ${writeConsumerSmokeCheck(index.checks.consumerSmoke)}")
            appendLine("  }")
            append("}")
            appendLine()
        }
        return json
    }

    private fun writeArtifact(artifact: EvidenceArtifact): String = buildString {
        append("{")
        append("\"id\": \"${Hashing.jsonEscape(artifact.id)}\", ")
        append("\"path\": \"${Hashing.jsonEscape(artifact.path)}\", ")
        append("\"type\": \"${Hashing.jsonEscape(artifact.type)}\", ")
        append("\"required\": ${artifact.required}")
        artifact.sha256?.let { append(", \"sha256\": \"${Hashing.jsonEscape(it)}\"") }
        artifact.fileCount?.let { append(", \"fileCount\": $it") }
        artifact.sha256Tree?.let { append(", \"sha256Tree\": \"${Hashing.jsonEscape(it)}\"") }
        append("}")
    }

    private fun writeCheck(check: EvidenceCheck): String = buildString {
        append("{")
        append("\"status\": \"${Hashing.jsonEscape(check.status)}\", ")
        append("\"taskPath\": \"${Hashing.jsonEscape(check.taskPath)}\"")
        append("}")
    }

    private fun writeConsumerSmokeCheck(check: ConsumerSmokeEvidenceCheck): String = buildString {
        append("{")
        append("\"status\": \"${Hashing.jsonEscape(check.status)}\", ")
        append("\"taskPath\": \"${Hashing.jsonEscape(check.taskPath)}\", ")
        append("\"executes\": \"${Hashing.jsonEscape(check.executes)}\", ")
        append("\"devTramaiResolutionPolicy\": {")
        append("\"allowedRepositories\": [${check.devTramaiResolutionPolicy.allowedRepositories.joinToString(", ") { "\"${Hashing.jsonEscape(it)}\"" }}], ")
        append("\"blockedRepositories\": [${check.devTramaiResolutionPolicy.blockedRepositories.joinToString(", ") { "\"${Hashing.jsonEscape(it)}\"" }}], ")
        append("\"coverage\": \"${Hashing.jsonEscape(check.devTramaiResolutionPolicy.coverage)}\"")
        append("}")
        append("}")
    }

    fun writeMarkdown(index: SovereignReleaseEvidenceIndexV1): String = buildString {
        appendLine("# Sovereign Release Evidence Index")
        appendLine()
        appendLine("- Repository: ${index.repository}")
        appendLine("- Commit: ${index.commitSha}")
        appendLine("- Ref: ${index.refName}")
        appendLine("- Version: ${index.version}")
        appendLine("- Generated at: ${index.generatedAt}")
        appendLine("- Remote publish: ${index.remotePublish}")
        appendLine("- Tag created: ${index.tagCreated}")
        appendLine("- Release candidate: ${index.releaseCandidate}")
        appendLine()
        appendLine("## Evidence Artifacts")
        appendLine()
        appendLine("| ID | Path | Type | Required | SHA-256 |")
        appendLine("|----|------|------|----------|---------|")
        for (artifact in index.artifacts) {
            val digest = artifact.sha256 ?: artifact.sha256Tree ?: ""
            appendLine("| ${artifact.id} | ${artifact.path} | ${artifact.type} | ${if (artifact.required) "yes" else "no"} | $digest |")
        }
        appendLine()
        appendLine("## Validation Gates")
        appendLine()
        appendLine("| Gate | Status | Task | Source |")
        appendLine("|------|--------|------|--------|")
        appendLine("| Release readiness | ${index.checks.releaseReadiness.status} | ${index.checks.releaseReadiness.taskPath} | build metadata and artifact validation |")
        appendLine("| Sovereign runtime publication | ${index.checks.sovereignRuntimePublication.status} | ${index.checks.sovereignRuntimePublication.taskPath} | published to mavenLocal |")
        appendLine("| Signed bundle dry-run | ${index.checks.sovereignRuntimeSignedBundle.status} | ${index.checks.sovereignRuntimeSignedBundle.taskPath} | build/sovereign-runtime-release-verification-repo |")
        appendLine("| Consumer smoke | ${index.checks.consumerSmoke.status} | ${index.checks.consumerSmoke.taskPath} | full dev.tramai closure from build/sovereign-runtime-release-verification-repo |")
    }
}
