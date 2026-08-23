package dev.tramai.persistence.jdbc

import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import dev.tramai.security.audit.CURRENT_AUDIT_SCHEMA_VERSION
import dev.tramai.security.audit.calculateHash
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcAuditStoreTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"

        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("audit_test")
            .withUsername("test")
            .withPassword("test")

        private fun createDataSource(): DataSource = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private val fixedClock: Clock = Clock.fixed(
        Instant.parse("2026-06-22T12:00:00Z"),
        ZoneId.of("UTC"),
    )

    private val testAesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private val testCodec = object : JdbcAuditPayloadCodec {
        private val ALGORITHM = "AES/GCM/NoPadding"
        private val TAG_LENGTH = 128

        override fun encode(plaintext: ByteArray): JdbcEncryptedAuditPayload {
            val cipher = Cipher.getInstance(ALGORITHM)
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val keySpec = SecretKeySpec(testAesKey, "AES")
            val spec = GCMParameterSpec(TAG_LENGTH, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
            val ciphertext = cipher.doFinal(plaintext)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(plaintext)
                .joinToString("") { "%02x".format(it) }
            return JdbcEncryptedAuditPayload(
                ciphertext = ciphertext,
                keyId = "test-key-1",
                algorithm = ALGORITHM,
                nonce = nonce,
                payloadDigest = "sha256:$digest",
            )
        }

        override fun decode(envelope: JdbcEncryptedAuditPayload): ByteArray {
            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(testAesKey, "AES")
            val spec = GCMParameterSpec(TAG_LENGTH, envelope.nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
            return cipher.doFinal(envelope.ciphertext)
        }
    }

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
        val migrations = listOf(
            "V1__sovereign_persistence.sql",
            "V2__approval_continuations.sql",
            "V3__audit_events_hardening.sql",
        )
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            for (migration in migrations) {
                val sql = javaClass.getResourceAsStream(
                    "/tramai/persistence/jdbc/postgres/$migration",
                )?.bufferedReader()?.readText()
                    ?: throw IllegalStateException("Migration not found: $migration")
                c.createStatement().use { stmt -> stmt.execute(sql) }
            }
        }
    }

    @BeforeEach
    fun cleanTables() {
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM audit_events")
                stmt.execute("DELETE FROM audit_stream_heads")
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun store(
        codec: JdbcAuditPayloadCodec = testCodec,
        clock: Clock = fixedClock,
    ): JdbcAuditStore = JdbcAuditStore(
        dataSource = createDataSource(),
        payloadCodec = codec,
        clock = clock,
    )

    private fun createEvent(
        auditStreamId: String = "stream-1",
        sequenceNumber: Long = 1,
        eventId: String = "evt-1",
        previousEventHash: String? = null,
        decision: String = "APPROVED",
        actor: String? = "user:alice",
        metadata: Map<String, String> = mapOf("key" to "value"),
    ): AuditEvent {
        val base = AuditEvent(
            schemaVersion = CURRENT_AUDIT_SCHEMA_VERSION,
            hashAlgorithm = AuditHashAlgorithm.SHA_256,
            auditStreamId = auditStreamId,
            eventId = eventId,
            sequenceNumber = sequenceNumber,
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            actor = actor,
            enforcementPoint = "approval",
            decision = decision,
            policyVersion = "v1",
            workflowDigest = "sha256:${"a".repeat(64)}",
            previousEventHash = previousEventHash,
            eventHash = "", // will be computed
            timestamp = fixedClock.instant(),
            reasonCode = null,
            metadata = metadata,
        )
        return base.copy(eventHash = base.copy(eventHash = "").calculateHash())
    }

    private fun eventFactory(
        auditStreamId: String = "stream-1",
        sequenceOffset: Long = 0,
        eventIdPrefix: String = "evt",
        decision: String = "APPROVED",
        actor: String? = "user:alice",
    ): (AuditEvent?) -> AuditEvent = { latest ->
        val seq = (latest?.sequenceNumber ?: 0L) + 1L + sequenceOffset
        // Include stream ID in eventId to avoid cross-stream uniqueness conflicts
        val eid = "$eventIdPrefix-$auditStreamId-$seq"
        createEvent(
            auditStreamId = auditStreamId,
            sequenceNumber = seq,
            eventId = eid,
            previousEventHash = latest?.eventHash,
            decision = decision,
            actor = actor,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Append tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `append first event gets sequence 1 with null previous hash`() { runBlocking {
        val s = store()
        val event = s.appendNext("stream-1", eventFactory("stream-1"))

        assertEquals(1L, event.sequenceNumber)
        assertNull(event.previousEventHash)
        assertEquals("APPROVED", event.decision)
        assertEquals("evt-stream-1-1", event.eventId)
    }
    }

    @Test
    fun `append second event gets sequence 2 with correct previous hash`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))
        val second = s.appendNext("stream-1", eventFactory("stream-1"))

        assertEquals(2L, second.sequenceNumber)
        assertNotNull(second.previousEventHash)

        val first = s.latestEvent("stream-1")
        assertNotNull(first)
        assertEquals(2L, first.sequenceNumber)
    }
    }

    @Test
    fun `duplicate event ID is rejected`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))

        assertFailsWith<IllegalArgumentException> {
            s.appendNext("stream-1") { latest ->
                // Return an event with a deliberately duplicate event ID
                createEvent(
                    auditStreamId = "stream-1",
                    sequenceNumber = 2L,
                    eventId = "evt-stream-1-1", // same ID as first
                    previousEventHash = latest?.eventHash,
                )
            }
        }
    }
    }

    @Test
    fun `append with sequence gap is rejected`() { runBlocking {
        val s = store()

        assertFailsWith<IllegalArgumentException> {
            s.appendNext("stream-1") {
                createEvent(
                    auditStreamId = "stream-1",
                    sequenceNumber = 5L,
                    eventId = "evt-gap",
                    previousEventHash = null,
                )
            }
        }
    }
    }

    @Test
    fun `append with wrong stream ID is rejected`() { runBlocking {
        val s = store()

        assertFailsWith<IllegalArgumentException> {
            s.appendNext("stream-1") {
                createEvent(
                    auditStreamId = "stream-WRONG",
                    sequenceNumber = 1L,
                    eventId = "evt-wrong-stream",
                    previousEventHash = null,
                )
            }
        }
    }
    }

    @Test
    fun `append with wrong previous hash is rejected`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))

        assertFailsWith<IllegalArgumentException> {
            s.appendNext("stream-1") {
                createEvent(
                    auditStreamId = "stream-1",
                    sequenceNumber = 2L,
                    eventId = "evt-bad-hash",
                    previousEventHash = "0000000000000000000000000000000000000000000000000000000000000000",
                )
            }
        }
    }
    }

    @Test
    fun `append with wrong event hash is rejected`() { runBlocking {
        val s = store()

        assertFailsWith<IllegalArgumentException> {
            s.appendNext("stream-1") {
                createEvent(
                    auditStreamId = "stream-1",
                    sequenceNumber = 1L,
                    eventId = "evt-bad-self-hash",
                    previousEventHash = null,
                ).copy(eventHash = "bad".repeat(20))
            }
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Read tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `readStream returns events in ascending sequence order`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))
        s.appendNext("stream-1", eventFactory("stream-1"))
        s.appendNext("stream-1", eventFactory("stream-1"))

        val events = s.readStream("stream-1")
        assertEquals(3, events.size)
        assertEquals(1L, events[0].sequenceNumber)
        assertEquals(2L, events[1].sequenceNumber)
        assertEquals(3L, events[2].sequenceNumber)
        assertEquals("evt-stream-1-1", events[0].eventId)
        assertEquals("evt-stream-1-2", events[1].eventId)
        assertEquals("evt-stream-1-3", events[2].eventId)
    }
    }

    @Test
    fun `readStream validates full chain and rejects corruption`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))
        s.appendNext("stream-1", eventFactory("stream-1"))

        // Tamper with event hash directly in the DB
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "UPDATE audit_events SET event_hash = 'corrupted' WHERE event_id = 'evt-stream-1-1'",
            ).use { stmt -> stmt.executeUpdate() }
        }

        assertFailsWith<IllegalArgumentException> {
            s.readStream("stream-1")
        }
    }
    }

    @Test
    fun `readStream returns empty for non-existent stream`() { runBlocking {
        val events = store().readStream("non-existent")
        assertTrue(events.isEmpty())
    }
    }

    @Test
    fun `readStreamPage with null after returns first page`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))
        s.appendNext("stream-1", eventFactory("stream-1"))
        s.appendNext("stream-1", eventFactory("stream-1"))

        val page = s.readStreamPage("stream-1", afterSequenceNumber = null, limit = 2)
        assertEquals(2, page.size)
        assertEquals(1L, page[0].sequenceNumber)
        assertEquals(2L, page[1].sequenceNumber)
    }
    }

    @Test
    fun `readStreamPage with cursor returns next page`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))
        s.appendNext("stream-1", eventFactory("stream-1"))
        s.appendNext("stream-1", eventFactory("stream-1"))

        val page = s.readStreamPage("stream-1", afterSequenceNumber = 1, limit = 2)
        assertEquals(2, page.size)
        assertEquals(2L, page[0].sequenceNumber)
        assertEquals(3L, page[1].sequenceNumber)
    }
    }

    @Test
    fun `readStreamPage rejects negative cursor`() { runBlocking {
        assertFailsWith<IllegalArgumentException> {
            store().readStreamPage("stream-1", afterSequenceNumber = -1, limit = 10)
        }
    }
    }

    @Test
    fun `readStreamPage rejects zero limit`() { runBlocking {
        assertFailsWith<IllegalArgumentException> {
            store().readStreamPage("stream-1", afterSequenceNumber = null, limit = 0)
        }
    }
    }

    @Test
    fun `latestEvent returns newest event`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))
        s.appendNext("stream-1", eventFactory("stream-1"))

        val latest = s.latestEvent("stream-1")
        assertNotNull(latest)
        assertEquals(2L, latest.sequenceNumber)
        assertEquals("evt-stream-1-2", latest.eventId)
    }
    }

    @Test
    fun `latestEvent returns null for empty stream`() { runBlocking {
        assertNull(store().latestEvent("empty-stream"))
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Restart tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `store A appends, store B reads`() { runBlocking {
        val sA = store()
        sA.appendNext("stream-1", eventFactory("stream-1"))
        sA.appendNext("stream-1", eventFactory("stream-1"))

        val sB = store()
        val events = sB.readStream("stream-1")
        assertEquals(2, events.size)
        assertEquals("evt-stream-1-1", events[0].eventId)
        assertEquals("evt-stream-1-2", events[1].eventId)
    }
    }

    @Test
    fun `store A appends, store B appends next`() { runBlocking {
        val sA = store()
        sA.appendNext("stream-1", eventFactory("stream-1"))

        val sB = store()
        val next = sB.appendNext("stream-1", eventFactory("stream-1"))
        assertEquals(2L, next.sequenceNumber)
        assertEquals("evt-stream-1-2", next.eventId)
    }
    }

    @Test
    fun `store C reads full stream after A and B appends`() { runBlocking {
        val sA = store()
        sA.appendNext("stream-1", eventFactory("stream-1"))
        val sB = store()
        sB.appendNext("stream-1", eventFactory("stream-1"))

        val sC = store()
        val events = sC.readStream("stream-1")
        assertEquals(2, events.size)
        val chain = checkChain(events)
        assertTrue(chain)
    }
    }

    private fun checkChain(events: List<AuditEvent>): Boolean {
        var prev: AuditEvent? = null
        for (event in events) {
            if (prev != null) {
                if (event.previousEventHash != prev.eventHash) return false
                if (event.sequenceNumber != prev.sequenceNumber + 1) return false
            }
            if (event.eventHash != event.copy(eventHash = "").calculateHash()) return false
            prev = event
        }
        return true
    }

    // ═══════════════════════════════════════════════════════════════
    // Concurrency tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `concurrent appenders to same stream produce ordered sequence`() { runBlocking {
        val s = store()
        val numAppenders = 5

        coroutineScope {
            val jobs = (1..numAppenders).map { index ->
                async {
                    s.appendNext("concurrent-stream") { latest ->
                        val seq = (latest?.sequenceNumber ?: 0L) + 1L
                        createEvent(
                            auditStreamId = "concurrent-stream",
                            sequenceNumber = seq,
                            eventId = "concurrent-evt-$seq",
                            previousEventHash = latest?.eventHash,
                        )
                    }
                }
            }
            jobs.forEach { it.await() }
        }

        val events = s.readStream("concurrent-stream")
        assertEquals(numAppenders, events.size)

        // Verify sequential ordering
        for (i in 0 until numAppenders) {
            assertEquals((i + 1L).toLong(), events[i].sequenceNumber)
        }

        // Verify hash chain
        assertTrue(checkChain(events), "Hash chain must be valid after concurrent appends")
    }
    }

    @Test
    fun `concurrent appenders to different streams both succeed independently`() { runBlocking {
        val s = store()

        coroutineScope {
            val jobA = async {
                s.appendNext("stream-a", eventFactory("stream-a"))
                s.appendNext("stream-a", eventFactory("stream-a"))
            }
            val jobB = async {
                s.appendNext("stream-b", eventFactory("stream-b"))
                s.appendNext("stream-b", eventFactory("stream-b"))
            }
            jobA.await()
            jobB.await()
        }

        assertEquals(2, s.readStream("stream-a").size)
        assertEquals(2, s.readStream("stream-b").size)
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Encryption tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `encrypted_payload is non-null after append`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "SELECT encrypted_payload FROM audit_events WHERE stream_id = ?",
            ).use { stmt ->
                stmt.setString(1, "stream-1")
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val bytes = rs.getBytes("encrypted_payload")
                    assertNotNull(bytes)
                    assertTrue(bytes.isNotEmpty())
                }
            }
        }
    }
    }

    @Test
    fun `raw metadata and decision not visible in database`() { runBlocking {
        val s = store()
        s.appendNext("stream-1") {
            createEvent(
                auditStreamId = "stream-1",
                sequenceNumber = 1,
                eventId = "evt-secret",
                previousEventHash = null,
                decision = "SENSITIVE_DECISION",
                actor = "user:secret-agent",
                metadata = mapOf("secretKey" to "super-secret-value"),
            )
        }

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "SELECT encrypted_payload FROM audit_events WHERE event_id = 'evt-secret'",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val ciphertext = rs.getBytes("encrypted_payload")
                    val text = ciphertext.toString(Charsets.UTF_8)
                    assertTrue(text.contains("super-secret-value").not(),
                        "Raw metadata must not be visible in DB")
                    assertTrue(text.contains("SENSITIVE_DECISION").not(),
                        "Decision must not be visible in DB")
                    assertTrue(text.contains("secret-agent").not(),
                        "Actor must not be visible in DB")
                }
            }
        }
    }
    }

    @Test
    fun `decode failure fails closed on read`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))

        // Create a store with a different key that can't decrypt
        val wrongKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val brokenCodec = object : JdbcAuditPayloadCodec {
            override fun encode(plaintext: ByteArray): JdbcEncryptedAuditPayload {
                throw UnsupportedOperationException("should not be called")
            }

            override fun decode(envelope: JdbcEncryptedAuditPayload): ByteArray {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val keySpec = SecretKeySpec(wrongKey, "AES")
                val spec = GCMParameterSpec(128, envelope.nonce)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
                return cipher.doFinal(envelope.ciphertext)
            }
        }
        val brokenStore = store(codec = brokenCodec)

        assertFailsWith<IllegalStateException> {
            brokenStore.readStream("stream-1")
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Schema hardening tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `schema rejects negative sequence number`() { runBlocking {
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        assertFailsWith<org.postgresql.util.PSQLException> {
            conn.prepareStatement(
                """INSERT INTO audit_events
                   (stream_id, sequence_number, event_id, event_type, event_hash, schema_version)
                   VALUES ('test', -1, 'neg-seq', 'APPROVED', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '1')"""
            ).use { stmt -> stmt.executeUpdate() }
        }
    }
    }

    @Test
    fun `schema rejects blank stream_id`() { runBlocking {
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        assertFailsWith<org.postgresql.util.PSQLException> {
            conn.prepareStatement(
                """INSERT INTO audit_events
                   (stream_id, sequence_number, event_id, event_type, event_hash, schema_version)
                   VALUES ('', 1, 'blank-stream', 'APPROVED', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '1')"""
            ).use { stmt -> stmt.executeUpdate() }
        }
    }
    }

    @Test
    fun `schema rejects wrong schema version`() { runBlocking {
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        assertFailsWith<org.postgresql.util.PSQLException> {
            conn.prepareStatement(
                """INSERT INTO audit_events
                   (stream_id, sequence_number, event_id, event_type, event_hash, schema_version)
                   VALUES ('test', 1, 'bad-schema', 'APPROVED', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', '0')"""
            ).use { stmt -> stmt.executeUpdate() }
        }
    }
    }

    @Test
    fun `schema rejects blank event_hash`() { runBlocking {
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        assertFailsWith<org.postgresql.util.PSQLException> {
            conn.prepareStatement(
                """INSERT INTO audit_events
                   (stream_id, sequence_number, event_id, event_type, event_hash, schema_version)
                   VALUES ('test', 1, 'blank-hash', 'APPROVED', '', '1')"""
            ).use { stmt -> stmt.executeUpdate() }
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // Dedicated stream isolation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `different streams have independent sequences`() { runBlocking {
        val s = store()
        s.appendNext("alpha", eventFactory("alpha"))
        s.appendNext("alpha", eventFactory("alpha"))
        s.appendNext("beta", eventFactory("beta"))

        val alphaEvents = s.readStream("alpha")
        val betaEvents = s.readStream("beta")

        assertEquals(2, alphaEvents.size)
        assertEquals(1, betaEvents.size)
        assertEquals(1L, alphaEvents[0].sequenceNumber)
        assertEquals(2L, alphaEvents[1].sequenceNumber)
        assertEquals(1L, betaEvents[0].sequenceNumber)
    }
    }

    @Test
    fun `readStreamPage validates chain within page`() { runBlocking {
        val s = store()
        val numEvents = 10
        for (i in 1..numEvents) {
            s.appendNext("stream-1", eventFactory("stream-1"))
        }

        // Read two separate pages and verify chain continuity within each
        val page1 = s.readStreamPage("stream-1", afterSequenceNumber = null, limit = 3)
        assertEquals(3, page1.size)
        assertEquals(1L, page1[0].sequenceNumber)
        assertEquals(3L, page1[2].sequenceNumber)
        assertTrue(checkChain(page1))

        val page2 = s.readStreamPage("stream-1", afterSequenceNumber = 3, limit = 3)
        assertEquals(3, page2.size)
        assertEquals(4L, page2[0].sequenceNumber)
        assertEquals(6L, page2[2].sequenceNumber)
        assertTrue(checkChain(page2))
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // P1 regression — tampered queryable DB columns fail closed
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `tampered event_hash column fails read`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "UPDATE audit_events SET event_hash = 'corrupted' WHERE event_id = 'evt-stream-1-1'",
            ).use { stmt -> stmt.executeUpdate() }
        }

        assertFailsWith<IllegalArgumentException> {
            s.readStream("stream-1")
        }
    }
    }

    @Test
    fun `tampered previous_event_hash column fails read`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))
        s.appendNext("stream-1", eventFactory("stream-1"))

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "UPDATE audit_events SET previous_event_hash = 'corrupted' WHERE event_id = 'evt-stream-1-2'",
            ).use { stmt -> stmt.executeUpdate() }
        }

        assertFailsWith<IllegalArgumentException> {
            s.readStream("stream-1")
        }
    }
    }

    @Test
    fun `tampered event_type column fails read`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "UPDATE audit_events SET event_type = 'TAMPERED' WHERE event_id = 'evt-stream-1-1'",
            ).use { stmt -> stmt.executeUpdate() }
        }

        assertFailsWith<IllegalArgumentException> {
            s.readStream("stream-1")
        }
    }
    }

    @Test
    fun `tampered schema_version column fails read`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            // ck_audit_events_schema_version CHECK (schema_version = '1') would
            // reject the tamper at write time, so drop it, tamper, and restore
            // it afterwards — the read path must still fail closed.
            c.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE audit_events DROP CONSTRAINT ck_audit_events_schema_version")
            }
            try {
                c.prepareStatement(
                    "UPDATE audit_events SET schema_version = '0' WHERE event_id = 'evt-stream-1-1'",
                ).use { stmt -> stmt.executeUpdate() }

                assertFailsWith<IllegalArgumentException> {
                    s.readStream("stream-1")
                }
            } finally {
                c.prepareStatement(
                    "UPDATE audit_events SET schema_version = '1' WHERE event_id = 'evt-stream-1-1'",
                ).use { stmt -> stmt.executeUpdate() }
                c.createStatement().use { stmt ->
                    stmt.execute(
                        "ALTER TABLE audit_events ADD CONSTRAINT ck_audit_events_schema_version CHECK (schema_version = '1')",
                    )
                }
            }
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // P1 regression — sanitized_actor stores hash, not raw actor
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `sanitized_actor stores hash not raw actor`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1", actor = "user:secret-agent"))

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "SELECT sanitized_actor FROM audit_events WHERE event_id = 'evt-stream-1-1'",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val actorValue = rs.getString("sanitized_actor")
                    assertNotNull(actorValue)
                    assertTrue(actorValue.startsWith("sha256:"),
                        "sanitized_actor must be a hash, got: $actorValue")
                    assertTrue(actorValue != "user:secret-agent",
                        "sanitized_actor must not contain raw actor ID")
                    assertEquals(71, actorValue.length,
                        "sha256 hex should be 64 chars + 'sha256:' prefix = 71")
                }
            }
        }
    }
    }

    @Test
    fun `sanitized_actor is null when actor is null`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1", actor = null))

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.prepareStatement(
                "SELECT sanitized_actor FROM audit_events WHERE event_id = 'evt-stream-1-1'",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertNull(rs.getString("sanitized_actor"),
                        "sanitized_actor must be null when actor is null")
                }
            }
        }
    }
    }

    // ═══════════════════════════════════════════════════════════════
    // P1/P2 regression — stream head integrity
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `append fails when stream head points to missing event`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))

        // Delete the event directly (bypassing store)
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM audit_events WHERE event_id = 'evt-stream-1-1'")
            }
        }

        // Append on the corrupted stream must fail before calling eventFactory
        assertFailsWith<IllegalStateException> {
            s.appendNext("stream-1", eventFactory("stream-1"))
        }
    }
    }

    @Test
    fun `append fails when stream head event_id mismatches latest event`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))
        s.appendNext("stream-1", eventFactory("stream-1"))

        // Corrupt the head to point to a wrong event_id
        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    "UPDATE audit_stream_heads SET latest_event_id = 'evt-stream-1-1' WHERE stream_id = 'stream-1'"
                )
            }
        }

        assertFailsWith<IllegalArgumentException> {
            s.appendNext("stream-1", eventFactory("stream-1"))
        }
    }
    }

    @Test
    fun `append fails when stream head event_hash mismatches latest event`() { runBlocking {
        val s = store()
        s.appendNext("stream-1", eventFactory("stream-1"))

        val conn: Connection = DriverManager.getConnection(
            postgres.jdbcUrl, postgres.username, postgres.password,
        )
        conn.use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    "UPDATE audit_stream_heads SET latest_event_hash = 'corrupted' WHERE stream_id = 'stream-1'"
                )
            }
        }

        assertFailsWith<IllegalArgumentException> {
            s.appendNext("stream-1", eventFactory("stream-1"))
        }
    }
    }

    @Test
    fun `appendNext rethrows CancellationException unchanged when rollback also fails`() { runBlocking {
        val cancellation = CancellationException("cancelled by test")
        val rollbackFailure = java.sql.SQLException("rollback failed")
        val failing = JdbcAuditStore(dataSourceWithFailures(rollbackFailure = rollbackFailure), testCodec, fixedClock)

        val thrown = runCatching {
            failing.appendNext("stream-1") { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf(rollbackFailure), thrown?.suppressed?.toList())
    }
    }

    @Test
    fun `appendNext preserves primary CancellationException when autoCommit restore fails`() { runBlocking {
        val cancellation = CancellationException("cancelled by test")
        val restoreFailure = java.sql.SQLException("restore failed")
        val failing = JdbcAuditStore(dataSourceWithFailures(restoreFailure = restoreFailure), testCodec, fixedClock)

        val thrown = runCatching {
            failing.appendNext("stream-1") { throw cancellation }
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf(restoreFailure), thrown?.suppressed?.toList())
    }
    }

    /**
     * A DataSource whose connections delegate to the real database but fail
     * deterministically: [rollbackFailure] is thrown from `rollback()`, and,
     * when [restoreFailure] is non-null, from the second `setAutoCommit` call
     * (the finally-block restoration). Each `getConnection` captures ONE
     * delegate, so the production code sees a single JDBC transaction rather
     * than a fresh connection per method call. Same shape as the #267
     * ApprovalStore failure-injection helper.
     */
    private fun dataSourceWithFailures(
        rollbackFailure: Exception? = null,
        restoreFailure: Exception? = null,
    ): DataSource {
        val real = createDataSource()
        return Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { _, method, args ->
            if (method.name == "getConnection" && args.isNullOrEmpty()) {
                val delegate = real.connection
                var autoCommitCalls = 0
                Proxy.newProxyInstance(
                    Connection::class.java.classLoader,
                    arrayOf(Connection::class.java),
                ) { _, connMethod, connArgs ->
                    if (connMethod.name == "rollback" && rollbackFailure != null) throw rollbackFailure
                    if (connMethod.name == "setAutoCommit") {
                        autoCommitCalls++
                        if (restoreFailure != null && autoCommitCalls > 1) throw restoreFailure
                    }
                    connMethod.invoke(delegate, *(connArgs ?: emptyArray()))
                }
            } else {
                method.invoke(real, *(args ?: emptyArray()))
            }
        } as DataSource
    }
}
