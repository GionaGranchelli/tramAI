package dev.tramai.testing

import java.io.File
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1e architecture guard: every concrete
 * [dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore]
 * implementation must be enrolled in the shared outbox compatibility
 * contract (tramai-testing testFixtures).
 *
 * Same two properties as the earlier guards (#267/#269/#270/#271): the three
 * roadmap runners are pinned by name, and every concrete implementation in
 * any module's main source set must ship a `<Store>TckTest` runner in the
 * same module that actually extends
 * [dev.tramai.testing.persistence.outbox.SovereignOpsAuditOutboxStoreTck].
 */
class SovereignOpsAuditOutboxStoreTckEnrollmentArchitectureTest {

    private val scanner = StoreEnrollmentScanner("SovereignOpsAuditOutboxStore", "SovereignOpsAuditOutboxStoreTck")

    private val repoRoot: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private val expectedRunners = setOf(
        "InMemorySovereignOpsAuditOutboxStoreTckTest",
        "FileSovereignOpsAuditOutboxStoreTckTest",
        "JdbcSovereignOpsAuditOutboxStoreTckTest",
    )

    @Test
    fun `every roadmap SovereignOpsAuditOutboxStore ships a TCK runner`() {
        val missing = expectedRunners.filter { runnerName -> scanner.findRunnerFile(repoRoot, runnerName) == null }
        assertThat(missing)
            .withFailMessage(
                "Pinned SovereignOpsAuditOutboxStore TCK runners missing. The compatibility contract is " +
                    "reviewed per runner; deleting a runner silently removes a store from the contract: $missing",
            )
            .isEmpty()
    }

    @Test
    fun `every SovereignOpsAuditOutboxStore implementation has a valid TCK runner in its module`() {
        val unenrolled = scanner.storeModules(repoRoot).flatMap { (module, implementations) ->
            implementations
                .filter { storeName -> !scanner.hasValidRunner(repoRoot, module, storeName) }
                .map { store -> "$module/$store" }
        }
        assertThat(unenrolled)
            .withFailMessage(
                "SovereignOpsAuditOutboxStore implementations without a <Store>TckTest runner extending " +
                    "SovereignOpsAuditOutboxStoreTck in the same module: $unenrolled. " +
                    "Adding a SovereignOpsAuditOutboxStore without enrolling it in the compatibility " +
                    "contract must make a gate fail.",
            )
            .isEmpty()
    }

    // ── probe tests for the scanner against this family ─────────────

    @Test
    fun `body-less SovereignOpsAuditOutboxStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
            class RedisAuditOutboxStore(private val delegate: SovereignOpsAuditOutboxStore) :
                SovereignOpsAuditOutboxStore by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).containsExactly("RedisAuditOutboxStore")
    }

    @Test
    fun `runner file must actually subclass SovereignOpsAuditOutboxStoreTck`() {
        val fake = tempSourceFile("class RedisAuditOutboxStoreTckTest")
        val real = tempSourceFile("class RedisAuditOutboxStoreTckTest : SovereignOpsAuditOutboxStoreTck() { }")
        assertThat(scanner.runnerSubclassesTck(fake, "RedisAuditOutboxStore")).isFalse()
        assertThat(scanner.runnerSubclassesTck(real, "RedisAuditOutboxStore")).isTrue()
    }

    @Test
    fun `exception class whose name contains the interface does not count as an implementation`() {
        val file = tempSourceFile(
            """
            package probe
            class SovereignOpsAuditOutboxStoreException(val code: String) : RuntimeException()
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).isEmpty()
    }

    @Test
    fun `constructor parameter never counts as an implementation`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
            class Holder(val store: SovereignOpsAuditOutboxStore)
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
