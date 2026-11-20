package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.operator.ActorIdentityDigest
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseAcquisition
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseRelease
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLException
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/**
 * Executable least-privilege matrix qualification against real PostgreSQL.
 *
 * Verifies that the minimum database permissions documented in the Sovereign Operator Control Plane
 * integration guide are both necessary and sufficient:
 * 1. Approval Decision API Runtime:
 *    - Required: SELECT, UPDATE on approvals; SELECT on approval_continuations; INSERT, UPDATE on audit_outbox.
 *    - Discriminator: Omitting UPDATE on audit_outbox breaks markPreparedOutboxPending (status PREPARED -> PENDING).
 * 2. Continuation Resume Worker:
 *    - Required: SELECT, UPDATE on approvals; SELECT, UPDATE on approval_continuations;
 *      SELECT, UPDATE, DELETE on tramai_approval_resume_credentials; SELECT, INSERT, UPDATE, DELETE on worker_leases.
 *    - Discriminator 1: Omitting UPDATE on approvals fails claimApprovedPending because PostgreSQL
 *      FOR UPDATE SKIP LOCKED across joined tables requires UPDATE privileges on all joined tables.
 *    - Discriminator 2: Omitting UPDATE on tramai_approval_resume_credentials fails claimApprovedPending
 *      for the same reason.
 * 3. Audit Outbox Dispatcher:
 *    - Required: SELECT, UPDATE on audit_outbox; worker_leases permissions.
 */
@Suppress("LongMethod", "LargeClass", "MagicNumber")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseLeastPrivilegeQualificationTest {
    private lateinit var adminDataSource: DataSource
    private lateinit var codec: JdbcOpsAuditOutboxPayloadCodec

    private val clock: Clock = Clock.fixed(BASE_NOW, ZoneOffset.UTC)
    private val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        adminDataSource = dataSourceFor(postgres.username, postgres.password)
        runMigrations()
        provisionRestrictedRoles()
        codec = createTestCodec()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun truncateTables() {
        adminDataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "TRUNCATE TABLE audit_outbox, approvals, approval_continuations, " +
                        "tramai_approval_resume_credentials, worker_leases CASCADE",
                )
            }
        }
    }

    // ── Approval Decision API Runtime ──────────────────────────────────────────

    @Test
    fun `approval decision api succeeds with minimal documented privileges`() {
        val approvalId = "appr-least-priv-1"
        insertApprovalAdmin(approvalId, version = 1, status = "PENDING")

        val restrictedDataSource = dataSourceFor("role_decision_api", "decision_pw")
        val decisionStore =
            JdbcSovereignOpsApprovalMutationStore(
                dataSource = restrictedDataSource,
                payloadCodec = codec,
                clock = clock,
            )

        val intent = auditIntent(approvalId, "evt-decision-1")
        val result =
            runBlocking {
                decisionStore.approveApprovalWithAuditIntent(
                    approvalId = approvalId,
                    expectedVersion = 1,
                    actor = "operator-alice",
                    reason = "Verified change",
                    auditIntent = intent,
                )
            }

        assertThat(result.approval.approvalId).isEqualTo(approvalId)
        assertThat(result.approval.status.name).isEqualTo("APPROVED")
        assertThat(result.auditOutboxRecord.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)

        // Verify database state directly via admin connection
        assertThat(selectApprovalStatusAdmin(approvalId)).isEqualTo("APPROVED")
        assertThat(selectApprovalVersionAdmin(approvalId)).isEqualTo(2)
        assertThat(selectOutboxStatusAdmin(intent.outboxId)).isEqualTo("PENDING")
    }

    @Test
    fun `approval decision api fails when UPDATE on audit_outbox is revoked`() {
        val approvalId = "appr-least-priv-no-upd"
        insertApprovalAdmin(approvalId, version = 1, status = "PENDING")

        val restrictedDataSource = dataSourceFor("role_decision_no_update", "decision_no_update_pw")
        val decisionStore =
            JdbcSovereignOpsApprovalMutationStore(
                dataSource = restrictedDataSource,
                payloadCodec = codec,
                clock = clock,
            )

        val intent = auditIntent(approvalId, "evt-decision-no-upd")

        // Discriminator: Attempting to approve must fail with PostgreSQL permission denied (42501)
        // on audit_outbox because markPreparedOutboxPending executes UPDATE audit_outbox.
        val exception =
            assertThrows<Throwable> {
                runBlocking {
                    decisionStore.approveApprovalWithAuditIntent(
                        approvalId = approvalId,
                        expectedVersion = 1,
                        actor = "operator-alice",
                        reason = "Should fail",
                        auditIntent = intent,
                    )
                }
            }

        val rootCause = generateSequence(exception) { it.cause }.last()
        assertThat(rootCause).isInstanceOf(PSQLException::class.java)
        val psqlException = rootCause as PSQLException
        assertThat(psqlException.sqlState).isEqualTo("42501")
        assertThat(psqlException.message).containsIgnoringCase("audit_outbox")

        // Verify transaction was rolled back and approval remains PENDING at version 1
        assertThat(selectApprovalStatusAdmin(approvalId)).isEqualTo("PENDING")
        assertThat(selectApprovalVersionAdmin(approvalId)).isEqualTo(1)
    }

    // ── Continuation Resume Worker ─────────────────────────────────────────────

    @Test
    fun `continuation resume worker succeeds with minimal documented privileges`() {
        val approvalId = "appr-resume-1"
        val runId = "run-resume-1"
        insertApprovalAdmin(approvalId, version = 2, status = "APPROVED")
        insertContinuationAdmin(approvalId, runId, version = 1, status = "PENDING")
        insertCredentialAdmin(approvalId, runId)

        val workerDataSource = dataSourceFor("role_resume_worker", "resume_pw")
        val resumeQueue = JdbcApprovedContinuationResumeQueue(dataSource = workerDataSource, clock = clock)
        val leaseStore = JdbcSovereignOpsWorkerLeaseStore(dataSource = workerDataSource)
        val credentialStore =
            JdbcApprovalResumeCredentialStore(
                dataSourceProvider = { workerDataSource.connection },
                key = SecretKeySpec(keyBytes, "AES"),
            )

        // 1. Acquire lease
        val leaseAcquired =
            runBlocking {
                leaseStore.tryAcquire("resume-worker-lease", "worker-1", BASE_NOW, Duration.ofMinutes(5))
            }
        assertThat(leaseAcquired).isInstanceOf(SovereignOpsWorkerLeaseAcquisition.Acquired::class.java)

        // 2. Claim next batch with FOR UPDATE SKIP LOCKED
        val claimed =
            runBlocking {
                resumeQueue.claimApprovedPending(
                    workerId = "worker-1",
                    limit = 10,
                    leaseUntil = BASE_NOW.plusSeconds(300),
                )
            }
        assertThat(claimed).hasSize(1)
        assertThat(claimed.first().approvalId.value).isEqualTo(approvalId)

        // 3. Mark continuation succeeded
        runBlocking {
            resumeQueue.markResumeSucceeded(ApprovalId(approvalId), "worker-1")
        }
        assertThat(selectContinuationStatusAdmin(approvalId)).isEqualTo("COMPLETED")

        // 4. Delete consumed credential
        runBlocking {
            credentialStore.delete(ApprovalId(approvalId))
        }
        assertThat(credentialExistsAdmin(approvalId)).isFalse()

        // 5. Release lease
        val leaseReleased =
            runBlocking {
                leaseStore.release("resume-worker-lease", "worker-1", BASE_NOW)
            }
        assertThat(leaseReleased).isEqualTo(SovereignOpsWorkerLeaseRelease.Released)
    }

    @Test
    fun `continuation resume worker fails when UPDATE on approvals is revoked`() {
        val approvalId = "appr-resume-no-appr-upd"
        val runId = "run-resume-no-appr-upd"
        insertApprovalAdmin(approvalId, version = 2, status = "APPROVED")
        insertContinuationAdmin(approvalId, runId, version = 1, status = "PENDING")
        insertCredentialAdmin(approvalId, runId)

        val restrictedDataSource = dataSourceFor("role_resume_no_approvals_update", "resume_no_appr_pw")
        val resumeQueue = JdbcApprovedContinuationResumeQueue(dataSource = restrictedDataSource, clock = clock)

        // Discriminator: In PostgreSQL, SELECT ... FOR UPDATE SKIP LOCKED across joins locks rows
        // in ALL joined tables unless "OF table" is specified. Omitting UPDATE on approvals causes 42501.
        val exception =
            assertThrows<Throwable> {
                runBlocking {
                    resumeQueue.claimApprovedPending(
                        workerId = "worker-1",
                        limit = 10,
                        leaseUntil = BASE_NOW.plusSeconds(300),
                    )
                }
            }

        val rootCause = generateSequence(exception) { it.cause }.last()
        assertThat(rootCause).isInstanceOf(PSQLException::class.java)
        val psqlException = rootCause as PSQLException
        assertThat(psqlException.sqlState).isEqualTo("42501")
        assertThat(psqlException.message).containsIgnoringCase("approvals")
    }

    @Test
    fun `continuation resume worker fails when UPDATE on tramai_approval_resume_credentials is revoked`() {
        val approvalId = "appr-resume-no-cred-upd"
        val runId = "run-resume-no-cred-upd"
        insertApprovalAdmin(approvalId, version = 2, status = "APPROVED")
        insertContinuationAdmin(approvalId, runId, version = 1, status = "PENDING")
        insertCredentialAdmin(approvalId, runId)

        val restrictedDataSource = dataSourceFor("role_resume_no_credentials_update", "resume_no_cred_pw")
        val resumeQueue = JdbcApprovedContinuationResumeQueue(dataSource = restrictedDataSource, clock = clock)

        // Discriminator: Omitting UPDATE on tramai_approval_resume_credentials causes 42501
        // because FOR UPDATE SKIP LOCKED locks the joined credentials table as well.
        val exception =
            assertThrows<Throwable> {
                runBlocking {
                    resumeQueue.claimApprovedPending(
                        workerId = "worker-1",
                        limit = 10,
                        leaseUntil = BASE_NOW.plusSeconds(300),
                    )
                }
            }

        val rootCause = generateSequence(exception) { it.cause }.last()
        assertThat(rootCause).isInstanceOf(PSQLException::class.java)
        val psqlException = rootCause as PSQLException
        assertThat(psqlException.sqlState).isEqualTo("42501")
        assertThat(psqlException.message).containsIgnoringCase("tramai_approval_resume_credentials")
    }

    // ── Audit Outbox Dispatcher ────────────────────────────────────────────────

    @Test
    fun `audit outbox dispatcher succeeds with minimal documented privileges`() {
        val record = auditIntent("appr-disp-1", "evt-disp-1").copy(status = SovereignOpsAuditOutboxStatus.PENDING)
        insertPendingOutboxRecordAdmin(record)

        val dispatcherDataSource = dataSourceFor("role_dispatcher", "dispatcher_pw")
        val outboxStore =
            JdbcSovereignOpsAuditOutboxStore(
                dataSource = dispatcherDataSource,
                payloadCodec = codec,
                claimLeaseDuration = Duration.ofMinutes(5),
            )

        val claimed =
            runBlocking {
                outboxStore.claimPending("dispatcher-1", 10, BASE_NOW)
            }
        assertThat(claimed).hasSize(1)
        assertThat(claimed.first().outboxId).isEqualTo(record.outboxId)

        val emitted =
            runBlocking {
                outboxStore.markEmitted(
                    outboxId = record.outboxId,
                    expectedStatus = SovereignOpsAuditOutboxStatus.EMITTING,
                    expectedAttemptCount = claimed.first().attemptCount,
                    emittedAt = BASE_NOW.plusSeconds(1),
                )
            }
        assertThat(emitted.status).isEqualTo(SovereignOpsAuditOutboxStatus.EMITTED)
        assertThat(selectOutboxStatusAdmin(record.outboxId)).isEqualTo("EMITTED")
    }

    // ── Helper functions and database fixtures ─────────────────────────────────

    private fun dataSourceFor(
        user: String,
        pass: String,
    ): DataSource =
        PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            this.user = user
            password = pass
        }

    private fun provisionRestrictedRoles() {
        adminDataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    DO $$
                    BEGIN
                        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'role_decision_api') THEN
                            CREATE ROLE role_decision_api WITH LOGIN PASSWORD 'decision_pw';
                        END IF;
                        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'role_decision_no_update') THEN
                            CREATE ROLE role_decision_no_update WITH LOGIN PASSWORD 'decision_no_update_pw';
                        END IF;
                        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'role_resume_worker') THEN
                            CREATE ROLE role_resume_worker WITH LOGIN PASSWORD 'resume_pw';
                        END IF;
                        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'role_resume_no_approvals_update') THEN
                            CREATE ROLE role_resume_no_approvals_update WITH LOGIN PASSWORD 'resume_no_appr_pw';
                        END IF;
                        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'role_resume_no_credentials_update') THEN
                            CREATE ROLE role_resume_no_credentials_update WITH LOGIN PASSWORD 'resume_no_cred_pw';
                        END IF;
                        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'role_dispatcher') THEN
                            CREATE ROLE role_dispatcher WITH LOGIN PASSWORD 'dispatcher_pw';
                        END IF;
                    END $$;
                    """.trimIndent(),
                )

                // Ensure public has no accidental table grants
                stmt.execute("REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC")
                stmt.execute("REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC")

                stmt.execute(
                    "GRANT USAGE ON SCHEMA public TO " +
                        "role_decision_api, role_decision_no_update, role_resume_worker, " +
                        "role_resume_no_approvals_update, role_resume_no_credentials_update, role_dispatcher",
                )

                // 1. Approval Decision API Runtime
                stmt.execute("GRANT SELECT, UPDATE ON approvals TO role_decision_api")
                stmt.execute("GRANT SELECT ON approval_continuations TO role_decision_api")
                stmt.execute("GRANT SELECT, INSERT, UPDATE ON audit_outbox TO role_decision_api")

                // 1a. Discriminator: lacks UPDATE on audit_outbox (only SELECT, INSERT)
                stmt.execute("GRANT SELECT, UPDATE ON approvals TO role_decision_no_update")
                stmt.execute("GRANT SELECT ON approval_continuations TO role_decision_no_update")
                stmt.execute("GRANT SELECT, INSERT ON audit_outbox TO role_decision_no_update")

                // 2. Continuation Resume Worker
                stmt.execute("GRANT SELECT, UPDATE ON approvals TO role_resume_worker")
                stmt.execute("GRANT SELECT, UPDATE ON approval_continuations TO role_resume_worker")
                stmt.execute("GRANT SELECT, UPDATE, DELETE ON tramai_approval_resume_credentials TO role_resume_worker")
                stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON worker_leases TO role_resume_worker")

                // 2a. Discriminator: lacks UPDATE on approvals
                stmt.execute("GRANT SELECT ON approvals TO role_resume_no_approvals_update")
                stmt.execute("GRANT SELECT, UPDATE ON approval_continuations TO role_resume_no_approvals_update")
                stmt.execute(
                    "GRANT SELECT, UPDATE, DELETE ON tramai_approval_resume_credentials " +
                        "TO role_resume_no_approvals_update",
                )
                stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON worker_leases TO role_resume_no_approvals_update")

                // 2b. Discriminator: lacks UPDATE on tramai_approval_resume_credentials
                stmt.execute("GRANT SELECT, UPDATE ON approvals TO role_resume_no_credentials_update")
                stmt.execute("GRANT SELECT, UPDATE ON approval_continuations TO role_resume_no_credentials_update")
                stmt.execute(
                    "GRANT SELECT, DELETE ON tramai_approval_resume_credentials " +
                        "TO role_resume_no_credentials_update",
                )
                stmt.execute(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON worker_leases " +
                        "TO role_resume_no_credentials_update",
                )

                // 3. Audit Outbox Dispatcher
                stmt.execute("GRANT SELECT, UPDATE ON audit_outbox TO role_dispatcher")
                stmt.execute("GRANT SELECT, INSERT ON audit_events TO role_dispatcher")
                stmt.execute("GRANT SELECT, INSERT, UPDATE ON audit_stream_heads TO role_dispatcher")
                stmt.execute("GRANT SELECT, INSERT ON audit_outbox_quarantine TO role_dispatcher")
                stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON worker_leases TO role_dispatcher")
            }
        }
    }

    private fun runMigrations() {
        adminDataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                listOf(
                    "tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql",
                    "tramai/persistence/jdbc/postgres/V2__approval_continuations.sql",
                    "tramai/persistence/jdbc/postgres/V3__audit_events_hardening.sql",
                    "tramai/persistence/jdbc/postgres/V4__audit_outbox_hardening.sql",
                    "tramai/persistence/jdbc/postgres/V5__worker_leases_hardening.sql",
                    "tramai/persistence/jdbc/postgres/V6__approval_resume_credential_custody.sql",
                    "tramai/persistence/jdbc/postgres/V7__approval_continuations_resume_retry.sql",
                    "tramai/persistence/jdbc/postgres/V8__control_plane_workload_registration.sql",
                    "tramai/persistence/jdbc/postgres/V9__model_attempt_observations.sql",
                    "tramai/persistence/jdbc/postgres/V10__approval_actor_provenance.sql",
                ).forEach { resource ->
                    val sql =
                        javaClass.classLoader
                            .getResourceAsStream(resource)
                            ?.bufferedReader()
                            ?.readText()
                            ?: error("Migration not found: $resource")
                    stmt.execute(sql)
                }
            }
        }
    }

    private fun insertApprovalAdmin(
        approvalId: String,
        version: Long = 1,
        status: String = "PENDING",
    ) {
        val metadata =
            mapOf(
                "binding" to
                    mapOf(
                        "workflowRunId" to "run-least-priv-1",
                        "toolName" to "test-tool",
                        "argumentsDigest" to "sha256:1111111111111111111111111111111111111111111111111111111111111111",
                        "policyVersion" to "1.0.0",
                        "workflowDigest" to "sha256:2222222222222222222222222222222222222222222222222222222222222222",
                        "approvalTokenDigest" to
                            "sha256:3333333333333333333333333333333333333333333333333333333333333333",
                    ),
                "requestedBy" to "test-user",
                "expiresAt" to BASE_NOW.plusSeconds(3600).toString(),
                "requestedAt" to BASE_NOW.toString(),
            )
        val metadataJson = mapper.writeValueAsString(metadata)

        adminDataSource.connection.use { conn ->
            val sql =
                """
                INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, approvalId)
                stmt.setString(2, status)
                stmt.setTimestamp(3, Timestamp.from(BASE_NOW))
                stmt.setString(4, metadataJson)
                stmt.setLong(5, version)
                stmt.executeUpdate()
            }
        }
    }

    private fun insertContinuationAdmin(
        approvalId: String,
        workflowRunId: String,
        version: Long = 1,
        status: String = "PENDING",
    ) {
        adminDataSource.connection.use { conn ->
            val sql =
                """
                INSERT INTO approval_continuations (
                    approval_id, status, workflow_run_id, correlation_id, tool_call_id, tool_name,
                    arguments_digest, policy_version, workflow_digest,
                    created_at, approval_expires_at, version
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'v1', 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    ?, ?, ?
                )
                """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, approvalId)
                stmt.setString(2, status)
                stmt.setString(3, workflowRunId)
                stmt.setString(4, "corr-$approvalId")
                stmt.setString(5, "tc-$approvalId")
                stmt.setString(6, "tool-$approvalId")
                stmt.setTimestamp(7, Timestamp.from(BASE_NOW))
                stmt.setTimestamp(8, Timestamp.from(BASE_NOW.plusSeconds(3600)))
                stmt.setLong(9, version)
                stmt.executeUpdate()
            }
        }
    }

    private fun insertCredentialAdmin(
        approvalId: String,
        workflowRunId: String,
    ) {
        adminDataSource.connection.use { conn ->
            val sql =
                """
                INSERT INTO tramai_approval_resume_credentials (
                    approval_id, workflow_run_id, encrypted_resume_token,
                    encryption_key_id, encryption_algorithm, encryption_nonce,
                    payload_digest, created_at, expires_at, version
                ) VALUES (
                    ?, ?, decode('74657374', 'hex'),
                    'test-key', 'AES-256-GCM', decode('000000000000000000000000', 'hex'),
                    'sha256:0000000000000000000000000000000000000000000000000000000000000000',
                    ?, ?, 0
                )
                """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, approvalId)
                stmt.setString(2, workflowRunId)
                stmt.setTimestamp(3, Timestamp.from(BASE_NOW))
                stmt.setTimestamp(4, Timestamp.from(BASE_NOW.plusSeconds(3600)))
                stmt.executeUpdate()
            }
        }
    }

    private fun insertPendingOutboxRecordAdmin(record: SovereignOpsAuditOutboxRecord) {
        val encrypted = codec.encode(encodeOutboxRecord(record, null))
        adminDataSource.connection.use { conn ->
            val sql =
                """
                INSERT INTO audit_outbox (
                    outbox_id, event_key, status, correlation_key_hash,
                    created_at, attempt_count,
                    encrypted_payload, encryption_key_id, encryption_algorithm,
                    encryption_nonce, payload_digest, version
                ) VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 1)
                """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, record.outboxId)
                stmt.setString(2, record.eventKey)
                stmt.setString(3, record.status.name)
                stmt.setString(4, record.aggregateIdDigest)
                stmt.setTimestamp(5, Timestamp.from(record.createdAt))
                stmt.setBytes(6, encrypted.ciphertext)
                stmt.setString(7, encrypted.keyId)
                stmt.setString(8, encrypted.algorithm)
                stmt.setBytes(9, encrypted.nonce)
                stmt.setString(10, encrypted.payloadDigest)
                stmt.executeUpdate()
            }
        }
    }

    private fun selectApprovalStatusAdmin(approvalId: String): String =
        adminDataSource.connection.use { conn ->
            conn.prepareStatement("SELECT status FROM approvals WHERE approval_id = ?").use { stmt ->
                stmt.setString(1, approvalId)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getString(1)
                }
            }
        }

    private fun selectApprovalVersionAdmin(approvalId: String): Long =
        adminDataSource.connection.use { conn ->
            conn.prepareStatement("SELECT version FROM approvals WHERE approval_id = ?").use { stmt ->
                stmt.setString(1, approvalId)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getLong(1)
                }
            }
        }

    private fun selectOutboxStatusAdmin(outboxId: String): String =
        adminDataSource.connection.use { conn ->
            conn.prepareStatement("SELECT status FROM audit_outbox WHERE outbox_id = ?").use { stmt ->
                stmt.setString(1, outboxId)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getString(1)
                }
            }
        }

    private fun selectContinuationStatusAdmin(approvalId: String): String =
        adminDataSource.connection.use { conn ->
            conn.prepareStatement("SELECT status FROM approval_continuations WHERE approval_id = ?").use { stmt ->
                stmt.setString(1, approvalId)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getString(1)
                }
            }
        }

    private fun credentialExistsAdmin(approvalId: String): Boolean =
        adminDataSource.connection.use { conn ->
            val sql = "SELECT 1 FROM tramai_approval_resume_credentials WHERE approval_id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, approvalId)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }

    private fun auditIntent(
        approvalId: String,
        eventKey: String,
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = UUID.randomUUID().toString(),
            eventKey = eventKey,
            aggregateIdDigest = sha256Hex(approvalId),
            actor = "operator-alice",
            workflowRunId = null,
            correlationId = null,
            approvalStatus = "PENDING",
            approvalVersion = 1,
            reasonDigest = sha256Hex("reason"),
            reasonLength = 6,
            createdAt = BASE_NOW,
            actorIssuer = "https://idp.example.com",
            actorIdentityDigest = ActorIdentityDigest.of("https://idp.example.com", "operator-alice"),
        )

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return "sha256:${hash.joinToString("") { "%02x".format(it) }}"
    }

    private fun createTestCodec(): JdbcOpsAuditOutboxPayloadCodec =
        object : JdbcOpsAuditOutboxPayloadCodec {
            override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
                val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
                val ciphertext = cipher.doFinal(plaintext)
                val digest =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(plaintext)
                        .joinToString("") { "%02x".format(it) }
                return JdbcEncryptedAuditOutboxPayload(
                    ciphertext = ciphertext,
                    keyId = "test-key-1",
                    algorithm = "AES-256-GCM",
                    nonce = nonce,
                    payloadDigest = "sha256:$digest",
                )
            }

            override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, envelope.nonce))
                return cipher.doFinal(envelope.ciphertext)
            }
        }

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val BASE_NOW: Instant = Instant.parse("2026-11-15T00:00:00Z")

        private val postgres =
            PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("least_privilege_test")
                .withUsername("test_admin")
                .withPassword("test_admin_pw")
    }
}
