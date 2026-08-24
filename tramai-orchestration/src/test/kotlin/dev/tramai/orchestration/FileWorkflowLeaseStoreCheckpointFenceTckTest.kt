package dev.tramai.orchestration

import dev.tramai.testing.persistence.lease.MutableMillisClock
import dev.tramai.testing.persistence.lease.WorkflowLeaseCheckpointFenceTck
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import kotlin.io.path.exists

/**
 * Epic 8.1g: FileWorkflowLeaseStore must also satisfy the companion
 * WorkflowLeaseCheckpointFence compatibility contract (tramai-testing
 * testFixtures). The runner owns per-case isolation: a fresh temp directory
 * shared by the lease store and its fenced checkpoint store.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileWorkflowLeaseStoreCheckpointFenceTckTest : WorkflowLeaseCheckpointFenceTck() {

    private val rootDir: Path = Files.createTempDirectory("tramai-fence-tck-file-").toAbsolutePath()
    private val caseCounter = AtomicLong(0)

    override fun newHarness(): Harness {
        val clock = MutableMillisClock()
        val caseDir = Files.createDirectories(rootDir.resolve("case-${caseCounter.incrementAndGet()}"))
        val leaseStore = FileWorkflowLeaseStore(caseDir, clockMillis = clock)
        return Harness(
            clock = clock,
            leaseStore = leaseStore,
            fence = leaseStore,
            checkpointStore = FileWorkflowCheckpointStore(caseDir),
        )
    }

    @AfterAll
    fun cleanup() {
        if (rootDir.exists()) rootDir.toFile().deleteRecursively()
    }
}
