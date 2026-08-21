package dev.tramai.build.quality

import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class JUnitTestSignatureVerifierTest {

    @TempDir
    lateinit var tempDir: Path

    private fun writeTest(fileName: String, content: String): Path {
        val dir = Files.createDirectories(tempDir.resolve("src/test/kotlin/demo"))
        val file = dir.resolve(fileName)
        Files.writeString(file, content)
        return file
    }

    private fun violationsFor(content: String): List<JUnitTestSignatureVerifier.Violation> {
        writeTest("SampleTest.kt", content)
        return JUnitTestSignatureVerifier.scan(tempDir)
    }

    @Test
    fun `rejects expression body ending in a chainable assertion`() {
        val violations = violationsFor(
            """
            import kotlinx.coroutines.runBlocking
            import org.assertj.core.api.Assertions.assertThat

            class SampleTest {
                @Test
                fun `reject me`() = runBlocking {
                    assertThat(true).isTrue()
                }
            }
            """.trimIndent(),
        )
        assertTrue(violations.size == 1, "expected 1 violation, got ${violations.size}")
        assertEquals("`reject me`", violations.single().functionName)
    }

    @Test
    fun `rejects bare assertion expression body`() {
        val violations = violationsFor(
            """
            import org.assertj.core.api.Assertions.assertThat

            class SampleTest {
                @Test
                fun `reject me`() = assertThat(1).isEqualTo(1)
            }
            """.trimIndent(),
        )
        assertTrue(violations.size == 1, "expected 1 violation, got ${violations.size}")
    }

    @Test
    fun `rejects assertThatThrownBy expression body`() {
        val violations = violationsFor(
            """
            import org.assertj.core.api.Assertions.assertThatThrownBy

            class SampleTest {
                @Test
                fun `reject me`() = assertThatThrownBy {
                    throw IllegalStateException("boom")
                }.isInstanceOf(IllegalStateException::class.java)
            }
            """.trimIndent(),
        )
        assertTrue(violations.size == 1, "expected 1 violation, got ${violations.size}")
    }

    @Test
    fun `accepts block-bodied coroutine test`() {
        val violations = violationsFor(
            """
            import kotlinx.coroutines.runBlocking
            import org.assertj.core.api.Assertions.assertThat

            class SampleTest {
                @Test
                fun `accept me`() {
                    runBlocking {
                        assertThat(true).isTrue()
                    }
                }
            }
            """.trimIndent(),
        )
        assertTrue(violations.isEmpty(), "expected no violations, got ${violations.map { it.functionName }}")
    }

    @Test
    fun `accepts runBlocking with explicit Unit type argument`() {
        val violations = violationsFor(
            """
            import kotlinx.coroutines.runBlocking
            import org.assertj.core.api.Assertions.assertThat

            class SampleTest {
                @Test
                fun `accept me`() = runBlocking<Unit> {
                    assertThat(true).isTrue()
                }
            }
            """.trimIndent(),
        )
        assertTrue(violations.isEmpty(), "expected no violations, got ${violations.map { it.functionName }}")
    }

    @Test
    fun `accepts explicit Unit return type`() {
        val violations = violationsFor(
            """
            class SampleTest {
                @Test
                fun `accept me`(): Unit = runBlocking {
                    assertThat(true).isTrue()
                }
            }
            """.trimIndent(),
        )
        assertTrue(violations.isEmpty(), "expected no violations, got ${violations.map { it.functionName }}")
    }

    @Test
    fun `rejects same-line expression body`() {
        val violations = violationsFor(
            """
            import org.assertj.core.api.Assertions.assertThatThrownBy

            class SampleTest {
                @Test fun `reject me`() = assertThatThrownBy {
                    throw IllegalStateException("boom")
                }.isInstanceOf(IllegalStateException::class.java)
            }
            """.trimIndent(),
        )
        assertTrue(violations.size == 1, "expected 1 violation, got ${violations.size}")
        assertEquals("`reject me`", violations.single().functionName)
    }

    @Test
    fun `accepts same-line runTest expression body`() {
        val violations = violationsFor(
            """
            import kotlinx.coroutines.test.runTest
            import org.assertj.core.api.Assertions.assertThat

            class SampleTest {
                @Test fun `accept me`() = runTest {
                    assertThat(true).isTrue()
                }
            }
            """.trimIndent(),
        )
        assertTrue(violations.isEmpty(), "expected no violations, got ${violations.map { it.functionName }}")
    }

    @Test
    fun `ignores expression bodies inside raw string fixtures`() {
        val content = """
            import org.assertj.core.api.Assertions.assertThat

            class SampleTest {
                val fixture = ${"\"\"\""}
                    @Test fun `not real code`() = assertThatThrownBy {
                        throw IllegalStateException("boom")
                    }
                ${"\"\"\""}

                @Test
                fun `real test`() {
                    assertThat(true).isTrue()
                }
            }
            """.trimIndent()
        val violations = violationsFor(content)
        assertTrue(violations.isEmpty(), "expected no violations, got ${violations.map { it.functionName }}")
    }

    @Test
    fun `rejects multiline parameter signature`() {
        val violations = violationsFor(
            """
            import kotlinx.coroutines.runBlocking
            import org.assertj.core.api.Assertions.assertThat

            class SampleTest {
                @Test
                fun `reject me`(
                    someParameter: String,
                ) = runBlocking {
                    assertThat(someParameter).isEqualTo("x")
                }
            }
            """.trimIndent(),
        )
        assertTrue(violations.size == 1, "expected 1 violation, got ${violations.size}")
        assertEquals("`reject me`", violations.single().functionName)
    }

    @Test
    fun `rejects multiline annotation between Test and fun`() {
        val violations = violationsFor(
            """
            import org.assertj.core.api.Assertions.assertThat

            class SampleTest {
                @Test
                @SomeAnnotation(
                    value = "x",
                )
                fun `reject me`() = assertThat(true).isTrue()
            }
            """.trimIndent(),
        )
        assertTrue(violations.size == 1, "expected 1 violation, got ${violations.size}")
        assertEquals("`reject me`", violations.single().functionName)
    }

    @Test
    fun `accepts multiline parameter signature with block body`() {
        val violations = violationsFor(
            """
            import kotlinx.coroutines.runBlocking
            import org.assertj.core.api.Assertions.assertThat

            class SampleTest {
                @Test
                fun `accept me`(
                    someParameter: String,
                ) {
                    runBlocking {
                        assertThat(someParameter).isEqualTo("x")
                    }
                }
            }
            """.trimIndent(),
        )
        assertTrue(violations.isEmpty(), "expected no violations, got ${violations.map { it.functionName }}")
    }

    @Test
    fun `ignores non-test expression-bodied functions`() {
        val violations = violationsFor(
            """
            class SampleTest {
                private fun helper() = assertThat(1).isEqualTo(1)

                @Test
                fun `accept me`() {
                    helper()
                }
            }
            """.trimIndent(),
        )
        assertTrue(violations.isEmpty(), "expected no violations, got ${violations.map { it.functionName }}")
    }
}
