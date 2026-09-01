package dev.tramai.build.release

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TestKit discriminator suite for tramai.release-verification and
 * tramai.sovereign-verification (Epic 9.2b). Each test builds a minimal
 * fixture and asserts task types, names, fail-closed evidence behavior, and
 * the security invariants (remote sovereign URL must never publish).
 *
 * Fixtures set the publishable module set through the same
 * tramai.publishableModulePaths extra that the real root build script uses,
 * and register sentinel publish tasks that write marker files so tests can
 * prove zero execution on rejected paths.
 */
class ReleaseVerificationPluginTest {
    @TempDir
    lateinit var tempDir: File

    private fun writeFile(
        base: File,
        relativePath: String,
        content: String,
    ) {
        val target = File(base, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content)
    }

    /** POM fixture content — kept separate so the build-script fixture string stays flat. */
    private val fixturePom =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>dev.tramai</groupId>
          <artifactId>tramai-core</artifactId>
          <version>0.6.0</version>
          <name>tramai-core</name>
          <description>Test module</description>
          <url>https://github.com/GionaGranchelli/tramAI</url>
          <licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>
          <developers><developer><id>GionaGranchelli</id><name>Giona</name><email>opensource@giona.dev</email></developer></developers>
          <scm><url>https://github.com/GionaGranchelli/tramAI.git</url>
            <connection>scm:git:https://github.com/GionaGranchelli/tramAI.git</connection>
            <developerConnection>scm:git:ssh://git@github.com/GionaGranchelli/tramAI.git</developerConnection></scm>
        </project>
        """.trimIndent()

    /** Triple-quoted form of the POM fixture, embedded inside the fixture build script. */
    private val fixturePomQuoted: String = "\"\"\"" + fixturePom + "\"\"\""

    private fun baseFixture(extra: String = ""): File {
        val dir = File(tempDir, "fixture").apply { mkdirs() }
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "sample"
            include("tramai-core")
            """.trimIndent(),
        )
        writeFile(
            dir,
            "gradle.properties",
            """
            tramaiVersion=0.6.0
            tramaiGroup=dev.tramai
            """.trimIndent(),
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins {
                id("tramai.release-verification")
                id("tramai.sovereign-verification")
            }
            // verifySovereignOpsObservabilityDocs is provided by tramai.sovereign-verification (9.2d-a3b1 typed extraction)
            // Isolate mavenLocal from the real ~/.m2 so tests are hermetic
            tasks.named<dev.tramai.build.sovereign.VerifySovereignSignedBundleTask>("verifySovereignRuntimeSignedBundle") {
                mavenLocalRepositoryDirectory.set(layout.buildDirectory.dir("fake-m2/repository/dev/tramai"))
            }
            $extra
            """.trimIndent(),
        )
        writeFile(
            dir,
            "config/quality/module-catalog.yml",
            fixtureCatalog(
                """
                - path: ":tramai-core"
                  <<: *core
                  layer: core-contracts
                  publishability: published
                  apiStability: stable
                  description: "Fixture core module."
                """.trimIndent(),
            ),
        )
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { `java-library` }
            group = "dev.tramai"
            version = "0.6.0"

            // Sentinel publication tasks that write marker files — used to prove
            // whether a publish task executed.
            tasks.register("generatePomFileForMavenPublication") {
                doLast {
                    val pom = layout.buildDirectory.file("publications/maven/pom-default.xml").get().asFile
                    pom.parentFile.mkdirs()
                    pom.writeText($fixturePomQuoted)
                }
            }
            tasks.register("publishToMavenLocal") {
                doLast {
                    // Create the hermetic mavenLocal dir (the task declares it as
                    // @InputDirectory, so it must exist before the action runs).
                    val m2 = rootProject.layout.buildDirectory.dir("fake-m2/repository/dev/tramai").get().asFile
                    m2.mkdirs()
                    val marker = layout.buildDirectory.file("publishToMavenLocal.executed").get().asFile
                    marker.parentFile.mkdirs()
                    marker.writeText("executed")
                }
            }
            tasks.register("publishMavenPublicationToSovereignBundleLocalRepository") {
                doLast {
                    // Mimic a real publication: create the verification repo dir
                    // (the task declares it as @InputDirectory, so it must exist
                    // before the verifier's action can run). Root project's build dir.
                    val repo = rootProject.layout.buildDirectory.dir("sovereign-runtime-release-verification-repo").get().asFile
                    repo.mkdirs()
                    val marker = layout.buildDirectory.file("sovereignBundleLocal.executed").get().asFile
                    marker.parentFile.mkdirs()
                    marker.writeText("executed")
                }
            }
            tasks.register("publish") {
                doLast {
                    val marker = layout.buildDirectory.file("publish.executed").get().asFile
                    marker.parentFile.mkdirs()
                    marker.writeText("executed")
                }
            }
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
            // The repository wrapper is Gradle 9.0.0 (see gradle/wrapper/gradle-wrapper.properties);
            // TestKit must exercise the same version CI runs.
            .withGradleVersion("9.0.0")
            .withArguments(*args, "--stacktrace")
            .withPluginClasspath()

    /** Minimal schema-3 module catalog with [moduleEntries] appended under the core default. */
    private fun fixtureCatalog(moduleEntries: String): String =
        buildString {
            appendLine("schemaVersion: \"3\"")
            appendLine("dependencyPolicies:")
            appendLine("  core: { allowedLayers: [core-contracts, testing-support] }")
            appendLine("entryDefaults:")
            appendLine(
                "  core: &core { maturity: stable, visibility: public, owner: core, " +
                    "dependencyPolicy: core, releaseInclusion: included, rationale: \"Fixture module.\" }",
            )
            appendLine("modules:")
            // Entries arrive trimmed to column 0 (call-site trimIndent); re-indent
            // under `modules:` so the merged YAML stays valid.
            moduleEntries.trimIndent().lines().forEach { appendLine(if (it.isBlank()) it else "  $it") }
        }

    // ── T1: task types ───────────────────────────────────────────────────────

    @Test
    fun `T1 task types are typed DefaultTasks not anonymous doLast`() {
        val dir = baseFixture()
        val result =
            runner(
                dir,
                "verifyPublicationMetadata",
                "verifyPublishedLocalArtifacts",
                "verifyReleasePublishInputs",
                "verifySovereignRuntimeSignedBundle",
                "prepareSovereignReleaseArtifacts",
                "verifySovereignReleaseManifest",
                "generateSovereignReleaseEvidenceIndex",
            ).buildAndFail()

        // All tasks must be found as typed tasks. With the sentinel fixture the
        // evidence generators will fail on missing evidence (expected), but the
        // error must be a task-execution failure, proving the typed task ran.
        val output = result.output
        assertTrue(output.contains("What went wrong"))
    }

    @Test
    fun `T1b task types resolve to the typed classes`() {
        val dir = baseFixture()
        val result = runner(dir, "tasks", "--all").build()
        for (name in listOf(
            "verifyPublicationMetadata",
            "verifyPublishedLocalArtifacts",
            "verifyReleasePublishInputs",
            "verifySovereignRuntimeSignedBundle",
            "prepareSovereignReleaseArtifacts",
            "verifySovereignReleaseManifest",
            "generateSovereignReleaseEvidenceIndex",
        )) {
            assertTrue(result.output.contains("$name -"), "task $name must be listed")
        }
    }

    // ── W1/W2 — wired description path: catalog → expectedDescriptions → verifier (9.2c-c) ──

    /** Minimal schema-v3 catalog with one published module. */
    private val wiredCatalog =
        """
        schemaVersion: "3"
        dependencyPolicies:
          core: { allowedLayers: [core-contracts] }
        entryDefaults:
          core: &core { maturity: stable, visibility: public, owner: core, dependencyPolicy: core, releaseInclusion: included, rationale: "Fixture core." }
        modules:
          - path: ":tramai-core"
            <<: *core
            layer: core-contracts
            publishability: published
            apiStability: stable
            description: "DESCRIPTION_PLACEHOLDER"
        """.trimIndent()

    @Test
    fun `W1 wired expectedDescriptions passes when POM matches the catalog description`() {
        val dir =
            baseFixture(
                extra =
                    """
                    project(":tramai-core") {
                        afterEvaluate {
                            tasks.named("generatePomFileForMavenPublication") {
                                doLast {
                                    val pom = layout.buildDirectory.file("publications/maven/pom-default.xml").get().asFile
                                    val text = pom.readText().replace("<description>Test module</description>", "<description>Expected description for :tramai-core.</description>")
                                    pom.writeText(text)
                                }
                            }
                        }
                    }
                    """.trimIndent(),
            )
        writeFile(
            dir,
            "config/quality/module-catalog.yml",
            wiredCatalog.replace("DESCRIPTION_PLACEHOLDER", "Expected description for :tramai-core."),
        )
        // The sentinel POM is explicitly rewritten to carry the same unique
        // description as the catalog, so the catalog-derived expected map and
        // the generated POM agree on an obviously-intentional value (not a
        // string coincidence with the default fixture POM).
        runner(dir, "verifyPublicationMetadata").build()
    }

    @Test
    fun `W2 wired expectedDescriptions fails when POM diverges from the catalog description`() {
        val dir = baseFixture()
        writeFile(
            dir,
            "config/quality/module-catalog.yml",
            wiredCatalog.replace("DESCRIPTION_PLACEHOLDER", "Catalog says something else."),
        )
        val result = runner(dir, "verifyPublicationMetadata").buildAndFail()
        assertTrue(
            result.output.contains("description"),
            "verifier must fail on the description divergence: ${result.output.take(800)}",
        )
    }

    // ── T2: exact task names preserved ───────────────────────────────────────

    @Test
    fun `T2 exact task names preserved`() {
        val dir = baseFixture()
        val result = runner(dir, "tasks", "--all").build()
        val output = result.output
        for (name in listOf(
            "verifyPublicationMetadata",
            "verifyPublishedLocalArtifacts",
            "verifyReleasePublishInputs",
            "verifySignedPublicationBundle",
            "verifyReleaseReadiness",
            "verifySovereignRuntimePublication",
            "verifySovereignRuntimeSignedBundle",
            "prepareSovereignReleaseArtifacts",
            "verifySovereignReleaseManifest",
            "verifySovereignRuntimeVerificationRepoClosure",
            "generateSovereignReleaseEvidenceIndex",
        )) {
            assertTrue(output.contains("$name -"), "task $name must be listed")
        }
    }

    // ── T3: remote sovereign URL rejected pre-publication ───────────────────

    @Test
    fun `T3a remote URL does not break unrelated tasks`() {
        val dir = baseFixture()
        val result = runner(dir, "help", "-PtramaiPublishReleaseUrl=https://repo.example.com").build()
        assertTrue(result.task(":help") != null)
    }

    @Test
    fun `T3b sovereign signed bundle fails before sentinel publish executes`() {
        val dir = baseFixture()
        val result =
            runner(
                dir,
                "verifySovereignRuntimeSignedBundle",
                "-PtramaiPublishReleaseUrl=https://repo.example.com",
            ).buildAndFail()
        assertTrue(result.output.contains("only supports file://"))
        // Sentinel publish tasks must NOT have executed
        assertFalse(
            File(dir, "tramai-core/build/publishToMavenLocal.executed").exists(),
            "publishToMavenLocal must not execute",
        )
        assertFalse(
            File(dir, "tramai-core/build/sovereignBundleLocal.executed").exists(),
            "sovereignBundleLocal publish must not execute",
        )
    }

    // ── T4: sovereign file repository accepted ──────────────────────────────

    @Test
    fun `T4 sovereign file repository reaches task execution`() {
        val dir = baseFixture()
        val result =
            runner(
                dir,
                "verifySovereignRuntimeSignedBundle",
                "-PtramaiPublishReleaseUrl=file:///tmp/repo",
            ).buildAndFail()
        // With a file URL the config-time guard passes; the task then fails on
        // missing mavenLocal artifacts — proving the typed task action ran
        // (i.e., the file:// path is accepted and reaches verification).
        assertTrue(result.output.contains("mavenLocal") || result.output.contains("Missing"))
        assertFalse(result.output.contains("only supports file://"))
    }

    // ── T5: unsigned mode ────────────────────────────────────────────────────

    @Test
    fun `T5 unsigned mode does not require asc`() {
        val dir = baseFixture()
        val result = runner(dir, "verifySovereignRuntimeSignedBundle").buildAndFail()
        // No signing material → signingRequested=false → the failure is about
        // mavenLocal artifacts, not a missing .asc signature.
        assertTrue(result.output.contains("mavenLocal"), "expected mavenLocal in output: ${result.output.take(1200)}")
        assertTrue(
            result.output.contains("Missing mavenLocal module directory"),
            "failure must be the mavenLocal check, not signatures: ${result.output.take(1200)}",
        )
    }

    // ── T5b: expected module set is fail-closed, not filtered by existence ───

    @Test
    fun `T5b missing expected module fails closed`() {
        val dir = baseFixture()
        // Declare an expected module that does not exist as a subproject.
        // The missing-project tolerance must apply only to dependency wiring,
        // never to the declared release boundary.
        writeFile(
            dir,
            "tramai-core/src/main/kotlin/dev/tramai/core/Stub.kt",
            "package dev.tramai.core\nclass Stub\n",
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins {
                id("tramai.release-verification")
                id("tramai.sovereign-verification")
            }
            // verifySovereignOpsObservabilityDocs is provided by tramai.sovereign-verification (9.2d-a3b1 typed extraction)
            tasks.named<dev.tramai.build.sovereign.VerifySovereignSignedBundleTask>("verifySovereignRuntimeSignedBundle") {
                mavenLocalRepositoryDirectory.set(layout.buildDirectory.dir("fake-m2/repository/dev/tramai"))
            }
            // The real convention plugin registers sources/javadoc jars; the
            // fixture must too, so the only failing module is :tramai-missing.
            subprojects {
                if (name == "tramai-core") {
                    tasks.register<Jar>("sourcesJar") { archiveClassifier.set("sources"); from("src") }
                    tasks.register<Jar>("javadocJar") { archiveClassifier.set("javadoc"); from("src") }
                }
            }
            """.trimIndent(),
        )
        // The catalog (single publishability authority) declares the missing
        // module as published; the fixture has no such subproject, so the
        // declared release boundary must fail closed.
        writeFile(
            dir,
            "config/quality/module-catalog.yml",
            fixtureCatalog(
                """
                - path: ":tramai-core"
                  <<: *core
                  layer: core-contracts
                  publishability: published
                  apiStability: stable
                  description: "Fixture core module."
                - path: ":tramai-missing"
                  <<: *core
                  layer: core-contracts
                  publishability: published
                  apiStability: preview
                  description: "Fixture module with no subproject."
                """.trimIndent(),
            ),
        )
        val result = runner(dir, "prepareSovereignReleaseArtifacts").buildAndFail()
        // The task must fail because :tramai-missing is part of the declared
        // release boundary and has no artifacts — not silently succeed with a
        // shrunken module set.
        assertTrue(
            result.output.contains("missing its binary jar") || result.output.contains("tramai-missing"),
            "expected module set must be enforced: ${result.output.take(1500)}",
        )
    }

    // ── T7: incomplete evidence fails closed ───────────────────────────────────

    @Test
    fun `T7 incomplete evidence deletes stale manifest`() {
        val dir = baseFixture()
        // Pre-seed a valid-looking old manifest
        val stale = File(dir, "build/sovereign-runtime-release/bundle-manifest.json")
        stale.parentFile.mkdirs()
        stale.writeText("""{"schemaVersion":"sovereign-runtime-release-bundle-v1","modules":[]}""")

        val result = runner(dir, "verifySovereignRuntimeSignedBundle").buildAndFail()
        assertTrue(
            result.output.contains("mavenLocal") || result.output.contains("Missing"),
            "expected mavenLocal/Missing in output, got: ${result.output.take(1200)}",
        )
        // The stale manifest must be gone after the failed run
        assertFalse(stale.exists(), "stale bundle-manifest.json must be invalidated on failure; output was: ${result.output.take(1200)}")
    }

    // ── T7b/T7c: corrupt catalog fails closed (9.2d-b1 P1) ────────────────────
    // The catalog is the single publishability authority. A corrupt catalog
    // must STOP the release/sovereign paths, never degrade to an empty module
    // set (fail-open). One kill test per consumer boundary.

    private fun corruptCatalog(dir: File) {
        writeFile(
            dir,
            "config/quality/module-catalog.yml",
            "schemaVersion: \"3\"\nmodules:\n  - malformed\n",
        )
    }

    @Test
    fun `T7b release verification fails closed on corrupt catalog`() {
        val dir = baseFixture()
        corruptCatalog(dir)
        // publishableModuleNames is resolved eagerly at plugin apply; a corrupt
        // catalog must abort configuration instead of verifying "no modules".
        val result = runner(dir, "verifyPublicationMetadata").buildAndFail()
        assertTrue(
            result.output.contains("MODULE_CATALOG") || result.output.contains("catalog"),
            "corrupt catalog must fail closed with a catalog diagnostic, got: ${result.output.take(1200)}",
        )
    }

    @Test
    fun `T7c sovereign bundle fails closed on corrupt catalog`() {
        val dir = baseFixture()
        corruptCatalog(dir)
        // sovereignBundleModules is resolved lazily at task realization; the
        // bundle set must never silently become empty.
        val result = runner(dir, "verifySovereignRuntimeSignedBundle").buildAndFail()
        assertTrue(
            result.output.contains("MODULE_CATALOG") || result.output.contains("catalog"),
            "corrupt catalog must fail closed with a catalog diagnostic, got: ${result.output.take(1200)}",
        )
    }

    // ── T9: evidence index depends on all producers ─────────────────────────

    @Test
    fun `T9 evidence index dependency wiring`() {
        val dir = baseFixture()
        val result = runner(dir, "generateSovereignReleaseEvidenceIndex", "--dry-run").build()
        for (producer in listOf(
            "verifyReleaseReadiness",
            "verifySovereignRuntimePublication",
            "verifySovereignRuntimeSignedBundle",
            "verifySovereignRuntimeConsumerSmoke",
            "prepareSovereignReleaseArtifacts",
            "verifySovereignReleaseManifest",
        )) {
            assertTrue(result.output.contains(":$producer"), "evidence index must depend on $producer")
        }
    }

    // ── T10: missing evidence fails closed AND stale evidence is invalidated ─

    @Test
    fun `T10 evidence index fails when bundle manifest missing`() {
        val dir = baseFixture()
        // The evidence task reads git metadata BEFORE validating artifacts, so
        // the fixture must be a real git repo to reach the artifact checks.
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        writeFile(dir, "committed.txt", "hello")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "seed")
        git(dir, "remote", "add", "origin", "https://github.com/GionaGranchelli/tramAI.git")
        // Seed a valid-looking stale evidence index from a previous PASS.
        val staleJson = File(dir, "build/sovereign-runtime-release/evidence-index.json")
        staleJson.parentFile.mkdirs()
        staleJson.writeText("""{"schemaVersion":"sovereign-release-evidence-index-v1","generatedAt":"2020-01-01T00:00:00Z"}""")
        val staleMd = File(dir, "build/sovereign-runtime-release/evidence-index.md")
        staleMd.writeText("# stale evidence")

        // Isolate the evidence task from upstream producers (their wiring is
        // proven by T9); this test pins the task's own fail-closed behavior.
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins {
                id("tramai.release-verification")
                id("tramai.sovereign-verification")
            }
            // verifySovereignOpsObservabilityDocs is provided by tramai.sovereign-verification (9.2d-a3b1 typed extraction)
            tasks.named<dev.tramai.build.sovereign.VerifySovereignSignedBundleTask>("verifySovereignRuntimeSignedBundle") {
                mavenLocalRepositoryDirectory.set(layout.buildDirectory.dir("fake-m2/repository/dev/tramai"))
            }
            tasks.named("generateSovereignReleaseEvidenceIndex") {
                setDependsOn(emptyList<String>())
            }
            """.trimIndent(),
        )

        val result = runner(dir, "generateSovereignReleaseEvidenceIndex").buildAndFail()
        assertTrue(
            result.output.contains("bundle-manifest.json"),
            "expected bundle-manifest.json failure, got: ${result.output.take(1500)}",
        )
        // The stale evidence from the previous PASS must NOT survive a failed run.
        assertFalse(staleJson.exists(), "stale evidence-index.json must be invalidated on failure. OUTPUT: ${result.output.take(2500)}")
        assertFalse(staleMd.exists(), "stale evidence-index.md must be invalidated on failure. OUTPUT: ${result.output.take(2500)}")
    }

    // ── T12: configuration cache proof ───────────────────────────────────────

    @Test
    fun `T12 configuration cache reuse for the typed evidence task`() {
        // The typed evidence task must be configuration-cache clean: it may not
        // read `project` at execution time (runGit used to reference
        // project.projectDir). Exercise the real task twice — the second run
        // must REUSE the stored configuration cache, proving no execution-time
        // project access and no cache-invalidation input.
        val dir = baseFixture()
        seedEvidenceInputs(dir)
        // Git metadata is read at execution time, so the fixture must be a repo.
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        writeFile(dir, "committed.txt", "hello")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "commit A")
        git(dir, "remote", "add", "origin", "https://github.com/GionaGranchelli/tramAI.git")
        val shaA = git(dir, "rev-parse", "HEAD").trim()

        val args =
            arrayOf(
                "generateSovereignReleaseEvidenceIndex",
                "--configuration-cache",
                "--configuration-cache-problems=fail",
            )
        val first = runner(dir, *args).build()
        assertTrue(
            first.task(":generateSovereignReleaseEvidenceIndex") != null,
            "evidence task must execute: ${first.output.take(800)}",
        )
        assertTrue(
            first.output.contains("Configuration cache entry stored"),
            "first run must store the configuration cache: ${first.output.take(800)}",
        )
        assertTrue(
            evidenceCommitSha(dir) == shaA,
            "evidence must record commit A: ${File(dir, "build/sovereign-runtime-release/evidence-index.json").readText()}",
        )

        // New git commit that does NOT touch any declared evidence input.
        writeFile(dir, "unrelated.txt", "commit B content")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "commit B")
        val shaB = git(dir, "rev-parse", "HEAD").trim()

        val second = runner(dir, *args).build()
        assertTrue(
            second.output.contains("Reusing configuration cache"),
            "second run must reuse the configuration cache: ${second.output.take(800)}",
        )
        // The evidence task must EXECUTE again — never be skipped as UP-TO-DATE —
        // so the freshly committed SHA B is recorded, not the stale SHA A.
        val evidenceTask = second.task(":generateSovereignReleaseEvidenceIndex")
        assertTrue(evidenceTask != null, "evidence task must execute on second run")
        assertTrue(
            !evidenceTask!!.outcome.name.contains("UP_TO_DATE") && !evidenceTask.outcome.name.contains("NO_SOURCE"),
            "evidence task must NOT be skipped as up-to-date: ${second.output.take(800)}",
        )
        assertTrue(
            evidenceCommitSha(dir) == shaB,
            "evidence must record commit B after a git-only change: ${File(
                dir,
                "build/sovereign-runtime-release/evidence-index.json",
            ).readText()}",
        )
    }

    private fun evidenceCommitSha(dir: File): String {
        val text = File(dir, "build/sovereign-runtime-release/evidence-index.json").readText()
        val m = Regex("\"commitSha\"\\s*:\\s*\"([a-f0-9]{40})\"").find(text)
        return m?.groupValues?.get(1) ?: error("no commitSha in evidence: $text")
    }

    // ── T13: clean-workspace producer graph for prepareSovereignReleaseArtifacts ──

    @Test
    fun `T13 prepareSovereignReleaseArtifacts has real producer edges from a clean workspace`() {
        val dir = baseFixture()
        // Fixture tramai-core only has `jar` (from java-library); the real
        // convention plugin also registers sourcesJar/javadocJar. Register them
        // here with real sources so the jars are non-empty.
        writeFile(
            dir,
            "tramai-core/src/main/kotlin/dev/tramai/core/Stub.kt",
            "package dev.tramai.core\nclass Stub\n",
        )
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { `java-library` }
            group = "dev.tramai"
            version = "0.6.0"

            tasks.register<Jar>("sourcesJar") {
                archiveClassifier.set("sources")
                from("src/main/kotlin")
            }
            tasks.register<Jar>("javadocJar") {
                archiveClassifier.set("javadoc")
                from("src/main/kotlin")
            }
            """.trimIndent(),
        )
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins {
                id("tramai.release-verification")
                id("tramai.sovereign-verification")
            }
            // verifySovereignOpsObservabilityDocs is provided by tramai.sovereign-verification (9.2d-a3b1 typed extraction)
            tasks.named<dev.tramai.build.sovereign.VerifySovereignSignedBundleTask>("verifySovereignRuntimeSignedBundle") {
                mavenLocalRepositoryDirectory.set(layout.buildDirectory.dir("fake-m2/repository/dev/tramai"))
            }
            tasks.named<dev.tramai.build.sovereign.PrepareSovereignReleaseArtifactsTask>("prepareSovereignReleaseArtifacts") {
                moduleNames.set(listOf("tramai-core"))
            }
            """.trimIndent(),
        )
        // Clean-workspace discriminator: no build/libs anywhere.
        assertFalse(File(dir, "tramai-core/build/libs").exists())

        val result = runner(dir, "prepareSovereignReleaseArtifacts", "--dry-run").build()
        for (producer in listOf(
            ":tramai-core:jar",
            ":tramai-core:sourcesJar",
            ":tramai-core:javadocJar",
        )) {
            assertTrue(
                result.output.contains(producer),
                "prepareSovereignReleaseArtifacts must depend on $producer: ${result.output.take(1500)}",
            )
        }
        // The real run must execute the producers and generate the manifest.
        val real = runner(dir, "prepareSovereignReleaseArtifacts").build()
        for (producer in listOf(
            "> Task :tramai-core:jar",
            "> Task :tramai-core:sourcesJar",
            "> Task :tramai-core:javadocJar",
        )) {
            assertTrue(
                real.output.contains(producer),
                "$producer must execute: ${real.output.take(1500)}",
            )
        }
        val manifest = File(dir, "build/sovereign-release/release-artifacts-v1.json")
        assertTrue(manifest.isFile, "release-artifacts-v1.json must be generated")
        assertTrue(
            real.output.contains("BUILD SUCCESSFUL"),
            "prepare must succeed on a clean workspace: ${real.output.take(800)}",
        )
    }

    // ── T14: evidence git metadata via injected ExecOperations ──────────────

    @Test
    fun `T14a evidence records correct git HEAD and repository`() {
        val dir = baseFixture()
        seedEvidenceInputs(dir)
        // Make the fixture a real git repository with a known commit and remote.
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        writeFile(dir, "committed.txt", "hello")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "seed")
        git(dir, "remote", "add", "origin", "https://github.com/GionaGranchelli/tramAI.git")
        val expectedSha = git(dir, "rev-parse", "HEAD").trim()

        val result = runner(dir, "generateSovereignReleaseEvidenceIndex").build()
        val json = File(dir, "build/sovereign-runtime-release/evidence-index.json")
        assertTrue(json.isFile, "evidence index must be generated: ${result.output.take(800)}")
        val text = json.readText()
        assertTrue(text.contains("\"commitSha\": \"$expectedSha\""), "evidence must record HEAD $expectedSha: $text")
        assertTrue(text.contains("\"repository\": \"GionaGranchelli/tramAI\""), "evidence must record repository: $text")
    }

    @Test
    fun `T14b git failure fails evidence generation closed`() {
        // Fixture is NOT a git repository: `git rev-parse HEAD` exits non-zero.
        // The evidence generation must FAIL (never record empty/wrong metadata).
        val dir = baseFixture()
        seedEvidenceInputs(dir)
        val result = runner(dir, "generateSovereignReleaseEvidenceIndex").buildAndFail()
        assertTrue(
            result.output.contains("non-zero exit") || result.output.contains("git"),
            "git failure must surface as an execution failure: ${result.output.take(1200)}",
        )
        // No evidence file may be produced from a failed run.
        assertFalse(
            File(dir, "build/sovereign-runtime-release/evidence-index.json").exists(),
            "no evidence index may survive a failed git resolution",
        )
    }

    private fun seedEvidenceInputs(dir: File) {
        writeFile(
            dir,
            "build/sovereign-runtime-release/bundle-manifest.json",
            """{"schemaVersion":"sovereign-runtime-release-bundle-v1","modules":[]}""",
        )
        writeFile(
            dir,
            "build/sovereign-runtime-release-verification-repo/.keep",
            "repo",
        )
        writeFile(
            dir,
            "build/sovereign-release/release-artifacts-v1.json",
            """{"schemaVersion":1,"artifacts":[]}""",
        )
        writeFile(
            dir,
            "build/sovereign-release/artifacts/.keep",
            "artifacts",
        )
        // Isolate the evidence task from upstream producers (proven by T9).
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins {
                id("tramai.release-verification")
                id("tramai.sovereign-verification")
            }
            // verifySovereignOpsObservabilityDocs is provided by tramai.sovereign-verification (9.2d-a3b1 typed extraction)
            tasks.named("generateSovereignReleaseEvidenceIndex") {
                setDependsOn(emptyList<String>())
            }
            """.trimIndent(),
        )
    }

    private fun git(
        dir: File,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "git ${args.joinToString(" ")} failed ($exit): $output" }
        return output
    }

    // ── T15: presence-only properties must default to false ─────────────────

    @Test
    fun `T15 missing remote-publish properties produce the stable diagnostic`() {
        val dir = baseFixture()
        // No tramaiPublishReleaseUrl/username/password/signing properties set.
        val result = runner(dir, "verifyReleasePublishInputs").buildAndFail()
        assertTrue(
            result.output.contains("Missing required Gradle property for remote release publishing: tramaiPublishReleaseUrl"),
            "stable TramAI diagnostic required, got: ${result.output.take(1200)}",
        )
    }

    // ── T16–T18: verify050ReleaseReadiness extraction (9.2d-b2 slice C) ─────
    // The document/file inspection implementation moved from the root build
    // script into TramaiReleaseVerificationPlugin. These tests prove the
    // plugin — not a residual root convention — executes the behavior, and
    // that the root build script no longer carries the implementation.

    /**
     * Fixture where the aggregation dependencies (provided by other plugins in
     * the real build) are registered as no-ops, so running
     * verify050ReleaseReadiness reaches ONLY the moved doLast implementation.
     */
    private fun readinessFixture(extra: String = ""): File {
        val dir = baseFixture()
        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins {
                id("tramai.release-verification")
            }
            // The real build provides these dependencies from other plugins;
            // register no-ops so the aggregation reaches the moved doLast.
            tasks.register("verifyVersionAlignment")
            tasks.register("verifyWorkflowApiStabilityBoundary")
            tasks.register("verifySovereignRuntimeApiBoundary")
            tasks.register("verifyToolGovernanceExample")
            // verifyReleaseReadiness is a plugin-registered aggregation whose
            // deps run real verification (POM/artifacts); neutralize them so
            // the fixture isolates the moved verify050ReleaseReadiness doLast.
            tasks.named("verifyReleaseReadiness") {
                setDependsOn(emptyList<String>())
            }
            $extra
            """.trimIndent(),
        )
        writeFile(
            dir,
            "gradle.properties",
            """
            tramaiVersion=0.6.0
            tramaiGroup=dev.tramai
            tramaiReleaseDate=2026-08-31
            """.trimIndent(),
        )
        // Valid 0.5.0 release-readiness evidence: doc, changelog, status,
        // roadmap, publish workflow — matching exactly what the moved
        // implementation requires.
        writeFile(
            dir,
            "docs/releases/0.5.0-release-readiness.md",
            """
            # 0.5.0 Release Readiness
            All checks passed.
            """.trimIndent(),
        )
        writeFile(
            dir,
            "CHANGELOG.md",
            """
            # Changelog

            ## 0.5.0 - 2026-08-31

            ### Added

            - Feature one (PR #10)
            - Feature two (PR #11)

            ### Changed

            - Refactor one
            """.trimIndent(),
        )
        writeFile(
            dir,
            "docs/STATUS.md",
            """
            # Status

            0.5.0 release candidate prepared
            """.trimIndent(),
        )
        writeFile(
            dir,
            "docs/POST-SOVEREIGNTY-ROADMAP.md",
            """
            # Roadmap

            Release prepared — publication pending
            """.trimIndent(),
        )
        writeFile(
            dir,
            ".github/workflows/publish.yml",
            """
            name: publish
            on: [workflow_dispatch]
            jobs:
              publish:
                steps:
                  - run: echo "Verify version alignment"
            """.trimIndent(),
        )
        return dir
    }

    @Test
    fun `T16 verify050ReleaseReadiness fails closed on missing release-readiness evidence`() {
        val dir = readinessFixture()
        // Delete the required doc: the moved implementation must fail with the
        // exact historical diagnostic.
        File(dir, "docs/releases/0.5.0-release-readiness.md").delete()
        val result = runner(dir, "verify050ReleaseReadiness").buildAndFail()
        assertTrue(
            result.output.contains("Missing 0.5.0 release-readiness document at"),
            "exact release-readiness diagnostic required, got: ${result.output.take(1200)}",
        )
    }

    @Test
    fun `T17 verify050ReleaseReadiness passes with valid evidence from the plugin implementation`() {
        val dir = readinessFixture()
        // No evidence may be missing: the moved implementation must pass and
        // log the completion marker exactly as the root script did.
        val result = runner(dir, "verify050ReleaseReadiness").build()
        assertTrue(
            result.output.contains("verify050ReleaseReadiness: all checks passed."),
            "completion marker required, got: ${result.output.take(1200)}",
        )
    }

    @Test
    fun `T18 root build script no longer carries the release-readiness implementation`() {
        val prop =
            System.getProperty("tramai.repositoryRoot")
                ?: error("tramai.repositoryRoot system property not set (wired by build-logic/build.gradle.kts)")
        val rootBuildScript = File(prop, "build.gradle.kts").readText()

        // The root may compose the task into `check`…
        assertTrue(
            rootBuildScript.contains("dependsOn(\"verify050ReleaseReadiness\")"),
            "root must still wire verify050ReleaseReadiness into check",
        )
        // …but must not contain the moved implementation markers.
        for (marker in listOf(
            "Missing \$expectedVersion release-readiness document",
            "Duplicate PR entries in Added section",
            "sovereign-runtime-release-readiness.md must not claim",
            "verify050ReleaseReadiness: all checks passed.",
        )) {
            assertFalse(
                rootBuildScript.contains(marker),
                "root build.gradle.kts must not contain release-readiness implementation marker: $marker",
            )
        }
    }
}
