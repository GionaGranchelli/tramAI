package dev.tramai.security.evidence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.InMemoryAuditStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * G1b: the governed identity reaches `tool.permission` evidence and the canonical digest binds it.
 *
 * The chain under test is the repository's own, not a reimplementation of it: the authoritative
 * evaluator is the real [DefaultPolicyEngine], the audit event comes from the real
 * [AuditEnginePolicyDecisionAuditEmitter] through the real [AuditEngine], and the record comes from
 * the production [ToolPermissionRuntimeEvidenceExporter]. Nothing is stubbed out of the path whose
 * behaviour these tests claim.
 *
 * The identity is not hand-built here: it is a typed [GovernedRunIdentity] translated by
 * [GovernedIdentityAttribution], the same production translation the governance-decision evidence
 * uses. A test that pasted its own literal tuple into a record would pass while the transport was
 * broken — which is the failure this suite exists to detect, and the reason the adversarial
 * discriminator can remove the production transfer and make these tests fail.
 *
 * The pair of legacy/attributed assertions is the discriminating property: an implementation that
 * carries nothing passes the legacy case and fails the attributed case, and one that attributes
 * everything passes the attributed case and fails the legacy case.
 */
class ToolPermissionEvidenceAttributionTest {
    private val exporter = ToolPermissionRuntimeEvidenceExporter()

    // ── attribution reaches the exported record ──────────────────────────────

    @Test
    fun `a governed tool decision reaches exported evidence with its complete identity`() {
        val identity = governedIdentity()
        val context = policyContext(identity)

        val decision = evaluate(context)
        // Reaching a decision is the proof that the evaluator accepted the tuple as governed: a
        // partial or run-less tuple is refused before any enforcement-point branch is entered.
        assertTrue(decision is PolicyDecision.Deny, "the real evaluator decided on the tool")

        val record = exporter.export(listOf(emit(context, decision))).single()

        assertEquals(identity.runId.value, record.workflowRunId, "the canonical run id reaches the record")
        assertEquals(
            GovernedIdentityAttribution.of(identity),
            record.metadata.filterKeys { it in RuntimeEvidenceAttribution.metadataKeys },
            "the complete deployment tuple reaches the record, key for key and value for value",
        )
        assertEquals(
            FAMILY_ALLOWLIST + RuntimeEvidenceAttribution.metadataKeys,
            record.metadata.keys,
            "the record carries the family allowlist plus exactly the reserved identity keys",
        )
    }

    @Test
    fun `the canonical digest binds the attributed identity`() {
        val identity = governedIdentity()
        val context = policyContext(identity)
        val event = emit(context, evaluate(context))

        val record = exporter.export(listOf(event)).single()
        val shown = record.metadata
        val withoutIdentity = shown - RuntimeEvidenceAttribution.metadataKeys

        assertEquals(
            event.computeToolPermissionPayloadDigest(shown),
            record.digests.payloadDigest,
            "the advertised digest is the digest of the payload the record actually shows",
        )
        assertNotEquals(
            event.computeToolPermissionPayloadDigest(withoutIdentity),
            record.digests.payloadDigest,
            "removing the identity changes the digest, so the identity is bound and not incidental",
        )
    }

    @Test
    fun `the digest distinguishes two runs that differ only by identity`() {
        // Same event shape, same decision, same tool: only the governed identity differs. If the
        // digest did not cover the identity these two records would be indistinguishable evidence.
        val first = govern(governedIdentity())
        val second = govern(governedIdentity(deploymentId = "deployment-g1b-2"))

        assertNotEquals(
            first.digests.payloadDigest,
            second.digests.payloadDigest,
            "evidence for two deployments must not share a payload digest",
        )
        assertEquals(first.metadata.keys, second.metadata.keys, "the shape is identical; only values differ")
    }

    // ── the canonical run id is bound, not merely carried ────────────────────

    @Test
    fun `the canonical payload digest binds the canonical run id`() {
        // Everything except the canonical run id is deliberately identical: same five deployment
        // fields, same decision, same reason code, same tool. If the digest did not bind the run id
        // these two records would be interchangeable evidence for two different runs.
        val first = govern(governedIdentity(runId = "run-g1b-first"))
        val second = govern(governedIdentity(runId = "run-g1b-second"))

        assertEquals(first.metadata, second.metadata, "the five deployment fields are deliberately identical")
        assertEquals(first.decision.kind, second.decision.kind, "the decision is deliberately identical")
        assertNotEquals(first.workflowRunId, second.workflowRunId, "only the canonical run id differs")

        assertNotEquals(
            first.digests.payloadDigest,
            second.digests.payloadDigest,
            "the canonical payload digest must bind the run of the evidence it describes",
        )
    }

    @Test
    fun `legacy evidence keeps the digest it had before run-id binding existed`() {
        // Legacy evidence gains no run-id term: two ungoverned records differing only by their run id
        // keep an identical payload digest, which is exactly the behaviour they had before G1b.
        // Without this control, "bind the run id" could be satisfied by silently re-digesting every
        // record in the repository.
        val first = legacyRecord(runId = "run-legacy-a")
        val second = legacyRecord(runId = "run-legacy-b")

        assertNotEquals(first.workflowRunId, second.workflowRunId, "the two runs are genuinely different")
        assertTrue(
            first.metadata.keys.none { it.startsWith("identity.") },
            "both are legacy records: ${first.metadata.keys}",
        )
        assertEquals(
            first.digests.payloadDigest,
            second.digests.payloadDigest,
            "an ungoverned record's canonical payload digest is unchanged by the run id",
        )
    }

    // ── fail closed, never downgrade ─────────────────────────────────────────

    @Test
    fun `a partial identity tuple in the audit event is refused rather than downgraded`() {
        val identity = governedIdentity()
        // The decision is produced by the real evaluator from a complete tuple; the event is then
        // emitted from a context carrying only four of the five keys, which is the corruption shape.
        val decision = evaluate(policyContext(identity))
        val partial = GovernedIdentityAttribution.of(identity) - "identity.deploymentId"
        val event = emit(policyContext(identity, attributes = partial), decision)

        assertEquals(
            4,
            event.metadata.keys.count { it in RuntimeEvidenceAttribution.metadataKeys },
            "the fixture really does carry a partial tuple, so the refusal below is not vacuous",
        )
        val failure = assertFailsWith<IllegalStateException> { exporter.export(listOf(event)) }

        assertTrue(
            failure.message!!.contains("partial governed attribution"),
            "the refusal names the cause rather than silently recording a downgrade: ${failure.message}",
        )
    }

    @Test
    fun `a complete identity without the canonical run id is refused`() {
        val identity = governedIdentity()
        val decision = evaluate(policyContext(identity))
        val runLess = policyContext(identity).copy(workflowRunId = null)
        val event = emit(runLess, decision)

        assertEquals(
            RuntimeEvidenceAttribution.metadataKeys.size,
            event.metadata.keys.count { it in RuntimeEvidenceAttribution.metadataKeys },
            "the tuple is complete; only the canonical run id is absent",
        )
        val failure = assertFailsWith<IllegalStateException> { exporter.export(listOf(event)) }

        assertTrue(
            failure.message!!.contains("without the canonical workflowRunId"),
            "the refusal names the cause: ${failure.message}",
        )
    }

    // ── legacy evidence and the family allowlist are unchanged ───────────────

    @Test
    fun `legacy evidence without identity still exports unchanged`() {
        val context = policyContext(governedIdentity(), attributes = emptyMap())
        val event = emit(context, evaluate(policyContext(governedIdentity(), attributes = emptyMap())))

        val record = exporter.export(listOf(event)).single()

        assertTrue(
            record.metadata.keys.none { it.startsWith("identity.") },
            "an ungoverned event gains no identity keys: ${record.metadata.keys}",
        )
        assertEquals(
            FAMILY_ALLOWLIST,
            record.metadata.keys,
            "the legacy record is exactly the family allowlist, so zero keys is still valid evidence",
        )
        assertEquals(
            event.computeToolPermissionPayloadDigest(record.metadata),
            record.digests.payloadDigest,
            "the legacy digest is computed over exactly the payload the record shows",
        )
    }

    @Test
    fun `only the reserved keys are read as identity, and the family allowlist is not widened`() {
        val identity = governedIdentity()
        // A caller-supplied near-miss key and an unknown key, both present in the event metadata.
        val event =
            emit(
                policyContext(
                    identity,
                    attributes =
                        GovernedIdentityAttribution.of(identity) +
                            mapOf("identity.workload" to "spoofed", "secret" to "should-not-survive"),
                ),
                evaluate(policyContext(identity)),
            )

        val record = exporter.export(listOf(event)).single()

        assertEquals(
            FAMILY_ALLOWLIST + RuntimeEvidenceAttribution.metadataKeys,
            record.metadata.keys,
            "a near-miss identity key and an unknown key are both filtered, not promoted",
        )
        assertEquals(
            GovernedIdentityAttribution.of(identity),
            record.metadata.filterKeys { it in RuntimeEvidenceAttribution.metadataKeys },
            "the reserved keys are exact: a lookalike neither substitutes for nor augments the identity",
        )
    }

    // ── authoritative identity length bounds (Finding 1) ─────────────────

    @Test
    fun `an overlength reserved identity value is refused rather than silently truncated`() {
        val identity = governedIdentity()
        val baseAttributes = GovernedIdentityAttribution.of(identity)

        // Two complete policy contexts differing ONLY after the 256th character of identity.deploymentId.
        val overlengthA = "x".repeat(256) + "A"
        val overlengthB = "x".repeat(256) + "B"
        val contextA = policyContext(identity, attributes = baseAttributes + ("identity.deploymentId" to overlengthA))
        val contextB = policyContext(identity, attributes = baseAttributes + ("identity.deploymentId" to overlengthB))

        // Both are accepted by the real policy engine's presence-based validation and evaluated.
        val decisionA = evaluate(contextA)
        val decisionB = evaluate(contextB)
        assertTrue(decisionA is PolicyDecision.Deny)
        assertTrue(decisionB is PolicyDecision.Deny)

        // The real emitter must fail closed and refuse overlength reserved identity values,
        // rather than silently truncating both to 256 chars and mapping two distinct identities
        // onto identical evidence with identical payload digests.
        val failureA = assertFailsWith<IllegalArgumentException> { emit(contextA, decisionA) }
        assertTrue(
            failureA.message!!.contains("identity.deploymentId") &&
                failureA.message!!.contains("256") &&
                failureA.message!!.contains("length"),
            "refusal names the offending key, bound, and length attribute: ${failureA.message}",
        )

        val failureB = assertFailsWith<IllegalArgumentException> { emit(contextB, decisionB) }
        assertTrue(
            failureB.message!!.contains("identity.deploymentId") &&
                failureB.message!!.contains("256") &&
                failureB.message!!.contains("length"),
            "refusal names the offending key, bound, and length attribute: ${failureB.message}",
        )
    }

    @Test
    fun `a 256-character identity value is preserved intact and exported unchanged byte-for-byte`() {
        val identity = governedIdentity()
        val baseAttributes = GovernedIdentityAttribution.of(identity)
        val exact256 = "x".repeat(256)
        val contextExact = policyContext(identity, attributes = baseAttributes + ("identity.deploymentId" to exact256))

        val decisionExact = evaluate(contextExact)
        val eventExact = emit(contextExact, decisionExact)

        // Boundary control: a value of exactly 256 characters is accepted and
        // preserved byte-for-byte in the audit event.
        assertEquals(
            exact256,
            eventExact.metadata["identity.deploymentId"],
            "a 256-character identity value is preserved byte-for-byte in the audit event",
        )

        // The exporter also preserves identity values exactly without truncation.
        val recordExact = exporter.export(listOf(eventExact)).single()
        assertEquals(
            exact256,
            recordExact.metadata["identity.deploymentId"],
            "the exporter preserves the 256-character identity value without truncation",
        )
    }

    // ── attributed bundle round-trip and tamper detection (Finding 2) ────────

    /**
     * Proves that real attributed tool.permission records (covering ALLOW, REQUIRE_APPROVAL, and DENY)
     * produced by the real evaluator/emitter/exporter can be bundled by [RuntimeEvidenceBundleWriter],
     * and that all reserved identity keys and digests survive serialization intact upon re-reading the joined file.
     */
    @Test
    fun `complete real tool-permission evidence is bundled across ALLOW, REQUIRE_APPROVAL and DENY and survives intact`(
        @TempDir bundleDir: Path,
    ) {
        val identity = governedIdentity()
        val writer = RuntimeEvidenceBundleWriter()
        createBundleManifest(bundleDir)

        // 1. DENY: produced by the real DefaultPolicyEngine rejecting an unknown tool under default configuration
        val denyContext = policyContext(identity).copy(toolName = "ledger.append")
        val denyRecord = produceAttributedRecord(denyContext, PolicyConfiguration())
        assertEquals("DENY", denyRecord.decision.kind)

        // 2. ALLOW: produced by the real DefaultPolicyEngine permitting an allowed tool
        val allowConfig =
            PolicyConfiguration(
                allowedTools = setOf("calculator"),
                allowLegacyToolsWithoutSecurityMetadata = true,
            )
        val allowContext = policyContext(identity).copy(toolName = "calculator")
        val allowRecord = produceAttributedRecord(allowContext, allowConfig)
        assertEquals("ALLOW", allowRecord.decision.kind)

        // 3. REQUIRE_APPROVAL: produced by the real DefaultPolicyEngine requiring human approval for high risk
        val approvalRecord = produceRequireApprovalRecord(identity)
        assertEquals("REQUIRE_APPROVAL", approvalRecord.decision.kind)

        val records = listOf(denyRecord, allowRecord, approvalRecord)

        // All three records carry complete attribution before bundling
        for (r in records) {
            assertEquals(identity.runId.value, r.workflowRunId)
            assertEquals(
                GovernedIdentityAttribution.of(identity),
                r.metadata.filterKeys { it in RuntimeEvidenceAttribution.metadataKeys },
            )
        }

        // Write into the sovereign lab bundle directory
        val writeResult = writer.write(bundleDir, records)
        val toolPermissionsFile = writeResult.runtimeEvidenceDirectory.resolve("tool-permissions.jsonl")
        assertTrue(Files.exists(toolPermissionsFile), "tool-permissions.jsonl must exist in the bundle")
        assertEquals(3, writeResult.countsByEventType["tool.permission"])

        // Re-read the joined file and assert every record's identity and digests survived intact
        assertBundledRecordsSurviveIntact(toolPermissionsFile, records)
    }

    /**
     * Proves that modifications to attributed tool-permission evidence are detected by the repository's
     * production verifiers ([RuntimeEvidenceContractValidator] and [RuntimeEvidenceBundleWriter]).
     *
     * LIMITATION NOTE:
     * The repository's production in-tree verifier for runtime evidence is [RuntimeEvidenceContractValidator],
     * which verifies contract adherence and attribution invariants in memory (atomic attribution presence,
     * canonical workflowRunId requirement, metadata allowlists, digest shapes).
     * The repository has no production JVM bundle verifier that performs cryptographic file hash (SHA-256)
     * integrity checking of on-disk `tool-permissions.jsonl` files against `manifest.json`.
     * (The shell verifier `examples/sovereign-lab/verify-evidence-bundle.sh` verifies file SHA-256 against
     * `manifest.json["files"]` for sovereign lab bundles, but only allowlists un-attributed metadata keys
     * and therefore rejects attributed records during structural validation).
     * Therefore, within the JVM evidence pipeline, integrity verification operates via
     * [RuntimeEvidenceContractValidator] and [RuntimeEvidenceBundleWriter], which refuse corrupt attribution
     * tuples, missing run IDs, unallowlisted keys, and invalid manifests.
     */
    @Test
    fun `tampered attributed tool-permission evidence is detected by production contract verification`(
        @TempDir bundleDir: Path,
    ) {
        val identity = governedIdentity()
        val writer = RuntimeEvidenceBundleWriter()
        createBundleManifest(bundleDir)

        val validRecord =
            produceAttributedRecord(
                policyContext(identity).copy(toolName = "ledger.append"),
                PolicyConfiguration(),
            )

        // Baseline: valid record passes contract validator and bundle writer
        RuntimeEvidenceContractValidator.validate(listOf(validRecord))
        writer.write(bundleDir, listOf(validRecord))

        assertAttributionTamperDetected(validRecord, bundleDir, writer)
        assertMetadataAndManifestTamperDetected(validRecord, bundleDir, writer)
    }

    private fun assertAttributionTamperDetected(
        validRecord: RuntimeEvidenceRecord,
        bundleDir: Path,
        writer: RuntimeEvidenceBundleWriter,
    ) {
        // Tamper 1: attribution corruption - stripping one reserved identity key (partial tuple)
        val tamperedAttribution =
            validRecord.copy(
                metadata = validRecord.metadata - "identity.deploymentId",
            )
        val partialEx =
            assertFailsWith<IllegalStateException> {
                RuntimeEvidenceContractValidator.validate(listOf(tamperedAttribution))
            }
        assertTrue(
            partialEx.message!!.contains("partial governed attribution"),
            "validator detects partial attribution: ${partialEx.message}",
        )
        val writerPartialEx =
            assertFailsWith<IllegalStateException> {
                writer.write(bundleDir, listOf(tamperedAttribution))
            }
        assertTrue(
            writerPartialEx.message!!.contains("partial governed attribution"),
            "writer refuses partial attribution: ${writerPartialEx.message}",
        )

        // Tamper 2: attribution without canonical run ID
        val tamperedRunId = validRecord.copy(workflowRunId = null)
        val runIdEx =
            assertFailsWith<IllegalStateException> {
                RuntimeEvidenceContractValidator.validate(listOf(tamperedRunId))
            }
        assertTrue(
            runIdEx.message!!.contains("attribution without the canonical workflowRunId"),
            "validator detects missing run ID: ${runIdEx.message}",
        )
    }

    private fun assertMetadataAndManifestTamperDetected(
        validRecord: RuntimeEvidenceRecord,
        bundleDir: Path,
        writer: RuntimeEvidenceBundleWriter,
    ) {
        // Tamper 3: metadata corruption - injecting unallowlisted metadata key
        val tamperedMetadata =
            validRecord.copy(
                metadata = validRecord.metadata + ("unallowlistedKey" to "injectedValue"),
            )
        val metaEx =
            assertFailsWith<IllegalArgumentException> {
                RuntimeEvidenceContractValidator.validate(listOf(tamperedMetadata))
            }
        assertTrue(
            metaEx.message!!.contains("Metadata key 'unallowlistedKey' is not allowlisted"),
            "validator detects unallowlisted metadata: ${metaEx.message}",
        )

        // Tamper 4: manifest.json corruption
        bundleDir.resolve("manifest.json").toFile().writeText("""{"bundleType": "untrusted-bundle"}""")
        val manifestEx =
            assertFailsWith<IllegalArgumentException> {
                writer.write(bundleDir, listOf(validRecord))
            }
        assertTrue(
            manifestEx.message!!.contains("manifest.json must declare bundleType"),
            "writer refuses untrusted manifest: ${manifestEx.message}",
        )
    }

    private fun assertBundledRecordsSurviveIntact(
        toolPermissionsFile: Path,
        expectedRecords: List<RuntimeEvidenceRecord>,
    ) {
        val lines = Files.readAllLines(toolPermissionsFile)
        assertEquals(expectedRecords.size, lines.size, "records written to tool-permissions.jsonl")

        val mapper = ObjectMapper()
        val parsedNodes = lines.map { mapper.readTree(it) }
        val sortedExpected =
            expectedRecords.sortedWith(
                compareBy(RuntimeEvidenceRecord::createdAt, RuntimeEvidenceRecord::eventId),
            )

        for (i in sortedExpected.indices) {
            val expected = sortedExpected[i]
            val node = parsedNodes[i]

            assertEquals("runtime-evidence.v1", node.get("schemaVersion").asText())
            assertEquals("tool.permission", node.get("eventType").asText())
            assertEquals(expected.eventId, node.get("eventId").asText())
            assertEquals(expected.workflowRunId, node.get("workflowRunId").asText())
            assertEquals(expected.decision.kind, node.get("decision").get("kind").asText())
            assertEquals(expected.decision.reasonCode, node.get("decision").get("reasonCode").asText())
            assertEquals(expected.digests.subjectDigest, node.get("digests").get("subjectDigest").asText())
            assertEquals(expected.digests.payloadDigest, node.get("digests").get("payloadDigest").asText())

            val metaNode = node.get("metadata")
            for (key in RuntimeEvidenceAttribution.metadataKeys) {
                assertEquals(
                    expected.metadata[key],
                    metaNode.get(key).asText(),
                    "Reserved identity key '$key' must survive serialization intact, key for key and value for value",
                )
            }
        }
    }

    private fun produceRequireApprovalRecord(identity: GovernedRunIdentity): RuntimeEvidenceRecord {
        val approvalToolSecurity =
            ToolSecurityMetadata(
                permission = "tool.transfer",
                risk = RiskLevel.HIGH,
                approval = ApprovalMode.HUMAN_REQUIRED,
                managedNetworkEgress = ManagedNetworkEgress.ALLOWLIST_ONLY,
                audit = AuditDetail.FULL,
            )
        val approvalConfig =
            PolicyConfiguration(
                allowedTools = setOf("funds.transfer"),
                allowedPermissions = setOf("tool.transfer"),
                requireApprovalForRiskLevel = setOf(RiskLevel.HIGH),
            )
        val approvalContext =
            policyContext(identity).copy(
                toolName = "funds.transfer",
                toolSecurity = approvalToolSecurity,
            )
        return produceAttributedRecord(approvalContext, approvalConfig)
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun produceAttributedRecord(
        context: PolicyContext,
        config: PolicyConfiguration = PolicyConfiguration(),
    ): RuntimeEvidenceRecord {
        val decision = runBlocking { DefaultPolicyEngine(config).evaluate(context) }
        val event = emit(context, decision)
        return exporter.export(listOf(event)).single()
    }

    private fun createBundleManifest(dir: Path) {
        val manifestJson =
            """{"bundleType": "sovereign-lab-evidence-bundle", "schemaVersion": 1, """ +
                """"claimBoundary": {}, "requiredFiles": [], "files": []}"""
        dir.resolve("manifest.json").toFile().writeText(manifestJson)
    }

    /** The full production chain, ending in the exported record. */
    private fun govern(identity: GovernedRunIdentity): RuntimeEvidenceRecord {
        val context = policyContext(identity)
        return exporter.export(listOf(emit(context, evaluate(context)))).single()
    }

    private fun evaluate(context: PolicyContext): PolicyDecision =
        runBlocking { DefaultPolicyEngine(PolicyConfiguration()).evaluate(context) }

    private fun emit(
        context: PolicyContext,
        decision: PolicyDecision,
    ): AuditEvent {
        val store = CapturingAuditStore()
        runBlocking {
            AuditEnginePolicyDecisionAuditEmitter(AuditEngine(store))
                .emit(EnforcementPoint.BEFORE_TOOL_EXECUTION, context, decision)
        }
        // Read back the event the real audit engine actually chained, rather than re-deriving the
        // emission's metadata: the assertions then describe the production event, and a change that
        // stopped the emitter writing through the audit chain fails here instead of passing.
        return store.last ?: error("the production emitter recorded no audit event")
    }

    private fun policyContext(
        identity: GovernedRunIdentity,
        attributes: Map<String, String> = GovernedIdentityAttribution.of(identity),
    ): PolicyContext =
        PolicyContext(
            enforcementPoint = EnforcementPoint.BEFORE_TOOL_EXECUTION,
            workflowRunId = identity.runId.value,
            correlationId = "corr-g1b",
            actorId = "actor-g1b",
            toolName = TOOL_NAME,
            policyVersion = POLICY_VERSION,
            attributes = attributes,
        )

    private fun governedIdentity(
        deploymentId: String = "deployment-g1b-1",
        runId: String = RUN_ID,
    ): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("workload-g1b"),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("configuration-g1b"),
                            version = ConfigurationVersion("v3"),
                        ),
                    environmentId = EnvironmentId("environment-g1b"),
                    deploymentId = DeploymentId(deploymentId),
                ),
            runId = RunId(runId),
        )

    /** The same governed call with zero reserved keys: the legacy shape an ungoverned caller produces. */
    private fun legacyRecord(runId: String): RuntimeEvidenceRecord {
        val context = policyContext(governedIdentity(runId = runId), attributes = emptyMap())
        return exporter.export(listOf(emit(context, evaluate(context)))).single()
    }

    private companion object {
        const val RUN_ID = "run-g1b-attribution"

        const val TOOL_NAME = "ledger.append"

        const val POLICY_VERSION = "policy-g1b-v1"

        /** The family allowlist the exporter has always applied: the tool name plus the enforcement point. */
        val FAMILY_ALLOWLIST = setOf("toolName", "enforcementPoint")
    }

    /**
     * Delegates to a real [InMemoryAuditStore] and keeps the event the engine chained, so the
     * production emitter's own output can be asserted without re-deriving it.
     */
    private class CapturingAuditStore(
        private val delegate: AuditStore = InMemoryAuditStore(),
    ) : AuditStore {
        var last: AuditEvent? = null
            private set

        override suspend fun appendNext(
            auditStreamId: String,
            eventFactory: (latest: AuditEvent?) -> AuditEvent,
        ): AuditEvent = delegate.appendNext(auditStreamId, eventFactory).also { last = it }

        override suspend fun readStream(auditStreamId: String): List<AuditEvent> = delegate.readStream(auditStreamId)

        override suspend fun latestEvent(auditStreamId: String): AuditEvent? = delegate.latestEvent(auditStreamId)
    }
}
