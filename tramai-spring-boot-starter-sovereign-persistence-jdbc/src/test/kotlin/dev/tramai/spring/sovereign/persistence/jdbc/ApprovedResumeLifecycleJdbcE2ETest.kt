package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.SealedResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueSnapshot
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorker
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeWorkerResult
import dev.tramai.spring.sovereign.ops.SovereignOpsApprovedContinuationResumeWorker
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalResumeCommand
import dev.tramai.spring.sovereign.ops.ApprovalResumeResult
import dev.tramai.spring.sovereign.ops.ApprovedContinuationResumeQueueStatusStore
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
 * via real JDBC persistence.
 *
 * Proves the entire chain:
 *   seed data → worker resumes → credential deleted → queue snapshot updates
 *   → metrics update → no token leaks
 *
 * Uses:
 * - Real [JdbcApprovedContinuationResumeQueue] (Testcontainers PostgreSQL)
 * - Real [JdbcApprovalResumeCredentialStore] (encrypted at rest)
 * - Real [JdbcApprovedContinuationResumeQueueStatusStore] (queue snapshot)
 * - Real [SovereignOpsApprovedContinuationResumeWorker]
 * - Custom [ApprovalResumeControlPlane] backed by SQL
 * - [SimpleMeterRegistry] for metrics assertions
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
    private lateinit var registry: SimpleMeterRegistry
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
        registry = SimpleMeterRegistry()
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
    fun `full approved resume lifecycle with jdbc persistence`() {
        // ── Step 1: Seed data ─────────────────────────────────────
        val approvalId = ApprovalId("e2e-lifecycle-001")
        val workflowRunId = WorkflowRunId("wf-e2e-lifecycle-001")
        val resumeToken = ResumeToken("resume-token-e2e-lifecycle-001")
        val baseNow = clock.instant()

        // Insert APPROVED approval
        sql(
            """INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
               VALUES ('${approvalId.value}', 'APPROVED', now(), '{}'::jsonb, 0)""",
        )

        // Insert PENDING continuation
        sql(
            """INSERT INTO approval_continuations
               (approval_id, status, workflow_run_id, correlation_id, tool_call_id, tool_name,
                arguments_digest, policy_version, workflow_digest,
                created_at, approval_expires_at, version)
               VALUES ('${approvalId.value}', 'PENDING', '${workflowRunId.value}',
                       'corr-lifecycle-001', 'tc-lifecycle-001', 'tool-lifecycle-001',
                       'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                       'v1', 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                       now(), now() + interval '5 minutes', 0)""",
        )

        // Create credential via the real encrypted credential store
        // This proves the encryption path rather than bypassing it with raw SQL
        runBlocking {
            credentialStore.create(
                ApprovalResumeCredentialRecord(
                    approvalId = approvalId,
                    workflowRunId = workflowRunId,
                    resumeToken = SealedResumeToken.seal(resumeToken),
                    createdAt = baseNow,
                    expiresAt = baseNow.plusSeconds(300),
                    version = 0L,
                ),
            )
        }

        // Verify seed data is in place
        assertThat(readValue("SELECT status FROM approvals WHERE approval_id = ?", approvalId.value))
            .isEqualTo("APPROVED")
        assertThat(readValue("SELECT status FROM approval_continuations WHERE approval_id = ?", approvalId.value))
            .isEqualTo("PENDING")
        assertThat(runBlocking { credentialStore.get(approvalId) }).isNotNull

        // ── Step 2: Queue snapshot before worker ──────────────────
        val beforeSnapshot = runBlocking { queueStatusStore.snapshot(baseNow) }
        assertThat(beforeSnapshot.eligibleNow).describedAs("eligibleNow before worker").isEqualTo(1L)
        assertThat(beforeSnapshot.activeLeases).isZero()
        assertThat(beforeSnapshot.terminalFailures).isZero()

        // ── Build control plane ───────────────────────────────────
        val controlPlane = SqlApprovalResumeControlPlane(dataSource)

        // ── Build worker with metrics ─────────────────────────────
        val rawWorker = SovereignOpsApprovedContinuationResumeWorker(
            queue = queue,
            credentialStore = credentialStore,
            resumeControlPlane = controlPlane,
            workerId = "e2e-lifecycle-worker",
            leaseDuration = Duration.ofMinutes(2),
            retryDelay = Duration.ofSeconds(30),
            conflictRetryDelay = Duration.ofSeconds(60),
            clock = clock,
        )
        val worker = MeteredApprovedContinuationResumeWorker(rawWorker, registry)

        // ── Step 3: Run worker ────────────────────────────────────
        val result = runBlocking { worker.runOnce(limit = 10) }

        assertThat(result.scanned).describedAs("scanned items").isEqualTo(1)
        assertThat(result.resumed).describedAs("resumed items").isEqualTo(1)
        assertThat(result.skipped).describedAs("skipped items").isZero()
        assertThat(result.failed).describedAs("failed items").isZero()

        // ── Step 4: Continuation status is COMPLETED ──────────────
        assertThat(
            readValue("SELECT status FROM approval_continuations WHERE approval_id = ?", approvalId.value),
        ).describedAs("continuation status after resume").isEqualTo("COMPLETED")

        // ── Step 5: Credential is deleted ─────────────────────────
        assertThat(runBlocking { credentialStore.get(approvalId) })
            .describedAs("credential should be null after resume")
            .isNull()

        // ── Step 6: Queue snapshot after worker ───────────────────
        val afterSnapshot = runBlocking { queueStatusStore.snapshot(baseNow) }
        assertThat(afterSnapshot.eligibleNow).describedAs("eligibleNow after worker").isZero()
        assertThat(afterSnapshot.terminalFailures).describedAs("terminalFailures").isZero()
        assertThat(afterSnapshot.activeLeases).describedAs("activeLeases").isZero()
        assertThat(afterSnapshot.delayedRetry).isZero()
        assertThat(afterSnapshot.expiredLeases).isZero()

        // ── Step 7: Metrics assertions ────────────────────────────
        val cyclesCompleted = registry.counter("cycles.total", "outcome", "completed")
        assertThat(cyclesCompleted.count()).describedAs("cycles.total{outcome=completed}").isPositive()

        val itemsResumed = registry.counter("items.resumed.total")
        assertThat(itemsResumed.count()).describedAs("items.resumed.total").isPositive()

        // ── Step 8: Idempotency — second worker run ───────────────
        val secondResult = runBlocking { worker.runOnce(limit = 10) }
        assertThat(secondResult.scanned).describedAs("second run scanned (idempotency)").isZero()
        assertThat(secondResult.resumed).isZero()
        assertThat(secondResult.skipped).isZero()
        assertThat(secondResult.failed).isZero()

        // ── Step 9: No resume token leak in sanitized_metadata ────
        val leakCount = readInt(
            """SELECT COUNT(*) FROM approvals
               WHERE approval_id = ? AND sanitized_metadata::text LIKE '%resume%'""",
            approvalId.value,
        )
        assertThat(leakCount).describedAs("resume token leak in sanitized_metadata").isZero()
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

    // ── Inner Types ─────────────────────────────────────────────────

    /**
     * SQL-backed control plane that transitions a PENDING continuation
     * to COMPLETED, simulating the engine resume.
     */
    private class SqlApprovalResumeControlPlane(
        private val dataSource: DataSource,
    ) : ApprovalResumeControlPlane {
        override suspend fun resume(cmd: ApprovalResumeCommand): ApprovalResumeResult {
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE approval_continuations SET status = 'COMPLETED', " +
                    "completed_at = now(), version = version + 1 " +
                    "WHERE approval_id = ? AND status = 'PENDING'",
                ).use { stmt ->
                    stmt.setString(1, cmd.approvalId.value)
                    val updated = stmt.executeUpdate()
                    return if (updated > 0) {
                        ApprovalResumeResult.Resumed(
                            approvalId = cmd.approvalId,
                            resumedBy = cmd.resumedBy,
                            result = "workflow-resolved",
                        )
                    } else {
                        ApprovalResumeResult.Conflict(
                            approvalId = cmd.approvalId,
                            reason = "approval-continuation-not-found-or-already-completed",
                        )
                    }
                }
            }
        }
    }

    /**
     * Metrics-decorated worker that wraps [SovereignOpsApprovedContinuationResumeWorker]
     * and records counters to a [SimpleMeterRegistry].
     */
    private class MeteredApprovedContinuationResumeWorker(
        private val delegate: SovereignOpsApprovedContinuationResumeWorker,
        private val registry: SimpleMeterRegistry,
    ) : ApprovedContinuationResumeWorker {

        private val cyclesCompleted = registry.counter("cycles.total", "outcome", "completed")
        private val itemsResumedTotal = registry.counter("items.resumed.total")

        override suspend fun runOnce(limit: Int): ApprovedContinuationResumeWorkerResult {
            val result = delegate.runOnce(limit)
            cyclesCompleted.increment()
            itemsResumedTotal.increment(result.resumed.toDouble())
            return result
        }
    }
}
