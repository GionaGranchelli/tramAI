package dev.tramai.examples.sovereign

import dev.tramai.core.approval.ApprovalIdGenerator
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalTokenGenerator
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ApprovalTokenRejectedException
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
import dev.tramai.sovereign.SovereignProfileConfiguration
import dev.tramai.sovereign.SovereignTramai
import dev.tramai.sovereign.SovereignTramaiRuntime
import dev.tramai.engine.ResumeApprovalCommand
import dev.tramai.security.ProviderTrustZone
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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
            fail("Should have thrown exception")
        } catch (_: PolicyViolationException) {
            // Success
        } catch (_: dev.tramai.core.exception.ModelRegistryException) {
            // Also acceptable
        }

        assertEquals(0, provider.capturedRequests.size)
        runtime.close()
    }
}
