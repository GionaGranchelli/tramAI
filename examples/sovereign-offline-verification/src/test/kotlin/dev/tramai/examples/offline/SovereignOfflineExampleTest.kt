package dev.tramai.examples.offline

import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelArtifactVerificationSettings
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.RegisteredModel
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.audit.AuditChainVerifier
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.model.InMemoryModelRegistry
import dev.tramai.security.verification.FileSystemModelArtifactVerifier
import dev.tramai.sovereign.SovereignDeploymentMode
import dev.tramai.sovereign.SovereignProfileConfiguration
import dev.tramai.sovereign.SovereignTramai
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.net.URI

/**
 * Tests for the sovereign offline verification example module.
 */
class SovereignOfflineExampleTest {

    // ── LoopbackModelServer tests ───────────────────────────────────────

    @Test
    fun `loopback server binds to 127-0-0-1 and returns valid response`() {
        val server = LoopbackModelServer()
        try {
            assertThat(server.port).isGreaterThan(0)
            assertThat(server.url).startsWith("http://127.0.0.1:")

            // Send a POST request to the /complete endpoint
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${server.url}/complete"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            assertThat(response.statusCode()).isEqualTo(200)
            assertThat(response.body()).isEqualTo("offline-loopback-echo")
        } finally {
            server.close()
        }
    }

    // ── LoopbackHttpModelProvider tests ────────────────────────────────

    @Test
    fun `loopback provider invokes server and increments counter`() {
        val server = LoopbackModelServer()
        try {
            val provider = LoopbackHttpModelProvider(server.url)

            assertThat(provider.providerId()).isEqualTo("loopback-local-provider")
            assertThat(provider.invocationCount.get()).isEqualTo(0)

            val request = ModelRequest(
                model = "offline-test-model",
                messages = emptyList(),
            )

            val response = runBlocking { provider.complete(request) }

            assertThat(response.content).isEqualTo("offline-loopback-response")
            assertThat(provider.invocationCount.get()).isEqualTo(1)

            // Second call increments again
            runBlocking { provider.complete(request) }
            assertThat(provider.invocationCount.get()).isEqualTo(2)
        } finally {
            server.close()
        }
    }

    // ── ZeroEgressReportWriter tests ───────────────────────────────────

    @Test
    fun `report writer produces valid JSON with no sensitive paths`() {
        val report = ZeroEgressVerificationReportV1(
            deploymentMode = "OFFLINE",
            runtimeBuildSucceeded = true,
            loopbackProviderInvocationSucceeded = true,
            loopbackProviderInvocationCount = 1,
            externalTcpProbeBlocked = true,
            externalDnsProbeBlocked = true,
            configuredProviderZones = mapOf("loopback-local-provider" to "LOCAL"),
            artifactVerificationReceiptCount = 1,
            auditChainValid = true,
        )
        val tempFile = Files.createTempFile("report-test-", ".json")
        try {
            ZeroEgressReportWriter.write(report, tempFile)
            val json = Files.readString(tempFile)

            // Validate JSON structure
            assertThat(json).contains("\"schemaVersion\": 1")
            assertThat(json).contains("\"deploymentMode\": \"OFFLINE\"")
            assertThat(json).contains("\"runtimeBuildSucceeded\": true")
            assertThat(json).contains("\"externalTcpProbeBlocked\": true")
            assertThat(json).contains("\"externalDnsProbeBlocked\": true")
            assertThat(json).contains("\"configuredProviderZones\"")
            assertThat(json).contains("\"loopback-local-provider\": \"LOCAL\"")
            assertThat(json).contains("\"artifactVerificationReceiptCount\": 1")
            assertThat(json).contains("\"auditChainValid\": true")

            // No sensitive path leaks
            assertThat(json).doesNotContain("/home/")
            assertThat(json).doesNotContain("/tmp/")
            assertThat(json).doesNotContain("/Users/")
            assertThat(json).doesNotContain("C:\\")

            // No prompt/token/secret leaks
            assertThat(json).doesNotContain("prompt")
            assertThat(json).doesNotContain("tokens")
            assertThat(json).doesNotContain("secret")
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `report writer creates parent directory`() {
        val tempDir = Files.createTempDirectory("report-parent-test-")
        val nestedPath = tempDir.resolve("subdir").resolve("nested").resolve("report.json")

        try {
            val report = ZeroEgressVerificationReportV1(
                deploymentMode = "OFFLINE",
                runtimeBuildSucceeded = true,
                loopbackProviderInvocationSucceeded = true,
                loopbackProviderInvocationCount = 0,
                externalTcpProbeBlocked = true,
                externalDnsProbeBlocked = true,
                configuredProviderZones = emptyMap(),
                artifactVerificationReceiptCount = 0,
                auditChainValid = true,
            )
            ZeroEgressReportWriter.write(report, nestedPath)

            assertThat(nestedPath).exists()
            assertThat(nestedPath.parent).exists()
        } finally {
            // Cleanup
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `report content has stable key ordering`() {
        val report = ZeroEgressVerificationReportV1(
            deploymentMode = "OFFLINE",
            runtimeBuildSucceeded = true,
            loopbackProviderInvocationSucceeded = true,
            loopbackProviderInvocationCount = 3,
            externalTcpProbeBlocked = false,
            externalDnsProbeBlocked = false,
            configuredProviderZones = mapOf("p1" to "LOCAL"),
            artifactVerificationReceiptCount = 2,
            auditChainValid = true,
        )

        val tempFile = Files.createTempFile("ordering-test-", ".json")
        try {
            ZeroEgressReportWriter.write(report, tempFile)
            val json = Files.readString(tempFile)

            // Verify field order matches data class declaration order
            val schemaIdx = json.indexOf("\"schemaVersion\"")
            val deployIdx = json.indexOf("\"deploymentMode\"")
            val runtimeIdx = json.indexOf("\"runtimeBuildSucceeded\"")
            val loopbackSuccIdx = json.indexOf("\"loopbackProviderInvocationSucceeded\"")
            val loopbackCountIdx = json.indexOf("\"loopbackProviderInvocationCount\"")
            val tcpIdx = json.indexOf("\"externalTcpProbeBlocked\"")
            val dnsIdx = json.indexOf("\"externalDnsProbeBlocked\"")
            val zonesIdx = json.indexOf("\"configuredProviderZones\"")
            val receiptIdx = json.indexOf("\"artifactVerificationReceiptCount\"")
            val auditIdx = json.indexOf("\"auditChainValid\"")

            assertThat(schemaIdx).isLessThan(deployIdx)
            assertThat(deployIdx).isLessThan(runtimeIdx)
            assertThat(runtimeIdx).isLessThan(loopbackSuccIdx)
            assertThat(loopbackSuccIdx).isLessThan(loopbackCountIdx)
            assertThat(loopbackCountIdx).isLessThan(tcpIdx)
            assertThat(tcpIdx).isLessThan(dnsIdx)
            assertThat(dnsIdx).isLessThan(zonesIdx)
            assertThat(zonesIdx).isLessThan(receiptIdx)
            assertThat(receiptIdx).isLessThan(auditIdx)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    // ── Audit chain validation test ─────────────────────────────────────

    @Test
    fun `audit chain validates after loopback invocation`() {
        // Setup
        val artifactContent = "offline-test-model-artifact-content"
        val tempDir = Files.createTempDirectory("audit-test-")
        try {
            val artifactFile = tempDir.resolve("offline-test-model.bin")
            Files.writeString(artifactFile, artifactContent)
            val sizeBytes = Files.size(artifactFile)

            val sha256 = MessageDigest.getInstance("SHA-256")
            val fileDigestHex = sha256.digest(artifactContent.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val fileDigest = ModelArtifactDigest.of("sha256:$fileDigestHex")

            val artifactFileV1 = dev.tramai.core.model.LocalModelArtifactFileV1(
                relativePath = "offline-test-model.bin",
                sizeBytes = sizeBytes,
                digest = fileDigest,
            )

            val manifest = dev.tramai.core.model.LocalModelArtifactManifestV1(
                schemaVersion = 1,
                registryEntryId = "offline-entry",
                providerId = "loopback-local-provider",
                modelName = "offline-test-model",
                revision = "1.0",
                artifacts = listOf(artifactFileV1),
            )

            val manifestDigestHex = MessageDigest.getInstance("SHA-256")
                .digest(manifest.canonicalBytes())
                .joinToString("") { "%02x".format(it) }
            val manifestDigest = ModelArtifactDigest.of("sha256:$manifestDigestHex")

            val registry = InMemoryModelRegistry.builder()
                .register(
                    RegisteredModel(
                        registryEntryId = "offline-entry",
                        providerId = "loopback-local-provider",
                        modelName = "offline-test-model",
                        revision = "1.0",
                        artifactDigest = manifestDigest,
                    ),
                )
                .build()

            val auditStore = InMemoryAuditStore()
            val loopbackServer = LoopbackModelServer()
            try {
                val loopbackProvider = LoopbackHttpModelProvider(loopbackServer.url)
                val verifier = FileSystemModelArtifactVerifier(
                    allowedRootDirectories = setOf(tempDir),
                    manifests = mapOf("offline-entry" to manifest),
                    clock = Clock.systemUTC(),
                )

                val tramai = SovereignTramai.builder()
                    .profile(
                        SovereignProfileConfiguration(
                            allowedModels = setOf("offline-test-model"),
                            allowedProviders = setOf("loopback-local-provider"),
                            providerZones = mapOf("loopback-local-provider" to ProviderTrustZone.LOCAL),
                            deploymentMode = SovereignDeploymentMode.OFFLINE,
                        ),
                    )
                    .modelRegistry(registry)
                    .auditStore(auditStore)
                    .provider(loopbackProvider, name = "loopback-local-provider", default = true)
                    .model("offline-test-model", "loopback-local-provider")
                    .clock(Clock.systemUTC())
                    .modelArtifactVerifier(verifier)
                    .modelArtifactVerificationSettings(
                        ModelArtifactVerificationSettings(enabled = true, requireDigestForLocalModels = true),
                    )
                    .build()

                val runtime = tramai.runtime()
                try {
                    val service = runtime.create(OfflineEchoService::class)
                    runBlocking { service.echo("test-audit-chain") }
                } finally {
                    runtime.close()
                }

                // Read all audit events via reflection helper
                val allEvents = readAllAuditEvents(auditStore)
                assertThat(allEvents).isNotEmpty

                // Validate the audit chain
                val result = AuditChainVerifier.verify(allEvents)
                assertThat(result.isValid)
                    .describedAs("Audit chain must be valid: ${result.errors.joinToString { it.message }}")
                    .isTrue()

            } finally {
                loopbackServer.close()
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    // ── Main flow test ──────────────────────────────────────────────────

    @Test
    fun `main flow works when network probes are blocked`(
        @TempDir tempDir: Path,
    ) {
        val reportPath = tempDir.resolve("test-report.json")

        // Override DNS and TCP blocking — in a test environment these
        // might succeed or fail. We test that the flow completes regardless.
        val result = kotlin.runCatching {
            executeVerification(reportPath)
        }

        // The main flow should succeed. Network probe results are
        // captured in the report but don't cause failure.
        assertThat(result.isSuccess)
            .describedAs("Main flow should complete: ${result.exceptionOrNull()?.message}")
            .isTrue()

        // Verify report was written
        assertThat(reportPath).exists()
        val json = Files.readString(reportPath)
        assertThat(json).contains("\"runtimeBuildSucceeded\": true")
        assertThat(json).contains("\"deploymentMode\": \"OFFLINE\"")
    }
}
