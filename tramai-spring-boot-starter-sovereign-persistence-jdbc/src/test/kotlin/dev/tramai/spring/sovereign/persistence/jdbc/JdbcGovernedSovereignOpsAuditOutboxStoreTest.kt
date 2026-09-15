@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.spring.sovereign.ops.outbox.GovernedSovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxGovernance
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * 0.7.1d governed JDBC outbox continuity.
 *
 * The five proofs this file owns, each observed on the durable row rather than on the returned value:
 *
 * 1. V2 carries the complete identity, and V1 stays exactly legacy — decoded straight out of
 *    `encrypted_payload`, so the assertion cannot be satisfied by an in-memory copy.
 * 2. Every released transition (ready, claim, failure, completion) retains the identity byte-equal.
 * 3. A new store instance over the same database still resolves the same identity.
 * 4. Attribution is never promoted into columns and never duplicated: one row, one payload shape.
 * 5. Unsupported and malformed payloads fail closed and are never read as legacy.
 */

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcGovernedSovereignOpsAuditOutboxStoreTest {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource
    private lateinit var store: GovernedJdbcSovereignOpsAuditOutboxStore

    private val testAesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private val testCodec =
        object : JdbcOpsAuditOutboxPayloadCodec {
            private val algorithm = "AES/GCM/NoPadding"
            private val tagLength = 128

            override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
                val cipher = Cipher.getInstance(algorithm)
                val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(testAesKey, "AES"), GCMParameterSpec(tagLength, nonce))
                val digest =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(plaintext)
                        .joinToString("") { "%02x".format(it) }
                return JdbcEncryptedAuditOutboxPayload(
                    ciphertext = cipher.doFinal(plaintext),
                    keyId = "governed-test-key-1",
                    algorithm = algorithm,
                    nonce = nonce,
                    payloadDigest = "sha256:$digest",
                )
            }

            override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray {
                val cipher = Cipher.getInstance(algorithm)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(testAesKey, "AES"),
                    GCMParameterSpec(tagLength, envelope.nonce),
                )
                return cipher.doFinal(envelope.ciphertext)
            }
        }

    @BeforeAll
    fun startPostgres() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("governed_outbox")
                .withUsername("test")
                .withPassword("test")
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        migrate()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun setUp() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("TRUNCATE TABLE audit_outbox CASCADE") }
        }
        store = governedStore()
    }

    // ---------- durable shape ----------

    @Test
    fun `governed append writes V2 into the same single row`() {
        runBlocking {
            val identity = identity()
            val record = record(identity, outboxId = "governed-row")

            store.appendGoverned(GovernedSovereignOpsAuditOutboxRecord(record, identity))

            assertEquals(1L, rowCount())
            val persisted = persistedRecord("governed-row")
            assertEquals(identity, persisted.runIdentity)
            assertEquals(record, persisted.record)
            assertGoverned(store, "governed-row", identity)
        }
    }

    @Test
    fun `legacy append stays V1 and is never reported as governed`() {
        runBlocking {
            val record = record(identity(), outboxId = "legacy-row")
            store.append(record)

            assertEquals(null, persistedRecord("legacy-row").runIdentity)
            val resolved = store.governanceById("legacy-row")
            assertThat(resolved).isInstanceOf(SovereignOpsAuditOutboxGovernance.Legacy::class.java)
            assertEquals(record, (resolved as SovereignOpsAuditOutboxGovernance.Legacy).record)

            store.markReadyForDispatch("legacy-row", SovereignOpsAuditOutboxStatus.PREPARED)
            assertEquals(null, persistedRecord("legacy-row").runIdentity)
            assertThat(store.governanceById("legacy-row"))
                .isInstanceOf(SovereignOpsAuditOutboxGovernance.Legacy::class.java)
        }
    }

    @Test
    fun `an absent record is NoRecord not Legacy`() {
        runBlocking {
            assertEquals(SovereignOpsAuditOutboxGovernance.NoRecord, store.governanceById("no-such-row"))
        }
    }

    @Test
    fun `identity survives ready claim failure retry and completion`() {
        runBlocking {
            val identity = identity()
            val record = record(identity, outboxId = "lifecycle")
            store.appendGoverned(GovernedSovereignOpsAuditOutboxRecord(record, identity))
            assertGoverned(store, "lifecycle", identity)

            store.markReadyForDispatch("lifecycle", SovereignOpsAuditOutboxStatus.PREPARED)
            assertGoverned(store, "lifecycle", identity)

            val claimed = store.claimPending("worker-1", 1, T0).single()
            assertEquals(1, claimed.attemptCount)
            assertGoverned(store, "lifecycle", identity)

            store.markFailed("lifecycle", SovereignOpsAuditOutboxStatus.EMITTING, 1, "transient", retryable = true)
            assertGoverned(store, "lifecycle", identity)

            store.claimPending("worker-2", 1, T0.plusSeconds(3600)).single()
            store.markEmitted(
                outboxId = "lifecycle",
                expectedStatus = SovereignOpsAuditOutboxStatus.EMITTING,
                expectedAttemptCount = 2,
                emittedAt = T0.plusSeconds(3700),
            )
            assertGoverned(store, "lifecycle", identity)
            assertEquals(1L, rowCount())
        }
    }

    @Test
    fun `a new store instance over the same database keeps the identity`() {
        runBlocking {
            val identity = identity()
            store.appendGoverned(
                GovernedSovereignOpsAuditOutboxRecord(record(identity, outboxId = "restart"), identity),
            )
            store.markReadyForDispatch("restart", SovereignOpsAuditOutboxStatus.PREPARED)

            val reopened = governedStore()
            assertGoverned(reopened, "restart", identity)
            assertEquals(identity, persistedRecord("restart").runIdentity)
        }
    }

    @Test
    fun `a governed row stays readable through the released store contract`() {
        runBlocking {
            val identity = identity()
            val record = record(identity, outboxId = "released-read")
            store.appendGoverned(GovernedSovereignOpsAuditOutboxRecord(record, identity))

            val released = plainStore()
            assertEquals(record, released.get("released-read"))
            assertEquals(record, released.findByEventKey(record.eventKey))
            released.markReadyForDispatch("released-read", SovereignOpsAuditOutboxStatus.PREPARED)
            assertEquals(
                SovereignOpsAuditOutboxStatus.PENDING,
                released.get("released-read")?.status,
            )
            assertEquals(identity, persistedRecord("released-read").runIdentity)
        }
    }

    @Test
    fun `governed append keeps the released duplicate codes`() {
        runBlocking {
            val identity = identity()
            val record = record(identity, outboxId = "duplicate")
            store.appendGoverned(GovernedSovereignOpsAuditOutboxRecord(record, identity))

            assertThatThrownBy {
                runBlocking {
                    store.appendGoverned(
                        GovernedSovereignOpsAuditOutboxRecord(record.copy(eventKey = "other"), identity),
                    )
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("tramai-sovereign-ops-outbox-duplicate-id")

            assertThatThrownBy {
                runBlocking {
                    store.appendGoverned(
                        GovernedSovereignOpsAuditOutboxRecord(
                            record(identity, outboxId = "other").copy(eventKey = record.eventKey),
                            identity,
                        ),
                    )
                }
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("tramai-sovereign-ops-outbox-duplicate-event-key")
            assertEquals(1L, rowCount())
        }
    }

    // ---------- codec fail-closed matrix ----------

    @Test
    fun `unknown schema version is unsupported never legacy`() {
        val payload = governedJson()
        val bumped = payload.replace("""{"schemaVersion":2,""", """{"schemaVersion":3,""")
        assertThat(bumped).isNotEqualTo(payload)
        assertThatThrownBy { decodeOutboxRecord(bumped.toByteArray()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("audit-outbox-unsupported-schema-version: 3")
    }

    @Test
    fun `payload without a declared version is corruption`() {
        val payload = governedJson().replace("""{"schemaVersion":2,""", "{")
        assertThatThrownBy { decodeOutboxRecord(payload.toByteArray()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no declared integral schema version")
    }

    @Test
    fun `a mistyped declared version is corruption, never a known or unsupported version`() {
        val payload = governedJson()
        val mistyped =
            mapOf(
                "text" to "\"2\"",
                "float" to "2.5",
                "non-numeric text" to "\"two\"",
                "null" to "null",
                "out of int range" to "99999999999",
            )
        mistyped.forEach { (kind, declared) ->
            val tampered = payload.replace("""{"schemaVersion":2,""", """{"schemaVersion":$declared,""")
            assertThat(tampered).describedAs("%s tamper must change the payload", kind).isNotEqualTo(payload)
            assertThatThrownBy { decodeOutboxRecord(tampered.toByteArray()) }
                .describedAs("a %s schema version must be corruption", kind)
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining(ERROR_CORRUPTED_RECORD)
        }
    }

    @Test
    fun `malformed payload is corruption`() {
        assertThatThrownBy { decodeOutboxRecord("{ not json".toByteArray()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(ERROR_CORRUPTED_RECORD)
    }

    @Test
    fun `partial V2 identity fails closed`() {
        val payload = governedJson()
        val tampered = payload.replace(""","deploymentId":"dep-1"""", "")
        assertThat(tampered).isNotEqualTo(payload)
        assertThatThrownBy { decodeOutboxRecord(tampered.toByteArray()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(ERROR_CORRUPTED_RECORD)
    }

    @Test
    fun `V2 identity naming another run fails closed`() {
        val payload = governedJson()
        val tampered = payload.replace(""""runId":"run-1"""", """"runId":"run-other"""")
        assertThat(tampered).isNotEqualTo(payload)
        assertThatThrownBy { decodeOutboxRecord(tampered.toByteArray()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("governed attribution names another run")
    }

    @Test
    fun `V1 payload carrying a governed identity is corruption`() {
        val payload = governedJson().replace("""{"schemaVersion":2,""", """{"schemaVersion":1,""")
        assertThatThrownBy { decodeOutboxRecord(payload.toByteArray()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("legacy payload carries a governed identity")
    }

    @Test
    fun `legacy record re-encodes as V1 and governed as V2`() {
        val identity = identity()
        val record = record(identity)
        assertEquals(null, decodeOutboxRecord(encodeOutboxRecord(record, null)).runIdentity)
        assertEquals(identity, decodeOutboxRecord(encodeOutboxRecord(record, identity)).runIdentity)
    }

    // ---------- helpers ----------

    private fun plainStore(): JdbcSovereignOpsAuditOutboxStore =
        JdbcSovereignOpsAuditOutboxStore(
            dataSource = dataSource,
            payloadCodec = testCodec,
            claimLeaseDuration = Duration.ofMinutes(5),
        )

    private fun governedStore(): GovernedOutboxStore = GovernedOutboxStore(plainStore())

    private suspend fun assertGoverned(
        subject: GovernedJdbcSovereignOpsAuditOutboxStore,
        outboxId: String,
        expected: GovernedRunIdentity,
    ) {
        val resolved = subject.governanceById(outboxId)
        assertThat(resolved).isInstanceOf(SovereignOpsAuditOutboxGovernance.Governed::class.java)
        assertEquals(expected, (resolved as SovereignOpsAuditOutboxGovernance.Governed).record.runIdentity)
    }

    private fun rowCount(): Long =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT count(*) FROM audit_outbox").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    /**
     * Decodes the plaintext of the durable row itself, so the V1/V2 shape claims are checked against
     * what is actually stored rather than against a round-tripped domain object.
     */
    private fun persistedRecord(outboxId: String): DecodedOutboxRecord =
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """SELECT encrypted_payload, encryption_key_id, encryption_algorithm,
                          encryption_nonce, payload_digest
                   FROM audit_outbox WHERE outbox_id = ?""",
                ).use { stmt ->
                    stmt.setString(1, outboxId)
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "no durable row for $outboxId" }
                        val envelope =
                            JdbcEncryptedAuditOutboxPayload(
                                ciphertext = rs.getBytes("encrypted_payload"),
                                keyId = rs.getString("encryption_key_id"),
                                algorithm = rs.getString("encryption_algorithm"),
                                nonce = rs.getBytes("encryption_nonce"),
                                payloadDigest = rs.getString("payload_digest"),
                            )
                        decodeOutboxRecord(testCodec.decode(envelope))
                    }
                }
        }

    private fun migrate() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                for (
                migration in
                listOf(
                    "V1__sovereign_persistence.sql",
                    "V4__audit_outbox_hardening.sql",
                )
                ) {
                    val sql =
                        this::class.java.classLoader
                            .getResource("tramai/persistence/jdbc/postgres/$migration")
                            ?.readText()
                            ?: error("Migration not found: $migration")
                    stmt.execute(sql)
                }
            }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun identity(
        workloadId: String = "wl-1",
        runId: String = "run-1",
    ): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId(workloadId),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId("cfg-1"),
                            version = ConfigurationVersion("v1"),
                        ),
                    environmentId = EnvironmentId("env-1"),
                    deploymentId = DeploymentId("dep-1"),
                ),
            runId = RunId(runId),
        )

    private fun record(
        identity: GovernedRunIdentity,
        outboxId: String = "outbox-1",
    ): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = outboxId,
            eventKey = "event-$outboxId",
            aggregateIdDigest = sha256("aggregate"),
            actor = "tester",
            workflowRunId = identity.runId.value,
            correlationId = null,
            approvalStatus = "DENIED",
            approvalVersion = 1L,
            reasonDigest = sha256("reason"),
            reasonLength = 6,
            createdAt = T0,
        )

    private fun governedJson(): String = String(encodeOutboxRecord(record(identity()), identity()))

    private companion object {
        private val T0: Instant = Instant.parse("2026-09-14T10:00:00Z")
    }
}

// Local alias for the governed wrapper: the class name plus a signature would otherwise push the
// one-line helper definitions past the analyzer's line limit.
private typealias GovernedOutboxStore = GovernedJdbcSovereignOpsAuditOutboxStore
