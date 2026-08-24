package dev.tramai.testing

import java.io.File
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.1h architecture guard: every concrete
 * [dev.tramai.core.memory.ChatMemoryStore] implementation must be enrolled
 * in the shared chat-memory compatibility contract (tramai-testing
 * testFixtures). A future File/Mongo/Dynamo chat-memory store cannot merge
 * without enrollment.
 */
class ChatMemoryStoreTckEnrollmentArchitectureTest {

    private val scanner = StoreEnrollmentScanner("ChatMemoryStore", "ChatMemoryStoreTck")

    private val repoRoot: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }

    private val expectedRunners = setOf(
        "JdbcChatMemoryStoreTckTest",
        "RedisChatMemoryStoreTckTest",
    )

    @Test
    fun `every roadmap ChatMemoryStore ships a TCK runner`() {
        val missing = expectedRunners.filter { runnerName -> scanner.findRunnerFile(repoRoot, runnerName) == null }
        assertThat(missing)
            .withFailMessage(
                "Pinned ChatMemoryStore TCK runners missing. The compatibility contract is " +
                    "reviewed per runner; deleting a runner silently removes a store from the contract: $missing",
            )
            .isEmpty()
    }

    @Test
    fun `every ChatMemoryStore implementation has a valid TCK runner in its module`() {
        val unenrolled = scanner.storeModules(repoRoot).flatMap { (module, implementations) ->
            implementations
                .filter { storeName -> !scanner.hasValidRunner(repoRoot, module, storeName) }
                .map { store -> "$module/$store" }
        }
        assertThat(unenrolled)
            .withFailMessage(
                "ChatMemoryStore implementations without a <Store>TckTest runner extending " +
                    "ChatMemoryStoreTck in the same module: $unenrolled. " +
                    "Adding a ChatMemoryStore without enrolling it in the compatibility " +
                    "contract must make a gate fail.",
            )
            .isEmpty()
    }

    // ── probe tests for the scanner against this family ─────────────

    @Test
    fun `body-less ChatMemoryStore implementation is detected`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.core.memory.ChatMemoryStore
            class FileChatMemoryStore(private val delegate: ChatMemoryStore) :
                ChatMemoryStore by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).containsExactly("FileChatMemoryStore")
    }

    @Test
    fun `runner file must actually subclass ChatMemoryStoreTck`() {
        val fake = tempSourceFile("class MongoChatMemoryStoreTckTest")
        val real = tempSourceFile("class MongoChatMemoryStoreTckTest : ChatMemoryStoreTck() { }")
        assertThat(scanner.runnerSubclassesTck(fake, "MongoChatMemoryStore")).isFalse()
        assertThat(scanner.runnerSubclassesTck(real, "MongoChatMemoryStore")).isTrue()
    }

    @Test
    fun `private decorator is not a family member`() {
        val file = tempSourceFile(
            """
            package probe
            import dev.tramai.core.memory.ChatMemoryStore
            private class FencedChatMemoryStore(private val delegate: ChatMemoryStore) :
                ChatMemoryStore by delegate
            """.trimIndent(),
        )
        assertThat(scanner.implementationsIn(file)).isEmpty()
    }

    private fun tempSourceFile(content: String): File =
        Files.createTempFile("chat-memory-enrollment-probe-", ".kt").toFile().apply {
            writeText(content)
            deleteOnExit()
        }
}
