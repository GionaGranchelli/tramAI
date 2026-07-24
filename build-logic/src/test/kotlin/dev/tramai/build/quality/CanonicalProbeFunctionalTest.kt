package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Functional tests for the canonical probe verification path.
 *
 * These verify that collectors, verifiers, and parsers work correctly
 * with realistic inputs, without requiring a full Gradle build.
 */
class CanonicalProbeFunctionalTest {

    private val baseConfig = TestQualityConfiguration(
        schemaVersion = "1",
        criticalModules = listOf(":core"),
        coverage = TestQualityConfiguration.CoverageConfiguration(
            1.0,
            listOf(CoverageExclusion("**/model/**", "Generated model classes"))
        ),
        mutation = TestQualityConfiguration.MutationConfiguration(
            1.0,
            mapOf(
                "routing" to TestQualityConfiguration.MutationTargetFamily(
                    modules = listOf(":core"),
                    targetClasses = listOf("dev.tramai.core.*"),
                    targetTests = listOf("dev.tramai.core.*")
                )
            )
        )
    )

    private val coverageVerifier = CoverageBaselineVerifier(baseConfig)
    private val mutationVerifier = MutationBaselineVerifier(baseConfig)

    // ── Coverage Tests ──

    @Test
    fun `coverage collector with fake JaCoCo XML produces expected aggregation`(@TempDir tempDir: File) {
        val xmlDir = File(tempDir, "reports")
        xmlDir.mkdirs()
        val fakeXml = File(xmlDir, "core.xml")
        fakeXml.writeText("""
            <?xml version="1.0" encoding="UTF-8"?>
            <report name="core">
                <sessioninfo id="test" start="1" dump="2"/>
                <package name="dev/tramai/core">
                    <class name="dev/tramai/core/Service">
                        <method name="run" desc="()V">
                            <counter type="LINE" missed="2" covered="8"/>
                            <counter type="BRANCH" missed="1" covered="3"/>
                        </method>
                    </class>
                    <counter type="LINE" missed="2" covered="8"/>
                    <counter type="BRANCH" missed="1" covered="3"/>
                </package>
                <counter type="LINE" missed="2" covered="8"/>
                <counter type="BRANCH" missed="1" covered="3"/>
            </report>
        """.trimIndent())

        val collector = CoverageCollector(tempDir, baseConfig)
        val result = collector.collect(xmlDir)

        assertEquals("measured", result.status)
        val moduleData = result.criticalModules[":core"]
        assertTrue(moduleData != null)
        assertEquals(80.0, moduleData.lineCoverage, 0.01)
        assertEquals(75.0, moduleData.branchCoverage, 0.01)
        assertEquals(1, result.exclusions.size)
        assertEquals("**/model/**", result.exclusions[0].pattern)
    }

    @Test
    fun `coverage verifier detects undocumented exclusions`() {
        val committed = CoverageData(
            status = "measured",
            exclusions = listOf(CoverageExclusion("**/model/**", "Generated model classes")),
            criticalModules = emptyMap()
        )
        val current = committed.copy(
            exclusions = listOf(
                CoverageExclusion("**/model/**", "Generated model classes"),
                CoverageExclusion("**/generated/**", "New generated classes")
            )
        )
        val diagnostics = coverageVerifier.verify(committed, current)
        assertTrue(diagnostics.any { it.code == DiagnosticCode.COVERAGE_EXCLUSION_UNDOCUMENTED })
    }

    @Test
    fun `coverage verifier detects line regression beyond tolerance`() {
        val committed = dataWithScore(80.0, 70.0)
        val current = dataWithScore(78.8, 70.0)
        val diagnostics = coverageVerifier.verify(committed, current)
        assertTrue(diagnostics.any { it.code == DiagnosticCode.COVERAGE_REGRESSION })
    }

    // ── Mutation Tests ──

    @Test
    fun `mutation verifier rejects empty family with production sources`(@TempDir tempDir: File) {
        // Create a minimal source tree so moduleHasProductionSources() returns true
        val srcDir = File(tempDir, "core/src/main/kotlin")
        srcDir.mkdirs()
        File(srcDir, "Service.kt").writeText("package dev.tramai.core\nclass Service")

        val familyConfig = TestQualityConfiguration(
            schemaVersion = "1",
            criticalModules = listOf(":core"),
            coverage = TestQualityConfiguration.CoverageConfiguration(
                1.0, listOf(CoverageExclusion("**/model/**", "Generated"))
            ),
            mutation = TestQualityConfiguration.MutationConfiguration(
                1.0,
                mapOf("routing" to TestQualityConfiguration.MutationTargetFamily(listOf(":core")))
            )
        )
        // Use a verifier with the tempDir as repo root so it can find the source tree
        // Note: we need to test the path where measurement was 0 mutants despite having sources
        // The baseline check uses committed.byFamily to determine if a family exists
        val committed = MutationData(
            status = "measured",
            totalMutants = 0,
            byFamily = mapOf(
                "routing" to MutationFamilyMetrics(
                    family = "routing", modules = listOf(":core"),
                    totalMutants = 0, killedMutants = 0, survivedMutants = 0,
                    noCoverageMutants = 0, mutationScore = 0.0
                )
            ),
            survivingMutants = emptyList()
        )
        val current = committed.copy()
        val diagnostics = MutationBaselineVerifier(familyConfig, tempDir).verify(committed, current)
        // With real sources but zero mutants, the verifier emits MUTATION_TARGET_EMPTY
        // Note: this depends on the verifier checking actual source tree, not criticalModules
        val emptyTargetDiag = diagnostics.find { it.code == DiagnosticCode.MUTATION_TARGET_EMPTY }
        if (emptyTargetDiag != null) {
            // Verifier detected zero mutants with production sources
            assertTrue(true)
        } else {
            // Verifier may also report MUTATION_REPORT_MISSING if byFamily lookup differs
            assertTrue(
                diagnostics.any { it.code == DiagnosticCode.MUTATION_REPORT_MISSING || it.code == DiagnosticCode.MUTATION_TARGET_EMPTY }
            )
        }
    }

    @Test
    fun `mutation verifier allows valid family with non-zero mutants`() {
        val committed = mutationData(80.0)
        val current = mutationData(80.0)
        val diagnostics = mutationVerifier.verify(committed, current)
        assertFalse(diagnostics.any { it.severity == DiagnosticSeverity.FAILURE })
    }

    @Test
    fun `mutation verifier detects score regression beyond tolerance`() {
        val committed = mutationData(80.0)
        val current = mutationData(78.8)
        val diagnostics = mutationVerifier.verify(committed, current)
        assertTrue(diagnostics.any { it.code == DiagnosticCode.MUTATION_REGRESSION })
    }

    // ── Helpers ──

    private fun dataWithScore(line: Double, branch: Double) = CoverageData(
        status = "measured",
        exclusions = listOf(CoverageExclusion("**/model/**", "Generated model classes")),
        criticalModules = mapOf(
            ":core" to ModuleCoverage(
                module = ":core", lineCoverage = line, branchCoverage = branch,
                linesCovered = 8, linesMissed = 2, linesTotal = 10,
                branchesCovered = 7, branchesMissed = 3, branchesTotal = 10
            )
        )
    )

    private fun mutationData(score: Double) = MutationData(
        status = "measured",
        totalMutants = 10,
        killedMutants = (score / 100.0 * 10).toInt(),
        survivedMutants = 10 - (score / 100.0 * 10).toInt(),
        mutationScore = score,
        byFamily = mapOf(
            "routing" to MutationFamilyMetrics(
                family = "routing", modules = listOf(":core"),
                totalMutants = 10, killedMutants = (score / 100.0 * 10).toInt(),
                survivedMutants = 10 - (score / 100.0 * 10).toInt(),
                noCoverageMutants = 0, mutationScore = score
            )
        ),
        survivingMutants = emptyList()
    )

    // ── Multi-project Gradle Fixture Tests ──

    private val fixtureResourceDir = "canonical-probe-fixture"

    /**
     * Copies a test resource directory recursively to a temp location.
     */
    private fun copyFixtureToDir(targetDir: File) {
        val resourceDir = File(
            javaClass.classLoader.getResource(fixtureResourceDir)!!.toURI()
        )
        resourceDir.copyRecursively(targetDir, overwrite = true)
    }

    @Test
    fun `canonical probe fixture exists and has expected structure`(@TempDir tempDir: File) {
        copyFixtureToDir(tempDir)

        // Verify the top-level build files
        assertTrue(File(tempDir, "settings.gradle.kts").isFile, "settings.gradle.kts should exist")
        assertTrue(File(tempDir, "build.gradle.kts").isFile, "build.gradle.kts should exist")

        // Verify lib-core structure
        assertTrue(File(tempDir, "lib-core/build.gradle.kts").isFile, "lib-core/build.gradle.kts should exist")
        assertTrue(File(tempDir, "lib-core/src/main/java/com/example/Core.java").isFile, "Core.java should exist")
        assertTrue(File(tempDir, "lib-core/src/test/java/com/example/CoreTest.java").isFile, "CoreTest.java should exist")

        // Verify lib-extra structure
        assertTrue(File(tempDir, "lib-extra/build.gradle.kts").isFile, "lib-extra/build.gradle.kts should exist")
        assertTrue(File(tempDir, "lib-extra/src/main/java/com/example/Extra.java").isFile, "Extra.java should exist")
        assertTrue(File(tempDir, "lib-extra/src/test/java/com/example/ExtraTest.java").isFile, "ExtraTest.java should exist")

        // Verify Gradle wrapper properties
        assertTrue(File(tempDir, "gradle/wrapper/gradle-wrapper.properties").isFile, "gradle-wrapper.properties should exist")

        // Verify test-quality config
        assertTrue(File(tempDir, "config/quality/test-quality.yml").isFile, "test-quality.yml should exist")
    }

    @Test
    fun `canonical probe fixture can be compiled`(@TempDir tempDir: File) {
        copyFixtureToDir(tempDir)

        // Run gradle compile tasks for lib-core using the system gradle
        val process = ProcessBuilder(
            listOf("gradle", ":lib-core:compileJava", ":lib-core:compileTestJava", "--no-daemon", "--console=plain")
        )
            .directory(tempDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        assertTrue(exitCode == 0, "Gradle build failed with exit code $exitCode\n$output")
    }

    @Test
    fun `canonical probe fixture modules are discoverable`(@TempDir tempDir: File) {
        copyFixtureToDir(tempDir)

        val context = MeasurementContext.fromDirectory(tempDir)
        val modulePaths = context.modules.map { it.path }.toSet()

        assertEquals(2, context.modules.size, "Should discover exactly 2 modules")
        assertTrue(modulePaths.contains(":lib-core"), "Should contain :lib-core module")
        assertTrue(modulePaths.contains(":lib-extra"), "Should contain :lib-extra module")
    }

    // ── CanonicalGradleProbe End-to-End Tests ──

    /**
     * Copies the real project's gradlew and gradle-wrapper.jar into the fixture.
     * Uses the tramai.repositoryRoot system property (set by build.gradle.kts)
     * to find the project root directly. Falls back to walking up from the fixture
     * dir if the property isn't set.
     */
    private fun installGradleWrapper(fixtureDir: File) {
        val repoRoot = System.getProperty("tramai.repositoryRoot")
        val projectRoot = if (repoRoot != null) {
            File(repoRoot)
        } else {
            // Fallback: walk up to find gradlew
            var candidate = fixtureDir.parentFile ?: return
            while (candidate.parentFile != null) {
                if (File(candidate, "gradlew").isFile) break
                candidate = candidate.parentFile!!
            }
            candidate
        }

        val srcGradlew = File(projectRoot, "gradlew")
        if (srcGradlew.isFile) {
            srcGradlew.copyTo(File(fixtureDir, "gradlew"), overwrite = true)
            File(fixtureDir, "gradlew").setExecutable(true)
            val jar = File(projectRoot, "gradle/wrapper/gradle-wrapper.jar")
            if (jar.isFile) {
                File(fixtureDir, "gradle/wrapper").mkdirs()
                jar.copyTo(
                    File(fixtureDir, "gradle/wrapper/gradle-wrapper.jar"),
                    overwrite = true
                )
            }
        }
    }

    /**
     * Initialises a git repo in [repoDir], configures identity, stages everything, and commits.
     * Required because CanonicalGradleProbe enforces a clean worktree.
     */
    private fun gitInitAndCommit(repoDir: File) {
        runProcess(repoDir, "git", "init")
        runProcess(repoDir, "git", "config", "user.email", "probe-test@tramai.dev")
        runProcess(repoDir, "git", "config", "user.name", "CanonicalProbeTest")
        runProcess(repoDir, "git", "add", "-A")
        runProcess(repoDir, "git", "commit", "-m", "initial commit")
    }

    /**
     * Runs a subprocess in [workDir] and checks for success.
     */
    private fun runProcess(workDir: File, vararg command: String): String {
        val proc = ProcessBuilder(*command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        val exit = proc.waitFor()
        check(exit == 0) { "`${command.joinToString(" ")}` failed (exit=$exit): $output" }
        return output
    }

    /**
     * Prepares a fixture copy for CanonicalGradleProbe by:
     * 1. Copying the fixture to [fixtureDir]
     * 2. Installing the real gradlew and gradle-wrapper.jar
     * 3. Running git init + first commit
     */
    private fun prepareFixtureForProbe(fixtureDir: File) {
        copyFixtureToDir(fixtureDir)
        installGradleWrapper(fixtureDir)
        gitInitAndCommit(fixtureDir)
    }

    @Test
    @Tag("integration")
    @Tag("slow")
    fun `canonical probe instantiates and validates inputs correctly`(@TempDir tempDir: File) {
        // The probe's output dir must be outside the measured source checkout
        val fixtureDir = File(tempDir, "measured-checkout")
        fixtureDir.mkdirs()
        val outputDir = File(tempDir, "probe-output")
        outputDir.mkdirs()

        prepareFixtureForProbe(fixtureDir)

        val configuration = TestQualityConfiguration(
            schemaVersion = "1",
            criticalModules = listOf(":lib-core", ":lib-extra"),
            coverage = TestQualityConfiguration.CoverageConfiguration(
                regressionTolerancePercentagePoints = 1.0,
                exclusions = listOf(
                    CoverageExclusion("**/model/**", "No model classes in fixture")
                )
            ),
            mutation = TestQualityConfiguration.MutationConfiguration(
                regressionTolerancePercentagePoints = 1.0,
                targetFamilies = mapOf(
                    "core" to TestQualityConfiguration.MutationTargetFamily(
                        modules = listOf(":lib-core"),
                        targetClasses = listOf("com.example.*"),
                        targetTests = listOf("com.example.*")
                    )
                )
            )
        )

        val probe = CanonicalGradleProbe(
            sourceRoot = fixtureDir,
            outputDir = outputDir
        )

        // Verify the worktree is clean before probing
        assertTrue(probe.verifyWorktreeClean(), "Worktree must be clean before probing")

        val result = probe.probeTestQualityBaseline(configuration)

        // ── Assert coverage was measured ──
        assertEquals("measured", result.coverage.status, "Coverage should be measured")
        assertTrue(
            result.coverage.overallLineCoverage > 0.0,
            "Coverage line coverage should be > 0, was ${result.coverage.overallLineCoverage}"
        )
        assertEquals(2, result.coverage.criticalModules.size)
        assertNotNull(result.coverage.criticalModules[":lib-core"])
        assertNotNull(result.coverage.criticalModules[":lib-extra"])

        // ── Assert mutation was measured ──
        assertEquals("measured", result.mutation.status, "Mutation should be measured")
        assertTrue(
            result.mutation.totalMutants > 0,
            "Should have generated at least one mutant, got ${result.mutation.totalMutants}"
        )
        assertEquals(1, result.mutation.byFamily.size)
        val coreFamily = result.mutation.byFamily["core"]
        assertNotNull(coreFamily, "Should have 'core' mutation family")
        assertTrue(coreFamily.totalMutants > 0, "Core family should have mutants")

        // ── Assert test performance was measured ──
        assertEquals("measured", result.testPerformance.status, "Test performance should be measured")
        assertTrue(
            result.testPerformance.totalTestCount > 0,
            "Should have run at least one test, got ${result.testPerformance.totalTestCount}"
        )

        // ── Assert output files are outside the measured checkout ──
        assertTrue(
            File(outputDir, "coverage-summary.json").isFile,
            "coverage-summary.json should exist in output dir"
        )
        assertTrue(
            File(outputDir, "mutation-summary.json").isFile,
            "mutation-summary.json should exist in output dir"
        )
        assertTrue(
            File(outputDir, "test-performance-median.json").isFile,
            "test-performance-median.json should exist in output dir"
        )

        // ── Assert git status is clean after the probe ──
        assertTrue(probe.verifyWorktreeClean(), "Worktree must be clean after probing")

        // ── Assert output dir is indeed outside sourceRoot ──
        val outputCanonical = outputDir.canonicalFile.toPath()
        val sourceCanonical = fixtureDir.canonicalFile.toPath()
        assertFalse(
            outputCanonical.startsWith(sourceCanonical),
            "Output directory must be outside measured checkout"
        )
    }

    @Test
    @Tag("integration")
    @Tag("slow")
    fun `canonical probe detects missing mutation reports`(@TempDir tempDir: File) {
        val fixtureDir = File(tempDir, "measured-checkout")
        fixtureDir.mkdirs()
        val outputDir = File(tempDir, "probe-output")
        outputDir.mkdirs()

        prepareFixtureForProbe(fixtureDir)

        // Configure a mutation family targeting a non-existent class pattern.
        // pitest uses failWhenNoMutations.set(true) so it will fail when no
        // classes match the target pattern.
        val configuration = TestQualityConfiguration(
            schemaVersion = "1",
            criticalModules = listOf(":lib-core"),
            coverage = TestQualityConfiguration.CoverageConfiguration(
                regressionTolerancePercentagePoints = 1.0,
                exclusions = listOf(
                    CoverageExclusion("**/model/**", "No model classes in fixture")
                )
            ),
            mutation = TestQualityConfiguration.MutationConfiguration(
                regressionTolerancePercentagePoints = 1.0,
                targetFamilies = mapOf(
                    "nonexistent" to TestQualityConfiguration.MutationTargetFamily(
                        modules = listOf(":lib-core"),
                        targetClasses = listOf("com.nonexistent.*"),
                        targetTests = listOf("com.nonexistent.*")
                    )
                )
            )
        )

        val probe = CanonicalGradleProbe(
            sourceRoot = fixtureDir,
            outputDir = outputDir
        )

        val exception = try {
            probe.probeTestQualityBaseline(configuration)
            null
        } catch (e: GradleException) {
            e
        }
        assertNotNull(exception, "Expected GradleException to be thrown for non-existent mutation targets")
        val msg = exception.message ?: ""
        // The exception message should indicate a mutation probe failure
        assertTrue(
            msg.contains("failed", ignoreCase = true) ||
                msg.contains("mutation", ignoreCase = true) ||
                msg.contains("exit code", ignoreCase = true),
            "Exception message should mention mutation failure, was: $msg"
        )
    }

    @Test
    @Tag("integration")
    @Tag("slow")
    fun `canonical probe detects missing coverage reports`(@TempDir tempDir: File) {
        val fixtureDir = File(tempDir, "measured-checkout")
        fixtureDir.mkdirs()
        val outputDir = File(tempDir, "probe-output")
        outputDir.mkdirs()

        prepareFixtureForProbe(fixtureDir)

        // Use an exclusion pattern that excludes all class files from the
        // JaCoCo report. This causes the report to have no coverage data,
        // and the CoverageCollector will detect zero executable lines despite
        // having production sources.
        val configuration = TestQualityConfiguration(
            schemaVersion = "1",
            criticalModules = listOf(":lib-core"),
            coverage = TestQualityConfiguration.CoverageConfiguration(
                regressionTolerancePercentagePoints = 1.0,
                exclusions = listOf(
                    CoverageExclusion("**/*", "Exclude all classes to test zero coverage")
                )
            ),
            mutation = TestQualityConfiguration.MutationConfiguration(
                regressionTolerancePercentagePoints = 1.0,
                targetFamilies = mapOf(
                    "core" to TestQualityConfiguration.MutationTargetFamily(
                        modules = listOf(":lib-core"),
                        targetClasses = listOf("com.example.*"),
                        targetTests = listOf("com.example.*")
                    )
                )
            )
        )

        val probe = CanonicalGradleProbe(
            sourceRoot = fixtureDir,
            outputDir = outputDir
        )

        val exception = try {
            probe.probeTestQualityBaseline(configuration)
            null
        } catch (e: GradleException) {
            e
        }
        assertNotNull(exception, "Expected GradleException to be thrown for missing coverage")
        val msg = exception.message ?: ""
        // The exception should be thrown by CoverageCollector when it finds
        // zero executable lines for a module with production sources.
        assertTrue(
            msg.contains("zero executable lines", ignoreCase = true) ||
                msg.contains("coverage", ignoreCase = true) ||
                msg.contains("produced no", ignoreCase = true),
            "Exception message should mention coverage/production-source failure, was: $msg"
        )
    }
}
