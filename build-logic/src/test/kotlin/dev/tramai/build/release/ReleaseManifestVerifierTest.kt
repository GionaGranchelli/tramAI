package dev.tramai.build.release

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReleaseManifestVerifierTest {

    @TempDir
    lateinit var tempDir: File

    private fun manifestDir(): File = File(tempDir, "manifest")

    private fun artifactsDir(): File = File(tempDir, "artifacts")

    private fun writeManifest(
        json: String,
        dir: File = manifestDir(),
        createArtifactsDir: Boolean = true,
    ): File {
        dir.mkdirs()
        if (createArtifactsDir) {
            artifactsDir().mkdirs()
        }
        val file = dir.resolve("release-artifacts-v1.json")
        file.writeText(json)
        return file
    }

    private fun writeArtifact(name: String, content: ByteArray = "content".toByteArray()): File {
        artifactsDir().mkdirs()
        val file = File(artifactsDir(), name)
        file.writeBytes(content)
        return file
    }

    private fun validManifestJson(fileName: String = "tramai-core-0.5.0.jar"): String = """
        {
          "schemaVersion": 1,
          "buildTool": "Gradle",
          "javaVersion": "21",
          "gradleVersion": "8.5",
          "artifacts": [
            {
              "groupId": "dev.tramai",
              "artifactId": "tramai-core",
              "version": "0.5.0",
              "classifier": null,
              "extension": "jar",
              "fileName": "$fileName",
              "sha256": "sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
              "sizeBytes": 7
            }
          ]
        }
    """.trimIndent()

    private fun validDigestFor(file: File): String = dev.tramai.build.sovereign.evidence.Hashing.sha256Hex(file)

    @Test
    fun `valid manifest passes`() {
        val artifact = writeArtifact("tramai-core-0.5.0.jar")
        val json = validManifestJson().replace("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", validDigestFor(artifact))
        writeManifest(json)
        ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
    }

    @Test
    fun `missing manifest fails`() {
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.startsWith("sovereign-release-manifest-missing"))
    }

    @Test
    fun `invalid JSON fails`() {
        writeManifest("{ not valid json ")
        val e = assertFailsWith<IllegalStateException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.startsWith("sovereign-release-manifest-invalid-json"))
    }

    @Test
    fun `unsupported schema fails`() {
        writeManifest(validManifestJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"))
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("unsupported-schema-version"))
    }

    @Test
    fun `empty artifacts fails`() {
        writeManifest(validManifestJson().replace(
            Regex("\"artifacts\": \\[[\\s\\S]*?\"fileName\": \"[^\"]+\"[\\s\\S]*?\\]"),
            "\"artifacts\": []",
        ))
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("sovereign-release-manifest-empty-artifacts"))
    }

    @Test
    fun `missing field fails`() {
        writeArtifact("tramai-core-0.5.0.jar")
        // Remove the trailing sizeBytes field (with its leading comma) — the
        // entry becomes invalid and the verifier must reject it.
        writeManifest(
            validManifestJson().replace(Regex(",\\s*\"sizeBytes\": 7"), ""),
        )
        val e = assertFailsWith<IllegalStateException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("invalid-artifact-entry"))
    }

    @Test
    fun `unsafe filename fails`() {
        // No artifact is written: the unsafe-name check runs before on-disk
        // existence is evaluated, so the manifest alone triggers the failure.
        val json = validManifestJson("../../etc/passwd")
        writeManifest(json)
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("unsafe-file-name"))
    }

    @Test
    fun `invalid SHA format fails`() {
        val artifact = writeArtifact("tramai-core-0.5.0.jar")
        val json = validManifestJson().replace("sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", "md5:abc")
        writeManifest(json)
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("invalid-digest-format") || e.message!!.contains("sha256"))
    }

    @Test
    fun `zero size fails`() {
        val artifact = writeArtifact("tramai-core-0.5.0.jar", ByteArray(0))
        val json = validManifestJson().replace("\"sizeBytes\": 7", "\"sizeBytes\": 0")
        writeManifest(json)
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("size"))
    }

    @Test
    fun `unsupported extension fails`() {
        val artifact = writeArtifact("tramai-core-0.5.0.zip")
        val json = validManifestJson("tramai-core-0.5.0.zip")
        check(json.contains("\"extension\": \"jar\"")) { "fixture must contain the jar extension to mutate" }
        val mutated = json
            .replaceFirst("\"extension\": \"jar\"", "\"extension\": \"zip\"")
            .replace("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", validDigestFor(artifact))
        writeManifest(mutated)
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("unsupported-extension"))
    }

    @Test
    fun `duplicate filename fails`() {
        val artifact = writeArtifact("tramai-core-0.5.0.jar")
        val digest = validDigestFor(artifact)
        val json = validManifestJson().replace(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            digest,
        ).replace(
            "  ]",
            ",\n            {\n              \"groupId\": \"dev.tramai\",\n              \"artifactId\": \"tramai-other\",\n              \"version\": \"0.5.0\",\n              \"classifier\": null,\n              \"extension\": \"jar\",\n              \"fileName\": \"tramai-core-0.5.0.jar\",\n              \"sha256\": \"sha256:$digest\",\n              \"sizeBytes\": 7\n            }\n          ]",
        )
        writeManifest(json)
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("duplicate") || e.message!!.contains("filename"))
    }

    @Test
    fun `duplicate coordinate fails`() {
        val artifact = writeArtifact("tramai-core-0.5.0.jar")
        val digest = validDigestFor(artifact)
        val json = validManifestJson().replace(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            digest,
        ).replace(
            "  ]",
            ",\n            {\n              \"groupId\": \"dev.tramai\",\n              \"artifactId\": \"tramai-core\",\n              \"version\": \"0.5.0\",\n              \"classifier\": null,\n              \"extension\": \"jar\",\n              \"fileName\": \"tramai-core-0.5.0-dup.jar\",\n              \"sha256\": \"sha256:$digest\",\n              \"sizeBytes\": 7\n            }\n          ]",
        )
        writeManifest(json)
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("duplicate-coordinate"))
    }

    @Test
    fun `missing artifact on disk fails`() {
        val json = validManifestJson() // no artifact file written
        writeManifest(json)
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("missing") || e.message!!.contains("not found"))
    }

    @Test
    fun `size mismatch fails`() {
        val artifact = writeArtifact("tramai-core-0.5.0.jar") // 7 bytes
        val json = validManifestJson().replace("\"sizeBytes\": 7", "\"sizeBytes\": 99")
        writeManifest(json)
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("size"))
    }

    @Test
    fun `digest mismatch fails`() {
        writeArtifact("tramai-core-0.5.0.jar")
        writeManifest(validManifestJson()) // wrong digest for the actual 7-byte file
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("sha256") || e.message!!.contains("digest"))
    }

    @Test
    fun `unlisted artifact fails`() {
        val artifact = writeArtifact("tramai-core-0.5.0.jar")
        writeArtifact("unlisted-extra.jar")
        val json = validManifestJson().replace("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", validDigestFor(artifact))
        writeManifest(json)
        val e = assertFailsWith<IllegalArgumentException> {
            ReleaseManifestVerifier.verify(manifestDir(), artifactsDir())
        }
        assertTrue(e.message!!.contains("unlisted") || e.message!!.contains("extra"))
    }
}
