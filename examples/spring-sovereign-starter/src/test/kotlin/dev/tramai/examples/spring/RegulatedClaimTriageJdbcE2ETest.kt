package dev.tramai.examples.spring

import com.zaxxer.hikari.HikariDataSource
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.calculateHash
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.spring.sovereign.SovereignTramaiAutoConfiguration
import dev.tramai.spring.sovereign.ops.ApprovalGatewayAuditIntentFactory
import dev.tramai.spring.sovereign.ops.ApprovalGatewayAutoConfiguration
import dev.tramai.spring.sovereign.ops.SovereignOpsTransactionalApprovalGateway
import dev.tramai.spring.sovereign.persistence.jdbc.SovereignJdbcPersistenceAutoConfiguration
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * JDBC-backed end-to-end proof for the regulated claim triage scenario.
 *
 * Demonstrates a deterministic governed workflow:
 * - DLP classification
 * - Policy-based routing (local-only for restricted data)
 * - Fail-closed cloud route denial (cloud model never invoked)
 * - Approval gating for high-risk recommendations
 * - Transactional approval denial + audit outbox intent
 * - Durable outbox record claimable after context restart
 * - Sanitized operational boundaries
 *
 * Uses embedded PostgreSQL (no Docker required).
 */
@Tag("e2e")
class RegulatedClaimTriageJdbcE2ETest {

    companion object {
        private val VALID_BASE64_KEY: String =
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

        @JvmStatic
        @BeforeAll
        fun startPg() {
            PgEmbeddedTestSupport.start()
        }

        @JvmStatic
        @AfterAll
        fun stopPg() {
            PgEmbeddedTestSupport.stop()
        }
    }

    @TempDir
    lateinit var tempDir: Path

    private lateinit var keyFile: Path

    @BeforeEach
    fun setUp() {
        keyFile = tempDir.resolve("e2e-claim-triage-key.b64")
        keyFile.toFile().writeText(VALID_BASE64_KEY)
    }

    // ── Context runner factory ────────────────────────────────────────

    private fun createJdbcRunner(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SovereignJdbcPersistenceAutoConfiguration::class.java,
                    SovereignTramaiAutoConfiguration::class.java,
                    ApprovalGatewayAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(
                JdbcE2eDataSourceConfig::class.java,
                DemoProviderConfiguration::class.java,
                RegulatedClaimTriageGatewayConfig::class.java,
            )
            .withPropertyValues(
                "tramai.sovereign.enabled=true",
                "tramai.sovereign.allowed-models[0]=local-invoice-model",
                "tramai.sovereign.allowed-providers[0]=deterministic-local-provider",
                "tramai.sovereign.provider-zones.deterministic-local-provider=LOCAL",
                "tramai.sovereign.models.local-invoice-model=deterministic-local-provider",
                "tramai.sovereign.persistence.type=jdbc",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
            )

    // ══════════════════════════════════════════════════════════════════
    // Test 1 — High-risk claim through the workflow
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `high risk claim suspends through approval gateway and survives restart`() {
        val claimId = "claim-hr-${UUID.randomUUID()}"
        val workflowRunId = "wf-$claimId"
        val input = ClaimTriageInput(
            claimId = claimId,
            claimantName = "Alpha",
            diagnosisText = "rib fracture",
            invoiceAmount = 2500,
            paymentReference = "PAY-REF-001",
        )

        // IDs to carry across context restart (durable, not in-memory)
        lateinit var persistedApprovalId: String
        lateinit var persistedAuditStreamId: String
        lateinit var denialOutboxId: String

        // ── Context A: run workflow, deny, capture durable IDs ────────
        createJdbcRunner().run { ctx ->
            val workflow = ctx.workflow()
            runBlocking {
                val result = workflow.triage(input, RequestedRoute.LOCAL_ONLY)

                assertThat(result.recommendationType).isEqualTo("SUGGEST_PAYOUT")
                assertThat(result.riskLevel).isEqualTo("HIGH")
                assertThat(result.requiredApproval).isTrue()
                assertThat(result.policyDecision).isEqualTo("ALLOW_LOCAL")
                assertThat(result.classification).isEqualTo("RESTRICTED")

                val pendingApproval = workflow.approvalStore.get(result.approvalId!!)
                assertThat(pendingApproval).isNotNull
                assertThat(pendingApproval!!.status).isEqualTo(ApprovalStatus.PENDING)

                // Gateway also persisted suspended invocation and continuation
                val suspended = workflow.suspendedInvocationStore.get(result.approvalId!!)
                assertThat(suspended).isNotNull
                assertThat(suspended!!.approvalId).isEqualTo(result.approvalId)
                val continuation = workflow.approvalContinuationStore.get(result.approvalId!!)
                assertThat(continuation).isNotNull
                assertThat(continuation!!.workflowRunId).isEqualTo(workflowRunId)
                assertThat(continuation.argumentsDigest)
                    .isEqualTo(pendingApproval.binding.argumentsDigest)

                // Gateway also created approval-requested audit outbox intent atomically
                val approvalRequestedOutbox = workflow.outboxStore.findByEventKey(
                    "regulated-claim-triage.approval-requested",
                )
                assertThat(approvalRequestedOutbox).isNotNull
                assertThat(approvalRequestedOutbox!!.status)
                    .isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
                assertThat(approvalRequestedOutbox.operation).isEqualTo("approvalRequested")
                assertThat(approvalRequestedOutbox.approvalStatus).isEqualTo("PENDING")
                assertThat(approvalRequestedOutbox.approvalVersion).isEqualTo(0L)
                assertThat(approvalRequestedOutbox.actor).isEqualTo("triage-system")

                // Audit: policy decision (allow-local) + recommendation + approval-requested → 3 events
                val events = workflow.auditStore.readStream(result.auditStreamId)
                assertThat(events).hasSize(3)
                assertThat(events[0].decision).isEqualTo("allow-local-route")
                assertThat(events[1].decision).contains("suggest")
                assertThat(events[2].decision).isEqualTo("approval-requested-high-risk")

                // Sanitized: no raw medical text in audit
                for (event in events) {
                    val leaksDiagnosis = event.reasonCode?.contains("fracture", true) == true
                    assertThat(leaksDiagnosis).isFalse()
                }

                // Execute denial — version 0 → deny → version 1
                val auditIntent = SovereignOpsAuditOutboxRecord(
                    outboxId = UUID.randomUUID().toString(),
                    eventKey = "outbox-${UUID.randomUUID()}",
                    aggregateIdDigest = sha256Hex(claimId),
                    actor = "medical-ops-reviewer",
                    workflowRunId = workflowRunId,
                    correlationId = claimId,
                    approvalStatus = ApprovalStatus.PENDING.name,
                    approvalVersion = 0,
                    reasonDigest = sha256Hex("medical-necessity-not-established"),
                    reasonLength = 34,
                    createdAt = Instant.now(),
                )

                val mutationResult = workflow.mutationStore.denyApprovalWithAuditIntent(
                    approvalId = result.approvalId!!,
                    expectedVersion = 0,
                    actor = "medical-ops-reviewer",
                    reason = "Medical necessity not established",
                    auditIntent = auditIntent,
                )

                assertThat(mutationResult.approval.status).isEqualTo(ApprovalStatus.DENIED)
                assertThat(mutationResult.approval.version).isEqualTo(1)
                assertThat(mutationResult.auditOutboxRecord.status)
                    .isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)

                // Capture durable IDs for restart proof
                persistedApprovalId = result.approvalId!!
                persistedAuditStreamId = result.auditStreamId
                denialOutboxId = auditIntent.outboxId
            }
        }

        // ── Context B: restart, verify durable state ──────────────────
        createJdbcRunner().run { ctx ->
            val workflow = ctx.workflow()
            runBlocking {
                // Approval durable and DENIED
                val approval = workflow.approvalStore.get(persistedApprovalId)
                assertThat(approval).isNotNull
                assertThat(approval!!.status).isEqualTo(ApprovalStatus.DENIED)
                assertThat(approval.version).isEqualTo(1)

                // Suspended invocation survives restart
                val suspendedAfterRestart = workflow.suspendedInvocationStore.get(persistedApprovalId)
                assertThat(suspendedAfterRestart).isNotNull

                // Continuation survives restart
                val continuationAfterRestart = workflow.approvalContinuationStore.get(persistedApprovalId)
                assertThat(continuationAfterRestart).isNotNull
                assertThat(continuationAfterRestart!!.workflowRunId).isEqualTo(workflowRunId)

                // Audit chain intact across restart
                val events = workflow.auditStore.readStream(persistedAuditStreamId)
                assertThat(events).hasSize(3)
                assertThat(events[1].previousEventHash).isEqualTo(events[0].eventHash)
                assertThat(events[2].previousEventHash).isEqualTo(events[1].eventHash)

                // Exact denial outbox is claimable and dispatchable
                val claimed = workflow.outboxStore.claimPending(
                    "worker-1", limit = 10, now = Instant.now(),
                )
                assertThat(claimed.map { it.outboxId }).contains(denialOutboxId)

                val claim = claimed.first { it.outboxId == denialOutboxId }
                workflow.outboxStore.markEmitted(
                    claim.outboxId,
                    SovereignOpsAuditOutboxStatus.EMITTING,
                    emittedAt = Instant.now(),
                )
                val dispatched = workflow.outboxStore.get(denialOutboxId)
                assertThat(dispatched!!.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
            }
        }
    }

    @Test
    fun `spring auto-configures ApprovalGateway when factory is present`() {
        createJdbcRunner().run { ctx ->
            assertThat(ctx).hasSingleBean(ApprovalGateway::class.java)
            val gateway = ctx.getBean(ApprovalGateway::class.java)
            // With JDBC persistence, the mutation store is available and auto-config
            // prefers SovereignOpsTransactionalApprovalGateway over DefaultApprovalGateway.
            assertThat(gateway).isInstanceOf(SovereignOpsTransactionalApprovalGateway::class.java)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Test 2 — Fail-closed cloud routing
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `restricted medical claim is denied before cloud model invocation`() {
        val claimId = "claim-fc-${UUID.randomUUID()}"
        val cloudModel = RecordingCloudClaimModel()
        val input = ClaimTriageInput(
            claimId = claimId,
            claimantName = "Beta",
            diagnosisText = "cardiac arrhythmia",
            invoiceAmount = 8000,
            paymentReference = "PAY-REF-002",
        )

        createJdbcRunner().run { ctx ->
            val workflow = ctx.workflow(cloudModel = cloudModel)
            runBlocking {
                val result = workflow.triage(input, RequestedRoute.APPROVED_CLOUD)

                // Policy denied — cloud model was never called
                assertThat(result.policyDecision).isEqualTo("DENY_CLOUD")
                assertThat(cloudModel.calls.get()).isZero()

                // Audit event proves the deny decision
                val events = workflow.auditStore.readStream(result.auditStreamId)
                assertThat(events).hasSize(1)
                assertThat(events[0].decision).isEqualTo("deny-cloud-route")

                // Sanitized: no medical text in audit
                assertThat(events[0].reasonCode).doesNotContain("arrhythmia")
                assertThat(events[0].reasonCode).doesNotContain("diagnosis-text")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Test 3 — Low-risk: no approval suspension
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `low risk missing document recommendation completes without approval suspension`() {
        val claimId = "claim-lr-${UUID.randomUUID()}"
        val workflowRunId = "wf-$claimId"
        val input = ClaimTriageInput(
            claimId = claimId,
            claimantName = "Gamma",
            diagnosisText = "",
            invoiceAmount = 0,
            paymentReference = "",
        )

        createJdbcRunner().run { ctx ->
            val workflow = ctx.workflow()
            runBlocking {
                val result = workflow.triage(input, RequestedRoute.LOCAL_ONLY)

                assertThat(result.recommendationType).isEqualTo("REQUEST_MISSING_DOCUMENT")
                assertThat(result.riskLevel).isEqualTo("LOW")
                assertThat(result.requiredApproval).isFalse()
                assertThat(result.approvalId).isNull()

                // DB-grounded proof: no approval row exists for this workflow run
                val approvalCount = workflow.countApprovalsForWorkflowRun(workflowRunId)
                assertThat(approvalCount).isZero()

                // Audit events: policy decision + recommendation
                val events = workflow.auditStore.readStream(result.auditStreamId)
                assertThat(events).hasSize(2)
                assertThat(events[0].decision).isEqualTo("allow-route")
                assertThat(events[1].decision).contains("request_missing_document")
                assertThat(events[1].previousEventHash).isEqualTo(events[0].eventHash)

                // Outbox dispatchable
                val claimed = workflow.outboxStore.claimPending(
                    "worker-1", limit = 10, now = Instant.now(),
                )
                assertThat(claimed).isNotEmpty
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return "sha256:${hashBytes.joinToString("") { "%02x".format(it) }}"
    }

    // ── Inner configuration ──────────────────────────────────────────

    @Configuration
    class JdbcE2eDataSourceConfig {
        @Bean(destroyMethod = "close")
        fun e2eDataSource(): DataSource {
            val ds = HikariDataSource()
            ds.jdbcUrl = PgEmbeddedTestSupport.jdbcUrl
            ds.username = PgEmbeddedTestSupport.username
            ds.password = PgEmbeddedTestSupport.password
            ds.maximumPoolSize = 3
            return ds
        }
    }

    @Configuration
    class RegulatedClaimTriageGatewayConfig {
        @Bean
        fun regulatedClaimTriageApprovalGatewayRequestFactory(): ApprovalGatewayRequestFactory =
            RegulatedClaimTriageApprovalGatewayRequestFactory()

        @Bean
        fun regulatedClaimTriageApprovalGatewayAuditIntentFactory(): ApprovalGatewayAuditIntentFactory =
            RegulatedClaimTriageApprovalGatewayAuditIntentFactory()
    }
}

// ══════════════════════════════════════════════════════════════════════
// Domain model
// ══════════════════════════════════════════════════════════════════════

data class ClaimTriageInput(
    val claimId: String,
    val claimantName: String,
    val diagnosisText: String,
    val invoiceAmount: Int,
    val paymentReference: String,
)

data class ClaimTriageResult(
    val recommendationType: String,
    val riskLevel: String,
    val requiredApproval: Boolean,
    val policyDecision: String,
    val classification: String,
    val auditStreamId: String,
    val approvalId: String?,
)

enum class RequestedRoute { LOCAL_ONLY, APPROVED_CLOUD }

enum class ClassificationLevel { NON_SENSITIVE, RESTRICTED }

// ══════════════════════════════════════════════════════════════════════
// Deterministic fake components
// ══════════════════════════════════════════════════════════════════════

class FakeDlpClassifier {
    fun classify(input: ClaimTriageInput): ClassificationLevel =
        if (input.diagnosisText.isNotBlank() || input.paymentReference.isNotBlank())
            ClassificationLevel.RESTRICTED
        else
            ClassificationLevel.NON_SENSITIVE
}

class FakePolicyEvaluator {
    fun evaluate(classification: ClassificationLevel, route: RequestedRoute): String =
        when {
            classification == ClassificationLevel.RESTRICTED && route == RequestedRoute.APPROVED_CLOUD -> "DENY_CLOUD"
            classification == ClassificationLevel.RESTRICTED && route == RequestedRoute.LOCAL_ONLY -> "ALLOW_LOCAL"
            else -> "ALLOW"
        }
}

class FakeLocalClaimModel {
    fun recommend(input: ClaimTriageInput): FakeRecommendation =
        when {
            input.diagnosisText.isNotBlank() && input.invoiceAmount > 1000 && input.paymentReference.isNotBlank() ->
                FakeRecommendation("SUGGEST_PAYOUT", "HIGH", true)
            input.diagnosisText.isBlank() && input.invoiceAmount == 0 ->
                FakeRecommendation("REQUEST_MISSING_DOCUMENT", "LOW", false)
            else ->
                FakeRecommendation("MANUAL_REVIEW", "MEDIUM", false)
        }
}

class RecordingCloudClaimModel {
    val calls = AtomicInteger(0)

    fun recommend(input: ClaimTriageInput): FakeRecommendation {
        calls.incrementAndGet()
        error("Cloud model must not be called for restricted medical data (input: ${input.claimId})")
    }
}

data class FakeRecommendation(
    val recommendationType: String,
    val riskLevel: String,
    val requiredApproval: Boolean,
)

// ══════════════════════════════════════════════════════════════════════
// Workflow harness
// ══════════════════════════════════════════════════════════════════════

class ClaimTriageWorkflow(
    val approvalStore: ApprovalStore,
    val auditStore: AuditStore,
    val mutationStore: SovereignOpsApprovalMutationStore,
    val outboxStore: SovereignOpsAuditOutboxStore,
    val suspendedInvocationStore: SuspendedInvocationStore,
    val approvalContinuationStore: ApprovalContinuationStore,
    private val approvalGateway: ApprovalGateway,
    private val dataSource: DataSource,
    private val dlp: FakeDlpClassifier = FakeDlpClassifier(),
    private val policy: FakePolicyEvaluator = FakePolicyEvaluator(),
    private val localModel: FakeLocalClaimModel = FakeLocalClaimModel(),
    private val cloudModel: RecordingCloudClaimModel = RecordingCloudClaimModel(),
) {
    suspend fun triage(input: ClaimTriageInput, requestedRoute: RequestedRoute): ClaimTriageResult {
        val workflowRunId = "wf-${input.claimId}"
        val auditStreamId = "audit-${UUID.randomUUID()}"

        // 1. DLP classification
        val classification = dlp.classify(input)

        // 2. Policy evaluation
        val policyDecision = policy.evaluate(classification, requestedRoute)

        // 3. Route: cloud denied for restricted data
        if (policyDecision == "DENY_CLOUD") {
            emitAuditEvent(
                auditStreamId, workflowRunId, input.claimId,
                actor = "triage-policy-engine",
                enforcementPoint = "policy-decision",
                decision = "deny-cloud-route",
                reasonCode = "restricted-medical-data-denied-cloud-route",
            )
            return ClaimTriageResult(
                recommendationType = "N/A",
                riskLevel = "N/A",
                requiredApproval = false,
                policyDecision = policyDecision,
                classification = classification.name,
                auditStreamId = auditStreamId,
                approvalId = null,
            )
        }

        // 4. Audit: policy decision for allowed routes
        emitAuditEvent(
            auditStreamId, workflowRunId, input.claimId,
            actor = "triage-policy-engine",
            enforcementPoint = "policy-decision",
            decision = when (policyDecision) {
                "ALLOW_LOCAL" -> "allow-local-route"
                else -> "allow-route"
            },
            reasonCode = if (policyDecision == "ALLOW_LOCAL") "restricted-medical-data-local-only" else null,
        )

        // 5. Model recommendation
        val recommendation = when (requestedRoute) {
            RequestedRoute.LOCAL_ONLY -> localModel.recommend(input)
            RequestedRoute.APPROVED_CLOUD -> cloudModel.recommend(input)
        }

        // 6. Audit: recommendation
        emitAuditEvent(
            auditStreamId, workflowRunId, input.claimId,
            actor = "local-claim-model",
            enforcementPoint = "recommendation",
            decision = "${recommendation.recommendationType.lowercase()}-${recommendation.riskLevel.lowercase()}-risk",
            reasonCode = null,
        )

        // 7. Audit outbox: recommendation emitted
        val outboxId = UUID.randomUUID().toString()
        val eventKey = "outbox-${UUID.randomUUID()}"
        val outboxRecord = outboxStore.append(
            SovereignOpsAuditOutboxRecord(
                outboxId = outboxId,
                eventKey = eventKey,
                aggregateIdDigest = sha256Hex(input.claimId),
                actor = "local-claim-model",
                workflowRunId = workflowRunId,
                correlationId = input.claimId,
                approvalStatus = if (recommendation.requiredApproval) ApprovalStatus.PENDING.name else "N/A",
                approvalVersion = if (recommendation.requiredApproval) 1 else 0,
                reasonDigest = sha256Hex(recommendation.recommendationType),
                reasonLength = recommendation.recommendationType.length,
                createdAt = Instant.now(),
            ),
        )
        outboxStore.markReadyForDispatch(outboxId, SovereignOpsAuditOutboxStatus.PREPARED)

        // 8. Approval gate: high-risk requires approval
        if (recommendation.requiredApproval) {
            val approvalResult = approvalGateway.requestApproval(
                subject = ApprovalSubject(input.claimId),
                recommendation = ApprovalRecommendation(
                    type = "regulated-claim-triage",
                    summary = recommendation.recommendationType,
                    payload = mapOf(
                        "riskLevel" to recommendation.riskLevel,
                        "requiredApprover" to if (recommendation.riskLevel == "HIGH") "medical-reviewer" else "reviewer",
                    ),
                ),
                requiredRole = ApproverRole(
                    if (recommendation.riskLevel == "HIGH") "medical-reviewer" else "reviewer",
                ),
                workflowRunId = WorkflowRunId(workflowRunId),
            )
            val approvalId = when (approvalResult) {
                is ApprovalRequestResult.Suspended -> approvalResult.approvalId.value
                is ApprovalRequestResult.AlreadyApproved -> approvalResult.decision.approvalId.value
                is ApprovalRequestResult.AlreadyDenied -> approvalResult.decision.approvalId.value
                is ApprovalRequestResult.Expired -> approvalResult.approvalId.value
            }
            val approvalAuditDecision = when (approvalResult) {
                is ApprovalRequestResult.Suspended -> "approval-requested-high-risk"
                is ApprovalRequestResult.AlreadyApproved -> "approval-already-approved"
                is ApprovalRequestResult.AlreadyDenied -> "approval-already-denied"
                is ApprovalRequestResult.Expired -> "approval-expired"
            }

            // Audit: approval requested
            emitAuditEvent(
                auditStreamId, workflowRunId, input.claimId,
                actor = "triage-system",
                enforcementPoint = "approval-gate",
                decision = approvalAuditDecision,
                reasonCode = null,
            )

            return ClaimTriageResult(
                recommendationType = recommendation.recommendationType,
                riskLevel = recommendation.riskLevel,
                requiredApproval = true,
                policyDecision = policyDecision,
                classification = classification.name,
                auditStreamId = auditStreamId,
                approvalId = approvalId,
            )
        }

        return ClaimTriageResult(
            recommendationType = recommendation.recommendationType,
            riskLevel = recommendation.riskLevel,
            requiredApproval = false,
            policyDecision = policyDecision,
            classification = classification.name,
            auditStreamId = auditStreamId,
            approvalId = null,
        )
    }

    private suspend fun emitAuditEvent(
        streamId: String,
        workflowRunId: String,
        correlationId: String,
        actor: String,
        enforcementPoint: String,
        decision: String,
        reasonCode: String?,
    ) {
        auditStore.appendNext(streamId) { previousEvent: AuditEvent? ->
            AuditEvent(
                schemaVersion = 1,
                hashAlgorithm = AuditHashAlgorithm.SHA_256,
                auditStreamId = streamId,
                eventId = UUID.randomUUID().toString(),
                sequenceNumber = (previousEvent?.sequenceNumber ?: 0L) + 1L,
                workflowRunId = workflowRunId,
                correlationId = correlationId,
                actor = actor,
                enforcementPoint = enforcementPoint,
                decision = decision,
                policyVersion = "1.0",
                workflowDigest = sha256Hex("claim-triage-workflow"),
                previousEventHash = previousEvent?.eventHash,
                eventHash = "",
                timestamp = Instant.now(),
                reasonCode = reasonCode,
            ).let { it.copy(eventHash = it.calculateHash()) }
        }
    }

    /** Count approvals whose metadata contains the given workflowRunId. */
    fun countApprovalsForWorkflowRun(workflowRunId: String): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT count(*)
                FROM approvals
                WHERE sanitized_metadata::text LIKE ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, "%$workflowRunId%")
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return "sha256:${hashBytes.joinToString("") { "%02x".format(it) }}"
    }
}

// ── Spring context convenience extension ─────────────────────────────

private fun org.springframework.context.ApplicationContext.workflow(
    cloudModel: RecordingCloudClaimModel = RecordingCloudClaimModel(),
): ClaimTriageWorkflow = ClaimTriageWorkflow(
    approvalStore = getBean(ApprovalStore::class.java),
    auditStore = getBean(AuditStore::class.java),
    mutationStore = getBean(SovereignOpsApprovalMutationStore::class.java),
    outboxStore = getBean(SovereignOpsAuditOutboxStore::class.java),
    suspendedInvocationStore = getBean(SuspendedInvocationStore::class.java),
    approvalContinuationStore = getBean(ApprovalContinuationStore::class.java),
    approvalGateway = getBean(ApprovalGateway::class.java),
    dataSource = getBean(DataSource::class.java),
    cloudModel = cloudModel,
)
