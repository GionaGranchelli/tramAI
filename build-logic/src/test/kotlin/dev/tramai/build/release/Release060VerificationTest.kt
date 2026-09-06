package dev.tramai.build.release

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TestKit positive and adversarial discriminator suite for the authoritative
 * TramAI 0.6.0 release verification command: ./gradlew verify060MaintainabilityRelease (Epic 12.4a).
 */
class Release060VerificationTest {
    @TempDir
    lateinit var tempDir: File

    private fun writeFile(
        base: File,
        relativePath: String,
        content: String,
    ): File {
        val target = File(base, relativePath)
        target.parentFile.mkdirs()
        target.writeText(content)
        return target
    }

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
            moduleEntries.trimIndent().lines().forEach { appendLine(if (it.isBlank()) it else "  $it") }
        }

    private fun baseFixture(
        extraBuild: String = "",
        includeApiCheck: Boolean = true,
        includeArchitecture: Boolean = true,
    ): File {
        val dir = File(tempDir, "fixture").apply { mkdirs() }
        seedFixtureProjectFiles(dir)
        seedFixtureReleaseDocs(dir)
        seedFixtureAuditFindings(dir)
        seedFixtureBuildScript(dir, extraBuild, includeApiCheck, includeArchitecture)
        return dir
    }

    private fun seedFixtureProjectFiles(dir: File) {
        writeFile(
            dir,
            "settings.gradle.kts",
            """
            rootProject.name = "sample-release"
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
        seedFixtureCoreModule(dir)
    }

    private fun seedFixtureCoreModule(dir: File) {
        writeFile(
            dir,
            "tramai-core/build.gradle.kts",
            """
            plugins { `java-library` }
            group = "dev.tramai"
            version = "0.6.0"

            tasks.register("generatePomFileForMavenPublication") {
                doLast {
                    val pom = layout.buildDirectory.file("publications/maven/pom-default.xml").get().asFile
                    pom.parentFile.mkdirs()
                    pom.writeText("<project><groupId>dev.tramai</groupId><artifactId>tramai-core</artifactId><version>0.6.0</version></project>")
                }
            }
            tasks.register("publishToMavenLocal") {
                doLast {
                    val m2 = rootProject.layout.buildDirectory.dir("fake-m2/repository/dev/tramai/tramai-core/0.6.0").get().asFile
                    m2.mkdirs()
                    File(m2, "tramai-core-0.6.0.pom").writeText("<project/>")
                    File(m2, "tramai-core-0.6.0.jar").writeText("PK")
                    File(m2, "tramai-core-0.6.0-sources.jar").writeText("PK")
                    File(m2, "tramai-core-0.6.0-javadoc.jar").writeText("PK")
                    File(m2, "tramai-core-0.6.0.module").writeText("{}")
                }
            }
            tasks.register("publishMavenPublicationToSovereignBundleLocalRepository") {
                doLast {
                    val repo = rootProject.layout.buildDirectory.dir("sovereign-runtime-release-verification-repo").get().asFile
                    repo.mkdirs()
                }
            }
            tasks.register("publish")
            """.trimIndent(),
        )
    }

    private fun seedFixtureReleaseDocs(dir: File) {
        writeFile(
            dir,
            "CHANGELOG.md",
            """
            # Changelog
            ## 0.6.0 - 2026-09-06
            ### Added
            - Governed workflow release
            """.trimIndent(),
        )
        writeFile(
            dir,
            "docs/releases/0.6.0-release-readiness.md",
            """
            # 0.6.0 Release Readiness
            READY_FOR_0.6.0_RELEASE
            """.trimIndent(),
        )
        writeFile(
            dir,
            "docs/releases/0.6.0-release-notes.md",
            """
            # 0.6.0 Release Notes
            Release Notes for TramAI 0.6.0
            """.trimIndent(),
        )
        writeFile(
            dir,
            "docs/releases/0.6.0-migration-guide.md",
            """
            # 0.6.0 Migration Guide
            Migration Guide for TramAI 0.6.0
            """.trimIndent(),
        )
    }

    private fun seedFixtureAuditFindings(dir: File) {
        writeFile(
            dir,
            "docs/evidence/12.3a-independent-review-findings.json",
            """
            {
              "schemaVersion": "1.0",
              "audit": {
                "status": "CLOSED",
                "disposition": "READY_FOR_0.6.0_RELEASE"
              },
              "findings": [
                { "id": "R12-001", "severity": "P0", "status": "CLOSED", "owner": "security", "rationale": "Fixed in PR #397" },
                { "id": "R12-002", "severity": "P1", "status": "CLOSED", "owner": "persistence", "rationale": "Fixed in PR #397" },
                { "id": "R12-003", "severity": "P1", "status": "CLOSED", "owner": "build-logic", "rationale": "Fixed in PR #398" },
                { "id": "R12-004", "severity": "P2", "status": "DEFERRED", "owner": "engine", "rationale": "Backwards compatibility" },
                { "id": "R12-005", "severity": "P2", "status": "DEFERRED", "owner": "security", "rationale": "DLP defense in depth" },
                { "id": "R12-006", "severity": "P2", "status": "DEFERRED", "owner": "observability", "rationale": "OTel standard" },
                { "id": "R12-007", "severity": "P2", "status": "DEFERRED", "owner": "testing", "rationale": "Covered by provider tests" },
                { "id": "R12-008", "severity": "P2", "status": "DEFERRED", "owner": "persistence", "rationale": "Roundtrip decryption asserted" },
                { "id": "R12-009", "severity": "P3", "status": "DEFERRED", "owner": "orchestration", "rationale": "Fail-closed binary path" },
                { "id": "R12-010", "severity": "P3", "status": "DEFERRED", "owner": "persistence", "rationale": "Determinism test" },
                { "id": "R12-011", "severity": "P3", "status": "DEFERRED", "owner": "orchestration", "rationale": "Graceful drain test" },
                { "id": "R12-012", "severity": "P3", "status": "DEFERRED", "owner": "docs", "rationale": "Minor line drift" },
                { "id": "R12-013", "severity": "P3", "status": "DEFERRED", "owner": "docs", "rationale": "Minor line drift" },
                { "id": "R12-014", "severity": "P3", "status": "DEFERRED", "owner": "docs", "rationale": "Minor line drift" },
                { "id": "R12-015", "severity": "P3", "status": "DEFERRED", "owner": "docs", "rationale": "Minor namespace drift" }
              ]
            }
            """.trimIndent(),
        )
    }

    private fun seedFixtureBuildScript(
        dir: File,
        extraBuild: String,
        includeApiCheck: Boolean,
        includeArchitecture: Boolean,
    ) {
        val stubsList =
            mutableListOf(
                "check",
                "spotlessCheck",
                "verifyStaticAnalysis",
                "verifyStaticSafetyGuards",
                "verifyCompilerWarnings",
                "verifyDependencyHygiene",
                "verifyCancellationSafety",
                "verifyMaintainabilityBaseline",
                "verifyModuleManifest",
                "verifyModuleMatrixDrift",
                "verifyCriticalCoverage",
                "verifyMutationRatchet",
                "verifyJUnitTestSignatures",
                "verifyChangePolicy",
                "verifyVersionAlignment",
                "verifySovereignRuntimeReleaseCandidate",
            )
        if (includeApiCheck) stubsList.add("apiCheck")
        if (includeArchitecture) stubsList.add("verify060Architecture")

        val stubsLiteral = stubsList.joinToString(", ") { "\"$it\"" }

        writeFile(
            dir,
            "build.gradle.kts",
            """
            plugins {
                id("tramai.release-verification")
                id("tramai.sovereign-verification")
                id("tramai.sovereign-lab-verification")
            }

            val stubs = listOf($stubsLiteral)
            stubs.forEach { stubName ->
                if (tasks.findByName(stubName) == null) {
                    tasks.register(stubName) {
                        doLast { println("Executed stub: " + stubName) }
                    }
                }
            }

            // Isolate mavenLocal
            tasks.named<dev.tramai.build.sovereign.VerifySovereignSignedBundleTask>(
                "verifySovereignRuntimeSignedBundle",
            ) {
                mavenLocalRepositoryDirectory.set(
                    layout.buildDirectory.dir("fake-m2/repository/dev/tramai"),
                )
            }

            $extraBuild
            """.trimIndent(),
        )
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

    // ── V1: Positive command wiring ───────────────────────────────────────────

    @Test
    fun `V1 verify060MaintainabilityRelease includes all required authorities in graph`() {
        val dir = baseFixture()
        val result = runner(dir, "verify060MaintainabilityRelease", "--dry-run").build()
        val output = result.output

        val requiredAuthorities =
            listOf(
                ":check",
                ":spotlessCheck",
                ":verifyStaticAnalysis",
                ":verifyStaticSafetyGuards",
                ":verifyCompilerWarnings",
                ":verifyDependencyHygiene",
                ":verifyCancellationSafety",
                ":verify060Architecture",
                ":apiCheck",
                ":verifyMaintainabilityBaseline",
                ":verifyModuleManifest",
                ":verifyModuleMatrixDrift",
                ":verifyCriticalCoverage",
                ":verifyMutationRatchet",
                ":verifyJUnitTestSignatures",
                ":verifyChangePolicy",
                ":verifyPublicationMetadata",
                ":verifyPublishedLocalArtifacts",
                ":verifyVersionAlignment",
                ":verifySovereignRuntimeReleaseCandidate",
                ":verifySovereignRuntimeVerificationRepoClosure",
                ":verifySovereignRuntimeConsumerSmoke",
                ":verifySovereignDocumentIntelligenceEvidenceRun",
                ":verifySovereignRuntimeApiBoundary",
                ":verifySovereignRuntimeClosureDocs",
                ":verifySovereignOpsObservabilityDocs",
                ":prepareSovereignReleaseArtifacts",
                ":verifySovereignReleaseManifest",
                ":verifyReleaseDocumentationIntegrity",
                ":verifyReleaseRequiredFiles",
                ":verifyAuditClosure",
                ":verify060MaintainabilityRelease",
            )

        for (auth in requiredAuthorities) {
            assertTrue(
                output.contains(auth),
                "verify060MaintainabilityRelease must depend on $auth; missing from output:\n$output",
            )
        }
    }

    // ── M1: Remove apiCheck dependency ────────────────────────────────────────

    @Test
    fun `M1 release verification fails if apiCheck authority is absent`() {
        val dir = baseFixture(includeApiCheck = false)
        val result = runner(dir, "verify060MaintainabilityRelease", "--dry-run").buildAndFail()
        assertTrue(
            result.output.contains("apiCheck") || result.output.contains("Task with name 'apiCheck' not found"),
            "M1: Missing apiCheck authority must fail graph resolution: ${result.output.take(800)}",
        )
    }

    // ── M2: Remove verify060Architecture dependency ───────────────────────────

    @Test
    fun `M2 release verification fails if verify060Architecture authority is absent`() {
        val dir = baseFixture(includeArchitecture = false)
        val result = runner(dir, "verify060MaintainabilityRelease", "--dry-run").buildAndFail()
        assertTrue(
            result.output.contains("verify060Architecture") ||
                result.output.contains("Task with name 'verify060Architecture' not found"),
            "M2: Missing verify060Architecture must fail graph resolution: ${result.output.take(800)}",
        )
    }

    // ── M3: Remove consumer smoke authority ───────────────────────────────────

    @Test
    fun `M3 release verification fails if consumer smoke authority is missing`() {
        val dir =
            baseFixture(
                extraBuild =
                    """
                    tasks.named<Exec>("verifySovereignRuntimeConsumerSmoke") {
                        setDependsOn(emptyList<String>())
                        commandLine("nonexistent-executable-smoke-fail")
                    }
                    """.trimIndent(),
            )
        val result = runner(dir, "verifySovereignRuntimeConsumerSmoke").buildAndFail()
        assertTrue(
            result.output.contains("nonexistent-executable-smoke-fail") ||
                result.output.contains("A problem occurred starting process") ||
                result.output.contains("Cannot run program"),
            "M3: Broken consumer smoke must fail execution: ${result.output.take(800)}",
        )
    }

    // ── M4: Remove sovereign release manifest authority ───────────────────────

    @Test
    fun `M4 release verification fails if sovereign release manifest is inconsistent`() {
        val dir = baseFixture()
        writeFile(
            dir,
            "build/sovereign-release/release-artifacts-v1.json",
            """{"schemaVersion":1,"artifacts":[{"module":"tramai-core","file":"nonexistent.jar","sha256":"abc"}]}""",
        )
        val result = runner(dir, "verifySovereignReleaseManifest").buildAndFail()
        assertTrue(
            result.output.contains("artifacts") ||
                result.output.contains("Missing") ||
                result.output.contains("nonexistent.jar"),
            "M4: Sovereign manifest mismatch must fail verification: ${result.output.take(800)}",
        )
    }

    // ── M5: Empty publishable module set ──────────────────────────────────────

    @Test
    fun `M5 empty publishable module set fails closed`() {
        val dir = baseFixture()
        writeFile(
            dir,
            "config/quality/module-catalog.yml",
            fixtureCatalog(""),
        )
        val result = runner(dir, "verifyPublicationMetadata").buildAndFail()
        assertTrue(
            result.output.contains("publishable") ||
                result.output.contains("empty") ||
                result.output.contains("catalog") ||
                result.output.contains("No publishable"),
            "M5: Empty publishable module set must fail closed: ${result.output.take(800)}",
        )
    }

    // ── M6: Missing migration guide ───────────────────────────────────────────

    @Test
    fun `M6 missing migration guide fails release required files check`() {
        val dir = baseFixture()
        File(dir, "docs/releases/0.6.0-migration-guide.md").delete()
        val result = runner(dir, "verifyReleaseRequiredFiles").buildAndFail()
        assertTrue(
            result.output.contains("0.6.0-migration-guide.md") &&
                result.output.contains("Required release file missing"),
            "M6: Missing migration guide must fail verification: ${result.output.take(800)}",
        )
    }

    // ── M7: Broken internal documentation link ────────────────────────────────

    @Test
    fun `M7 broken documentation link fails documentation integrity check`() {
        val dir = baseFixture()
        writeFile(
            dir,
            "docs/broken-link.md",
            """
            # Broken Doc
            See [missing document](./non-existent-file.md) for details.
            """.trimIndent(),
        )
        val result = runner(dir, "verifyReleaseDocumentationIntegrity").buildAndFail()
        assertTrue(
            result.output.contains("broken relative link") &&
                result.output.contains("non-existent-file.md"),
            "M7: Broken relative doc link must fail verification: ${result.output.take(800)}",
        )
    }

    // ── M8: Unresolved P0 or P1 audit finding ─────────────────────────────────

    @Test
    fun `M8 unresolved P0 or P1 audit finding fails audit closure check`() {
        val dir = baseFixture()
        writeFile(
            dir,
            "docs/evidence/12.3a-independent-review-findings.json",
            """
            {
              "schemaVersion": "1.0",
              "findings": [
                { "id": "R12-001", "severity": "P0", "status": "OPEN", "owner": "security", "rationale": "Unresolved" },
                { "id": "R12-002", "severity": "P1", "status": "CLOSED", "owner": "persistence", "rationale": "Fixed" },
                { "id": "R12-003", "severity": "P1", "status": "CLOSED", "owner": "build-logic", "rationale": "Fixed" },
                { "id": "R12-004", "severity": "P2", "status": "DEFERRED", "owner": "engine", "rationale": "Backwards compatibility" },
                { "id": "R12-005", "severity": "P2", "status": "DEFERRED", "owner": "security", "rationale": "DLP defense in depth" },
                { "id": "R12-006", "severity": "P2", "status": "DEFERRED", "owner": "observability", "rationale": "OTel standard" },
                { "id": "R12-007", "severity": "P2", "status": "DEFERRED", "owner": "testing", "rationale": "Covered by provider tests" },
                { "id": "R12-008", "severity": "P2", "status": "DEFERRED", "owner": "persistence", "rationale": "Roundtrip decryption asserted" },
                { "id": "R12-009", "severity": "P3", "status": "DEFERRED", "owner": "orchestration", "rationale": "Fail-closed binary path" },
                { "id": "R12-010", "severity": "P3", "status": "DEFERRED", "owner": "persistence", "rationale": "Determinism test" },
                { "id": "R12-011", "severity": "P3", "status": "DEFERRED", "owner": "orchestration", "rationale": "Graceful drain test" },
                { "id": "R12-012", "severity": "P3", "status": "DEFERRED", "owner": "docs", "rationale": "Minor line drift" },
                { "id": "R12-013", "severity": "P3", "status": "DEFERRED", "owner": "docs", "rationale": "Minor line drift" },
                { "id": "R12-014", "severity": "P3", "status": "DEFERRED", "owner": "docs", "rationale": "Minor line drift" },
                { "id": "R12-015", "severity": "P3", "status": "DEFERRED", "owner": "docs", "rationale": "Minor namespace drift" }
              ]
            }
            """.trimIndent(),
        )
        val result = runner(dir, "verifyAuditClosure").buildAndFail()
        assertTrue(
            result.output.contains("R12-001") && result.output.contains("is not CLOSED"),
            "M8: Unclosed P0 finding must fail audit closure: ${result.output.take(800)}",
        )
    }

    // ── M9: Malformed or missing audit evidence ───────────────────────────────

    @Test
    fun `M9 missing audit evidence fails audit closure check closed`() {
        val dir = baseFixture()
        File(dir, "docs/evidence/12.3a-independent-review-findings.json").delete()
        val result = runner(dir, "verifyAuditClosure").buildAndFail()
        assertTrue(
            result.output.contains("Audit findings evidence file missing") ||
                result.output.contains("does not exist") ||
                result.output.contains("auditFindingsFile"),
            "M9: Missing audit file must fail closed: ${result.output.take(800)}",
        )
    }

    // ── M10: Deferred finding without rationale fails ─────────────────────────

    @Test
    fun `M10 deferred finding without owner or rationale fails audit closure`() {
        val dir = baseFixture()
        writeFile(
            dir,
            "docs/evidence/12.3a-independent-review-findings.json",
            """
            {
              "schemaVersion": "1.0",
              "findings": [
                { "id": "R12-001", "severity": "P0", "status": "CLOSED", "owner": "security", "rationale": "Fixed" },
                { "id": "R12-002", "severity": "P1", "status": "CLOSED", "owner": "persistence", "rationale": "Fixed" },
                { "id": "R12-003", "severity": "P1", "status": "CLOSED", "owner": "build-logic", "rationale": "Fixed" },
                { "id": "R12-004", "severity": "P2", "status": "DEFERRED", "owner": "", "rationale": "" },
                { "id": "R12-005", "severity": "P2", "status": "DEFERRED", "owner": "security", "rationale": "DLP defense in depth" },
                { "id": "R12-006", "severity": "P2", "status": "DEFERRED", "owner": "observability", "rationale": "OTel standard" },
                { "id": "R12-007", "severity": "P2", "status": "DEFERRED", "owner": "testing", "rationale": "Covered by provider tests" },
                { "id": "R12-008", "severity": "P2", "status": "DEFERRED", "owner": "persistence", "rationale": "Roundtrip decryption asserted" },
                { "id": "R12-009", "severity": "P3", "status": "DEFERRED", "owner": "orchestration", "rationale": "Fail-closed binary path" },
                { "id": "R12-010", "severity": "P3", "status": "DEFERRED", "owner": "persistence", "rationale": "Determinism test" },
                { "id": "R12-011", "severity": "P3", "status": "DEFERRED", "owner": "orchestration", "rationale": "Graceful drain test" },
                { "id": "R12-012", "severity": "P3", "status": "DEFERRED", "owner": "docs", "rationale": "Minor line drift" },
                { "id": "R12-013", "severity": "P3", "status": "DEFERRED", "owner": "docs", "rationale": "Minor line drift" },
                { "id": "R12-014", "severity": "P3", "status": "DEFERRED", "owner": "docs", "rationale": "Minor line drift" },
                { "id": "R12-015", "severity": "P3", "status": "DEFERRED", "owner": "docs", "rationale": "Minor namespace drift" }
              ]
            }
            """.trimIndent(),
        )
        val result = runner(dir, "verifyAuditClosure").buildAndFail()
        assertTrue(
            result.output.contains("R12-004") &&
                (result.output.contains("owner") || result.output.contains("rationale")),
            "M10: Undocumented deferral must fail audit closure: ${result.output.take(800)}",
        )
    }
}
