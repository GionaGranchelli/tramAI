package dev.tramai.persistence.jdbc

import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.testing.persistence.engine.SuspendedInvocationStoreTck
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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
 * Epic 8.1c: JdbcSuspendedInvocationStore must satisfy the shared
 * SuspendedInvocationStore compatibility contract (tramai-testing
 * testFixtures). The runner owns the datasource, schema, replay-envelope
 * codec, and per-case isolation — storage technology never contaminates the
 * contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSuspendedInvocationStoreTckTest : SuspendedInvocationStoreTck() {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection

    private val testAesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private val testCodec = object : JdbcReplayEnvelopeCodec {
        private val algorithm = "AES/GCM/NoPadding"
        private val tagLength = 128

        override fun encode(plaintext: ByteArray): JdbcEncryptedReplayEnvelope {
            val cipher = Cipher.getInstance(algorithm)
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(testAesKey, "AES"), GCMParameterSpec(tagLength, nonce))
            val ciphertext = cipher.doFinal(plaintext)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(plaintext)
                .joinToString("") { "%02x".format(it) }
            return JdbcEncryptedReplayEnvelope(
                ciphertext = ciphertext,
                keyId = "tck-key-1",
                algorithm = algorithm,
                nonce = nonce,
                payloadDigest = "sha256:$digest",
            )
        }

        override fun decode(envelope: JdbcEncryptedReplayEnvelope): ByteArray {
            val cipher = Cipher.getInstance(algorithm)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(testAesKey, "AES"),
                GCMParameterSpec(tagLength, envelope.nonce),
            )
            return cipher.doFinal(envelope.ciphertext)
        }
    }

    private val fixedClock = Clock.fixed(
        Instant.parse("2026-06-21T12:00:00Z"),
        ZoneId.of("UTC"),
    )

    @BeforeAll
    fun setUpAll() {
        postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("suspended_tck")
            .withUsername("test")
            .withPassword("test")
        postgres.start()
        dataSource = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
        setupConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        val v1Sql = this::class.java.classLoader
            .getResource("tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql")
            ?.readText()
            ?: throw IllegalStateException("V1 schema SQL resource not found")
        setupConnection.createStatement().use { stmt -> stmt.execute(v1Sql) }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { setupConnection.close() }
        runCatching { postgres.stop() }
    }

    override fun createStore(): SuspendedInvocationStore {
        // Fresh isolated storage per case: previous cases' records must not
        // leak into the next case.
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("DELETE FROM suspended_invocations") }
        }
        return JdbcSuspendedInvocationStore(dataSource, testCodec, clock = fixedClock)
    }
}
