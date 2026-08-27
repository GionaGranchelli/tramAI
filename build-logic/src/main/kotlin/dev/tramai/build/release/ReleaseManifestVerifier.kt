package dev.tramai.build.release

import groovy.json.JsonSlurper
import java.io.File

/**
 * Pure verifier for the release-artifacts-v1.json manifest (9.2b extraction).
 *
 * Every error is communicated through a [GradleException]-compatible failure
 * whose message starts with one of the required error codes from the release
 * manifest spec. The verifier is Gradle-free so it can be unit tested without
 * TestKit.
 */
object ReleaseManifestVerifier {

    fun verify(manifestDir: File, artifactsDir: File) {
        val manifestFile = manifestDir.resolve("release-artifacts-v1.json")

        // 1. Manifest file must exist
        require(manifestFile.exists()) {
            "sovereign-release-manifest-missing: ${manifestFile.absolutePath}"
        }

        // 2. Artifacts directory must exist
        require(artifactsDir.isDirectory()) {
            "sovereign-release-artifacts-dir-missing: ${artifactsDir.absolutePath}"
        }

        // 3. Parse JSON (fail closed on malformed content)
        val manifest: Map<String, Any>
        try {
            @Suppress("UNCHECKED_CAST")
            manifest = JsonSlurper().parse(manifestFile) as Map<String, Any>
        } catch (e: Exception) {
            throw IllegalStateException("sovereign-release-manifest-invalid-json", e)
        }

        // 4. Schema version must be supported (currently 1)
        val schemaVersion = (manifest["schemaVersion"] as? Number)?.toInt()
            ?: throw IllegalStateException("sovereign-release-manifest-unsupported-schema-version")
        require(schemaVersion == 1) {
            "sovereign-release-manifest-unsupported-schema-version: $schemaVersion"
        }

        // 5. artifacts array must be present
        val rawArtifacts = manifest["artifacts"]
            ?: throw IllegalStateException("sovereign-release-manifest-missing-artifacts")

        // 6. artifacts array must not be empty
        @Suppress("UNCHECKED_CAST")
        val artifactList = rawArtifacts as? List<Map<String, Any>>
            ?: throw IllegalStateException("sovereign-release-manifest-invalid-json: artifacts is not an array")
        require(artifactList.isNotEmpty()) {
            "sovereign-release-manifest-empty-artifacts"
        }

        val seenFileNames = mutableSetOf<String>()
        val seenCoordinates = mutableSetOf<String>()
        val manifestFileNames = mutableSetOf<String>()

        for ((i, entry) in artifactList.withIndex()) {
            // 7. Each entry must be a map with required fields
            val fileName = entry["fileName"]?.let { it as? String }
                ?: throw IllegalStateException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-String fileName")
            val groupId = entry["groupId"]?.let { it as? String }
                ?: throw IllegalStateException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-String groupId")
            val artifactId = entry["artifactId"]?.let { it as? String }
                ?: throw IllegalStateException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-String artifactId")
            val version = entry["version"]?.let { it as? String }
                ?: throw IllegalStateException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-String version")
            val extension = entry["extension"]?.let { it as? String }
                ?: throw IllegalStateException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-String extension")
            val sha256 = entry["sha256"]?.let { it as? String }
                ?: throw IllegalStateException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-String sha256")
            val sizeBytes = entry["sizeBytes"]?.let { it as? Number }
                ?: throw IllegalStateException("sovereign-release-manifest-invalid-artifact-entry (index $i): missing or non-Numeric sizeBytes")

            val rawClassifier = entry["classifier"]
            val classifier = when (rawClassifier) {
                null -> null
                is String -> rawClassifier
                else -> throw IllegalStateException(
                    "sovereign-release-manifest-invalid-artifact-entry (index $i): classifier must be String or null, got ${rawClassifier::class.simpleName}"
                )
            }

            // 8. Unsafe file name — reject blank, path traversal, and directory separators
            require(
                fileName.isNotBlank() &&
                    !fileName.contains("/") &&
                    !fileName.contains("\\") &&
                    !fileName.contains("..")
            ) {
                "sovereign-release-manifest-unsafe-file-name: $fileName"
            }

            // 9. Digest must be sha256: followed by 64 lowercase hex chars
            require(sha256.startsWith("sha256:")) {
                "sovereign-release-manifest-invalid-digest-format: $sha256 (missing 'sha256:' prefix)"
            }
            val hexPart = sha256.removePrefix("sha256:")
            val digestRegex = Regex("^[a-fA-F0-9]{64}$")
            require(digestRegex.matches(hexPart)) {
                "sovereign-release-manifest-invalid-digest-format: $sha256 (expected 64 hex chars, got ${hexPart.length})"
            }
            // Normalise to lowercase for comparison after validation
            val normalisedHex = hexPart.lowercase()

            // 10. Size must be a positive integer
            val size = sizeBytes.toLong()
            require(size > 0) {
                "sovereign-release-manifest-invalid-size: $size (must be positive)"
            }

            // 11. Only JAR extensions are supported in the release manifest
            require(extension == "jar") {
                "sovereign-release-manifest-unsupported-extension: $extension (only 'jar' is supported)"
            }

            // 12. Duplicate fileName rejection
            require(seenFileNames.add(fileName)) {
                "sovereign-release-manifest-duplicate-file-name: $fileName"
            }

            // 13. Duplicate Maven coordinate rejection
            val coordinate = "$groupId:$artifactId:$version:${classifier ?: ""}:$extension"
            require(seenCoordinates.add(coordinate)) {
                "sovereign-release-manifest-duplicate-coordinate: $coordinate"
            }

            manifestFileNames.add(fileName)

            // 14. File must exist on disk
            val jarFile = artifactsDir.resolve(fileName)
            require(jarFile.exists()) {
                "sovereign-release-artifact-missing: $fileName"
            }

            // 15. File size must match
            val actualSize = jarFile.length()
            require(actualSize == size) {
                "sovereign-release-artifact-size-mismatch: $fileName (expected $size bytes, actual $actualSize)"
            }

            // 16. SHA-256 digest must match
            val computedHex = dev.tramai.build.sovereign.evidence.Hashing.sha256Hex(jarFile)
            require(computedHex == normalisedHex) {
                "sovereign-release-artifact-digest-mismatch: $fileName"
            }
        }

        // 17. Reject unlisted .jar files in the artifacts directory
        val actualJars = artifactsDir.listFiles { f -> f.name.endsWith(".jar") }?.toList() ?: emptyList()
        for (jarFile in actualJars) {
            require(jarFile.name in manifestFileNames) {
                "sovereign-release-artifact-unlisted: ${jarFile.name}"
            }
        }
    }
}
