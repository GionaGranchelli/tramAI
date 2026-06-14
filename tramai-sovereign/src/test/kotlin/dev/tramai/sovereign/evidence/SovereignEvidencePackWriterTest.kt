package dev.tramai.sovereign.evidence

import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelArtifactVerificationSettings
import dev.tramai.core.model.VerifiedLocalModelArtifact
import dev.tramai.sovereign.SovereignDeploymentMode
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [SovereignEvidencePackWriter].
 */
class SovereignEvidencePackWriterTest {

    @Test
    fun `writer produces valid JSON with stable field ordering`() {
        val pack = samplePack()
        val json = writeToString(pack)

        assertThat(json).contains("\"schemaVersion\": 1")
        assertThat(json).contains("\"deploymentMode\":")
        assertThat(json).contains("\"allowedModels\":")
        assertThat(json).contains("\"allowedProviders\":")
        assertThat(json).contains("\"providerZones\":")
        assertThat(json).contains("\"artifactVerificationSettings\":")
        assertThat(json).contains("\"artifacts\":")
        assertThat(json).contains("\"zeroEgress\": null")
        assertThat(json).contains("\"auditChain\": null")
        assertThat(json).contains("\"generatedAt\":")

        // Verify field order matches data class declaration order
        val schemaIdx = json.indexOf("\"schemaVersion\"")
        val deployIdx = json.indexOf("\"deploymentMode\"")
        val modelsIdx = json.indexOf("\"allowedModels\"")
        val providersIdx = json.indexOf("\"allowedProviders\"")
        val zonesIdx = json.indexOf("\"providerZones\"")
        val settingsIdx = json.indexOf("\"artifactVerificationSettings\"")
        val artifactsIdx = json.indexOf("\"artifacts\"")
        val zeroEgressIdx = json.indexOf("\"zeroEgress\"")
        val auditChainIdx = json.indexOf("\"auditChain\"")
        val generatedAtIdx = json.indexOf("\"generatedAt\"")

        assertThat(schemaIdx).isLessThan(deployIdx)
        assertThat(deployIdx).isLessThan(modelsIdx)
        assertThat(modelsIdx).isLessThan(providersIdx)
        assertThat(providersIdx).isLessThan(zonesIdx)
        assertThat(zonesIdx).isLessThan(settingsIdx)
        assertThat(settingsIdx).isLessThan(artifactsIdx)
        assertThat(artifactsIdx).isLessThan(zeroEgressIdx)
        assertThat(zeroEgressIdx).isLessThan(auditChainIdx)
        assertThat(auditChainIdx).isLessThan(generatedAtIdx)
    }

    @Test
    fun `writer escapes quotes backslashes newline tab carriage return`() {
        // Build a pack with strings containing special chars
        val pack = SovereignEvidencePackV1(
            deploymentMode = "OF\"FLINE",
            allowedModels = listOf("test\\model"),
            allowedProviders = listOf("line1\nline2", "tab\there", "cr\rend"),
            providerZones = emptyMap(),
            artifactVerificationSettings = emptyMap(),
            artifacts = emptyList(),
            zeroEgress = null,
            auditChain = null,
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        // The escaped strings in the JSON output:
        // deploymentMode: "OF\"FLINE"  → check for "OF\"FLINE"
        assertThat(json).contains("\"OF\\\"FLINE\"")
        // allowedModels[0]: "test\\model" → check for test\\model
        assertThat(json).contains("test\\\\model")
        // allowedProviders[0]: "line1\nline2" → check for line1\nline2
        assertThat(json).contains("line1\\nline2")
        // allowedProviders[1]: "tab\there" → check for tab\there
        assertThat(json).contains("tab\\there")
        // allowedProviders[2]: "cr\rend" → check for cr\rend
        assertThat(json).contains("cr\\rend")
    }

    @Test
    fun `writer escapes all control chars less than 0x20`() {
        val pack = SovereignEvidencePackV1(
            deploymentMode = "safe",
            allowedModels = listOf("test"),
            allowedProviders = listOf("\u0000\u0008\u000c"),
            providerZones = emptyMap(),
            artifactVerificationSettings = emptyMap(),
            artifacts = emptyList(),
            zeroEgress = null,
            auditChain = null,
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        // null (0x00), backspace (0x08), form feed (0x0c)
        // Writer escapes them as \u0000, \u0008, \u000c
        assertThat(json).contains("\\u0000")
        assertThat(json).contains("\\u0008")
        assertThat(json).contains("\\u000c")
    }

    @Test
    fun `writer creates parent directories`(
        @TempDir tempDir: Path,
    ) {
        val nestedPath = tempDir.resolve("subdir").resolve("nested").resolve("evidence.json")
        val pack = samplePack()

        SovereignEvidencePackWriter.write(pack, nestedPath)

        assertThat(nestedPath).exists()
        assertThat(nestedPath.parent).exists()
    }

    @Test
    fun `output does not contain sensitive patterns`() {
        val pack = samplePack()
        val json = writeToString(pack)

        assertThat(json).doesNotContain("/tmp/")
        assertThat(json).doesNotContain("/home/")
        assertThat(json).doesNotContain("/Users/")
        assertThat(json).doesNotContain("C:\\")
        assertThat(json).doesNotContain("prompt")
        assertThat(json).doesNotContain("token")
        assertThat(json).doesNotContain("secret")
        assertThat(json).doesNotContain("stacktrace")
        assertThat(json).doesNotContain("rawRequest")
        assertThat(json).doesNotContain("rawResponse")
    }

    @Test
    fun `writer serialises zero-egress and audit-chain subsections`() {
        val pack = SovereignEvidencePackV1(
            deploymentMode = "OFFLINE",
            allowedModels = listOf("test-model"),
            allowedProviders = listOf("local-provider"),
            providerZones = mapOf("local-provider" to "LOCAL"),
            artifactVerificationSettings = mapOf("enabled" to true),
            artifacts = listOf(
                ArtifactEvidenceV1(
                    registryEntryId = "entry-1",
                    manifestDigest = "sha256:abc",
                    modelName = "test-model",
                    verifiedAt = "2026-01-01T00:00:00Z",
                    artifactCount = 2,
                    totalSizeBytes = 1024,
                ),
            ),
            zeroEgress = ZeroEgressEvidenceV1(
                deploymentMode = "OFFLINE",
                runtimeBuildSucceeded = true,
                loopbackProviderInvocationSucceeded = true,
                loopbackProviderInvocationCount = 1,
                externalTcpProbeBlocked = true,
                externalDnsProbeBlocked = true,
            ),
            auditChain = AuditChainEvidenceV1(
                isValid = true,
                totalEvents = 5,
            ),
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        assertThat(json).contains("\"zeroEgress\":")
        assertThat(json).contains("\"auditChain\":")
        assertThat(json).contains("\"deploymentMode\": \"OFFLINE\"")
        assertThat(json).contains("\"runtimeBuildSucceeded\": true")
        assertThat(json).contains("\"loopbackProviderInvocationCount\": 1")
        assertThat(json).contains("\"externalTcpProbeBlocked\": true")
        assertThat(json).contains("\"externalDnsProbeBlocked\": true")
        assertThat(json).contains("\"isValid\": true")
        assertThat(json).contains("\"totalEvents\": 5")
        assertThat(json).contains("\"registryEntryId\": \"entry-1\"")
        assertThat(json).contains("\"manifestDigest\": \"sha256:abc\"")
        assertThat(json).contains("\"artifactCount\": 2")
        assertThat(json).contains("\"totalSizeBytes\": 1024")
    }

    @Test
    fun `writer serialises empty lists correctly`() {
        val pack = SovereignEvidencePackV1(
            deploymentMode = "STANDARD",
            allowedModels = emptyList(),
            allowedProviders = emptyList(),
            providerZones = emptyMap(),
            artifactVerificationSettings = emptyMap(),
            artifacts = emptyList(),
            zeroEgress = null,
            auditChain = null,
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        assertThat(json).contains("\"allowedModels\": []")
        assertThat(json).contains("\"allowedProviders\": []")
        assertThat(json).contains("\"artifacts\": []")
        assertThat(json).contains("\"zeroEgress\": null")
        assertThat(json).contains("\"auditChain\": null")
    }

    @Test
    fun `rejects evidence-unsafe model names`() {
        assertThrows(IllegalArgumentException::class.java) {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("/tmp/model"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
            )
        }
    }

    @Test
    fun `rejects evidence-unsafe provider names`() {
        assertThrows(IllegalArgumentException::class.java) {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("secret-provider"),
                providerZones = mapOf("secret-provider" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
            )
        }
    }

    @Test
    fun `rejects evidence-unsafe home path in model name`() {
        assertThrows(IllegalArgumentException::class.java) {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("/home/user/model"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
            )
        }
    }

    @Test
    fun `rejects evidence-unsafe token in provider zone key`() {
        assertThrows(IllegalArgumentException::class.java) {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("token-provider"),
                providerZones = mapOf("token-provider" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
            )
        }
    }

    @Test
    fun `rejects evidence-unsafe registry entry id`() {
        assertThrows(IllegalArgumentException::class.java) {
            val receipt = VerifiedLocalModelArtifact(
                registryEntryId = "token-entry-1",
                manifestDigest = ModelArtifactDigest.of("sha256:abc"),
                modelName = "model-a",
                verifiedAt = Instant.parse("2026-01-01T00:00:00Z"),
                artifactCount = 1,
                totalSizeBytes = 100,
            )
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = listOf(receipt),
            )
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun samplePack(): SovereignEvidencePackV1 = SovereignEvidencePackV1(
        deploymentMode = "STANDARD",
        allowedModels = listOf("model-a"),
        allowedProviders = listOf("provider-x"),
        providerZones = mapOf("provider-x" to "LOCAL"),
        artifactVerificationSettings = mapOf("enabled" to false),
        artifacts = emptyList(),
        zeroEgress = null,
        auditChain = null,
        generatedAt = "2026-01-01T00:00:00Z",
    )

    private fun writeToString(pack: SovereignEvidencePackV1): String {
        val tempFile = Files.createTempFile("evidence-test-", ".json")
        try {
            SovereignEvidencePackWriter.write(pack, tempFile)
            return Files.readString(tempFile)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
