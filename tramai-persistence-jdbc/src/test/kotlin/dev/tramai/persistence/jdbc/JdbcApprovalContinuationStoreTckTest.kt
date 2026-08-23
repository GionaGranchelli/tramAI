package dev.tramai.persistence.jdbc

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.testing.persistence.approval.MutableClock
import dev.tramai.testing.persistence.approval.continuation.ApprovalContinuationStoreTck
import dev.tramai.testing.persistence.approval.continuation.ApprovalContinuationStoreTckHarness
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/**
 * Epic 8.1b: JdbcApprovalContinuationStore must satisfy the shared
 * ApprovalContinuationStore compatibility contract (tramai-testing
 * testFixtures). The runner owns the datasource, schema, and arguments
 * codec — storage technology never contaminates the contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcApprovalContinuationStoreTckTest : ApprovalContinuationStoreTck() {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection

    private val testAesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private val testCodec = object : JdbcContinuationArgumentsCodec {
        private val algorithm = "AES/GCM/NoPadding"
        private val tagLength = 128

        override fun encode(plaintext: ByteArray): JdbcEncryptedContinuationArguments {
            val cipher = Cipher.getInstance(algorithm)
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(testAesKey, "AES"), GCMParameterSpec(tagLength, nonce))
            val ciphertext = cipher.doFinal(plaintext)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(plaintext)
                .joinToString("") { "%02x".format(it) }
            return JdbcEncryptedContinuationArguments(
                ciphertext = ciphertext,
                keyId = "tck-key-1",
                algorithm = algorithm,
                nonce = nonce,
                payloadDigest = "sha256:$digest",
            )
        }

        override fun decode(envelope: JdbcEncryptedContinuationArguments): ByteArray {
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
            .withDatabaseName("continuation_tck")
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
        val v2Sql = this::class.java.classLoader
            .getResource("tramai/persistence/jdbc/postgres/V2__approval_continuations.sql")
            ?.readText()
            ?: throw IllegalStateException("V2 schema SQL resource not found")
        setupConnection.createStatement().use { stmt -> stmt.execute(v1Sql) }
        setupConnection.createStatement().use { stmt -> stmt.execute(v2Sql) }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { setupConnection.close() }
        runCatching { postgres.stop() }
    }

    override val harness = object : ApprovalContinuationStoreTckHarness {
        override fun createStore(clock: MutableClock): ApprovalContinuationStore {
            // Fresh isolated storage per case: previous cases' records must
            // not leak into the next case.
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt -> stmt.execute("DELETE FROM approval_continuations") }
            }
            return JdbcApprovalContinuationStore(dataSource, testCodec, clock = clock)
        }
    }
}
