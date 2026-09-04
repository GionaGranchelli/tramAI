package dev.tramai.orchestration

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Epic 12.1c probe 5 (file part) — the file-backed counterpart to
 * [JdbcConnectionReleaseProbeTest]. Exercises the real file-lock / FileChannel
 * path of [FileWorkflowCheckpointStore]: every save/load/delete goes through
 * [withFileLockCancellable] (retainPathLock -> FileChannel.lock -> releasePathLock)
 * and the module-global pathLocks registry must return to its baseline after
 * every operation and after repeated cycles over many distinct paths.
 *
 * Deterministic in-process observability: [pathLockRegistrySize]. Linux-only
 * /proc/self/fd corroboration is platform-gated and bounded-growth only — a
 * specific FD number is never a universal contract.
 */
class FileDescriptorReleaseProbeTest {
    private lateinit var root: Path

    @AfterEach
    fun cleanup() {
        if (::root.isInitialized) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `repeated save load delete cycles return path locks to baseline`() {
        val store = newStore()
        val baseline = pathLockRegistrySize()

        repeat(CYCLES) { cycle ->
            val cp = checkpoint("wf", "w-$cycle")
            runBlocking {
                store.save(cp)
                store.load("wf", "w-$cycle")
                store.delete("wf", "w-$cycle")
            }
            assertEquals(baseline, pathLockRegistrySize(), "cycle $cycle leaked a path lock")
        }
    }

    @Test
    fun `many distinct workflow paths return the path lock registry to baseline`() {
        val store = newStore()
        val baseline = pathLockRegistrySize()

        // Distinct names AND ids force distinct lock paths through the
        // reference-counted registry; a missing release would leave entries.
        repeat(DISTINCT_PATH_COUNT) { i ->
            val name = "wf-$i"
            val cp = checkpoint(name, "id-$i")
            runBlocking {
                store.save(cp)
                store.load(name, "id-$i")
            }
        }
        repeat(DISTINCT_PATH_COUNT) { i ->
            runBlocking { store.delete("wf-$i", "id-$i") }
        }

        assertEquals(baseline, pathLockRegistrySize(), "distinct-path cycles left path locks retained")
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `linux proc fd count stays bounded across repeated file-store cycles`() {
        val procFd = File("/proc/self/fd")
        org.junit.jupiter.api.Assumptions
            .assumeTrue(procFd.isDirectory, "/proc/self/fd unavailable")

        val store = newStore()
        repeat(10) { cycle -> cycleOp(store, "warm-$cycle") }
        val baseline = procFd.listFiles()?.size ?: 0

        repeat(LINUX_FD_CYCLES) { cycle -> cycleOp(store, "fd-$cycle") }
        val after = procFd.listFiles()?.size ?: 0

        // Bounded growth only: JVM/file-system internals may legitimately hold
        // descriptors; a monotonic leak blows far past this bound.
        assertTrue(after - baseline < 20, "fd growth ${after - baseline} suggests a descriptor leak")
    }

    private fun cycleOp(
        store: FileWorkflowCheckpointStore,
        id: String,
    ) {
        val cp = checkpoint("wf", id)
        runBlocking {
            store.save(cp)
            store.load("wf", id)
            store.delete("wf", id)
        }
    }

    private fun newStore(): FileWorkflowCheckpointStore {
        root = createTempDirectory("file-fd-probe-")
        return FileWorkflowCheckpointStore.forTest(root, realAtomicFileWriter)
    }

    private fun checkpoint(
        name: String,
        id: String,
    ): WorkflowCheckpoint =
        WorkflowCheckpoint(
            workflowName = name,
            workflowId = id,
            nextStepIndex = 0,
            stepExecutions = 0,
            lastCompletedStepName = null,
            statePayload = "state-${UUID.randomUUID()}",
            metadata = mapOf("test" to "true"),
        )

    private companion object {
        const val CYCLES = 30
        const val DISTINCT_PATH_COUNT = 50
        const val LINUX_FD_CYCLES = 200
    }
}
