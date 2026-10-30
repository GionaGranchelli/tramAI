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
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.security.SecureRandom
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

            // And the payload itself round-trips through decode(): reading each row back must return the
            // evidence the writer encrypted, which is only true if decode() inverts encode().
            val reader = newStore()
            assertThat(reader.get("approved-1")?.evidenceKind).isEqualTo(APPROVED)
            assertThat(reader.get("approved-1")?.approvalStatus).isEqualTo("APPROVED")
            assertThat(reader.get("denied-1")?.evidenceKind).isEqualTo(DENIED)
            assertThat(reader.get("refused-1")?.evidenceKind).isEqualTo(REFUSAL)
            assertThat(reader.get("refused-1")?.refusalReason).isEqualTo("missing-capability")
        }

    @Test
    fun `a tampered authentication tag fails the read instead of returning evidence`() =
        runBlocking<Unit> {
            newStore().appendDispatchable(refusal("refused-tag"))
            // Flip one bit in the last byte of the GCM tag: the ciphertext (and the plaintext prefix) is
            // untouched, so only authentication can detect this.
            dataSource.connection.use { conn ->
                conn
                    .prepareStatement(
                        "UPDATE audit_outbox SET encrypted_payload = " +
                            "overlay(encrypted_payload placing ?::bytea from octet_length(encrypted_payload) for 1) " +
                            "WHERE outbox_id = ?",
                    ).use { stmt ->
                        stmt.setBytes(1, byteArrayOf((TAMPER_XOR).toByte()))
                        stmt.setString(2, "refused-tag")
                        // The tamper must actually land, or the assertion below proves nothing.
                        assertThat(stmt.executeUpdate()).isEqualTo(1)
                    }
            }

            val thrown =
                assertThatThrownBy { runBlocking { newStore().get("refused-tag") } }
            thrown.isInstanceOf(IllegalStateException::class.java)
            thrown.hasMessageContaining("audit-outbox-payload-decryption-failed")
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

    /**
     * Real AES-GCM, because the point of this test is the whole read path: the evidence fields must survive
     * being encrypted, stored, reloaded by a different store instance and decrypted again.
     *
     * decode() must authenticate, not merely recover bytes: GCM is counter-mode, so re-encrypting the
     * ciphertext with the same key and nonce reproduces the plaintext prefix by XOR and proves nothing about
     * the tag. Only DECRYPT_MODE validates the tag, which is what `a tampered authentication tag` pins down.
     */
    private class PassThroughTestCodec : JdbcOpsAuditOutboxPayloadCodec {
        override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
            val nonce = ByteArray(NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
            return JdbcEncryptedAuditOutboxPayload(
                ciphertext = cipher(AES_GCM, Cipher.ENCRYPT_MODE, nonce, plaintext),
                keyId = KEY_ID,
                algorithm = AES_GCM,
                nonce = nonce,
                payloadDigest = sha256Hex(plaintext),
            )
        }

        override fun decode(payload: JdbcEncryptedAuditOutboxPayload): ByteArray {
            require(payload.keyId == KEY_ID) { "audit-outbox-test-key-mismatch" }
            require(payload.algorithm == AES_GCM) { "audit-outbox-test-algorithm-mismatch" }
            return cipher(AES_GCM, Cipher.DECRYPT_MODE, payload.nonce, payload.ciphertext)
        }

        private fun cipher(
            algorithm: String,
            mode: Int,
            nonce: ByteArray,
            bytes: ByteArray,
        ): ByteArray =
            Cipher.getInstance(algorithm).run {
                init(mode, SecretKeySpec(KEY, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
                doFinal(bytes)
            }
    }

    companion object {
        private fun sha256Hex(bytes: ByteArray): String =
            "sha256:" +
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private val AGGREGATE_DIGEST = "sha256:" + "a".repeat(64)
        private val APPROVED = SovereignOpsAuditEvidenceKind.APPROVAL_APPROVED
        private val DENIED = SovereignOpsAuditEvidenceKind.APPROVAL_DENIED
        private val REFUSAL = SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL
        private const val NONCE_LENGTH = 12
        private const val TAMPER_XOR = 0xFF
        private const val TAG_LENGTH_BITS = 128
        private const val AES_GCM = "AES/GCM/NoPadding"
        private val KEY = ByteArray(16) { it.toByte() }
        private const val KEY_ID = "test-key"
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
