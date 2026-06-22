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
        check(Files.exists(path)) {
            "release-bundle-evidence-missing: $path"
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

        val obj = checkNotNull(root as? Map<*, *>) {
            "release-bundle-evidence-invalid-json: root is not an object"
        }

        val schemaVersion = checkNotNull((obj["schemaVersion"] as? Number)?.toInt()) {
            "release-bundle-evidence-unsupported-schema-version"
        }
        check(schemaVersion == 1) {
            "release-bundle-evidence-unsupported-schema-version: $schemaVersion"
        }

        val buildTool = stringField(obj, "buildTool")
        val javaVersion = stringField(obj, "javaVersion")
        val gradleVersion = stringField(obj, "gradleVersion")

        val rawArtifacts = checkNotNull(obj["artifacts"]) {
            "release-bundle-evidence-missing-artifacts"
        }
        val artifactList = checkNotNull(rawArtifacts as? List<*>) {
            "release-bundle-evidence-invalid-json: artifacts is not an array"
        }

        check(artifactList.isNotEmpty()) {
            "release-bundle-evidence-empty-artifacts"
        }

        val resultArtifacts = parseArtifacts(artifactList)

        return ReleaseBundleEvidenceV1(
            schemaVersion = schemaVersion,
            buildTool = buildTool,
            javaVersion = javaVersion,
            gradleVersion = gradleVersion,
            artifacts = resultArtifacts,
        )
    }

    private fun parseArtifacts(artifactList: List<*>): List<ReleaseArtifactEvidenceV1> {
        val seenFileNames = mutableSetOf<String>()
        val seenCoordinates = mutableSetOf<String>()
        return artifactList.mapIndexed { index, rawEntry ->
            val artifact = parseArtifact(index, rawEntry)
            validateUniqueArtifact(artifact, seenFileNames, seenCoordinates)
            artifact
        }
    }

    private fun parseArtifact(index: Int, rawEntry: Any?): ReleaseArtifactEvidenceV1 {
        val entry = checkNotNull(rawEntry as? Map<*, *>) {
            "release-bundle-evidence-invalid-artifact-entry (index $index)"
        }
        val groupId = stringField(entry, "groupId", index)
        val artifactId = stringField(entry, "artifactId", index)
        val version = stringField(entry, "version", index)
        val fileName = stringField(entry, "fileName", index)
        val sha256 = stringField(entry, "sha256", index)
        val extension = stringField(entry, "extension", index)
        val size = numericField(entry, "sizeBytes", index).toLong()
        val classifier = classifierField(entry, index)

        validateArtifactFields(fileName, sha256, size, extension)
        return ReleaseArtifactEvidenceV1(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            classifier = classifier,
            extension = extension,
            fileName = fileName,
            sha256 = sha256,
            sizeBytes = size,
        )
    }

    private fun classifierField(entry: Map<*, *>, index: Int): String? =
        when (val rawClassifier = entry["classifier"]) {
            null -> null
            is String -> rawClassifier
            else -> error("release-bundle-evidence-invalid-artifact-entry (index $index): classifier must be String or null")
        }

    private fun validateArtifactFields(
        fileName: String,
        sha256: String,
        size: Long,
        extension: String,
    ) {
        check(fileName.isNotBlank() && safeFileNameRegex.matches(fileName)) {
            "release-bundle-evidence-unsafe-file-name: $fileName"
        }
        check(digestRegex.matches(sha256)) {
            "release-bundle-evidence-invalid-digest-format: $sha256"
        }
        check(size > 0) {
            "release-bundle-evidence-invalid-size: $size"
        }
        check(extension == "jar") {
            "release-bundle-evidence-unsupported-extension: $extension"
        }
    }

    private fun validateUniqueArtifact(
        artifact: ReleaseArtifactEvidenceV1,
        seenFileNames: MutableSet<String>,
        seenCoordinates: MutableSet<String>,
    ) {
        check(seenFileNames.add(artifact.fileName)) {
            "release-bundle-evidence-duplicate-file-name: ${artifact.fileName}"
        }
        val coordinate = "${artifact.groupId}:${artifact.artifactId}:${artifact.version}:${artifact.classifier ?: ""}:${artifact.extension}"
        check(seenCoordinates.add(coordinate)) {
            "release-bundle-evidence-duplicate-coordinate: $coordinate"
        }
    }

    private fun stringField(obj: Map<*, *>, key: String, index: Int? = null): String {
        val prefix = if (index != null) " (index $index)" else ""
        return checkNotNull(obj[key] as? String) {
            "release-bundle-evidence-invalid-artifact-entry$prefix: missing or non-String $key"
        }
    }

    private fun numericField(obj: Map<*, *>, key: String, index: Int? = null): Number {
        val prefix = if (index != null) " (index $index)" else ""
        return checkNotNull(obj[key] as? Number) {
            "release-bundle-evidence-invalid-artifact-entry$prefix: missing or non-Numeric $key"
        }
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
                else -> error("release-bundle-evidence-invalid-json: unexpected '${ch}' at position $pos")
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
            check(isAtEnd()) {
                "release-bundle-evidence-invalid-json: trailing content at position $pos"
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
                error("release-bundle-evidence-invalid-json: expected ',' or '}' at position $pos")
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
                error("release-bundle-evidence-invalid-json: expected ',' or ']' at position $pos")
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos]
                if (ch == '"') { pos++; return sb.toString() }
                if (ch == '\\') {
                    sb.append(parseEscapedCharacter())
                } else {
                    sb.append(ch)
                    pos++
                }
            }
            error("release-bundle-evidence-invalid-json: unclosed string")
        }

        private fun parseEscapedCharacter(): Char {
            pos++
            return when (val esc = text.getOrElse(pos) {
                error("release-bundle-evidence-invalid-json: unexpected end in escape")
            }) {
                '"' -> consumeEscaped('"')
                '\\' -> consumeEscaped('\\')
                '/' -> consumeEscaped('/')
                'n' -> consumeEscaped('\n')
                'r' -> consumeEscaped('\r')
                't' -> consumeEscaped('\t')
                'u' -> parseUnicodeEscape()
                else -> error("release-bundle-evidence-invalid-json: unknown escape '\\$esc'")
            }
        }

        private fun consumeEscaped(character: Char): Char {
            pos++
            return character
        }

        private fun parseUnicodeEscape(): Char {
            val hex = text.substring(pos + 1, pos + 5)
            pos += 5
            return hex.toInt(16).toChar()
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
                error("release-bundle-evidence-invalid-json at position $pos")
            }
        }

        private fun parseNull(): Nothing? {
            if (text.startsWith("null", pos)) {
                pos += 4; return null
            }
            error("release-bundle-evidence-invalid-json at position $pos")
        }

        private fun expect(ch: Char) {
            skipWhitespace()
            check(pos < text.length && text[pos] == ch) {
                "release-bundle-evidence-invalid-json: expected '$ch' at position $pos"
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
