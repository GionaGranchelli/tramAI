package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.SealedResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.persistence.jdbc.JdbcApprovalStore
import dev.tramai.spring.sovereign.ops.ApprovalDecisionCommand
import dev.tramai.spring.sovereign.ops.ApprovalResumeCommand
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalResumeResult
import dev.tramai.spring.sovereign.ops.SovereignOpsApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.SovereignOpsApprovedContinuationResumeWorker
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueStatusStore
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.InMemorySovereignOpsAuditOutboxStore
import dev.tramai.spring.sovereign.ops.actuator.ApprovedContinuationResumeWorkerMetricsObserver
import dev.tramai.spring.sovereign.ops.actuator.ApprovedContinuationResumeWorkerMetricsProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Comprehensive E2E test proving the full approved-resume lifecycle
 * via real JDBC persistence, real decision control plane, and real metrics observer.
 *
 * Proves the entire chain:
 *   PENDING approval → real approve → worker resumes → credential deleted
 *   → queue snapshot updates → metrics update → no token leaks → idempotency
 *
 * Uses:
 * - Real [JdbcApprovedContinuationResumeQueue] (Testcontainers PostgreSQL)
 * - Real [JdbcApprovalResumeCredentialStore] (encrypted at rest)
 * - Real [JdbcApprovedContinuationResumeQueueStatusStore] (queue snapshot)
 * - Real [JdbcApprovalStore] + [SovereignOpsApprovalDecisionControlPlane] for approval
 * - Real [SovereignOpsApprovedContinuationResumeWorker] for resume
 * - Real [ApprovedContinuationResumeWorkerMetricsObserver] + [SimpleMeterRegistry] for metrics
 * - Stronger [ApprovalResumeControlPlane] with full validation
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovedResumeLifecycleJdbcE2ETest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("resume_lifecycle_e2e_test")
            .withUsername("test")
            .withPassword("test")

        private fun createDataSource() = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private val aes256Key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val secretKey: SecretKey = SecretKeySpec(aes256Key, "AES")

    private lateinit var dataSource: DataSource
    private lateinit var queue: JdbcApprovedContinuationResumeQueue
    private lateinit var credentialStore: JdbcApprovalResumeCredentialStore
    private lateinit var queueStatusStore: JdbcApprovedContinuationResumeQueueStatusStore
    private lateinit var approvalStore: JdbcApprovalStore
    private lateinit var decisionControlPlane: SovereignOpsApprovalDecisionControlPlane
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var metricsObserver: ApprovedContinuationResumeWorkerMetricsObserver
    private val clock: Clock = Clock.systemUTC()

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        dataSource = createDataSource()
        runMigrations()

        queue = JdbcApprovedContinuationResumeQueue(dataSource)
        credentialStore = JdbcApprovalResumeCredentialStore(
            dataSourceProvider = { dataSource.connection },
            key = secretKey,
            keyId = "test-key",
        )
        queueStatusStore = JdbcApprovedContinuationResumeQueueStatusStore(dataSource)
        approvalStore = JdbcApprovalStore(dataSource, clock)
        meterRegistry = SimpleMeterRegistry()
        metricsObserver = ApprovedContinuationResumeWorkerMetricsObserver(
            meterRegistry = meterRegistry,
            properties = ApprovedContinuationResumeWorkerMetricsProperties(),
        )

        // Build the real decision control plane with in-memory outbox
        val outboxStore = InMemorySovereignOpsAuditOutboxStore()
        val mutationStore = InMemorySovereignOpsApprovalMutationStore(approvalStore, outboxStore)
        decisionControlPlane = SovereignOpsApprovalDecisionControlPlane(
            approvalStore = approvalStore,
            mutationStore = mutationStore,
            clock = clock,
        )
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun setUp() {
        truncateTables()
    }

    @Test
    fun `full approval resume lifecycle with real decision control plane and metrics observer`() {
        // ── Step 1: Seed a PENDING approval + PENDING continuation + credential ──
        val approvalId = ApprovalId("e2e-lifecycle-002")
        val workflowRunId = WorkflowRunId("wf-e2e-lifecycle-002")
        val rawTokenValue = "resume-token-e2e-lifecycle-002"
        val resumeToken = ResumeToken(rawTokenValue)
        val baseNow = clock.instant()
        val approvalExpiresAt = baseNow.plus(Duration.ofMinutes(10))

        // Insert PENDING approval (not yet approved — the control plane will approve it)
        // Version 0 and proper metadata JSON to match JdbcApprovalStore.create() format
        sql(
            """INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
               VALUES ('${approvalId.value}', 'PENDING', now(),
                       '{"binding":{"workflowRunId":"${workflowRunId.value}","toolName":"tool-lifecycle-002","argumentsDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","policyVersion":"v1","workflowDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","approvalTokenDigest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"},"requestedBy":"test-requester","expiresAt":"$approvalExpiresAt","requestedAt":"$baseNow"}'::jsonb,
                       0)""",
        )

        // Insert PENDING continuation
        sql(
            """INSERT INTO approval_continuations
               (approval_id, status, workflow_run_id, correlation_id, tool_call_id, tool_name,
                arguments_digest, policy_version, workflow_digest,
                created_at, approval_expires_at, version)
               VALUES ('${approvalId.value}', 'PENDING', '${workflowRunId.value}',
                       'corr-lifecycle-002', 'tc-lifecycle-002', 'tool-lifecycle-002',
                       'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                       'v1', 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                       '$baseNow', '$approvalExpiresAt', 0)""",
        )

        // Create credential via the real encrypted credential store
        runBlocking {
            credentialStore.create(
                ApprovalResumeCredentialRecord(
                    approvalId = approvalId,
                    workflowRunId = workflowRunId,
                    resumeToken = SealedResumeToken.seal(resumeToken),
                    createdAt = baseNow,
                    expiresAt = approvalExpiresAt,
                    version = 0L,
                ),
            )
        }

        // Verify seed data is in place
        assertThat(readValue("SELECT status FROM approvals WHERE approval_id = ?", approvalId.value))
            .describedAs("initial approval status").isEqualTo("PENDING")
        assertThat(readValue("SELECT status FROM approval_continuations WHERE approval_id = ?", approvalId.value))
            .describedAs("initial continuation status").isEqualTo("PENDING")
        assertThat(runBlocking { credentialStore.get(approvalId) })
            .describedAs("initial credential").isNotNull

        // ── Step 2: Approve via real decision control plane ────────────────────
        val approveResult = runBlocking {
            decisionControlPlane.approve(
                ApprovalDecisionCommand(
                    approvalId = approvalId,
                    actorId = "e2e-test-actor",
                    actorRole = ApproverRole("admin"),
                    comment = "E2E test approval",
                    correlationId = "corr-lifecycle-002",
                ),
            )
        }
        assertThat(approveResult)
            .describedAs("approve result type")
            .isInstanceOf(dev.tramai.spring.sovereign.ops.ApprovalDecisionResult.Approved::class.java)

        // ── Step 3: Assert approval status = APPROVED (via SQL read) ──────────
        assertThat(readValue("SELECT status FROM approvals WHERE approval_id = ?", approvalId.value))
            .describedAs("approval status after approve").isEqualTo("APPROVED")

        // ── Step 4: Assert queue snapshot: eligibleNow == 1 ────────────────────
        val beforeSnapshot = runBlocking { queueStatusStore.snapshot(baseNow) }
        assertThat(beforeSnapshot.eligibleNow)
            .describedAs("eligibleNow before worker").isEqualTo(1L)
        assertThat(beforeSnapshot.activeLeases).isZero()
        assertThat(beforeSnapshot.terminalFailures).isZero()

        // ── Build the validating resume control plane (Fix 3) ──────────────────
        val controlPlane = object : ApprovalResumeControlPlane {
            override suspend fun resume(command: ApprovalResumeCommand): ApprovalResumeResult {
                val appStatus = readValue(
                    "SELECT status FROM approvals WHERE approval_id = ?",
                    command.approvalId.value,
                )
                if (appStatus != "APPROVED") {
                    return ApprovalResumeResult.NotApproved(
                        command.approvalId,
                        ApprovalStatus.valueOf(appStatus ?: "PENDING"),
                    )
                }

                // Read continuation with expiry check
                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        """SELECT status, approval_expires_at
                           FROM approval_continuations
                           WHERE approval_id = ?""",
                    ).use { stmt ->
                        stmt.setString(1, command.approvalId.value)
                        stmt.executeQuery().use { rs ->
                            if (!rs.next()) {
                                return ApprovalResumeResult.Conflict(
                                    command.approvalId,
                                    "approval-continuation-missing",
                                )
                            }
                            val contStatus = rs.getString("status")
                            val expiresAt = rs.getObject("approval_expires_at", java.time.OffsetDateTime::class.java)

                            if (contStatus != "PENDING") {
                                return if (contStatus == "COMPLETED") {
                                    ApprovalResumeResult.AlreadyCompleted(command.approvalId)
                                } else {
                                    ApprovalResumeResult.Conflict(
                                        command.approvalId,
                                        "approval-continuation-not-pending-$contStatus",
                                    )
                                }
                            }

                            // Check continuation not expired
                            if (expiresAt != null && expiresAt.toInstant().isBefore(clock.instant())) {
                                return ApprovalResumeResult.Conflict(
                                    command.approvalId,
                                    "approval-continuation-expired",
                                )
                            }
                        }
                    }
                }

                // All validations passed — delegate completion to worker/queue path
                return ApprovalResumeResult.Resumed(
                    approvalId = command.approvalId,
                    resumedBy = command.resumedBy,
                    result = "workflow-resolved",
                )
            }
        }

        // ── Build worker ───────────────────────────────────────────────────────
        val worker = SovereignOpsApprovedContinuationResumeWorker(
            queue = queue,
            credentialStore = credentialStore,
            resumeControlPlane = controlPlane,
            workerId = "e2e-lifecycle-worker",
            leaseDuration = Duration.ofMinutes(2),
            retryDelay = Duration.ofSeconds(30),
            conflictRetryDelay = Duration.ofSeconds(60),
            clock = clock,
        )

        // ── Step 5: Run worker — scanned=1, resumed=1 ──────────────────────────
        val result = runBlocking { worker.runOnce(limit = 10) }
        assertThat(result.scanned).describedAs("scanned items").isEqualTo(1)
        assertThat(result.resumed).describedAs("resumed items").isEqualTo(1)
        assertThat(result.skipped).describedAs("skipped items").isZero()
        assertThat(result.failed).describedAs("failed items").isZero()

        // ── Step 6: Assert continuation COMPLETED (via SQL) ────────────────────
        assertThat(
            readValue("SELECT status FROM approval_continuations WHERE approval_id = ?", approvalId.value),
        ).describedAs("continuation status after resume").isEqualTo("COMPLETED")

        // ── Step 7: Assert credential deleted ──────────────────────────────────
        assertThat(runBlocking { credentialStore.get(approvalId) })
            .describedAs("credential should be null after resume")
            .isNull()

        // ── Step 8: Assert queue snapshot: eligibleNow == 0, terminalFailures == 0 ──
        val afterSnapshot = runBlocking { queueStatusStore.snapshot(baseNow) }
        assertThat(afterSnapshot.eligibleNow).describedAs("eligibleNow after worker").isZero()
        assertThat(afterSnapshot.terminalFailures).describedAs("terminalFailures").isZero()
        assertThat(afterSnapshot.activeLeases).describedAs("activeLeases").isZero()
        assertThat(afterSnapshot.delayedRetry).isZero()
        assertThat(afterSnapshot.expiredLeases).isZero()

        // ── Step 9: Metrics assertions via real metrics observer ───────────────
        metricsObserver.cycleCompleted("test-worker", result, Duration.ofMillis(100))

        val cyclesCounter = meterRegistry.find(
            "tramai.sovereign.approved_resume_worker.cycles.total",
        ).counter()!!
        assertThat(cyclesCounter.count())
            .describedAs("cycles.total counter count")
            .isEqualTo(1.0)

        val resumedCounter = meterRegistry.find(
            "tramai.sovereign.approved_resume_worker.items.resumed.total",
        ).counter()!!
        assertThat(resumedCounter.count())
            .describedAs("items.resumed.total counter count")
            .isEqualTo(1.0)

        // ── Step 10: No resume token leaks ─────────────────────────────────────
        // 10a. sanitized_metadata JSON — no token
        val sanitizedMetadata = readText(
            "SELECT sanitized_metadata::text FROM approvals WHERE approval_id = ?",
            approvalId.value,
        )
        assertThat(sanitizedMetadata)
            .describedAs("sanitized_metadata does not contain raw token")
            .doesNotContain(rawTokenValue)

        // 10b. Credential ciphertext columns — no raw token (credential may be null after resume)
        val encryptedToken = readText(
            "SELECT encrypted_resume_token FROM tramai_approval_resume_credentials WHERE approval_id = ?",
            approvalId.value,
        )
        val encryptionNonce = readText(
            "SELECT encryption_nonce FROM tramai_approval_resume_credentials WHERE approval_id = ?",
            approvalId.value,
        )
        if (encryptedToken != null) {
            assertThat(encryptedToken)
                .describedAs("encrypted_resume_token does not contain raw token")
                .doesNotContain(rawTokenValue)
        }
        if (encryptionNonce != null) {
            assertThat(encryptionNonce)
                .describedAs("encryption_nonce does not contain raw token")
                .doesNotContain(rawTokenValue)
        }

        // 10c. Metrics registry meters toString() — no token
        assertThat(meterRegistry.meters.toString())
            .describedAs("meter registry meters do not contain raw token")
            .doesNotContain(rawTokenValue)

        // 10d. Queue snapshot toString() — no token
        assertThat(afterSnapshot.toString())
            .describedAs("queue snapshot does not contain raw token")
            .doesNotContain(rawTokenValue)

        // ── Step 11: Idempotency — second runOnce() scans 0 ────────────────────
        val secondResult = runBlocking { worker.runOnce(limit = 10) }
        assertThat(secondResult.scanned).describedAs("second run scanned (idempotency)").isZero()
        assertThat(secondResult.resumed).isZero()
        assertThat(secondResult.skipped).isZero()
        assertThat(secondResult.failed).isZero()
    }

    // ── SQL Helpers ─────────────────────────────────────────────────

    private fun sql(sql: String) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt -> stmt.execute(sql) }
        }
    }

    private fun readValue(sql: String, param: String): String? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, param)
                stmt.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString(1) else null
                }
            }
        }
    }

    private fun readText(sql: String, param: String): String? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, param)
                stmt.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString(1) else null
                }
            }
        }
    }

    private fun readInt(sql: String, param: String): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, param)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    return rs.getInt(1)
                }
            }
        }
    }

    private fun update(sql: String, param: String): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, param)
                return stmt.executeUpdate()
            }
        }
    }

    private fun truncateTables() {
        sql("TRUNCATE TABLE approval_continuations, approvals, tramai_approval_resume_credentials CASCADE")
    }

    private fun runMigrations() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                listOf(
                    "tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql",
                    "tramai/persistence/jdbc/postgres/V2__approval_continuations.sql",
                    "tramai/persistence/jdbc/postgres/V6__approval_resume_credential_custody.sql",
                    "tramai/persistence/jdbc/postgres/V7__approval_continuations_resume_retry.sql",
                ).forEach { resource ->
                    val sql = javaClass.classLoader
                        .getResourceAsStream(resource)
                        ?.bufferedReader()
                        ?.readText()
                        ?: error("Migration not found: $resource")
                    stmt.execute(sql)
                }
            }
        }
    }
}
