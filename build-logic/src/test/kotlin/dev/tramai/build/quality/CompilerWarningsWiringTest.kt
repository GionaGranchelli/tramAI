package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
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

    // ── Delta invalidation (10.1c round-4, M12; P3-A impact selection) ────
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
        val noDeps = emptyMap<String, Set<String>>()
        assertEquals(
            CompilerWarningsImpact.Full,
            resolveCompilerWarningsImpact("gradle/libs.versions.toml\n", noDeps),
        )
        assertEquals(
            CompilerWarningsImpact.Full,
            resolveCompilerWarningsImpact("gradle.properties\n", noDeps),
        )
        assertEquals(
            CompilerWarningsImpact.Full,
            resolveCompilerWarningsImpact("settings.gradle.kts\n", noDeps),
        )
        assertEquals(
            CompilerWarningsImpact.Full,
            resolveCompilerWarningsImpact("build.gradle.kts\n", noDeps),
        )
        assertEquals(
            CompilerWarningsImpact.Full,
            resolveCompilerWarningsImpact(
                "build-logic/src/main/kotlin/dev/tramai/build/conventions/TramaiKotlinLibraryPlugin.kt\n",
                noDeps,
            ),
        )
        assertEquals(
            CompilerWarningsImpact.Modules(setOf(":tramai-core")),
            resolveCompilerWarningsImpact("tramai-core/src/main/kotlin/dev/tramai/core/X.kt\n", noDeps),
        )
        assertEquals(
            CompilerWarningsImpact.Modules(setOf(":tramai-core")),
            resolveCompilerWarningsImpact("tramai-core/build.gradle.kts\n", noDeps),
        )
    }

    // ── P3-A: build-logic scanner/verifier changes are NOT global ─────────
    @Test
    fun `build-logic scanner change leaves no module impacted`() {
        // The #362 case: mutation-measurement scanner code does not configure
        // module compilation — no module inventory can change, gate passes.
        assertEquals(
            CompilerWarningsImpact.None,
            resolveCompilerWarningsImpact(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/MutationPopulationAggregator.kt\n",
                emptyMap(),
            ),
        )
        assertEquals(
            CompilerWarningsImpact.None,
            resolveCompilerWarningsImpact(
                "build-logic/src/main/kotlin/dev/tramai/build/release/ReleaseVerificationPlugin.kt\n",
                emptyMap(),
            ),
        )
    }

    @Test
    fun `convention-imported module manifest change is global`() {
        // TramaiJavaPlatformPlugin imports quality.ModuleManifest for BOM
        // constraints — a change can alter what every module resolves against.
        assertEquals(
            CompilerWarningsImpact.Full,
            resolveCompilerWarningsImpact(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/ModuleManifest.kt\n",
                emptyMap(),
            ),
        )
        assertEquals(
            CompilerWarningsImpact.Full,
            resolveCompilerWarningsImpact(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/ModuleCatalog.kt\n",
                emptyMap(),
            ),
        )
    }

    @Test
    fun `module-catalog and wrapper changes are global`() {
        assertEquals(
            CompilerWarningsImpact.Full,
            resolveCompilerWarningsImpact("config/quality/module-catalog.yml\n", emptyMap()),
        )
        assertEquals(
            CompilerWarningsImpact.Full,
            resolveCompilerWarningsImpact("gradle/wrapper/gradle-wrapper.properties\n", emptyMap()),
        )
    }

    @Test
    fun `module change closes over transitive dependents`() {
        val dependents =
            mapOf(
                ":tramai-core" to setOf(":tramai-engine", ":tramai-structured"),
                ":tramai-structured" to setOf(":tramai-server"),
            )
        val impact =
            resolveCompilerWarningsImpact(
                "tramai-core/src/main/kotlin/dev/tramai/core/X.kt\n",
                dependents,
            )
        assertEquals(
            CompilerWarningsImpact.Modules(
                setOf(":tramai-core", ":tramai-engine", ":tramai-structured", ":tramai-server"),
            ),
            impact,
        )
    }

    @Test
    fun `docs and workflow changes are inert`() {
        assertEquals(
            CompilerWarningsImpact.None,
            resolveCompilerWarningsImpact(
                "docs/specs/ci-parallelization-p0-p1.md\n.github/workflows/ci.yml\n",
                emptyMap(),
            ),
        )
    }

    // ── P3-A review round: production-only build-logic matching ─────────
    @Test
    fun `compiler-warnings test sources do not trigger full verification`() {
        // The gate's own test file and fixture build scripts configure no module
        // compilation — classifying them as FULL is exactly the unnecessary
        // rebuild P3-A exists to eliminate.
        assertEquals(
            CompilerWarningsImpact.None,
            resolveCompilerWarningsImpact(
                "build-logic/src/test/kotlin/dev/tramai/build/quality/CompilerWarningsWiringTest.kt\n",
                emptyMap(),
            ),
        )
        assertEquals(
            CompilerWarningsImpact.None,
            resolveCompilerWarningsImpact(
                "build-logic/src/test/resources/canonical-probe-fixture/build.gradle.kts\n",
                emptyMap(),
            ),
        )
    }

    @Test
    fun `compiler-warnings production sources still trigger full verification`() {
        assertEquals(
            CompilerWarningsImpact.Full,
            resolveCompilerWarningsImpact(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/CompilerWarningsTasks.kt\n",
                emptyMap(),
            ),
        )
        assertEquals(
            CompilerWarningsImpact.Full,
            resolveCompilerWarningsImpact(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/CompilerWarningsPlugin.kt\n",
                emptyMap(),
            ),
        )
        assertEquals(
            CompilerWarningsImpact.Full,
            resolveCompilerWarningsImpact("build-logic/build.gradle.kts\n", emptyMap()),
        )
    }

    // ── P3-A review round: non-vacuity guard ────────────────────────────
    @Test
    fun `modules impact verifies its compile units or falls back to full`() {
        val known = setOf(":tramai-core", ":tramai-engine")
        assertEquals(
            setOf(":tramai-core"),
            resolveVerifyModules(CompilerWarningsImpact.Modules(setOf(":tramai-core")), false, known),
        )
        // A path with no matching compile unit must never be silently dropped.
        assertEquals(
            known,
            resolveVerifyModules(CompilerWarningsImpact.Modules(setOf(":phantom")), false, known),
        )
        // Zero selected units would pass vacuously — fall back to full.
        assertEquals(
            known,
            resolveVerifyModules(CompilerWarningsImpact.Modules(emptySet()), false, known),
        )
        // Full impact and baseline edits are always exhaustive.
        assertEquals(known, resolveVerifyModules(CompilerWarningsImpact.Full, false, known))
        assertEquals(known, resolveVerifyModules(CompilerWarningsImpact.None, true, known))
    }
}
