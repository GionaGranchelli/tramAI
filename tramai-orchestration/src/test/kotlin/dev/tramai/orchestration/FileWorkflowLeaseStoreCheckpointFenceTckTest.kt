package dev.tramai.orchestration

import dev.tramai.testing.persistence.lease.MutableMillisClock
import dev.tramai.testing.persistence.lease.WorkflowLeaseCheckpointFenceTck
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.exists

/**
 * Epic 8.1g: FileWorkflowLeaseStore must also satisfy the companion
 * WorkflowLeaseCheckpointFence compatibility contract (tramai-testing
 * testFixtures). The runner owns per-case isolation: a fresh temp directory
 * shared by the lease store and its fenced checkpoint store.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileWorkflowLeaseStoreCheckpointFenceTckTest : WorkflowLeaseCheckpointFenceTck() {
    // FileWorkflowCheckpointStore is local-only: it records neither a durable admission counter nor an authoritative
    // lifecycle status. The fence still refuses a superseded worker; what it cannot do is grant, because there is no
    // authority behind it to grant with.
    override val durableAdmissionSupported: Boolean get() = false
    override val durableCancellationSupported: Boolean get() = false

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
