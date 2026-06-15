package dev.tramai.sovereign.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [ReleaseBundleEvidenceLoader].
 */
class ReleaseBundleEvidenceLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    private val validManifest = """
        {
          "schemaVersion": 1,
          "buildTool": "Gradle",
          "javaVersion": "25.0.1",
          "gradleVersion": "8.10",
          "artifacts": [
            {
              "groupId": "dev.tramai",
              "artifactId": "tramai-core",
              "version": "0.3.1",
              "classifier": null,
              "extension": "jar",
              "fileName": "tramai-core-0.3.1.jar",
              "sha256": "sha256:abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
              "sizeBytes": 289479
            },
            {
              "groupId": "dev.tramai",
              "artifactId": "tramai-core",
              "version": "0.3.1",
              "classifier": "sources",
              "extension": "jar",
              "fileName": "tramai-core-0.3.1-sources.jar",
              "sha256": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
              "sizeBytes": 61200
            }
          ]
        }
    """.trimIndent()

    private fun writeManifest(content: String = validManifest): Path {
        val path = tempDir.resolve("release-artifacts-v1.json")
        Files.writeString(path, content)
        return path
    }

    // ── Happy path ──────────────────────────────────────────────────────────

    @Test
    fun `valid manifest loads successfully`() {
        val path = writeManifest()
        val result = ReleaseBundleEvidenceLoader.load(path)

        assertThat(result.schemaVersion).isEqualTo(1)
        assertThat(result.buildTool).isEqualTo("Gradle")
        assertThat(result.javaVersion).isEqualTo("25.0.1")
        assertThat(result.gradleVersion).isEqualTo("8.10")
        assertThat(result.artifacts).hasSize(2)

        assertThat(result.artifacts[0].groupId).isEqualTo("dev.tramai")
        assertThat(result.artifacts[0].artifactId).isEqualTo("tramai-core")
        assertThat(result.artifacts[0].version).isEqualTo("0.3.1")
        assertThat(result.artifacts[0].classifier).isNull()
        assertThat(result.artifacts[0].extension).isEqualTo("jar")
        assertThat(result.artifacts[0].fileName).isEqualTo("tramai-core-0.3.1.jar")
        assertThat(result.artifacts[0].sha256).isEqualTo("sha256:abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890")
        assertThat(result.artifacts[0].sizeBytes).isEqualTo(289479)

        assertThat(result.artifacts[1].classifier).isEqualTo("sources")
        assertThat(result.artifacts[1].sizeBytes).isEqualTo(61200)
    }

    // ── File missing ────────────────────────────────────────────────────────

    @Test
    fun `missing file throws release-bundle-evidence-missing`() {
        val missing = tempDir.resolve("nonexistent.json")
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(missing) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-missing")
    }

    // ── Invalid JSON ────────────────────────────────────────────────────────

    @Test
    fun `invalid JSON throws release-bundle-evidence-invalid-json`() {
        val path = writeManifest("not valid json")
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-invalid-json")
    }

    // ── Schema version ──────────────────────────────────────────────────────

    @Test
    fun `schema version not 1 throws unsupported-schema-version`() {
        val path = writeManifest("""
            {"schemaVersion": 2, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": []}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-unsupported-schema-version")
    }

    @Test
    fun `missing schema version throws unsupported-schema-version`() {
        val path = writeManifest("""
            {"buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": [{"groupId": "x", "artifactId": "y", "version": "1", "fileName": "a.jar", "sha256": "sha256:${"a".repeat(64)}", "sizeBytes": 1, "extension": "jar"}]}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-unsupported-schema-version")
    }

    // ── Missing artifacts ───────────────────────────────────────────────────

    @Test
    fun `missing artifacts field throws missing-artifacts`() {
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8"}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-missing-artifacts")
    }

    // ── Empty artifacts ─────────────────────────────────────────────────────

    @Test
    fun `empty artifacts throws empty-artifacts`() {
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": []}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-empty-artifacts")
    }

    // ── Missing field in artifact entry ─────────────────────────────────────

    @Test
    fun `missing required field throws invalid-artifact-entry`() {
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": [{"groupId": "x", "artifactId": "y"}]}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-invalid-artifact-entry")
    }

    // ── Non-string classifier ───────────────────────────────────────────────

    @Test
    fun `non-string non-null classifier throws invalid-artifact-entry`() {
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": [{"groupId": "x", "artifactId": "y", "version": "1", "classifier": 42, "extension": "jar", "fileName": "a.jar", "sha256": "sha256:${"a".repeat(64)}", "sizeBytes": 1}]}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-invalid-artifact-entry")
    }

    // ── Blank filename ──────────────────────────────────────────────────────

    @Test
    fun `blank filename throws unsafe-file-name`() {
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": [{"groupId": "x", "artifactId": "y", "version": "1", "extension": "jar", "fileName": "  ", "sha256": "sha256:${"a".repeat(64)}", "sizeBytes": 1}]}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-unsafe-file-name")
    }

    // ── Filename with path traversal ────────────────────────────────────────

    @Test
    fun `filename with slash throws unsafe-file-name`() {
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": [{"groupId": "x", "artifactId": "y", "version": "1", "extension": "jar", "fileName": "../evil.jar", "sha256": "sha256:${"a".repeat(64)}", "sizeBytes": 1}]}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-unsafe-file-name")
    }

    // ── Invalid digest ──────────────────────────────────────────────────────

    @Test
    fun `invalid digest format throws invalid-digest-format`() {
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": [{"groupId": "x", "artifactId": "y", "version": "1", "extension": "jar", "fileName": "a.jar", "sha256": "sha256:xyz", "sizeBytes": 1}]}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-invalid-digest-format")
    }

    // ── Uppercase digest ────────────────────────────────────────────────────

    @Test
    fun `uppercase hex digest is accepted`() {
        val upperHex = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": [{"groupId": "x", "artifactId": "y", "version": "1", "extension": "jar", "fileName": "a.jar", "sha256": "sha256:$upperHex", "sizeBytes": 1}]}
        """.trimIndent())
        val result = ReleaseBundleEvidenceLoader.load(path)
        assertThat(result.artifacts[0].sha256).isEqualTo("sha256:$upperHex")
    }

    // ── Negative size ───────────────────────────────────────────────────────

    @Test
    fun `negative size throws invalid-size`() {
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": [{"groupId": "x", "artifactId": "y", "version": "1", "extension": "jar", "fileName": "a.jar", "sha256": "sha256:${"a".repeat(64)}", "sizeBytes": -1}]}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-invalid-size")
    }

    // ── Zero size ───────────────────────────────────────────────────────────

    @Test
    fun `zero size throws invalid-size`() {
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": [{"groupId": "x", "artifactId": "y", "version": "1", "extension": "jar", "fileName": "a.jar", "sha256": "sha256:${"a".repeat(64)}", "sizeBytes": 0}]}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-invalid-size")
    }

    // ── Unsupported extension ───────────────────────────────────────────────

    @Test
    fun `unsupported extension throws unsupported-extension`() {
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": [{"groupId": "x", "artifactId": "y", "version": "1", "extension": "zip", "fileName": "a.zip", "sha256": "sha256:${"a".repeat(64)}", "sizeBytes": 1}]}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-unsupported-extension")
    }

    // ── Duplicate filename ──────────────────────────────────────────────────

    @Test
    fun `duplicate filename throws duplicate-file-name`() {
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": [{"groupId": "x", "artifactId": "y", "version": "1", "extension": "jar", "fileName": "same.jar", "sha256": "sha256:${"a".repeat(64)}", "sizeBytes": 1}, {"groupId": "x", "artifactId": "z", "version": "1", "extension": "jar", "fileName": "same.jar", "sha256": "sha256:${"b".repeat(64)}", "sizeBytes": 1}]}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-duplicate-file-name")
    }

    // ── Duplicate coordinate ────────────────────────────────────────────────

    @Test
    fun `duplicate coordinate throws duplicate-coordinate`() {
        val path = writeManifest("""
            {"schemaVersion": 1, "buildTool": "Gradle", "javaVersion": "25", "gradleVersion": "8", "artifacts": [{"groupId": "g", "artifactId": "a", "version": "1", "classifier": null, "extension": "jar", "fileName": "a-v1.jar", "sha256": "sha256:${"a".repeat(64)}", "sizeBytes": 1}, {"groupId": "g", "artifactId": "a", "version": "1", "classifier": null, "extension": "jar", "fileName": "a-v1-dup.jar", "sha256": "sha256:${"b".repeat(64)}", "sizeBytes": 1}]}
        """.trimIndent())
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-duplicate-coordinate")
    }

    // ── Trailing content ────────────────────────────────────────────────────

    @Test
    fun `trailing garbage after valid json throws invalid-json`() {
        val path = writeManifest(validManifest + " trailing-garbage")
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-invalid-json")
    }

    @Test
    fun `second JSON object after valid json throws invalid-json`() {
        val path = writeManifest(validManifest + "\n{}")
        assertThatThrownBy { ReleaseBundleEvidenceLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("release-bundle-evidence-invalid-json")
    }

    @Test
    fun `allows trailing whitespace after valid json`() {
        val path = writeManifest(validManifest + "   \n  \t  ")
        val result = ReleaseBundleEvidenceLoader.load(path)
        assertThat(result.schemaVersion).isEqualTo(1)
        assertThat(result.artifacts).hasSize(2)
    }
}
