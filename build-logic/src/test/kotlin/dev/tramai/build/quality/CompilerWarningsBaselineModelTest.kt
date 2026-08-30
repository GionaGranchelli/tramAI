package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the compiler-warning baseline gate (Epic 10.1c, C-series).
 * Pure logic only — no Gradle, no git.
 *
 * Identity = repo-relative path + [DIAGNOSTIC_NAME] + normalized fingerprint +
 * multiplicity. Line/column numbers are deliberately excluded so moving a
 * warning within a file is not a violation (C4).
 */
class CompilerWarningsBaselineModelTest {
    private val entryA =
        WarningEntry(
            path = "tramai-core/src/main/kotlin/dev/tramai/core/X.kt",
            diagnostic = "OPT_IN_USAGE",
            message = "Tramai secondary-failure machinery is internal implementation API for cross-module composition",
            count = 1,
        )
    private val entryB =
        WarningEntry(
            path = "tramai-core/src/main/kotlin/dev/tramai/core/Y.kt",
            diagnostic = "UNNECESSARY_SAFE_CALL",
            message = "Unnecessary safe call on a non-null receiver of type Foo",
            count = 1,
        )

    // ── Parser (standalone kotlinc format) ────────────────────────────────

    @Test
    fun `parser extracts diagnostic names, line and col excluded from identity`() {
        val output =
            "tramai-core/src/main/kotlin/dev/tramai/core/X.kt:12:34: warning: [OPT_IN_USAGE] " +
                "Tramai secondary-failure machinery is internal implementation API for cross-module composition\n" +
                "tramai-core/src/main/kotlin/dev/tramai/core/X.kt:99:5: warning: [OPT_IN_USAGE] " +
                "Tramai secondary-failure machinery is internal implementation API for cross-module composition\n"
        val parsed = CompilerWarningsParser.parse(output)
        assertEquals(1, parsed.size) // same identity, multiplicity 2
        assertEquals(2, parsed.single().count)
        assertEquals("OPT_IN_USAGE", parsed.single().diagnostic)
    }

    @Test
    fun `parser fails closed on compile errors`() {
        val output =
            "tramai-core/src/main/kotlin/dev/tramai/core/X.kt:12:34: error: Unresolved reference 'foo'\n"
        assertThrows<IllegalStateException> { CompilerWarningsParser.parse(output) }
    }

    @Test
    fun `parser ignores source excerpt and caret continuation lines`() {
        val output =
            "tramai-core/src/main/kotlin/dev/tramai/core/X.kt:12:34: warning: [OPT_IN_USAGE] msg\n" +
                "                  authority = SecondaryEffectAuthority.NON_AUTHORITATIVE.name,\n" +
                "                              ^^^^^^^^^^^^^^^^^^^^^^^^\n"
        val parsed = CompilerWarningsParser.parse(output)
        assertEquals(1, parsed.size)
        assertEquals(1, parsed.single().count)
    }

    // ── Fingerprint (C4: normalization) ───────────────────────────────────

    @Test
    fun `fingerprint collapses whitespace but preserves symbols and digits`() {
        // Whitespace-only normalization: quoted values and digits are IDENTITY
        // (10.1c BLOCKER 2 — remove-A/add-B substitution must not hide).
        assertEquals(
            CompilerWarningsFingerprint.normalize("  deprecated   since 3.0 "),
            CompilerWarningsFingerprint.normalize("deprecated since 3.0"),
        )
        assertFalse(
            CompilerWarningsFingerprint.normalize("This annotation is applied to 'X' 42 times") ==
                CompilerWarningsFingerprint.normalize("This annotation is applied to 'Y' 7 times"),
        )
        assertFalse(
            CompilerWarningsFingerprint.normalize("deprecated since 3.0") ==
                CompilerWarningsFingerprint.normalize("deprecated since 4.0"),
        )
    }

    // ── Verifier (C1/C2/C3/C4) ────────────────────────────────────────────

    @Test
    fun `unchanged baseline passes`() {
        val violations = CompilerWarningsBaselineVerifier.compare(listOf(entryA, entryB), listOf(entryA, entryB))
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `new warning identity fails`() {
        val current =
            listOf(
                entryA,
                WarningEntry("tramai-core/.../Z.kt", "PARAMETER_NAME_CHANGED", "msg", 1),
            )
        val violations = CompilerWarningsBaselineVerifier.compare(current, listOf(entryA))
        assertEquals(1, violations.size)
        assertTrue(violations.single().path.contains("Z.kt"))
    }

    @Test
    fun `additional occurrence of baselined warning fails`() {
        val current = listOf(entryA.copy(count = 2))
        val violations = CompilerWarningsBaselineVerifier.compare(current, listOf(entryA))
        assertEquals(1, violations.size)
        assertEquals(2, violations.single().currentCount) // count 2 > baseline 1
    }

    @Test
    fun `warning removal passes`() {
        val violations = CompilerWarningsBaselineVerifier.compare(listOf(entryA), listOf(entryA, entryB))
        assertTrue(violations.isEmpty()) // shrink is always allowed
    }

    @Test
    fun `message change is a new identity and fails`() {
        val changed = entryA.copy(message = "a completely different message")
        val violations = CompilerWarningsBaselineVerifier.compare(listOf(changed), listOf(entryA))
        assertEquals(1, violations.size)
    }

    // ── Baseline JSON round-trip (C10) ────────────────────────────────────
    @Test
    fun `baseline json round-trips`() {
        val json = CompilerWarningsBaselineIo.toJson(listOf(entryA, entryB))
        val parsed = CompilerWarningsBaselineIo.fromJson(json)
        assertEquals(listOf(entryA, entryB), parsed)
    }

    // ── Baseline JSON fail-closed strictness (10.1c review round) ─────────
    private fun entryJson(
        path: String,
        diagnostic: String,
        message: String,
        count: Int = 1,
    ) = """{"path":"$path","diagnostic":"$diagnostic","message":"$message","count":$count}"""

    private fun baselineJson(
        schemaVersion: String,
        vararg entries: String,
    ) = """{"schemaVersion":$schemaVersion,"entries":[${entries.joinToString(",")}]}"""

    @Test
    fun `malformed entry in multi-entry baseline fails closed`() {
        val json = baselineJson("2", entryJson("a.kt", "D1", "m1"), """{"path":"b.kt"}""")
        assertNull(CompilerWarningsBaselineIo.fromJson(json))
    }

    @Test
    fun `missing schemaVersion fails closed`() {
        val json = """{"entries":[${entryJson("a.kt", "D1", "m1")}]}"""
        assertNull(CompilerWarningsBaselineIo.fromJson(json))
    }

    @Test
    fun `unknown schemaVersion fails closed`() {
        val json = baselineJson("3", entryJson("a.kt", "D1", "m1"))
        assertNull(CompilerWarningsBaselineIo.fromJson(json))
    }

    @Test
    fun `duplicate identity fails closed`() {
        val json = baselineJson("2", entryJson("a.kt", "D1", "m1"), entryJson("a.kt", "D1", "m1"))
        assertNull(CompilerWarningsBaselineIo.fromJson(json))
    }

    // ── Fail-closed runner (C8: tool failure is NOT zero findings) ────────

    @Test
    fun `compiler boot failure fails closed`() {
        // Empty compiler classpath → the spawned JVM cannot load K2JVMCompiler →
        // non-zero exit with no w: lines. The runner MUST throw, never return [].
        val root = File("/tmp/cw-probe-" + System.nanoTime())
        // moduleDir = root + modulePath (":probe" → "probe")
        File(root, "probe/src/main/kotlin").mkdirs()
        File(root, "probe/src/main/kotlin/X.kt").writeText("package x\nval a = 1\n")
        val unit = CompileUnitSpec(":probe", "main", emptyList(), "21")
        org.junit.jupiter.api.assertThrows<GradleException> {
            KotlincRunner.compileWarnings(unit, emptyList(), emptyList(), root, File(root, "out"))
        }
    }
}
