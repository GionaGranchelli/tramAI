package dev.tramai.sovereign.evidence

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a [SovereignEvidencePackV1] as deterministic JSON to a file.
 *
 * Features:
 * - Stable field ordering matching the data class declaration order
 * - Full JSON control-character escaping (all chars < 0x20 as \uXXXX)
 * - No external JSON library dependency
 * - Creates parent directories if they do not exist
 */
object SovereignEvidencePackWriter {

    /**
     * Serialises the [pack] as JSON and writes it to [path].
     * Creates parent directories if they do not exist.
     *
     * @throws Exception on any I/O or serialisation failure.
     */
    fun write(pack: SovereignEvidencePackV1, path: Path) {
        path.parent?.let { Files.createDirectories(it) }
        val json = serialize(pack)
        Files.writeString(path, json)
    }

    private fun serialize(pack: SovereignEvidencePackV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")

        // Field order matches data class declaration order
        appendField(sb, "schemaVersion", pack.schemaVersion.toString())
        appendStringField(sb, "deploymentMode", pack.deploymentMode)
        appendStringListField(sb, "allowedModels", pack.allowedModels)
        appendStringListField(sb, "allowedProviders", pack.allowedProviders)
        appendStringMapField(sb, "providerZones", pack.providerZones)
        appendObjectField(sb, "artifactVerificationSettings", pack.artifactVerificationSettings)
        appendObjectListField(sb, "artifacts", pack.artifacts, serialize = ::serializeArtifact)

        if (pack.zeroEgress != null) {
            appendObjectField(
                sb = sb, key = "zeroEgress", value = pack.zeroEgress, last = false,
                serialize = { z -> serializeZeroEgress(z) },
            )
        } else {
            appendNullField(sb, "zeroEgress", last = false)
        }

        if (pack.auditChain != null) {
            appendObjectField(
                sb = sb, key = "auditChain", value = pack.auditChain, last = false,
                serialize = { a -> serializeAuditChain(a) },
            )
        } else {
            appendNullField(sb, "auditChain", last = false)
        }

        if (pack.supplyChain != null) {
            appendObjectField(
                sb = sb, key = "supplyChain", value = pack.supplyChain, last = false,
                serialize = { s -> serializeSupplyChain(s) },
            )
        } else {
            appendNullField(sb, "supplyChain", last = false)
        }

        if (pack.releaseBundle != null) {
            appendObjectField(
                sb = sb, key = "releaseBundle", value = pack.releaseBundle, last = false,
                serialize = { r -> serializeReleaseBundle(r) },
            )
        } else {
            appendNullField(sb, "releaseBundle", last = false)
        }

        if (pack.attestation != null) {
            appendObjectField(
                sb = sb, key = "attestation", value = pack.attestation, last = false,
                serialize = { a -> serializeAttestation(a) },
            )
        } else {
            appendNullField(sb, "attestation", last = false)
        }

        appendStringField(sb, "generatedAt", pack.generatedAt, last = true)

        sb.append("}")
        sb.appendLine()
        return sb.toString()
    }

    private fun serializeArtifact(a: ArtifactEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendStringField(sb, "registryEntryId", a.registryEntryId, indent = 2)
        appendStringField(sb, "manifestDigest", a.manifestDigest, indent = 2)
        appendStringField(sb, "modelName", a.modelName, indent = 2)
        appendStringField(sb, "verifiedAt", a.verifiedAt, indent = 2)
        appendField(sb, "artifactCount", a.artifactCount.toString(), indent = 2)
        appendField(sb, "totalSizeBytes", a.totalSizeBytes.toString(), indent = 2, last = true)
        sb.append(JSON_INDENT_CLOSE)
        return sb.toString()
    }

    private fun serializeZeroEgress(z: ZeroEgressEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendStringField(sb, "deploymentMode", z.deploymentMode, indent = 2)
        appendField(sb, "runtimeBuildSucceeded", z.runtimeBuildSucceeded.toString(), indent = 2)
        appendField(sb, "loopbackProviderInvocationSucceeded", z.loopbackProviderInvocationSucceeded.toString(), indent = 2)
        appendField(sb, "loopbackProviderInvocationCount", z.loopbackProviderInvocationCount.toString(), indent = 2)
        appendField(sb, "externalTcpProbeBlocked", z.externalTcpProbeBlocked.toString(), indent = 2)
        appendField(sb, "externalDnsProbeBlocked", z.externalDnsProbeBlocked.toString(), indent = 2, last = true)
        sb.append(JSON_INDENT_CLOSE)
        return sb.toString()
    }

    private fun serializeAuditChain(a: AuditChainEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendField(sb, "isValid", a.isValid.toString(), indent = 2)
        appendField(sb, "totalEvents", a.totalEvents.toString(), indent = 2, last = true)
        sb.append(JSON_INDENT_CLOSE)
        return sb.toString()
    }

    private fun serializeSupplyChain(s: SupplyChainEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendField(sb, "schemaVersion", s.schemaVersion.toString(), indent = 2)
        appendStringField(sb, "sbomFormat", s.sbomFormat, indent = 2)
        appendStringField(sb, "sbomSpecVersion", s.sbomSpecVersion, indent = 2)
        appendStringField(sb, "sbomFileName", s.sbomFileName, indent = 2)
        appendStringField(sb, "sbomSha256", s.sbomSha256, indent = 2)
        appendStringField(sb, "generatedBy", s.generatedBy, indent = 2, last = true)
        sb.append(JSON_INDENT_CLOSE)
        return sb.toString()
    }

    private fun serializeReleaseBundle(r: ReleaseBundleEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendField(sb, "schemaVersion", r.schemaVersion.toString(), indent = 2)
        appendStringField(sb, "buildTool", r.buildTool, indent = 2)
        appendStringField(sb, "javaVersion", r.javaVersion, indent = 2)
        appendStringField(sb, "gradleVersion", r.gradleVersion, indent = 2)
        appendObjectListField(sb, "artifacts", r.artifacts, indent = 2, serialize = ::serializeReleaseArtifact, last = true)
        sb.append(JSON_INDENT_CLOSE)
        return sb.toString()
    }

    private fun serializeReleaseArtifact(a: ReleaseArtifactEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendStringField(sb, "groupId", a.groupId, indent = 1)
        appendStringField(sb, "artifactId", a.artifactId, indent = 1)
        appendStringField(sb, "version", a.version, indent = 1)
        if (a.classifier != null) {
            appendStringField(sb, "classifier", a.classifier, indent = 1)
        } else {
            appendField(sb, "classifier", "null", indent = 1)
        }
        appendStringField(sb, "extension", a.extension, indent = 1)
        appendStringField(sb, "fileName", a.fileName, indent = 1)
        appendStringField(sb, "sha256", a.sha256, indent = 1)
        appendField(sb, "sizeBytes", a.sizeBytes.toString(), indent = 1, last = true)
        sb.append(JSON_INDENT_CLOSE)
        return sb.toString()
    }

    private fun serializeAttestation(a: AttestationEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendField(sb, "schemaVersion", a.schemaVersion.toString(), indent = 2)
        appendStringField(sb, "provider", a.provider, indent = 2)
        appendStringField(sb, "workflowName", a.workflowName, indent = 2)
        appendStringField(sb, "workflowRunId", a.workflowRunId, indent = 2)
        appendStringField(sb, "repository", a.repository, indent = 2)
        appendStringField(sb, "commitSha", a.commitSha, indent = 2)
        appendObjectListField(sb, "attestedSubjects", a.attestedSubjects, indent = 2, serialize = ::serializeAttestedSubject, last = true)
        sb.append(JSON_INDENT_CLOSE)
        return sb.toString()
    }

    private fun serializeAttestedSubject(s: AttestedSubjectV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendStringField(sb, "fileName", s.fileName, indent = 1)
        appendStringField(sb, "sha256", s.sha256, indent = 1)
        appendStringField(sb, "attestationType", s.attestationType, indent = 1, last = true)
        sb.append(JSON_INDENT_CLOSE)
        return sb.toString()
    }

    // ── Field appenders ──────────────────────────────────────────────────────

    private fun appendField(
        sb: StringBuilder,
        key: String,
        value: String,
        indent: Int = 1,
        last: Boolean = false,
    ) {
        sb.append("    ".repeat(indent))
            .append(escapedString(key))
            .append(": ")
            .append(value)
        if (!last) sb.append(",")
        sb.appendLine()
    }

    private fun appendStringField(
        sb: StringBuilder,
        key: String,
        value: String,
        indent: Int = 1,
        last: Boolean = false,
    ) {
        appendField(sb, key, escapedString(value), indent, last)
    }

    private fun appendStringListField(
        sb: StringBuilder,
        key: String,
        list: List<String>,
        indent: Int = 1,
        last: Boolean = false,
    ) {
        val indentStr = "    ".repeat(indent)
        sb.append(indentStr).append(escapedString(key)).append(": [")
        if (list.isEmpty()) {
            sb.append("]")
            if (!last) sb.append(",")
            sb.appendLine()
            return
        }
        sb.appendLine()
        for ((i, item) in list.withIndex()) {
            val isLast = i == list.lastIndex
            val innerIndent = "    ".repeat(indent + 1)
            sb.append(innerIndent).append(escapedString(item))
            if (!isLast) sb.append(",")
            sb.appendLine()
        }
        sb.append(indentStr).append("]")
        if (!last) sb.append(",")
        sb.appendLine()
    }

    private fun appendStringMapField(
        sb: StringBuilder,
        key: String,
        map: Map<String, String>,
        indent: Int = 1,
        last: Boolean = false,
    ) {
        val indentStr = "    ".repeat(indent)
        sb.append(indentStr).append(escapedString(key)).append(": {")
        if (map.isEmpty()) {
            sb.append("}")
            if (!last) sb.append(",")
            sb.appendLine()
            return
        }
        sb.appendLine()
        val entries = map.entries.sortedBy { it.key }
        for ((i, entry) in entries.withIndex()) {
            val isLast = i == entries.lastIndex
            val innerIndent = "    ".repeat(indent + 1)
            sb.append(innerIndent)
                .append(escapedString(entry.key))
                .append(": ")
                .append(escapedString(entry.value))
            if (!isLast) sb.append(",")
            sb.appendLine()
        }
        sb.append(indentStr).append("}")
        if (!last) sb.append(",")
        sb.appendLine()
    }

    private fun <T : Any> appendObjectField(
        sb: StringBuilder,
        key: String,
        value: T?,
        serialize: ((T) -> String)? = null,
        indent: Int = 1,
        last: Boolean = false,
    ) {
        val indentStr = "    ".repeat(indent)
        sb.append(indentStr).append(escapedString(key)).append(": ")
        if (value == null) {
            sb.append("null")
            if (!last) sb.append(",")
            sb.appendLine()
            return
        }
        if (serialize != null) {
            sb.appendLine().append(serialize(value))
            if (!last) sb.append(",")
            sb.appendLine()
        } else {
            // Generic object: serialize as JSON object with reflection-like approach
            // For the artifactVerificationSettings map, we need special handling
            @Suppress("UNCHECKED_CAST")
            val map = value as Map<String, Any?>
            if (map.isEmpty()) {
                sb.append("{ }")
                if (!last) sb.append(",")
                sb.appendLine()
                return
            }
            sb.appendLine("{")
            val entries = map.entries.sortedBy { it.key }
            for ((i, entry) in entries.withIndex()) {
                val isLast = i == entries.lastIndex
                val innerIndent = "    ".repeat(indent + 1)
                sb.append(innerIndent).append(escapedString(entry.key)).append(": ")
                appendAnyValue(sb, entry.value)
                if (!isLast) sb.append(",")
                sb.appendLine()
            }
            sb.append(indentStr).append("}")
            if (!last) sb.append(",")
            sb.appendLine()
        }
    }

    private fun appendNullField(
        sb: StringBuilder,
        key: String,
        indent: Int = 1,
        last: Boolean = false,
    ) {
        val indentStr = "    ".repeat(indent)
        sb.append(indentStr).append(escapedString(key)).append(": null")
        if (!last) sb.append(",")
        sb.appendLine()
    }

    private fun <T> appendObjectListField(
        sb: StringBuilder,
        key: String,
        list: List<T>,
        indent: Int = 1,
        serialize: (T) -> String,
        last: Boolean = false,
    ) {
        val indentStr = "    ".repeat(indent)
        sb.append(indentStr).append(escapedString(key)).append(": [")
        if (list.isEmpty()) {
            sb.append("]")
            if (!last) sb.append(",")
            sb.appendLine()
            return
        }
        sb.appendLine()
        for ((i, item) in list.withIndex()) {
            val isLast = i == list.lastIndex
            val innerIndent = "    ".repeat(indent + 1)
            sb.append(innerIndent).append(serialize(item))
            if (!isLast) sb.append(",")
            sb.appendLine()
        }
        sb.append(indentStr).append("]")
        if (!last) sb.append(",")
        sb.appendLine()
    }

    private fun appendAnyValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> sb.append(escapedString(value))
            is Boolean -> sb.append(value.toString())
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append("{ ")
                @Suppress("UNCHECKED_CAST")
                val m = value as Map<String, Any?>
                val entries = m.entries.sortedBy { it.key }
                for ((i, entry) in entries.withIndex()) {
                    sb.append(escapedString(entry.key)).append(": ")
                    appendAnyValue(sb, entry.value)
                    if (i < entries.lastIndex) sb.append(", ")
                }
                sb.append(" }")
            }
            is List<*> -> {
                sb.append("[")
                for ((i, item) in value.withIndex()) {
                    appendAnyValue(sb, item)
                    if (i < value.size - 1) sb.append(", ")
                }
                sb.append("]")
            }
            else -> sb.append(escapedString(value.toString()))
        }
    }

    // ── JSON string escaping ─────────────────────────────────────────────────

    /**
     * Escapes a string value for inclusion in JSON output.
     *
     * Handles:
     * - Quotes (")
     * - Backslashes (\\)
     * - Newline (\\n)
     * - Carriage return (\\r)
     * - Tab (\\t)
     * - All Unicode control characters < 0x20 as \\uXXXX
     */
    fun escapedString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/** @see SovereignEvidencePackWriter */
private const val JSON_INDENT_CLOSE = "            }"
