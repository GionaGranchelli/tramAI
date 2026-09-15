package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JUnitTestSignatureVerifierTest {
    @TempDir
    lateinit var tempDir: Path

    private fun writeTest(
        fileName: String,
        content: String,
    ): Path {
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
        val violations =
            violationsFor(
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
        val violations =
            violationsFor(
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
        val violations =
            violationsFor(
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
        val violations =
            violationsFor(
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
        val violations =
            violationsFor(
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
        val violations =
            violationsFor(
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
        val violations =
            violationsFor(
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
        val violations =
            violationsFor(
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
        val content =
            """
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
        val violations =
            violationsFor(
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
        val violations =
            violationsFor(
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
        val violations =
            violationsFor(
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
    fun `accepts runBlocking with explicit Unit type argument on a continuation line`() {
        // Regression: the head of a multi-line expression body was read from the line
        // containing `=` only, so an empty head was rejected before the next line could be
        // appended. This is the documented Unit-safe form and must be accepted.
        val violations =
            violationsFor(
                """
                import kotlinx.coroutines.runBlocking
                import org.assertj.core.api.Assertions.assertThat

                class SampleTest {
                    @Test
                    fun `accept me`() =
                        runBlocking<Unit> {
                            assertThat(true).isTrue()
                        }
                }
                """.trimIndent(),
            )
        assertTrue(violations.isEmpty(), "expected no violations, got ${violations.map { it.functionName }}")
    }

    @Test
    fun `accepts explicit Unit return type on a continuation line`() {
        val violations =
            violationsFor(
                """
                import kotlinx.coroutines.runBlocking
                import org.assertj.core.api.Assertions.assertThat

                class SampleTest {
                    @Test
                    fun `accept me`(): Unit =
                        runBlocking {
                            assertThat(true).isTrue()
                        }
                }
                """.trimIndent(),
            )
        assertTrue(violations.isEmpty(), "expected no violations, got ${violations.map { it.functionName }}")
    }

    @Test
    fun `accepts bare Unit on a continuation line`() {
        val violations =
            violationsFor(
                """
                class SampleTest {
                    @Test
                    fun `accept me`() =
                        Unit
                }
                """.trimIndent(),
            )
        assertTrue(violations.isEmpty(), "expected no violations, got ${violations.map { it.functionName }}")
    }

    @Test
    fun `accepts multiline signature followed by Unit-safe head on a continuation line`() {
        val violations =
            violationsFor(
                """
                import kotlinx.coroutines.runBlocking
                import org.assertj.core.api.Assertions.assertThat
                import org.junit.jupiter.api.Test

                class SampleTest {
                    @Test
                    fun `accept me`(
                        someParameter: String,
                    ) =
                        runBlocking<Unit> {
                            assertThat(someParameter).isEqualTo("x")
                        }
                }
                """.trimIndent(),
            )
        assertTrue(violations.isEmpty(), "expected no violations, got ${violations.map { it.functionName }}")
    }

    @Test
    fun `accepts runTest on a continuation line`() {
        val violations =
            violationsFor(
                """
                import kotlinx.coroutines.test.runTest
                import org.assertj.core.api.Assertions.assertThat

                class SampleTest {
                    @Test
                    fun `accept me`() =
                        runTest {
                            assertThat(true).isTrue()
                        }
                }
                """.trimIndent(),
            )
        assertTrue(violations.isEmpty(), "expected no violations, got ${violations.map { it.functionName }}")
    }

    @Test
    fun `rejects continuation-line expression body ending in a chainable assertion`() {
        // The critical adversarial case: waiting for a continuation line must not become a
        // fail-open. A non-Unit head on the next line is still a violation.
        val violations =
            violationsFor(
                """
                import kotlinx.coroutines.runBlocking
                import org.assertj.core.api.Assertions.assertThat

                class SampleTest {
                    @Test
                    fun `reject me`() =
                        runBlocking {
                            assertThat(true).isTrue()
                        }
                }
                """.trimIndent(),
            )
        assertTrue(violations.size == 1, "expected 1 violation, got ${violations.size}")
        assertEquals("`reject me`", violations.single().functionName)
    }

    @Test
    fun `rejects continuation-line bare assertion expression body`() {
        val violations =
            violationsFor(
                """
                import org.assertj.core.api.Assertions.assertThat

                class SampleTest {
                    @Test
                    fun `reject me`() =
                        assertThat(1).isEqualTo(1)
                }
                """.trimIndent(),
            )
        assertTrue(violations.size == 1, "expected 1 violation, got ${violations.size}")
        assertEquals("`reject me`", violations.single().functionName)
    }

    @Test
    fun `rejects multiline parameter signature with non-Unit head on a continuation line`() {
        val violations =
            violationsFor(
                """
                import kotlinx.coroutines.runBlocking
                import org.assertj.core.api.Assertions.assertThat

                class SampleTest {
                    @Test
                    fun `reject me`(
                        someParameter: String,
                    ) =
                        runBlocking {
                            assertThat(someParameter).isEqualTo("x")
                        }
                }
                """.trimIndent(),
            )
        assertTrue(violations.size == 1, "expected 1 violation, got ${violations.size}")
        assertEquals("`reject me`", violations.single().functionName)
    }

    @Test
    fun `ignores non-test expression-bodied functions`() {
        val violations =
            violationsFor(
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
