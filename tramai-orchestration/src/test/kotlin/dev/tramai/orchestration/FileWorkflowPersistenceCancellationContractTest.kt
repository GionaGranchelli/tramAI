package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

class FileWorkflowPersistenceCancellationContractTest {

    private lateinit var root: Path

    @AfterTest
    fun cleanup() {
        if (::root.isInitialized) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun temporaryFilesUnder(dir: Path): List<Path> =
        Files.walk(dir).use { paths ->
            paths.filter(Files::isRegularFile).filter {
                it.fileName.toString().endsWith(".tmp")
            }.toList()
        }

    private fun checkpointStore(
        atomicWriter: AtomicFileWriter = realAtomicFileWriter,
    ): FileWorkflowCheckpointStore {
        root = createTempDirectory("file-cancel-checkpoint-")
        return FileWorkflowCheckpointStore.forTest(root, atomicWriter)
    }

    private fun testCheckpoint(
        workflowName: String = "test-wf",
        workflowId: String = UUID.randomUUID().toString(),
    ) = WorkflowCheckpoint(
        workflowName = workflowName, workflowId = workflowId, nextStepIndex = 0,
        stepExecutions = 0, lastCompletedStepName = null,
        statePayload = "state-${UUID.randomUUID()}", metadata = mapOf("test" to "true"),
    )

    private fun leaseStore(
        clockMillis: () -> Long = System::currentTimeMillis,
    ): FileWorkflowLeaseStore {
        root = createTempDirectory("file-cancel-lease-")
        return FileWorkflowLeaseStore.forTest(root, realAtomicFileWriter, clockMillis)
    }

    // ═══ Cross-process OS file-lock cancellation ═══
    // The helper JVM (FileLockHolderMain) acquires the SAME FileChannel.lock() on the
    // actual `<path>.lock` file that production uses — not the Unix `flock` command —
    // so these tests prove cancellation of a real cross-process OS-lock wait.

    private fun javaBinary(): String = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private fun spawnLockHolder(lockPath: Path): Triple<Process, Path, Path> {
        val markerPath = kotlin.io.path.createTempFile("lock-holder-marker", ".flag")
        val releasePath = kotlin.io.path.createTempFile("lock-holder-release", ".flag")
        Files.deleteIfExists(markerPath)
        Files.deleteIfExists(releasePath)
        val classPath = System.getProperty("java.class.path")
        val helper = ProcessBuilder(
            javaBinary(),
            "-cp",
            classPath,
            "dev.tramai.orchestration.FileLockHolderMainKt",
            lockPath.toString(),
            markerPath.toString(),
            releasePath.toString(),
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        return Triple(helper, releasePath, markerPath)
    }

    private fun awaitFile(path: Path, timeoutMillis: Long = 15_000) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (!Files.exists(path)) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $path" }
            Thread.sleep(25)
        }
    }

    @Test
    fun `real cross-process checkpoint lock wait is cancellable without mutation`() {
        runBlocking {
            val store = checkpointStore()
            val workflowName = "cross-wf"
            val workflowId = "cross-id"
            val checkpointLockPath = DefaultWorkflowCheckpointPathStrategy("checkpoint.properties")
                .resolve(root, workflowName, workflowId).resolveSibling(
                    "checkpoint.properties.lock",
                )
            val registryBefore = pathLockRegistrySize()

            val (helper, releasePath, markerPath) = spawnLockHolder(checkpointLockPath)
            try {
                awaitFile(markerPath)
                // The helper JVM owns the OS lock; confirm the helper is holding it.
                assertThat(helper.isAlive).isTrue()

                val job = launch(Dispatchers.IO) {
                    store.save(testCheckpoint(workflowName = workflowName, workflowId = workflowId))
                }
                // The operation is confirmed to be waiting on the OS lock.
                assertThat(withTimeoutOrNull(500) { job.join() }).isNull()

                val cancelledAt = System.nanoTime()
                job.cancel()
                // join() on a cancelled child may return normally or throw the job's
                // CancellationException depending on kotlinx version — assert the OUTCOME
                // (job completed within bounds, no mutation) rather than the exception type.
                runCatching { withTimeout(10_000) { job.join() } }
                val elapsedMillis = (System.nanoTime() - cancelledAt) / 1_000_000L

                // Cancellation completed within a bounded window...
                assertThat(elapsedMillis).isLessThan(10_000)
                assertThat(job.isCompleted).isTrue()
                // ...without depending on lock release: the helper still lives and holds it.
                assertThat(helper.isAlive).isTrue()
                assertThat(Path.of("$checkpointLockPath").let { Files.exists(it) }).isTrue()
                // No checkpoint mutation or temporary file was created.
                val workflowDir = root.resolve(workflowName).resolve(workflowId)
                assertThat(temporaryFilesUnder(workflowDir)).isEmpty()
                assertThat(store.load(workflowName, workflowId)).isNull()
                // Path-lock registry returns to its original size.
                assertThat(pathLockRegistrySize()).isEqualTo(registryBefore)

                // After releasing the helper, the store remains usable.
                Files.writeString(releasePath, "go")
                helper.waitFor()
                val saved = store.save(testCheckpoint(workflowName = workflowName, workflowId = workflowId))
                assertThat(store.load(workflowName, workflowId)?.revision).isEqualTo(saved.revision)
            } finally {
                runCatching { Files.writeString(releasePath, "go") }
                helper.destroyForcibly()
                helper.waitFor()
            }
        }
    }

    @Test
    fun `real cross-process suspending file lock wait is cancellable without mutation`() {
        runBlocking {
            val store = checkpointStore()
            val leaseStore = FileWorkflowLeaseStore.forTest(root, realAtomicFileWriter)
            val workflowName = "cross-susp-wf"
            val workflowId = "cross-susp-id"
            val leaseLockPath = DefaultWorkflowCheckpointPathStrategy("lease.properties")
                .resolve(root, workflowName, workflowId).resolveSibling(
                    "lease.properties.lock",
                )
            val registryBefore = pathLockRegistrySize()

            // Claim a lease first so the fenced operation has a valid expected lease.
            val lease = leaseStore.claim(
                workflowName = workflowName,
                workflowId = workflowId,
                ownerId = "owner-1",
                checkpointRevision = null,
                leaseDurationMillis = 60_000,
            )

            val (helper, releasePath, markerPath) = spawnLockHolder(leaseLockPath)
            try {
                awaitFile(markerPath)
                assertThat(helper.isAlive).isTrue()

                val job = launch(Dispatchers.IO) {
                    leaseStore.saveCheckpointIfLeaseOwner(
                        checkpointStore = store,
                        checkpoint = testCheckpoint(workflowName = workflowName, workflowId = workflowId),
                        expectedRevision = null,
                        expectedLease = lease,
                    )
                }
                assertThat(withTimeoutOrNull(500) { job.join() }).isNull()

                val cancelledAt = System.nanoTime()
                job.cancel()
                runCatching { withTimeout(10_000) { job.join() } }
                val elapsedMillis = (System.nanoTime() - cancelledAt) / 1_000_000L

                assertThat(elapsedMillis).isLessThan(10_000)
                assertThat(job.isCompleted).isTrue()
                assertThat(helper.isAlive).isTrue()
                val workflowDir = root.resolve(workflowName).resolve(workflowId)
                assertThat(temporaryFilesUnder(workflowDir)).isEmpty()
                assertThat(store.load(workflowName, workflowId)).isNull()
                assertThat(pathLockRegistrySize()).isEqualTo(registryBefore)

                Files.writeString(releasePath, "go")
                helper.waitFor()
                val saved = leaseStore.saveCheckpointIfLeaseOwner(
                    checkpointStore = store,
                    checkpoint = testCheckpoint(workflowName = workflowName, workflowId = workflowId),
                    expectedRevision = null,
                    expectedLease = lease,
                )
                assertThat(store.load(workflowName, workflowId)?.revision).isEqualTo(saved.revision)
            } finally {
                runCatching { Files.writeString(releasePath, "go") }
                helper.destroyForcibly()
                helper.waitFor()
            }
        }
    }

    // ═══ Test 1: Pre-cancelled checkpoint save ═══

    @Test
    fun `pre-cancelled checkpoint save throws CancellationException and creates nothing`() {
        runBlocking {
            val store = checkpointStore()
            val checkpoint = testCheckpoint()
            val job = Job().also { it.cancel() }
            assertThatThrownBy {
                runBlocking(job) { store.save(checkpoint, expectedRevision = null) }
            }.isInstanceOf(CancellationException::class.java)
            assertThat(store.load(checkpoint.workflowName, checkpoint.workflowId)).isNull()
            assertThat(temporaryFilesUnder(root)).isEmpty()
        }
    }

    // ═══ Test 2: JVM mutex released on cancellation ═══

    @Test
    fun `jvm mutex released on cancellation - second coroutine cancelled while waiting`() {
        runBlocking {
            val checkpoint = testCheckpoint()
            val registryBefore = pathLockRegistrySize()

            val enteredLock = java.util.concurrent.CountDownLatch(1)
            val releaseLock = java.util.concurrent.CountDownLatch(1)

            val hookWriter = AtomicFileWriter(beforeMove = {
                enteredLock.countDown()
                releaseLock.await()
            })

            val store = checkpointStore(atomicWriter = hookWriter)

            // First coroutine: enters the lock and blocks on releaseLock.
            val first = launch(Dispatchers.IO) {
                store.save(checkpoint, expectedRevision = null)
            }

            assertThat(
                enteredLock.await(5, java.util.concurrent.TimeUnit.SECONDS),
            ).`as`("first coroutine entered the atomic-write hook").isTrue()

            // Second coroutine: undispatched so it reaches the Mutex
            // suspension before control returns to this thread.
            val second = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                store.save(
                    checkpoint.copy(statePayload = "should-not-persist"),
                    expectedRevision = 1,
                )
            }

            assertThat(second.isCompleted).isFalse()
            second.cancel()
            second.join()
            assertThat(second.isCancelled).isTrue()

            // Release the first coroutine so it completes normally.
            releaseLock.countDown()
            withTimeout(5_000) { first.join() }

            // The second's save must not have persisted — after the first
            // completes, the stored value must be the original checkpoint,
            // not "should-not-persist".
            val loaded = withTimeout(5_000) {
                store.load(checkpoint.workflowName, checkpoint.workflowId)
            }
            assertThat(loaded?.statePayload).isEqualTo(checkpoint.statePayload)

            // Registry must return to baseline (cancelled waiter removed).
            assertThat(pathLockRegistrySize()).isEqualTo(registryBefore)

            // Path must be usable after both coroutines release.
            val reloaded = store.save(
                checkpoint.copy(statePayload = "after-release"),
                expectedRevision = 1,
            )
            assertThat(reloaded.statePayload).isEqualTo("after-release")
        }
    }

    // ═══ Test 3: Cancellation during atomic window preserves old state ═══

    @Test
    fun `cancellation during atomic write window preserves old checkpoint and cleans temp files`() {
        runBlocking {
            val checkpoint = testCheckpoint()

            val writeCount = java.util.concurrent.atomic.AtomicInteger(0)
            val enteredSecondWrite = java.util.concurrent.CountDownLatch(1)
            val holdSecondWrite = java.util.concurrent.CountDownLatch(1)

            val hookWriter = AtomicFileWriter(beforeMove = {
                if (writeCount.incrementAndGet() == 2) {
                    enteredSecondWrite.countDown()
                    // Block until cancelled. runInterruptible interrupts
                    // the IO thread, CountDownLatch.await() throws
                    // InterruptedException, converted to
                    // CancellationException. writeStringAtomically's
                    // finally block cleans up the temp file.
                    holdSecondWrite.await()
                }
            })

            val store = checkpointStore(atomicWriter = hookWriter)

            // Persist the original checkpoint (writeCount == 1, no hook).
            val persisted = store.save(checkpoint, expectedRevision = null)

            // Start an update (writeCount == 2, hook fires and blocks).
            val update = launch(Dispatchers.IO) {
                store.save(
                    checkpoint.copy(statePayload = "in-flight"),
                    expectedRevision = persisted.revision,
                )
            }

            assertThat(
                enteredSecondWrite.await(5, java.util.concurrent.TimeUnit.SECONDS),
            ).`as`("update coroutine entered the hook").isTrue()

            update.cancel()
            update.join()
            assertThat(update.isCancelled).isTrue()

            // Original checkpoint must be preserved — the update was
            // cancelled before the atomic move.
            assertThat(
                store.load(checkpoint.workflowName, checkpoint.workflowId)?.statePayload,
            ).isEqualTo(checkpoint.statePayload)

            // No temp files left behind — proves production finally block ran.
            assertThat(temporaryFilesUnder(root)).isEmpty()

            // Path remains usable after cancellation.
            val redo = store.save(
                checkpoint.copy(statePayload = "redo-after-cancel"),
                expectedRevision = persisted.revision,
            )
            assertThat(
                store.load(checkpoint.workflowName, checkpoint.workflowId)?.statePayload,
            ).isEqualTo("redo-after-cancel")
        }
    }

    // ═══ Test 4: Cancellation during lease claim ═══

    @Test
    fun `cancellation during lease claim leaves no lease visible`() {
        runBlocking {
            val store = leaseStore()
            val job = Job().also { it.cancel() }
            assertThatThrownBy {
                runBlocking(job) {
                    store.claim("wf", "id-1", "owner", null, 60_000)
                }
            }.isInstanceOf(CancellationException::class.java)
            assertThat(store.currentLease("wf", "id-1")).isNull()
            val lease = store.claim("wf", "id-1", "owner-2", null, 60_000)
            assertThat(lease.ownerId).isEqualTo("owner-2")
        }
    }

    // ═══ Test 5: Cancellation during lease renewal ═══

    @Test
    fun `cancellation during lease renewal leaves existing lease unchanged`() {
        runBlocking {
            val now = System.currentTimeMillis()
            val store = leaseStore { now }
            val lease = store.claim("wf", "id-renew", "owner", null, 60_000)
            val originalExpiry = lease.expiresAtEpochMillis
            val job = Job().also { it.cancel() }
            assertThatThrownBy {
                runBlocking(job) {
                    store.renew(lease, checkpointRevision = 1, leaseDurationMillis = 120_000)
                }
            }.isInstanceOf(CancellationException::class.java)
            val current = store.currentLease("wf", "id-renew")
            assertThat(current).isNotNull
            assertThat(current!!.expiresAtEpochMillis).isEqualTo(originalExpiry)
        }
    }

    // ═══ Test 6: Cancellation during fenced save ═══

    @Test
    fun `cancellation during fenced save preserves lease and checkpoint state`() {
        runBlocking {
            root = createTempDirectory("file-cancel-fenced-")
            val checkpointStore = FileWorkflowCheckpointStore.forTest(root, realAtomicFileWriter)
            val leaseStore = FileWorkflowLeaseStore.forTest(root, realAtomicFileWriter)
            val original = testCheckpoint()
            checkpointStore.save(original, expectedRevision = null)
            val lease = leaseStore.claim(
                original.workflowName, original.workflowId, "owner", null, 60_000,
            )
            val job = Job().also { it.cancel() }
            assertThatThrownBy {
                runBlocking(job) {
                    leaseStore.saveCheckpointIfLeaseOwner(
                        checkpointStore,
                        original.copy(statePayload = "should-not-persist"),
                        0,
                        lease,
                    )
                }
            }.isInstanceOf(CancellationException::class.java)
            assertThat(
                checkpointStore.load(original.workflowName, original.workflowId)?.statePayload,
            ).isEqualTo(original.statePayload)
            assertThat(
                leaseStore.currentLease(original.workflowName, original.workflowId),
            ).isNotNull
        }
    }

    // ═══ Test 6b: Fenced save does not deadlock ═══

    @Test
    fun `fenced save does not deadlock when lease and checkpoint are different paths`() {
        runBlocking {
            root = createTempDirectory("file-cancel-fenced-")
            val checkpointStore = FileWorkflowCheckpointStore.forTest(root, realAtomicFileWriter)
            val leaseStore = FileWorkflowLeaseStore.forTest(root, realAtomicFileWriter)

            val checkpoint = testCheckpoint()
            checkpointStore.save(checkpoint, expectedRevision = null)
            val lease = leaseStore.claim(
                checkpoint.workflowName, checkpoint.workflowId, "owner", null, 60_000,
            )

            withTimeout(5_000) {
                leaseStore.saveCheckpointIfLeaseOwner(
                    checkpointStore,
                    checkpoint.copy(statePayload = "updated-under-fence"),
                    expectedRevision = 1,
                    expectedLease = lease,
                )
            }

            assertThat(
                checkpointStore.load(checkpoint.workflowName, checkpoint.workflowId)
                    ?.statePayload,
            ).isEqualTo("updated-under-fence")
        }
    }

    // ═══ Test 6c: Path mutex registry returns to zero after use ═══

    @Test
    fun `path mutex registry releases entries after every operation completes`() {
        runBlocking {
            val store = checkpointStore()
            val beforeSize = pathLockRegistrySize()

            // Create many distinct checkpoints to exercise the registry.
            val count = 50
            repeat(count) { i ->
                store.save(
                    testCheckpoint(workflowId = "registry-test-$i"),
                    expectedRevision = null,
                )
            }

            // All operations completed — registry should return to baseline.
            val afterSize = pathLockRegistrySize()
            assertThat(afterSize).isEqualTo(beforeSize)
        }
    }

    // ═══ Test 7a: Revision conflict ═══

    @Test
    fun `revision conflict throws WorkflowCheckpointConflictException`() {
        runBlocking {
            val store = checkpointStore()
            val cp = testCheckpoint()
            store.save(cp, expectedRevision = null)
            assertThatThrownBy {
                runBlocking { store.save(cp.copy(statePayload = "b"), expectedRevision = null) }
            }.isInstanceOf(WorkflowCheckpointConflictException::class.java)
            assertThatThrownBy {
                runBlocking {
                    store.delete(cp.workflowName, cp.workflowId, expectedRevision = 99)
                }
            }.isInstanceOf(WorkflowCheckpointConflictException::class.java)
        }
    }

    // ═══ Test 7b: Lease conflict ═══

    @Test
    fun `lease conflict throws WorkflowLeaseConflictException`() {
        runBlocking {
            val store = leaseStore()
            store.claim("wf", "id-conflict", "owner-a", null, 60_000)
            assertThatThrownBy {
                runBlocking {
                    store.claim("wf", "id-conflict", "owner-b", null, 60_000)
                }
            }.isInstanceOf(WorkflowLeaseConflictException::class.java)
        }
    }

    // ═══ Test 8: Resource cleanup ═══

    @Test
    fun `no tmp files remain after successful and cancelled operations`() {
        runBlocking {
            val store = checkpointStore()
            store.save(testCheckpoint(), expectedRevision = null)
            assertThat(temporaryFilesUnder(root)).isEmpty()
            val job = Job().also { it.cancel() }
            try {
                runBlocking(job) {
                    store.save(
                        testCheckpoint(workflowId = UUID.randomUUID().toString()),
                        null,
                    )
                }
            } catch (_: CancellationException) {
            }
            assertThat(temporaryFilesUnder(root)).isEmpty()
        }
    }

}
