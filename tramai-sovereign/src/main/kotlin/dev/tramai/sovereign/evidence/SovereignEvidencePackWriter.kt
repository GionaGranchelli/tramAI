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
        appendField(sb, "schemaVersion", pack.schemaVersion.toString(), 1, 13)
        appendStringField(sb, "deploymentMode", pack.deploymentMode, 2, 13)
        appendStringListField(sb, "allowedModels", pack.allowedModels, 3, 13)
        appendStringListField(sb, "allowedProviders", pack.allowedProviders, 4, 13)
        appendStringMapField(sb, "providerZones", pack.providerZones, 5, 13)
        appendObjectField(sb, "artifactVerificationSettings", pack.artifactVerificationSettings, 6, 13)
        appendObjectListField(sb, "artifacts", pack.artifacts, 7, 13, serialize = ::serializeArtifact)

        if (pack.zeroEgress != null) {
            appendObjectField(
                sb = sb, key = "zeroEgress", value = pack.zeroEgress,
                index = 8, total = 13, last = false,
                serialize = { z -> serializeZeroEgress(z) },
            )
        } else {
            appendNullField(sb, "zeroEgress", 8, 13, last = false)
        }

        if (pack.auditChain != null) {
            appendObjectField(
                sb = sb, key = "auditChain", value = pack.auditChain,
                index = 9, total = 13, last = false,
                serialize = { a -> serializeAuditChain(a) },
            )
        } else {
            appendNullField(sb, "auditChain", 9, 13, last = false)
        }

        if (pack.supplyChain != null) {
            appendObjectField(
                sb = sb, key = "supplyChain", value = pack.supplyChain,
                index = 10, total = 13, last = false,
                serialize = { s -> serializeSupplyChain(s) },
            )
        } else {
            appendNullField(sb, "supplyChain", 10, 13, last = false)
        }

        if (pack.releaseBundle != null) {
            appendObjectField(
                sb = sb, key = "releaseBundle", value = pack.releaseBundle,
                index = 11, total = 13, last = false,
                serialize = { r -> serializeReleaseBundle(r) },
            )
        } else {
            appendNullField(sb, "releaseBundle", 11, 13, last = false)
        }

        if (pack.attestation != null) {
            appendObjectField(
                sb = sb, key = "attestation", value = pack.attestation,
                index = 12, total = 13, last = false,
                serialize = { a -> serializeAttestation(a) },
            )
        } else {
            appendNullField(sb, "attestation", 12, 13, last = false)
        }

        appendStringField(sb, "generatedAt", pack.generatedAt, 13, 13, last = true)

        sb.append("}")
        sb.appendLine()
        return sb.toString()
    }

    private fun serializeArtifact(a: ArtifactEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendStringField(sb, "registryEntryId", a.registryEntryId, 1, 6, indent = 2)
        appendStringField(sb, "manifestDigest", a.manifestDigest, 2, 6, indent = 2)
        appendStringField(sb, "modelName", a.modelName, 3, 6, indent = 2)
        appendStringField(sb, "verifiedAt", a.verifiedAt, 4, 6, indent = 2)
        appendField(sb, "artifactCount", a.artifactCount.toString(), 5, 6, indent = 2)
        appendField(sb, "totalSizeBytes", a.totalSizeBytes.toString(), 6, 6, indent = 2, last = true)
        sb.append("            }")
        return sb.toString()
    }

    private fun serializeZeroEgress(z: ZeroEgressEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendStringField(sb, "deploymentMode", z.deploymentMode, 1, 6, indent = 2)
        appendField(sb, "runtimeBuildSucceeded", z.runtimeBuildSucceeded.toString(), 2, 6, indent = 2)
        appendField(sb, "loopbackProviderInvocationSucceeded", z.loopbackProviderInvocationSucceeded.toString(), 3, 6, indent = 2)
        appendField(sb, "loopbackProviderInvocationCount", z.loopbackProviderInvocationCount.toString(), 4, 6, indent = 2)
        appendField(sb, "externalTcpProbeBlocked", z.externalTcpProbeBlocked.toString(), 5, 6, indent = 2)
        appendField(sb, "externalDnsProbeBlocked", z.externalDnsProbeBlocked.toString(), 6, 6, indent = 2, last = true)
        sb.append("            }")
        return sb.toString()
    }

    private fun serializeAuditChain(a: AuditChainEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendField(sb, "isValid", a.isValid.toString(), 1, 2, indent = 2)
        appendField(sb, "totalEvents", a.totalEvents.toString(), 2, 2, indent = 2, last = true)
        sb.append("            }")
        return sb.toString()
    }

    private fun serializeSupplyChain(s: SupplyChainEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendField(sb, "schemaVersion", s.schemaVersion.toString(), 1, 6, indent = 2)
        appendStringField(sb, "sbomFormat", s.sbomFormat, 2, 6, indent = 2)
        appendStringField(sb, "sbomSpecVersion", s.sbomSpecVersion, 3, 6, indent = 2)
        appendStringField(sb, "sbomFileName", s.sbomFileName, 4, 6, indent = 2)
        appendStringField(sb, "sbomSha256", s.sbomSha256, 5, 6, indent = 2)
        appendStringField(sb, "generatedBy", s.generatedBy, 6, 6, indent = 2, last = true)
        sb.append("            }")
        return sb.toString()
    }

    private fun serializeReleaseBundle(r: ReleaseBundleEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendField(sb, "schemaVersion", r.schemaVersion.toString(), 1, 5, indent = 2)
        appendStringField(sb, "buildTool", r.buildTool, 2, 5, indent = 2)
        appendStringField(sb, "javaVersion", r.javaVersion, 3, 5, indent = 2)
        appendStringField(sb, "gradleVersion", r.gradleVersion, 4, 5, indent = 2)
        appendObjectListField(sb, "artifacts", r.artifacts, 5, 5, indent = 2, serialize = ::serializeReleaseArtifact, last = true)
        sb.append("            }")
        return sb.toString()
    }

    private fun serializeReleaseArtifact(a: ReleaseArtifactEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendStringField(sb, "groupId", a.groupId, 1, 8, indent = 1)
        appendStringField(sb, "artifactId", a.artifactId, 2, 8, indent = 1)
        appendStringField(sb, "version", a.version, 3, 8, indent = 1)
        if (a.classifier != null) {
            appendStringField(sb, "classifier", a.classifier, 4, 8, indent = 1)
        } else {
            appendField(sb, "classifier", "null", 4, 8, indent = 1)
        }
        appendStringField(sb, "extension", a.extension, 5, 8, indent = 1)
        appendStringField(sb, "fileName", a.fileName, 6, 8, indent = 1)
        appendStringField(sb, "sha256", a.sha256, 7, 8, indent = 1)
        appendField(sb, "sizeBytes", a.sizeBytes.toString(), 8, 8, indent = 1, last = true)
        sb.append("            }")
        return sb.toString()
    }

    private fun serializeAttestation(a: AttestationEvidenceV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendField(sb, "schemaVersion", a.schemaVersion.toString(), 1, 7, indent = 2)
        appendStringField(sb, "provider", a.provider, 2, 7, indent = 2)
        appendStringField(sb, "workflowName", a.workflowName, 3, 7, indent = 2)
        appendStringField(sb, "workflowRunId", a.workflowRunId, 4, 7, indent = 2)
        appendStringField(sb, "repository", a.repository, 5, 7, indent = 2)
        appendStringField(sb, "commitSha", a.commitSha, 6, 7, indent = 2)
        appendObjectListField(sb, "attestedSubjects", a.attestedSubjects, 7, 7, indent = 2, serialize = ::serializeAttestedSubject, last = true)
        sb.append("            }")
        return sb.toString()
    }

    private fun serializeAttestedSubject(s: AttestedSubjectV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        appendStringField(sb, "fileName", s.fileName, 1, 3, indent = 1)
        appendStringField(sb, "sha256", s.sha256, 2, 3, indent = 1)
        appendStringField(sb, "attestationType", s.attestationType, 3, 3, indent = 1, last = true)
        sb.append("            }")
        return sb.toString()
    }

    // ── Field appenders ──────────────────────────────────────────────────────

    private fun appendField(
        sb: StringBuilder,
        key: String,
        value: String,
        index: Int,
        total: Int,
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
        index: Int,
        total: Int,
        indent: Int = 1,
        last: Boolean = false,
    ) {
        appendField(sb, key, escapedString(value), index, total, indent, last)
    }

    private fun appendStringListField(
        sb: StringBuilder,
        key: String,
        list: List<String>,
        index: Int,
        total: Int,
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
        index: Int,
        total: Int,
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
        index: Int,
        total: Int,
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
        index: Int,
        total: Int,
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
        index: Int,
        total: Int,
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

    /** Shorthand for [escapedString] used internally. */
    private fun esc(value: String): String = escapedString(value)
}
