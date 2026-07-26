package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // ── Negative tests for rethrow detection (PR #203 re-review) ──

    @Test
    fun `cancellation check followed by throw DomainException is not accepted`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        auditCancellation(e)
                    }
                    throw DomainOperationException(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertEquals("high", findings.first().risk,
            "Cancellation check followed by DomainException throw should be high, not accepted")
    }

    @Test
    fun `cancellation check followed by unrelated throw is not accepted`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        logCancellation(e)
                    }
                    throw IllegalStateException("unexpected")
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertEquals("high", findings.first().risk,
            "Cancellation check followed by unrelated throw should be high, not accepted")
    }

    @Test
    fun `identifier containing rethrow does not trigger rethrow detection`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        rethrowPolicy.record(e)
                    }
                    handleError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertNotEquals("accepted", findings.first().risk,
            "RethrowPolicy reference should not be accepted as cancellation rethrow")
    }

    @Test
    fun `CancellationException in comment is not treated as rethrow check`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    // need to handle CancellationException here
                    throw DomainException(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertEquals("high", findings.first().risk,
            "CancellationException in comment should not elevate to accepted")
    }

    @Test
    fun `CancellationException in string is not treated as rethrow check`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    val msg = "CancellationException occurred"
                    throw DomainException(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertEquals("high", findings.first().risk,
            "CancellationException in string should not elevate to accepted")
    }

    @Test
    fun `rethrowIfCancellation extension is recognized as accepted`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    handleError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertEquals("accepted", findings.first().risk,
            "rethrowIfCancellation() should be recognized as accepted risk")
    }

    @Test
    fun `rethrowIfCancellation with different variable name is recognized`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (error: Throwable) {
                    error.rethrowIfCancellation()
                    throw ProviderException(error)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertEquals("accepted", findings.first().risk,
            "rethrowIfCancellation() with different variable name should be accepted")
    }

    @Test
    fun `rethrowIfCancellation with trailing whitespace in parens is recognized`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    e.rethrowIfCancellation( )
                    handleError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertEquals("accepted", findings.first().risk,
            "rethrowIfCancellation() with whitespace should be accepted")
    }

    @Test
    fun `different variable rethrowIfCancellation is not accepted`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    val other = RuntimeException()
                    other.rethrowIfCancellation()
                    handleError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertNotEquals("accepted", findings.first().risk,
            "rethrowIfCancellation() on different variable should NOT be accepted")
    }

    @Test
    fun `throwing caught variable in unrelated condition is not accepted`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun run() {
                try {
                    work()
                } catch (e: Exception) {
                    if (e is CancellationException) audit(e)
                    if (shouldRethrow()) throw e
                    handle(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertNotEquals("accepted", findings.first().risk,
            "throw e outside cancellation branch should not be accepted")
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

    // ─── Deviation scope matching tests ───

    @Test
    fun `global wildcard covers any scope`() {
        val scope = DeviationParser.DeviationScope(null, null, null, isWildcard = true)
        assertTrue(scope.covers(DeviationParser.FindingScope(null, null, null)))
        assertTrue(scope.covers(DeviationParser.FindingScope(":tramai-engine", null, null)))
        assertTrue(scope.covers(DeviationParser.FindingScope(":examples:tool-governance", null, null)))
    }

    @Test
    fun `prefix wildcard matches tramai modules`() {
        val scope = DeviationParser.DeviationScope(":tramai-", null, null, isWildcard = true)
        assertTrue(scope.covers(DeviationParser.FindingScope(":tramai-engine", null, null)))
        assertTrue(scope.covers(DeviationParser.FindingScope(":tramai-core", null, null)))
        assertTrue(scope.covers(DeviationParser.FindingScope(":tramai-bom", null, null)))
        assertTrue(scope.covers(DeviationParser.FindingScope(":tramai-provider-openai", null, null)))
        // Should NOT match non-tramai modules
        assertTrue(!scope.covers(DeviationParser.FindingScope(":examples:tool-governance", null, null)))
        assertTrue(!scope.covers(DeviationParser.FindingScope(":spring-boot-starter", null, null)))
    }

    @Test
    fun `prefix wildcard matches provider modules`() {
        val scope = DeviationParser.DeviationScope(":tramai-provider-", null, null, isWildcard = true)
        assertTrue(scope.covers(DeviationParser.FindingScope(":tramai-provider-openai", null, null)))
        assertTrue(scope.covers(DeviationParser.FindingScope(":tramai-provider-anthropic", null, null)))
        // Should NOT match non-provider tramai modules
        assertTrue(!scope.covers(DeviationParser.FindingScope(":tramai-engine", null, null)))
        assertTrue(!scope.covers(DeviationParser.FindingScope(":tramai-core", null, null)))
    }

    @Test
    fun `exact module scope does not match prefix`() {
        val scope = DeviationParser.DeviationScope(":tramai-engine", null, null, isWildcard = false)
        assertTrue(scope.covers(DeviationParser.FindingScope(":tramai-engine", null, null)))
        // Non-wildcard module scopes must be exact — no prefix matching
        assertTrue(!scope.covers(DeviationParser.FindingScope(":tramai-engine-core", null, null)))
        assertTrue(!scope.covers(DeviationParser.FindingScope(":tramai", null, null)))
    }

    @Test
    fun `file scope requires module AND path match`() {
        val scope = DeviationParser.DeviationScope(":tramai-engine", "src/main/Foo.kt", null, isWildcard = false)
        assertTrue(scope.covers(DeviationParser.FindingScope(
            ":tramai-engine", "src/main/Foo.kt", null)))
        // Wrong module
        assertTrue(!scope.covers(DeviationParser.FindingScope(
            ":tramai-core", "src/main/Foo.kt", null)))
        // Wrong path
        assertTrue(!scope.covers(DeviationParser.FindingScope(
            ":tramai-engine", "src/main/Bar.kt", null)))
    }

    @Test
    fun `declaration scope requires module path and declaration`() {
        val scope = DeviationParser.DeviationScope(
            ":tramai-engine", "src/main/Foo.kt", "TramaiInvocationHandler", isWildcard = false)
        assertTrue(scope.covers(DeviationParser.FindingScope(
            ":tramai-engine", "src/main/Foo.kt", "TramaiInvocationHandler")))
        // Wrong declaration
        assertTrue(!scope.covers(DeviationParser.FindingScope(
            ":tramai-engine", "src/main/Foo.kt", "OtherHandler")))
    }

    // ─── Deviation parser scope grammar tests ───

    @Test
    fun `parseScope handles all valid forms`() {
        val parser = DeviationParser(File("/nonexistent"))
        assertNotNull(parser.parseScope("*"))
        assertNotNull(parser.parseScope(":tramai-engine"))
        assertNotNull(parser.parseScope(":tramai-engine:src/main/Foo.kt"))
        assertNotNull(parser.parseScope(":tramai-engine:src/main/Foo.kt#Declaration"))
        assertNotNull(parser.parseScope(":tramai-*"))
        assertNotNull(parser.parseScope(":tramai-provider-*"))
        // Invalid scopes
        assertEquals(null, parser.parseScope(""))
        assertEquals(null, parser.parseScope("tramai-engine")) // no leading colon
    }

    @Test
    fun `parseScope wildcard stores correct prefix`() {
        val parser = DeviationParser(File("/nonexistent"))
        val scope = parser.parseScope(":tramai-provider-*")!!
        assertEquals(true, scope.isWildcard)
        assertEquals(":tramai-provider-", scope.modulePath)
    }

    // ─── Deviation baseline validation tests ───

    @Test
    fun `deviation baseline mismatch is detected`() {
        val parser = DeviationParser(File("/nonexistent"))

        // baseline != allowed deviation should work if baseline <= allowed
        assertNull(parser.validateBaselineMatch(1, 1))
        assertNull(parser.validateBaselineMatch(5, 5))
        // baseline mismatch should produce diagnostic
        val result = parser.validateBaselineMatch(1, 5)
        assertNotNull(result)
        assertEquals(DiagnosticCode.DEVIATION_BASELINE_MISMATCH, result?.code)
    }

    // ─── Count-delta multiset tests ───

    @Test
    fun `count delta detects added duplicates`() {
        val committed = listOf("id1", "id2", "id2")
        val current = listOf("id1", "id2", "id2", "id2")

        val committedCounts = committed.groupBy { it }.mapValues { it.value.size }
        val currentCounts = current.groupBy { it }.mapValues { it.value.size }

        val addedCounts = currentCounts.mapNotNull { (key, currentCount) ->
            val delta = currentCount - (committedCounts[key] ?: 0)
            if (delta > 0) key to delta else null
        }

        // id2 went from 2 to 3 occurrences
        assertEquals(1, addedCounts.size)
        assertEquals("id2", addedCounts[0].first)
        assertEquals(1, addedCounts[0].second)
    }

    @Test
    fun `count delta ignores unchanged identities`() {
        val committed = listOf("id1", "id1", "id2")
        val current = listOf("id1", "id1", "id2")

        val committedCounts = committed.groupBy { it }.mapValues { it.value.size }
        val currentCounts = current.groupBy { it }.mapValues { it.value.size }

        val addedCounts = currentCounts.mapNotNull { (key, currentCount) ->
            val delta = currentCount - (committedCounts[key] ?: 0)
            if (delta > 0) key to delta else null
        }

        assertEquals(0, addedCounts.size)
    }

    @Test
    fun `count delta detects new identities`() {
        val committed = listOf("id1")
        val current = listOf("id1", "id2", "id3")

        val committedCounts = committed.groupBy { it }.mapValues { it.value.size }
        val currentCounts = current.groupBy { it }.mapValues { it.value.size }

        val addedCounts = currentCounts.mapNotNull { (key, currentCount) ->
            val delta = currentCount - (committedCounts[key] ?: 0)
            if (delta > 0) key to delta else null
        }

        assertEquals(2, addedCounts.size) // id2 and id3 are new
        assertEquals(1, addedCounts.find { it.first == "id2" }?.second)
        assertEquals(1, addedCounts.find { it.first == "id3" }?.second)
    }

    // ── Negative tests: rethrowIfCancellation must be first executable statement ──

    @Test
    fun `rethrowIfCancellation after side effect is NOT accepted`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    logError(e)
                    e.rethrowIfCancellation()
                    handleError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertNotEquals("accepted", findings.first().risk,
            "rethrowIfCancellation() after a side effect should NOT be accepted")
    }

    @Test
    fun `rethrowIfCancellation inside string is not recognized as helper`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    val msg = "e.rethrowIfCancellation()"
                    handleError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertNotEquals("accepted", findings.first().risk,
            "rethrowIfCancellation() inside string should NOT be accepted")
    }

    @Test
    fun `rethrowIfCancellation inside conditional is NOT accepted as first statement`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    if (someCondition) {
                        e.rethrowIfCancellation()
                    }
                    handleError(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertNotEquals("accepted", findings.first().risk,
            "rethrowIfCancellation() inside a conditional branch should NOT be accepted as first-statement helper")
    }
}
