package dev.tramai.testing

import java.io.File
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1d architecture guard: every concrete [dev.tramai.security.audit.AuditStore]
 * implementation must be enrolled in the shared AuditStore compatibility
 * contract (tramai-testing testFixtures).
 *
 * Same two properties as the earlier guards (#267/#269/#270): the three
 * roadmap runners are pinned by name, and every concrete implementation in
 * any module's main source set must ship a `<Store>TckTest` runner in the
 * same module that actually extends [dev.tramai.testing.persistence.audit.AuditStoreTck].
 */
class AuditStoreTckEnrollmentArchitectureTest {

    private val scanner = StoreEnrollmentScanner("AuditStore", "AuditStoreTck")

    private val repoRoot: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private val expectedRunners = setOf(
        "InMemoryAuditStoreTckTest",
        "FileAuditStoreTckTest",
        "JdbcAuditStoreTckTest",
    )

    @Test
    fun `every roadmap AuditStore ships a TCK runner`() {
        val missing = expectedRunners.filter { runnerName -> scanner.findRunnerFile(repoRoot, runnerName) == null }
        assertThat(missing)
            .withFailMessage(
                "Pinned AuditStore TCK runners missing. The compatibility contract is " +
                    "reviewed per runner; deleting a runner silently removes a store from the contract: $missing",
            )
            .isEmpty()
    }

    @Test
    fun `every AuditStore implementation has a valid TCK runner in its module`() {
        val unenrolled = scanner.storeModules(repoRoot).flatMap { (module, implementations) ->
            implementations
                .filter { storeName -> !scanner.hasValidRunner(repoRoot, module, storeName) }
                .map { store -> "$module/$store" }
        }
        assertThat(unenrolled)
            .withFailMessage(
                "AuditStore implementations without a <Store>TckTest runner extending " +
                    "AuditStoreTck in the same module: $unenrolled. " +
                    "Adding an AuditStore without enrolling it in the compatibility " +
                    "contract must make a gate fail.",
            )
            .isEmpty()
    }

    // ── probe tests for the scanner against this family ─────────────

    @Test
    fun `body-less AuditStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.security.audit.AuditStore
            class RedisAuditStore(private val delegate: AuditStore) :
                AuditStore by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).containsExactly("RedisAuditStore")
    }

    @Test
    fun `runner file must actually subclass AuditStoreTck`() {
        val fake = tempSourceFile("class RedisAuditStoreTckTest")
        val real = tempSourceFile("class RedisAuditStoreTckTest : AuditStoreTck() { }")
        assertThat(scanner.runnerSubclassesTck(fake, "RedisAuditStore")).isFalse()
        assertThat(scanner.runnerSubclassesTck(real, "RedisAuditStore")).isTrue()
    }

    @Test
    fun `exception class whose name contains the interface does not count as an implementation`() {
        val file = tempSourceFile(
            """
            package probe
            class AuditStoreException(val code: String) : RuntimeException()
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).isEmpty()
    }

    @Test
    fun `constructor parameter never counts as an implementation`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.security.audit.AuditStore
            class Holder(val store: AuditStore)
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
