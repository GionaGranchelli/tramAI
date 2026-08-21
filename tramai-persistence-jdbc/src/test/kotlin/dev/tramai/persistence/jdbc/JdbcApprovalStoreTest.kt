package dev.tramai.persistence.jdbc

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalStoreConflictException
import dev.tramai.core.exception.ApprovalStoreNotConsumableException
import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.exception.ApprovalStoreTokenRejectedException
import dev.tramai.core.exception.IllegalApprovalTransitionException
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
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcApprovalStoreTest {

    companion object {
        private const val POSTGRES_IMAGE = "postgres:17-alpine"

        private val postgres = PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("approval_test")
            .withUsername("test")
            .withPassword("test")

        private fun createDataSource(): DataSource = PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection
    private val fixedClock: Clock = Clock.fixed(
        Instant.parse("2026-06-21T12:00:00Z"),
        ZoneId.of("UTC"),
    )

    @BeforeAll
    fun setUpAll() {
        postgres.start()
        setupConnection = DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        )
        val schemaSql = this::class.java.classLoader
            .getResource("tramai/persistence/jdbc/postgres/V1__sovereign_persistence.sql")
            ?.readText()
            ?: throw IllegalStateException("Schema SQL resource not found")
        setupConnection.createStatement().use { stmt ->
            stmt.execute(schemaSql)
        }
        dataSource = createDataSource()
    }

    @AfterAll
    fun tearDownAll() {
        setupConnection.close()
        postgres.stop()
    }

    @BeforeEach
    fun cleanUp() {
        setupConnection.createStatement().use { stmt ->
            stmt.execute("DELETE FROM approvals")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun store(clock: Clock = fixedClock): JdbcApprovalStore =
        JdbcApprovalStore(dataSource, clock)

    private fun approvalRequest(
        id: String = "test-approval-1",
        requestedBy: String = "user:alice",
        toolName: String = "deploy-service",
    ): ApprovalRequest {
        val now = fixedClock.instant()
        return ApprovalRequest(
            approvalId = id,
            binding = ApprovalBinding(
                workflowRunId = "wf-run-001",
                toolName = toolName,
                argumentsDigest = Sha256Digest.of("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                policyVersion = "v1",
                workflowDigest = Sha256Digest.of("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                approvalTokenDigest = Sha256Digest.of("sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            ),
            status = ApprovalStatus.PENDING,
            requestedBy = requestedBy,
            requestedAt = now,
            expiresAt = now.plus(Duration.ofMinutes(5)),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0,
        )
    }

    // ── Create / Get ────────────────────────────────────────────

    @Test
    fun `saves and loads a pending approval`() { runBlocking {
        val s = store()
        val request = approvalRequest()

        s.create(request)
        val loaded = s.get("test-approval-1")

        assertNotNull(loaded)
        assertEquals("test-approval-1", loaded.approvalId)
        assertEquals(ApprovalStatus.PENDING, loaded.status)
        assertEquals("user:alice", loaded.requestedBy)
        assertEquals("deploy-service", loaded.binding.toolName)
        assertEquals(0, loaded.version)
    }
    }

    @Test
    fun `persisted approval survives new store instance`() { runBlocking {
        val s1 = store()
        s1.create(approvalRequest(id = "survive-test"))

        val s2 = store()
        val loaded = s2.get("survive-test")

        assertNotNull(loaded)
        assertEquals(ApprovalStatus.PENDING, loaded.status)
        assertEquals("user:alice", loaded.requestedBy)
    }
    }

    @Test
    fun `get returns null for non-existent approval`() { runBlocking {
        val loaded = store().get("non-existent")
        assertNull(loaded)
    }
    }

    @Test
    fun `duplicate approval ID throws ApprovalStoreConflictException`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "dup-test"))

        assertFailsWith<ApprovalStoreConflictException> {
            s.create(approvalRequest(id = "dup-test"))
        }
    }
    }

    // ── Transitions ─────────────────────────────────────────────

    @Test
    fun `records approval decision`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "approve-test"))

        val updated = s.transition("approve-test", 0, ApprovalTransition.Approve("user:bob"))

        assertEquals(ApprovalStatus.APPROVED, updated.status)
        assertEquals("user:bob", updated.decidedBy)
        assertNotNull(updated.decidedAt)
        assertEquals(1, updated.version)
    }
    }

    @Test
    fun `records denial decision`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "deny-test"))

        val updated = s.transition("deny-test", 0, ApprovalTransition.Deny("user:bob", "not approved"))

        assertEquals(ApprovalStatus.DENIED, updated.status)
        assertEquals("user:bob", updated.decidedBy)
        assertEquals("not approved", updated.decisionComment)
        assertEquals(1, updated.version)
    }
    }

    @Test
    fun `records timeout decision`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "timeout-test"))

        val expiredClock = Clock.fixed(
            Instant.parse("2026-06-21T12:10:00Z"),
            ZoneId.of("UTC"),
        )
        val expiredStore = store(expiredClock)
        val updated = expiredStore.transition("timeout-test", 0, ApprovalTransition.Timeout)

        assertEquals(ApprovalStatus.TIMED_OUT, updated.status)
        assertNull(updated.decidedBy)
        assertEquals(1, updated.version)
    }
    }

    @Test
    fun `transition with wrong version throws ApprovalStoreConflictException`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "version-test"))
        s.transition("version-test", 0, ApprovalTransition.Approve("user:bob"))

        assertFailsWith<ApprovalStoreConflictException> {
            s.transition("version-test", 0, ApprovalTransition.Deny("user:mallory"))
        }
    }
    }

    @Test
    fun `transition on non-existent approval throws ApprovalStoreNotFoundException`() { runBlocking {
        assertFailsWith<ApprovalStoreNotFoundException> {
            store().transition("non-existent", 0, ApprovalTransition.Approve("user:bob"))
        }
    }
    }

    @Test
    fun `illegal transition from terminal status throws IllegalApprovalTransitionException`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "terminal-test"))
        s.transition("terminal-test", 0, ApprovalTransition.Approve("user:bob"))

        assertFailsWith<IllegalApprovalTransitionException> {
            s.transition("terminal-test", 1, ApprovalTransition.Deny("user:bob"))
        }
    }
    }

    @Test
    fun `decision update increments version`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "version-inc-test"))

        val v1 = s.transition("version-inc-test", 0, ApprovalTransition.Approve("user:bob"))
        assertEquals(1, v1.version)

        val loaded = s.get("version-inc-test")
        assertEquals(1, loaded!!.version)
    }
    }

    @Test
    fun `competing decisions cannot both win`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "compete-test"))

        s.transition("compete-test", 0, ApprovalTransition.Approve("user:bob"))

        assertFailsWith<ApprovalStoreConflictException> {
            s.transition("compete-test", 0, ApprovalTransition.Deny("user:mallory"))
        }
    }
    }

    // ── Consume / Replay ────────────────────────────────────────

    @Test
    fun `consumes an approved request`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "consume-test"))
        s.transition("consume-test", 0, ApprovalTransition.Approve("user:bob"))

        val receipt = s.consumeApprovedOrReplay(
            "consume-test", 1,
            Sha256Digest.of("sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            "worker:executor",
        )

        assertEquals("worker:executor", receipt.request.consumedBy)
        assertNotNull(receipt.request.consumedAt)
        assertEquals(2, receipt.request.version)
        assertEquals(false, receipt.replayed)
    }
    }

    @Test
    fun `exact replay returns replayed receipt`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "replay-test"))
        s.transition("replay-test", 0, ApprovalTransition.Approve("user:bob"))

        s.consumeApprovedOrReplay(
            "replay-test", 1,
            Sha256Digest.of("sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            "worker:executor",
        )

        val receipt = s.consumeApprovedOrReplay(
            "replay-test", 1,
            Sha256Digest.of("sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            "worker:executor",
        )

        assertEquals(true, receipt.replayed)
        assertEquals(2, receipt.request.version)
    }
    }

    @Test
    fun `consuming unapproved request throws ApprovalStoreNotConsumableException`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "unapproved-consume"))

        assertFailsWith<ApprovalStoreNotConsumableException> {
            s.consumeApprovedOrReplay(
                "unapproved-consume", 0,
                Sha256Digest.of("sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
                "worker:executor",
            )
        }
    }
    }

    @Test
    fun `consuming with wrong token throws ApprovalStoreTokenRejectedException`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "wrong-token"))
        s.transition("wrong-token", 0, ApprovalTransition.Approve("user:bob"))

        assertFailsWith<ApprovalStoreTokenRejectedException> {
            s.consumeApprovedOrReplay(
                "wrong-token", 1,
                Sha256Digest.of("sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"),
                "worker:executor",
            )
        }
    }
    }

    // ── Sanitized persistence ───────────────────────────────────

    @Test
    fun `sanitized metadata round-trips as JSONB`() { runBlocking {
        val s = store()
        val request = approvalRequest(id = "jsonb-test")
        s.create(request)

        val loaded = s.get("jsonb-test")
        assertNotNull(loaded)
        assertEquals(request.binding.workflowRunId, loaded.binding.workflowRunId)
        assertEquals(request.binding.toolName, loaded.binding.toolName)
        assertEquals(request.binding.argumentsDigest, loaded.binding.argumentsDigest)
        assertEquals(request.binding.policyVersion, loaded.binding.policyVersion)
        assertEquals(request.binding.workflowDigest, loaded.binding.workflowDigest)
        assertEquals(request.binding.approvalTokenDigest, loaded.binding.approvalTokenDigest)
    }
    }

    @Test
    fun `encrypted_payload remains null for sanitized-only approval records`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "enc-null-test"))

        setupConnection.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                "SELECT encrypted_payload, encryption_key_id, encryption_algorithm, encryption_nonce, payload_digest FROM approvals WHERE approval_id = 'enc-null-test'"
            )
            assertTrue(rs.next())
            assertNull(rs.getObject("encrypted_payload"))
            assertNull(rs.getObject("encryption_key_id"))
            assertNull(rs.getObject("encryption_algorithm"))
            assertNull(rs.getObject("encryption_nonce"))
            assertNull(rs.getObject("payload_digest"))
        }
    }
    }

    @Test
    fun `no raw prompt or model payload is persisted`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "sanitized-payload-test"))
        s.transition("sanitized-payload-test", 0, ApprovalTransition.Approve("user:bob"))

        val loaded = s.get("sanitized-payload-test")
        assertNotNull(loaded)

        assertTrue(loaded.binding.argumentsDigest.value.startsWith("sha256:"))
        assertTrue(loaded.binding.workflowDigest.value.startsWith("sha256:"))
        assertTrue(loaded.binding.approvalTokenDigest.value.startsWith("sha256:"))

        setupConnection.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                "SELECT sanitized_metadata::text FROM approvals WHERE approval_id = 'sanitized-payload-test'"
            )
            assertTrue(rs.next())
            val metadata = rs.getString("sanitized_metadata")
            assertNotNull(metadata)
            assertTrue("argumentsDigest" in metadata)
        }
    }
    }

    @Test
    fun `decision_actor_hash stores hash not raw identity`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "actor-hash-test"))
        s.transition("actor-hash-test", 0, ApprovalTransition.Approve("user:bob"))

        setupConnection.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                "SELECT decision_actor_hash FROM approvals WHERE approval_id = 'actor-hash-test'"
            )
            assertTrue(rs.next())
            val hash = rs.getString("decision_actor_hash")
            assertNotNull(hash)
            assertTrue(hash.startsWith("sha256:"))
            assertEquals(71, hash.length)
        }
    }
    }

    // ── RequestedAt round-trip ────────────────────────────────────

    @Test
    fun `requestedAt round-trips when earlier than store clock`() { runBlocking {
        val now = fixedClock.instant()
        val request = approvalRequest(id = "requested-at-roundtrip").copy(
            requestedAt = now.minusSeconds(30),
            expiresAt = now.plus(Duration.ofMinutes(5)),
        )

        store().create(request)

        val loaded = store().get("requested-at-roundtrip")!!

        assertEquals(request.requestedAt, loaded.requestedAt)
        assertEquals(request.expiresAt, loaded.expiresAt)
    }
    }

    // ── Concurrent consumption ────────────────────────────────────

    @Test
    fun `concurrent consumption with same version results in exactly one fresh consume`() { runBlocking {
        val s = store()
        s.create(approvalRequest(id = "concurrent-consume"))
        s.transition("concurrent-consume", 0, ApprovalTransition.Approve("user:bob"))

        val token = Sha256Digest.of("sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")

        val results = coroutineScope {
            val d1 = async {
                try {
                    val r = s.consumeApprovedOrReplay("concurrent-consume", 1, token, "worker:alpha")
                    "fresh:${r.replayed}"
                } catch (e: Exception) {
                    "error:${e::class.simpleName}"
                }
            }
            val d2 = async {
                try {
                    val r = s.consumeApprovedOrReplay("concurrent-consume", 1, token, "worker:alpha")
                    "fresh:${r.replayed}"
                } catch (e: Exception) {
                    "error:${e::class.simpleName}"
                }
            }
            listOf(d1.await(), d2.await())
        }

        assertEquals(2, results.size)
        val freshCount = results.count { it == "fresh:false" }
        assertEquals(1, freshCount, "Exactly one concurrent consumer should get fresh consumption")
    }
    }

    // ── Clock ───────────────────────────────────────────────────

    @Test
    fun `created_at uses store clock`() { runBlocking {
        val customClock = Clock.fixed(
            Instant.parse("2026-01-15T08:30:00Z"),
            ZoneId.of("UTC"),
        )
        val s = store(customClock)
        val request = approvalRequest(id = "clock-test").copy(
            requestedAt = customClock.instant(),
            expiresAt = customClock.instant().plus(Duration.ofMinutes(5)),
        )
        s.create(request)

        val loaded = s.get("clock-test")
        assertNotNull(loaded)
        assertEquals(Instant.parse("2026-01-15T08:30:00Z"), loaded.requestedAt)
    }
    }
}
