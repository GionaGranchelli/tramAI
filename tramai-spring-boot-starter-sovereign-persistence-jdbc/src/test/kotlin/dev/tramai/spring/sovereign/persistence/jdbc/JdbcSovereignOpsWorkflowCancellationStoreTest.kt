@file:Suppress("MaxLineLength")

package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.orchestration.JdbcWorkflowCheckpointStore
import dev.tramai.orchestration.WorkflowAdmissionRequest
import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowCheckpointConflictException
import dev.tramai.orchestration.WorkflowLifecycleStatus
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.spring.sovereign.ops.AuditEngineSovereignOpsAuditEmitter
import dev.tramai.spring.sovereign.ops.WorkflowCancellationCommand
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatcher
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsWorkflowCancellationConflictException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSovereignOpsWorkflowCancellationStoreTest {
    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val postgres =
            PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("sovereign_ops_wf_cancel_test")
                .withUsername("test")
                .withPassword("test")

        private fun createDataSource(): DataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }

        private val BASE_NOW: Instant = Instant.parse("2026-03-01T12:00:00Z")
    }

    private val testAesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private lateinit var dataSource: DataSource
    private lateinit var checkpointStore: JdbcWorkflowCheckpointStore
    private lateinit var codec: JdbcOpsAuditOutboxPayloadCodec
    private lateinit var store: JdbcSovereignOpsWorkflowCancellationStore

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        dataSource = createDataSource()
        runMigrations()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun setUp() {
        truncateTables()
        checkpointStore = JdbcWorkflowCheckpointStore(dataSource)
        val key = javax.crypto.spec.SecretKeySpec(testAesKey, "AES")
        codec = DefaultJdbcOpsAuditOutboxPayloadCodec(key, "test-key-id")
        store =
            JdbcSovereignOpsWorkflowCancellationStore(
                dataSource = dataSource,
                checkpointStore = checkpointStore,
                payloadCodec = codec,
                clock = Clock.fixed(BASE_NOW, ZoneOffset.UTC),
            )
    }

    @Test
    @Suppress("LongMethod")
    fun `cancelWithAuditIntent commits checkpoint cancellation and pending outbox record atomically`() {
        runBlocking {
            val workflowName = "test-pipeline"
            val runId = "run-001"
            val saved = saveInitialCheckpoint(workflowName, runId)

            val command =
                WorkflowCancellationCommand(
                    workflowName = workflowName,
                    runId = runId,
                    expectedRevision = saved.revision,
                    reason = "Operator requested cancellation",
                )
            val auditRecord = createAuditRecord(command, "event-001")

            val result =
                store.cancelWithAuditIntent(
                    command = command,
                    actor = "ops-user",
                    actorIssuer = "https://id.tramai.dev",
                    actorIdentityDigest = "d".repeat(64),
                    auditIntent = auditRecord,
                )

            assertThat(result.alreadyCancelled).isFalse()
            assertThat(result.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED.wireName)
            assertThat(result.revision).isEqualTo(saved.revision + 1)
            assertThat(result.cancelledBy).isEqualTo("ops-user")
            assertThat(result.cancelledAtEpochMillis).isEqualTo(BASE_NOW.toEpochMilli())

            // Verify outbox state in DB
            val outboxStatus =
                selectSingleValue(
                    "SELECT status FROM audit_outbox WHERE outbox_id = ?",
                    auditRecord.outboxId,
                )
            assertThat(outboxStatus).isEqualTo("PENDING")

            // Verify decoded outbox payload
            val rawCiphertext =
                selectBytes(
                    "SELECT encrypted_payload FROM audit_outbox WHERE outbox_id = ?",
                    auditRecord.outboxId,
                )
            val keyId =
                selectSingleValue(
                    "SELECT encryption_key_id FROM audit_outbox WHERE outbox_id = ?",
                    auditRecord.outboxId,
                )
            val algorithm =
                selectSingleValue(
                    "SELECT encryption_algorithm FROM audit_outbox WHERE outbox_id = ?",
                    auditRecord.outboxId,
                )
            val nonce =
                selectBytes(
                    "SELECT encryption_nonce FROM audit_outbox WHERE outbox_id = ?",
                    auditRecord.outboxId,
                )
            val digest =
                selectSingleValue(
                    "SELECT payload_digest FROM audit_outbox WHERE outbox_id = ?",
                    auditRecord.outboxId,
                )
            val envelope =
                JdbcEncryptedAuditOutboxPayload(
                    ciphertext = rawCiphertext,
                    keyId = keyId,
                    algorithm = algorithm,
                    nonce = nonce,
                    payloadDigest = digest,
                )
            val decoded = decodeOutboxRecord(codec.decode(envelope)).record
            assertThat(decoded.approvalStatus).isEqualTo(WorkflowLifecycleStatus.CANCELLED.wireName)
            assertThat(decoded.approvalVersion).isEqualTo(saved.revision + 1)
            assertThat(decoded.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
        }
    }

    @Test
    fun `cancelWithAuditIntent marks CANCELLED_UNCERTAIN when admissions have occurred`() {
        runBlocking {
            val workflowName = "test-pipeline"
            val runId = "run-002"
            val saved = saveInitialCheckpoint(workflowName, runId)
            // Perform an admission to simulate dispatched effects
            val admission =
                checkpointStore.admit(
                    WorkflowAdmissionRequest(
                        workflowName = workflowName,
                        workflowId = runId,
                        expectedRevision = saved.revision,
                        expectedGeneration = saved.checkpointGeneration,
                    ),
                )
            assertThat(admission.admitted).isTrue()

            val command =
                WorkflowCancellationCommand(
                    workflowName = workflowName,
                    runId = runId,
                    expectedRevision = saved.revision,
                    reason = "Pipeline cancelled after step admissions",
                )
            val auditRecord = createAuditRecord(command, "event-002")

            val result =
                store.cancelWithAuditIntent(
                    command = command,
                    actor = "ops-user",
                    actorIssuer = "https://id.tramai.dev",
                    actorIdentityDigest = "d".repeat(64),
                    auditIntent = auditRecord,
                )

            assertThat(result.alreadyCancelled).isFalse()
            assertThat(result.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED_UNCERTAIN.wireName)
            assertThat(result.revision).isEqualTo(saved.revision + 1)

            val outboxStatus =
                selectSingleValue(
                    "SELECT status FROM audit_outbox WHERE outbox_id = ?",
                    auditRecord.outboxId,
                )
            assertThat(outboxStatus).isEqualTo("PENDING")
        }
    }

    @Test
    fun `cancelWithAuditIntent rolls back atomically on expectedRevision conflict`() {
        runBlocking {
            val workflowName = "test-pipeline"
            val runId = "run-003"
            val saved = saveInitialCheckpoint(workflowName, runId)

            val command =
                WorkflowCancellationCommand(
                    workflowName = workflowName,
                    runId = runId,
                    expectedRevision = saved.revision + 99L, // Stale!
                    reason = "Stale attempt",
                )
            val auditRecord = createAuditRecord(command, "event-003")

            assertThatThrownBy {
                runBlocking {
                    store.cancelWithAuditIntent(
                        command = command,
                        actor = "ops-user",
                        actorIssuer = "https://id.tramai.dev",
                        actorIdentityDigest = "d".repeat(64),
                        auditIntent = auditRecord,
                    )
                }
            }.isInstanceOf(SovereignOpsWorkflowCancellationConflictException::class.java)

            // Workflow remains active at initial revision
            val current = checkpointStore.load(workflowName, runId)
            assertThat(current?.revision).isEqualTo(saved.revision)

            // Zero outbox rows created
            val outboxCount = selectCount("SELECT count(*) FROM audit_outbox WHERE event_key = 'event-003'")
            assertThat(outboxCount).isEqualTo(0)
        }
    }

    @Test
    fun `cancelWithAuditIntent is idempotent and returns alreadyCancelled when previously cancelled`() {
        runBlocking {
            val workflowName = "test-pipeline"
            val runId = "run-004"
            val saved = saveInitialCheckpoint(workflowName, runId)

            val command =
                WorkflowCancellationCommand(
                    workflowName = workflowName,
                    runId = runId,
                    expectedRevision = saved.revision,
                    reason = "Initial cancel",
                )
            val auditRecord1 = createAuditRecord(command, "event-004-1")

            val result1 =
                store.cancelWithAuditIntent(
                    command = command,
                    actor = "ops-user",
                    actorIssuer = "https://id.tramai.dev",
                    actorIdentityDigest = "d".repeat(64),
                    auditIntent = auditRecord1,
                )
            assertThat(result1.alreadyCancelled).isFalse()

            // Second call with newer expected revision (since revision incremented)
            val command2 = command.copy(expectedRevision = result1.revision, reason = "Repeat cancel")
            val auditRecord2 = createAuditRecord(command2, "event-004-2")
            val result2 =
                store.cancelWithAuditIntent(
                    command = command2,
                    actor = "ops-user",
                    actorIssuer = "https://id.tramai.dev",
                    actorIdentityDigest = "d".repeat(64),
                    auditIntent = auditRecord2,
                )

            assertThat(result2.alreadyCancelled).isTrue()
            assertThat(result2.status).isEqualTo(WorkflowLifecycleStatus.CANCELLED.wireName)
            assertThat(result2.revision).isEqualTo(result1.revision)

            // Only the first outbox record exists
            val outboxCount2 = selectCount("SELECT count(*) FROM audit_outbox WHERE event_key = 'event-004-2'")
            assertThat(outboxCount2).isEqualTo(0)
        }
    }

    @Test
    fun `failing codec rolls back cancellation and outbox record completely`() {
        runBlocking {
            val workflowName = "test-pipeline"
            val runId = "run-005"
            val saved = saveInitialCheckpoint(workflowName, runId)

            val failingCodec =
                object : JdbcOpsAuditOutboxPayloadCodec {
                    override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload = error("codec encryption failed")

                    override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray = envelope.ciphertext
                }

            val failingStore =
                JdbcSovereignOpsWorkflowCancellationStore(
                    dataSource = dataSource,
                    checkpointStore = checkpointStore,
                    payloadCodec = failingCodec,
                    clock = Clock.fixed(BASE_NOW, ZoneOffset.UTC),
                )

            val command =
                WorkflowCancellationCommand(
                    workflowName = workflowName,
                    runId = runId,
                    expectedRevision = saved.revision,
                    reason = "Should rollback",
                )
            val auditRecord = createAuditRecord(command, "event-005")

            assertThatThrownBy {
                runBlocking {
                    failingStore.cancelWithAuditIntent(
                        command = command,
                        actor = "ops-user",
                        actorIssuer = "https://id.tramai.dev",
                        actorIdentityDigest = "d".repeat(64),
                        auditIntent = auditRecord,
                    )
                }
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("codec encryption failed")

            // Verify checkpoint was rolled back and is NOT cancelled
            val current = checkpointStore.load(workflowName, runId)
            assertThat(current?.revision).isEqualTo(saved.revision)

            // Zero outbox rows created
            val count = selectCount("SELECT count(*) FROM audit_outbox WHERE event_key = 'event-005'")
            assertThat(count).isEqualTo(0)
        }
    }

    @Test
    fun `cancelWithAuditIntent outbox record dispatches cleanly through real AuditEngineSovereignOpsAuditEmitter to AuditStore`() {
        runBlocking {
            val workflowName = "test-pipeline"
            val runId = "run-006"
            val saved = saveInitialCheckpoint(workflowName, runId)

            val command =
                WorkflowCancellationCommand(
                    workflowName = workflowName,
                    runId = runId,
                    expectedRevision = saved.revision,
                    reason = "Transactional cancellation test",
                )
            val auditRecord = createAuditRecord(command, "event-006")

            val mutationResult =
                store.cancelWithAuditIntent(
                    command = command,
                    actor = "ops-user",
                    actorIssuer = "https://id.tramai.dev",
                    actorIdentityDigest = "d".repeat(64),
                    auditIntent = auditRecord,
                )

            assertThat(mutationResult.alreadyCancelled).isFalse()
            assertThat(mutationResult.status).isEqualTo("cancelled")

            // Outbox record is PENDING in PostgreSQL
            val pendingStatus = selectSingleValue("SELECT status FROM audit_outbox WHERE event_key = ?", "event-006")
            assertThat(pendingStatus).isEqualTo("PENDING")

            // Dispatch through real outbox store and real AuditEngineSovereignOpsAuditEmitter
            val outboxStore = JdbcSovereignOpsAuditOutboxStore(dataSource = dataSource, payloadCodec = codec)
            val auditStore = InMemoryAuditStore()
            val auditEngine = AuditEngine(auditStore, clock = Clock.fixed(BASE_NOW, ZoneOffset.UTC))
            val emitter = AuditEngineSovereignOpsAuditEmitter(auditEngine)
            val dispatcher =
                SovereignOpsAuditOutboxDispatcher(
                    outboxStore = outboxStore,
                    auditEmitter = emitter,
                    clock = Clock.fixed(BASE_NOW, ZoneOffset.UTC),
                )

            val dispatchResult = dispatcher.dispatchPending(limit = 10)
            assertThat(dispatchResult.claimed).isEqualTo(1)
            assertThat(dispatchResult.emitted).isEqualTo(1)
            assertThat(dispatchResult.failedPermanent).isEqualTo(0)
            assertThat(dispatchResult.failedRetryable).isEqualTo(0)

            // Database outbox row is now EMITTED
            val finalStatus = selectSingleValue("SELECT status FROM audit_outbox WHERE event_key = ?", "event-006")
            assertThat(finalStatus).isEqualTo("EMITTED")

            // Real AuditEvent is in the workflow stream
            val expectedStream = "sovereign-ops-workflow:${auditRecord.aggregateIdDigest}"
            val events = auditStore.readStream(expectedStream)
            assertThat(events).hasSize(1)
            assertEmittedWorkflowCancellationEvent(
                event = events.single(),
                runId = runId,
                aggregateIdDigest = auditRecord.aggregateIdDigest,
                revision = mutationResult.revision,
                previousRevision = saved.revision,
            )
        }
    }

    private fun assertEmittedWorkflowCancellationEvent(
        event: dev.tramai.security.audit.AuditEvent,
        runId: String,
        aggregateIdDigest: String,
        revision: Long,
        previousRevision: Long = revision - 1L,
    ) {
        assertThat(event.actor).isEqualTo("ops-user")
        assertThat(event.workflowRunId).isEqualTo(runId)
        assertThat(event.enforcementPoint).isEqualTo("sovereign-ops.workflow.cancel")
        assertThat(event.decision).isEqualTo("CANCELLED")
        assertThat(event.reasonCode).isEqualTo("sovereign-ops-workflow-cancelled")

        // Workflow-specific metadata
        assertThat(event.metadata).containsEntry("workflowTargetDigest", aggregateIdDigest)
        assertThat(event.metadata).containsEntry("workflowStatus", "cancelled")
        assertThat(event.metadata).containsEntry("workflowRevision", revision.toString())
        assertThat(event.metadata).containsEntry("previousWorkflowStatus", "ACTIVE")
        assertThat(event.metadata).containsEntry("previousWorkflowRevision", previousRevision.toString())
        assertThat(event.metadata).containsEntry("actorIssuer", "https://id.tramai.dev")
        assertThat(event.metadata).containsEntry("actorIdentityDigest", "d".repeat(64))

        // Misleading approval labels are omitted
        assertThat(event.metadata).doesNotContainKey("approvalIdDigest")
        assertThat(event.metadata).doesNotContainKey("approvalStatus")
        assertThat(event.metadata).doesNotContainKey("approvalVersion")
        assertThat(event.metadata).doesNotContainKey("previousApprovalStatus")
        assertThat(event.metadata).doesNotContainKey("previousApprovalVersion")
        assertThat(event.metadata).doesNotContainKey("refusalReason")
    }

    private suspend fun saveInitialCheckpoint(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint {
        val checkpoint =
            WorkflowCheckpoint(
                workflowName = workflowName,
                workflowId = workflowId,
                nextStepIndex = 0,
                stepExecutions = 0,
                lastCompletedStepName = null,
                statePayload = "{}",
                savedAtEpochMillis = BASE_NOW.toEpochMilli(),
            )
        return checkpointStore.save(checkpoint)
    }

    private fun createAuditRecord(
        command: WorkflowCancellationCommand,
        eventKey: String,
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = "outbox-${command.runId}",
            aggregateType = "workflow",
            aggregateIdDigest = "a".repeat(64),
            operation = "cancelWorkflow",
            eventKey = eventKey,
            actor = "ops-user",
            actorIssuer = "https://id.tramai.dev",
            actorIdentityDigest = "d".repeat(64),
            workflowRunId = command.runId,
            correlationId = command.correlationId,
            approvalStatus = null,
            approvalVersion = null,
            previousApprovalStatus = "ACTIVE",
            previousApprovalVersion = command.expectedRevision,
            reasonDigest = "r".repeat(64),
            reasonLength = command.reason.length,
            createdAt = BASE_NOW,
            status = SovereignOpsAuditOutboxStatus.PREPARED,
            evidenceKind = SovereignOpsAuditEvidenceKind.WORKFLOW_CANCELLED,
        )

    private fun selectSingleValue(
        sql: String,
        param: String,
    ): String =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, param)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getString(1)
                }
            }
        }

    private fun selectBytes(
        sql: String,
        param: String,
    ): ByteArray =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, param)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getBytes(1)
                }
            }
        }

    private fun selectCount(sql: String): Int =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }

    private fun truncateTables() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE TABLE audit_outbox CASCADE")
                stmt.execute("TRUNCATE TABLE tramai_workflow_checkpoint CASCADE")
            }
        }
    }

    private fun runMigrations() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val v1 =
                    javaClass.classLoader
                        .getResourceAsStream("tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql")
                        ?.bufferedReader()
                        ?.readText()
                        ?: error("V1 migration not found")
                stmt.execute(v1)
                val v4 =
                    javaClass.classLoader
                        .getResourceAsStream("tramai/persistence/jdbc/postgres/V4__audit_outbox_hardening.sql")
                        ?.bufferedReader()
                        ?.readText()
                        ?: error("V4 migration not found")
                runCatching { stmt.execute(v4) }
                val v10 =
                    javaClass.classLoader
                        .getResourceAsStream("tramai/persistence/jdbc/postgres/V10__approval_actor_provenance.sql")
                        ?.bufferedReader()
                        ?.readText()
                        ?: error("V10 migration not found")
                runCatching { stmt.execute(v10) }

                // Create workflow checkpoints table
                stmt.execute(JdbcWorkflowCheckpointStore(dataSource).createTableSql())
            }
        }
    }
}
