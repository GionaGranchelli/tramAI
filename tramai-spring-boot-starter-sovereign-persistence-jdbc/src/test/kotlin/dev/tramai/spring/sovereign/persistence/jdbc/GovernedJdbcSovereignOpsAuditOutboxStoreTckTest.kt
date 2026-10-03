@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import dev.tramai.testing.persistence.outbox.SovereignOpsAuditOutboxStoreTck
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/**
 * 0.7.1d: [GovernedJdbcSovereignOpsAuditOutboxStore] is a concrete [SovereignOpsAuditOutboxStore]
 * implementation, so it must satisfy the shared outbox compatibility contract like any other store.
 *
 * Enrolling the wrapper is worth more than appeasing the enrollment guard: the released contract now
 * runs through the governed delegation layer, which is what proves legacy behaviour stays transparent
 * and that identity-aware encoding changed nothing a released caller can observe.
 *
 * The runner owns the datasource, schema, and per-case isolation exactly like the plain store's runner.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernedJdbcSovereignOpsAuditOutboxStoreTckTest : SovereignOpsAuditOutboxStoreTck() {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection

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
                    keyId = "governed-tck-key-1",
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
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("governed_outbox_tck")
                .withUsername("test")
                .withPassword("test")
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        setupConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
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
            setupConnection.createStatement().use { stmt -> stmt.execute(sql) }
        }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { setupConnection.close() }
        runCatching { postgres.stop() }
    }

    override fun createStore(): SovereignOpsAuditOutboxStore {
        // Fresh isolated storage per case: previous cases' records must not leak into the next case.
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("TRUNCATE TABLE audit_outbox CASCADE") }
        }
        return GovernedJdbcSovereignOpsAuditOutboxStore(
            JdbcSovereignOpsAuditOutboxStore(
                dataSource = dataSource,
                payloadCodec = testCodec,
                claimLeaseDuration = Duration.ofMinutes(5),
            ),
        )
    }
}
