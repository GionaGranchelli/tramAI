package dev.tramai.persistence.file

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalStoreConflictException
import dev.tramai.core.exception.ApprovalStoreNotConsumableException
import dev.tramai.core.exception.ApprovalStoreTokenRejectedException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileApprovalStoreTest {

    private val rootDir: Path = Files.createTempDirectory("tramai-approval-test-").toAbsolutePath()
    private val now: Instant = Instant.parse("2025-06-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneId.of("UTC"))

    private fun testKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val testKey = testKey()
    private val keyProvider = FileStoreEncryptionKeyProvider { testKey }

    private fun createConfig() = FileBackedStoreConfiguration(
        rootDirectory = rootDir,
        encryption = FileStoreEncryptionConfiguration(
            activeKeyId = "test-key",
            keyProvider = keyProvider,
        ),
        verifyOnOpen = false,
    )

    private fun makeDigest(hex: String = "0000000000000000000000000000000000000000000000000000000000000001"): Sha256Digest =
        Sha256Digest.of("sha256:$hex")

    @BeforeEach
    fun setup() {
        // FileApprovalStore requires the approvals directory to exist
        Files.createDirectories(rootDir.resolve("approvals"))
    }

    @AfterEach
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `create and reopen`() = runBlocking {
        val store1 = FileApprovalStore(rootDir, testKey, createConfig(), clock)

        val request = ApprovalRequest(
            approvalId = "test-create-1",
            binding = ApprovalBinding(
                workflowRunId = "wf-1",
                toolName = "reader",
                argumentsDigest = makeDigest("a".repeat(64)),
                policyVersion = "v1",
                workflowDigest = makeDigest("b".repeat(64)),
                approvalTokenDigest = makeDigest("c".repeat(64)),
            ),
            status = ApprovalStatus.PENDING,
            requestedBy = "alice",
            requestedAt = now,
            expiresAt = now.plusSeconds(300),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        store1.create(request)

        // Reopen with a fresh instance
        val store2 = FileApprovalStore(rootDir, testKey, createConfig(), clock)
        val retrieved = store2.get("test-create-1")
        assertNotNull(retrieved)
        assertEquals(request.approvalId, retrieved.approvalId)
        assertEquals(ApprovalStatus.PENDING, retrieved.status)
        assertEquals(0L, retrieved.version)
        assertEquals("alice", retrieved.requestedBy)
    }

    @Test
    fun `transition and reopen`() = runBlocking {
        val store = FileApprovalStore(rootDir, testKey, createConfig(), clock)

        val request = ApprovalRequest(
            approvalId = "test-transition-1",
            binding = ApprovalBinding(
                workflowRunId = "wf-2",
                toolName = "writer",
                argumentsDigest = makeDigest("d".repeat(64)),
                policyVersion = "v1",
                workflowDigest = makeDigest("e".repeat(64)),
                approvalTokenDigest = makeDigest("f".repeat(64)),
            ),
            status = ApprovalStatus.PENDING,
            requestedBy = "bob",
            requestedAt = now,
            expiresAt = now.plusSeconds(300),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        store.create(request)

        val transitioned = store.transition(
            approvalId = "test-transition-1",
            expectedVersion = 0L,
            transition = ApprovalTransition.Approve(decidedBy = "carol", comment = "approved"),
        )
        assertEquals(ApprovalStatus.APPROVED, transitioned.status)
        assertEquals(1L, transitioned.version)
        assertEquals("carol", transitioned.decidedBy)

        // Reopen and verify
        val store2 = FileApprovalStore(rootDir, testKey, createConfig(), clock)
        val retrieved = store2.get("test-transition-1")
        assertNotNull(retrieved)
        assertEquals(ApprovalStatus.APPROVED, retrieved.status)
        assertEquals(1L, retrieved.version)
        assertEquals("carol", retrieved.decidedBy)
    }

    @Test
    fun `duplicate create rejected`() = runBlocking {
        val store = FileApprovalStore(rootDir, testKey, createConfig(), clock)

        val request = ApprovalRequest(
            approvalId = "test-dup-1",
            binding = ApprovalBinding(
                workflowRunId = "wf-3",
                toolName = "deleter",
                argumentsDigest = makeDigest("g".repeat(64)),
                policyVersion = "v1",
                workflowDigest = makeDigest("h".repeat(64)),
                approvalTokenDigest = makeDigest("i".repeat(64)),
            ),
            status = ApprovalStatus.PENDING,
            requestedBy = "dave",
            requestedAt = now,
            expiresAt = now.plusSeconds(300),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        store.create(request)
        assertThrows<ApprovalStoreConflictException> {
            store.create(request)
        }
    }

    @Test
    fun `stale expected version rejected`() = runBlocking {
        val store = FileApprovalStore(rootDir, testKey, createConfig(), clock)

        val request = ApprovalRequest(
            approvalId = "test-stale-1",
            binding = ApprovalBinding(
                workflowRunId = "wf-4",
                toolName = "checker",
                argumentsDigest = makeDigest("j".repeat(64)),
                policyVersion = "v1",
                workflowDigest = makeDigest("k".repeat(64)),
                approvalTokenDigest = makeDigest("l".repeat(64)),
            ),
            status = ApprovalStatus.PENDING,
            requestedBy = "eve",
            requestedAt = now,
            expiresAt = now.plusSeconds(300),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        store.create(request)
        store.transition(
            approvalId = "test-stale-1",
            expectedVersion = 0L,
            transition = ApprovalTransition.Approve(decidedBy = "frank"),
        )

        // Version is now 1, try with expectedVersion = 0
        assertThrows<ApprovalStoreConflictException> {
            store.transition(
                approvalId = "test-stale-1",
                expectedVersion = 0L,
                transition = ApprovalTransition.Deny(decidedBy = "grace"),
            )
        }
    }

    @Test
    fun `wrong token digest rejected`() = runBlocking {
        val store = FileApprovalStore(rootDir, testKey, createConfig(), clock)

        val request = ApprovalRequest(
            approvalId = "test-token-1",
            binding = ApprovalBinding(
                workflowRunId = "wf-5",
                toolName = "consumer",
                argumentsDigest = makeDigest("m".repeat(64)),
                policyVersion = "v1",
                workflowDigest = makeDigest("n".repeat(64)),
                approvalTokenDigest = makeDigest("o".repeat(64)),
            ),
            status = ApprovalStatus.PENDING,
            requestedBy = "hank",
            requestedAt = now,
            expiresAt = now.plusSeconds(300),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        store.create(request)
        store.transition(
            approvalId = "test-token-1",
            expectedVersion = 0L,
            transition = ApprovalTransition.Approve(decidedBy = "ivy"),
        )

        val wrongDigest = makeDigest("z".repeat(64))
        assertThrows<ApprovalStoreTokenRejectedException> {
            store.consumeApprovedOrReplay(
                approvalId = "test-token-1",
                expectedVersion = 1L,
                presentedTokenDigest = wrongDigest,
                consumedBy = "jack",
            )
        }
    }

    @Test
    fun `exact replay after reopen succeeds without mutation`() = runBlocking {
        val store = FileApprovalStore(rootDir, testKey, createConfig(), clock)

        val tokenDigest = makeDigest("f".repeat(64))
        val request = ApprovalRequest(
            approvalId = "test-replay-1",
            binding = ApprovalBinding(
                workflowRunId = "wf-6",
                toolName = "replayer",
                argumentsDigest = makeDigest("f".repeat(64)),
                policyVersion = "v1",
                workflowDigest = makeDigest("f".repeat(64)),
                approvalTokenDigest = tokenDigest,
            ),
            status = ApprovalStatus.PENDING,
            requestedBy = "kim",
            requestedAt = now,
            expiresAt = now.plusSeconds(300),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        store.create(request)
        store.transition(
            approvalId = "test-replay-1",
            expectedVersion = 0L,
            transition = ApprovalTransition.Approve(decidedBy = "leo"),
        )

        // First consumption
        val receipt1 = store.consumeApprovedOrReplay(
            approvalId = "test-replay-1",
            expectedVersion = 1L,
            presentedTokenDigest = tokenDigest,
            consumedBy = "mia",
        )
        assertEquals(false, receipt1.replayed)

        val store2 = FileApprovalStore(rootDir, testKey, createConfig(), clock)

        // Exact replay should succeed
        val receipt2 = store2.consumeApprovedOrReplay(
            approvalId = "test-replay-1",
            expectedVersion = 1L, // original expectedVersion before increment
            presentedTokenDigest = tokenDigest,
            consumedBy = "mia",
        )
        assertEquals(true, receipt2.replayed)
        assertEquals(receipt1.request.consumedAt, receipt2.request.consumedAt)
        assertEquals(receipt1.request.version, receipt2.request.version)
    }

    @Test
    fun `non-exact replay after reopen rejected`() = runBlocking {
        val store = FileApprovalStore(rootDir, testKey, createConfig(), clock)

        val tokenDigest = makeDigest("s".repeat(64))
        val request = ApprovalRequest(
            approvalId = "test-bad-replay-1",
            binding = ApprovalBinding(
                workflowRunId = "wf-7",
                toolName = "bad-replay",
                argumentsDigest = makeDigest("t".repeat(64)),
                policyVersion = "v1",
                workflowDigest = makeDigest("u".repeat(64)),
                approvalTokenDigest = tokenDigest,
            ),
            status = ApprovalStatus.PENDING,
            requestedBy = "nina",
            requestedAt = now,
            expiresAt = now.plusSeconds(300),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        store.create(request)
        store.transition(
            approvalId = "test-bad-replay-1",
            expectedVersion = 0L,
            transition = ApprovalTransition.Approve(decidedBy = "oscar"),
        )

        store.consumeApprovedOrReplay(
            approvalId = "test-bad-replay-1",
            expectedVersion = 1L,
            presentedTokenDigest = tokenDigest,
            consumedBy = "pat",
        )

        val store2 = FileApprovalStore(rootDir, testKey, createConfig(), clock)

        // Try replaying with a different consumer - should be rejected
        assertThrows<ApprovalStoreNotConsumableException> {
            store2.consumeApprovedOrReplay(
                approvalId = "test-bad-replay-1",
                expectedVersion = 1L,
                presentedTokenDigest = tokenDigest,
                consumedBy = "quinn",
            )
        }
    }

    @Test
    fun `concurrent mutation has exactly one winner`() = runBlocking {
        val store = FileApprovalStore(rootDir, testKey, createConfig(), clock)

        val request = ApprovalRequest(
            approvalId = "test-concurrent-1",
            binding = ApprovalBinding(
                workflowRunId = "wf-8",
                toolName = "concurrent",
                argumentsDigest = makeDigest("f".repeat(64)),
                policyVersion = "v1",
                workflowDigest = makeDigest("f".repeat(64)),
                approvalTokenDigest = makeDigest("f".repeat(64)),
            ),
            status = ApprovalStatus.PENDING,
            requestedBy = "ray",
            requestedAt = now,
            expiresAt = now.plusSeconds(300),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )

        store.create(request)

        // Two concurrent transition attempts, both targeting version 0
        coroutineScope {
            val results = listOf(
                async {
                    try {
                        store.transition(
                            approvalId = "test-concurrent-1",
                            expectedVersion = 0L,
                            transition = ApprovalTransition.Approve(decidedBy = "sam", comment = "first"),
                        )
                        "approve"
                    } catch (e: ApprovalStoreConflictException) {
                        "conflict"
                    }
                },
                async {
                    try {
                        store.transition(
                            approvalId = "test-concurrent-1",
                            expectedVersion = 0L,
                            transition = ApprovalTransition.Deny(decidedBy = "tina", comment = "second"),
                        )
                        "deny"
                    } catch (e: ApprovalStoreConflictException) {
                        "conflict"
                    }
                },
            )

            val outcomes = results.map { it.await() }
            val winners = outcomes.filter { it != "conflict" }
            assertEquals(1, winners.size, "Exactly one transition must succeed")
        }

        // Verify the final state
        val finalRequest = store.get("test-concurrent-1")
        assertNotNull(finalRequest)
        assertEquals(1L, finalRequest.version)
        assertTrue(
            finalRequest.status == ApprovalStatus.APPROVED || finalRequest.status == ApprovalStatus.DENIED,
            "Status must be either APPROVED or DENIED, got ${finalRequest.status}",
        )
    }
}
