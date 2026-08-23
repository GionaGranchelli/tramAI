package dev.tramai.testing

import java.io.File
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1f architecture guard: every concrete
 * [dev.tramai.orchestration.WorkflowCheckpointStore] implementation must be
 * enrolled in the shared checkpoint compatibility contract (tramai-testing
 * testFixtures).
 *
 * Same two properties as the earlier guards (#267-#272): the four roadmap
 * runners are pinned by name, and every concrete implementation in any
 * module's main source set must ship a `<Store>TckTest` runner in the same
 * module that actually extends
 * [dev.tramai.testing.persistence.checkpoint.WorkflowCheckpointStoreTck].
 * A future Redis/workflow-checkpoint store cannot merge without enrollment.
 */
class WorkflowCheckpointStoreTckEnrollmentArchitectureTest {

    private val scanner = StoreEnrollmentScanner("WorkflowCheckpointStore", "WorkflowCheckpointStoreTck")

    private val repoRoot: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private val expectedRunners = setOf(
        "InMemoryWorkflowCheckpointStoreTckTest",
        "FileWorkflowCheckpointStoreTckTest",
        "MarkdownWorkflowCheckpointStoreTckTest",
        "JdbcWorkflowCheckpointStoreTckTest",
    )

    @Test
    fun `every roadmap WorkflowCheckpointStore ships a TCK runner`() {
        val missing = expectedRunners.filter { runnerName -> scanner.findRunnerFile(repoRoot, runnerName) == null }
        assertThat(missing)
            .withFailMessage(
                "Pinned WorkflowCheckpointStore TCK runners missing. The compatibility contract is " +
                    "reviewed per runner; deleting a runner silently removes a store from the contract: $missing",
            )
            .isEmpty()
    }

    @Test
    fun `every WorkflowCheckpointStore implementation has a valid TCK runner in its module`() {
        val unenrolled = scanner.storeModules(repoRoot).flatMap { (module, implementations) ->
            implementations
                .filter { storeName -> !scanner.hasValidRunner(repoRoot, module, storeName) }
                .map { store -> "$module/$store" }
        }
        assertThat(unenrolled)
            .withFailMessage(
                "WorkflowCheckpointStore implementations without a <Store>TckTest runner extending " +
                    "WorkflowCheckpointStoreTck in the same module: $unenrolled. " +
                    "Adding a WorkflowCheckpointStore without enrolling it in the compatibility " +
                    "contract must make a gate fail.",
            )
            .isEmpty()
    }

    // ── probe tests for the scanner against this family ─────────────

    @Test
    fun `body-less WorkflowCheckpointStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.orchestration.WorkflowCheckpointStore
            class RedisCheckpointStore(private val delegate: WorkflowCheckpointStore) :
                WorkflowCheckpointStore by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).containsExactly("RedisCheckpointStore")
    }

    @Test
    fun `runner file must actually subclass WorkflowCheckpointStoreTck`() {
        val fake = tempSourceFile("class RedisCheckpointStoreTckTest")
        val real = tempSourceFile("class RedisCheckpointStoreTckTest : WorkflowCheckpointStoreTck() { }")
        assertThat(scanner.runnerSubclassesTck(fake, "RedisCheckpointStore")).isFalse()
        assertThat(scanner.runnerSubclassesTck(real, "RedisCheckpointStore")).isTrue()
    }

    @Test
    fun `exception class whose name contains the interface does not count as an implementation`() {
        val file = tempSourceFile(
            """
            package probe
            class WorkflowCheckpointStoreException(val code: String) : RuntimeException()
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).isEmpty()
    }

    @Test
    fun `constructor parameter never counts as an implementation`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.orchestration.WorkflowCheckpointStore
            class Holder(val store: WorkflowCheckpointStore)
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).isEmpty()
    }

    private fun tempSourceFile(content: String): File =
        Files.createTempFile("enrollment-probe-", ".kt").toFile().apply {
            writeText(content)
            deleteOnExit()
        }
}
