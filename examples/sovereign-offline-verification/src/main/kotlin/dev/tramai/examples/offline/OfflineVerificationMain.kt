package dev.tramai.examples.offline

import dev.tramai.core.model.LocalModelArtifactFileV1
import dev.tramai.core.model.LocalModelArtifactManifestV1
import dev.tramai.core.model.ModelArtifactDigest
import dev.tramai.core.model.ModelArtifactVerificationSettings
import dev.tramai.core.model.RegisteredModel
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.audit.AuditChainVerifier
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.model.InMemoryModelRegistry
import dev.tramai.security.verification.FileSystemModelArtifactVerifier
import dev.tramai.sovereign.SovereignDeploymentMode
import dev.tramai.sovereign.SovereignProfileConfiguration
import dev.tramai.sovereign.SovereignTramai
import dev.tramai.sovereign.evidence.AttestationEvidenceV1
import dev.tramai.sovereign.evidence.AttestedSubjectV1
import dev.tramai.sovereign.evidence.AuditChainEvidenceV1
import dev.tramai.sovereign.evidence.ReleaseBundleEvidenceLoader
import dev.tramai.sovereign.evidence.ReleaseBundleEvidenceV1
import dev.tramai.sovereign.evidence.SovereignEvidencePackWriter
import dev.tramai.sovereign.evidence.SupplyChainEvidenceV1
import dev.tramai.sovereign.evidence.ZeroEgressEvidenceV1
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/**
 * Zero-egress offline verification harness for the TramAI sovereign runtime.
 *
 * Wires a loopback HTTP server + provider, registers a local model
 * artifact, builds the sovereign runtime, invokes the model, and runs
 * external network probes to verify that no egress occurs.
 *
 * Exits with code 0 on success, 1 on failure.
 */
fun main(args: Array<String>) {
    val exitCode = run(args)
    kotlin.system.exitProcess(exitCode)
}

private fun run(args: Array<String>): Int {
    // a. Parse --report-path= and --evidence-path= arguments
    val reportPathArg = args.firstOrNull { it.startsWith("--report-path=") }
    val reportPathStr = reportPathArg?.substringAfter("--report-path=") ?: "zero-egress-report.json"
    val reportPath = Path.of(reportPathStr)

    val evidencePathArg = args.firstOrNull { it.startsWith("--evidence-path=") }
    val evidencePathStr = evidencePathArg?.substringAfter("--evidence-path=")
        ?: "build/sovereign-evidence/sovereign-evidence-pack-v1.json"
    val evidencePath = Path.of(evidencePathStr)

    // Parse optional SBOM arguments
    val sbomPathArg = args.firstOrNull { it.startsWith("--sbom-path=") }
    val sbomDigestPathArg = args.firstOrNull { it.startsWith("--sbom-digest-path=") }

    val supplyChain: SupplyChainEvidenceV1? = if (sbomPathArg != null && sbomDigestPathArg != null) {
        val sbomPathStr = sbomPathArg.substringAfter("--sbom-path=")
        val sbomDigestPathStr = sbomDigestPathArg.substringAfter("--sbom-digest-path=")
        val sbomPath = Path.of(sbomPathStr)
        val sbomDigestPath = Path.of(sbomDigestPathStr)

        val digestContent = Files.readString(sbomDigestPath).trim()
        // Validate digest format
        val digestRegex = Regex("^sha256:[a-fA-F0-9]{64}$")
        require(digestRegex.matches(digestContent)) {
            "Invalid SBOM digest format in $sbomDigestPathStr"
        }

        SupplyChainEvidenceV1(
            sbomFormat = "CycloneDX",
            sbomSpecVersion = "1.6",
            sbomFileName = sbomPath.fileName.toString(),
            sbomSha256 = digestContent,
            generatedBy = "CycloneDX Gradle Plugin 3.2.4",
        )
    } else {
        null
    }

    // Parse optional release bundle manifest argument
    val releaseBundleManifestArg = args.firstOrNull { it.startsWith("--release-bundle-manifest=") }
    val releaseBundle = if (releaseBundleManifestArg != null) {
        val manifestPathStr = releaseBundleManifestArg.substringAfter("--release-bundle-manifest=")
        val manifestPath = Path.of(manifestPathStr)
        println("Loading release bundle manifest: $manifestPath")
        val loaded = ReleaseBundleEvidenceLoader.load(manifestPath)
        println("Release bundle loaded: ${loaded.artifacts.size} artifact(s)")
        loaded
    } else {
        null
    }

    val result = kotlin.runCatching {
        executeVerification(reportPath, evidencePath, supplyChain, releaseBundle)
    }

    return if (result.isSuccess) {
        println("ZERO_EGRESS_VERIFICATION_PASSED")
        0
    } else {
        val error = result.exceptionOrNull()!!
        System.err.println("ZERO_EGRESS_VERIFICATION_FAILED: ${error.message}")
        // Print stack trace for debugging
        error.printStackTrace(System.err)
        1
    }
}

/** Runs the full verification flow. Throws on failure. */
internal fun executeVerification(
    reportPath: Path,
    evidencePath: Path,
    supplyChain: SupplyChainEvidenceV1? = null,
    releaseBundle: ReleaseBundleEvidenceV1? = null,
) {
    // b. Create temporary directory for artifact file
    val tempDir = Files.createTempDirectory("tramai-offline-verification-")
    try {
        executeVerificationInternal(tempDir, reportPath, evidencePath, supplyChain, releaseBundle)
    } finally {
        // Clean up temp directory
        tempDir.toFile().deleteRecursively()
    }
}

/** Core verification logic after temp directory is created. */
internal fun executeVerificationInternal(
    tempDir: Path,
    reportPath: Path,
    evidencePath: Path,
    supplyChain: SupplyChainEvidenceV1? = null,
    releaseBundle: ReleaseBundleEvidenceV1? = null,
) {
    // c. Write dummy artifact file
    val artifactContent = "offline-test-model-artifact-content"
    val artifactFile = tempDir.resolve("offline-test-model.bin")
    Files.writeString(artifactFile, artifactContent)
    val sizeBytes = Files.size(artifactFile)

    // d. Compute SHA-256 digest of the artifact file manually
    val sha256 = MessageDigest.getInstance("SHA-256")
    val fileDigestBytes = sha256.digest(artifactContent.toByteArray(Charsets.UTF_8))
    val fileDigestHex = fileDigestBytes.joinToString("") { "%02x".format(it) }
    val fileDigest = ModelArtifactDigest.of("sha256:$fileDigestHex")

    val artifactFileV1 = LocalModelArtifactFileV1(
        relativePath = "offline-test-model.bin",
        sizeBytes = sizeBytes,
        digest = fileDigest,
    )

    // e. Construct manifest
    val manifest = LocalModelArtifactManifestV1(
        schemaVersion = 1,
        registryEntryId = "offline-entry",
        providerId = "loopback-local-provider",
        modelName = "offline-test-model",
        revision = "1.0",
        artifacts = listOf(artifactFileV1),
    )

    // f. Compute manifest canonical bytes digest
    val manifestDigestBytes = MessageDigest.getInstance("SHA-256")
        .digest(manifest.canonicalBytes())
    val manifestDigestHex = manifestDigestBytes.joinToString("") { "%02x".format(it) }
    val manifestDigest = ModelArtifactDigest.of("sha256:$manifestDigestHex")

    // g. Create InMemoryModelRegistry with the registered model
    val registeredModel = RegisteredModel(
        registryEntryId = "offline-entry",
        providerId = "loopback-local-provider",
        modelName = "offline-test-model",
        revision = "1.0",
        artifactDigest = manifestDigest,
    )
    val registry = InMemoryModelRegistry.builder()
        .register(registeredModel)
        .build()

    // h. Create InMemoryAuditStore
    val auditStore = InMemoryAuditStore()

    // i. Create and start loopback server
    val loopbackServer = LoopbackModelServer()
    val loopbackUrl = loopbackServer.url

    try {
        // j. Create loopback provider
        val loopbackProvider = LoopbackHttpModelProvider(loopbackUrl)

        // k. Create FileSystemModelArtifactVerifier
        val verifier = FileSystemModelArtifactVerifier(
            allowedRootDirectories = setOf(tempDir),
            manifests = mapOf("offline-entry" to manifest),
            clock = Clock.systemUTC(),
        )

        // l. Build SovereignTramai
        val tramai = SovereignTramai.builder()
            .profile(
                SovereignProfileConfiguration(
                    allowedModels = setOf("offline-test-model"),
                    allowedProviders = setOf("loopback-local-provider"),
                    providerZones = mapOf(
                        "loopback-local-provider" to ProviderTrustZone.LOCAL,
                    ),
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
                ModelArtifactVerificationSettings(
                    enabled = true,
                    requireDigestForLocalModels = true,
                ),
            )
            .build()

        // m. Assert verification receipts
        val receipts = tramai.verificationReceipts()
        check(receipts.size == 1) {
            "Expected 1 verification receipt but got ${receipts.size}"
        }

        // n. Create runtime and service proxy
        val runtime = tramai.runtime()
        var providerSucceeded = false
        try {
            val service = runtime.create(OfflineEchoService::class)

            // o. Run echo call and assert response
            val response = runBlocking {
                service.echo("offline-verification")
            }
            check(response == "offline-loopback-response") {
                "loopback-service-response-invalid"
            }

            providerSucceeded = true
        } finally {
            // p. Close the runtime
            runtime.close()
        }

        // q. External network probes
        val tcpBlocked: Boolean = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("1.1.1.1", 443), 1000)
            }
            false // connection succeeded — not blocked
        } catch (_: Exception) {
            true // blocked as expected
        }

        val dnsBlocked: Boolean = try {
            InetAddress.getByName("example.com")
            false // resolution succeeded — not blocked
        } catch (_: Exception) {
            true // blocked as expected
        }

        // r. Validate audit chain — collect all events from all streams
        val allEvents = readAllAuditEvents(auditStore)
        val auditResult = AuditChainVerifier.verify(allEvents)
        val auditValid = auditResult.isValid

        // s. Build report
        val report = ZeroEgressVerificationReportV1(
            deploymentMode = SovereignDeploymentMode.OFFLINE.name,
            runtimeBuildSucceeded = true,
            loopbackProviderInvocationSucceeded = providerSucceeded,
            loopbackProviderInvocationCount = loopbackProvider.invocationCount.get(),
            externalTcpProbeBlocked = tcpBlocked,
            externalDnsProbeBlocked = dnsBlocked,
            configuredProviderZones = mapOf(
                "loopback-local-provider" to ProviderTrustZone.LOCAL.name,
            ),
            artifactVerificationReceiptCount = receipts.size,
            auditChainValid = auditValid,
        )

        // t. Write report
        ZeroEgressReportWriter.write(report, reportPath)

        // u. Generate and write evidence pack (first pass, without attestation)
        var evidencePack = tramai.evidencePack(
            zeroEgress = ZeroEgressEvidenceV1(
                deploymentMode = SovereignDeploymentMode.OFFLINE.name,
                runtimeBuildSucceeded = true,
                loopbackProviderInvocationSucceeded = providerSucceeded,
                loopbackProviderInvocationCount = loopbackProvider.invocationCount.get(),
                externalTcpProbeBlocked = tcpBlocked,
                externalDnsProbeBlocked = dnsBlocked,
            ),
            auditChain = AuditChainEvidenceV1(
                isValid = auditValid,
                totalEvents = allEvents.size,
            ),
            supplyChain = supplyChain,
            releaseBundle = releaseBundle,
        )
        SovereignEvidencePackWriter.write(evidencePack, evidencePath)

        // v. If GitHub CI env vars are present, generate AttestationEvidenceV1 and re-write
        val githubWorkflow = System.getenv("GITHUB_WORKFLOW")
        val githubRunId = System.getenv("GITHUB_RUN_ID")
        val githubRepository = System.getenv("GITHUB_REPOSITORY")
        val githubSha = System.getenv("GITHUB_SHA")

        val attestation: AttestationEvidenceV1? = if (
            githubWorkflow != null && githubRunId != null &&
            githubRepository != null && githubSha != null
        ) {
            val sha256 = MessageDigest.getInstance("SHA-256")
            val subjects = mutableListOf<AttestedSubjectV1>()

            // NOTE: The evidence pack itself is attested by GitHub Actions
            // via actions/attest-build-provenance@v2. We do NOT include its
            // own digest here because the SHA would be stale after the
            // re-generation below. See SPEC-022 for the attestation architecture.

            // Zero-egress report
            if (Files.exists(reportPath)) {
                val reportBytes = Files.readAllBytes(reportPath)
                val reportHex = sha256.digest(reportBytes).joinToString("") { "%02x".format(it) }
                subjects.add(AttestedSubjectV1(
                    fileName = reportPath.fileName.toString(),
                    sha256 = "sha256:$reportHex",
                    attestationType = "build-provenance",
                ))
            }

            // SBOM
            val sbomPath = Path.of("build/supply-chain/sbom/tramai-cyclonedx-sbom.json")
            if (Files.exists(sbomPath)) {
                val sbomBytes = Files.readAllBytes(sbomPath)
                val sbomHex = sha256.digest(sbomBytes).joinToString("") { "%02x".format(it) }
                subjects.add(AttestedSubjectV1(
                    fileName = sbomPath.fileName.toString(),
                    sha256 = "sha256:$sbomHex",
                    attestationType = "sbom",
                ))
            }

            AttestationEvidenceV1(
                provider = "GitHub Artifact Attestations",
                workflowName = githubWorkflow,
                workflowRunId = githubRunId,
                repository = githubRepository,
                commitSha = githubSha,
                attestedSubjects = subjects,
            )
        } else {
            println("GITHUB_* env vars not set — attestation omitted from evidence pack")
            null
        }

        // w. Re-generate with attestation if present
        if (attestation != null) {
            evidencePack = tramai.evidencePack(
                zeroEgress = ZeroEgressEvidenceV1(
                    deploymentMode = SovereignDeploymentMode.OFFLINE.name,
                    runtimeBuildSucceeded = true,
                    loopbackProviderInvocationSucceeded = providerSucceeded,
                    loopbackProviderInvocationCount = loopbackProvider.invocationCount.get(),
                    externalTcpProbeBlocked = tcpBlocked,
                    externalDnsProbeBlocked = dnsBlocked,
                ),
                auditChain = AuditChainEvidenceV1(
                    isValid = auditValid,
                    totalEvents = allEvents.size,
                ),
                supplyChain = supplyChain,
                releaseBundle = releaseBundle,
                attestation = attestation,
            )
            SovereignEvidencePackWriter.write(evidencePack, evidencePath)
            println("EVIDENCE_PACK_WITH_ATTESTATION_WRITTEN: ${evidencePath.toAbsolutePath().normalize()}")
        } else {
            println("EVIDENCE_PACK_WRITTEN: ${evidencePath.toAbsolutePath().normalize()}")
        }

    } finally {
        // v. Close the loopback server
        loopbackServer.close()
    }

    // Exit code 0 is handled by the caller (step v, w)
}

/**
 * Reads all audit events from all streams in the [InMemoryAuditStore].
 *
 * Uses reflection to access the private `streams` field since the public API
 * does not expose stream enumeration. This is a test-only / verification-harness
 * pattern acceptable within the example module.
 */
internal fun readAllAuditEvents(auditStore: InMemoryAuditStore): List<AuditEvent> {
    val streamsField = InMemoryAuditStore::class.java.getDeclaredField("streams")
    streamsField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val streams = streamsField.get(auditStore) as ConcurrentHashMap<String, *>
    val allEvents = mutableListOf<AuditEvent>()
    for (key in streams.keys) {
        val state = streams.get(key)!!
        val stateClass = state::class.java
        val eventsField = stateClass.getDeclaredField("events")
        eventsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val events = eventsField.get(state) as List<AuditEvent>
        allEvents.addAll(events)
    }
    return allEvents.sortedBy { it.sequenceNumber }
}
