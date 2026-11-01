package dev.tramai.spring.sovereign.ops.evidence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.tramai.engine.evidence.ProviderRouteDecisionEvidenceSource
import dev.tramai.engine.evidence.ProviderRouteDecisionKind
import dev.tramai.engine.evidence.ProviderRoutingRuntimeEvidenceExporter
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import dev.tramai.security.evidence.PolicyDecisionRuntimeEvidenceExporter
import dev.tramai.security.evidence.RuntimeEvidenceBundleWriter
import dev.tramai.security.evidence.RuntimeEvidenceRecord
import dev.tramai.security.evidence.ToolPermissionRuntimeEvidenceExporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * End-to-end integration test for runtime evidence bundle wiring.
 *
 * Uses three real exporters to produce [RuntimeEvidenceRecord]s from
 * real source types and writes them into a bundle directory using
 * [RuntimeEvidenceBundleWriter].
 *
 * Also tests the full bundle lifecycle: create → write → finalize →
 * verify → verify determinism.
 */
class RuntimeEvidenceBundleIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUpBundleRoot() {
        createBundleManifest(tempDir)
    }

    private val policyExporter = PolicyDecisionRuntimeEvidenceExporter()
    private val approvalExporter = ApprovalDecisionRuntimeEvidenceExporter()
    private val routingExporter = ProviderRoutingRuntimeEvidenceExporter()
    private val toolPermissionExporter = ToolPermissionRuntimeEvidenceExporter()
    private val bundleWriter = RuntimeEvidenceBundleWriter()
    private val mapper = ObjectMapper()

    private val fixedTimestamp = Instant.parse("2026-07-13T10:00:00Z")

    private fun createBundleManifest(dir: Path) {
        dir.resolve("manifest.json").toFile().writeText(
            """{"bundleType": "sovereign-lab-evidence-bundle", "schemaVersion": 1, "claimBoundary": {}, "requiredFiles": [], "files": []}""",
        )
    }

    @Test
    fun `three real exporters produce complete runtime evidence section`() {
        // Create a real AuditEvent for policy
        val policyEvent =
            AuditEvent(
                schemaVersion = 1,
                hashAlgorithm = AuditHashAlgorithm.SHA_256,
                auditStreamId = "integration-run-001",
                eventId = "int-policy-001",
                sequenceNumber = 1L,
                workflowRunId = "wf-int-001",
                correlationId = "corr-int-001",
                actor = "policy-engine",
                enforcementPoint = "BEFORE_PROVIDER_INVOCATION",
                decision = "ALLOW",
                policyVersion = "v1",
                workflowDigest = "digest-int-001",
                previousEventHash = null,
                eventHash = "hash-int-001",
                timestamp = fixedTimestamp,
                reasonCode = "policy_allowed",
                metadata =
                    mapOf(
                        "providerName" to "ollama",
                        "modelName" to "mistral",
                        "classification" to "low-risk",
                    ),
            )

        // Create a real SovereignOpsAuditOutboxRecord for approval
        val approvalRecord =
            dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord(
                outboxId = "int-approval-001",
                aggregateType = "approval",
                aggregateIdDigest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                operation = "approval-approved.int-approval-001",
                eventKey = "approval-approved.int-approval-001",
                actor = "integration-tester",
                workflowRunId = "wf-int-001",
                correlationId = "corr-int-002",
                approvalStatus = "APPROVED",
                approvalVersion = 1L,
                reasonDigest = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                reasonLength = 29,
                createdAt = fixedTimestamp.plusSeconds(5),
                status = dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus.PENDING,
            )

        // Create a real ProviderRouteDecisionEvidenceSource for routing
        val routeSource =
            ProviderRouteDecisionEvidenceSource(
                eventId = "int-routing-001",
                workflowRunId = "wf-int-001",
                correlationId = "corr-int-003",
                actor = "provider-router",
                createdAt = fixedTimestamp.plusSeconds(10),
                decisionKind = ProviderRouteDecisionKind.SELECTED,
                requestedModelName = "mistral-7b",
                selectedProviderName = "ollama",
                selectedModelName = "mistral-7b",
                routeIndex = 0,
                attempt = 1,
            )

        // Export all three into RuntimeEvidenceRecords
        val policyRecords = policyExporter.export(listOf(policyEvent))
        val approvalRecords = approvalExporter.export(listOf(approvalRecord))
        val routingRecords = routingExporter.export(listOf(routeSource))

        assertEquals(1, policyRecords.size)
        assertEquals(1, approvalRecords.size)
        assertEquals(1, routingRecords.size)

        // Write them into the bundle
        val allRecords = policyRecords + approvalRecords + routingRecords
        val result = bundleWriter.write(tempDir, allRecords)

        // Assert all three JSONL files exist
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("policy-decisions.jsonl")))
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("approval-decisions.jsonl")))
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("provider-routing.jsonl")))

        // Check each file's content for correct eventType and decision kind
        val policyContent =
            Files.readString(
                result.runtimeEvidenceDirectory.resolve("policy-decisions.jsonl"),
            )
        assertTrue(policyContent.contains("\"eventType\":\"policy.decision\""))
        assertTrue(policyContent.contains("\"kind\":\"ALLOW\""))
        // No raw prompt should appear (not even as a metadata key)
        assertFalse(policyContent.contains("prompt"))
        assertFalse(policyContent.contains("toolArg"))

        val approvalContent =
            Files.readString(
                result.runtimeEvidenceDirectory.resolve("approval-decisions.jsonl"),
            )
        assertTrue(approvalContent.contains("\"eventType\":\"approval.decision\""))
        assertTrue(approvalContent.contains("\"kind\":\"APPROVED\""))
        // No raw approval IDs, event keys, comments, or tokens
        assertFalse(approvalContent.contains("approvalToken"))
        assertFalse(approvalContent.contains("comments"))

        val routingContent =
            Files.readString(
                result.runtimeEvidenceDirectory.resolve("provider-routing.jsonl"),
            )
        assertTrue(routingContent.contains("\"eventType\":\"provider.route\""))
        assertTrue(routingContent.contains("\"kind\":\"SELECTED\""))
        // Raw provider/model names must not appear
        assertFalse(routingContent.contains("ollama"))
        assertFalse(routingContent.contains("mistral-7b"))
        assertFalse(routingContent.contains("mistral"))
    }

    @Test
    fun `full sovereign bundle lifecycle with runtime evidence`() {
        // Create the runtime evidence records
        val policyEvent =
            AuditEvent(
                schemaVersion = 1,
                hashAlgorithm = AuditHashAlgorithm.SHA_256,
                auditStreamId = "lifecycle-policy",
                eventId = "lifecycle-policy-001",
                sequenceNumber = 1L,
                workflowRunId = "wf-lifecycle",
                correlationId = "corr-lifecycle",
                actor = "policy-engine",
                enforcementPoint = "BEFORE_PROVIDER_INVOCATION",
                decision = "ALLOW",
                policyVersion = "v1",
                workflowDigest = "digest-lifecycle",
                previousEventHash = null,
                eventHash = "hash-lifecycle",
                timestamp = fixedTimestamp,
                reasonCode = "policy_allowed",
                metadata =
                    mapOf(
                        "providerName" to "ollama",
                        "classification" to "low-risk",
                    ),
            )

        val routeSource =
            ProviderRouteDecisionEvidenceSource(
                eventId = "lifecycle-routing-001",
                workflowRunId = "wf-lifecycle",
                correlationId = "corr-lifecycle",
                actor = "provider-router",
                createdAt = fixedTimestamp.plusSeconds(5),
                decisionKind = ProviderRouteDecisionKind.SELECTED,
                requestedModelName = "mistral-7b",
                selectedProviderName = "ollama",
                selectedModelName = "mistral-7b",
                routeIndex = 0,
                attempt = 1,
            )

        val policyRecords = policyExporter.export(listOf(policyEvent))
        val routingRecords = routingExporter.export(listOf(routeSource))

        // 1. Write runtime evidence into the bundle directory
        val result = bundleWriter.write(tempDir, policyRecords + routingRecords)
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("policy-decisions.jsonl")))
        assertTrue(Files.exists(result.runtimeEvidenceDirectory.resolve("provider-routing.jsonl")))

        // 2. Assert only the two files are present (no approval-decisions)
        assertFalse(Files.exists(result.runtimeEvidenceDirectory.resolve("approval-decisions.jsonl")))

        // 3. Assert the backup dir is cleaned after successful write
        assertFalse(Files.exists(tempDir.resolve("runtime-evidence.bak")))

        // 4. Verify files are non-empty JSONL
        for (filename in listOf("policy-decisions.jsonl", "provider-routing.jsonl")) {
            val file = result.runtimeEvidenceDirectory.resolve(filename)
            assertTrue(Files.size(file) > 0, "$filename must be non-empty")
            val content = Files.readString(file)
            assertTrue(content.endsWith("\n"))
            // Every line is valid JSON
            for (line in content.trimEnd().lines()) {
                assertTrue(line.trim().startsWith("{"))
                assertTrue(line.trim().endsWith("}"))
            }
        }
    }

    private data class ScriptResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val output: String get() = "exitCode: $exitCode\nstdout:\n$stdout\nstderr:\n$stderr"
    }

    private fun findRepositoryRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath().normalize()
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                val finalizeScript = current.resolve("examples/sovereign-lab/finalize-evidence-bundle.sh")
                val verifyScript = current.resolve("examples/sovereign-lab/verify-evidence-bundle.sh")
                assertTrue(
                    Files.isRegularFile(finalizeScript),
                    "finalize-evidence-bundle.sh must exist at: $finalizeScript",
                )
                assertTrue(
                    Files.isRegularFile(verifyScript),
                    "verify-evidence-bundle.sh must exist at: $verifyScript",
                )
                return current
            }
            current = current.parent
        }
        error(
            "Failed to locate repository root containing settings.gradle.kts from ${Path.of("").toAbsolutePath()}",
        )
    }

    private fun runScript(
        scriptPath: Path,
        bundleDir: Path,
        extraArgs: List<String> = emptyList(),
    ): ScriptResult {
        val command =
            listOf("bash", scriptPath.toAbsolutePath().toString()) +
                extraArgs +
                bundleDir.toAbsolutePath().toString()
        val process =
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        assertTrue(completed, "Process timed out executing $scriptPath")
        return ScriptResult(process.exitValue(), stdout, stderr)
    }

    private fun createSovereignLabManifest(
        dir: Path,
        requiredFiles: List<String> = listOf("runtime-evidence/tool-permissions.jsonl"),
    ) {
        val reqFilesJson = requiredFiles.joinToString(separator = ", ") { "\"$it\"" }
        val manifestJson =
            """
            {
              "schemaVersion": 1,
              "bundleType": "sovereign-lab-evidence-bundle",
              "claimBoundary": {
                "localEvidenceScaffold": true,
                "certifiesProductionReadiness": false,
                "definesPerformanceGuarantees": false,
                "runsLocalModel": false,
                "runsBenchmark": false,
                "validatesEvidenceTruth": false
              },
              "requiredFiles": [$reqFilesJson]
            }
            """.trimIndent()
        dir.resolve("manifest.json").toFile().writeText(manifestJson)
    }

    private fun produceAttributedToolPermissionRecord(
        workflowRunId: String = "wf-tool-g1b-001",
        toolName: String = "calculator",
        identityAttributes: Map<String, String> =
            mapOf(
                "identity.workloadId" to "workload-g1b",
                "identity.configurationId" to "config-g1b",
                "identity.configurationVersion" to "v1",
                "identity.environmentId" to "env-g1b",
                "identity.deploymentId" to "dep-g1b",
            ),
        boundaryAttributes: Map<String, String> = emptyMap(),
    ): RuntimeEvidenceRecord {
        val event =
            AuditEvent(
                schemaVersion = 1,
                hashAlgorithm = AuditHashAlgorithm.SHA_256,
                auditStreamId = "stream-tool-g1b",
                eventId = "tool-event-g1b-001",
                sequenceNumber = 1L,
                workflowRunId = workflowRunId,
                correlationId = "corr-tool-001",
                actor = "policy-engine",
                enforcementPoint = "BEFORE_TOOL_EXECUTION",
                decision = "ALLOW",
                policyVersion = "v1",
                workflowDigest = "digest-tool-001",
                previousEventHash = null,
                eventHash = "hash-tool-001",
                timestamp = fixedTimestamp,
                reasonCode = "tool_allowed",
                metadata = mapOf("toolName" to toolName) + identityAttributes + boundaryAttributes,
            )
        return toolPermissionExporter.export(listOf(event)).single()
    }

    private fun mutateToolPermissionsRecord(
        bundleDir: Path,
        mutator: (ObjectNode) -> Unit,
    ) {
        val file = bundleDir.resolve("runtime-evidence/tool-permissions.jsonl")
        val lines = Files.readAllLines(file)
        val mutatedLines =
            lines.map { line ->
                val node = mapper.readTree(line) as ObjectNode
                mutator(node)
                mapper.writeValueAsString(node)
            }
        Files.write(file, mutatedLines)
    }

    @Test
    fun `reviewer pipeline finalizes and verifies sovereign bundle with attributed tool-permission evidence`() {
        val root = findRepositoryRoot()
        val finalizeScript = root.resolve("examples/sovereign-lab/finalize-evidence-bundle.sh")
        val verifyScript = root.resolve("examples/sovereign-lab/verify-evidence-bundle.sh")

        createSovereignLabManifest(tempDir)
        val record = produceAttributedToolPermissionRecord()
        bundleWriter.write(tempDir, listOf(record))

        val finalizeResult = runScript(finalizeScript, tempDir)
        assertEquals(0, finalizeResult.exitCode, "Finalize must exit 0:\n${finalizeResult.output}")
        assertTrue(
            finalizeResult.stdout.contains("Evidence bundle finalized:"),
            "Finalize stdout must report bundle finalized:\n${finalizeResult.output}",
        )

        val verifyResult = runScript(verifyScript, tempDir)
        assertEquals(0, verifyResult.exitCode, "Verify must exit 0:\n${verifyResult.output}")
        assertTrue(
            verifyResult.stdout.contains("Evidence bundle verified:"),
            "Verify stdout must report bundle verified:\n${verifyResult.output}",
        )
    }

    @Test
    fun `reviewer verifier detects on-disk tampering of tool-permission evidence after finalization`() {
        val root = findRepositoryRoot()
        val finalizeScript = root.resolve("examples/sovereign-lab/finalize-evidence-bundle.sh")
        val verifyScript = root.resolve("examples/sovereign-lab/verify-evidence-bundle.sh")

        createSovereignLabManifest(tempDir)
        val record = produceAttributedToolPermissionRecord()
        bundleWriter.write(tempDir, listOf(record))

        val finalizeResult = runScript(finalizeScript, tempDir)
        assertEquals(0, finalizeResult.exitCode, "Finalize must exit 0:\n${finalizeResult.output}")

        // Tamper with tool-permissions.jsonl on disk AFTER finalization
        val toolPermissionsFile = tempDir.resolve("runtime-evidence/tool-permissions.jsonl")
        val originalContent = Files.readString(toolPermissionsFile)
        Files.writeString(toolPermissionsFile, originalContent.replace("calculator", "calculator_tampered"))

        val verifyResult = runScript(verifyScript, tempDir)
        assertNotEquals(0, verifyResult.exitCode, "Verify must fail when evidence is tampered:\n${verifyResult.output}")
        assertTrue(
            verifyResult.stderr.contains("sha256 mismatch for runtime-evidence/tool-permissions.jsonl"),
            "Verify must fail with sha256 mismatch reason:\n${verifyResult.output}",
        )
    }

    @Test
    fun `reviewer verifier rejects tool-permission evidence with partial governed identity`() {
        val root = findRepositoryRoot()
        val finalizeScript = root.resolve("examples/sovereign-lab/finalize-evidence-bundle.sh")
        val verifyScript = root.resolve("examples/sovereign-lab/verify-evidence-bundle.sh")

        createSovereignLabManifest(tempDir)
        val record = produceAttributedToolPermissionRecord()
        bundleWriter.write(tempDir, listOf(record))

        // Drop one of the five reserved identity keys and re-finalize so sha256 matches
        mutateToolPermissionsRecord(tempDir) { node ->
            val meta = node.get("metadata") as ObjectNode
            meta.remove("identity.deploymentId")
        }

        val finalizeResult = runScript(finalizeScript, tempDir)
        assertEquals(0, finalizeResult.exitCode, "Finalize must exit 0:\n${finalizeResult.output}")

        val verifyResult = runScript(verifyScript, tempDir)
        assertNotEquals(0, verifyResult.exitCode, "Verify must reject partial identity:\n${verifyResult.output}")
        assertTrue(
            verifyResult.stderr.contains("partial governed identity attribution"),
            "Verify must fail with partial attribution reason:\n${verifyResult.output}",
        )
        assertTrue(
            verifyResult.stderr.contains("identity.deploymentId"),
            "Verify must list the missing reserved key:\n${verifyResult.output}",
        )
    }

    @Test
    fun `reviewer verifier rejects attributed tool-permission evidence with missing or blank workflowRunId`() {
        val root = findRepositoryRoot()
        val finalizeScript = root.resolve("examples/sovereign-lab/finalize-evidence-bundle.sh")
        val verifyScript = root.resolve("examples/sovereign-lab/verify-evidence-bundle.sh")

        createSovereignLabManifest(tempDir)
        val record = produceAttributedToolPermissionRecord()
        bundleWriter.write(tempDir, listOf(record))

        // Blank workflowRunId and re-finalize so sha256 matches
        mutateToolPermissionsRecord(tempDir) { node ->
            node.put("workflowRunId", "")
        }

        val finalizeResult = runScript(finalizeScript, tempDir)
        assertEquals(0, finalizeResult.exitCode, "Finalize must exit 0:\n${finalizeResult.output}")

        val verifyResult = runScript(verifyScript, tempDir)
        assertNotEquals(0, verifyResult.exitCode, "Verify must reject blank run id:\n${verifyResult.output}")
        assertTrue(
            verifyResult.stderr.contains("missing or blank canonical workflowRunId for governed identity attribution"),
            "Verify must fail naming missing/blank canonical run id:\n${verifyResult.output}",
        )
    }

    @Test
    fun `reviewer verifier rejects tool-permission evidence with unallowlisted metadata key`() {
        val root = findRepositoryRoot()
        val finalizeScript = root.resolve("examples/sovereign-lab/finalize-evidence-bundle.sh")
        val verifyScript = root.resolve("examples/sovereign-lab/verify-evidence-bundle.sh")

        createSovereignLabManifest(tempDir)
        val record = produceAttributedToolPermissionRecord()
        bundleWriter.write(tempDir, listOf(record))

        // Inject unallowlisted metadata key and re-finalize so sha256 matches
        mutateToolPermissionsRecord(tempDir) { node ->
            val meta = node.get("metadata") as ObjectNode
            meta.put("unallowlistedCustomKey", "disallowed")
        }

        val finalizeResult = runScript(finalizeScript, tempDir)
        assertEquals(0, finalizeResult.exitCode, "Finalize must exit 0:\n${finalizeResult.output}")

        val verifyResult = runScript(verifyScript, tempDir)
        assertNotEquals(
            0,
            verifyResult.exitCode,
            "Verify must reject unallowlisted metadata key:\n${verifyResult.output}",
        )
        assertTrue(
            verifyResult.stderr.contains(
                "metadata key 'unallowlistedCustomKey' is not allowlisted for tool.permission",
            ),
            "Verify must fail with not allowlisted reason:\n${verifyResult.output}",
        )
    }

    // ── G2: registered enforcement-boundary provenance through the reviewer verifier ──────────

    @Test
    fun `reviewer verifier accepts a registered boundary's provenance and the legacy control`() {
        val root = findRepositoryRoot()
        val finalizeScript = root.resolve("examples/sovereign-lab/finalize-evidence-bundle.sh")
        val verifyScript = root.resolve("examples/sovereign-lab/verify-evidence-bundle.sh")

        createSovereignLabManifest(tempDir)
        bundleWriter.write(
            tempDir,
            listOf(produceAttributedToolPermissionRecord(boundaryAttributes = registeredBoundary)),
        )
        assertEquals(0, runScript(finalizeScript, tempDir).exitCode, "Finalize must exit 0")

        val attributed = runScript(verifyScript, tempDir)
        assertEquals(
            0,
            attributed.exitCode,
            "the reviewer verifier must accept a registered boundary's provenance:\n${attributed.output}",
        )
        assertTrue(
            attributed.stdout.contains("Evidence bundle verified:"),
            "the verifier must report the bundle verified:\n${attributed.output}",
        )

        // Control: the same record without provenance is pre-G2 legacy evidence and must still verify.
        createSovereignLabManifest(tempDir)
        bundleWriter.write(tempDir, listOf(produceAttributedToolPermissionRecord()))
        assertEquals(0, runScript(finalizeScript, tempDir).exitCode, "Finalize must exit 0")

        val legacy = runScript(verifyScript, tempDir)
        assertEquals(
            0,
            legacy.exitCode,
            "legacy evidence without boundary provenance must still verify:\n${legacy.output}",
        )
    }

    @Test
    fun `a pre-g1 two-key bundle verifies only in legacy mode and never as attested`() {
        val root = findRepositoryRoot()
        val finalizeScript = root.resolve("examples/sovereign-lab/finalize-evidence-bundle.sh")
        val verifyScript = root.resolve("examples/sovereign-lab/verify-evidence-bundle.sh")

        createSovereignLabManifest(tempDir)
        bundleWriter.write(
            tempDir,
            listOf(produceAttributedToolPermissionRecord(boundaryAttributes = registeredBoundary)),
        )
        // The shape a 0.7.4-era writer produced: identifier and strength, no origin axis.
        mutateToolPermissionsRecord(tempDir) { node -> node.metadataNode().remove("boundary.origin") }
        assertEquals(0, runScript(finalizeScript, tempDir).exitCode, "Finalize must exit 0")

        // Default policy: a two-key tuple is partial provenance, and the refusal must say so.
        val strict = runScript(verifyScript, tempDir)
        assertNotEquals(0, strict.exitCode, "a two-key bundle must not verify under the current policy")
        assertTrue(
            strict.stderr.contains("partial enforcement-boundary provenance"),
            "the refusal must name the rule:\n${strict.output}",
        )

        // Historical reading mode: the bundle stays verifiable, and its strength is labelled for what
        // it is -- registration-derived, not attested.
        val legacy = runScript(verifyScript, tempDir, listOf("--legacy-provenance"))
        assertEquals(
            0,
            legacy.exitCode,
            "a pre-g1 bundle must remain verifiable in legacy mode:\n${legacy.output}",
        )
        assertTrue(
            legacy.stderr.contains("registration-derived, not attested"),
            "legacy mode must say the strength was never attested:\n${legacy.output}",
        )
        assertTrue(
            legacy.stdout.contains("Evidence bundle verified:"),
            "legacy mode must still report the bundle verified:\n${legacy.output}",
        )

        // Legacy reading relaxes the tuple shape, not the registration: an identifier no contract covers
        // must stay refused, or the old format becomes a bypass for unknown boundaries.
        createSovereignLabManifest(tempDir)
        bundleWriter.write(
            tempDir,
            listOf(produceAttributedToolPermissionRecord(boundaryAttributes = registeredBoundary)),
        )
        mutateToolPermissionsRecord(tempDir) { node ->
            node.metadataNode().remove("boundary.origin")
            node.metadataNode().put("boundary.id", "some-other-adapter")
        }
        assertEquals(0, runScript(finalizeScript, tempDir).exitCode, "Finalize must exit 0")

        val unknownInLegacyMode = runScript(verifyScript, tempDir, listOf("--legacy-provenance"))
        assertNotEquals(
            0,
            unknownInLegacyMode.exitCode,
            "legacy mode must not accept an unregistered boundary:\n${unknownInLegacyMode.output}",
        )
        assertTrue(
            unknownInLegacyMode.stderr.contains("unregistered enforcement boundary 'some-other-adapter'"),
            "legacy mode must still apply the registry:\n${unknownInLegacyMode.output}",
        )
    }

    @Test
    fun `reviewer verifier refuses boundary provenance the registration does not cover`() {
        val root = findRepositoryRoot()
        val finalizeScript = root.resolve("examples/sovereign-lab/finalize-evidence-bundle.sh")
        val verifyScript = root.resolve("examples/sovereign-lab/verify-evidence-bundle.sh")

        val cases =
            listOf<Triple<String, (ObjectNode) -> Unit, String>>(
                Triple(
                    "an unregistered boundary identifier",
                    { node -> node.metadataNode().put("boundary.id", "some-other-adapter") },
                    "unregistered enforcement boundary 'some-other-adapter'",
                ),
                Triple(
                    "a strength above the declaration",
                    { node -> node.metadataNode().put("boundary.enforcementStrength", "AUTHORITATIVE") },
                    "cannot claim 'AUTHORITATIVE'",
                ),
                Triple(
                    "a caller-asserted attested origin",
                    { node -> node.metadataNode().put("boundary.origin", "attested") },
                    "has no attester in this runtime",
                ),
                Triple(
                    "partial provenance",
                    { node -> node.metadataNode().remove("boundary.enforcementStrength") },
                    "partial enforcement-boundary provenance",
                ),
                Triple(
                    "a pre-g1 two-key provenance tuple",
                    { node -> node.metadataNode().remove("boundary.origin") },
                    "partial enforcement-boundary provenance",
                ),
                Triple(
                    "a registration applied at the wrong enforcement point",
                    { node -> node.metadataNode().put("enforcementPoint", "BEFORE_TOOL_EXPOSURE") },
                    "is registered for BEFORE_TOOL_EXECUTION",
                ),
            )

        cases.forEach { (label, mutation, expectedReason) ->
            createSovereignLabManifest(tempDir)
            bundleWriter.write(
                tempDir,
                listOf(produceAttributedToolPermissionRecord(boundaryAttributes = registeredBoundary)),
            )
            mutateToolPermissionsRecord(tempDir, mutation)
            // Re-finalize so the digests match and the refusal can only be the provenance rule.
            assertEquals(0, runScript(finalizeScript, tempDir).exitCode, "Finalize must exit 0 for: $label")

            val verifyResult = runScript(verifyScript, tempDir)
            assertNotEquals(
                0,
                verifyResult.exitCode,
                "the verifier must refuse $label:\n${verifyResult.output}",
            )
            assertTrue(
                verifyResult.stderr.contains(expectedReason),
                "the refusal for $label must name the rule, expected '$expectedReason':\n${verifyResult.output}",
            )
        }
    }

    private fun ObjectNode.metadataNode(): ObjectNode = get("metadata") as ObjectNode

    /**
     * The declared boundary identifier only. Strength and origin are deliberately absent: the framework
     * emits `DECLARED` plus origin `declared` from the registration, and refuses a caller-supplied value
     * for either.
     */
    private val registeredBoundary = mapOf("boundary.id" to "spring-ai-tool-callback")
}
