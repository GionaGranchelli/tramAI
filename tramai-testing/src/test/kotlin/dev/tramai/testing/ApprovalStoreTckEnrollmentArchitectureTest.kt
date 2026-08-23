package dev.tramai.testing

import java.io.File
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1a architecture guard: every concrete [dev.tramai.core.approval.ApprovalStore]
 * implementation must be enrolled in the shared ApprovalStore compatibility
 * contract (tramai-testing testFixtures).
 *
 * Two properties hold:
 *
 * 1. The three roadmap implementations (pinned allowlist) must each ship a
 *    `<Implementation>TckTest` runner. Deleting or renaming a runner breaks
 *    the gate.
 * 2. **Every concrete `ApprovalStore` implementation** in any module's main
 *    source set must have a runner named after it (`<Store>TckTest`), in the
 *    same module, that actually extends [dev.tramai.testing.persistence.approval.ApprovalStoreTck].
 *    Adding a store without a runner fails the gate — the phrase
 *    "future stores must pass the TCK" is otherwise documentation, not
 *    architecture. A file named `<Store>TckTest.kt` that does not subclass
 *    the TCK does not count. The scanner is source-shape based (class/object
 *    declarations with or without bodies, single-line or multiline, with or
 *    without visibility/`open` modifiers) — not a type resolver.
 *
 * The runner file IS the reviewed contract matrix: it wires the store to the
 * shared [dev.tramai.testing.persistence.approval.ApprovalStoreTck].
 */
class ApprovalStoreTckEnrollmentArchitectureTest {

    private val scanner = StoreEnrollmentScanner("ApprovalStore", "ApprovalStoreTck")

    private val repoRoot: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private val expectedRunners = setOf(
        "InMemoryApprovalStoreTckTest",
        "FileApprovalStoreTckTest",
        "JdbcApprovalStoreTckTest",
    )

    @Test
    fun `every roadmap ApprovalStore ships a TCK runner`() {
        val missing = expectedRunners.filter { runnerName -> scanner.findRunnerFile(repoRoot, runnerName) == null }
        assertThat(missing)
            .withFailMessage(
                "Pinned ApprovalStore TCK runners missing. The compatibility contract is " +
                    "reviewed per runner; deleting a runner silently removes a store from the contract: $missing",
            )
            .isEmpty()
    }

    @Test
    fun `every ApprovalStore implementation has a valid TCK runner in its module`() {
        val unenrolled = scanner.storeModules(repoRoot).flatMap { (module, implementations) ->
            implementations
                .filter { storeName -> !scanner.hasValidRunner(repoRoot, module, storeName) }
                .map { store -> "$module/$store" }
        }
        assertThat(unenrolled)
            .withFailMessage(
                "ApprovalStore implementations without a <Store>TckTest runner extending " +
                    "ApprovalStoreTck in the same module: $unenrolled. " +
                    "Adding an ApprovalStore without enrolling it in the compatibility contract " +
                    "must make a gate fail.",
            )
            .isEmpty()
    }

    // ── probe tests for the scanner itself ──────────────────────────

    @Test
    fun `body-less ApprovalStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.core.approval.ApprovalStore
            class RedisApprovalStore(private val delegate: ApprovalStore) : ApprovalStore by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).containsExactly("RedisApprovalStore")
    }

    @Test
    fun `multiline ApprovalStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            class MultiLineApprovalStore(
                private val clock: java.time.Clock,
            ) : ApprovalStore {
                override suspend fun create(request: ApprovalRequest): ApprovalRequest = request
            }
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).containsExactly("MultiLineApprovalStore")
    }

    @Test
    fun `multiline body-less ApprovalStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.core.approval.ApprovalStore
            class RedisApprovalStore(
                private val delegate: ApprovalStore,
            ) : ApprovalStore by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).containsExactly("RedisApprovalStore")
    }

    @Test
    fun `modifier-prefixed and colon-continued body-less implementations are detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.core.approval.ApprovalStore
            internal class RedisApprovalStore(
                private val delegate: ApprovalStore,
            )
                : ApprovalStore by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).containsExactly("RedisApprovalStore")
    }

    @Test
    fun `runner file must actually subclass ApprovalStoreTck`() {
        val fake = tempSourceFile("class RedisApprovalStoreTckTest")
        val real = tempSourceFile("class RedisApprovalStoreTckTest : ApprovalStoreTck() { }")
        assertThat(scanner.runnerSubclassesTck(fake, "RedisApprovalStore")).isFalse()
        assertThat(scanner.runnerSubclassesTck(real, "RedisApprovalStore")).isTrue()
    }

    private fun tempSourceFile(content: String): File {
        val dir = Files.createTempDirectory("tck-enrollment-probe-").toFile()
        dir.deleteOnExit()
        return File(dir, "Probe.kt").apply { writeText(content) }
    }
}
