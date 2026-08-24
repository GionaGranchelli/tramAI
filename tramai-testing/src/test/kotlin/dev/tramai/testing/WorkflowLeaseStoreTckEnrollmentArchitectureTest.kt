package dev.tramai.testing

import java.io.File
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1g architecture guard: every concrete
 * [dev.tramai.orchestration.WorkflowLeaseStore] implementation must be
 * enrolled in the shared lease compatibility contract (tramai-testing
 * testFixtures). A future Redis/workflow lease store cannot merge without
 * enrollment.
 */
class WorkflowLeaseStoreTckEnrollmentArchitectureTest {

    private val scanner = StoreEnrollmentScanner("WorkflowLeaseStore", "WorkflowLeaseStoreTck")

    private val repoRoot: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private val expectedRunners = setOf(
        "InMemoryWorkflowLeaseStoreTckTest",
        "FileWorkflowLeaseStoreTckTest",
        "JdbcWorkflowLeaseStoreTckTest",
    )

    @Test
    fun `every roadmap WorkflowLeaseStore ships a TCK runner`() {
        val missing = expectedRunners.filter { runnerName -> scanner.findRunnerFile(repoRoot, runnerName) == null }
        assertThat(missing)
            .withFailMessage(
                "Pinned WorkflowLeaseStore TCK runners missing. The compatibility contract is " +
                    "reviewed per runner; deleting a runner silently removes a store from the contract: $missing",
            )
            .isEmpty()
    }

    @Test
    fun `every WorkflowLeaseStore implementation has a valid TCK runner in its module`() {
        val unenrolled = scanner.storeModules(repoRoot).flatMap { (module, implementations) ->
            implementations
                .filter { storeName -> !scanner.hasValidRunner(repoRoot, module, storeName) }
                .map { store -> "$module/$store" }
        }
        assertThat(unenrolled)
            .withFailMessage(
                "WorkflowLeaseStore implementations without a <Store>TckTest runner extending " +
                    "WorkflowLeaseStoreTck in the same module: $unenrolled. " +
                    "Adding a WorkflowLeaseStore without enrolling it in the compatibility " +
                    "contract must make a gate fail.",
            )
            .isEmpty()
    }

    // ── probe tests for the scanner against this family ─────────────

    @Test
    fun `body-less WorkflowLeaseStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.orchestration.WorkflowLeaseStore
            class RedisLeaseStore(private val delegate: WorkflowLeaseStore) :
                WorkflowLeaseStore by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).containsExactly("RedisLeaseStore")
    }

    @Test
    fun `runner file must actually subclass WorkflowLeaseStoreTck`() {
        val fake = tempSourceFile("class RedisLeaseStoreTckTest")
        val real = tempSourceFile("class RedisLeaseStoreTckTest : WorkflowLeaseStoreTck() { }")
        assertThat(scanner.runnerSubclassesTck(fake, "RedisLeaseStore")).isFalse()
        assertThat(scanner.runnerSubclassesTck(real, "RedisLeaseStore")).isTrue()
    }

    @Test
    fun `private decorator is not a family member`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.orchestration.WorkflowLeaseStore
            private class FencedLeaseStore(private val delegate: WorkflowLeaseStore) :
                WorkflowLeaseStore by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).isEmpty()
    }

    private fun tempSourceFile(content: String): File =
        Files.createTempFile("lease-enrollment-probe-", ".kt").toFile().apply {
            writeText(content)
            deleteOnExit()
        }
}
