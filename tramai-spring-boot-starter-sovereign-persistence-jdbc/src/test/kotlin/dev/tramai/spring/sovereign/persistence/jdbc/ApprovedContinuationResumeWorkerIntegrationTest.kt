package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.SealedResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueue
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkItem
import dev.tramai.spring.sovereign.ops.SovereignOpsApprovedContinuationResumeWorker
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalResumeCommand
import dev.tramai.spring.sovereign.ops.ApprovalResumeResult
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
 * Integrated happy-path test for the auto-resume worker.
 *
 * Proves the full chain through real persistence:
 *   JDBC queue claim → worker → control plane → DB state transition → credential cleanup
 *
 * Uses:
 * - Real [JdbcApprovedContinuationResumeQueue] (Testcontainers PostgreSQL)
 * - Real [JdbcApprovalResumeCredentialStore] (encrypted at rest)
 * - Real [SovereignOpsApprovedContinuationResumeWorker]
 * - Custom [ApprovalResumeControlPlane] backed by SQL (reads approval/continuation state,
 *   transitions continuation to COMPLETED, simulates the engine resume)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovedContinuationResumeWorkerIntegrationTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("resume_worker_integration_test")
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
    private lateinit var queue: ApprovedContinuationResumeQueue
    private lateinit var credentialStore: JdbcApprovalResumeCredentialStore
    private lateinit var worker: SovereignOpsApprovedContinuationResumeWorker
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
    fun `approved continuation worker resumes through real control plane after jdbc queue claim`() {
        val approvalId = ApprovalId("e2e-happy-001")
        val workflowRunId = WorkflowRunId("wf-e2e-happy-001")
        val resumeToken = ResumeToken("resume-token-e2e-001")

        // Insert APPROVED approval + PENDING continuation + credential
        sql(
            """INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
               VALUES ('${approvalId.value}', 'APPROVED', now(), '{}'::jsonb, 0)""",
        )
        sql(
            """INSERT INTO approval_continuations
               (approval_id, status, workflow_run_id, correlation_id, tool_call_id, tool_name,
                arguments_digest, policy_version, workflow_digest,
                created_at, approval_expires_at, version)
               VALUES ('${approvalId.value}', 'PENDING', '${workflowRunId.value}',
                       'corr-001', 'tc-001', 'tool-001',
                       'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                       'v1', 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                       now(), now() + interval '5 minutes', 0)""",
        )
        runBlocking {
            credentialStore.create(
                ApprovalResumeCredentialRecord(
                    approvalId = approvalId,
                    workflowRunId = workflowRunId,
                    resumeToken = SealedResumeToken.seal(resumeToken),
                    createdAt = Instant.now(),
                    expiresAt = Instant.now().plusSeconds(300),
                    version = 0L,
                ),
            )
        }

        // Verify pre-conditions
        assertThat(runBlocking { credentialStore.get(approvalId) }).isNotNull
        assertThat(readValue("SELECT status FROM approval_continuations WHERE approval_id = ?", approvalId.value))
            .isEqualTo("PENDING")

        // Build a SQL-backed control plane that:
        // 1. Validates approval is APPROVED and continuation is PENDING (reads from DB)
        // 2. Transitions continuation to COMPLETED (simulating the engine resume)
        val controlPlane = object : ApprovalResumeControlPlane {
            override suspend fun resume(command: ApprovalResumeCommand): ApprovalResumeResult {
                val appStatus = readValue(
                    "SELECT status FROM approvals WHERE approval_id = ?",
                    command.approvalId.value,
                )
                if (appStatus != "APPROVED") {
                    return ApprovalResumeResult.NotApproved(command.approvalId, dev.tramai.core.approval.ApprovalStatus.valueOf(appStatus ?: "PENDING"))
                }

                val contStatus = readValue(
                    "SELECT status FROM approval_continuations WHERE approval_id = ?",
                    command.approvalId.value,
                )
                if (contStatus == null) {
                    return ApprovalResumeResult.Conflict(command.approvalId, "approval-continuation-missing")
                }
                if (contStatus != "PENDING") {
                    if (contStatus == "COMPLETED") {
                        return ApprovalResumeResult.AlreadyCompleted(command.approvalId)
                    }
                    return ApprovalResumeResult.Conflict(
                        command.approvalId,
                        "approval-continuation-not-pending-$contStatus",
                    )
                }

                // Transition to COMPLETED (simulating the real engine)
                val updated = update(
                    """UPDATE approval_continuations
                       SET status = 'COMPLETED', completed_at = now(), version = version + 1
                       WHERE approval_id = ? AND status = 'PENDING'""",
                    command.approvalId.value,
                )
                if (updated == 0) {
                    return ApprovalResumeResult.Conflict(command.approvalId, "approval-continuation-conflict")
                }

                return ApprovalResumeResult.Resumed(
                    approvalId = command.approvalId,
                    resumedBy = command.resumedBy,
                    result = "workflow-resolved",
                )
            }
        }

        worker = SovereignOpsApprovedContinuationResumeWorker(
            queue = queue,
            credentialStore = credentialStore,
            resumeControlPlane = controlPlane,
            workerId = "integration-test-worker",
            leaseDuration = Duration.ofMinutes(2),
            retryDelay = Duration.ofSeconds(30),
            conflictRetryDelay = Duration.ofSeconds(60),
            clock = clock,
        )

        // Act
        val result = runBlocking { worker.runOnce(limit = 10) }

        // Assert
        assertThat(result.scanned).isEqualTo(1)
        assertThat(result.resumed).isEqualTo(1)
        assertThat(result.skipped).isZero()
        assertThat(result.failed).isZero()

        // Continuation is COMPLETED
        assertThat(readValue("SELECT status FROM approval_continuations WHERE approval_id = ?", approvalId.value))
            .isEqualTo("COMPLETED")

        // Credential is deleted
        assertThat(runBlocking { credentialStore.get(approvalId) }).isNull()

        // No resume token exposed in approval metadata
        assertThat(
            readInt(
                """SELECT COUNT(*) FROM approvals
                   WHERE approval_id = ? AND sanitized_metadata::text LIKE '%resume%'""",
                approvalId.value,
            ),
        ).isZero()
    }

    @Test
    fun `worker does not resume when continuation is already completed`() {
        val approvalId = ApprovalId("e2e-already-completed")
        val workflowRunId = WorkflowRunId("wf-e2e-completed")

        sql(
            """INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
               VALUES ('${approvalId.value}', 'APPROVED', now(), '{}'::jsonb, 0)""",
        )
        sql(
            """INSERT INTO approval_continuations
               (approval_id, status, workflow_run_id, correlation_id, tool_call_id, tool_name,
                arguments_digest, policy_version, workflow_digest,
                created_at, approval_expires_at, completed_at, version)
               VALUES ('${approvalId.value}', 'COMPLETED', '${workflowRunId.value}',
                       'corr-002', 'tc-002', 'tool-002',
                       'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                       'v1', 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                       now(), now() + interval '5 minutes', now(), 0)""",
        )

        // Control plane that detects AlreadyCompleted
        val controlPlane = object : ApprovalResumeControlPlane {
            override suspend fun resume(command: ApprovalResumeCommand): ApprovalResumeResult {
                val status = readValue(
                    "SELECT status FROM approval_continuations WHERE approval_id = ?",
                    command.approvalId.value,
                )
                return if (status == "COMPLETED") {
                    ApprovalResumeResult.AlreadyCompleted(command.approvalId)
                } else {
                    ApprovalResumeResult.Conflict(command.approvalId, "unexpected-status-$status")
                }
            }
        }

        worker = SovereignOpsApprovedContinuationResumeWorker(
            queue = queue,
            credentialStore = credentialStore,
            resumeControlPlane = controlPlane,
            workerId = "integration-test-worker",
            leaseDuration = Duration.ofMinutes(2),
            retryDelay = Duration.ofSeconds(30),
            conflictRetryDelay = Duration.ofSeconds(60),
            clock = clock,
        )

        val result = runBlocking { worker.runOnce(limit = 10) }

        // The queue won't return this item because it's COMPLETED, not PENDING
        // So scanned should be 0 (nothing to claim)
        assertThat(result.scanned).isZero()
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
