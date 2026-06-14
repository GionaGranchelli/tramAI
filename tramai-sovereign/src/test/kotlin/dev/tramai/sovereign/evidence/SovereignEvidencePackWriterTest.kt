package dev.tramai.sovereign.evidence

import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelArtifactVerificationSettings
import dev.tramai.core.model.VerifiedLocalModelArtifact
import dev.tramai.sovereign.SovereignDeploymentMode
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
        assertThat(json).contains("\"supplyChain\": null")
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
        val supplyChainIdx = json.indexOf("\"supplyChain\"")
        val generatedAtIdx = json.indexOf("\"generatedAt\"")

        assertThat(schemaIdx).isLessThan(deployIdx)
        assertThat(deployIdx).isLessThan(modelsIdx)
        assertThat(modelsIdx).isLessThan(providersIdx)
        assertThat(providersIdx).isLessThan(zonesIdx)
        assertThat(zonesIdx).isLessThan(settingsIdx)
        assertThat(settingsIdx).isLessThan(artifactsIdx)
        assertThat(artifactsIdx).isLessThan(zeroEgressIdx)
        assertThat(zeroEgressIdx).isLessThan(auditChainIdx)
        assertThat(auditChainIdx).isLessThan(supplyChainIdx)
        assertThat(supplyChainIdx).isLessThan(generatedAtIdx)
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
            supplyChain = null,
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        // In the raw JSON text, backslash is escaped as \\.
        // Input "test\model" (1 backslash) → JSON "test\\model" (2 backslash chars)
        assertThat(json).contains("test\\\\model")
        // Input "line1\nline2" (newline char) → JSON "line1\\nline2" (literal \n)
        assertThat(json).contains("line1\\nline2")
        // Input "tab\there" (tab char) → JSON "tab\\there" (literal \t)
        assertThat(json).contains("tab\\there")
        // Input "cr\rend" (CR char) → JSON "cr\\rend" (literal \r)
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
            supplyChain = null,
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
            supplyChain = null,
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
            supplyChain = null,
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        assertThat(json).contains("\"allowedModels\": []")
        assertThat(json).contains("\"allowedProviders\": []")
        assertThat(json).contains("\"artifacts\": []")
        assertThat(json).contains("\"zeroEgress\": null")
        assertThat(json).contains("\"auditChain\": null")
        assertThat(json).contains("\"supplyChain\": null")
    }

    @Test
    fun `writer serialises supply-chain subsection`() {
        val pack = SovereignEvidencePackV1(
            deploymentMode = "STANDARD",
            allowedModels = listOf("model-a"),
            allowedProviders = listOf("provider-x"),
            providerZones = mapOf("provider-x" to "LOCAL"),
            artifactVerificationSettings = mapOf("enabled" to false),
            artifacts = emptyList(),
            zeroEgress = null,
            auditChain = null,
            supplyChain = SupplyChainEvidenceV1(
                sbomFormat = "CycloneDX",
                sbomSpecVersion = "1.6",
                sbomFileName = "tramai-cyclonedx-sbom.json",
                sbomSha256 = "sha256:abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                generatedBy = "CycloneDX Gradle Plugin 3.2.4",
            ),
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        assertThat(json).contains("\"supplyChain\":")
        assertThat(json).contains("\"sbomFormat\": \"CycloneDX\"")
        assertThat(json).contains("\"sbomSpecVersion\": \"1.6\"")
        assertThat(json).contains("\"sbomFileName\": \"tramai-cyclonedx-sbom.json\"")
        assertThat(json).contains("\"sbomSha256\": \"sha256:abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\"")
        assertThat(json).contains("\"generatedBy\": \"CycloneDX Gradle Plugin 3.2.4\"")
    }

    @Test
    fun `rejects evidence-unsafe model names`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("/tmp/model"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `rejects evidence-unsafe provider names`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("secret-provider"),
                providerZones = mapOf("secret-provider" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `rejects evidence-unsafe home path in model name`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("/home/user/model"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `rejects evidence-unsafe token in provider zone key`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("token-provider"),
                providerZones = mapOf("token-provider" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `rejects evidence-unsafe registry entry id`() {
        val validDigest = ModelArtifactDigest.of("sha256:${"a".repeat(64)}")
        assertThatThrownBy {
            val receipt = VerifiedLocalModelArtifact(
                registryEntryId = "token-entry-1",
                manifestDigest = validDigest,
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
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `rejects Windows drive path with backslash`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("C:\\models\\x"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `rejects Windows drive path with forward slash`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("C:/models/x"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `rejects Windows drive path with lowercase drive letter`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("d:\\private\\model"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    // ── Supply-chain validation tests ──────────────────────────────────────

    @Test
    fun `rejects invalid sbom digest sha256 abc`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                supplyChain = SupplyChainEvidenceV1(
                    sbomFormat = "CycloneDX",
                    sbomSpecVersion = "1.6",
                    sbomFileName = "sbom.json",
                    sbomSha256 = "sha256:abc",
                    generatedBy = "test",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-digest-format")
    }

    @Test
    fun `rejects invalid sbom digest format abc`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                supplyChain = SupplyChainEvidenceV1(
                    sbomFormat = "CycloneDX",
                    sbomSpecVersion = "1.6",
                    sbomFileName = "sbom.json",
                    sbomSha256 = "abc",
                    generatedBy = "test",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-digest-format")
    }

    @Test
    fun `rejects wrong digest algorithm sha512`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                supplyChain = SupplyChainEvidenceV1(
                    sbomFormat = "CycloneDX",
                    sbomSpecVersion = "1.6",
                    sbomFileName = "sbom.json",
                    sbomSha256 = "sha512:a" + "b".repeat(63),
                    generatedBy = "test",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-digest-format")
    }

    @Test
    fun `rejects non-hex sbom digest`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                supplyChain = SupplyChainEvidenceV1(
                    sbomFormat = "CycloneDX",
                    sbomSpecVersion = "1.6",
                    sbomFileName = "sbom.json",
                    sbomSha256 = "sha256:zzzz${"a".repeat(60)}",
                    generatedBy = "test",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-digest-format")
    }

    @Test
    fun `rejects sbom filename with path`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                supplyChain = SupplyChainEvidenceV1(
                    sbomFormat = "CycloneDX",
                    sbomSpecVersion = "1.6",
                    sbomFileName = "/tmp/sbom.json",
                    sbomSha256 = "sha256:${"a".repeat(64)}",
                    generatedBy = "test",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `rejects sbom filename with windows path`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                supplyChain = SupplyChainEvidenceV1(
                    sbomFormat = "CycloneDX",
                    sbomSpecVersion = "1.6",
                    sbomFileName = "C:\\sbom.json",
                    sbomSha256 = "sha256:${"a".repeat(64)}",
                    generatedBy = "test",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `accepts valid sbom filename`() {
        val pack = SovereignEvidencePackGenerator.generate(
            deploymentMode = SovereignDeploymentMode.STANDARD,
            allowedModels = setOf("model-a"),
            allowedProviders = setOf("provider-x"),
            providerZones = mapOf("provider-x" to "LOCAL"),
            verificationSettings = ModelArtifactVerificationSettings(),
            verificationReceipts = emptyList(),
            supplyChain = SupplyChainEvidenceV1(
                sbomFormat = "CycloneDX",
                sbomSpecVersion = "1.6",
                sbomFileName = "tramai-cyclonedx-sbom.json",
                sbomSha256 = "sha256:${"a".repeat(64)}",
                generatedBy = "test",
            ),
        )

        assertThat(pack.supplyChain).isNotNull()
        assertThat(pack.supplyChain!!.sbomFileName).isEqualTo("tramai-cyclonedx-sbom.json")
        assertThat(pack.supplyChain!!.sbomSha256).isEqualTo("sha256:${"a".repeat(64)}")
    }

    @Test
    fun `evidence output does not contain sensitive patterns in supply-chain`() {
        val pack = SovereignEvidencePackV1(
            deploymentMode = "STANDARD",
            allowedModels = listOf("model-a"),
            allowedProviders = listOf("provider-x"),
            providerZones = mapOf("provider-x" to "LOCAL"),
            artifactVerificationSettings = mapOf("enabled" to false),
            artifacts = emptyList(),
            zeroEgress = null,
            auditChain = null,
            supplyChain = SupplyChainEvidenceV1(
                sbomFormat = "CycloneDX",
                sbomSpecVersion = "1.6",
                sbomFileName = "safe-sbom.json",
                sbomSha256 = "sha256:${"a".repeat(64)}",
                generatedBy = "Gradle Plugin",
            ),
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        // supply-chain fields should not introduce sensitive paths
        assertThat(json).doesNotContain("/tmp/")
        assertThat(json).doesNotContain("/home/")
        assertThat(json).doesNotContain("/Users/")
        assertThat(json).doesNotContain("C:\\")
        assertThat(json).doesNotContain("prompt")
        // Note: "token" can appear in hex digests ("a" hex won't), but we verify no unsafe patterns
        assertThat(json).doesNotContain("stacktrace")
        assertThat(json).doesNotContain("rawRequest")
        assertThat(json).doesNotContain("rawResponse")
    }

    @Test
    fun `rejects sbom filename with subdir relative path`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                supplyChain = SupplyChainEvidenceV1(
                    sbomFormat = "CycloneDX",
                    sbomSpecVersion = "1.6",
                    sbomFileName = "subdir/sbom.json",
                    sbomSha256 = "sha256:${"a".repeat(64)}",
                    generatedBy = "test",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `rejects sbom filename with backslash relative path`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                supplyChain = SupplyChainEvidenceV1(
                    sbomFormat = "CycloneDX",
                    sbomSpecVersion = "1.6",
                    sbomFileName = "subdir\\sbom.json",
                    sbomSha256 = "sha256:${"a".repeat(64)}",
                    generatedBy = "test",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `rejects unsupported supply-chain schema version 0`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                supplyChain = SupplyChainEvidenceV1(
                    schemaVersion = 0,
                    sbomFormat = "CycloneDX",
                    sbomSpecVersion = "1.6",
                    sbomFileName = "sbom.json",
                    sbomSha256 = "sha256:${"a".repeat(64)}",
                    generatedBy = "test",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsupported-supply-chain-schema-version")
    }

    @Test
    fun `rejects unsupported supply-chain schema version 2`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                supplyChain = SupplyChainEvidenceV1(
                    schemaVersion = 2,
                    sbomFormat = "CycloneDX",
                    sbomSpecVersion = "1.6",
                    sbomFileName = "sbom.json",
                    sbomSha256 = "sha256:${"a".repeat(64)}",
                    generatedBy = "test",
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsupported-supply-chain-schema-version")
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
        supplyChain = null,
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
