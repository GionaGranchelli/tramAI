package dev.tramai.build.docs

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Discriminating TestKit tests for the tramai.docs-guards plugin (Epic 9.2d-a2).
 *
 * Standing rule (user-mandated): NO empty/degenerate fixture may serve as the
 * successful oracle when the production verifier is expected to discover
 * concrete files, profiles, claims, modules, or records.
 *
 * Every fixture therefore contains REAL repository files (copied from the
 * working tree via `git ls-files`), and every positive test asserts the task
 * actually ran with SUCCESS. Fail-closed tests mutate exactly one real file
 * and assert the exact historical diagnostic.
 */
class TramaiDocsGuardsPluginTest {

    @TempDir
    lateinit var tempDir: File

    private val repoRoot: File by lazy {
        // Test JVM cwd is the build-logic module dir; repo root is the nearest
        // ancestor that is a git work tree (build-logic itself also has a
        // settings.gradle.kts, so require .git as the discriminator).
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, ".git").isDirectory) {
            dir = dir.parentFile ?: error("repo root not found from ${System.getProperty("user.dir")}")
        }
        dir
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    /** Copies tracked files under [relativePaths] from the real repo into [dir]. */
    private fun copyFromRepo(dir: File, vararg relativePaths: String) {
        val git = ProcessBuilder("git", "-C", repoRoot.absolutePath, "ls-files", *relativePaths)
            .redirectErrorStream(true)
            .start()
        val listing = git.inputStream.bufferedReader().readText()
        check(git.waitFor() == 0) { "git ls-files failed: $listing" }
        listing.lineSequence().filter { it.isNotBlank() }.forEach { rel ->
            val src = File(repoRoot, rel)
            val dst = File(dir, rel)
            if (src.isFile) {
                dst.parentFile.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
    }

    /** Minimal fixture root that applies tramai.docs-guards on the root project. */
    private fun fixture(settingsModules: String = "", exampleDirs: List<String> = emptyList()): File {
        val dir = File(tempDir, "fixture-${System.nanoTime()}").apply { mkdirs() }
        writeFile(dir, "settings.gradle.kts", """
            rootProject.name = "docs-guards-fixture"
            $settingsModules
        """.trimIndent())
        writeFile(dir, "build.gradle.kts", """
            plugins { id("tramai.docs-guards") }
        """.trimIndent())
        // Included example projects must exist as directories or Gradle refuses
        // to configure them ("without an existing directory is not allowed").
        exampleDirs.forEach { rel -> File(dir, rel).mkdirs() }
        return dir
    }

    private fun runner(dir: File, vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(dir)
            .withGradleVersion("9.0.0")
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()

    private fun writeFile(base: File, relativePath: String, content: String) {
        val target = File(base, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content)
    }

    private fun runTask(dir: File, task: String): org.gradle.testkit.runner.BuildResult {
        val result = runner(dir, task, "--no-build-cache").build()
        assertTrue(
            result.task(":$task")?.outcome == TaskOutcome.SUCCESS,
            "$task must succeed: ${result.output.take(1200)}"
        )
        return result
    }

    // ------------------------------------------------------------------
    // Positive discriminators (real repo files, task must run + pass)
    // ------------------------------------------------------------------

    @Test
    fun `verifyPostSovereigntyRoadmap passes on real roadmap`() {
        val dir = fixture()
        copyFromRepo(dir, "docs/POST-SOVEREIGNTY-ROADMAP.md")
        runTask(dir, "verifyPostSovereigntyRoadmap")
    }

    @Test
    fun `verifyProductPositioning passes on real positioning docs`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "docs/product/positioning.md",
            "docs/security/PRODUCT-THESIS.md",
            "docs/security/mcp-governance-boundary.md",
            "docs/POST-SOVEREIGNTY-ROADMAP.md",
        )
        runTask(dir, "verifyProductPositioning")
    }

    @Test
    fun `verifyReadmePositioning passes on real README and examples`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "README.md",
            "docs/architecture/overview.md",
            "docs/modules/sovereign-runtime-module-matrix.md",
            "examples/governed-workflow",
            "examples/approval-resume",
            "examples/sovereign-document-intelligence",
        )
        runTask(dir, "verifyReadmePositioning")
    }

    @Test
    fun `verifyGovernedWorkflowArticle passes on real article and talk`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "docs/articles/governed-ai-workflows-for-the-jvm.md",
            "docs/talks/governed-ai-workflows-for-the-jvm.md",
            "README.md",
            "docs/product/positioning.md",
            "docs/STATUS.md",
            "examples/governed-workflow",
            "examples/approval-resume",
            "examples/sovereign-document-intelligence",
        )
        runTask(dir, "verifyGovernedWorkflowArticle")
    }

    @Test
    fun `verifyExampleSelectionGuide passes on real guide`() {
        val dir = fixture(
            settingsModules = """
                include("examples:support-agent")
                include("examples:sovereign-document-intelligence")
                include("examples:sovereign-offline-verification")
                include("examples:spring-sovereign-starter")
                include("examples:governed-workflow")
                include("examples:approval-resume")
            """.trimIndent(),
            exampleDirs = listOf(
                "examples/support-agent",
                "examples/sovereign-document-intelligence",
                "examples/sovereign-offline-verification",
                "examples/spring-sovereign-starter",
                "examples/governed-workflow",
                "examples/approval-resume",
            ),
        )
        copyFromRepo(
            dir,
            "examples/README.md",
            "scripts/verify-zero-egress.sh",
            "examples/spring-sovereign-starter/README.md",
            "examples/kotlin-springboot-example/README.md",
            "examples/sovereign-lab/README.md",
        )
        runTask(dir, "verifyExampleSelectionGuide")
    }

    @Test
    fun `verifyJvmAiFrameworkComparison passes on real comparison doc`() {
        val dir = fixture()
        copyFromRepo(dir, "docs/comparison/jvm-ai-frameworks.md")
        runTask(dir, "verifyJvmAiFrameworkComparison")
    }

    @Test
    fun `verifyWorkflowApiStabilityBoundary passes on real boundary doc`() {
        val dir = fixture()
        copyFromRepo(dir, "docs/workflow-api-stability-boundary.md")
        runTask(dir, "verifyWorkflowApiStabilityBoundary")
    }

    @Test
    fun `verifyVersionAlignment passes on real version surfaces`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "gradle.properties",
            "build.gradle.kts",
            "CHANGELOG.md",
            "docs/STATUS.md",
            "docs/POST-SOVEREIGNTY-ROADMAP.md",
            "docs/releases/0.5.0-release-readiness.md",
            "docs/releases/sovereign-runtime-release-readiness.md",
            "README.md",
            "docs/guides",
            "docs/module-guide.md",
            "docs/reference/releasing.md",
            "examples/README.md",
            "examples/support-agent/build.gradle.kts",
            "examples/kotlin-springboot-example/build.gradle.kts",
            "examples/kotlin-native-smoke-example/build.gradle.kts",
            "examples/sovereign-runtime-consumer-smoke/build.gradle.kts",
            "examples/spring-sovereign-starter/build.gradle.kts",
            "docs/modules",
        )
        // The real build.gradle.kts uses version-catalog aliases that need a
        // libs.versions.toml; restore a minimal fixture build file that still
        // carries the orElse("0.5.0") fallback the verifier checks.
        writeFile(dir, "build.gradle.kts", """
            plugins { id("tramai.docs-guards") }
            version = providers.gradleProperty("tramaiVersion").orElse("0.5.0")
        """.trimIndent())
        runTask(dir, "verifyVersionAlignment")
    }

    @Test
    fun `verifyToolGovernanceExample passes on real example`() {
        val dir = fixture(
            settingsModules = """include("examples:tool-governance")""",
            exampleDirs = listOf("examples/tool-governance"),
        )
        copyFromRepo(
            dir,
            "examples/tool-governance",
            "examples/README.md",
            "docs/guides/governed-tool-use.md",
            "docs/POST-SOVEREIGNTY-ROADMAP.md",
        )
        // The example's build.gradle.kts references :tramai-bom which is not
        // in the fixture; the verifier checks sources/README, not the build file.
        File(dir, "examples/tool-governance/build.gradle.kts").delete()
        runTask(dir, "verifyToolGovernanceExample")
    }

    @Test
    fun `verifyModuleDocContract passes on real module cards`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "config/quality",
            "docs",
            "ARCHITECTURE.md",
        )
        runTask(dir, "verifyModuleDocContract")
    }

    // ------------------------------------------------------------------
    // Fail-closed negative discriminators (mutate ONE real file)
    // ------------------------------------------------------------------

    @Test
    fun `verifyProductPositioning fails when a required section is removed`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "docs/product/positioning.md",
            "docs/security/PRODUCT-THESIS.md",
            "docs/security/mcp-governance-boundary.md",
            "docs/POST-SOVEREIGNTY-ROADMAP.md",
        )
        val positioning = File(dir, "docs/product/positioning.md")
        val text = positioning.readText()
        positioning.writeText(text.replace("## Canonical Message", "## Removed"))
        val result = runner(dir, "verifyProductPositioning", "--no-build-cache").buildAndFail()
        assertContains(result.output, "Missing required section: '## Canonical Message'")
    }

    @Test
    fun `verifyProductPositioning fails when a forbidden claim is introduced`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "docs/product/positioning.md",
            "docs/security/PRODUCT-THESIS.md",
            "docs/security/mcp-governance-boundary.md",
            "docs/POST-SOVEREIGNTY-ROADMAP.md",
        )
        val positioning = File(dir, "docs/product/positioning.md")
        positioning.appendText("\nTramAI is fully compliant with everything.\n")
        val result = runner(dir, "verifyProductPositioning", "--no-build-cache").buildAndFail()
        assertContains(result.output, "Forbidden claim found: 'fully compliant'")
    }

    @Test
    fun `verifyPostSovereigntyRoadmap fails when a required phrase is removed`() {
        val dir = fixture()
        copyFromRepo(dir, "docs/POST-SOVEREIGNTY-ROADMAP.md")
        val roadmap = File(dir, "docs/POST-SOVEREIGNTY-ROADMAP.md")
        val text = roadmap.readText()
        roadmap.writeText(text.replace("Workflow Ergonomics", "Workflow Things"))
        val result = runner(dir, "verifyPostSovereigntyRoadmap", "--no-build-cache").buildAndFail()
        assertContains(result.output, "Post-sovereignty roadmap is missing required phrase: Workflow Ergonomics")
    }

    @Test
    fun `verifyVersionAlignment fails on a stale snapshot reference`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "gradle.properties",
            "build.gradle.kts",
            "CHANGELOG.md",
            "docs/STATUS.md",
            "docs/POST-SOVEREIGNTY-ROADMAP.md",
            "docs/releases/0.5.0-release-readiness.md",
            "docs/releases/sovereign-runtime-release-readiness.md",
            "README.md",
            "docs/guides",
            "docs/module-guide.md",
            "docs/reference/releasing.md",
            "examples/README.md",
            "examples/support-agent/build.gradle.kts",
            "examples/kotlin-springboot-example/build.gradle.kts",
            "examples/kotlin-native-smoke-example/build.gradle.kts",
            "examples/sovereign-runtime-consumer-smoke/build.gradle.kts",
            "examples/spring-sovereign-starter/build.gradle.kts",
            "docs/modules",
        )
        // Restore minimal fixture build file (see positive version-alignment test).
        writeFile(dir, "build.gradle.kts", """
            plugins { id("tramai.docs-guards") }
            version = providers.gradleProperty("tramaiVersion").orElse("0.5.0")
        """.trimIndent())
        writeFile(dir, "docs/guides/getting-started.md", "Use dev.tramai:tramai-core:0.5.0-SNAPSHOT now.\n")
        val result = runner(dir, "verifyVersionAlignment", "--no-build-cache").buildAndFail()
        assertContains(result.output, "still contains dev.tramai:*:0.5.0-SNAPSHOT dependency reference")
    }

    @Test
    fun `verifyExampleSelectionGuide fails when a required profile is removed`() {
        val dir = fixture(
            settingsModules = """
                include("examples:support-agent")
                include("examples:sovereign-document-intelligence")
                include("examples:sovereign-offline-verification")
                include("examples:spring-sovereign-starter")
                include("examples:governed-workflow")
                include("examples:approval-resume")
            """.trimIndent(),
            exampleDirs = listOf(
                "examples/support-agent",
                "examples/sovereign-document-intelligence",
                "examples/sovereign-offline-verification",
                "examples/spring-sovereign-starter",
                "examples/governed-workflow",
                "examples/approval-resume",
            ),
        )
        copyFromRepo(
            dir,
            "examples/README.md",
            "scripts/verify-zero-egress.sh",
            "examples/spring-sovereign-starter/README.md",
            "examples/kotlin-springboot-example/README.md",
            "examples/sovereign-lab/README.md",
        )
        val guide = File(dir, "examples/README.md")
        val text = guide.readText()
        guide.writeText(text.replace("### Support Agent", "### Support Bot"))
        val result = runner(dir, "verifyExampleSelectionGuide", "--no-build-cache").buildAndFail()
        assertContains(result.output, "Guide missing required profile: '### Support Agent'")
    }

    @Test
    fun `verifyModuleDocContract fails when a module card is missing`() {
        val dir = fixture()
        copyFromRepo(
            dir,
            "config/quality",
            "docs",
            "ARCHITECTURE.md",
        )
        val card = File(dir, "docs/modules/tramai-core.md")
        if (card.isFile) card.delete()
        val result = runner(dir, "verifyModuleDocContract", "--no-build-cache").buildAndFail()
        assertContains(result.output, "[MODULE_CARD_MISSING] Manifest module 'tramai-core' has no card in docs/modules")
    }

    // ------------------------------------------------------------------
    // Fail-loud guard: an unconfigured verifierKind must never pass silently
    // ------------------------------------------------------------------

    @Test
    fun `task without verifierKind fails loud instead of passing vacuously`() {
        val dir = File(tempDir, "fixture-${System.nanoTime()}").apply { mkdirs() }
        writeFile(dir, "settings.gradle.kts", """
            rootProject.name = "docs-guards-fixture"
        """.trimIndent())
        writeFile(dir, "build.gradle.kts", """
            plugins { id("tramai.docs-guards") }
            tasks.register<dev.tramai.build.docs.DocsContractVerifierTask>("verifyMisconfigured") {
                contractId.set("verifyMisconfigured")
                documents.from(layout.projectDirectory.file("docs/POST-SOVEREIGNTY-ROADMAP.md"))
            }
        """.trimIndent())
        writeFile(dir, "docs/POST-SOVEREIGNTY-ROADMAP.md", "Sovereign Lab Evidence Handoff v1 is complete\n")
        val result = runner(dir, "verifyMisconfigured", "--no-build-cache").buildAndFail()
        assertContains(result.output, "verifyMisconfigured: verifierKind is not configured; register with an explicit DocGuardKind")
    }
}
