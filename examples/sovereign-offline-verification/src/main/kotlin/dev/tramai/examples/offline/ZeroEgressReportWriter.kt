package dev.tramai.examples.offline

import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a [ZeroEgressVerificationReportV1] as a JSON file with
 * deterministic field ordering (matching the data class declaration order).
 */
object ZeroEgressReportWriter {

    /**
     * Serializes the [report] as JSON and writes it to [path].
     * Creates parent directories if they do not exist.
     *
     * @throws Exception on any I/O or serialization failure.
     */
    fun write(report: ZeroEgressVerificationReportV1, path: Path) {
        path.parent?.let { Files.createDirectories(it) }
        val json = serialize(report)
        Files.writeString(path, json)
    }

    private fun serialize(report: ZeroEgressVerificationReportV1): String {
        val sb = StringBuilder()
        sb.appendLine("{")

        appendField(sb, "schemaVersion", report.schemaVersion.toString())
        appendStringField(sb, "deploymentMode", report.deploymentMode)
        appendField(sb, "runtimeBuildSucceeded", report.runtimeBuildSucceeded.toString())
        appendField(sb, "loopbackProviderInvocationSucceeded", report.loopbackProviderInvocationSucceeded.toString())
        appendField(sb, "loopbackProviderInvocationCount", report.loopbackProviderInvocationCount.toString())
        appendField(sb, "externalTcpProbeBlocked", report.externalTcpProbeBlocked.toString())
        appendField(sb, "externalDnsProbeBlocked", report.externalDnsProbeBlocked.toString())
        appendObjectField(sb, "configuredProviderZones", report.configuredProviderZones)
        appendField(sb, "artifactVerificationReceiptCount", report.artifactVerificationReceiptCount.toString())
        appendField(sb, "auditChainValid", report.auditChainValid.toString(), last = true)

        sb.append("}")
        sb.appendLine()
        return sb.toString()
    }

    private fun appendField(
        sb: StringBuilder,
        key: String,
        value: String,
        last: Boolean = false,
    ) {
        val indent = "    "
        sb.append(indent).append(escapedString(key)).append(": ").append(value)
        if (!last) sb.append(",")
        sb.appendLine()
    }

    private fun appendStringField(
        sb: StringBuilder,
        key: String,
        value: String,
        last: Boolean = false,
    ) {
        val indent = "    "
        sb.append(indent).append(escapedString(key)).append(": ").append(escapedString(value))
        if (!last) sb.append(",")
        sb.appendLine()
    }

    private fun appendObjectField(
        sb: StringBuilder,
        key: String,
        map: Map<String, String>,
        last: Boolean = false,
    ) {
        val indent = "    "
        sb.append(indent).append(escapedString(key)).append(": {")
        if (map.isEmpty()) {
            sb.append("}")
            if (!last) sb.append(",")
            sb.appendLine()
            return
        }
        sb.appendLine()
        val entries = map.entries.toList()
        for ((i, entry) in entries.withIndex()) {
            val isLast = i == entries.lastIndex
            val innerIndent = "        "
            sb.append(innerIndent)
                .append(escapedString(entry.key))
                .append(": ")
                .append(escapedString(entry.value))
            if (!isLast) sb.append(",")
            sb.appendLine()
        }
        sb.append(indent).append("}")
        if (!last) sb.append(",")
        sb.appendLine()
    }

    /** Escapes a string for JSON with full Unicode control-character handling. */
    private fun escapedString(value: String): String {
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
