package dev.tramai.testing

import java.io.File
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1c architecture guard: every concrete [dev.tramai.engine.SuspendedInvocationStore]
 * implementation must be enrolled in the shared SuspendedInvocationStore
 * compatibility contract (tramai-testing testFixtures).
 *
 * Same two properties as the Approval guards (#267/#269): the three roadmap
 * runners are pinned by name, and every concrete implementation in any
 * module's main source set must ship a `<Store>TckTest` runner in the same
 * module that actually extends
 * [dev.tramai.testing.persistence.engine.SuspendedInvocationStoreTck].
 */
class SuspendedInvocationStoreTckEnrollmentArchitectureTest {

    private val scanner = StoreEnrollmentScanner("SuspendedInvocationStore", "SuspendedInvocationStoreTck")

    private val repoRoot: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private val expectedRunners = setOf(
        "InMemorySuspendedInvocationStoreTckTest",
        "FileSuspendedInvocationStoreTckTest",
        "JdbcSuspendedInvocationStoreTckTest",
    )

    @Test
    fun `every roadmap SuspendedInvocationStore ships a TCK runner`() {
        val missing = expectedRunners.filter { runnerName -> scanner.findRunnerFile(repoRoot, runnerName) == null }
        assertThat(missing)
            .withFailMessage(
                "Pinned SuspendedInvocationStore TCK runners missing. The compatibility contract is " +
                    "reviewed per runner; deleting a runner silently removes a store from the contract: $missing",
            )
            .isEmpty()
    }

    @Test
    fun `every SuspendedInvocationStore implementation has a valid TCK runner in its module`() {
        val unenrolled = scanner.storeModules(repoRoot).flatMap { (module, implementations) ->
            implementations
                .filter { storeName -> !scanner.hasValidRunner(repoRoot, module, storeName) }
                .map { store -> "$module/$store" }
        }
        assertThat(unenrolled)
            .withFailMessage(
                "SuspendedInvocationStore implementations without a <Store>TckTest runner extending " +
                    "SuspendedInvocationStoreTck in the same module: $unenrolled. " +
                    "Adding a SuspendedInvocationStore without enrolling it in the compatibility " +
                    "contract must make a gate fail.",
            )
            .isEmpty()
    }

    // ── probe tests for the scanner against this family ─────────────

    @Test
    fun `body-less SuspendedInvocationStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.engine.SuspendedInvocationStore
            class RedisSuspendedStore(private val delegate: SuspendedInvocationStore) :
                SuspendedInvocationStore by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).containsExactly("RedisSuspendedStore")
    }

    @Test
    fun `runner file must actually subclass SuspendedInvocationStoreTck`() {
        val fake = tempSourceFile("class RedisSuspendedStoreTckTest")
        val real = tempSourceFile("class RedisSuspendedStoreTckTest : SuspendedInvocationStoreTck() { }")
        assertThat(scanner.runnerSubclassesTck(fake, "RedisSuspendedStore")).isFalse()
        assertThat(scanner.runnerSubclassesTck(real, "RedisSuspendedStore")).isTrue()
    }

    @Test
    fun `exception class whose name contains the interface does not count as an implementation`() {
        val file = tempSourceFile(
            """
            package probe
            class SuspendedInvocationStoreException(val code: String) : RuntimeException()
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).isEmpty()
    }

    @Test
    fun `constructor parameter never counts as an implementation`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.engine.SuspendedInvocationStore
            class Holder(val store: SuspendedInvocationStore)
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
