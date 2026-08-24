package dev.tramai.testing

import java.io.File
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1g architecture guard: every concrete
 * [dev.tramai.orchestration.WorkflowLeaseCheckpointFence] implementation
 * must be enrolled in the companion fence compatibility contract
 * (tramai-testing testFixtures). The fence is a distinct optional SPI the
 * built-in lease stores implement and the worker depends on — it must never
 * be weakened silently.
 */
class WorkflowLeaseCheckpointFenceTckEnrollmentArchitectureTest {

    private val scanner = StoreEnrollmentScanner("WorkflowLeaseCheckpointFence", "WorkflowLeaseCheckpointFenceTck")

    private val repoRoot: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private val expectedRunners = setOf(
        "InMemoryWorkflowLeaseStoreCheckpointFenceTckTest",
        "FileWorkflowLeaseStoreCheckpointFenceTckTest",
        "JdbcWorkflowLeaseStoreCheckpointFenceTckTest",
    )

    /** Fence runners follow the `<Store>CheckpointFenceTckTest` convention. */
    private fun runnerNameFor(storeName: String): String = "${storeName}CheckpointFenceTckTest"

    private fun hasValidRunner(repoRoot: File, module: String, storeName: String): Boolean {
        val runnerName = runnerNameFor(storeName)
        val testDir = File(repoRoot, "$module/src/test/kotlin")
        val runnerFile = testDir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "$runnerName.kt" } ?: return false
        return Regex("""(?s)class\s+$runnerName\b[^{]*:\s*WorkflowLeaseCheckpointFenceTck\b""")
            .containsMatchIn(runnerFile.readText())
    }

    @Test
    fun `every roadmap fence implementation ships a TCK runner`() {
        val missing = expectedRunners.filter { runnerName -> scanner.findRunnerFile(repoRoot, runnerName) == null }
        assertThat(missing)
            .withFailMessage(
                "Pinned WorkflowLeaseCheckpointFence TCK runners missing: $missing",
            )
            .isEmpty()
    }

    @Test
    fun `every WorkflowLeaseCheckpointFence implementation has a valid TCK runner in its module`() {
        val unenrolled = scanner.storeModules(repoRoot).flatMap { (module, implementations) ->
            implementations
                .filter { storeName -> !hasValidRunner(repoRoot, module, storeName) }
                .map { store -> "$module/$store" }
        }
        assertThat(unenrolled)
            .withFailMessage(
                "WorkflowLeaseCheckpointFence implementations without a <Store>TckTest runner " +
                    "extending WorkflowLeaseCheckpointFenceTck in the same module: $unenrolled",
            )
            .isEmpty()
    }

    @Test
    fun `private decorator is not a fence family member`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.orchestration.WorkflowLeaseCheckpointFence
            private class FencedLease(private val delegate: WorkflowLeaseCheckpointFence) :
                WorkflowLeaseCheckpointFence by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).isEmpty()
    }

    private fun tempSourceFile(content: String): File =
        Files.createTempFile("fence-enrollment-probe-", ".kt").toFile().apply {
            writeText(content)
            deleteOnExit()
        }
}
