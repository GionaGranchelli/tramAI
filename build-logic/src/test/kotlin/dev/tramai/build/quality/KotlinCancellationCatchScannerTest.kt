package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinCancellationCatchScannerTest {

    @Test
    fun `detects catch (e Exception) syntax`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    logError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch (e: Exception)")
        assertEquals("Exception", findings.first().catchType)
    }

    @Test
    fun `detects catch (_ Throwable) syntax`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (_: Throwable) {
                    // swallow silently
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch (_: Throwable)")
        assertEquals("Throwable", findings.first().catchType)
    }

    @Test
    fun `detects runCatching`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                runCatching {
                    doSomething()
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find runCatching")
    }

    @Test
    fun `classifies explicit cancellation rethrow as accepted`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    handleError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertEquals("accepted", findings.first().risk,
            "Cancellation rethrow inside nested if-block should be accepted")
    }

    @Test
    fun `classifies suspend catch without rethrow as critical`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    handleError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty())
        assertEquals("critical", findings.first().risk)
    }

    @Test
    fun `genuine multiline catch preserves suspend position`() {
        // A real multiline catch declaration split across three lines
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (
                    e: Exception
                ) {
                    handleError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find multiline catch")
        assertEquals("Exception", findings.first().catchType)
        assertEquals("riskyOperation", findings.first().function,
            "Should identify enclosing function despite multiline catch")
        assertEquals("critical", findings.first().risk,
            "Suspend catch without rethrow should be critical even when multiline")
    }

    @Test
    fun `nested cancellation rethrow is accepted`() {
        // CancellationException check and throw on separate lines in nested block
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) {
                        throw e
                    }
                    handleError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertEquals("accepted", findings.first().risk,
            "Nested cancellation rethrow on separate lines should be accepted")
    }

    @Test
    fun `handles qualified exception names`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: java.lang.IllegalArgumentException) {
                    handleError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isEmpty(), "Qualified non-base exception should not match broad catch pattern")
    }

    @Test
    fun `runCatching outside suspend is medium risk`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            fun regularFunction() {
                runCatching {
                    doSomething()
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty())
        assertEquals("medium", findings.first().risk, "runCatching outside suspend should be medium")
    }

    @Test
    fun `catch in string literal is not detected`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                val msg = "catch (e: Exception)"
                doSomething()
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isEmpty(), "String literal should not produce a finding")
    }

    // ── Regression tests requested in PR #203 review ──

    @Test
    fun `project and directory contexts produce identical findings`(@TempDir tempDir: Path) {
        val sourceCode = """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) {
                        throw e
                    }
                    handleError(e)
                }
            }
            
            fun regularFunction() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    logError(e)
                }
            }
            
            suspend fun anotherSuspend() {
                runCatching {
                    doSomething()
                }
            }
        """.trimIndent()

        // Create a minimal module structure in temp dir
        val srcDir = tempDir.resolve("src/main/kotlin").toFile()
        srcDir.mkdirs()
        val sourceFile = File(srcDir, "TestModule.kt")
        sourceFile.writeText(sourceCode)

        // Create a minimal build file so directory discovery finds it
        File(tempDir.toFile(), "build.gradle.kts").writeText("")
        // Create minimal settings
        File(tempDir.toFile(), "settings.gradle.kts").writeText(
            """include("test-module")"""
        )
        val moduleDir = tempDir.resolve("test-module").toFile()
        moduleDir.mkdirs()
        File(moduleDir, "build.gradle.kts").writeText("")
        val moduleSrcDir = File(moduleDir, "src/main/kotlin")
        moduleSrcDir.mkdirs()
        File(moduleSrcDir, "TestModule.kt").writeText(sourceCode)

        // Scan via CancellationCatchInventory with MeasurementContext.fromDirectory
        val dirCtx = MeasurementContext.fromDirectory(tempDir.toFile())
        val dirFindings = CancellationCatchInventory(dirCtx).inventory()
            .filter { it.file.contains("TestModule.kt") }
            .sortedBy { "${it.function}::${it.catchType}" }

        // Direct scan of the same source (bypassing inventory, pure scanner)
        val directFindings = KotlinCancellationCatchScanner.scan(
            sourceCode, "test-module", "test-module/src/main/kotlin/TestModule.kt"
        ).sortedBy { "${it.function}::${it.catchType}" }

        assertEquals(directFindings.size, dirFindings.size,
            "Directory-mode inventory should find same number of catches as direct scan")
        for (i in directFindings.indices) {
            assertEquals(directFindings[i].function, dirFindings[i].function)
            assertEquals(directFindings[i].catchType, dirFindings[i].catchType)
            assertEquals(directFindings[i].risk, dirFindings[i].risk,
                "Risk mismatch for ${directFindings[i].function}::${directFindings[i].catchType}")
        }
    }
}
