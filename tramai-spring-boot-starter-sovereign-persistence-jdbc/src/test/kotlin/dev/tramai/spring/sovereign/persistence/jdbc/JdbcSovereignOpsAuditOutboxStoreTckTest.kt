package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import dev.tramai.testing.persistence.outbox.SovereignOpsAuditOutboxStoreTck
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Epic 8.1e: JdbcSovereignOpsAuditOutboxStore must satisfy the shared outbox
 * compatibility contract (tramai-testing testFixtures). The runner owns the
 * datasource, schema, and per-case isolation — storage technology (SQL
 * indexes/schema, FOR UPDATE SKIP LOCKED, maxClaimLimit, queryable-column
 * tamper detection, physical transactions) never contaminates the contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSovereignOpsAuditOutboxStoreTckTest : SovereignOpsAuditOutboxStoreTck() {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection

    private val testAesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private val testCodec = object : JdbcOpsAuditOutboxPayloadCodec {
        private val algorithm = "AES/GCM/NoPadding"
        private val tagLength = 128

        override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
            val cipher = Cipher.getInstance(algorithm)
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(testAesKey, "AES"), GCMParameterSpec(tagLength, nonce))
            val ciphertext = cipher.doFinal(plaintext)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(plaintext)
                .joinToString("") { "%02x".format(it) }
            return JdbcEncryptedAuditOutboxPayload(
                ciphertext = ciphertext,
                keyId = "tck-key-1",
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
        postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("outbox_tck")
            .withUsername("test")
            .withPassword("test")
        postgres.start()
        dataSource = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
        setupConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        for (migration in listOf(
            "V1__sovereign_persistence.sql",
            "V4__audit_outbox_hardening.sql",
        )) {
            val sql = this::class.java.classLoader
                .getResource("tramai/persistence/jdbc/postgres/$migration")
                ?.readText()
                ?: throw IllegalStateException("Migration not found: $migration")
            setupConnection.createStatement().use { stmt -> stmt.execute(sql) }
        }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { setupConnection.close() }
        runCatching { postgres.stop() }
    }

    override fun createStore(): SovereignOpsAuditOutboxStore {
        // Fresh isolated storage per case: previous cases' records must not
        // leak into the next case.
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("TRUNCATE TABLE audit_outbox CASCADE") }
        }
        return JdbcSovereignOpsAuditOutboxStore(
            dataSource = dataSource,
            payloadCodec = testCodec,
            claimLeaseDuration = Duration.ofMinutes(5),
        )
    }
}
