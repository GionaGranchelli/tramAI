package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
        return FileWorkflowCheckpointStore(root, atomicWriter = atomicWriter)
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
        return FileWorkflowLeaseStore(root, clockMillis = clockMillis)
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
    fun `jvm mutex released on cancellation — second coroutine cancelled while waiting`() {
        runBlocking {
            val checkpoint = testCheckpoint()

            val enteredLock = CompletableDeferred<Unit>()
            val releaseLock = CompletableDeferred<Unit>()

            val blockingWriter = AtomicFileWriter { path, content ->
                enteredLock.complete(Unit)
                // Block inside the JVM mutex + OS lock until released.
                runBlocking { releaseLock.await() }
                realAtomicFileWriter.write(path, content)
            }

            val store = checkpointStore(atomicWriter = blockingWriter)

            // First coroutine: enters the lock and blocks.
            val first = launch(Dispatchers.IO) {
                store.save(checkpoint, expectedRevision = null)
            }

            withTimeout(5_000) { enteredLock.await() }

            // Second coroutine: tries same path — blocks on the JVM mutex.
            val second = launch(Dispatchers.IO) {
                store.save(
                    checkpoint.copy(statePayload = "should-not-persist"),
                    expectedRevision = 1,
                )
            }

            // Give the scheduler time to move second onto the mutex queue.
            kotlinx.coroutines.delay(500)
            second.cancel()

            // Join within timeout — cancellation must release the mutex waiter.
            withTimeout(5_000) { second.join() }

            // The second's save must not have persisted.
            assertThat(store.load(checkpoint.workflowName, checkpoint.workflowId))
                .isNull()

            // Release the first coroutine so the path is usable again.
            releaseLock.complete(Unit)
            withTimeout(5_000) { first.join() }

            // After release, the same checkpoint path must be lockable again.
            val reloaded = withTimeout(5_000) {
                store.save(
                    checkpoint.copy(statePayload = "after-release"),
                    expectedRevision = 1,
                )
            }
            assertThat(reloaded.statePayload).isEqualTo("after-release")
        }
    }

    // ═══ Test 3: Cancellation during atomic window preserves old state ═══

    @Test
    fun `cancellation during atomic write window preserves old checkpoint and cleans temp files`() {
        runBlocking {
            val checkpoint = testCheckpoint()
            val tempFileWritten = CompletableDeferred<Unit>()
            val holdAtomicMove = CompletableDeferred<Unit>()
            var writeCount = 0

            val hookWriter = AtomicFileWriter { path, content ->
                writeCount++
                if (writeCount == 2) {
                    ensureOwnerOnlyDirectory(path.parent)
                    val tempFile = Files.createTempFile(
                        path.parent,
                        path.fileName.toString(),
                        ".tmp",
                    )
                    try {
                        Files.writeString(tempFile, content, StandardCharsets.UTF_8)
                        tempFileWritten.complete(Unit)
                        // Block between temp-file write and atomic move.
                        runBlocking { holdAtomicMove.await() }
                        try {
                            Files.move(
                                tempFile, path,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE,
                            )
                        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                            Files.move(
                                tempFile, path,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                    } finally {
                        Files.deleteIfExists(tempFile)
                    }
                } else {
                    realAtomicFileWriter.write(path, content)
                }
            }

            val store = checkpointStore(atomicWriter = hookWriter)

            // Persist the original checkpoint.
            val persisted = store.save(checkpoint, expectedRevision = null)

            // Start an update that will be cancelled during the atomic-write window.
            val update = launch(Dispatchers.IO) {
                store.save(
                    checkpoint.copy(statePayload = "in-flight"),
                    expectedRevision = persisted.revision,
                )
            }

            withTimeout(5_000) { tempFileWritten.await() }

            update.cancel()

            // Wait for update to finish (cancelled or not) within timeout.
            try {
                withTimeout(5_000) { update.join() }
            } catch (_: CancellationException) {
                // Expected when cancellation propagates through the coroutine.
            }

            // Original checkpoint must be preserved regardless.
            assertThat(
                store.load(checkpoint.workflowName, checkpoint.workflowId)?.statePayload,
            ).isEqualTo(checkpoint.statePayload)

            // No temp files left behind.
            assertThat(temporaryFilesUnder(root)).isEmpty()

            // Path remains usable after cancellation.
            val redo = withTimeout(5_000) {
                store.save(
                    checkpoint.copy(statePayload = "redo-after-cancel"),
                    expectedRevision = persisted.revision,
                )
            }
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
            val checkpointStore = FileWorkflowCheckpointStore(root)
            val leaseStore = FileWorkflowLeaseStore(root)
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
            val checkpointStore = FileWorkflowCheckpointStore(root)
            val leaseStore = FileWorkflowLeaseStore(root)

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
