package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.spring.sovereign.ops.outbox.QuarantineReport
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxQuarantineStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/**
 * 0.7.6f slice 3 -- durable evidence quarantine on the JDBC store.
 *
 * The primary invariant is two-sided: one damaged row must not stop healthy evidence from dispatching,
 * and uncertainty about the deployment key must never quarantine healthy evidence. Both directions are
 * asserted against a real PostgreSQL instance, with the stored ciphertext compared byte-for-byte.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSovereignOpsAuditOutboxQuarantineTest {
    private lateinit var dataSource: DataSource
    private lateinit var codec: TestCodec
    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        applyMigrations()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun setUp() {
        exec("TRUNCATE TABLE audit_outbox, approvals CASCADE")
        codec = TestCodec()
    }

    // --- discriminator 18: mixed batch, healthy evidence keeps flowing, bytes preserved ----------

    @Test
    fun `a mixed batch quarantines the damaged row and still dispatches the healthy one`() =
        runBlocking<Unit> {
            val store = newStore()
            store.appendDispatchable(refusal("healthy-row"))
            store.appendDispatchable(refusal("damaged-row"))
            corruptCiphertext("damaged-row")
            val before = ciphertextOf("damaged-row")

            val recovery = requireNotNull(store.quarantineStore())
            val report: QuarantineReport = recovery.quarantineUndecodableEvidence(limit = 10, now = now)

            assertThat(report.scanned).isEqualTo(2)
            assertThat(report.decodable).isEqualTo(1)
            assertThat(report.quarantined).hasSize(1)
            assertThat(report.quarantined.single().locator).isEqualTo("damaged-row")
            assertThat(report.quarantined.single().label)
                .isEqualTo(SovereignOpsAuditOutboxQuarantineStore.LABEL_UNDECODABLE)
            assertThat(report.quarantined.single().payloadDigest).startsWith("sha256:")

            // Ciphertext is preserved byte-for-byte: quarantine is metadata, not a rewrite.
            assertThat(ciphertextOf("damaged-row")).isEqualTo(before)

            // The healthy record still dispatches.
            val claimed = store.claimPending("dispatcher", 10, now.plusSeconds(1))
            assertThat(claimed.map { it.outboxId }).containsExactly("healthy-row")

            // A second run re-quarantines nothing and rewrites nothing.
            val second = store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now.plusSeconds(2))
            assertThat(second.quarantined).isEmpty()
            assertThat(second.skipped).isEqualTo(1)
            assertThat(ciphertextOf("damaged-row")).isEqualTo(before)
        }

    // --- discriminator 19: listings and claims never decode a quarantined row -------------------

    @Test
    fun `quarantined rows are excluded from listings and claims and their bytes stay readable`() =
        runBlocking<Unit> {
            val store = newStore()
            // A control record is required by the contract: with no successful decode the operation must
            // refuse to act, so a lone damaged row is never quarantined automatically.
            store.appendDispatchable(refusal("listing-control"))
            store.appendDispatchable(refusal("damaged-listing"))
            corruptCiphertext("damaged-listing")
            val before = ciphertextOf("damaged-listing")
            store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

            // No throw, no row: the anti-join is what keeps every page of FAILED_PERMANENT usable.
            assertThat(store.listByStatus(SovereignOpsAuditOutboxStatus.FAILED_PERMANENT, 10)).isEmpty()
            assertThat(store.claimPending("dispatcher", 10, now.plusSeconds(1)).map { it.outboxId })
                .containsExactly("listing-control")
            assertThat(store.get("damaged-listing")).isNull()
            assertThat(store.findByEventKey("event-damaged-listing")).isNull()

            val listed = store.quarantineStore().listQuarantined(10)
            assertThat(listed).hasSize(1)
            val summary = listed.single()
            assertThat(summary.locator).isEqualTo("damaged-listing")
            assertThat(summary.outboxId).isEqualTo("damaged-listing")
            assertThat(summary.eventKey).isEqualTo("event-damaged-listing")
            assertThat(summary.quarantinedAt).isEqualTo(now)
            assertThat(summary.failureDetail).isNotNull()
            assertThat(summary.failureDetail!!.length)
                .isLessThanOrEqualTo(SovereignOpsAuditOutboxQuarantineStore.MAX_FAILURE_DETAIL)

            val storedBytes = store.quarantineStore().readQuarantinedCiphertext("damaged-listing")
            assertThat(storedBytes?.toList()).isEqualTo(before)
            assertThat(store.quarantineStore().readQuarantinedCiphertext("no-such-locator")).isNull()
            // The row itself is still there, still FAILED_PERMANENT, with its identifiers intact.
            assertThat(columnOf("status", "damaged-listing")).isEqualTo("FAILED_PERMANENT")
            assertThat(columnOf("event_key", "damaged-listing")).isEqualTo("event-damaged-listing")
        }

    // --- discriminator 22: a wrong key must not quarantine anything ------------------------------

    @Test
    fun `a wrong deployment key aborts instead of quarantining healthy evidence`() =
        runBlocking<Unit> {
            val store = newStore()
            store.appendDispatchable(refusal("key-row-1"))
            store.appendDispatchable(refusal("key-row-2"))
            val beforeOne = ciphertextOf("key-row-1")
            val beforeTwo = ciphertextOf("key-row-2")

            val wrongKeyStore = newStore(codec = TestCodec(freshKey = true))

            assertThatThrownBy {
                runBlocking { wrongKeyStore.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now) }
            }.hasMessage(SovereignOpsAuditOutboxQuarantineStore.ERROR_DECRYPTION_UNAVAILABLE)

            assertThat(wrongKeyStore.quarantineStore().listQuarantined(10)).isEmpty()
            assertThat(columnOf("status", "key-row-1")).isEqualTo("PENDING")
            assertThat(columnOf("status", "key-row-2")).isEqualTo("PENDING")
            assertThat(ciphertextOf("key-row-1")).isEqualTo(beforeOne)
            assertThat(ciphertextOf("key-row-2")).isEqualTo(beforeTwo)
        }

    @Test
    fun `a batch with no decodable control refuses to quarantine even distinguishable failures`() =
        runBlocking<Unit> {
            val store = newStore()
            store.appendDispatchable(refusal("only-damaged-1"))
            store.appendDispatchable(refusal("only-damaged-2"))
            corruptCiphertext("only-damaged-1")
            corruptCiphertext("only-damaged-2")

            assertThatThrownBy {
                runBlocking { store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now) }
            }.hasMessage(SovereignOpsAuditOutboxQuarantineStore.ERROR_DECRYPTION_UNAVAILABLE)

            assertThat(store.quarantineStore().listQuarantined(10)).isEmpty()
            assertThat(columnOf("status", "only-damaged-1")).isEqualTo("PENDING")
        }

    // --- discriminator 23: corrupt evidence keeps its own label ---------------------------------

    @Test
    fun `a payload that contradicts its columns is labelled corrupt, not undecodable`() =
        runBlocking<Unit> {
            val store = newStore()
            store.appendDispatchable(refusal("column-drift-control"))
            store.appendDispatchable(refusal("column-drift"))
            // The ciphertext still decodes; the row's status column now disagrees with its own payload.
            exec("UPDATE audit_outbox SET status = 'FAILED_RETRYABLE' WHERE outbox_id = 'column-drift'")

            val report = store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

            assertThat(report.quarantined).hasSize(1)
            assertThat(report.quarantined.single().locator).isEqualTo("column-drift")
            assertThat(report.quarantined.single().label)
                .isEqualTo(SovereignOpsAuditOutboxQuarantineStore.LABEL_CORRUPT)
            assertThat(SovereignOpsAuditOutboxQuarantineStore.LABEL_CORRUPT)
                .isNotEqualTo(SovereignOpsAuditOutboxQuarantineStore.LABEL_UNDECODABLE)
            assertThat(columnOf("last_failure_type", "column-drift"))
                .isEqualTo(SovereignOpsAuditOutboxQuarantineStore.LABEL_CORRUPT)
        }

    // --- discriminator 24: decodable and PREPARED records are never touched ---------------------

    @Test
    fun `quarantine touches neither decodable nor PREPARED records`() =
        runBlocking<Unit> {
            val store = newStore()
            store.appendDispatchable(refusal("healthy-untouched"))
            store.append(prepared("prepared-untouched"))
            store.appendDispatchable(refusal("damaged-only"))
            corruptCiphertext("damaged-only")

            val report = store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

            assertThat(report.scanned).isEqualTo(2)
            assertThat(report.quarantined.map { it.locator }).containsExactly("damaged-only")
            assertThat(columnOf("status", "healthy-untouched")).isEqualTo("PENDING")
            assertThat(columnOf("status", "prepared-untouched")).isEqualTo("PREPARED")
            assertThat(store.quarantineStore().listQuarantined(10).map { it.locator }).containsExactly("damaged-only")
        }

    // --- restart and idempotency ----------------------------------------------------------------

    @Test
    fun `quarantine metadata survives a restart and is never re-applied`() =
        runBlocking<Unit> {
            val store = newStore()
            store.appendDispatchable(refusal("restart-control"))
            store.appendDispatchable(refusal("restart-damaged"))
            corruptCiphertext("restart-damaged")
            val before = ciphertextOf("restart-damaged")
            store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

            // A brand-new store over the same database: nothing cached.
            val restarted = newStore()
            val listed = restarted.quarantineStore().listQuarantined(10)
            assertThat(listed).hasSize(1)
            assertThat(listed.single().outboxId).isEqualTo("restart-damaged")
            assertThat(restarted.listByStatus(SovereignOpsAuditOutboxStatus.FAILED_PERMANENT, 10)).isEmpty()
            assertThat(restarted.claimPending("dispatcher", 10, now.plusSeconds(5)).map { it.outboxId })
                .containsExactly("restart-control")
            val restartedRecovery = requireNotNull(restarted.quarantineStore())
            val second = restartedRecovery.quarantineUndecodableEvidence(limit = 10, now = now.plusSeconds(6))
            assertThat(second.quarantined).isEmpty()
            val storedBytes = restartedRecovery.readQuarantinedCiphertext("restart-damaged")
            assertThat(storedBytes?.toList()).isEqualTo(before)
        }

    // --- expired claim recovery, concurrency and future schema versions -------------------------

    @Test
    fun `an unreadable expired claim is recovered so healthy records still dispatch`() =
        runBlocking<Unit> {
            val store = newStore()
            store.appendDispatchable(refusal("expired-damaged"))
            store.appendDispatchable(refusal("expired-healthy"))
            assertThat(store.claimPending("dispatcher", 10, now.plusSeconds(1))).hasSize(2)
            corruptCiphertext("expired-damaged")
            val later = now.plusSeconds(1).plus(Duration.ofMinutes(6))

            val report = store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = later)

            assertThat(report.quarantined.map { it.locator }).containsExactly("expired-damaged")
            assertThat(columnOf("status", "expired-damaged")).isEqualTo("FAILED_PERMANENT")
            assertThat(columnOf("status", "expired-healthy")).isEqualTo("EMITTING")
            // The whole point: claiming after recovery dispatches the healthy record instead of throwing.
            assertThat(store.claimPending("dispatcher", 10, later).map { it.outboxId })
                .containsExactly("expired-healthy")
        }

    @Test
    fun `two concurrent recovery passes quarantine a record exactly once`() =
        runBlocking<Unit> {
            val store = newStore()
            store.appendDispatchable(refusal("race-control"))
            store.appendDispatchable(refusal("race-damaged"))
            corruptCiphertext("race-damaged")
            val versionBefore = requireNotNull(columnOf("version", "race-damaged")).toLong()

            val reports =
                listOf(1, 2)
                    .map {
                        async(Dispatchers.IO) {
                            store.quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)
                        }
                    }.awaitAll()

            assertThat(reports.sumOf { it.quarantined.size }).isEqualTo(1)
            assertThat(store.quarantineStore().listQuarantined(10).map { it.locator })
                .containsExactly("race-damaged")
            // Exactly one transition was written: the loser revalidated and stood down.
            assertThat(requireNotNull(columnOf("version", "race-damaged")).toLong()).isEqualTo(versionBefore + 1)
        }

    @Test
    fun `a payload from a newer schema version is preserved instead of quarantined`() =
        runBlocking<Unit> {
            newStore().appendDispatchable(refusal("future-control"))
            newStore(codec = TestCodec(unsupportedVersion = true)).appendDispatchable(refusal("future-version"))
            val before = ciphertextOf("future-version")

            val report = newStore().quarantineStore().quarantineUndecodableEvidence(limit = 10, now = now)

            assertThat(report.quarantined).isEmpty()
            assertThat(report.decodable).isEqualTo(1)
            assertThat(columnOf("status", "future-version")).isEqualTo("PENDING")
            assertThat(ciphertextOf("future-version")).isEqualTo(before)
        }

    // --- helpers --------------------------------------------------------------------------------

    private fun newStore(codec: TestCodec = this.codec): JdbcSovereignOpsAuditOutboxStore =
        JdbcSovereignOpsAuditOutboxStore(
            dataSource = dataSource,
            payloadCodec = codec,
            claimLeaseDuration = Duration.ofMinutes(5),
        )

    private fun refusal(outboxId: String): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            aggregateType = "approval",
            aggregateIdDigest = "sha256:" + "a".repeat(64),
            operation = "authorizationRefusal",
            eventKey = "event-$outboxId",
            actor = "operator-1",
            workflowRunId = null,
            correlationId = null,
            approvalStatus = null,
            approvalVersion = null,
            reasonDigest = "sha256:" + "b".repeat(64),
            reasonLength = 11,
            createdAt = now,
            status = SovereignOpsAuditOutboxStatus.PENDING,
            evidenceKind = SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL,
            refusalReason = "missing-capability",
            refusalExpectedVersion = 3,
            actorIssuer = "https://issuer.example",
            actorIdentityDigest = "0123456789abcdef".repeat(4),
        )

    private fun prepared(outboxId: String): SovereignOpsAuditOutboxRecord =
        refusal(outboxId).copy(status = SovereignOpsAuditOutboxStatus.PREPARED, evidenceKind = null)

    /** Replaces the stored ciphertext with bytes the codec cannot authenticate. */
    private fun corruptCiphertext(outboxId: String) {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE audit_outbox SET encrypted_payload = ? WHERE outbox_id = ?").use { stmt ->
                stmt.setBytes(1, bytes)
                stmt.setString(2, outboxId)
                stmt.executeUpdate()
            }
        }
    }

    private fun ciphertextOf(outboxId: String): List<Byte> =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT encrypted_payload FROM audit_outbox WHERE outbox_id = ?").use { stmt ->
                stmt.setString(1, outboxId)
                stmt.executeQuery().use { rs ->
                    assertThat(rs.next()).isTrue()
                    rs.getBytes(1).toList()
                }
            }
        }

    private fun columnOf(
        column: String,
        outboxId: String,
    ): String? = firstColumn("SELECT $column FROM audit_outbox WHERE outbox_id = '$outboxId'")

    private fun firstColumn(sql: String): String? =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.firstOrNull()
                }
            }
        }

    private fun exec(sql: String) {
        dataSource.connection.use { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    private fun applyMigrations() {
        val root = Paths.get(System.getProperty("user.dir")).parent
        val base = root.resolve("tramai-persistence-jdbc/src/main/resources/tramai/persistence/jdbc/postgres")
        for (file in MIGRATIONS) {
            val sql = Files.readString(base.resolve(file))
            exec(sql)
        }
    }

    private class TestCodec(
        freshKey: Boolean = false,
        private val unsupportedVersion: Boolean = false,
    ) : JdbcOpsAuditOutboxPayloadCodec {
        private val algorithm = "AES/GCM/NoPadding"
        private val tagLength = 128
        private val key =
            if (freshKey) {
                ByteArray(16).also { SecureRandom().nextBytes(it) }
            } else {
                SHARED_KEY
            }

        override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
            // A row written by a newer build: decrypts fine, declares a schema version this build lacks.
            val future = """{"schemaVersion":99,"record":{},"governedRunIdentity":{}}"""
            val effective = if (unsupportedVersion) future.toByteArray() else plaintext
            val cipher = Cipher.getInstance(algorithm)
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagLength, nonce))
            val ciphertext = cipher.doFinal(effective)
            val digest = MessageDigest.getInstance("SHA-256").digest(effective).joinToString("") { "%02x".format(it) }
            return JdbcEncryptedAuditOutboxPayload(
                ciphertext = ciphertext,
                keyId = "test-key",
                algorithm = algorithm,
                nonce = nonce,
                payloadDigest = "sha256:$digest",
            )
        }

        override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray {
            require(envelope.keyId == "test-key") { "audit-outbox-test-key-mismatch" }
            require(envelope.algorithm == algorithm) { "audit-outbox-test-algorithm-mismatch" }
            val cipher = Cipher.getInstance(algorithm)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(tagLength, envelope.nonce),
            )
            val plaintext = cipher.doFinal(envelope.ciphertext)
            val digest = MessageDigest.getInstance("SHA-256").digest(plaintext).joinToString("") { "%02x".format(it) }
            require(envelope.payloadDigest == "sha256:$digest") { "audit-outbox-payload-digest-mismatch" }
            return plaintext
        }

        companion object {
            private val SHARED_KEY = ByteArray(16) { (it + 1).toByte() }
        }
    }

    private companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val MIGRATIONS =
            listOf(
                "V1__sovereign_persistence.sql",
                "V4__audit_outbox_hardening.sql",
                "V10__approval_actor_provenance.sql",
            )
        private val postgres =
            PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("sovereign_quarantine")
                .withUsername("test")
                .withPassword("test")
    }
}
