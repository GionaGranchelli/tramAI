package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for the compiler-baseline growth verifier (10.1c BLOCKER 1).
 * The working-tree baseline must never EXPAND relative to the certified base:
 * that closes the allowance-file attack (add warning + expand baseline in one PR).
 */
class CompilerWarningsBaselineGrowthVerifierTest {
    private val entryA = WarningEntry("tramai-core/.../A.kt", "DEPRECATION", "A is deprecated", 1)
    private val entryB = WarningEntry("tramai-core/.../B.kt", "UNNECESSARY_SAFE_CALL", "B message", 2)

    private fun json(vararg entries: WarningEntry) = CompilerWarningsBaselineIo.toJson(entries.toList())

    @Test
    fun `new identity added to current baseline fails`() {
        val verdict =
            CompilerWarningsBaselineGrowthVerifier.verify(
                CompilerWarningsGrowthInput(json(entryA), json(entryA, entryB)),
            )
        assertFalse(verdict.passed)
        assertEquals(CompilerWarningsGrowthVerdict.GROWTH, verdict.code)
        assertEquals(1, verdict.added.size)
    }

    @Test
    fun `count increase on existing identity fails`() {
        val verdict =
            CompilerWarningsBaselineGrowthVerifier.verify(
                CompilerWarningsGrowthInput(json(entryA), json(entryA.copy(count = 3))),
            )
        assertFalse(verdict.passed)
        assertEquals(1, verdict.grown.size)
    }

    @Test
    fun `identity removal passes`() {
        val verdict =
            CompilerWarningsBaselineGrowthVerifier.verify(
                CompilerWarningsGrowthInput(json(entryA, entryB), json(entryA)),
            )
        assertTrue(verdict.passed)
        assertEquals(1, verdict.removed.size)
    }

    @Test
    fun `count decrease passes`() {
        val verdict =
            CompilerWarningsBaselineGrowthVerifier.verify(
                CompilerWarningsGrowthInput(json(entryB), json(entryB.copy(count = 1))),
            )
        assertTrue(verdict.passed)
    }

    @Test
    fun `identical baselines pass`() {
        val verdict =
            CompilerWarningsBaselineGrowthVerifier.verify(
                CompilerWarningsGrowthInput(json(entryA, entryB), json(entryA, entryB)),
            )
        assertTrue(verdict.passed)
    }

    @Test
    fun `bootstrap base absent current present passes only for first adoption`() {
        val verdict =
            CompilerWarningsBaselineGrowthVerifier.verify(
                CompilerWarningsGrowthInput(null, json(entryA)),
            )
        assertTrue(verdict.passed)
    }

    @Test
    fun `current baseline deleted fails`() {
        val verdict =
            CompilerWarningsBaselineGrowthVerifier.verify(
                CompilerWarningsGrowthInput(json(entryA), null),
            )
        assertFalse(verdict.passed)
        assertEquals(CompilerWarningsGrowthVerdict.DELETED, verdict.code)
    }

    @Test
    fun `both absent fails`() {
        val verdict =
            CompilerWarningsBaselineGrowthVerifier.verify(
                CompilerWarningsGrowthInput(null, null),
            )
        assertFalse(verdict.passed)
    }

    @Test
    fun `malformed current baseline fails closed`() {
        val verdict =
            CompilerWarningsBaselineGrowthVerifier.verify(
                CompilerWarningsGrowthInput(json(entryA), "{ not json"),
            )
        assertFalse(verdict.passed)
        assertEquals(CompilerWarningsGrowthVerdict.MALFORMED, verdict.code)
    }

    @Test
    fun `malformed base baseline fails closed`() {
        val verdict =
            CompilerWarningsBaselineGrowthVerifier.verify(
                CompilerWarningsGrowthInput("{ not json", json(entryA)),
            )
        assertFalse(verdict.passed)
        assertEquals(CompilerWarningsGrowthVerdict.MALFORMED, verdict.code)
    }

    @Test
    fun `legacy v1 schema base allows one-time migration`() {
        // A base baseline generated with the old lossy fingerprint schema cannot
        // be compared identity-for-identity — the one-time migration rewrite is
        // allowed; unparseable garbage still fails closed (covered above).
        val oldSchema =
            CompilerWarningsBaselineIo
                .toJson(listOf(entryA))
                .replace("\"schemaVersion\" : 2", "\"schemaVersion\" : 1")
        val verdict =
            CompilerWarningsBaselineGrowthVerifier.verify(
                CompilerWarningsGrowthInput(oldSchema, json(entryA)),
            )
        assertTrue(verdict.passed)
    }
}
