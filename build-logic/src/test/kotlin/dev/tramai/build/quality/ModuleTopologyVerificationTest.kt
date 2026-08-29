package dev.tramai.build.quality

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/**
 * Discriminator suite for VerifyModuleManifestTask (Epic 9.2d-a3c1): real
 * multi-project + java-platform BOM fixture, five failing mutations with exact
 * diagnostics, lazy-BOM ordering guard, root-extra decoupling, and
 * configuration-cache reuse + input-mutation re-execution.
 *
 * The release-verification plugin is applied IMPERATIVELY after the extra is
 * set, mirroring how the release task's typed publishableModules snapshot can
 * differ from the catalog (the extra is read only if present at apply time).
 */
class ModuleTopologyVerificationTest {
    @TempDir
    lateinit var tempDir: File

    private val counter = AtomicInteger(0)

    @Test
    fun `verifyModuleManifest passes with consistent topology`() {
        val dir = topologyFixture()
        val result = runner(dir, "verifyModuleManifest").build()
        assertTrue(result.task(":verifyModuleManifest")?.outcome == TaskOutcome.SUCCESS, result.output.take(800))
    }

    // ── Five failing mutations (each with the correct diagnostic) ───────────

    @Test
    fun `mutation 1 - project exists but catalog entry removed fails`() {
        val dir = topologyFixture(engineEntry = null)
        val result = runner(dir, "verifyModuleManifest").buildAndFail()
        assertTrue(
            result.output.contains(
                "[MODULE_CATALOG_MISSING_ENTRY] Gradle project ':tramai-engine' has no module-catalog entry",
            ),
            "expected exact missing-entry diagnostic: ${result.output.take(800)}",
        )
    }

    @Test
    fun `mutation 2 - catalog says published but release snapshot omits it fails`() {
        // Release task snapshot = extra (set before imperative apply): engine omitted.
        val dir = topologyFixture(publishableExtra = """listOf(":tramai-core", ":tramai-bom")""")
        val result = runner(dir, "verifyModuleManifest").buildAndFail()
        assertTrue(
            result.output.contains(
                "[MODULE_CATALOG_PUBLISHING_DRIFT] Publishing drift: configured publication set " +
                    "[:tramai-bom, :tramai-core] but manifest requires " +
                    "[:tramai-bom, :tramai-core, :tramai-engine]",
            ),
            "expected exact publishing-drift diagnostic: ${result.output.take(800)}",
        )
    }

    @Test
    fun `mutation 3 - release snapshot includes unexpected published module fails`() {
        val dir =
            topologyFixture(
                publishableExtra = """listOf(":tramai-core", ":tramai-engine", ":tramai-bom", ":tramai-extra")""",
            )
        val result = runner(dir, "verifyModuleManifest").buildAndFail()
        assertTrue(
            result.output.contains("[MODULE_CATALOG_PUBLISHING_DRIFT]") && result.output.contains(":tramai-extra"),
            "expected publishing drift mentioning :tramai-extra: ${result.output.take(800)}",
        )
    }

    @Test
    fun `mutation 4 - BOM constraint removed fails`() {
        val dir = topologyFixture(bomModules = listOf("tramai-core"))
        val result = runner(dir, "verifyModuleManifest").buildAndFail()
        assertTrue(
            result.output.contains(
                "[MODULE_CATALOG_BOM_DRIFT] BOM drift: configured BOM constraints " +
                    "[:tramai-core] but manifest requires " +
                    "[:tramai-core, :tramai-engine]",
            ),
            "expected exact bom-drift diagnostic: ${result.output.take(800)}",
        )
    }

    @Test
    fun `mutation 5 - unexpected BOM constraint added fails`() {
        val dir = topologyFixture(bomModules = listOf("tramai-core", "tramai-engine", "tramai-extra"))
        val result = runner(dir, "verifyModuleManifest").buildAndFail()
        assertTrue(
            result.output.contains("[MODULE_CATALOG_BOM_DRIFT]") && result.output.contains(":tramai-extra"),
            "expected bom drift mentioning :tramai-extra: ${result.output.take(800)}",
        )
    }

    // ── Ordering + decoupling discriminators ────────────────────────────────

    @Test
    fun `BOM signal resolves lazily after evaluation - eager apply-time lookup would fail`() {
        // BOM constraints are declared in tramai-bom/build.gradle.kts, evaluated
        // AFTER the root plugin apply. An eager plugin-apply-time lookup would
        // see an empty api constraint graph and fail with BOM drift; passing
        // proves the provider resolves lazily (Epic 9.2d-a1 rule).
        val dir = topologyFixture()
        val result = runner(dir, "verifyModuleManifest").build()
        assertTrue(result.task(":verifyModuleManifest")?.outcome == TaskOutcome.SUCCESS, result.output.take(800))
    }

    @Test
    fun `publishedPaths come from the release task model when the root extra is absent`() {
        // No tramai.publishableModulePaths extra anywhere: the release task's
        // publishableModules falls back to the module catalog, and
        // verifyModuleManifest still passes (9.2d-b can delete the extra later).
        val dir = topologyFixture(publishableExtra = null)
        val result = runner(dir, "verifyModuleManifest").build()
        assertTrue(result.task(":verifyModuleManifest")?.outcome == TaskOutcome.SUCCESS, result.output.take(800))
    }

    // ── Configuration cache discriminators ──────────────────────────────────

    @Test
    fun `declared catalog file is the execution authority - kill the old rediscovery`() {
        // P1 (a3c1 Round-1): moduleCatalogFile must BE the parsed file, not a
        // pointer used to rediscover the conventional path. The conventional
        // config/quality/module-catalog.yml holds VALID catalog A; the declared
        // input points at fixtures/catalog-B.yml holding a DIFFERENT catalog B
        // that omits :tramai-engine. The fixed implementation parses B and
        // fails with MODULE_CATALOG_MISSING_ENTRY; the old implementation
        // rediscovered A from B's parent chain and passed.
        val dir = topologyFixture(catalogFileOverride = "fixtures/catalog-B.yml")
        // Catalog B (declared): engine entry REMOVED -> missing-entry diagnostic.
        val catalogB = catalog(engineEntry = null)
        writeFile(dir, "fixtures/catalog-B.yml", catalogB)
        val result = runner(dir, "verifyModuleManifest").buildAndFail()
        assertTrue(
            result.output.contains("[MODULE_CATALOG_MISSING_ENTRY]"),
            "must observe the DECLARED catalog B, not the conventional catalog A: ${result.output.take(800)}",
        )
    }

    @Test
    fun `declared catalog file mutation is observed by the configuration cache`() {
        // CC variant of the exact-file authority: cold -> warm reuse -> mutate
        // ONLY the declared B file -> task re-executes and observes B's change.
        val dir = topologyFixture(catalogFileOverride = "fixtures/catalog-B.yml")
        // Initial B (declared): valid (same modules as the model) -> passes.
        writeFile(dir, "fixtures/catalog-B.yml", catalog(defaultEngineEntry))
        val args = arrayOf("verifyModuleManifest", "--configuration-cache", "--configuration-cache-problems=fail")

        val first = runner(dir, *args).build()
        assertTrue(first.output.contains("Configuration cache entry stored"), "cold run must store: ${first.output.take(800)}")
        assertTrue(first.task(":verifyModuleManifest")?.outcome == TaskOutcome.SUCCESS)

        val second = runner(dir, *args).build()
        assertTrue(second.output.contains("Reusing configuration cache"), "warm run must reuse: ${second.output.take(800)}")
        assertTrue(second.task(":verifyModuleManifest")?.outcome == TaskOutcome.SUCCESS)

        // Mutate ONLY the declared B file (remove the engine entry -> must fail
        // against the unchanged project/published/BOM model).
        writeFile(dir, "fixtures/catalog-B.yml", catalog(engineEntry = null))
        val third = runner(dir, *args).buildAndFail()
        assertTrue(
            third.output.contains("[MODULE_CATALOG_MISSING_ENTRY]"),
            "mutated declared catalog must be observed and fail: ${third.output.take(800)}",
        )
        assertTrue(third.output.contains("Reusing configuration cache"), "CC entry must still be reused after input mutation")
    }

    @Test
    fun `verifyModuleManifest stores and reuses configuration cache and re-executes on catalog mutation`() {
        val dir = topologyFixture()
        val args = arrayOf("verifyModuleManifest", "--configuration-cache", "--configuration-cache-problems=fail")

        val first = runner(dir, *args).build()
        assertTrue(first.output.contains("Configuration cache entry stored"), "cold run must store: ${first.output.take(800)}")

        val second = runner(dir, *args).build()
        assertTrue(second.output.contains("Reusing configuration cache"), "warm run must reuse: ${second.output.take(800)}")
        // Verifier has no output artifact (deliberately never up-to-date/skippable):
        // warm run reuses the CC entry but the task still executes.
        assertTrue(second.task(":verifyModuleManifest")?.outcome == TaskOutcome.SUCCESS, "warm run must still execute the verifier")

        // Input mutation (catalog content) -> task re-executes.
        val mutatedCatalog = catalog(defaultEngineEntry).replace("Fixture core module.", "Fixture core module v2.")
        writeFile(dir, "config/quality/module-catalog.yml", mutatedCatalog)
        val third = runner(dir, *args).build()
        assertTrue(third.task(":verifyModuleManifest")?.outcome == TaskOutcome.SUCCESS, "mutated catalog must re-execute")
    }

    // ── Fixtures & helpers ──────────────────────────────────────────────────

    private val defaultEngineEntry = """
              - path: ":tramai-engine"
                <<: *core
                layer: core-contracts
                publishability: published
                apiStability: stable
                description: "Fixture engine module."
    """

    private fun catalog(engineEntry: String?): String {
        val engine = engineEntry ?: ""
        return """
            schemaVersion: "3"
            dependencyPolicies:
              core: { allowedLayers: [core-contracts] }
              testing: { allowedLayers: [testing-support] }
            entryDefaults:
              core: &core { maturity: stable, visibility: public, owner: core, dependencyPolicy: core, releaseInclusion: included, rationale: "Fixture module." }
              internal: &internal { maturity: internal, visibility: internal, owner: testing, dependencyPolicy: testing, releaseInclusion: internal_only, rationale: "Fixture internal module." }
            modules:
              - path: ":tramai-core"
                <<: *core
                layer: core-contracts
                publishability: published
                apiStability: stable
                description: "Fixture core module."
              $engine
              - path: ":tramai-testing"
                <<: *internal
                layer: testing-support
                publishability: internal
                apiStability: internal
              - path: ":tramai-bom"
                <<: *core
                layer: core-contracts
                publishability: published
                apiStability: stable
                description: "Fixture BOM."
            """.trimIndent()
    }

    private fun bomScript(modules: List<String>): String {
        val constraints = modules.joinToString("\n") { "            api(\"dev.tramai:$it:0.6.0\")" }
        return """
            plugins { `java-platform` }
            dependencies {
                constraints {
            $constraints
                }
            }
            """.trimIndent()
    }

    private fun topologyFixture(
        publishableExtra: String? = """listOf(":tramai-core", ":tramai-engine", ":tramai-bom")""",
        bomModules: List<String> = listOf("tramai-core", "tramai-engine"),
        engineEntry: String? = defaultEngineEntry,
        catalogFileOverride: String? = null,
    ): File {
        val dir = File(tempDir, "topology-${counter.incrementAndGet()}").apply { mkdirs() }
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "topology-fixture"
            include(":tramai-core", ":tramai-engine", ":tramai-testing", ":tramai-bom")
            """.trimIndent(),
        )
        val extraBlock =
            if (publishableExtra != null) {
                "extra[\"tramai.publishableModulePaths\"] = $publishableExtra"
            } else {
                ""
            }
        // Exact-file authority override: point moduleCatalogFile at a
        // non-conventional path so the P1 kill-the-old-implementation
        // discriminator can prove the declared file is parsed directly.
        val catalogOverrideBlock =
            if (catalogFileOverride != null) {
                "tasks.named<VerifyModuleManifestTask>(\"verifyModuleManifest\") { moduleCatalogFile.set(layout.projectDirectory.file(\"$catalogFileOverride\")) }"
            } else {
                ""
            }
        writeFile(
            dir,
            "build.gradle.kts",
            """
            import dev.tramai.build.quality.VerifyModuleManifestTask
            plugins {
                id("tramai.maintainability-baseline")
            }
            $extraBlock
            // Imperative apply AFTER the extra is set: the release task's
            // publishableModules snapshot only reads the extra when it exists at
            // apply time, otherwise it falls back to the module catalog.
            apply(plugin = "tramai.release-verification")
            $catalogOverrideBlock
            """.trimIndent(),
        )
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { `java-library` }
            tasks.register("generatePomFileForMavenPublication")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "tramai-engine/build.gradle.kts",
            """
            plugins { `java-library` }
            tasks.register("generatePomFileForMavenPublication")
            """.trimIndent(),
        )
        writeFile(dir, "tramai-testing/build.gradle.kts", """plugins { `java-library` }""")
        writeFile(
            dir,
            "tramai-bom/build.gradle.kts",
            """
            ${bomScript(bomModules)}
            tasks.register("generatePomFileForMavenPublication")
            """.trimIndent(),
        )
        writeFile(dir, "config/quality/module-catalog.yml", catalog(engineEntry))
        writeFile(
            dir,
            "config/quality/test-quality.yml",
            """
            schemaVersion: "1"
            criticalModules: [":tramai-core"]
            coverage:
              regressionTolerancePercentagePoints: 1.0
              exclusions: []
            mutation:
              regressionTolerancePercentagePoints: 1.0
              targetFamilies:
                core:
                  modules: [":tramai-core"]
                  targetClasses: ["example.*"]
                  targetTests: ["example.*"]
            """.trimIndent(),
        )
        return dir
    }

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
}
