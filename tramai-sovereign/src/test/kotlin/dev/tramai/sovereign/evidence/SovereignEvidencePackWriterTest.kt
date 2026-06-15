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
        assertThat(json).contains("\"releaseBundle\": null")
        assertThat(json).contains("\"attestation\": null")
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
        val releaseBundleIdx = json.indexOf("\"releaseBundle\"")
        val attestationIdx = json.indexOf("\"attestation\"")
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
        assertThat(supplyChainIdx).isLessThan(releaseBundleIdx)
        assertThat(releaseBundleIdx).isLessThan(attestationIdx)
        assertThat(attestationIdx).isLessThan(generatedAtIdx)
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
            releaseBundle = null,
            attestation = null,
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
            releaseBundle = null,
            attestation = null,
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
            releaseBundle = null,
            attestation = null,
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
            releaseBundle = null,
            attestation = null,
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
            releaseBundle = null,
            attestation = null,
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
    fun `writer serialises attestation subsection`() {
        val pack = SovereignEvidencePackV1(
            deploymentMode = "STANDARD",
            allowedModels = listOf("model-a"),
            allowedProviders = listOf("provider-x"),
            providerZones = mapOf("provider-x" to "LOCAL"),
            artifactVerificationSettings = mapOf("enabled" to false),
            artifacts = emptyList(),
            zeroEgress = null,
            auditChain = null,
            supplyChain = null,
            releaseBundle = null,
            attestation = AttestationEvidenceV1(
                provider = "GitHub Artifact Attestations",
                workflowName = "CI",
                workflowRunId = "1234567",
                repository = "my-org/my-repo",
                commitSha = "abcdef1234567890abcdef1234567890abcdef12",
                attestedSubjects = listOf(
                    AttestedSubjectV1(
                            attestationType = "build-provenance",
                        fileName = "tramai.jar",
                        sha256 = "sha256:${"a".repeat(64)}",
                    ),
                ),
            ),
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        assertThat(json).contains("\"attestation\":")
        assertThat(json).contains("\"provider\": \"GitHub Artifact Attestations\"")
        assertThat(json).contains("\"workflowRunId\": \"1234567\"")
        assertThat(json).contains("\"repository\": \"my-org/my-repo\"")
        assertThat(json).contains("\"commitSha\": \"abcdef1234567890abcdef1234567890abcdef12\"")
        assertThat(json).contains("\"attestedSubjects\":")
        assertThat(json).contains("\"fileName\": \"tramai.jar\"")
        assertThat(json).contains("\"sha256\": \"sha256:${"a".repeat(64)}\"")
    }

    @Test
    fun `writer serialises attestation with multiple subjects`() {
        val pack = SovereignEvidencePackV1(
            deploymentMode = "STANDARD",
            allowedModels = listOf("model-a"),
            allowedProviders = listOf("provider-x"),
            providerZones = mapOf("provider-x" to "LOCAL"),
            artifactVerificationSettings = mapOf("enabled" to false),
            artifacts = emptyList(),
            zeroEgress = null,
            auditChain = null,
            supplyChain = null,
            releaseBundle = null,
            attestation = AttestationEvidenceV1(
                provider = "GitHub Artifact Attestations",
                workflowName = "CI",
                workflowRunId = "987654",
                repository = "org/repo-name",
                commitSha = "1234567890abcdef1234567890abcdef12345678",
                attestedSubjects = listOf(
                    AttestedSubjectV1(
                            attestationType = "build-provenance",
                        fileName = "artifact-a.bin",
                        sha256 = "sha256:${"b".repeat(64)}",
                    ),
                    AttestedSubjectV1(
                            attestationType = "build-provenance",
                        fileName = "artifact-b.bin",
                        sha256 = "sha256:${"c".repeat(64)}",
                    ),
                ),
            ),
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        assertThat(json).contains("\"attestedSubjects\":")
        assertThat(json).contains("\"fileName\": \"artifact-a.bin\"")
        assertThat(json).contains("\"fileName\": \"artifact-b.bin\"")
        assertThat(json).contains("\"sha256\": \"sha256:${"b".repeat(64)}\"")
        assertThat(json).contains("\"sha256\": \"sha256:${"c".repeat(64)}\"")
        assertThat(json).contains("\"commitSha\": \"1234567890abcdef1234567890abcdef12345678\"")
    }

    @Test
    fun `writer serialises null attestation`() {
        val pack = SovereignEvidencePackV1(
            deploymentMode = "STANDARD",
            allowedModels = listOf("model-a"),
            allowedProviders = listOf("provider-x"),
            providerZones = mapOf("provider-x" to "LOCAL"),
            artifactVerificationSettings = mapOf("enabled" to false),
            artifacts = emptyList(),
            zeroEgress = null,
            auditChain = null,
            supplyChain = null,
            releaseBundle = null,
            attestation = null,
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        assertThat(json).contains("\"attestation\": null")
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
            releaseBundle = null,
            attestation = null,
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

    // ── Attestation validation tests ───────────────────────────────────────

    @Test
    fun `rejects unsupported attestation schema version`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                attestation = AttestationEvidenceV1(
                    schemaVersion = 2,
                    provider = "GitHub Artifact Attestations",
                    workflowName = "CI",
                    workflowRunId = "12345",
                    repository = "org/repo",
                    commitSha = "a".repeat(40),
                    attestedSubjects = listOf(
                        AttestedSubjectV1(
                        attestationType = "build-provenance",
                        fileName = "artifact.bin",
                        sha256 = "sha256:${"a".repeat(64)}",
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsupported-attestation-schema-version")
    }

    @Test
    fun `rejects invalid attestation workflow run id`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                attestation = AttestationEvidenceV1(
                    provider = "GitHub Artifact Attestations",
                    workflowName = "CI",
                    workflowRunId = "abc123",
                    repository = "org/repo",
                    commitSha = "a".repeat(40),
                    attestedSubjects = listOf(
                        AttestedSubjectV1(
                        attestationType = "build-provenance",
                        fileName = "artifact.bin",
                        sha256 = "sha256:${"a".repeat(64)}",
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-attestation-workflow-run-id")
    }

    @Test
    fun `rejects invalid attestation repository format`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                attestation = AttestationEvidenceV1(
                    provider = "GitHub Artifact Attestations",
                    workflowName = "CI",
                    workflowRunId = "12345",
                    repository = "invalid-repo-no-slash",
                    commitSha = "a".repeat(40),
                    attestedSubjects = listOf(
                        AttestedSubjectV1(
                        attestationType = "build-provenance",
                        fileName = "artifact.bin",
                        sha256 = "sha256:${"a".repeat(64)}",
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-attestation-repository")
    }

    @Test
    fun `rejects invalid attestation commit sha`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                attestation = AttestationEvidenceV1(
                    provider = "GitHub Artifact Attestations",
                    workflowName = "CI",
                    workflowRunId = "12345",
                    repository = "org/repo",
                    commitSha = "short",
                    attestedSubjects = listOf(
                        AttestedSubjectV1(
                        attestationType = "build-provenance",
                        fileName = "artifact.bin",
                        sha256 = "sha256:${"a".repeat(64)}",
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-attestation-commit-sha")
    }

    @Test
    fun `rejects empty attestation subjects`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                attestation = AttestationEvidenceV1(
                    provider = "GitHub Artifact Attestations",
                    workflowName = "CI",
                    workflowRunId = "12345",
                    repository = "org/repo",
                    commitSha = "a".repeat(40),
                    attestedSubjects = emptyList(),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-attestation-subjects")
    }

    @Test
    fun `rejects invalid digest format in attestation subject`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                attestation = AttestationEvidenceV1(
                    provider = "GitHub Artifact Attestations",
                    workflowName = "CI",
                    workflowRunId = "12345",
                    repository = "org/repo",
                    commitSha = "a".repeat(40),
                    attestedSubjects = listOf(
                        AttestedSubjectV1(
                        attestationType = "build-provenance",
                        fileName = "artifact.bin",
                        sha256 = "sha256:xyz",
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-digest-format")
    }

    @Test
    fun `rejects invalid subject name with path separator`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                attestation = AttestationEvidenceV1(
                    provider = "GitHub Artifact Attestations",
                    workflowName = "CI",
                    workflowRunId = "12345",
                    repository = "org/repo",
                    commitSha = "a".repeat(40),
                    attestedSubjects = listOf(
                        AttestedSubjectV1(
                        attestationType = "build-provenance",
                        fileName = "subdir/artifact.bin",
                        sha256 = "sha256:${"a".repeat(64)}",
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `rejects unsupported attestation type`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                attestation = AttestationEvidenceV1(
                    provider = "GitHub Artifact Attestations",
                    workflowName = "CI",
                    workflowRunId = "12345",
                    repository = "org/repo",
                    commitSha = "a".repeat(40),
                    attestedSubjects = listOf(
                        AttestedSubjectV1(
                        attestationType = "invalid-type",
                        fileName = "artifact.bin",
                        sha256 = "sha256:${"a".repeat(64)}",
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsupported-attestation-type")
    }

    @Test
    fun `accepts valid full attestation`() {
        val pack = SovereignEvidencePackGenerator.generate(
            deploymentMode = SovereignDeploymentMode.STANDARD,
            allowedModels = setOf("model-a"),
            allowedProviders = setOf("provider-x"),
            providerZones = mapOf("provider-x" to "LOCAL"),
            verificationSettings = ModelArtifactVerificationSettings(),
            verificationReceipts = emptyList(),
            attestation = AttestationEvidenceV1(
                provider = "GitHub Artifact Attestations",
                workflowName = "CI",
                workflowRunId = "123456789",
                repository = "my-org/my-repo_name",
                commitSha = "abcdefabcdefabcdefabcdefabcdefabcdefabcd",
                attestedSubjects = listOf(
                    AttestedSubjectV1(
                            attestationType = "build-provenance",
                        fileName = "tramai.jar",
                        sha256 = "sha256:${"a".repeat(64)}",
                    ),
                    AttestedSubjectV1(
                            attestationType = "build-provenance",
                        fileName = "tramai-sources.jar",
                        sha256 = "sha256:${"b".repeat(64)}",
                    ),
                ),
            ),
        )

        assertThat(pack.attestation).isNotNull()
        assertThat(pack.attestation!!.provider).isEqualTo("GitHub Artifact Attestations")
        assertThat(pack.attestation!!.workflowRunId).isEqualTo("123456789")
        assertThat(pack.attestation!!.repository).isEqualTo("my-org/my-repo_name")
        assertThat(pack.attestation!!.commitSha).isEqualTo("abcdefabcdefabcdefabcdefabcdefabcdefabcd")
        assertThat(pack.attestation!!.attestedSubjects).hasSize(2)
        assertThat(pack.attestation!!.attestedSubjects[0].fileName).isEqualTo("tramai.jar")
        assertThat(pack.attestation!!.attestedSubjects[1].fileName).isEqualTo("tramai-sources.jar")
    }

    // ── Release-bundle writer tests ─────────────────────────────────────────

    @Test
    fun `writer serialises release-bundle subsection with multiple artifacts`() {
        val pack = SovereignEvidencePackV1(
            deploymentMode = "STANDARD",
            allowedModels = listOf("model-a"),
            allowedProviders = listOf("provider-x"),
            providerZones = mapOf("provider-x" to "LOCAL"),
            artifactVerificationSettings = mapOf("enabled" to false),
            artifacts = emptyList(),
            zeroEgress = null,
            auditChain = null,
            supplyChain = null,
            releaseBundle = ReleaseBundleEvidenceV1(
                buildTool = "Gradle",
                javaVersion = "25.0.1",
                gradleVersion = "8.10",
                artifacts = listOf(
                    ReleaseArtifactEvidenceV1(
                        groupId = "dev.tramai",
                        artifactId = "tramai-core",
                        version = "1.0.0",
                        classifier = null,
                        extension = "jar",
                        fileName = "tramai-core-1.0.0.jar",
                        sha256 = "sha256:${"a".repeat(64)}",
                        sizeBytes = 51200,
                    ),
                    ReleaseArtifactEvidenceV1(
                        groupId = "dev.tramai",
                        artifactId = "tramai-sovereign",
                        version = "1.0.0",
                        classifier = "sources",
                        extension = "jar",
                        fileName = "tramai-sovereign-1.0.0-sources.jar",
                        sha256 = "sha256:${"b".repeat(64)}",
                        sizeBytes = 25600,
                    ),
                ),
            ),
            attestation = null,
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        assertThat(json).contains("\"releaseBundle\":")
        assertThat(json).contains("\"buildTool\": \"Gradle\"")
        assertThat(json).contains("\"javaVersion\": \"25.0.1\"")
        assertThat(json).contains("\"gradleVersion\": \"8.10\"")
        assertThat(json).contains("\"groupId\": \"dev.tramai\"")
        assertThat(json).contains("\"artifactId\": \"tramai-core\"")
        assertThat(json).contains("\"artifactId\": \"tramai-sovereign\"")
        assertThat(json).contains("\"version\": \"1.0.0\"")
        assertThat(json).contains("\"classifier\": null")
        assertThat(json).contains("\"classifier\": \"sources\"")
        assertThat(json).contains("\"extension\": \"jar\"")
        assertThat(json).contains("\"fileName\": \"tramai-core-1.0.0.jar\"")
        assertThat(json).contains("\"fileName\": \"tramai-sovereign-1.0.0-sources.jar\"")
        assertThat(json).contains("\"sha256\": \"sha256:${"a".repeat(64)}\"")
        assertThat(json).contains("\"sha256\": \"sha256:${"b".repeat(64)}\"")
        assertThat(json).contains("\"sizeBytes\": 51200")
        assertThat(json).contains("\"sizeBytes\": 25600")
    }

    @Test
    fun `writer serialises null releaseBundle as null`() {
        val pack = SovereignEvidencePackV1(
            deploymentMode = "STANDARD",
            allowedModels = listOf("model-a"),
            allowedProviders = listOf("provider-x"),
            providerZones = mapOf("provider-x" to "LOCAL"),
            artifactVerificationSettings = mapOf("enabled" to false),
            artifacts = emptyList(),
            zeroEgress = null,
            auditChain = null,
            supplyChain = null,
            releaseBundle = null,
            attestation = null,
            generatedAt = "2026-01-01T00:00:00Z",
        )
        val json = writeToString(pack)

        assertThat(json).contains("\"releaseBundle\": null")
    }

    // ── Release-bundle validation tests ─────────────────────────────────────

    @Test
    fun `rejects unsupported release bundle schema version`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                releaseBundle = ReleaseBundleEvidenceV1(
                    schemaVersion = 2,
                    buildTool = "Gradle",
                    javaVersion = "25",
                    gradleVersion = "8.10",
                    artifacts = listOf(
                        ReleaseArtifactEvidenceV1(
                            groupId = "dev.tramai",
                            artifactId = "tramai-core",
                            version = "1.0.0",
                            classifier = null,
                            extension = "jar",
                            fileName = "tramai-core-1.0.0.jar",
                            sha256 = "sha256:${"a".repeat(64)}",
                            sizeBytes = 51200,
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsupported-release-bundle-schema-version")
    }

    @Test
    fun `rejects empty artifact list in release bundle`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                releaseBundle = ReleaseBundleEvidenceV1(
                    buildTool = "Gradle",
                    javaVersion = "25",
                    gradleVersion = "8.10",
                    artifacts = emptyList(),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-release-artifacts-empty")
    }

    @Test
    fun `rejects negative artifact size in release bundle`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                releaseBundle = ReleaseBundleEvidenceV1(
                    buildTool = "Gradle",
                    javaVersion = "25",
                    gradleVersion = "8.10",
                    artifacts = listOf(
                        ReleaseArtifactEvidenceV1(
                            groupId = "dev.tramai",
                            artifactId = "tramai-core",
                            version = "1.0.0",
                            classifier = null,
                            extension = "jar",
                            fileName = "tramai-core-1.0.0.jar",
                            sha256 = "sha256:${"a".repeat(64)}",
                            sizeBytes = -1,
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-artifact-size-negative")
    }

    @Test
    fun `rejects invalid digest format in release artifact`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                releaseBundle = ReleaseBundleEvidenceV1(
                    buildTool = "Gradle",
                    javaVersion = "25",
                    gradleVersion = "8.10",
                    artifacts = listOf(
                        ReleaseArtifactEvidenceV1(
                            groupId = "dev.tramai",
                            artifactId = "tramai-core",
                            version = "1.0.0",
                            classifier = null,
                            extension = "jar",
                            fileName = "tramai-core-1.0.0.jar",
                            sha256 = "sha256:xyz",
                            sizeBytes = 51200,
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-digest-format")
    }

    @Test
    fun `rejects filename with path separator in release artifact`() {
        assertThatThrownBy {
            SovereignEvidencePackGenerator.generate(
                deploymentMode = SovereignDeploymentMode.STANDARD,
                allowedModels = setOf("model-a"),
                allowedProviders = setOf("provider-x"),
                providerZones = mapOf("provider-x" to "LOCAL"),
                verificationSettings = ModelArtifactVerificationSettings(),
                verificationReceipts = emptyList(),
                releaseBundle = ReleaseBundleEvidenceV1(
                    buildTool = "Gradle",
                    javaVersion = "25",
                    gradleVersion = "8.10",
                    artifacts = listOf(
                        ReleaseArtifactEvidenceV1(
                            groupId = "dev.tramai",
                            artifactId = "tramai-core",
                            version = "1.0.0",
                            classifier = null,
                            extension = "jar",
                            fileName = "subdir/tramai-core-1.0.0.jar",
                            sha256 = "sha256:${"a".repeat(64)}",
                            sizeBytes = 51200,
                        ),
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("evidence-unsafe-identifier")
    }

    @Test
    fun `accepts valid full release bundle`() {
        val pack = SovereignEvidencePackGenerator.generate(
            deploymentMode = SovereignDeploymentMode.STANDARD,
            allowedModels = setOf("model-a"),
            allowedProviders = setOf("provider-x"),
            providerZones = mapOf("provider-x" to "LOCAL"),
            verificationSettings = ModelArtifactVerificationSettings(),
            verificationReceipts = emptyList(),
            releaseBundle = ReleaseBundleEvidenceV1(
                buildTool = "Gradle",
                javaVersion = "25.0.1",
                gradleVersion = "8.10",
                artifacts = listOf(
                    ReleaseArtifactEvidenceV1(
                        groupId = "dev.tramai",
                        artifactId = "tramai-core",
                        version = "1.0.0",
                        classifier = null,
                        extension = "jar",
                        fileName = "tramai-core-1.0.0.jar",
                        sha256 = "sha256:${"a".repeat(64)}",
                        sizeBytes = 51200,
                    ),
                ),
            ),
        )

        assertThat(pack.releaseBundle).isNotNull()
        assertThat(pack.releaseBundle!!.schemaVersion).isEqualTo(1)
        assertThat(pack.releaseBundle!!.buildTool).isEqualTo("Gradle")
        assertThat(pack.releaseBundle!!.javaVersion).isEqualTo("25.0.1")
        assertThat(pack.releaseBundle!!.gradleVersion).isEqualTo("8.10")
        assertThat(pack.releaseBundle!!.artifacts).hasSize(1)
        assertThat(pack.releaseBundle!!.artifacts[0].groupId).isEqualTo("dev.tramai")
        assertThat(pack.releaseBundle!!.artifacts[0].artifactId).isEqualTo("tramai-core")
        assertThat(pack.releaseBundle!!.artifacts[0].sha256).isEqualTo("sha256:${"a".repeat(64)}")
        assertThat(pack.releaseBundle!!.artifacts[0].sizeBytes).isEqualTo(51200)
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
        releaseBundle = null,
        attestation = null,
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
