package dev.tramai.orchestration

import dev.tramai.testing.persistence.lease.MutableMillisClock
import dev.tramai.testing.persistence.lease.WorkflowLeaseStoreTck
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import kotlin.io.path.exists

/**
 * Epic 8.1g: FileWorkflowLeaseStore must satisfy the shared lease
 * compatibility contract (tramai-testing testFixtures). The runner owns the
 * per-case isolation (unique temp directory + deterministic clock); file
 * format, permissions and lock mechanics never contaminate the contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileWorkflowLeaseStoreTckTest : WorkflowLeaseStoreTck() {

    private val rootDir: Path = Files.createTempDirectory("tramai-lease-tck-file-").toAbsolutePath()
    private val caseCounter = AtomicLong(0)

    override fun createStore(clock: MutableMillisClock): WorkflowLeaseStore {
        val caseDir = Files.createDirectories(rootDir.resolve("case-${caseCounter.incrementAndGet()}"))
        return FileWorkflowLeaseStore(caseDir, clockMillis = clock)
    }

    @AfterAll
    fun cleanup() {
        if (rootDir.exists()) rootDir.toFile().deleteRecursively()
    }
}
