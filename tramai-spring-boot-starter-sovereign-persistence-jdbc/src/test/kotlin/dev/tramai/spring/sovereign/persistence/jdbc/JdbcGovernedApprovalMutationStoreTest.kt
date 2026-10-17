package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.engine.GovernedSuspendedInvocation
import dev.tramai.persistence.jdbc.GovernedJdbcSuspendedInvocationStore
import dev.tramai.persistence.jdbc.JdbcSuspendedInvocationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.testing.persistence.engine.SuspendedInvocationFixtures
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
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/**
 * 0.7.1d: the approval-mutation transaction must record governed attribution without changing its
 * shape — one native transaction, `INSERT audit_outbox(PREPARED)` → `UPDATE approvals` →
 * `UPDATE audit_outbox(PENDING)` → `COMMIT`, with the same canonical identity in both outbox writes.
 *
 * The `encode`-recording codec is what makes that claim checkable: it captures the exact plaintexts
 * the store encrypts, so "V2 PREPARED then V2 PENDING with the same identity" is asserted on the
 * bytes in the transaction rather than inferred from the returned value.
 *
 * The three injected statements are the discriminator for rollback: failing the insert, the approval
 * update, or the final outbox update must leave the approval untouched and no outbox row behind — the
 * half-governed mutation this slice exists to prevent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcGovernedApprovalMutationStoreTest {
    private val key: SecretKey = SecretKeySpec(ByteArray(16).also { SecureRandom().nextBytes(it) }, "AES")
    private val recordedPlaintexts = mutableListOf<ByteArray>()

    private lateinit var dataSource: DataSource
    private lateinit var suspended: GovernedJdbcSuspendedInvocationStore

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        dataSource = createDataSource()
        migrate()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun setUp() {
        truncateTables()
        recordedPlaintexts.clear()
        suspended =
            GovernedJdbcSuspendedInvocationStore(
                JdbcSuspendedInvocationStore(dataSource, DefaultJdbcSuspendedInvocationPayloadCodec(key, "test-key")),
            )
    }

    // ── The transaction shape ────────────────────────────────────────

    @Test
    fun `a governed decision records V2 for both outbox writes and keeps the approval transition`() {
        runBlocking {
            val identity = identity(RUN_ID)
            insertApproval(APPROVAL_ID, workflowRunId = RUN_ID)
            suspendGoverned(APPROVAL_ID, identity)
            val intent = auditIntent(APPROVAL_ID, workflowRunId = RUN_ID)

            val result = store(dataSource).denyApprovalWithAuditIntent(APPROVAL_ID, 1, "admin", "reason", intent)

            assertThat(result.approval.status).isEqualTo(ApprovalStatus.DENIED)
            assertThat(selectApprovalStatus(APPROVAL_ID)).isEqualTo("DENIED")
            assertThat(outboxStatus(intent.outboxId)).isEqualTo("PENDING")
            assertThat(recordedPlaintexts).hasSize(2)
            assertThat(decoded(0).runIdentity).isEqualTo(identity)
            assertThat(decoded(1).runIdentity).isEqualTo(identity)
            assertThat(decoded(0).record.status).isEqualTo(SovereignOpsAuditOutboxStatus.PREPARED)
            assertThat(decoded(1).record.status).isEqualTo(SovereignOpsAuditOutboxStatus.PENDING)
        }
    }

    @Test
    fun `a legacy decision keeps the released V1 shape for both writes`() {
        runBlocking {
            insertApproval(APPROVAL_ID, workflowRunId = RUN_ID)
            val intent = auditIntent(APPROVAL_ID, workflowRunId = null)

            store(dataSource).denyApprovalWithAuditIntent(APPROVAL_ID, 1, "admin", "reason", intent)

            assertThat(recordedPlaintexts).hasSize(2)
            assertThat(decoded(0).runIdentity).isNull()
            assertThat(decoded(1).runIdentity).isNull()
        }
    }

    @Test
    fun `a governed decision whose intent names another run fails closed before mutating`() {
        runBlocking {
            insertApproval(APPROVAL_ID, workflowRunId = RUN_ID)
            suspendGoverned(APPROVAL_ID, identity(RUN_ID))
            val intent = auditIntent(APPROVAL_ID, workflowRunId = null)

            assertThatThrownBy {
                runBlocking {
                    store(dataSource).denyApprovalWithAuditIntent(APPROVAL_ID, 1, "admin", "reason", intent)
                }
            }.isInstanceOf(IllegalArgumentException::class.java)

            assertThat(selectApprovalStatus(APPROVAL_ID)).isEqualTo("PENDING")
            assertThat(selectApprovalVersion(APPROVAL_ID)).isEqualTo(1L)
            assertThat(countOutboxRows()).isZero()
        }
    }

    // ── Injected failures inside the transaction ─────────────────────

    @Test
    fun `a failed outbox insert leaves the approval untouched`() {
        runBlocking {
            insertApproval(APPROVAL_ID, workflowRunId = RUN_ID)
            suspendGoverned(APPROVAL_ID, identity(RUN_ID))
            val intent = auditIntent(APPROVAL_ID, workflowRunId = RUN_ID)
            val failing = store(dataSourceFailingOn("INSERT INTO audit_outbox"))

            assertMutationFailed(failing, intent)
            assertThat(countOutboxRows()).isZero()
        }
    }

    @Test
    fun `a failed approval update rolls the prepared outbox record back`() {
        runBlocking {
            insertApproval(APPROVAL_ID, workflowRunId = RUN_ID)
            suspendGoverned(APPROVAL_ID, identity(RUN_ID))
            val intent = auditIntent(APPROVAL_ID, workflowRunId = RUN_ID)
            val failing = store(dataSourceFailingOn("UPDATE approvals"))

            assertMutationFailed(failing, intent)
            assertThat(countOutboxRows()).isZero()
        }
    }

    @Test
    fun `a failed final outbox update rolls back both the approval and the prepared record`() {
        runBlocking {
            insertApproval(APPROVAL_ID, workflowRunId = RUN_ID)
            suspendGoverned(APPROVAL_ID, identity(RUN_ID))
            val intent = auditIntent(APPROVAL_ID, workflowRunId = RUN_ID)
            val failing = store(dataSourceFailingOn("UPDATE audit_outbox"))

            assertMutationFailed(failing, intent)
            assertThat(countOutboxRows()).isZero()
            assertThat(selectApprovalStatus(APPROVAL_ID)).isEqualTo("PENDING")
            assertThat(selectApprovalVersion(APPROVAL_ID)).isEqualTo(1L)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private suspend fun assertMutationFailed(
        failing: JdbcSovereignOpsApprovalMutationStore,
        intent: SovereignOpsAuditOutboxRecord,
    ) {
        assertThatThrownBy {
            runBlocking {
                failing.denyApprovalWithAuditIntent(APPROVAL_ID, 1, "admin", "reason", intent)
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(selectApprovalStatus(APPROVAL_ID)).isEqualTo("PENDING")
        assertThat(selectApprovalVersion(APPROVAL_ID)).isEqualTo(1L)
    }

    /** The store under test, with a codec that records every plaintext the transaction encodes. */
    private fun store(source: DataSource): JdbcSovereignOpsApprovalMutationStore =
        JdbcSovereignOpsApprovalMutationStore(
            dataSource = source,
            payloadCodec = RecordingOutboxCodec(key, recordedPlaintexts),
            clock = Clock.fixed(BASE_NOW.plusSeconds(30), ZoneOffset.UTC),
            suspendedInvocations = suspended,
        )

    private fun decoded(index: Int): DecodedOutboxRecord = decodeOutboxRecord(recordedPlaintexts[index])

    private fun suspendGoverned(
        approvalId: String,
        identity: GovernedRunIdentity,
    ) {
        val (metadata, envelope) = SuspendedInvocationFixtures.record(approvalId = approvalId)
        val governed = metadata.copy(identity = metadata.identity.copy(workflowRunId = identity.runId.value))
        runBlocking { suspended.createGoverned(GovernedSuspendedInvocation(governed, identity), envelope) }
    }

    private fun auditIntent(
        approvalId: String,
        workflowRunId: String?,
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = "outbox-$approvalId",
            eventKey = "key-$approvalId",
            aggregateIdDigest = sha256Hex(approvalId),
            actor = "admin",
            workflowRunId = workflowRunId,
            correlationId = null,
            approvalStatus = "PENDING",
            approvalVersion = 1,
            reasonDigest = sha256Hex("reason"),
            reasonLength = 6,
            createdAt = BASE_NOW,
        )

    /**
     * A DataSource whose connections delegate to the real database but reject one statement with a
     * [SQLException], injected at the point the production code prepares it — so the failure lands
     * inside the transaction and the rollback path is the code under test (same idiom as the
     * outbox store's `dataSourceWithFailures`).
     */
    private fun dataSourceFailingOn(fragment: String): DataSource {
        val real = dataSource
        val connection = { delegate: Connection ->
            Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) {
                _,
                connMethod,
                connArgs,
                ->
                val sql = connArgs?.firstOrNull() as? String
                if (connMethod.name == "prepareStatement" && sql?.contains(fragment) == true) {
                    throw SQLException("injected failure at: $fragment")
                }
                connMethod.invoke(delegate, *(connArgs ?: emptyArray()))
            }
        }
        return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) {
            _,
            method,
            args,
            ->
            if (method.name == "getConnection" && args.isNullOrEmpty()) {
                connection(real.connection)
            } else {
                method.invoke(real, *(args ?: emptyArray()))
            }
        } as DataSource
    }

    private fun insertApproval(
        approvalId: String,
        workflowRunId: String,
    ) {
        val metadata =
            mapOf(
                "binding" to
                    mapOf(
                        "workflowRunId" to workflowRunId,
                        "toolName" to "test-tool",
                        "argumentsDigest" to "sha256:${"a".repeat(64)}",
                        "policyVersion" to "1.0.0",
                        "workflowDigest" to "sha256:${"b".repeat(64)}",
                        "approvalTokenDigest" to "sha256:${"c".repeat(64)}",
                    ),
                "requestedBy" to "test-user",
                "expiresAt" to BASE_NOW.plusSeconds(600).toString(),
                "requestedAt" to BASE_NOW.toString(),
            )
        val json = mapper.writeValueAsString(metadata)
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
                    VALUES (?, 'PENDING', ?, ?::jsonb, 1)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, approvalId)
                    stmt.setTimestamp(2, Timestamp.from(BASE_NOW))
                    stmt.setString(3, json)
                    stmt.executeUpdate()
                }
        }
    }

    private fun selectApprovalStatus(approvalId: String): String =
        selectString("SELECT status FROM approvals WHERE approval_id = ?", approvalId)

    private fun selectApprovalVersion(approvalId: String): Long =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT version FROM approvals WHERE approval_id = ?").use { stmt ->
                stmt.setString(1, approvalId)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getLong(1)
                }
            }
        }

    private fun outboxStatus(outboxId: String): String = selectString(OUTBOX_STATUS_SQL, outboxId)

    private fun countOutboxRows(): Int =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT count(*) FROM audit_outbox").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun selectString(
        sql: String,
        value: String,
    ): String =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, value)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getString(1)
                }
            }
        }

    private fun truncateTables() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("TRUNCATE TABLE audit_outbox, approvals, suspended_invocations CASCADE")
            }
        }
    }

    private fun migrate() {
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
            }
        }
    }

    private fun identity(runId: String): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId("claims"),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("claims-prod"),
                            version = ConfigurationVersion("17"),
                        ),
                    environmentId = EnvironmentId("production"),
                    deploymentId = DeploymentId("eu-west-amsterdam-01"),
                ),
            runId = RunId(runId),
        )

    private fun sha256Hex(input: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        private const val APPROVAL_ID = "approval-governed-1"
        private const val OUTBOX_STATUS_SQL = "SELECT status FROM audit_outbox WHERE outbox_id = ?"
        private const val RUN_ID = "governed-run-1"
        private val BASE_NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
        private val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("governed_mutation_test")
                .withUsername("test")
                .withPassword("test")

        private val mapper = ObjectMapper()

        private fun createDataSource(): DataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
    }
}

/**
 * Wraps the production payload codec and records every plaintext the store encrypts, so a test can
 * assert the shape of each write inside the transaction instead of only the committed row.
 */
private class RecordingOutboxCodec(
    key: SecretKey,
    private val plaintexts: MutableList<ByteArray>,
) : JdbcOpsAuditOutboxPayloadCodec {
    private val delegate = DefaultJdbcOpsAuditOutboxPayloadCodec(key, "test-key")

    override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
        plaintexts += plaintext
        return delegate.encode(plaintext)
    }

    override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray = delegate.decode(envelope)
}
