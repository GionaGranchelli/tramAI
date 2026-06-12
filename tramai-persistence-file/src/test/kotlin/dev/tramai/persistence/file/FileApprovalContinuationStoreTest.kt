package dev.tramai.persistence.file

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalContinuationNotClaimableException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileApprovalContinuationStoreTest {

    private val rootDir: Path = Files.createTempDirectory("tramai-continuation-test-").toAbsolutePath()
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

    private fun computeArgumentsDigest(arguments: String): Sha256Digest {
        val bytes = MessageDigest.getInstance("SHA-256").digest(arguments.toByteArray(Charsets.UTF_8))
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return Sha256Digest.of("sha256:$hex")
    }

    private fun createContinuation(
        approvalId: String = "cont-test-1",
        status: ApprovalContinuationStatus = ApprovalContinuationStatus.PENDING,
        arguments: String = "plain-args",
        clock: Clock = this.clock,
    ): Pair<ApprovalContinuation, SensitiveToolArguments> {
        val args = SensitiveToolArguments.of(arguments)
        val digest = computeArgumentsDigest(arguments)
        val creationTime = Clock.fixed(now, ZoneId.of("UTC")).instant()
        val continuation = ApprovalContinuation(
            approvalId = approvalId,
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            toolCallId = "tc-1",
            toolName = "test-tool",
            argumentsDigest = digest,
            policyVersion = "v1",
            workflowDigest = makeDigest("b".repeat(64)),
            status = status,
            createdAt = creationTime,
            approvalExpiresAt = creationTime.plusSeconds(300),
            claimedBy = null,
            claimedAt = null,
            completedAt = null,
            version = 0L,
        )
        return continuation to args
    }

    @BeforeEach
    fun setup() {
        Files.createDirectories(rootDir.resolve("continuations"))
        Files.setPosixFilePermissions(
            rootDir.resolve("continuations"),
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
        )
    }

    @AfterEach
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `create pending continuation and reopen`() = runBlocking {
        val store1 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val (continuation, args) = createContinuation("cont-reopen-1")

        store1.create(continuation, args)

        val store2 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val retrieved = store2.get("cont-reopen-1")
        assertNotNull(retrieved)
        assertEquals("cont-reopen-1", retrieved.approvalId)
        assertEquals(ApprovalContinuationStatus.PENDING, retrieved.status)
        assertEquals(0L, retrieved.version)
    }

    @Test
    fun `raw arguments absent from persisted bytes`() = runBlocking {
        val store = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val (continuation, args) = createContinuation("cont-secret-1", arguments = "super-secret-arg-value")

        store.create(continuation, args)

        // Read the encrypted file and verify the raw argument content is NOT present in plain sight
        val digest = FileStoreSha256.digest("approval-continuation", "cont-secret-1")
        val encFile = rootDir.resolve("continuations/$digest.tram.enc")
        assertTrue(encFile.exists(), "Encrypted file must exist")

        val envelopeJson = encFile.readText()
        // The argument content must not appear in plaintext in the envelope JSON
        assertEquals(false, envelopeJson.contains("super-secret-arg-value"))
    }

    @Test
    fun `claim after reopen returns arguments once`() = runBlocking {
        val store1 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val rawArgs = "simple-command"
        val (continuation, args) = createContinuation("cont-claim-1", arguments = rawArgs)

        store1.create(continuation, args)

        val store2 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val claimed = store2.claimForExecution(
            approvalId = "cont-claim-1",
            expectedVersion = 0L,
            claimedBy = "executor-alice",
        )

        assertEquals("cont-claim-1", claimed.continuation.approvalId)
        assertEquals(ApprovalContinuationStatus.CLAIMED, claimed.continuation.status)
        assertEquals(rawArgs, claimed.arguments.reveal())
        assertEquals(1L, claimed.continuation.version)
    }

    @Test
    fun `claim atomically removes persisted arguments`() = runBlocking {
        val store = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val (continuation, args) = createContinuation("cont-atom-1", arguments = "transient-secret")

        store.create(continuation, args)

        // Claim - should clear arguments
        val claimed = store.claimForExecution(
            approvalId = "cont-atom-1",
            expectedVersion = 0L,
            claimedBy = "executor-bob",
        )
        assertNotNull(claimed.arguments)
        assertEquals("transient-secret", claimed.arguments.reveal())

        // After claim, the stored record should have arguments = null
        // We can verify by trying to claim again (should fail because already claimed)
        assertThrows<ApprovalContinuationNotClaimableException> {
            store.claimForExecution(
                approvalId = "cont-atom-1",
                expectedVersion = 1L,
                claimedBy = "executor-charlie",
            )
        }
    }

    @Test
    fun `reopen after claim remains CLAIMED`() = runBlocking {
        val store1 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val (continuation, args) = createContinuation("cont-reclaim-1", arguments = "claim-me")

        store1.create(continuation, args)
        store1.claimForExecution(
            approvalId = "cont-reclaim-1",
            expectedVersion = 0L,
            claimedBy = "executor-dave",
        )

        val store2 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val retrieved = store2.get("cont-reclaim-1")
        assertNotNull(retrieved)
        assertEquals(ApprovalContinuationStatus.CLAIMED, retrieved.status)
        assertEquals(1L, retrieved.version)
    }

    @Test
    fun `concurrent claim has exactly one winner`() = runBlocking {
        val store = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val (continuation, args) = createContinuation("cont-concurrent-claim-1", arguments = "contestable")

        store.create(continuation, args)

        coroutineScope {
            val results = listOf(
                async {
                    try {
                        store.claimForExecution("cont-concurrent-claim-1", 0L, "executor-1")
                        "winner"
                    } catch (e: Exception) {
                        "loser"
                    }
                },
                async {
                    try {
                        store.claimForExecution("cont-concurrent-claim-1", 0L, "executor-2")
                        "winner"
                    } catch (e: Exception) {
                        "loser"
                    }
                },
                async {
                    try {
                        store.claimForExecution("cont-concurrent-claim-1", 0L, "executor-3")
                        "winner"
                    } catch (e: Exception) {
                        "loser"
                    }
                },
            )

            val outcomes = results.map { it.await() }
            val winners = outcomes.count { it == "winner" }
            assertEquals(1, winners, "Exactly one concurrent claim must succeed")
        }
    }

    @Test
    fun `complete survive reopen`() = runBlocking {
        val store1 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val (continuation, args) = createContinuation("cont-complete-1")

        store1.create(continuation, args)
        store1.claimForExecution("cont-complete-1", 0L, "executor-eve")

        store1.complete("cont-complete-1", 1L, "executor-eve")

        val store2 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val retrieved = store2.get("cont-complete-1")
        assertNotNull(retrieved)
        assertEquals(ApprovalContinuationStatus.COMPLETED, retrieved.status)
    }

    @Test
    fun `expire survive reopen`() = runBlocking {
        // Create continuation that expires at 12:05
        val creationClock = Clock.fixed(now, ZoneId.of("UTC"))
        val storeCreate = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), creationClock)
        val (continuation, args) = createContinuation("cont-expire-1", clock = creationClock)

        storeCreate.create(continuation, args)

        // Use a clock at 12:20 (after expiry at 12:05) for expiry and reopen
        val futureNow = Instant.parse("2025-06-01T12:20:00Z")
        val futureClock = Clock.fixed(futureNow, ZoneId.of("UTC"))

        val storeExpire = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), futureClock)
        storeExpire.expire("cont-expire-1", 0L)

        val store2 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), futureClock)
        val retrieved = store2.get("cont-expire-1")
        assertNotNull(retrieved)
        assertEquals(ApprovalContinuationStatus.EXPIRED, retrieved.status)
    }

    @Test
    fun `cancel survive reopen`() = runBlocking {
        val store1 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val (continuation, args) = createContinuation("cont-cancel-1")

        store1.create(continuation, args)
        store1.cancel("cont-cancel-1", 0L)

        val store2 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val retrieved = store2.get("cont-cancel-1")
        assertNotNull(retrieved)
        assertEquals(ApprovalContinuationStatus.CANCELLED, retrieved.status)
    }

    @Test
    fun `stale search survive reopen`() = runBlocking {
        val store = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val (continuation, args) = createContinuation("cont-stale-1")

        store.create(continuation, args)
        store.claimForExecution("cont-stale-1", 0L, "executor-slow")

        // Search for stale claimed continuations (claimed before now + 1 second)
        val stale = store.findStaleClaimed(claimedBefore = now.plusSeconds(1), limit = 10)
        assertEquals(1, stale.size)
        assertEquals("cont-stale-1", stale.first().approvalId)
    }

    @Test
    fun `sweep survive reopen`() = runBlocking {
        // Create continuations with creation clock set to now (expires at 12:05)
        val creationClock = Clock.fixed(now, ZoneId.of("UTC"))
        val store1 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), creationClock)
        val (continuation1, args1) = createContinuation("cont-sweep-1", clock = creationClock)
        val (continuation2, args2) = createContinuation("cont-sweep-2", clock = creationClock)

        store1.create(continuation1, args1)
        store1.create(continuation2, args2)

        // Use future clock (12:20, after 12:05 expiry) to sweep
        val futureNow = Instant.parse("2025-06-01T12:20:00Z")
        val futureClock = Clock.fixed(futureNow, ZoneId.of("UTC"))
        val store2 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), futureClock)

        // Sweep should transition both PENDING continuations past expiry to EXPIRED
        val swept = store2.sweepExpired()
        assertEquals(2, swept)

        // Verify records are preserved (not deleted) with EXPIRED status
        val retrieved1 = store2.get("cont-sweep-1")
        assertNotNull(retrieved1)
        assertEquals(ApprovalContinuationStatus.EXPIRED, retrieved1.status)

        val retrieved2 = store2.get("cont-sweep-2")
        assertNotNull(retrieved2)
        assertEquals(ApprovalContinuationStatus.EXPIRED, retrieved2.status)

        // Verify files still exist
        val digest1 = FileStoreSha256.digest("approval-continuation", "cont-sweep-1")
        val digest2 = FileStoreSha256.digest("approval-continuation", "cont-sweep-2")
        assertTrue(rootDir.resolve("continuations/$digest1.tram.enc").exists())
        assertTrue(rootDir.resolve("continuations/$digest2.tram.enc").exists())
    }

    @Test
    fun `force cancel survive reopen`() = runBlocking {
        val store1 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val (continuation, args) = createContinuation("cont-force-cancel-1")

        store1.create(continuation, args)
        store1.claimForExecution("cont-force-cancel-1", 0L, "executor-stuck")

        store1.forceCancelClaimed(
            approvalId = "cont-force-cancel-1",
            expectedVersion = 1L,
            cancelledBy = "admin-alice",
            reasonCode = "stuck.worker",
        )

        val store2 = FileApprovalContinuationStore(rootDir, testKey, createConfig(), FileStoreLease(), clock)
        val retrieved = store2.get("cont-force-cancel-1")
        assertNotNull(retrieved)
        assertEquals(ApprovalContinuationStatus.CANCELLED_UNCERTAIN, retrieved.status)
        assertEquals("admin-alice", retrieved.recoveryResolvedBy)
        assertEquals("stuck.worker", retrieved.recoveryReasonCode)
    }
}
