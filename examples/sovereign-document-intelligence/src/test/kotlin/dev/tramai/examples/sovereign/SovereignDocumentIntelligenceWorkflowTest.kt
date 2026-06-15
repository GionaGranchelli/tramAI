package dev.tramai.examples.sovereign

import dev.tramai.core.approval.ApprovalIdGenerator
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalTokenGenerator
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ApprovalTokenRejectedException
import dev.tramai.core.exception.ModelDisabledException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.model.RegisteredModel
import dev.tramai.security.approval.DefaultApprovalGateCoordinator
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalStore
import dev.tramai.security.approval.Sha256ApprovalTokenDigester
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import dev.tramai.security.model.InMemoryModelRegistry
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditChainVerifier
import dev.tramai.security.audit.toCanonicalJson
import dev.tramai.sovereign.SovereignProfileConfiguration
import dev.tramai.sovereign.SovereignTramai
import dev.tramai.sovereign.SovereignTramaiRuntime
import dev.tramai.sovereign.evidence.ReleaseBundleEvidenceLoader
import dev.tramai.sovereign.evidence.ZeroEgressEvidenceV1
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.security.ProviderTrustZone
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * Comprehensive integration test for the sovereign document intelligence workflow.
 *
 * Proves the public sovereign consumer path through [SovereignTramai.builder().runtime()].
 * Demonstrates: sovereign routing → model registry enforcement → approval suspension
 * → token-bound resume → exactly-once tool execution → hash-chained audit evidence.
 */
class SovereignDocumentIntelligenceWorkflowTest {

    @TempDir
    lateinit var tempDir: Path

    // ── Deterministic fixtures ──────────────────────────────────────────────

    private val ledger = InMemoryPaymentLedger()
    private val provider = DeterministicInvoiceProvider()

    private val fixedClock = Clock.fixed(
        Instant.parse("2026-06-11T12:00:00Z"),
        ZoneId.of("UTC"),
    )

    private val approvalStore = InMemoryApprovalStore(clock = fixedClock)
    private val continuationStore = InMemoryApprovalContinuationStore(clock = fixedClock)
    private val auditStore = InMemoryAuditStore()
    private val toolArgumentsDigester = Sha256ToolArgumentsDigester()
    private val approvalTokenDigester = Sha256ApprovalTokenDigester()

    /** Deterministic approval ID generator for reproducible tests. */
    private val approvalIdGenerator = ApprovalIdGenerator { "approval-invoice-001" }

    /** Deterministic token generator for reproducible tests. */
    private val approvalTokenGenerator = ApprovalTokenGenerator {
        ApprovalToken.parsePresented("approval-token-invoice-001")
    }

    private val gateCoordinator = DefaultApprovalGateCoordinator(
        store = approvalStore,
        approvalIdGenerator = approvalIdGenerator,
        approvalTokenGenerator = approvalTokenGenerator,
        approvalTokenDigester = approvalTokenDigester,
        clock = fixedClock,
    )

    private val profile = SovereignProfileConfiguration(
        allowedModels = setOf("local-invoice-model"),
        allowedProviders = setOf("local-provider"),
        allowedTools = setOf("schedule-payment"),
        allowedPermissions = setOf("payment.schedule"),
        providerZones = mapOf("local-provider" to ProviderTrustZone.LOCAL),
    )

    private val modelRegistry = InMemoryModelRegistry.builder()
        .register(
            RegisteredModel(
                registryEntryId = "invoice-model-local-v1",
                providerId = "local-provider",
                modelName = "local-invoice-model",
                revision = "1.0",
            ),
        )
        .build()

    // ── Helper: build SovereignTramaiRuntime with approval stack ────────────

    private fun buildRuntime(): SovereignTramaiRuntime {
        val tramai = SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(modelRegistry)
            .auditStore(auditStore)
            .provider(provider, name = "local-provider", default = true)
            .model("local-invoice-model", "local-provider")
            .tools(SchedulePaymentTool(ledger))
            .approvalContinuationStore(continuationStore)
            .toolArgumentsDigester(toolArgumentsDigester)
            .approvalGateCoordinator(gateCoordinator)
            .clock(fixedClock)
            .build()
        return tramai.runtime()
    }

    // ── Helper: trigger approval suspension ─────────────────────────────────

    private fun triggerSuspension(service: InvoiceAnalysisService): ApprovalSuspendedException {
        try {
            runBlocking { service.analyze(classifiedInvoice()) }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (e: ApprovalSuspendedException) {
            return e
        }
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `restricted invoice suspends for approval and resumes with exactly-once payment`() {
        val runtime = buildRuntime()
        val service = runtime.create(InvoiceAnalysisService::class)

        // 1. Invoke analyze → should suspend
        val suspension = triggerSuspension(service)
        assertEquals("approval-invoice-001", suspension.approvalId)

        // 2. Verify ledger is empty (no side effect before approval)
        assertEquals(0, ledger.executionCount())

        // 3. Approve the request (suspend store calls need runBlocking)
        val stored = runBlocking { approvalStore.get(suspension.approvalId) }
        assertNotNull(stored)
        assertEquals(ApprovalStatus.PENDING, stored.status)
        val approved = runBlocking {
            approvalStore.transition(
                suspension.approvalId,
                stored.version,
                ApprovalTransition.Approve(decidedBy = "human-operator", comment = "Approved"),
            )
        }

        // 4. Resume with bound token
        val command = ResumeApprovalCommand(
            approvalId = suspension.approvalId,
            approvalExpectedVersion = approved.version,
            continuationExpectedVersion = suspension.continuationVersion,
            presentedToken = suspension.challenge.token,
            resumedBy = "human-operator",
        )
        val assessment = runBlocking {
            runtime.resumeApprovalTyped<InvoiceAssessment>(command)
        }

        // 5. Verify typed assessment
        assertEquals("INV-001", assessment.invoiceId)
        assertEquals(InvoiceRisk.HIGH, assessment.risk)
        assertEquals(InvoiceAction.SCHEDULE_PAYMENT, assessment.recommendedAction)

        // 6. Verify exactly-once payment
        assertEquals(1, ledger.executionCount())

        // 7. Verify hash-chained audit evidence (readStream is suspend)
        val events = runBlocking { auditStore.readStream(suspension.workflowRunId) }
        assertTrue(events.isNotEmpty(), "Audit events must be emitted for workflow ${suspension.workflowRunId}")
        val result = AuditChainVerifier.verify(events)
        assertTrue(result.isValid, "Audit chain must be valid: ${result.errors.joinToString { it.message }}")

        // 8. Verify approval lifecycle events contain toolName=schedule-payment
        val suspendedEvents = events.filter { it.enforcementPoint == "APPROVAL_SUSPENDED" }
        assertTrue(suspendedEvents.isNotEmpty(), "APPROVAL_SUSPENDED event must exist")
        assertEquals("schedule-payment", suspendedEvents.first().metadata["toolName"])

        val resumedEvents = events.filter { it.enforcementPoint == "APPROVAL_RESUMED" }
        assertTrue(resumedEvents.isNotEmpty(), "APPROVAL_RESUMED event must exist")
        assertEquals("schedule-payment", resumedEvents.first().metadata["toolName"])

        val completedEvents = events.filter { it.enforcementPoint == "APPROVAL_COMPLETED" }
        assertTrue(completedEvents.isNotEmpty(), "APPROVAL_COMPLETED event must exist")
        assertEquals("schedule-payment", completedEvents.first().metadata["toolName"])

        // 9. No sensitive data in any audit event (scan canonical serialized form)
        val sensitiveTokens = setOf("approval-token-invoice-001", "DE89370400440532013000", "Acme Corp", "Enterprise license renewal")
        for (event in events) {
            val serialized = event.toCanonicalJson()
            for (token in sensitiveTokens) {
                assertTrue(token !in serialized, "Sensitive value '$token' must not appear in serialized audit event")
            }
        }

        // 10. All audit timestamps equal fixedClock.instant()
        val expectedTimestamp = fixedClock.instant()
        for (event in events) {
            assertEquals(expectedTimestamp, event.timestamp, "All audit timestamps must equal fixed clock instant")
        }

        runtime.close()
    }

    @Test
    fun `wrong approval token is rejected before tool execution`() {
        val runtime = buildRuntime()
        val service = runtime.create(InvoiceAnalysisService::class)
        val suspension = triggerSuspension(service)
        assertEquals(0, ledger.executionCount())

        // Approve the request (suspend store calls need runBlocking)
        val stored = runBlocking { approvalStore.get(suspension.approvalId) }
        assertNotNull(stored)
        val approved = runBlocking {
            approvalStore.transition(
                suspension.approvalId,
                stored.version,
                ApprovalTransition.Approve(decidedBy = "human-operator", comment = "Approved"),
            )
        }

        // Try to resume with wrong token
        val wrongToken = ApprovalToken.parsePresented("wrong-token-value")
        val wrongCommand = ResumeApprovalCommand(
            approvalId = suspension.approvalId,
            approvalExpectedVersion = approved.version,
            continuationExpectedVersion = suspension.continuationVersion,
            presentedToken = wrongToken,
            resumedBy = "human-operator",
        )

        try {
            runBlocking { runtime.resumeApproval(wrongCommand) }
            fail("Should have thrown ApprovalTokenRejectedException")
        } catch (_: ApprovalTokenRejectedException) {
            // Success — wrong token rejected
        }

        assertEquals(0, ledger.executionCount())
        runtime.close()
    }

    @Test
    fun `restricted invoice routes only through approved local provider`() {
        val runtime = buildRuntime()
        val service = runtime.create(InvoiceAnalysisService::class)

        try {
            runBlocking { service.analyze(classifiedInvoice()) }
            fail("Should have thrown ApprovalSuspendedException")
        } catch (_: ApprovalSuspendedException) {
            // Provider was called (then suspended for approval)
        }

        assertEquals(1, provider.capturedRequests.size)
        runtime.close()
    }

    @Test
    fun `disabled model registry entry fails before provider invocation`() {
        val disabledRegistry = InMemoryModelRegistry.builder()
            .register(
                RegisteredModel(
                    registryEntryId = "invoice-model-local-v1",
                    providerId = "local-provider",
                    modelName = "local-invoice-model",
                    revision = "1.0",
                    enabled = false,
                ),
            )
            .build()

        val tramai = SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(disabledRegistry)
            .auditStore(auditStore)
            .provider(provider, name = "local-provider", default = true)
            .model("local-invoice-model", "local-provider")
            .tools(SchedulePaymentTool(ledger))
            .build()
        val runtime = tramai.runtime()
        val service = runtime.create(InvoiceAnalysisService::class)

        try {
            runBlocking { service.analyze(classifiedInvoice()) }
            fail("Should have thrown ModelDisabledException")
        } catch (_: ModelDisabledException) {
            // Success - only ModelDisabledException is acceptable
        }

        assertEquals(0, provider.capturedRequests.size)
        runtime.close()
    }

    @Test
    fun `unregistered model is rejected before provider invocation`() {
        val unregisteredModelRegistry = InMemoryModelRegistry.builder()
            .register(
                RegisteredModel(
                    registryEntryId = "some-other-model",
                    providerId = "local-provider",
                    modelName = "not-the-service-model",
                    revision = "1.0",
                ),
            )
            .build()

        val tramai = SovereignTramai.builder()
            .profile(profile)
            .modelRegistry(unregisteredModelRegistry)
            .auditStore(auditStore)
            .provider(provider, name = "local-provider", default = true)
            .model("local-invoice-model", "local-provider")
            .tools(SchedulePaymentTool(ledger))
            .build()
        val runtime = tramai.runtime()
        val service = runtime.create(InvoiceAnalysisService::class)

        try {
            runBlocking { service.analyze(classifiedInvoice()) }
            fail("Should have thrown exception before provider invocation")
        } catch (_: Exception) {
            // Accept any exception - the key assertion is that provider was never called
        }

        assertEquals(0, provider.capturedRequests.size,
            "Provider must not be invoked when model is not registered")
        runtime.close()
    }

    @Test
    fun `restricted invoice allows exactly one payment after duplicate resume attempt`() {
        val runtime = buildRuntime()
        val service = runtime.create(InvoiceAnalysisService::class)
        val suspension = triggerSuspension(service)

        val stored = runBlocking { approvalStore.get(suspension.approvalId) }
        assertNotNull(stored)
        val approved = runBlocking {
            approvalStore.transition(
                suspension.approvalId,
                stored.version,
                ApprovalTransition.Approve(decidedBy = "human-operator", comment = "Approved"),
            )
        }

        val command = ResumeApprovalCommand(
            approvalId = suspension.approvalId,
            approvalExpectedVersion = approved.version,
            continuationExpectedVersion = suspension.continuationVersion,
            presentedToken = suspension.challenge.token,
            resumedBy = "human-operator",
        )

        val assessment = runBlocking {
            runtime.resumeApprovalTyped<InvoiceAssessment>(command)
        }
        assertEquals("INV-001", assessment.invoiceId)
        assertEquals(1, ledger.executionCount())

        try {
            runBlocking { runtime.resumeApproval(command) }
            fail("Should have thrown ApprovalTokenRejectedException")
        } catch (e: ApprovalTokenRejectedException) {
            // Success — token was consumed by the first resume.
        }

        assertEquals(1, ledger.executionCount())
        runtime.close()
    }

    @Test
    fun `evidence pack can be generated from completed workflow`() {
        val harness = buildExampleHarness()

        harness.use {
            val outcome = runApprovedWorkflow(
                runtime = harness.runtime,
                approvalStore = harness.approvalStore,
                auditStore = harness.auditStore,
            )

            val pack = harness.tramai.evidencePack(
                zeroEgress = ZeroEgressEvidenceV1(
                    deploymentMode = "STANDARD",
                    runtimeBuildSucceeded = true,
                    loopbackProviderInvocationSucceeded = true,
                    loopbackProviderInvocationCount = harness.provider.capturedRequests.size,
                    externalTcpProbeBlocked = false,
                    externalDnsProbeBlocked = false,
                ),
                auditChain = auditChainEvidence(outcome.auditEvents),
            )

            assertEquals(1, pack.schemaVersion)
            assertEquals("STANDARD", pack.deploymentMode)
            assertEquals(outcome.auditEvents.size, pack.auditChain?.totalEvents)
            assertTrue(pack.auditChain?.isValid == true)
            assertEquals(listOf("local-invoice-model"), pack.allowedModels)
            assertEquals(listOf("local-provider"), pack.allowedProviders)
        }
    }

    @Test
    fun `release bundle manifest can be loaded and included in evidence pack`() {
        val manifestPath = tempDir.resolve("release-artifacts-v1.json")
        Files.writeString(
            manifestPath,
            """
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
                }
              ]
            }
            """.trimIndent(),
        )

        val releaseBundle = ReleaseBundleEvidenceLoader.load(manifestPath)
        val harness = buildExampleHarness()

        harness.use {
            runApprovedWorkflow(
                runtime = harness.runtime,
                approvalStore = harness.approvalStore,
                auditStore = harness.auditStore,
            )
            val pack = harness.tramai.evidencePack(releaseBundle = releaseBundle)

            assertNotNull(pack.releaseBundle)
            assertEquals("Gradle", pack.releaseBundle!!.buildTool)
            assertEquals(1, pack.releaseBundle!!.artifacts.size)
            assertEquals("tramai-core-0.3.1.jar", pack.releaseBundle!!.artifacts.single().fileName)
        }
    }

    @Test
    fun `structured invoice assessment is deterministic across runs`() {
        val firstHarness = buildExampleHarness()
        val firstAssessment = firstHarness.use {
            runApprovedWorkflow(
                runtime = firstHarness.runtime,
                approvalStore = firstHarness.approvalStore,
                auditStore = firstHarness.auditStore,
            ).assessment
        }

        val secondHarness = buildExampleHarness()
        val secondAssessment = secondHarness.use {
            runApprovedWorkflow(
                runtime = secondHarness.runtime,
                approvalStore = secondHarness.approvalStore,
                auditStore = secondHarness.auditStore,
            ).assessment
        }

        assertEquals(firstAssessment, secondAssessment)
        assertEquals(
            InvoiceAssessment(
                invoiceId = "INV-001",
                supplierName = "Acme Corp",
                amountCents = 15000000,
                currency = "EUR",
                risk = InvoiceRisk.HIGH,
                recommendedAction = InvoiceAction.SCHEDULE_PAYMENT,
                rationale = "Enterprise license renewal Q3 exceeds threshold and requires payment scheduling",
            ),
            firstAssessment,
        )
    }

    @Test
    fun `audit chain includes workflow resume and approval enforcement points`() {
        val harness = buildExampleHarness()

        harness.use {
            val outcome = runApprovedWorkflow(
                runtime = harness.runtime,
                approvalStore = harness.approvalStore,
                auditStore = harness.auditStore,
            )
            val enforcementPoints = outcome.auditEvents.map { it.enforcementPoint }.toSet()

            assertTrue("BEFORE_WORKFLOW_RESUME" in enforcementPoints)
            assertTrue("APPROVAL_SUSPENDED" in enforcementPoints)
            assertTrue("APPROVAL_RESUMED" in enforcementPoints)
            assertTrue("APPROVAL_COMPLETED" in enforcementPoints)
        }
    }
}
