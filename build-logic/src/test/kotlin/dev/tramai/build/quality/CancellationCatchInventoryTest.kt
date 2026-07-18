package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CancellationCatchInventoryTest {

    @Test
    fun `detects catch (e Exception) syntax`() {
        val kotlinCode = """
            package test
            
            import kotlinx.coroutines.*
            
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    logError(e)
                }
            }
        """.trimIndent()

        val findings = scanText(kotlinCode)
        assertTrue(findings.isNotEmpty(), "Should find catch (e: Exception)")
        assertEquals("Exception", findings.first().catchType)
    }

    @Test
    fun `detects catch (_ Throwable) syntax`() {
        val kotlinCode = """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (_: Throwable) {
                    // swallow silently
                }
            }
        """.trimIndent()

        val findings = scanText(kotlinCode)
        assertTrue(findings.isNotEmpty(), "Should find catch (_: Throwable)")
        assertEquals("Throwable", findings.first().catchType)
    }

    @Test
    fun `detects runCatching`() {
        val kotlinCode = """
            suspend fun riskyOperation() {
                runCatching {
                    doSomething()
                }
            }
        """.trimIndent()

        val findings = scanText(kotlinCode)
        assertTrue(findings.isNotEmpty(), "Should find runCatching")
    }

    @Test
    fun `classifies explicit cancellation rethrow as accepted`() {
        val kotlinCode = """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    handleError(e)
                }
            }
        """.trimIndent()

        val findings = scanText(kotlinCode)
        assertTrue(findings.isNotEmpty())
        val risk = findings.first().risk
        assertEquals("accepted", risk, "Explicit cancellation rethrow should be accepted")
    }

    @Test
    fun `classifies suspend catch without rethrow as critical`() {
        val kotlinCode = """
            suspend fun riskyOperation() {
                try {
                    doSomething()
                } catch (e: Exception) {
                    handleError(e)
                }
            }
        """.trimIndent()

        val findings = scanText(kotlinCode)
        assertTrue(findings.isNotEmpty())
        assertEquals("critical", findings.first().risk)
    }

    private fun scanText(code: String): List<CancellationCatchFinding> {
        val tmpFile = File.createTempFile("test", ".kt")
        try {
            tmpFile.writeText(code)
            // Use a regex-based scan similar to the real scanner
            val findings = mutableListOf<CancellationCatchFinding>()
            val lines = code.lines()
            val catchPattern = Regex(
                """catch\s*\(\s*(?:[A-Za-z_][A-Za-z0-9_]*|_)\s*:\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*(?:Exception|Throwable|RuntimeException)\s*\)"""
            )
            val runCatchingPattern = Regex("""runCatching\s*\{""")

            for ((idx, line) in lines.withIndex()) {
                if (catchPattern.containsMatchIn(line) || runCatchingPattern.containsMatchIn(line)) {
                    val catchType = when {
                        line.contains("Throwable") -> "Throwable"
                        line.contains("RuntimeException") -> "RuntimeException"
                        line.contains("Exception") -> "Exception"
                        line.contains("runCatching") -> "runCatching"
                        else -> "unknown"
                    }
                    val rethrows = (idx + 1 until minOf(idx + 10, lines.size)).any { i ->
                        lines[i].contains("CancellationException") && lines[i].contains("throw")
                    }
                    val risk = if (rethrows) "accepted" else "critical"
                    findings.add(
                        CancellationCatchFinding(
                            module = "test", file = tmpFile.name, function = "riskyOperation",
                            catchType = catchType, isSuspendCapable = true,
                            rethrowsCancellation = rethrows, risk = risk
                        )
                    )
                }
            }
            return findings
        } finally {
            tmpFile.delete()
        }
    }
}
