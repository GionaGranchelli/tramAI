package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.spring.sovereign.ops.SovereignOpsProperties
import dev.tramai.spring.sovereign.ops.outbox.DefaultSovereignOpsAuditOutboxOperations
import dev.tramai.spring.sovereign.ops.outbox.RefusalEvidence
import dev.tramai.spring.sovereign.ops.outbox.RefusalOperation
import dev.tramai.spring.sovereign.ops.outbox.RefusalRecording
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsPreparedRecoveryDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/**
 * 0.7.6f §5.2 against a real PostgreSQL outbox: one durable record per canonical refusal, across restarts
 * and concurrent duplicates.
 *
 * An in-memory store cannot show this: the guarantee is that the *database* holds one row for one logical
 * refusal, which is what makes idempotent retry meaningful after a process restart.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcRefusalRecordingRestartTest {
    private lateinit var dataSource: DataSource
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-31T09:00:00Z"), ZoneOffset.UTC)

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
    fun `a retry after restart dedupes onto the durable record`() =
        runBlocking<Unit> {
            val first = operations().recordRefusal(evidence())
            assertThat(first).isInstanceOf(RefusalRecording.Recorded::class.java)

            // New store, new operations instance: only the database is shared, as after a restart.
            val second = operations().recordRefusal(evidence())
            assertThat(second).isInstanceOf(RefusalRecording.Duplicate::class.java)
            assertThat((second as RefusalRecording.Duplicate).record.outboxId)
                .isEqualTo((first as RefusalRecording.Recorded).record.outboxId)

            val stored = store().listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)
            assertThat(stored).hasSize(1)
            assertThat(stored.single().evidenceKind).isEqualTo(
                dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL,
            )
            assertThat(stored.single().refusalReason).isEqualTo("missing-capability")
            assertThat(stored.single().approvalStatus).isNull()
            assertThat(stored.single().approvalVersion).isNull()
        }

    @Test
    fun `concurrent duplicates produce one durable row`() =
        runBlocking<Unit> {
            val results =
                (1..8)
                    .map {
                        async(Dispatchers.IO) { operations().recordRefusal(evidence()) }
                    }.awaitAll()

            // Exactly one write, and every other attempt is a verifiable duplicate of that record.
            assertThat(results.filterIsInstance<RefusalRecording.Recorded>()).hasSize(1)
            assertThat(results.filterIsInstance<RefusalRecording.Duplicate>()).hasSize(7)
            // Every attempt names the same record: that is what makes a retry indistinguishable from the
            // first attempt rather than merely "not a second insert".
            val ids =
                results.map { recording ->
                    when (recording) {
                        is RefusalRecording.Recorded -> recording.record.outboxId
                        is RefusalRecording.Duplicate -> recording.record.outboxId
                    }
                }
            assertThat(ids.toSet()).hasSize(1)
            assertThat(store().listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 20)).hasSize(1)
        }

    @Test
    fun `a different attempt writes a second durable row`() =
        runBlocking<Unit> {
            operations().recordRefusal(evidence(expectedVersion = 7))
            val other = operations().recordRefusal(evidence(expectedVersion = 8))

            assertThat(other).isInstanceOf(RefusalRecording.Recorded::class.java)
            assertThat(store().listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 20)).hasSize(2)
        }

    // --- helpers --------------------------------------------------------------------------------

    private fun store(): JdbcSovereignOpsAuditOutboxStore =
        JdbcSovereignOpsAuditOutboxStore(
            dataSource = dataSource,
            payloadCodec = TestCodec(),
            claimLeaseDuration = Duration.ofMinutes(5),
        )

    private fun operations(): DefaultSovereignOpsAuditOutboxOperations =
        DefaultSovereignOpsAuditOutboxOperations(
            outboxStore = store(),
            outboxDispatcher = null,
            properties = SovereignOpsProperties(),
            recoveryResolver = { SovereignOpsPreparedRecoveryDecision.UNKNOWN },
            clock = clock,
        )

    private fun evidence(expectedVersion: Long = 7) =
        RefusalEvidence(
            operation = RefusalOperation.APPROVE,
            approvalId = "approval-refusal-1",
            issuer = "https://issuer.example",
            subject = "operator-1",
            refusalReason = "missing-capability",
            policyVersion = null,
            expectedVersion = expectedVersion,
            correlationId = "corr-1",
        )

    /** Real AES-GCM: the round-trip through the payload must authenticate, as in production. */
    private class TestCodec : JdbcOpsAuditOutboxPayloadCodec {
        override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
            val nonce = ByteArray(NONCE_LENGTH).also { java.security.SecureRandom().nextBytes(it) }
            return JdbcEncryptedAuditOutboxPayload(
                ciphertext = cipher(Cipher.ENCRYPT_MODE, nonce, plaintext),
                keyId = KEY_ID,
                algorithm = AES_GCM,
                nonce = nonce,
                payloadDigest =
                    "sha256:" +
                        java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(plaintext)
                            .joinToString("") { "%02x".format(it) },
            )
        }

        override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray {
            require(envelope.keyId == KEY_ID) { "audit-outbox-test-key-mismatch" }
            return cipher(Cipher.DECRYPT_MODE, envelope.nonce, envelope.ciphertext)
        }

        private fun cipher(
            mode: Int,
            nonce: ByteArray,
            bytes: ByteArray,
        ): ByteArray =
            Cipher.getInstance(AES_GCM).run {
                init(mode, SecretKeySpec(KEY, "AES"), GCMParameterSpec(128, nonce))
                doFinal(bytes)
            }
    }

    companion object {
        private const val NONCE_LENGTH = 12
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val KEY_ID = "test-key"
        private val KEY = ByteArray(16) { it.toByte() }

        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val MIGRATIONS =
            listOf(
                "V1__sovereign_persistence.sql",
                "V4__audit_outbox_hardening.sql",
                "V10__approval_actor_provenance.sql",
            )

        private val postgres =
            PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("refusal_evidence")
                .withUsername("test")
                .withPassword("test")
    }
}
