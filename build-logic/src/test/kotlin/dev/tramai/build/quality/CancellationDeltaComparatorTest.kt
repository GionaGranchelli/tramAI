package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CancellationDeltaComparatorTest {

    // Helper factory for test findings
    private fun finding(
        risk: String,
        module: String = "test-module",
        file: String = "Test.kt",
        function: String = "riskyOperation",
        catchType: String = "Exception",
        sourceLine: Int = 10
    ): CancellationCatchFinding = CancellationCatchFinding(
        module = module,
        file = file,
        function = function,
        catchType = catchType,
        risk = risk,
        sourceLine = sourceLine
    )

    private fun asserting(result: CancellationDeltaComparator.Result) {
        // Just a helper for test name readability
    }

    @Test
    fun `unchanged same population`() {
        val base = listOf(
            finding("critical", function = "op1"),
            finding("critical", function = "op2"),
            finding("critical", function = "op3")
        )
        val current = listOf(
            finding("critical", function = "op1"),
            finding("critical", function = "op2"),
            finding("critical", function = "op3")
        )
        val result = CancellationDeltaComparator.compare(base, current)
        assertTrue(result.newCriticalHigh.isEmpty(), "No new findings expected")
        assertTrue(result.worsened.isEmpty(), "No worsenings expected")
        assertEquals(3, result.unchanged, "All 3 findings unchanged")
        // Should pass the gate
        assertFalse(
            result.newCriticalHigh.isNotEmpty() || result.worsened.isNotEmpty(),
            "Gate check should pass"
        )
    }

    @Test
    fun `accepted to critical`() {
        val base = listOf(finding("accepted"))
        val current = listOf(finding("critical"))
        val result = CancellationDeltaComparator.compare(base, current)
        assertTrue(result.newCriticalHigh.isEmpty(), "No new findings")
        assertEquals(1, result.worsened.size, "One worsening expected")
        val w = result.worsened.first()
        assertEquals("accepted", w.base.risk)
        assertEquals("critical", w.current.risk)
        // Should fail the gate
        assertTrue(
            result.newCriticalHigh.isNotEmpty() || result.worsened.isNotEmpty(),
            "Gate check should fail"
        )
    }

    @Test
    fun `medium to high`() {
        val base = listOf(finding("medium"))
        val current = listOf(finding("high"))
        val result = CancellationDeltaComparator.compare(base, current)
        assertTrue(result.newCriticalHigh.isEmpty(), "No new findings")
        assertEquals(1, result.worsened.size, "One worsening expected")
        val w = result.worsened.first()
        assertEquals("medium", w.base.risk)
        assertEquals("high", w.current.risk)
        assertTrue(
            result.newCriticalHigh.isNotEmpty() || result.worsened.isNotEmpty(),
            "Gate check should fail"
        )
    }

    @Test
    fun `new second critical`() {
        val base = listOf(finding("critical", function = "op1"))
        val current = listOf(
            finding("critical", function = "op1"),
            finding("critical", function = "op2")
        )
        val result = CancellationDeltaComparator.compare(base, current)
        assertEquals(1, result.newCriticalHigh.size, "One new critical expected")
        assertEquals("critical", result.newCriticalHigh.first().risk)
        assertEquals("op2", result.newCriticalHigh.first().function)
        assertTrue(result.worsened.isEmpty(), "No worsenings")
        assertTrue(
            result.newCriticalHigh.isNotEmpty() || result.worsened.isNotEmpty(),
            "Gate check should fail"
        )
    }

    @Test
    fun `safe accepted insertion before unchanged critical`() {
        val base = listOf(finding("critical"))
        val current = listOf(finding("accepted"), finding("critical"))
        val result = CancellationDeltaComparator.compare(base, current)
        assertTrue(result.newCriticalHigh.isEmpty(), "No new critical/high findings")
        assertTrue(result.worsened.isEmpty(), "No worsenings")
        assertEquals(1, result.unchanged, "1 unchanged (critical matched)")
        assertFalse(
            result.newCriticalHigh.isNotEmpty() || result.worsened.isNotEmpty(),
            "Gate check should pass"
        )
    }

    @Test
    fun `accepted insertion plus critical to high improvement`() {
        val base = listOf(finding("critical"))
        val current = listOf(finding("accepted"), finding("high"))
        val result = CancellationDeltaComparator.compare(base, current)
        assertTrue(result.newCriticalHigh.isEmpty(), "No new critical/high (accepted is safe)")
        assertTrue(result.worsened.isEmpty(), "No worsenings (high < critical)")
        assertEquals(1, result.unchanged, "1 improvement (critical→high counts as unchanged/pass)")
        assertFalse(
            result.newCriticalHigh.isNotEmpty() || result.worsened.isNotEmpty(),
            "Gate check should pass"
        )
    }

    @Test
    fun `safe accepted deletion plus critical to high improvement`() {
        val base = listOf(finding("accepted"), finding("critical"))
        val current = listOf(finding("high"))
        val result = CancellationDeltaComparator.compare(base, current)
        assertTrue(result.newCriticalHigh.isEmpty(), "No new findings")
        assertTrue(result.worsened.isEmpty(), "No worsenings (high < critical)")
        assertEquals(1, result.unchanged, "1 improvement (critical→high)")
        assertFalse(
            result.newCriticalHigh.isNotEmpty() || result.worsened.isNotEmpty(),
            "Gate check should pass"
        )
    }

    @Test
    fun `source line movement only`() {
        val base = listOf(finding("critical", sourceLine = 10))
        val current = listOf(finding("critical", sourceLine = 20))
        val result = CancellationDeltaComparator.compare(base, current)
        assertTrue(result.newCriticalHigh.isEmpty(), "No new findings for same risk at different line")
        assertTrue(result.worsened.isEmpty(), "No worsenings")
        assertEquals(1, result.unchanged, "1 unchanged (same risk level)")
        assertFalse(
            result.newCriticalHigh.isNotEmpty() || result.worsened.isNotEmpty(),
            "Gate check should pass"
        )
    }

    @Test
    fun `duplicate high findings with different source lines`() {
        val base = listOf(finding("high", function = "op1", sourceLine = 10))
        val current = listOf(
            finding("high", function = "op1", sourceLine = 10),
            finding("high", function = "op2", sourceLine = 20)
        )
        val result = CancellationDeltaComparator.compare(base, current)
        assertEquals(1, result.newCriticalHigh.size, "One new high expected")
        assertTrue(result.worsened.isEmpty(), "No worsenings")
        assertTrue(
            result.newCriticalHigh.isNotEmpty() || result.worsened.isNotEmpty(),
            "Gate check should fail"
        )
    }

    @Test
    fun `accepted removed critical stays`() {
        val base = listOf(finding("accepted"), finding("critical"))
        val current = listOf(finding("critical"))
        val result = CancellationDeltaComparator.compare(base, current)
        assertTrue(result.newCriticalHigh.isEmpty(), "No new findings")
        assertTrue(result.worsened.isEmpty(), "No worsenings")
        assertEquals(1, result.unchanged, "1 unchanged (critical matched)")
        assertFalse(
            result.newCriticalHigh.isNotEmpty() || result.worsened.isNotEmpty(),
            "Gate check should pass"
        )
    }

    @Test
    fun `all accepted replaced with critical`() {
        val base = listOf(finding("accepted", function = "op1"), finding("accepted", function = "op2"))
        val current = listOf(finding("critical", function = "op1"), finding("accepted", function = "op2"))
        val result = CancellationDeltaComparator.compare(base, current)
        assertTrue(result.newCriticalHigh.isEmpty(), "No new findings")
        assertEquals(1, result.worsened.size, "One worsening expected (accepted→critical)")
        val w = result.worsened.first()
        assertEquals("accepted", w.base.risk)
        assertEquals("critical", w.current.risk)
        assertTrue(
            result.newCriticalHigh.isNotEmpty() || result.worsened.isNotEmpty(),
            "Gate check should fail"
        )
    }

    @Test
    fun `preserves finding objects in worsened result`() {
        // Same group (module, file, function, catchType) — only risk and sourceLine differ
        val base = listOf(
            finding("accepted", module = "modA", file = "Test.kt", function = "riskyOp", catchType = "Exception", sourceLine = 5)
        )
        val current = listOf(
            finding("critical", module = "modA", file = "Test.kt", function = "riskyOp", catchType = "Exception", sourceLine = 15)
        )
        val result = CancellationDeltaComparator.compare(base, current)
        assertEquals(1, result.worsened.size)
        val w = result.worsened.first()
        // Base finding preserved
        assertEquals("modA", w.base.module)
        assertEquals("Test.kt", w.base.file)
        assertEquals("riskyOp", w.base.function)
        assertEquals("Exception", w.base.catchType)
        assertEquals(5, w.base.sourceLine)
        assertEquals("accepted", w.base.risk)
        // Current finding preserved
        assertEquals("modA", w.current.module)
        assertEquals("Test.kt", w.current.file)
        assertEquals("riskyOp", w.current.function)
        assertEquals("Exception", w.current.catchType)
        assertEquals(15, w.current.sourceLine)
        assertEquals("critical", w.current.risk)
    }

    @Test
    fun `preserves finding objects in new result`() {
        val base = listOf(finding("critical", function = "op1"))
        val current = listOf(
            finding("critical", function = "op1"),
            finding("critical", module = "modX", file = "NewFile.kt", function = "op2", catchType = "Throwable", sourceLine = 42)
        )
        val result = CancellationDeltaComparator.compare(base, current)
        assertEquals(1, result.newCriticalHigh.size)
        val n = result.newCriticalHigh.first()
        assertEquals("modX", n.module)
        assertEquals("NewFile.kt", n.file)
        assertEquals("op2", n.function)
        assertEquals("Throwable", n.catchType)
        assertEquals(42, n.sourceLine)
        assertEquals("critical", n.risk)
    }

    @Test
    fun `diagnostics contain module file and source line for worsened findings`() {
        // Same group — only risk and sourceLine differ
        val base = listOf(finding("medium", module = "modA", file = "Test.kt", sourceLine = 10))
        val current = listOf(finding("critical", module = "modA", file = "Test.kt", sourceLine = 20))
        val result = CancellationDeltaComparator.compare(base, current)
        val diagLines = result.diagnostics.joinToString("\n")
        assertTrue(diagLines.contains("medium") && diagLines.contains("critical"),
            "Diagnostics should show risk transition: $diagLines")
        assertTrue(diagLines.contains("modA") && diagLines.contains("Test.kt") && diagLines.contains("20"),
            "Diagnostics should show current finding location: $diagLines")
    }

    @Test
    fun `diagnostics contain module file and source line for new findings`() {
        val base = listOf(finding("critical", function = "op1"))
        val current = listOf(
            finding("critical", function = "op1"),
            finding("critical", module = "modC", file = "NewCatch.kt", function = "op2", sourceLine = 99)
        )
        val result = CancellationDeltaComparator.compare(base, current)
        val diagLines = result.diagnostics.joinToString("\n")
        assertTrue(diagLines.contains("modC"), "Diagnostics should show module: $diagLines")
        assertTrue(diagLines.contains("NewCatch.kt"), "Diagnostics should show file: $diagLines")
        assertTrue(diagLines.contains("99"), "Diagnostics should show sourceLine: $diagLines")
    }
}
