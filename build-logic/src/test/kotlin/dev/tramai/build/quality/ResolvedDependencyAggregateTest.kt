package dev.tramai.build.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Discriminator suite for AggregateResolvedDependencyBaselineTask (Epic 9.2d-a3c1):
 * sorted deterministic merge, fail-closed diagnostics, no empty success oracle,
 * plugin wiring, and configuration-cache reuse + input-mutation re-execution.
 */
class ResolvedDependencyAggregateTest {
    @TempDir
    lateinit var tempDir: File

    private val counter = AtomicInteger(0)
    private val mapper = ObjectMapper()

    // ── Direct task-registration discriminators ─────────────────────────────

    @Test
    fun `aggregate merges unsorted probe records into a sorted deterministic baseline`() {
        val dir = aggregateFixture(listOf(":a", ":b"), listOf("a.json", "b.json"))
        // Records arrive UNSORTED: a.json carries zeta BEFORE alpha; b.json adds beta.
        writeProbe(dir, "probes/a.json", record("zeta", ":a"), record("alpha", ":a"))
        writeProbe(dir, "probes/b.json", record("beta", ":b"))

        val result = runner(dir, "aggregate").build()

        val output = File(dir, "build/reports/maintainability/resolved-dependencies.json")
        assertTrue(output.isFile, "aggregate file must be written")
        val artifacts = parse(output.readText()).map { it.get("artifact").asText() }
        // Sorted by consumers (":a" < ":b") then artifact: alpha, zeta, beta.
        assertEquals(listOf("alpha", "zeta", "beta"), artifacts)
        // No empty dependency universe as success oracle: non-empty, both projects present.
        assertEquals(3, artifacts.size)
        assertTrue(
            result.output.contains("Resolved dependency baseline: 3 records (3 direct, 0 transitive)"),
            "exact summary line must be printed: ${result.output.take(800)}",
        )

        // Deterministic: forced re-run must produce byte-identical output.
        runner(dir, "aggregate", "--rerun-tasks").build()
        assertEquals(output.readText(), File(dir, "build/reports/maintainability/resolved-dependencies.json").readText())
    }

    @Test
    fun `missing probe fails with the exact missing-probe diagnostic`() {
        val dir = aggregateFixture(listOf(":a", ":b"), listOf("a.json", "b.json"))
        writeProbe(dir, "probes/a.json", record("alpha", ":a"))
        // b.json intentionally never written.

        val result = runner(dir, "aggregate").buildAndFail()

        val expected = "Missing dependency probe output for :b: ${File(dir, "probes/b.json").absolutePath}"
        assertTrue(result.output.contains(expected), "expected '$expected' in output: ${result.output.take(800)}")
    }

    @Test
    fun `malformed probe fails with the exact invalid-probe diagnostic`() {
        val dir = aggregateFixture(listOf(":a", ":b"), listOf("a.json", "b.json"))
        writeProbe(dir, "probes/a.json", record("alpha", ":a"))
        writeFile(dir, "probes/b.json", "{ this is not json ")

        val result = runner(dir, "aggregate").buildAndFail()

        assertTrue(
            result.output.contains("Invalid dependency probe output for :b:"),
            "expected invalid-probe diagnostic in output: ${result.output.take(800)}",
        )
    }

    @Test
    fun `records from project B are not lost when project A is valid`() {
        val dir = aggregateFixture(listOf(":a", ":b"), listOf("a.json", "b.json"))
        writeProbe(dir, "probes/a.json", record("alpha", ":a"), record("zeta", ":a"))
        writeProbe(dir, "probes/b.json", record("beta", ":b"), record("gamma", ":b"))

        runner(dir, "aggregate").build()

        val artifacts =
            parse(File(dir, "build/reports/maintainability/resolved-dependencies.json").readText())
                .map { it.get("artifact").asText() }
        assertEquals(listOf("alpha", "zeta", "beta", "gamma"), artifacts, "both projects' records must survive the merge")
    }

    @Test
    fun `aggregate stores and reuses configuration cache and re-executes on probe mutation`() {
        val dir = aggregateFixture(listOf(":a", ":b"), listOf("a.json", "b.json"))
        writeProbe(dir, "probes/a.json", record("alpha", ":a"))
        writeProbe(dir, "probes/b.json", record("beta", ":b"))

        val args = arrayOf("aggregate", "--configuration-cache", "--configuration-cache-problems=fail")
        val first = runner(dir, *args).build()
        assertTrue(first.output.contains("Configuration cache entry stored"), "cold run must store: ${first.output.take(800)}")

        val second = runner(dir, *args).build()
        assertTrue(second.output.contains("Reusing configuration cache"), "warm run must reuse: ${second.output.take(800)}")
        assertTrue(second.task(":aggregate")?.outcome == TaskOutcome.UP_TO_DATE, "unchanged inputs must be up-to-date")

        // Input mutation -> re-executes.
        writeProbe(dir, "probes/b.json", record("beta", ":b"), record("gamma", ":b"))
        val third = runner(dir, *args).build()
        assertTrue(third.task(":aggregate")?.outcome == TaskOutcome.SUCCESS, "mutated input must re-execute")
        val artifacts =
            parse(File(dir, "build/reports/maintainability/resolved-dependencies.json").readText())
                .map { it.get("artifact").asText() }
        assertEquals(listOf("alpha", "beta", "gamma"), artifacts)
    }

    // ── Plugin wiring integration ───────────────────────────────────────────

    @Test
    fun `plugin wires root aggregate from per-project probes in deterministic order`() {
        val dir = multiProjectFixture()
        val result = runner(dir, "generateResolvedDependencyBaseline").build()

        val output = File(dir, "build/reports/maintainability/resolved-dependencies.json")
        assertTrue(output.isFile, "aggregate file must be written by the root task")
        val parsed = parse(output.readText())
        assertTrue(parsed.size >= 3, "both projects' records must be merged: ${output.readText()}")
        assertTrue(parsed.all { it.get("group").asText() == "com.example" })
        assertTrue(result.output.contains("Resolved dependency baseline:"), "summary line must be printed")
        assertTrue(result.task(":generateResolvedDependencyBaseline")?.outcome == TaskOutcome.SUCCESS)
    }

    // ── Fixtures & helpers ──────────────────────────────────────────────────

    /** Direct-registration fixture: full control over probe file existence/content. */
    private fun aggregateFixture(
        owners: List<String>,
        fileNames: List<String>,
    ): File {
        val dir = File(tempDir, "agg-${counter.incrementAndGet()}").apply { mkdirs() }
        writeFile(dir, "settings.gradle.kts", "rootProject.name = \"agg-fixture\"\n")
        val files = fileNames.joinToString(", ") { """layout.projectDirectory.file("probes/$it")""" }
        val ownersArg = owners.joinToString(", ") { "\"$it\"" }
        // Apply the maintainability plugin so TestKit injects the build-logic
        // classpath (withPluginClasspath exposes classes through plugin
        // resolution, not the raw script classpath). The custom aggregate task
        // then resolves the import.
        writeFile(
            dir,
            "config/quality/module-catalog.yml",
            """
            schemaVersion: "3"
            dependencyPolicies:
              core: { allowedLayers: [core-contracts] }
            entryDefaults:
              core: &core { maturity: stable, visibility: public, owner: core, dependencyPolicy: core, releaseInclusion: included, rationale: "Fixture module." }
            modules:
              - path: ":alpha"
                <<: *core
                layer: core-contracts
                publishability: published
                apiStability: stable
                description: "Alpha fixture."
            """.trimIndent(),
        )
        writeFile(
            dir,
            "config/quality/test-quality.yml",
            """
            schemaVersion: "1"
            criticalModules: [":alpha"]
            coverage:
              regressionTolerancePercentagePoints: 1.0
              exclusions: []
            mutation:
              regressionTolerancePercentagePoints: 1.0
              targetFamilies:
                alpha:
                  modules: [":alpha"]
                  targetClasses: ["example.*"]
                  targetTests: ["example.*"]
            """.trimIndent(),
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins { id("tramai.maintainability-baseline") }
            import dev.tramai.build.quality.AggregateResolvedDependencyBaselineTask

            tasks.register<AggregateResolvedDependencyBaselineTask>("aggregate") {
                probeFiles.from($files)
                expectedProbeOwners.set(listOf($ownersArg))
                aggregateFile.set(layout.buildDirectory.file("reports/maintainability/resolved-dependencies.json"))
            }
            """.trimIndent(),
        )
        return dir
    }

    /** Real multi-project fixture: probes resolve real external modules from a local repo. */
    private fun multiProjectFixture(): File {
        val dir = File(tempDir, "agg-integration-${counter.incrementAndGet()}").apply { mkdirs() }
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "agg-integration"
            include(":alpha", ":beta")
            """.trimIndent(),
        )
        writeFile(dir, "build.gradle.kts", """plugins { id("tramai.maintainability-baseline") }""")
        writeFile(
            dir,
            "alpha/build.gradle.kts",
            """
            plugins { `java-library` }
            repositories { maven { url = uri(rootDir.resolve("repo")) } }
            dependencies { implementation("com.example:fake:1.0") }
            """.trimIndent(),
        )
        writeFile(
            dir,
            "beta/build.gradle.kts",
            """
            plugins { `java-library` }
            repositories { maven { url = uri(rootDir.resolve("repo")) } }
            dependencies {
                implementation("com.example:fake:1.0")
                implementation("com.example:fake2:1.0")
            }
            """.trimIndent(),
        )
        writeFile(
            dir,
            "config/quality/module-catalog.yml",
            """
            schemaVersion: "3"
            dependencyPolicies:
              core: { allowedLayers: [core-contracts] }
            entryDefaults:
              core: &core { maturity: stable, visibility: public, owner: core, dependencyPolicy: core, releaseInclusion: included, rationale: "Fixture module." }
            modules:
              - path: ":alpha"
                <<: *core
                layer: core-contracts
                publishability: published
                apiStability: stable
                description: "Alpha fixture."
              - path: ":beta"
                <<: *core
                layer: core-contracts
                publishability: published
                apiStability: stable
                description: "Beta fixture."
            """.trimIndent(),
        )
        writeFile(
            dir,
            "config/quality/test-quality.yml",
            """
            schemaVersion: "1"
            criticalModules: [":alpha"]
            coverage:
              regressionTolerancePercentagePoints: 1.0
              exclusions: []
            mutation:
              regressionTolerancePercentagePoints: 1.0
              targetFamilies:
                alpha:
                  modules: [":alpha"]
                  targetClasses: ["example.*"]
                  targetTests: ["example.*"]
            """.trimIndent(),
        )
        for ((name, version) in listOf("fake" to "1.0", "fake2" to "1.0")) {
            val moduleDir = File(dir, "repo/com/example/$name/$version")
            moduleDir.mkdirs()
            writeFile(
                moduleDir,
                "$name-$version.pom",
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>$name</artifactId>
                  <version>$version</version>
                </project>
                """.trimIndent(),
            )
            java.util.zip
                .ZipOutputStream(File(moduleDir, "$name-$version.jar").outputStream())
                .use { it.close() }
        }
        return dir
    }

    private fun record(
        artifact: String,
        consumer: String,
    ): String {
        val json =
            """
            {
              "group": "com.example",
              "artifact": "$artifact",
              "selectedVersion": "1.0",
              "requestedVersion": "1.0",
              "direct": true,
              "configuration": "compileClasspath",
              "selectionReason": "requested",
              "dependencyPath": ["$consumer", "com.example:$artifact:1.0"],
              "consumers": ["$consumer"]
            }
            """.trimIndent()
        return json
    }

    private fun writeProbe(
        base: File,
        relativePath: String,
        vararg records: String,
    ) {
        writeFile(base, relativePath, records.joinToString(separator = ",\n", prefix = "[", postfix = "]"))
    }

    private fun parse(json: String): List<JsonNode> {
        val tree = mapper.readTree(json)
        val list = mutableListOf<JsonNode>()
        tree.forEach { list.add(it) }
        return list
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
