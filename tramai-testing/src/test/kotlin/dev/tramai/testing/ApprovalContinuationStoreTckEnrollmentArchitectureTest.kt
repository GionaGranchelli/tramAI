package dev.tramai.testing

import java.io.File
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1b architecture guard: every concrete [dev.tramai.core.approval.ApprovalContinuationStore]
 * implementation must be enrolled in the shared ApprovalContinuationStore
 * compatibility contract (tramai-testing testFixtures).
 *
 * Same two properties as the ApprovalStore guard (#267): the three roadmap
 * runners are pinned by name, and every concrete implementation in any
 * module's main source set must ship a `<Store>TckTest` runner in the same
 * module that actually extends
 * [dev.tramai.testing.persistence.approval.continuation.ApprovalContinuationStoreTck].
 */
class ApprovalContinuationStoreTckEnrollmentArchitectureTest {

    private val scanner = StoreEnrollmentScanner("ApprovalContinuationStore", "ApprovalContinuationStoreTck")

    private val repoRoot: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private val expectedRunners = setOf(
        "InMemoryApprovalContinuationStoreTckTest",
        "FileApprovalContinuationStoreTckTest",
        "JdbcApprovalContinuationStoreTckTest",
    )

    @Test
    fun `every roadmap ApprovalContinuationStore ships a TCK runner`() {
        val missing = expectedRunners.filter { runnerName -> scanner.findRunnerFile(repoRoot, runnerName) == null }
        assertThat(missing)
            .withFailMessage(
                "Pinned ApprovalContinuationStore TCK runners missing. The compatibility contract is " +
                    "reviewed per runner; deleting a runner silently removes a store from the contract: $missing",
            )
            .isEmpty()
    }

    @Test
    fun `every ApprovalContinuationStore implementation has a valid TCK runner in its module`() {
        val unenrolled = scanner.storeModules(repoRoot).flatMap { (module, implementations) ->
            implementations
                .filter { storeName -> !scanner.hasValidRunner(repoRoot, module, storeName) }
                .map { store -> "$module/$store" }
        }
        assertThat(unenrolled)
            .withFailMessage(
                "ApprovalContinuationStore implementations without a <Store>TckTest runner extending " +
                    "ApprovalContinuationStoreTck in the same module: $unenrolled. " +
                    "Adding an ApprovalContinuationStore without enrolling it in the compatibility " +
                    "contract must make a gate fail.",
            )
            .isEmpty()
    }

    // ── probe tests for the scanner against this family ─────────────

    @Test
    fun `body-less ApprovalContinuationStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.core.approval.ApprovalContinuationStore
            class RedisContinuationStore(private val delegate: ApprovalContinuationStore) :
                ApprovalContinuationStore by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).containsExactly("RedisContinuationStore")
    }

    @Test
    fun `runner file must actually subclass ApprovalContinuationStoreTck`() {
        val fake = tempSourceFile("class RedisContinuationStoreTckTest")
        val real = tempSourceFile("class RedisContinuationStoreTckTest : ApprovalContinuationStoreTck() { }")
        assertThat(scanner.runnerSubclassesTck(fake, "RedisContinuationStore")).isFalse()
        assertThat(scanner.runnerSubclassesTck(real, "RedisContinuationStore")).isTrue()
    }

    @Test
    fun `exception classes whose names contain the interface never count as implementations`() {
        val file = tempSourceFile(
            """
            package probe
            sealed class ApprovalContinuationStoreException(
                open val approvalId: String,
            ) : RuntimeException()

            class ApprovalContinuationNotFoundException(
                override val approvalId: String,
            ) : ApprovalContinuationStoreException(approvalId)
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).isEmpty()
    }

    private fun tempSourceFile(content: String): File {
        val dir = Files.createTempDirectory("tck-enrollment-probe-").toFile()
        dir.deleteOnExit()
        return File(dir, "Probe.kt").apply { writeText(content) }
    }
}
