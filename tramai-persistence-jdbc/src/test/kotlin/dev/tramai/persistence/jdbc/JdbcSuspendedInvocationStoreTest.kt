package dev.tramai.persistence.jdbc

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.DataClassification
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.TokenBudgetSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.charset.StandardCharsets
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSuspendedInvocationStoreTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"

        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("suspended_test")
            .withUsername("test")
            .withPassword("test")

        private fun createDataSource(): DataSource = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private val fixedClock = Clock.fixed(
        Instant.parse("2026-06-21T12:00:00Z"),
        ZoneId.of("UTC"),
    )

    private val testAesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private val testCodec = object : JdbcReplayEnvelopeCodec {
        private val ALGORITHM = "AES/GCM/NoPadding"
        private val TAG_LENGTH = 128

        override fun encode(plaintext: ByteArray): JdbcEncryptedReplayEnvelope {
            val cipher = Cipher.getInstance(ALGORITHM)
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val keySpec = SecretKeySpec(testAesKey, "AES")
            val spec = GCMParameterSpec(TAG_LENGTH, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
            val ciphertext = cipher.doFinal(plaintext)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(plaintext)
                .joinToString("") { "%02x".format(it) }
            return JdbcEncryptedReplayEnvelope(
                ciphertext = ciphertext,
                keyId = "test-key-1",
                algorithm = ALGORITHM,
                nonce = nonce,
                payloadDigest = "sha256:$digest",
            )
        }

        override fun decode(envelope: JdbcEncryptedReplayEnvelope): ByteArray {
            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(testAesKey, "AES")
            val spec = GCMParameterSpec(TAG_LENGTH, envelope.nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
            return cipher.doFinal(envelope.ciphertext)
        }
    }

    // Codec that returns mismatched metadata (wrong keyId)
    private val wrongMetadataCodec = object : JdbcReplayEnvelopeCodec {
        override fun encode(plaintext: ByteArray): JdbcEncryptedReplayEnvelope {
            val enc = testCodec.encode(plaintext)
            return enc.copy(keyId = "mismatched-key-id", algorithm = "AES/GCM/NoPadding-BOGUS")
        }

        override fun decode(envelope: JdbcEncryptedReplayEnvelope): ByteArray {
            throw IllegalStateException("decryption-not-possible-with-wrong-metadata")
        }
    }

    private val fixedDigest = Sha256Digest.of("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        runMigrations()
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    private fun runMigrations() {
        val sql = javaClass.getResourceAsStream(
            "/tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql"
        )?.bufferedReader()?.readText()
            ?: throw IllegalStateException("Migration script not found")

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password
        )
        conn.use { c ->
            c.createStatement().use { stmt -> stmt.execute(sql) }
        }
    }

    @BeforeEach
    fun cleanTable() {
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password
        )
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM suspended_invocations")
            }
        }
    }

    private fun store(
        codec: JdbcReplayEnvelopeCodec = testCodec,
        clock: Clock = fixedClock,
    ): JdbcSuspendedInvocationStore = JdbcSuspendedInvocationStore(
        dataSource = createDataSource(),
        replayEnvelopeCodec = codec,
        clock = clock,
    )

    private fun sampleMetadata(
        approvalId: String = "si-test-1",
        digest: Sha256Digest = fixedDigest,
    ): SuspendedInvocationMetadata = SuspendedInvocationMetadata(
        approvalId = approvalId,
        toolCallId = "tc-1",
        toolName = "test_tool",
        toolCallIndex = 0,
        correlationId = "corr-1",
        identity = EngineExecutionIdentity(
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            workflowDigest = fixedDigest,
            policyVersion = "1.0",
            actorId = "actor:test",
        ),
        securityContext = ExecutionSecurityContext(
            dataClassification = DataClassification.INTERNAL,
            classificationSource = ClassificationSource.DECLARED,
        ),
        operationReference = ResumeOperationReference(
            serviceInterface = "com.example.TestService",
            methodName = "execute",
            jvmMethodDescriptor = "(Ljava/lang/String;)V",
            resumeDefinitionDigest = fixedDigest,
        ),
        replayEnvelopeDigest = digest,
        conversationId = "conv-1",
        historySize = 5,
        tokenBudgetSnapshot = TokenBudgetSnapshot(
            totalInputTokens = 100,
            totalOutputTokens = 50,
            totalInputCost = 0.01,
            totalOutputCost = 0.005,
            warnIfExceeded = false,
        ),
        toolReference = ResumeToolReference(
            toolName = "test_tool",
            declarationDigest = fixedDigest,
        ),
        toolSecurity = null,
    )

    private fun sampleMessages(): List<Message> = listOf(
        Message(
            role = MessageRole.USER,
            content = "Hello",
        ),
        Message(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = listOf(
                ToolCall(
                    id = "tc-1",
                    name = "test_tool",
                    argumentsJson = """{"key":"value"}""",
                ),
            ),
        ),
    )

    private fun sampleEnvelope(messages: List<Message> = sampleMessages()): SensitiveReplayEnvelope =
        SensitiveReplayEnvelope.of(messages)

    // ── Tests ─────────────────────────────────────────────────────

    @Test
    fun `stores a suspended invocation`() = runBlocking {
        val s = store()
        s.create(sampleMetadata(), sampleEnvelope())

        val loaded = s.get("si-test-1")
        assertNotNull(loaded)
        assertEquals("si-test-1", loaded.approvalId)
        assertEquals("tc-1", loaded.toolCallId)
        assertEquals("test_tool", loaded.toolName)
    }

    @Test
    fun `loads a suspended invocation by ID`() = runBlocking {
        val s = store()
        val digest1 = Sha256Digest.of("sha256:1111111111111111111111111111111111111111111111111111111111111111")
        val digest2 = Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222")
        s.create(sampleMetadata("si-load-1", digest1), sampleEnvelope())
        s.create(sampleMetadata("si-load-2", digest2), sampleEnvelope())

        val loaded = s.get("si-load-2")
        assertNotNull(loaded)
        assertEquals("si-load-2", loaded.approvalId)
    }

    @Test
    fun `persisted invocation survives new store instance`() = runBlocking {
        val s1 = store()
        s1.create(sampleMetadata("si-survive"), sampleEnvelope())

        val s2 = store()
        val loaded = s2.get("si-survive")
        assertNotNull(loaded)
        assertEquals("si-survive", loaded.approvalId)
    }

    @Test
    fun `duplicate invocation ID is rejected`() = runBlocking {
        val s = store()
        s.create(sampleMetadata("si-duplicate-id"), sampleEnvelope())

        val ex = assertFailsWith<IllegalArgumentException> {
            s.create(sampleMetadata("si-duplicate-id"), sampleEnvelope())
        }
        assertTrue(ex.message?.contains("already-exists", ignoreCase = true) == true)
    }

    @Test
    fun `duplicate replay envelope digest is rejected`() = runBlocking {
        val s = store()
        s.create(
            sampleMetadata("si-digest-1"),
            sampleEnvelope(),
        )

        val ex = assertFailsWith<IllegalArgumentException> {
            s.create(
                sampleMetadata("si-digest-2"),
                sampleEnvelope(),
            )
        }
        assertTrue(
            ex.message?.contains("digest", ignoreCase = true) == true ||
            ex.message?.contains("exists", ignoreCase = true) == true,
            "Expected digest/conflict message but got: ${ex.message}",
        )
    }

    @Test
    fun `replay envelope round-trips through codec`() = runBlocking {
        val s = store()
        val messages = sampleMessages()
        s.create(sampleMetadata("si-roundtrip"), sampleEnvelope(messages))

        val revealed = s.revealReplayEnvelope("si-roundtrip")
        assertNotNull(revealed)
        val payload = revealed.revealForResume()
        assertEquals(2, payload.messages.size)
        assertEquals("Hello", payload.messages[0].content)
        assertEquals("tc-1", payload.messages[1].toolCalls!![0].id)
    }

    @Test
    fun `encrypted_replay_envelope is non-null`() = runBlocking {
        val s = store()
        s.create(sampleMetadata("si-enc-nonnull"), sampleEnvelope())

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password
        )
        conn.use { c ->
            c.prepareStatement("SELECT encrypted_replay_envelope FROM suspended_invocations WHERE invocation_id = ?").use { stmt ->
                stmt.setString(1, "si-enc-nonnull")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val bytes = rs.getBytes("encrypted_replay_envelope")
                    assertNotNull(bytes)
                    assertTrue(bytes.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun `encryption metadata fields are non-null`() = runBlocking {
        val s = store()
        s.create(sampleMetadata("si-enc-meta"), sampleEnvelope())

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password
        )
        conn.use { c ->
            val sql = """
                SELECT encryption_key_id, encryption_algorithm, encryption_nonce, payload_digest
                FROM suspended_invocations WHERE invocation_id = ?
            """.trimIndent()
            c.prepareStatement(sql).use { stmt ->
                stmt.setString(1, "si-enc-meta")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertNotNull(rs.getString("encryption_key_id"))
                    assertNotNull(rs.getString("encryption_algorithm"))
                    assertNotNull(rs.getBytes("encryption_nonce"))
                    assertNotNull(rs.getString("payload_digest"))
                }
            }
        }
    }

    @Test
    fun `raw replay envelope is not visible in the database`() = runBlocking {
        val s = store()
        // Create with some sensitive content
        val messages = listOf(
            Message(MessageRole.USER, "Sensitive query: password=secret123"),
            Message(
                MessageRole.ASSISTANT, "",
                toolCalls = listOf(ToolCall("tc-x", "test_tool", """{"secret":"top-secret"}""")),
            ),
        )
        s.create(
            sampleMetadata("si-no-raw").copy(toolCallId = "tc-x"),
            sampleEnvelope(messages),
        )

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password
        )
        conn.use { c ->
            c.prepareStatement("SELECT encrypted_replay_envelope, invocation_id FROM suspended_invocations WHERE invocation_id = ?").use { stmt ->
                stmt.setString(1, "si-no-raw")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val ciphertext = rs.getBytes("encrypted_replay_envelope")
                    val text = ciphertext.toString(StandardCharsets.UTF_8)
                    // Verify ciphertext does not contain the plaintext strings
                    assertTrue(text.contains("secret123").not(), "Raw content visible in DB")
                    assertTrue(text.contains("top-secret").not(), "Raw tool args visible in DB")
                    assertTrue(text.contains("password").not(), "Raw password visible in DB")
                }
            }
        }
    }

    @Test
    fun `returns metadata not found`() = runBlocking {
        val s = store()
        assertNull(s.get("non-existent"))
    }

    @Test
    fun `returns envelope not found`() = runBlocking {
        val s = store()
        assertNull(s.revealReplayEnvelope("non-existent"))
    }

    @Test
    fun `returns metadata on remove`() = runBlocking {
        val s = store()
        s.create(sampleMetadata("si-remove"), sampleEnvelope())

        val metadata = s.remove("si-remove")
        assertNotNull(metadata)
        assertEquals("si-remove", metadata.approvalId)

        // Gone after removal
        assertNull(s.get("si-remove"))
    }

    @Test
    fun `returns null on remove for non-existent`() = runBlocking {
        val s = store()
        assertNull(s.remove("non-existent"))
    }

    @Test
    fun `version increments on create`() = runBlocking {
        val s = store()
        s.create(sampleMetadata("si-version"), sampleEnvelope())

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password
        )
        conn.use { c ->
            c.prepareStatement("SELECT version FROM suspended_invocations WHERE invocation_id = ?").use { stmt ->
                stmt.setString(1, "si-version")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1L, rs.getLong("version"))
                }
            }
        }
    }

    @Test
    fun `duplicate remove returns null`() = runBlocking {
        val s = store()
        s.create(sampleMetadata("si-dup-remove"), sampleEnvelope())

        val first = s.remove("si-dup-remove")
        assertNotNull(first)

        val second = s.remove("si-dup-remove")
        assertNull(second)
    }

    @Test
    fun `corrupted encrypted payload fails closed`() = runBlocking {
        val s = store()
        s.create(sampleMetadata("si-corrupt"), sampleEnvelope())

        // Corrupt the ciphertext in the database
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password
        )
        conn.use { c ->
            c.prepareStatement(
                "UPDATE suspended_invocations SET encrypted_replay_envelope = ?::bytea WHERE invocation_id = ?"
            ).use { stmt ->
                stmt.setBytes(1, byteArrayOf(0x00, 0x01, 0x02))
                stmt.setString(2, "si-corrupt")
                stmt.executeUpdate()
            }
        }

        val ex = assertFailsWith<IllegalStateException> {
            s.get("si-corrupt")
        }
        assertTrue(
            ex.message?.contains("decryption", ignoreCase = true) == true ||
            ex.message?.contains("corrupted", ignoreCase = true) == true ||
            ex.message?.contains("failed", ignoreCase = true) == true ||
            ex.cause?.message?.contains("AEADBadTagException") == true ||
            ex.message?.contains("Tag mismatch", ignoreCase = true) == true,
        )
    }

    @Test
    fun `wrong encryption metadata fails closed`() = runBlocking {
        // Store with testCodec, then try to read with wrongMetadataCodec
        val sWrite = store(codec = testCodec)
        sWrite.create(sampleMetadata("si-bad-meta"), sampleEnvelope())

        val sRead = store(codec = wrongMetadataCodec)
        val ex = assertFailsWith<IllegalStateException> {
            sRead.get("si-bad-meta")
        }
        assertTrue(
            ex.message?.contains("decryption", ignoreCase = true) == true ||
            ex.message?.contains("failed", ignoreCase = true) == true,
        )
    }

    @Test
    fun `non-unique SQL errors are not mapped to duplicate conflicts`() = runBlocking {
        // Verify that a non-23505 SQLException is rethrown, not swallowed as conflict
        val realDs = createDataSource()
        val throwingDs = object : DataSource by realDs {
            private var callCount = 0
            override fun getConnection(): java.sql.Connection {
                callCount++
                val realConn = realDs.getConnection()
                return object : java.sql.Connection by realConn {
                    override fun prepareStatement(sql: String): java.sql.PreparedStatement {
                        // On the INSERT call (matches INSERT pattern), throw a non-23505 error
                        if (sql.trimStart().startsWith("INSERT", ignoreCase = true)) {
                            realConn.close()
                            throw java.sql.SQLException("connection failure", "08006")
                        }
                        return realConn.prepareStatement(sql)
                    }
                }
            }
        }
        val s = JdbcSuspendedInvocationStore(
            dataSource = throwingDs,
            replayEnvelopeCodec = testCodec,
            clock = fixedClock,
        )
        val ex = assertFailsWith<java.sql.SQLException> {
            s.create(sampleMetadata("si-sql-error"), sampleEnvelope())
        }
        assertTrue(ex.message?.contains("connection failure", ignoreCase = true) == true,
            "Non-23505 SQL error must be rethrown, not mapped to conflict")
    }

    @Test
    fun `concurrent creation with same digest results in exactly one success`() = runBlocking {
        val s = store()
        val messages = sampleMessages()

        // This digest will be the same since both use the same messages + codec
        val results = coroutineScope {
            val d1 = async {
                try {
                    s.create(sampleMetadata("si-conc-1", fixedDigest), sampleEnvelope(messages))
                    "created"
                } catch (e: IllegalArgumentException) {
                    "rejected:${e.message}"
                }
            }
            val d2 = async {
                try {
                    s.create(sampleMetadata("si-conc-2", fixedDigest), sampleEnvelope(messages))
                    "created"
                } catch (e: IllegalArgumentException) {
                    "rejected:${e.message}"
                }
            }
            listOf(d1.await(), d2.await())
        }

        assertEquals(2, results.size)
        val createdCount = results.count { it == "created" }
        assertEquals(1, createdCount, "Exactly one concurrent creation should succeed")
    }

    @Test
    fun `concurrent remove returns metadata once and null once`() = runBlocking {
        val s = store()
        s.create(sampleMetadata("si-remove-concurrent"), sampleEnvelope())

        val results = coroutineScope {
            listOf(
                async { s.remove("si-remove-concurrent") },
                async { s.remove("si-remove-concurrent") },
            ).map { it.await() }
        }

        assertEquals(1, results.count { it != null })
        assertEquals(1, results.count { it == null })
    }

    @Test
    fun `metadata round-trips all fields correctly`() = runBlocking {
        val s = store()
        s.create(sampleMetadata("si-full-meta"), sampleEnvelope())

        val loaded = s.get("si-full-meta")!!
        assertEquals("si-full-meta", loaded.approvalId)
        assertEquals("tc-1", loaded.toolCallId)
        assertEquals("test_tool", loaded.toolName)
        assertEquals(0, loaded.toolCallIndex)
        assertEquals("corr-1", loaded.correlationId)
        assertEquals("wf-1", loaded.identity.workflowRunId)
        assertEquals("corr-1", loaded.identity.correlationId)
        assertEquals(fixedDigest, loaded.identity.workflowDigest)
        assertEquals("1.0", loaded.identity.policyVersion)
        assertEquals("actor:test", loaded.identity.actorId)
        assertEquals(DataClassification.INTERNAL, loaded.securityContext.dataClassification)
        assertEquals(ClassificationSource.DECLARED, loaded.securityContext.classificationSource)
        assertEquals("com.example.TestService", loaded.operationReference.serviceInterface)
        assertEquals("execute", loaded.operationReference.methodName)
        assertEquals("(Ljava/lang/String;)V", loaded.operationReference.jvmMethodDescriptor)
        assertEquals(fixedDigest, loaded.operationReference.resumeDefinitionDigest)
        assertEquals(fixedDigest, loaded.replayEnvelopeDigest)
        assertEquals("conv-1", loaded.conversationId)
        assertEquals(5, loaded.historySize)
        assertEquals(100L, loaded.tokenBudgetSnapshot?.totalInputTokens)
        assertEquals(50L, loaded.tokenBudgetSnapshot?.totalOutputTokens)
        assertEquals(0.01, loaded.tokenBudgetSnapshot!!.totalInputCost, 0.001)
        assertEquals(0.005, loaded.tokenBudgetSnapshot!!.totalOutputCost, 0.001)
        assertEquals("test_tool", loaded.toolReference.toolName)
    }

    @Test
    fun `store clock is used for created_at`() = runBlocking {
        val s = store(clock = Clock.fixed(
            Instant.parse("2026-01-15T08:30:00Z"),
            ZoneId.of("UTC"),
        ))
        s.create(sampleMetadata("si-clock"), sampleEnvelope())

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password
        )
        conn.use { c ->
            c.prepareStatement("SELECT created_at FROM suspended_invocations WHERE invocation_id = ?").use { stmt ->
                stmt.setString(1, "si-clock")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val createdAt = rs.getTimestamp("created_at").toInstant()
                    assertEquals(Instant.parse("2026-01-15T08:30:00Z"), createdAt)
                }
            }
        }
    }

    @Test
    fun `service_key and operation_key are stored`() = runBlocking {
        val s = store()
        s.create(sampleMetadata("si-keys"), sampleEnvelope())

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password
        )
        conn.use { c ->
            val sql = "SELECT service_key, operation_key, descriptor_hash FROM suspended_invocations WHERE invocation_id = ?"
            c.prepareStatement(sql).use { stmt ->
                stmt.setString(1, "si-keys")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("com.example.TestService", rs.getString("service_key"))
                    assertEquals("execute", rs.getString("operation_key"))
                    val hash = rs.getString("descriptor_hash")
                    assertNotNull(hash)
                    assertTrue(hash.startsWith("sha256:"))
                }
            }
        }
    }
}
