package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `rethrowIfCancellation after semicolon-prefixed side effect on same line is NOT accepted`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (error: Throwable) {
                    auditFailure(error); error.rethrowIfCancellation()
                    handleError(error)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertNotEquals("accepted", findings.first().risk,
            "rethrowIfCancellation() after side effect on same line should NOT be accepted")
    }

    @Test
    fun `rethrowIfCancellation inside inline conditional is NOT accepted`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (error: Throwable) {
                    if (shouldPropagate) error.rethrowIfCancellation()
                    handleError(error)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertNotEquals("accepted", findings.first().risk,
            "rethrowIfCancellation() inside inline conditional should NOT be accepted")
    }

    @Test
    fun `rethrowIfCancellation after inline block comment is NOT accepted`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (error: Throwable) {
                    /* intent: suppress cancellation */ error.rethrowIfCancellation()
                    handleError(error)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertNotEquals("accepted", findings.first().risk,
            "rethrowIfCancellation() after inline block comment should NOT be accepted")
    }

    @Test
    fun `multiple statements on first line without helper are NOT accepted as rethrow`() {
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    logError(e); notifyAlert(e); throw DomainException(e)
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty(), "Should find catch")
        assertNotEquals("accepted", findings.first().risk,
            "Multiple statements without rethrowIfCancellation() should NOT be accepted")
    }

    // ── Fingerprint (source-content relocation evidence) tests ──

    private fun fingerprintOf(source: String): String {
        val findings = KotlinCancellationCatchScanner.scan(source, "test", "Test.kt")
        assertTrue(findings.isNotEmpty(), "Expected at least one finding in: $source")
        return findings.first().sourceFingerprint
    }

    private fun fingerprintOf(source: String, catchType: String): String {
        val findings = KotlinCancellationCatchScanner.scan(source, "test", "Test.kt")
        val finding = findings.firstOrNull { it.catchType == catchType }
        assertNotNull(finding, "Expected a $catchType finding in: $source")
        return finding.sourceFingerprint
    }

    @Test
    fun `identical multiline runCatching moved across files has same fingerprint`() {
        val base = """
            suspend fun execute() {
                runCatching {
                    deleteTemporaryState()
                }
            }
        """.trimIndent()
        val current = """
            suspend fun execute() {
                runCatching {
                    deleteTemporaryState()
                }
            }
        """.trimIndent()
        assertEquals(fingerprintOf(base), fingerprintOf(current),
            "Identical multiline runCatching must have identical fingerprints")
    }

    @Test
    fun `multiline runCatching with different bodies has different fingerprints`() {
        val base = """
            suspend fun execute() {
                runCatching {
                    deleteTemporaryState()
                }
            }
        """.trimIndent()
        val current = """
            suspend fun execute() {
                runCatching {
                    publishExternalResult()
                }
            }
        """.trimIndent()
        assertNotEquals(fingerprintOf(base), fingerprintOf(current),
            "Different runCatching bodies must not collide")
    }

    @Test
    fun `catch blocks with different bodies have different fingerprints`() {
        val base = """
            suspend fun execute() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    logError(e)
                }
            }
        """.trimIndent()
        val current = """
            suspend fun execute() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    publishFailure(e)
                }
            }
        """.trimIndent()
        assertNotEquals(fingerprintOf(base), fingerprintOf(current),
            "Different catch bodies must not collide")
    }

    @Test
    fun `genuine move landing on same line has same fingerprint`() {
        // Same construct, same line number — the fingerprint is content-based,
        // so a genuine move that lands on the same line is still a relocation.
        val source = """
            suspend fun execute() {
                runCatching { abort() }
            }
        """.trimIndent()
        assertEquals(fingerprintOf(source), fingerprintOf(source))
    }

    @Test
    fun `strings containing a b vs ab do not collapse`() {
        val withSpace = """
            suspend fun execute() {
                runCatching {
                    log("a b")
                }
            }
        """.trimIndent()
        val withoutSpace = """
            suspend fun execute() {
                runCatching {
                    log("ab")
                }
            }
        """.trimIndent()
        assertNotEquals(fingerprintOf(withSpace), fingerprintOf(withoutSpace),
            "String contents must be preserved — 'a b' must not collapse to 'ab'")
    }

    @Test
    fun `strings containing https are preserved`() {
        val source = """
            suspend fun execute() {
                runCatching {
                    post("https://example.com/api")
                }
            }
        """.trimIndent()
        val fingerprint = fingerprintOf(source)
        assertTrue(fingerprint.contains("https://"),
            "Comment marker inside string literal must be preserved, got: $fingerprint")
    }

    @Test
    fun `fingerprint covers full body not just opening line`() {
        // The fingerprint must include body content — an opening line alone
        // (runCatching{) would not distinguish these two constructs.
        val base = """
            suspend fun execute() {
                runCatching {
                    deleteTemporaryState()
                }
            }
        """.trimIndent()
        val current = """
            suspend fun execute() {
                runCatching {
                    publishExternalResult()
                }
            }
        """.trimIndent()
        val baseFp = fingerprintOf(base)
        val currentFp = fingerprintOf(current)
        assertTrue(baseFp.contains("deleteTemporaryState"), "Fingerprint must cover body, got: $baseFp")
        assertTrue(currentFp.contains("publishExternalResult"), "Fingerprint must cover body, got: $currentFp")
        assertNotEquals(baseFp, currentFp)
    }

    @Test
    fun `fingerprint is not serialized into baseline json`() {
        // "Zero schema change" is a protected invariant: the ephemeral
        // relocation evidence must never leak into the persisted baseline.
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun execute() {
                runCatching { abort() }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty())
        val json = ReportNormalizer.toJson(findings.first())
        assertFalse(json.contains("sourceFingerprint"), "sourceFingerprint must not be serialized: $json")
        assertFalse(json.contains("runCatching { abort() }"), "Fingerprint content must not leak into JSON: $json")
    }

    @Test
    fun `brace inside line comment does not terminate fingerprint`() {
        // A `}` in a comment is not structural — the construct continues past
        // it and the differing executable statements must produce different
        // fingerprints.
        val base = """
            suspend fun execute() {
                runCatching {
                    // }
                    deleteTemporaryState()
                }
            }
        """.trimIndent()
        val current = """
            suspend fun execute() {
                runCatching {
                    // }
                    publishExternalResult()
                }
            }
        """.trimIndent()
        val baseFp = fingerprintOf(base)
        val currentFp = fingerprintOf(current)
        assertTrue(baseFp.contains("deleteTemporaryState"), "Fingerprint must not truncate at comment brace, got: $baseFp")
        assertTrue(currentFp.contains("publishExternalResult"), "Fingerprint must not truncate at comment brace, got: $currentFp")
        assertNotEquals(baseFp, currentFp)
    }

    @Test
    fun `brace inside block comment does not terminate fingerprint`() {
        val base = """
            suspend fun execute() {
                runCatching {
                    /* }
                       still comment */
                    deleteTemporaryState()
                }
            }
        """.trimIndent()
        val current = """
            suspend fun execute() {
                runCatching {
                    /* }
                       still comment */
                    publishExternalResult()
                }
            }
        """.trimIndent()
        val baseFp = fingerprintOf(base)
        val currentFp = fingerprintOf(current)
        assertTrue(baseFp.contains("deleteTemporaryState"), "Fingerprint must not truncate at block-comment brace, got: $baseFp")
        assertTrue(currentFp.contains("publishExternalResult"), "Fingerprint must not truncate at block-comment brace, got: $currentFp")
        assertNotEquals(baseFp, currentFp)
    }

    @Test
    fun `brace inside multiline raw string does not terminate fingerprint`() {
        // A `}` inside a triple-quoted raw string is content, not structure —
        // the state must carry across lines so the construct is not truncated.
        val tq = "\"\"\""
        val base = """
            suspend fun execute() {
                runCatching {
                    val json = $tq
                        }
                    $tq.trimIndent()
                    deleteTemporaryState()
                }
            }
        """.trimIndent()
        val current = """
            suspend fun execute() {
                runCatching {
                    val json = $tq
                        }
                    $tq.trimIndent()
                    publishExternalResult()
                }
            }
        """.trimIndent()
        val baseFp = fingerprintOf(base)
        val currentFp = fingerprintOf(current)
        assertTrue(baseFp.contains("deleteTemporaryState"), "Fingerprint must not truncate at raw-string brace, got: $baseFp")
        assertTrue(currentFp.contains("publishExternalResult"), "Fingerprint must not truncate at raw-string brace, got: $currentFp")
        assertNotEquals(baseFp, currentFp)
    }

    @Test
    fun `brace inside regular string does not terminate fingerprint`() {
        val base = """
            suspend fun execute() {
                runCatching {
                    log("}")
                    deleteTemporaryState()
                }
            }
        """.trimIndent()
        val current = """
            suspend fun execute() {
                runCatching {
                    log("}")
                    publishExternalResult()
                }
            }
        """.trimIndent()
        val baseFp = fingerprintOf(base)
        val currentFp = fingerprintOf(current)
        assertTrue(baseFp.contains("deleteTemporaryState"), "Fingerprint must not truncate at string brace, got: $baseFp")
        assertTrue(currentFp.contains("publishExternalResult"), "Fingerprint must not truncate at string brace, got: $currentFp")
        assertNotEquals(baseFp, currentFp)
    }

    @Test
    fun `nested block comment containing brace does not truncate fingerprint`() {
        // Kotlin block comments nest — an inner `/* */` inside an outer
        // `/* */` must not return to CODE early, and a `}` inside the still-
        // open outer comment must not close the construct.
        val base = """
            suspend fun execute() {
                runCatching {
                    /* outer
                       /* inner */
                       }
                    */
                    deleteTemporaryState()
                }
            }
        """.trimIndent()
        val current = """
            suspend fun execute() {
                runCatching {
                    /* outer
                       /* inner */
                       }
                    */
                    publishExternalResult()
                }
            }
        """.trimIndent()
        val baseFp = fingerprintOf(base)
        val currentFp = fingerprintOf(current)
        assertTrue(baseFp.contains("deleteTemporaryState"), "Fingerprint must not truncate at nested-comment brace, got: $baseFp")
        assertTrue(currentFp.contains("publishExternalResult"), "Fingerprint must not truncate at nested-comment brace, got: $currentFp")
        assertNotEquals(baseFp, currentFp)
    }

    @Test
    fun `outer catch with inner runCatching on opening line anchors to the catch`() {
        // The fingerprint must anchor to the ACTUAL matched construct. An
        // outer catch whose opening line also contains an inner runCatching
        // must balance the OUTER construct (statement after the inner
        // runCatching included), not stop at the inner one.
        val base = """
            suspend fun execute() {
                try {
                    work()
                } catch (e: Exception) { runCatching { commonCleanup() }
                    oldWork()
                }
            }
        """.trimIndent()
        val current = """
            suspend fun execute() {
                try {
                    work()
                } catch (e: Exception) { runCatching { commonCleanup() }
                    completelyDifferentWork()
                }
            }
        """.trimIndent()
        val baseFp = fingerprintOf(base, "Exception")
        val currentFp = fingerprintOf(current, "Exception")
        assertTrue(baseFp.contains("oldWork"), "Outer-catch fingerprint must include statement after inner runCatching, got: $baseFp")
        assertTrue(currentFp.contains("completelyDifferentWork"), "Outer-catch fingerprint must include statement after inner runCatching, got: $currentFp")
        assertNotEquals(baseFp, currentFp)
    }

    @Test
    fun `unterminated string yields blank fingerprint refusing relocation`() {
        // Unbalanced source (unterminated raw string before the construct's
        // real close) → blank fingerprint → Phase 2 refuses relocation.
        val tq = "\"\"\""
        val findings = KotlinCancellationCatchScanner.scan(
            """
            suspend fun execute() {
                runCatching {
                    val json = $tq
                    deleteTemporaryState()
                }
            }
            """.trimIndent(), "test", "Test.kt"
        )
        assertTrue(findings.isNotEmpty())
        assertEquals("", findings.first().sourceFingerprint,
            "Unbalanced construct must produce blank fingerprint (refuse relocation)")
    }

    @Test
    fun `suspend fun immediately after another block is still detected as suspend`() {
        // Regression for the suspend-range walk: findSuspendRanges advanced
        // with `i = end` then the loop's `i++` skipped the line at index end —
        // exactly where a `private suspend fun` declaration can sit. Its body
        // was then never in a suspend range and the runCatching was classified
        // medium instead of critical. The declaration here follows another
        // block's closing brace directly (previous findBlockEnd == declaration
        // line), reproducing the Workflow.kt:1763 layout.
        val source = """
            suspend fun previousBlock() {
                doWork()
            }
            private suspend fun runCatchingAbort(
                error: Throwable,
            ) {
                runCatching { abort() }
                    .onFailure { error.addSuppressed(it) }
            }
        """.trimIndent()
        val findings = KotlinCancellationCatchScanner.scan(source, "test", "Test.kt")
        val rc = findings.firstOrNull { it.catchType == "runCatching" }
        assertNotNull(rc, "runCatching finding expected")
        assertEquals("critical", rc.risk,
            "runCatching inside suspend fun directly after another block must be critical, got ${rc.risk}")
        assertEquals(true, rc.isSuspendCapable)
    }
}
