package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.spring.sovereign.ops.AuditEngineSovereignOpsAuditEmitter
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatcher
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/**
 * 0.7.6f §8 discriminator 16: dispatch reads the kind from storage, not from process state.
 *
 * All three kinds are written, the store and dispatcher are then rebuilt from nothing but the database,
 * and the emitted events must still be labelled correctly. A dispatch that depended on the objects that
 * wrote the records would pass every in-process test and fail here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSovereignOpsAuditEvidenceRestartTest {
    private lateinit var dataSource: DataSource
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC)

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        val base =
            Paths
                .get(System.getProperty("user.dir"))
                .parent
                .resolve("tramai-persistence-jdbc/src/main/resources/tramai/persistence/jdbc/postgres")
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                for (file in MIGRATIONS) stmt.execute(Files.readString(base.resolve(file)))
                stmt.execute("TRUNCATE TABLE audit_outbox, approvals CASCADE")
            }
        }
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun clean() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE TABLE audit_outbox, approvals CASCADE") }
        }
    }

    @Test
    fun `all three kinds survive a restart and dispatch from storage alone`() =
        runBlocking<Unit> {
            val writer = newStore()
            writer.append(decision("approved-1", SovereignOpsAuditEvidenceKind.APPROVAL_APPROVED, "APPROVED"))
            writer.markReadyForDispatch("approved-1", SovereignOpsAuditOutboxStatus.PREPARED)
            writer.append(decision("denied-1", SovereignOpsAuditEvidenceKind.APPROVAL_DENIED, "DENIED"))
            writer.markReadyForDispatch("denied-1", SovereignOpsAuditOutboxStatus.PREPARED)
            writer.appendDispatchable(refusal("refused-1"))

            // Nothing of the writing process is carried over: new store, new emitter, new audit store.
            val audit = InMemoryAuditStore()
            val dispatcher =
                SovereignOpsAuditOutboxDispatcher(
                    outboxStore = newStore(),
                    auditEmitter = AuditEngineSovereignOpsAuditEmitter(AuditEngine(audit, clock = clock)),
                    clock = clock,
                )

            val result = dispatcher.dispatchPending(limit = 10)

            assertThat(result.emitted).isEqualTo(3)
            assertThat(result.failedPermanent).isZero()
            val events = audit.readStream("sovereign-ops-approval:$AGGREGATE_DIGEST")
            assertThat(events.map { it.decision }).containsExactly("APPROVED", "DENIED", "DENIED")
            assertThat(events.map { it.reasonCode })
                .containsExactly(
                    "sovereign-ops-approval-granted",
                    "sovereign-ops-admin-denial",
                    "sovereign-ops-authorization-refused",
                )
            assertThat(events.map { it.enforcementPoint })
                .containsExactly(
                    "sovereign-ops.approval.decide",
                    "sovereign-ops.approval.decide",
                    "sovereign-ops.approval.authorization",
                )
            // The refusal's reason wire name came back out of the ciphertext, not out of memory.
            assertThat(events.last().metadata).containsEntry("refusalReason", "missing-capability")
        }

    private fun newStore(): JdbcSovereignOpsAuditOutboxStore =
        JdbcSovereignOpsAuditOutboxStore(
            dataSource = dataSource,
            payloadCodec = PassThroughTestCodec(),
            claimLeaseDuration = Duration.ofMinutes(5),
        )

    private fun decision(
        outboxId: String,
        kind: SovereignOpsAuditEvidenceKind,
        approvalStatus: String,
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            aggregateType = "approval",
            aggregateIdDigest = AGGREGATE_DIGEST,
            operation = "decideApproval",
            eventKey = "event-$outboxId",
            actor = "operator-1",
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            approvalStatus = approvalStatus,
            approvalVersion = 2L,
            reasonDigest = "sha256:" + "b".repeat(64),
            reasonLength = 11,
            createdAt = clock.instant(),
            status = SovereignOpsAuditOutboxStatus.PREPARED,
            evidenceKind = kind,
        )

    private fun refusal(outboxId: String): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            aggregateType = "approval",
            aggregateIdDigest = AGGREGATE_DIGEST,
            operation = "authorizationRefusal",
            eventKey = "event-$outboxId",
            actor = "operator-1",
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            approvalStatus = null,
            approvalVersion = null,
            reasonDigest = "sha256:" + "b".repeat(64),
            reasonLength = 11,
            createdAt = clock.instant(),
            status = SovereignOpsAuditOutboxStatus.PENDING,
            evidenceKind = SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL,
            refusalReason = "missing-capability",
        )

    /** Encryption is irrelevant to this discriminator; the codec contract just has to be satisfied. */
    private class PassThroughTestCodec : JdbcOpsAuditOutboxPayloadCodec {
        override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload =
            JdbcEncryptedAuditOutboxPayload(
                ciphertext = wrap(plaintext),
                keyId = KEY_ID,
                algorithm = ALGORITHM,
                nonce = ByteArray(NONCE_LENGTH),
                payloadDigest = sha256Hex(plaintext),
            )

        override fun decode(payload: JdbcEncryptedAuditOutboxPayload): ByteArray = wrap(payload.ciphertext)

        private fun wrap(bytes: ByteArray): ByteArray =
            Cipher.getInstance(ALGORITHM).run {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(ByteArray(16), "AES"),
                    GCMParameterSpec(128, ByteArray(NONCE_LENGTH)),
                )
                doFinal(bytes)
            }
    }

    companion object {
        private fun sha256Hex(bytes: ByteArray): String =
            "sha256:" +
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private val AGGREGATE_DIGEST = "sha256:" + "a".repeat(64)
        private const val NONCE_LENGTH = 12
        private const val KEY_ID = "test-key"
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val POSTGRES_IMAGE = "postgres:17-alpine"

        private val MIGRATIONS =
            listOf(
                "V1__sovereign_persistence.sql",
                "V4__audit_outbox_hardening.sql",
                "V10__approval_actor_provenance.sql",
            )

        private val postgres =
            PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("sovereign_evidence")
                .withUsername("test")
                .withPassword("test")
    }
}
