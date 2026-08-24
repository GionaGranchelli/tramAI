package dev.tramai.orchestration

import dev.tramai.testing.persistence.checkpoint.WorkflowCheckpointStoreTck
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import kotlin.io.path.exists

/**
 * Epic 8.1f: FileWorkflowCheckpointStore must satisfy the shared checkpoint
 * compatibility contract (tramai-testing testFixtures). The runner owns the
 * per-case isolation; storage technology (file format, permissions,
 * corruption) never contaminates the contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileWorkflowCheckpointStoreTckTest : WorkflowCheckpointStoreTck() {

    private val rootDir: Path = Files.createTempDirectory("tramai-checkpoint-tck-file-").toAbsolutePath()
    private val caseCounter = AtomicLong(0)

    override fun createStore(): WorkflowCheckpointStore {
        val caseDir = Files.createDirectories(rootDir.resolve("case-${caseCounter.incrementAndGet()}"))
        return FileWorkflowCheckpointStore(caseDir)
    }

    @AfterAll
    fun cleanup() {
        if (rootDir.exists()) rootDir.toFile().deleteRecursively()
    }
}
