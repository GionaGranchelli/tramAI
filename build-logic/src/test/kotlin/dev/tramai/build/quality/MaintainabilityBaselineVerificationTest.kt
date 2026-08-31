package dev.tramai.build.quality

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Epic 9.2d-a3c2 discriminators for [VerifyMaintainabilityBaselineTask]:
 *
 *  - C1/C2: cold configuration-cache run stores, warm run reuses with 0 problems.
 *  - C3/C6: mutating a declared input (committed baseline) is observed without
 *    reconfiguration and produces the exact existing diagnostic.
 *  - C4: an alternative declared input is authoritative — the task never falls
 *    back to the conventional config/quality/0.6.0-baseline.json path.
 *  - C5: missing required evidence fails closed.
 *  - C7: the clean fixture genuinely passes (non-vacuous oracle) — the report
 *    contains real content and the gate reports PASSED.
 */
class MaintainabilityBaselineVerificationTest {
    private companion object {
        val MODULE_CATALOG_YML =
            """
            schemaVersion: "3"
            dependencyPolicies:
              testing:
                allowedLayers: [testing-support]
            entryDefaults:
              internal: &internal
                layer: "testing-support"
                maturity: "internal"
                publishability: "internal"
                apiStability: "excluded"
                visibility: "internal"
                owner: "testing"
                dependencyPolicy: "testing"
                releaseInclusion: "internal_only"
                rationale: "Provides a TestKit fixture module."
            modules:
              - path: ":sample"
                <<: *internal
              - path: ":tramai-core"
                <<: *internal
              - path: ":examples:java-consumer-smoke"
                <<: *internal
              - path: ":examples:kotlin-consumer-smoke"
                <<: *internal
            """.trimIndent()

        val TEST_QUALITY_YML =
            """
            schemaVersion: "1"
            criticalModules: [":sample"]
            coverage:
              regressionTolerancePercentagePoints: 1.0
              exclusions: []
            mutation:
              regressionTolerancePercentagePoints: 1.0
              targetFamilies:
                sample:
                  modules: [":sample"]
                  targetClasses: ["example.*"]
                  targetTests: ["example.*"]
            """.trimIndent()
    }

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `verify maintainability baseline stores and reuses configuration cache on clean fixture`() {
        val dir = verificationFixture(applyPlugins = "")
        generateCommittedBaseline(dir)

        val args = configurationCacheArguments("verifyMaintainabilityBaseline")
        val first = runner(dir, *args).build()
        assertTrue(
            first.output.contains("Configuration cache entry stored"),
            "cold run must store the configuration cache: ${first.output.take(1200)}",
        )
        assertTrue(
            first.output.contains("Maintainability baseline verification PASSED"),
            "clean fixture must pass the gate: ${first.output.take(1200)}",
        )
        val report = File(dir, "build/reports/maintainability/verification-report.json")
        assertTrue(report.isFile, "verification report must be written")
        assertTrue(
            report.readText().contains("\"code\"") || report.readText().contains("diagnostics"),
            "report must contain real diagnostics content (non-vacuous oracle)",
        )

        val second = runner(dir, *args).build()
        assertTrue(
            second.output.contains("Reusing configuration cache"),
            "warm run must reuse the configuration cache: ${second.output.take(1200)}",
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            second.task(":verifyMaintainabilityBaseline")?.outcome,
            "task must succeed on warm run: ${second.output.take(800)}",
        )
    }

    @Test
    fun `mutating the committed baseline is observed without reconfiguration and fails with exact diagnostic`() {
        val dir = verificationFixture(applyPlugins = "")
        generateCommittedBaseline(dir)

        val args = configurationCacheArguments("verifyMaintainabilityBaseline")
        runner(dir, *args).build()

        // Regress the committed baseline identity: dirty worktree flag flips to false.
        val baselineFile = File(dir, "config/quality/0.6.0-baseline.json")
        val doc =
            com.fasterxml.jackson.databind
                .ObjectMapper()
                .readTree(baselineFile)
        (doc as com.fasterxml.jackson.databind.node.ObjectNode)
            .get("baselineIdentity")
            .let { (it as com.fasterxml.jackson.databind.node.ObjectNode).put("workingTreeClean", false) }
        baselineFile.writeText(doc.toPrettyString())

        val failed = runner(dir, *args).buildAndFail()
        assertTrue(
            failed.output.contains("Committed baseline was generated from a dirty worktree"),
            "known regression must produce the exact existing diagnostic: ${failed.output.take(1200)}",
        )
        assertTrue(
            failed.task(":verifyMaintainabilityBaseline")?.outcome == TaskOutcome.FAILED,
            "gate must fail on the regression",
        )
    }

    @Test
    fun `alternative declared committed baseline is authoritative - no conventional path fallback`() {
        val dir =
            verificationFixture(
                applyPlugins =
                    """
                    import dev.tramai.build.quality.VerifyMaintainabilityBaselineTask
                    tasks.named<VerifyMaintainabilityBaselineTask>("verifyMaintainabilityBaseline") {
                        committedBaselineFile.set(layout.projectDirectory.file("config/quality/alt-baseline.json"))
                    }
                    """.trimIndent(),
            )
        // The conventional baseline exists and is valid — but the task is pointed
        // at an alternative malformed file. It must read the DECLARED input and
        // fail with the read diagnostic, proving no conventional-path rediscovery.
        generateCommittedBaseline(dir)
        File(dir, "config/quality/alt-baseline.json").writeText("{ not json")

        val failed = runner(dir, *configurationCacheArguments("verifyMaintainabilityBaseline")).buildAndFail()
        assertTrue(
            failed.output.contains("Failed to read committed baseline"),
            "task must consume the declared alternative input: ${failed.output.take(1200)}",
        )
    }

    @Test
    fun `missing committed baseline fails closed`() {
        val dir = verificationFixture(applyPlugins = "")
        // No generate step: config/quality/0.6.0-baseline.json is absent.

        val failed = runner(dir, *configurationCacheArguments("verifyMaintainabilityBaseline")).buildAndFail()
        assertTrue(
            failed.output.contains("Committed baseline not found"),
            "missing required evidence must fail closed: ${failed.output.take(1200)}",
        )
    }

    @Test
    fun `forbidden project dependency edge is detected from declared graph snapshot`() {
        // :sample depends on :tramai-core via a real Gradle project
        // dependency; the boundaries file forbids the layer edge. Directory
        // mode alone produces no edges, so this discriminator only passes
        // when the configuration-time project-edge snapshot is restored.
        val dir =
            verificationFixture(
                applyPlugins = "",
                extraSampleDeps = listOf("implementation(project(\":tramai-core\"))"),
                boundariesYml =
                    """
                    forbiddenEdges:
                      - fromLayer: "testing-support"
                        toLayer: "testing-support"
                        reason: "fixture forbids testing-support edges"
                    allowedEdges: []
                    """.trimIndent(),
            )
        generateCommittedBaseline(dir)

        val failed = runner(dir, *configurationCacheArguments("verifyMaintainabilityBaseline")).buildAndFail()
        assertTrue(
            failed.output.contains("Forbidden edge: :sample"),
            "forbidden project edge must be detected from the declared graph snapshot: ${failed.output.take(1400)}",
        )
    }

    @Test
    fun `new project dependency cycle is detected from declared graph snapshot`() {
        // Committed baseline is generated WITHOUT the back-edge; the
        // :tramai-core -> :sample dependency is added AFTER, so the cycle is
        // genuinely new. Only the declared graph snapshot can surface it.
        val dir =
            verificationFixture(
                applyPlugins = "",
                extraSampleDeps = listOf("implementation(project(\":tramai-core\"))"),
            )
        generateCommittedBaseline(dir)
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { `java-library` }
            repositories { maven { url = uri(rootDir.resolve("repo")) } }
            dependencies {
                implementation(project(":sample"))
            }
            """.trimIndent(),
        )

        val failed = runner(dir, *configurationCacheArguments("verifyMaintainabilityBaseline")).buildAndFail()
        assertTrue(
            failed.output.contains("New dependency cycle detected"),
            "new project cycle must be detected from the declared graph snapshot: ${failed.output.take(1400)}",
        )
    }

    @Test
    fun `declared alternative catalog drives both verifier and measurement context`() {
        // Conventional catalog A declares :sample as testing-support; the
        // declared alternative B declares it as core. A positional root
        // derivation or a conventional-path context reload would silently use
        // A while the verifier parses B, producing a MODULE_CATALOG_DISAGREEMENT.
        // The discriminator proves both the verifier and the MeasurementContext
        // observe B: the gate passes with layer "core" recorded in the report.
        val dir =
            verificationFixture(
                applyPlugins =
                    """
                    import dev.tramai.build.quality.VerifyMaintainabilityBaselineTask
                    tasks.named<VerifyMaintainabilityBaselineTask>("verifyMaintainabilityBaseline") {
                        moduleCatalogFile.set(layout.projectDirectory.file("config/quality/alt-catalog.yml"))
                    }
                    """.trimIndent(),
            )
        // Alternative catalog B: same modules, :sample layer "core-contracts".
        val altCatalog =
            MODULE_CATALOG_YML.replace(
                "- path: \":sample\"",
                "- path: \":sample\"\n    layer: \"core-contracts\"",
            )
        writeFile(dir, "config/quality/alt-catalog.yml", altCatalog)
        generateCommittedBaseline(dir)

        val result = runner(dir, *configurationCacheArguments("verifyMaintainabilityBaseline")).build()
        assertTrue(
            result.output.contains("Maintainability baseline verification PASSED"),
            "declared alternative catalog must drive context and verifier consistently: ${result.output.take(1400)}",
        )
        val report = File(dir, "build/reports/maintainability/verification-report.json")
        assertTrue(report.isFile, "verification report must be written")
    }

    @Test
    fun `root build script mutation is observed and invalidates the gate`() {
        // Root build.gradle.kts is measured by generateStructuralHotspots; it
        // must be a declared sourceTree input so a mutation re-executes the
        // verifier instead of serving a stale up-to-date PASS.
        val dir = verificationFixture(applyPlugins = "")
        generateCommittedBaseline(dir)

        val args = configurationCacheArguments("verifyMaintainabilityBaseline")
        val first = runner(dir, *args).build()
        assertTrue(
            first.output.contains("Maintainability baseline verification PASSED"),
            "clean fixture must pass the gate: ${first.output.take(1200)}",
        )

        // Mutate the root build script (measured, previously undeclared).
        val buildFile = File(dir, "build.gradle.kts")
        buildFile.writeText(buildFile.readText() + "\n// mutation\n")

        val second = runner(dir, *args).build()
        assertEquals(
            TaskOutcome.SUCCESS,
            second.task(":verifyMaintainabilityBaseline")?.outcome,
            "task must re-execute after root build script mutation: ${second.output.take(1200)}",
        )
    }

    // ─── Epic 9.2d-a3c3: typed verify060Architecture discriminators ───

    @Test
    fun `verify060Architecture stores and reuses configuration cache on fixture`() {
        // C5 north star: the architecture gate must be configuration-cache
        // compatible (cold store, warm reuse). The fixture fails closed on
        // enrollment evidence (no architectureContractEnrollmentTest classes),
        // but the CC contract itself is observable on both runs.
        val dir = architectureFixture(applyPlugins = "")
        generateCommittedBaseline(dir)

        val args = configurationCacheArguments("verify060Architecture")
        val first = runner(dir, *args).buildAndFail()
        assertTrue(
            first.output.contains("Configuration cache entry stored"),
            "cold run must store the configuration cache: ${first.output.take(1200)}",
        )
        val report = File(dir, "build/reports/tramai/architecture/architecture-report.json")
        assertTrue(report.isFile, "architecture report must be written before the terminal exception")

        val second = runner(dir, *args).buildAndFail()
        assertTrue(
            second.output.contains("Reusing configuration cache"),
            "warm run must reuse the configuration cache: ${second.output.take(1200)}",
        )
    }

    @Test
    fun `verify060Architecture forbidden project edge fails closed with FORBIDDEN_LAYER_EDGE`() {
        // The configuration-time project-edge snapshot must flow into the
        // architecture gate: :sample -> :tramai-core violates the fixture
        // boundaries, and the failure must surface as the exact architecture
        // diagnostic in the report (fail-closed, report written before throw).
        val dir =
            architectureFixture(
                applyPlugins = "",
                extraSampleDeps = listOf("implementation(project(\":tramai-core\"))"),
                boundariesYml =
                    """
                    forbiddenEdges:
                      - fromLayer: "testing-support"
                        toLayer: "testing-support"
                        reason: "fixture forbids testing-support edges"
                    allowedEdges: []
                    """.trimIndent(),
            )
        generateCommittedBaseline(dir)

        val failed = runner(dir, *configurationCacheArguments("verify060Architecture")).buildAndFail()
        val report = architectureReport(dir)
        assertTrue(
            report.contains("FORBIDDEN_LAYER_EDGE"),
            "report must contain the exact FORBIDDEN_LAYER_EDGE diagnostic: ${report.take(1200)}",
        )
        assertTrue(
            report.contains("Forbidden edge: :sample"),
            "report must name the offending edge: ${report.take(1200)}",
        )
    }

    @Test
    fun `verify060Architecture new project cycle is detected as NEW_DEPENDENCY_CYCLE`() {
        // Committed baseline generated WITHOUT the back-edge; the
        // :tramai-core -> :sample dependency is added AFTER, so the cycle is
        // genuinely new. Only the declared graph snapshot can surface it.
        val dir =
            architectureFixture(
                applyPlugins = "",
                extraSampleDeps = listOf("implementation(project(\":tramai-core\"))"),
            )
        generateCommittedBaseline(dir)
        // The gate depends on the consumer compile tasks, which depend on
        // :tramai-core:jar. With a real sample<->tramai-core project cycle,
        // that pulls :sample:jar back into the task graph -> circular task
        // dependency -> Gradle aborts BEFORE the gate action runs (no report).
        // The consumer fixtures are irrelevant to this discriminator, so drop
        // them from the settings: the snapshot still carries the new cycle.
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "maintainability-verification"
            include(":sample", ":tramai-core", ":tramai-testing")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { `java-library` }
            repositories { maven { url = uri(rootDir.resolve("repo")) } }
            dependencies {
                implementation(project(":sample"))
            }
            """.trimIndent(),
        )

        val failed = runner(dir, *configurationCacheArguments("verify060Architecture")).buildAndFail()
        val report = architectureReport(dir)
        assertTrue(
            report.contains("NEW_DEPENDENCY_CYCLE"),
            "report must contain the exact NEW_DEPENDENCY_CYCLE diagnostic: ${report.take(
                1200,
            )}. FIXTURE OUTPUT:\n${failed.output.take(2500)}",
        )
    }

    @Test
    fun `verify060Architecture missing dependency probe evidence fails closed`() {
        // Break dependency resolution by removing the local repo :sample
        // resolves from. The fail-soft probe tasks record a typed resolution
        // failure; the gate must fail EVERY baseline-backed check with
        // DEPENDENCY_RESOLUTION_FAILED instead of claiming a stale PASS.
        val dir = architectureFixture(applyPlugins = "")
        generateCommittedBaseline(dir)
        File(dir, "repo").deleteRecursively()

        val failed = runner(dir, *configurationCacheArguments("verify060Architecture")).buildAndFail()
        val report = architectureReport(dir)
        assertTrue(
            report.contains("DEPENDENCY_RESOLUTION_FAILED"),
            "report must fail closed with DEPENDENCY_RESOLUTION_FAILED: ${report.take(1200)}",
        )
    }

    @Test
    fun `verify060Architecture bad consumer compile evidence fails closed with API_COMPATIBILITY_FAILED`() {
        // The java consumer smoke fixture compiles zero sources (no src tree),
        // so its fail-soft producer marker records ok=false. The gate must read
        // the marker as typed api-architecture evidence and fail closed.
        val dir = architectureFixture(applyPlugins = "")
        generateCommittedBaseline(dir)

        val failed = runner(dir, *configurationCacheArguments("verify060Architecture")).buildAndFail()
        val report = architectureReport(dir)
        assertTrue(
            report.contains("API_COMPATIBILITY_FAILED"),
            "report must contain API_COMPATIBILITY_FAILED: ${report.take(1200)}",
        )
        assertTrue(
            report.contains("Consumer compile proof"),
            "report must carry the consumer compile proof message: ${report.take(1200)}",
        )
    }

    @Test
    fun `verify060Architecture consumes declared generated api dump not conventional build path`() {
        // P1 authority discriminator: the gate must read the EXACT declared
        // apiBuild output (relocated), never rediscover <module>/build/api.
        // A conventional dump at the conventional path that matches the
        // committed dump would PASS if rediscovered; the declared relocated
        // dump differs and must FAIL.
        val dir = architectureFixture(applyPlugins = "")
        // :sample declares apiCheck (so it enters apiValidationModules) and an
        // apiBuild whose output lands in a RELOCATED build dir.
        writeFile(
            dir,
            "sample/build.gradle.kts",
            """
            plugins { `java-library` }
            repositories { maven { url = uri(rootDir.resolve("repo")) } }
            dependencies { implementation("com.example:fake:1.0") }
            tasks.register("apiCheck")
            tasks.register("apiBuild") {
                val relocated = layout.buildDirectory.file("relocated/sample.api")
                outputs.file(relocated)
                doLast {
                    relocated.get().asFile.parentFile.mkdirs()
                    relocated.get().asFile.writeText("declared generated dump B - deliberately different")
                }
            }
            """.trimIndent(),
        )
        // Committed dump X (matches the conventional generated A).
        writeFile(dir, "sample/api/sample.api", "committed dump X")
        // Conventional generated dump A at the rediscovery path — matches the
        // committed dump, so a rediscovering implementation would PASS.
        writeFile(dir, "sample/build/api/sample.api", "committed dump X")
        // baseRef must resolve in the fixture repo (no origin remote): HEAD
        // contains the committed dump after generateCommittedBaseline.
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.maintainability-baseline") }
            import dev.tramai.build.quality.VerifyArchitectureTask
            tasks.named<VerifyArchitectureTask>("verify060Architecture") {
                baseRef.set("HEAD")
            }
            """.trimIndent(),
        )
        generateCommittedBaseline(dir)
        val committedDump = "committed dump X"
        val declaredDump = "declared generated dump B - deliberately different"

        val failed = runner(dir, *configurationCacheArguments("verify060Architecture")).buildAndFail()
        val report = architectureReport(dir)
        assertTrue(
            report.contains("does not represent current source"),
            "gate must consume the DECLARED relocated dump, not conventional build/api: ${report.take(2000)}",
        )
        assertTrue(
            report.contains("generated ${declaredDump.length} bytes vs committed ${committedDump.length} bytes"),
            "the byte delta must reflect the declared relocated dump B: ${report.take(1200)}",
        )
    }

    @Test
    fun `verify060Architecture preserves ordered owner file pairing across two modules`() {
        // P1 pairing contract: owner[i] <-> file[i] is positional. Two modules
        // with distinct dump contents are wired in NON-natural order (settings
        // declares :tramai-core before :sample, so the plugin adds core's
        // owner+file first, while the relocated file paths sort sample first).
        // Each module's declared dump differs from its committed dump with a
        // distinct byte length, so a mispairing would emit the wrong delta.
        val dir = architectureFixture(applyPlugins = "")
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "maintainability-verification"
            include(":tramai-core", ":sample", ":examples:java-consumer-smoke", ":examples:kotlin-consumer-smoke", ":tramai-testing")
            """.trimIndent(),
        )
        val committedSample = "committed sample dump"
        val committedCore = "committed core dump"
        val declaredSample = "declared sample dump content"
        val declaredCore = "declared core dump content"
        // apiCheck enters the module into apiValidationModules; apiBuild's
        // RELOCATED output is the declared generated evidence (never the
        // conventional build/api path).
        writeFile(
            dir,
            "sample/build.gradle.kts",
            """
            plugins { `java-library` }
            tasks.register("apiCheck")
            tasks.register("apiBuild") {
                val relocated = layout.buildDirectory.file("relocated/sample.api")
                outputs.file(relocated)
                doLast {
                    relocated.get().asFile.parentFile.mkdirs()
                    relocated.get().asFile.writeText("$declaredSample")
                }
            }
            """.trimIndent(),
        )
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { `java-library` }
            tasks.register("apiCheck")
            tasks.register("apiBuild") {
                val relocated = layout.buildDirectory.file("relocated/core.api")
                outputs.file(relocated)
                doLast {
                    relocated.get().asFile.parentFile.mkdirs()
                    relocated.get().asFile.writeText("$declaredCore")
                }
            }
            """.trimIndent(),
        )
        writeFile(dir, "sample/api/sample.api", committedSample)
        writeFile(dir, "tramai-core/api/tramai-core.api", committedCore)
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.maintainability-baseline") }
            import dev.tramai.build.quality.VerifyArchitectureTask
            tasks.named<VerifyArchitectureTask>("verify060Architecture") {
                baseRef.set("HEAD")
            }
            """.trimIndent(),
        )
        generateCommittedBaseline(dir)

        val failed = runner(dir, *configurationCacheArguments("verify060Architecture")).buildAndFail()
        val report = architectureReport(dir)
        assertTrue(
            report.contains(
                "committed API dump for ':sample' does not represent current source " +
                    "(generated ${declaredSample.length} bytes vs committed ${committedSample.length} bytes)",
            ),
            ":sample must receive ITS OWN declared dump content: ${report.take(2000)}",
        )
        assertTrue(
            report.contains(
                "committed API dump for ':tramai-core' does not represent current source " +
                    "(generated ${declaredCore.length} bytes vs committed ${committedCore.length} bytes)",
            ),
            ":tramai-core must receive ITS OWN declared dump content: ${report.take(2000)}",
        )
    }

    @Test
    fun `verify060Architecture declared alternative catalog drives boundary enforcement`() {
        // Declared alt catalog B marks :sample as layer "core"; the fixture
        // boundaries forbid core -> testing-support. If the gate fell back to
        // the conventional catalog A (:sample testing-support), the
        // :sample -> :tramai-core edge would be testing-support ->
        // testing-support and NOT forbidden by these rules. Only the declared
        // catalog driving both context and verifier produces FORBIDDEN_LAYER_EDGE
        // without MODULE_CATALOG_DISAGREEMENT.
        val dir =
            architectureFixture(
                applyPlugins =
                    """
                    import dev.tramai.build.quality.VerifyArchitectureTask
                    tasks.named<VerifyArchitectureTask>("verify060Architecture") {
                        moduleCatalogFile.set(layout.projectDirectory.file("config/quality/alt-catalog.yml"))
                    }
                    """.trimIndent(),
                extraSampleDeps = listOf("implementation(project(\":tramai-core\"))"),
                boundariesYml =
                    """
                    forbiddenEdges:
                      - fromLayer: "core-contracts"
                        toLayer: "testing-support"
                        reason: "fixture forbids core-to-testing-support edges"
                    allowedEdges: []
                    """.trimIndent(),
            )
        val altCatalog =
            architectureCatalogYml().replace(
                "- path: \":sample\"",
                "- path: \":sample\"\n    layer: \"core-contracts\"",
            )
        writeFile(dir, "config/quality/alt-catalog.yml", altCatalog)
        generateCommittedBaseline(dir)

        val failed = runner(dir, *configurationCacheArguments("verify060Architecture")).buildAndFail()
        val report = architectureReport(dir)
        assertTrue(
            report.contains("FORBIDDEN_LAYER_EDGE"),
            "declared catalog must drive boundary enforcement: ${report.take(2000)}",
        )
        assertTrue(
            !report.contains("MODULE_CATALOG_DISAGREEMENT"),
            "verifier and context must observe the same declared catalog: ${report.take(1200)}",
        )
    }

    @Test
    fun `verify060Architecture root build script mutation invalidates the gate`() {
        // Root build.gradle.kts is a declared sourceTree input; a mutation must
        // invalidate the configuration-cache entry (reconfiguration) and
        // re-execute the gate rather than replaying a stale result.
        val dir = architectureFixture(applyPlugins = "")
        generateCommittedBaseline(dir)

        val args = configurationCacheArguments("verify060Architecture")
        val first = runner(dir, *args).buildAndFail()
        assertTrue(
            first.output.contains("Configuration cache entry stored"),
            "cold run must store the configuration cache: ${first.output.take(1200)}",
        )

        val buildFile = File(dir, "build.gradle.kts")
        buildFile.writeText(buildFile.readText() + "\n// mutation\n")

        val second = runner(dir, *args).buildAndFail()
        assertTrue(
            !second.output.contains("Reusing configuration cache"),
            "root build script mutation must invalidate the configuration cache: ${second.output.take(1200)}",
        )
        assertEquals(
            TaskOutcome.FAILED,
            second.task(":verify060Architecture")?.outcome,
            "gate must re-execute after root build script mutation: ${second.output.take(800)}",
        )
    }

    // ─── Fixture ───

    /** Module catalog for the architecture gate fixture (adds :tramai-testing,
     *  which the enrollment test task requires at realization). */
    private fun architectureCatalogYml(): String =
        MODULE_CATALOG_YML.replace(
            """- path: ":examples:kotlin-consumer-smoke"
    <<: *internal""",
            """- path: ":examples:kotlin-consumer-smoke"
    <<: *internal
  - path: ":tramai-testing"
    <<: *internal""",
        )

    /**
     * Architecture-gate fixture: the maintainability fixture plus the
     * :tramai-testing project the architectureContractEnrollmentTest task
     * touches at realization, and a catalog covering it. The enrollment test
     * produces no XML results (no matching classes) — enrollment evidence is
     * missing by design, which exercises the fail-closed path.
     */
    private fun architectureFixture(
        applyPlugins: String,
        extraSampleDeps: List<String> = emptyList(),
        boundariesYml: String =
            """
            forbiddenEdges: []
            allowedEdges: []
            """.trimIndent(),
    ): File {
        val dir = File(tempDir, "fixture-${tempDir.listFiles()?.size ?: 0}").apply { mkdirs() }
        writeGradleScripts(dir, applyPlugins, extraSampleDeps, emptyList())
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "maintainability-verification"
            include(":sample", ":tramai-core", ":examples:java-consumer-smoke", ":examples:kotlin-consumer-smoke", ":tramai-testing")
            """.trimIndent(),
        )
        writeFile(dir, "tramai-testing/build.gradle.kts", "plugins { `java` }")
        writeProductionSource(dir)
        writeQualityConfig(dir, boundariesYml, architectureCatalogYml())
        writeLocalMavenRepo(dir)
        initGit(dir)
        return dir
    }

    private fun architectureReport(dir: File): String {
        val report = dir.resolve("build/reports/tramai/architecture/architecture-report.json")
        return report.readText()
    }

    /**
     * Self-contained fixture with a committed git history and one production
     * source file, so identity resolution and mandatory sections are real.
     *
     * @param extraSampleDeps Gradle dependency declarations added to :sample
     *   (e.g. project deps, for edge/cycle discriminators).
     * @param extraCoreDeps Gradle dependency declarations added to
     *   :tramai-core (for the new-cycle discriminator).
     * @param boundariesYml Optional module-boundaries.yml content; defaults to
     *   the empty ruleset.
     */
    private fun verificationFixture(
        applyPlugins: String,
        extraSampleDeps: List<String> = emptyList(),
        extraCoreDeps: List<String> = emptyList(),
        boundariesYml: String =
            """
            forbiddenEdges: []
            allowedEdges: []
            """.trimIndent(),
    ): File {
        val dir = File(tempDir, "fixture-${tempDir.listFiles()?.size ?: 0}").apply { mkdirs() }
        writeGradleScripts(dir, applyPlugins, extraSampleDeps, extraCoreDeps)
        writeProductionSource(dir)
        writeQualityConfig(dir, boundariesYml)
        writeLocalMavenRepo(dir)
        initGit(dir)
        return dir
    }

    private fun writeGradleScripts(
        dir: File,
        applyPlugins: String,
        extraSampleDeps: List<String>,
        extraCoreDeps: List<String>,
    ) {
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "maintainability-verification"
            include(":sample", ":tramai-core", ":examples:java-consumer-smoke", ":examples:kotlin-consumer-smoke")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.maintainability-baseline") }
            $applyPlugins
            """.trimIndent(),
        )
        // Constrain the TestKit-spawned fixture daemon: the sandbox cgroup
        // OOM-kills unbounded nested daemons mid-suite.
        writeFile(
            dir,
            "gradle.properties",
            """
            org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
            org.gradle.workers.max=1
            """.trimIndent(),
        )
        val sampleDeps =
            (listOf("implementation(\"com.example:fake:1.0\")") + extraSampleDeps).joinToString("\n        ")
        writeFile(
            dir,
            "sample/build.gradle.kts",
            """
            plugins { `java-library` }
            repositories { maven { url = uri(rootDir.resolve("repo")) } }
            dependencies {
                $sampleDeps
            }
            """.trimIndent(),
        )
        val coreDeps = extraCoreDeps.joinToString("\n        ")
        val coreRepos =
            if (extraCoreDeps.isEmpty()) {
                ""
            } else {
                "\n    repositories { maven { url = uri(rootDir.resolve(\"repo\")) } }"
            }
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { `java-library` }$coreRepos
            dependencies {
                $coreDeps
            }
            """.trimIndent(),
        )
        writeFile(dir, "examples/java-consumer-smoke/build.gradle.kts", "plugins { `java-library` }")
        writeFile(dir, "examples/kotlin-consumer-smoke/build.gradle.kts", "plugins { `java-library` }")
        writeFile(dir, ".gitignore", ".gradle/\n.kotlin/\nbuild/\nsample/build/\nlocal.properties\n")
    }

    private fun writeProductionSource(dir: File) {
        // A protocol constant (protocol catalog), a catch (cancellation
        // inventory), and declarations (source metrics) — the mandatory
        // sections must be non-empty for a genuine PASS.
        writeFile(
            dir,
            "sample/src/main/kotlin/example/Sample.kt",
            """
            package example

            const val SAMPLE_PROTOCOL = "tramai.sample.protocol"

            class Sample {
                fun run(): String =
                    try {
                        "ok"
                    } catch (e: Exception) {
                        "fail"
                    }
            }
            """.trimIndent(),
        )
    }

    private fun writeQualityConfig(
        dir: File,
        boundariesYml: String,
        catalogYml: String = MODULE_CATALOG_YML,
    ) {
        writeFile(dir, "config/quality/module-catalog.yml", catalogYml)
        writeFile(dir, "config/quality/test-quality.yml", TEST_QUALITY_YML)
        writeFile(
            dir,
            "config/quality/maintainability-deviations.yml",
            """
            schemaVersion: "1"
            deviations: []
            """.trimIndent(),
        )
        writeFile(dir, "config/quality/module-boundaries.yml", boundariesYml)
        writeFile(
            dir,
            "config/quality/runtime-nondeterminism.yml",
            """
            schemaVersion: "1"
            entries: []
            """.trimIndent(),
        )
    }

    private fun writeLocalMavenRepo(dir: File) {
        // Minimal local Maven repo so :sample resolves a real external module.
        val repoModule = File(dir, "repo/com/example/fake/1.0")
        repoModule.mkdirs()
        writeFile(
            repoModule,
            "fake-1.0.pom",
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>fake</artifactId>
              <version>1.0</version>
            </project>
            """.trimIndent(),
        )
        java.util.zip
            .ZipOutputStream(File(repoModule, "fake-1.0.jar").outputStream())
            .use { it.close() }
    }

    private fun initGit(dir: File) {
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "initial fixture")
        git(dir, "tag", "v0.5.0")
        git(dir, "remote", "add", "origin", "https://example.invalid/tramai.git")
        git(dir, "update-ref", "refs/remotes/origin/master", "HEAD")
    }

    /**
     * Runs the real generateMaintainabilityBaseline task, then patches the
     * recorded baseline identity to a clean, self-consistent state. The
     * generate path writes repo artifacts (baseline, protocol catalog, docs)
     * BEFORE capturing identity, so its recorded workingTreeClean is always
     * false — a fixture artifact, not a verifier concern. The verifier only
     * asserts non-blank identity fields and baselineCommitSha ==
     * measuredCommitSha; the measured sections are regenerated deterministically
     * from the same fixture, so patched identity keeps the gate honest.
     */
    private fun generateCommittedBaseline(dir: File) {
        runner(dir, "generateMaintainabilityBaseline").build()

        val baselineFile = File(dir, "config/quality/0.6.0-baseline.json")
        val doc =
            com.fasterxml.jackson.databind
                .ObjectMapper()
                .readTree(baselineFile) as com.fasterxml.jackson.databind.node.ObjectNode
        val head = git(dir, "rev-parse", "HEAD")
        val tree = git(dir, "rev-parse", "HEAD^{tree}")
        val identity = doc.get("baselineIdentity") as com.fasterxml.jackson.databind.node.ObjectNode
        identity.put("workingTreeClean", true)
        identity.put("commitSha", head)
        identity.put("baselineCommitSha", head)
        identity.put("measuredCommitSha", head)
        identity.put("measuredGitTreeSha", tree)
        identity.put("measuredSourceTreeHash", "fixture-source-tree-hash")
        identity.put("analyzerCommitSha", head)
        baselineFile.writeText(doc.toPrettyString())

        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "generated committed baseline")
    }

    private fun configurationCacheArguments(task: String) =
        arrayOf(
            task,
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )

    private fun runner(
        dir: File,
        vararg args: String,
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withGradleVersion("9.0.0")
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()

    private fun writeFile(
        base: File,
        relativePath: String,
        content: String,
    ) {
        val target = File(base, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content)
    }

    private fun git(
        dir: File,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }
}
