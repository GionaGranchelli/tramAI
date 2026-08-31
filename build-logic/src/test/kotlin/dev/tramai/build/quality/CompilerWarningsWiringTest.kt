package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 10.1c C-series wiring contract (review round): `check`/`verifyPr` own the
 * compiler-warning gate; a malformed/deleted baseline fails closed before any
 * compile happens.
 */
class CompilerWarningsWiringTest : StaticAnalysisContractTestBase() {
    @Test
    fun `c9 check owns compiler gate`() {
        val run = gradleUntil(":verifyCompilerWarnings", "--no-build-cache", "check", "--dry-run")
        assertTrue(
            run.output.contains(":verifyCompilerWarnings"),
            "check's task graph must contain :verifyCompilerWarnings. Output: ${run.output.take(1500)}",
        )
    }

    @Test
    fun `c10 verifyPr owns compiler gate`() {
        val run = gradleUntil(":verifyCompilerWarnings", "--no-build-cache", "verifyPr", "--dry-run")
        assertTrue(
            run.output.contains(":verifyCompilerWarnings"),
            "verifyPr's task graph must contain :verifyCompilerWarnings. Output: ${run.output.take(1500)}",
        )
    }

    @Test
    fun `c7 deleted compiler baseline fails closed`() {
        val baseline = File(worktree, "config/warnings/baseline.json")
        check(baseline.isFile) { "worktree must carry the committed compiler baseline" }
        baseline.delete()
        git(worktree, "commit", "-am", "delete compiler baseline")
        val run = gradle("verifyCompilerWarnings", "-PtramaiCompilerWarningsBaseRef=HEAD", "--no-build-cache")
        assertTrue(
            run.exit != 0 && run.output.contains("fails closed"),
            "deleted baseline must fail the gate before any compile. Output: ${run.output.take(1500)}",
        )
    }

    // ── Delta invalidation (10.1c round-4, M12) ───────────────────────────
    @Test
    fun `java-only delta selects the module`() {
        val modules = compilerDeltaModules("tramai-core/src/main/java/dev/tramai/core/Foo.java\n")
        assertEquals(setOf(":tramai-core"), modules)
    }

    @Test
    fun `module build-script-only delta selects the module`() {
        val modules = compilerDeltaModules("tramai-core/build.gradle.kts\n")
        assertEquals(setOf(":tramai-core"), modules)
    }

    @Test
    fun `global version or convention delta invalidates the whole repository`() {
        assertTrue(globalConfigInvalidated("gradle/libs.versions.toml\n"))
        assertTrue(globalConfigInvalidated("gradle.properties\n"))
        assertTrue(globalConfigInvalidated("settings.gradle.kts\n"))
        assertTrue(globalConfigInvalidated("build.gradle.kts\n"))
        assertTrue(globalConfigInvalidated("build-logic/src/main/kotlin/Conventions.kt\n"))
        assertFalse(globalConfigInvalidated("tramai-core/src/main/kotlin/dev/tramai/core/X.kt\n"))
        assertFalse(globalConfigInvalidated("tramai-core/build.gradle.kts\n"))
    }
}
