package dev.tramai.orchestration

import dev.tramai.testing.persistence.checkpoint.WorkflowCheckpointStoreTck
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import kotlin.io.path.exists

/**
 * Epic 8.1f: MarkdownWorkflowCheckpointStore must satisfy the shared
 * checkpoint compatibility contract (tramai-testing testFixtures). The
 * runner owns the per-case isolation; the Markdown format and audit-friendly
 * rendering are implementation mechanics, never contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarkdownWorkflowCheckpointStoreTckTest : WorkflowCheckpointStoreTck() {

    private val rootDir: Path = Files.createTempDirectory("tramai-checkpoint-tck-md-").toAbsolutePath()
    private val caseCounter = AtomicLong(0)

    override fun createStore(): WorkflowCheckpointStore {
        val caseDir = Files.createDirectories(rootDir.resolve("case-${caseCounter.incrementAndGet()}"))
        return MarkdownWorkflowCheckpointStore(caseDir)
    }

    @AfterAll
    fun cleanup() {
        if (rootDir.exists()) rootDir.toFile().deleteRecursively()
    }
}
