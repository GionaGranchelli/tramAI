package dev.tramai.sovereign.evidence

import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads a [ReleaseBundleEvidenceV1] from a `release-artifacts-v1.json` file.
 *
 * This is a build-time loader that maps the verified artifact manifest
 * (produced by Gradle's `prepareSovereignReleaseArtifacts` and verified by
 * PR #37's verifier tasks) into the evidence pack DTO.
 *
 * The loader validates manifest *shape* — it does NOT recompute JAR file hashes.
 * Hash/file verification is owned by the Gradle verifier tasks.
 *
 * Uses manual JSON parsing (matching the project's `SovereignEvidencePackWriter`
 * approach) to avoid pulling in a heavy JSON dependency at runtime.
 */
object ReleaseBundleEvidenceLoader {

    private val digestRegex = Regex("^sha256:[a-fA-F0-9]{64}$")
    private val safeFileNameRegex = Regex("^[^/\\\\]+$")

    /**
     * Reads and validates [path] as `release-artifacts-v1.json`,
     * returning a [ReleaseBundleEvidenceV1].
     *
     * @throws IllegalStateException with a deterministic error code on any validation failure.
     */
    @JvmStatic
    fun load(path: Path): ReleaseBundleEvidenceV1 {
        if (!Files.exists(path)) {
            throw IllegalStateException("release-bundle-evidence-missing: $path")
        }

        val text: String = try {
            Files.readString(path)
        } catch (e: Exception) {
            throw IllegalStateException("release-bundle-evidence-invalid-json", e)
        }

        val parser = JsonParser(text)
        val root = try {
            parser.parseDocument()
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("release-bundle-evidence-invalid-json", e)
        }

        val obj = root as? Map<*, *>
            ?: throw IllegalStateException("release-bundle-evidence-invalid-json: root is not an object")

        val schemaVersion = (obj["schemaVersion"] as? Number)?.toInt()
            ?: throw IllegalStateException("release-bundle-evidence-unsupported-schema-version")
        if (schemaVersion != 1) {
            throw IllegalStateException("release-bundle-evidence-unsupported-schema-version: $schemaVersion")
        }

        val buildTool = stringField(obj, "buildTool")
        val javaVersion = stringField(obj, "javaVersion")
        val gradleVersion = stringField(obj, "gradleVersion")

        val rawArtifacts = obj["artifacts"]
            ?: throw IllegalStateException("release-bundle-evidence-missing-artifacts")
        val artifactList = rawArtifacts as? List<*>
            ?: throw IllegalStateException("release-bundle-evidence-invalid-json: artifacts is not an array")

        if (artifactList.isEmpty()) {
            throw IllegalStateException("release-bundle-evidence-empty-artifacts")
        }

        val seenFileNames = mutableSetOf<String>()
        val seenCoordinates = mutableSetOf<String>()
        val resultArtifacts = mutableListOf<ReleaseArtifactEvidenceV1>()

        for ((i, rawEntry) in artifactList.withIndex()) {
            val entry = rawEntry as? Map<*, *>
                ?: throw IllegalStateException("release-bundle-evidence-invalid-artifact-entry (index $i)")

            val groupId = stringField(entry, "groupId", i)
            val artifactId = stringField(entry, "artifactId", i)
            val version = stringField(entry, "version", i)
            val fileName = stringField(entry, "fileName", i)
            val sha256 = stringField(entry, "sha256", i)
            val extension = stringField(entry, "extension", i)
            val sizeBytes = numericField(entry, "sizeBytes", i)

            val rawClassifier = entry["classifier"]
            val classifier = when (rawClassifier) {
                null -> null
                is String -> rawClassifier
                else -> throw IllegalStateException(
                    "release-bundle-evidence-invalid-artifact-entry (index $i): classifier must be String or null"
                )
            }

            // Unsafe file name
            if (fileName.isBlank() || !safeFileNameRegex.matches(fileName)) {
                throw IllegalStateException("release-bundle-evidence-unsafe-file-name: $fileName")
            }

            // Digest format
            if (!digestRegex.matches(sha256)) {
                throw IllegalStateException("release-bundle-evidence-invalid-digest-format: $sha256")
            }

            // Size must be positive
            val size = sizeBytes.toLong()
            if (size <= 0) {
                throw IllegalStateException("release-bundle-evidence-invalid-size: $size")
            }

            // Only JAR extension supported
            if (extension != "jar") {
                throw IllegalStateException("release-bundle-evidence-unsupported-extension: $extension")
            }

            // Duplicate fileName
            if (!seenFileNames.add(fileName)) {
                throw IllegalStateException("release-bundle-evidence-duplicate-file-name: $fileName")
            }

            // Duplicate coordinate
            val coordinate = "$groupId:$artifactId:$version:${classifier ?: ""}:$extension"
            if (!seenCoordinates.add(coordinate)) {
                throw IllegalStateException("release-bundle-evidence-duplicate-coordinate: $coordinate")
            }

            resultArtifacts.add(
                ReleaseArtifactEvidenceV1(
                    groupId = groupId,
                    artifactId = artifactId,
                    version = version,
                    classifier = classifier,
                    extension = extension,
                    fileName = fileName,
                    sha256 = sha256,
                    sizeBytes = size,
                )
            )
        }

        return ReleaseBundleEvidenceV1(
            schemaVersion = schemaVersion,
            buildTool = buildTool,
            javaVersion = javaVersion,
            gradleVersion = gradleVersion,
            artifacts = resultArtifacts,
        )
    }

    private fun stringField(obj: Map<*, *>, key: String, index: Int? = null): String {
        val prefix = if (index != null) " (index $index)" else ""
        return (obj[key] as? String)
            ?: throw IllegalStateException("release-bundle-evidence-invalid-artifact-entry$prefix: missing or non-String $key")
    }

    private fun numericField(obj: Map<*, *>, key: String, index: Int? = null): Number {
        val prefix = if (index != null) " (index $index)" else ""
        return (obj[key] as? Number)
            ?: throw IllegalStateException("release-bundle-evidence-invalid-artifact-entry$prefix: missing or non-Numeric $key")
    }

    // ── Minimal recursive-descent JSON parser ──────────────────────────────
    // Handles the subset of JSON used by release-artifacts-v1.json:
    // objects, arrays, strings, numbers, booleans, and null.

    private class JsonParser(private val text: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) return null
            return when (val ch = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> parseNumber()
                else -> throw IllegalStateException("release-bundle-evidence-invalid-json: unexpected '${ch}' at position $pos")
            }
        }

        /**
         * Parses a complete JSON document: one value followed by optional
         * trailing whitespace and then end-of-file. Rejects trailing content
         * after the first JSON value.
         */
        fun parseDocument(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (!isAtEnd()) {
                throw IllegalStateException("release-bundle-evidence-invalid-json: trailing content at position $pos")
            }
            return value
        }

        private fun isAtEnd(): Boolean = pos >= text.length

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                val ch = peek()
                if (ch == '}') { pos++; return map }
                if (ch == ',') { pos++; continue }
                throw IllegalStateException("release-bundle-evidence-invalid-json: expected ',' or '}' at position $pos")
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return list }
            while (true) {
                skipWhitespace()
                list.add(parseValue())
                skipWhitespace()
                val ch = peek()
                if (ch == ']') { pos++; return list }
                if (ch == ',') { pos++; continue }
                throw IllegalStateException("release-bundle-evidence-invalid-json: expected ',' or ']' at position $pos")
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos]
                if (ch == '"') { pos++; return sb.toString() }
                if (ch == '\\') {
                    pos++
                    val esc = text.getOrElse(pos) {
                        throw IllegalStateException("release-bundle-evidence-invalid-json: unexpected end in escape")
                    }
                    sb.append(
                        when (esc) {
                            '"' -> '"'; '\\' -> '\\'; '/' -> '/'
                            'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                            'u' -> {
                                val hex = text.substring(pos + 1, pos + 5)
                                pos += 4
                                hex.toInt(16).toChar()
                            }
                            else -> throw IllegalStateException("release-bundle-evidence-invalid-json: unknown escape '\\$esc'")
                        }
                    )
                    pos++
                } else {
                    sb.append(ch)
                    pos++
                }
            }
            throw IllegalStateException("release-bundle-evidence-invalid-json: unclosed string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (text[pos] == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            if (pos < text.length && text[pos] == '.') {
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val numStr = text.substring(start, pos)
            return if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E')) {
                numStr.toDouble()
            } else {
                numStr.toLong()
            }
        }

        private fun parseBoolean(): Boolean {
            return if (text.startsWith("true", pos)) {
                pos += 4; true
            } else if (text.startsWith("false", pos)) {
                pos += 5; false
            } else {
                throw IllegalStateException("release-bundle-evidence-invalid-json at position $pos")
            }
        }

        private fun parseNull(): Nothing? {
            if (text.startsWith("null", pos)) {
                pos += 4; return null
            }
            throw IllegalStateException("release-bundle-evidence-invalid-json at position $pos")
        }

        private fun expect(ch: Char) {
            skipWhitespace()
            if (pos >= text.length || text[pos] != ch) {
                throw IllegalStateException(
                    "release-bundle-evidence-invalid-json: expected '$ch' at position $pos"
                )
            }
            pos++
        }

        private fun peek(): Char {
            skipWhitespace()
            return text.getOrElse(pos) { '?' }
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
