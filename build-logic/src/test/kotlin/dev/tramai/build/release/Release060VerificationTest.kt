package dev.tramai.build.release

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val FIXTURE_AUDIT_FINDINGS =
    """
    {
      "schemaVersion": "1.0",
      "audit": {
        "status": "AUDIT_COMPLETE",
        "disposition": "READY_FOR_REMEDIATION",
        "targetCommit": "2a44a1f3513ebeb4a62446ce6ce96616c9e480e4"
      },
      "verdicts": {
        "q4_security_boundaries": "PARTIAL",
        "q5_safe_failures": "PARTIAL"
      },
      "findings": [
        { "id": "R12-001", "severity": "P0", "releaseBlocking": true, "owner": "security" },
        { "id": "R12-002", "severity": "P1", "releaseBlocking": true, "owner": "persistence" },
        { "id": "R12-003", "severity": "P1", "releaseBlocking": true, "owner": "build-logic" },
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
    """.trimIndent()

private val FIXTURE_REMEDIATION_CLOSURE =
    """
    {
      "schemaVersion": "1.0",
      "closure": {
        "status": "CLOSED",
        "disposition": "REMEDIATION_CLOSED",
        "auditRef": "12.3a-independent-review-findings.json",
        "baseCommit": "2a44a1f3513ebeb4a62446ce6ce96616c9e480e4"
      },
      "closedFindings": [
        { "id": "R12-001", "severity": "P0", "status": "CLOSED", "closurePr": 397, "closureCommit": "35845aca", "closureNotes": "closed" },
        { "id": "R12-002", "severity": "P1", "status": "CLOSED", "closurePr": 397, "closureCommit": "35845aca", "closureNotes": "closed" },
        { "id": "R12-003", "severity": "P1", "status": "CLOSED", "closurePr": 398, "closureCommit": "31938b07", "closureNotes": "closed" }
      ],
      "deferredFindings": [
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
    """.trimIndent()

/**
 * TestKit positive and adversarial discriminator suite for the authoritative
 * TramAI 0.6.0 release verification command: ./gradlew verify060MaintainabilityRelease (Epic 12.4a).
 */
@Suppress("LargeClass") // One TestKit matrix keeps graph, execution, and failure discriminators together.
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
            include("examples:spring-sovereign-starter")
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
        seedFixtureSpringModule(dir)
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

    private fun seedFixtureSpringModule(dir: File) {
        writeFile(
            dir,
            "examples/spring-sovereign-starter/build.gradle.kts",
            "",
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
            12.4A_RELEASE_COMMAND_READY
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
        writeFile(dir, "docs/evidence/12.3a-independent-review-findings.json", FIXTURE_AUDIT_FINDINGS)
        writeFile(dir, "docs/evidence/12.3b-remediation-closure.json", FIXTURE_REMEDIATION_CLOSURE)
    }

    private fun seedFixtureBuildScript(
        dir: File,
        extraBuild: String,
        includeApiCheck: Boolean,
        includeArchitecture: Boolean,
    ) {
        val stubsLiteral =
            fixtureStubNames(includeApiCheck, includeArchitecture)
                .joinToString(", ") { "\"$it\"" }

        writeFile(dir, "build.gradle.kts", fixtureBuildScript(stubsLiteral, extraBuild))
    }

    private fun fixtureStubNames(
        includeApiCheck: Boolean,
        includeArchitecture: Boolean,
    ): List<String> =
        buildList {
            addAll(
                listOf(
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
                    "verifyReleaseMutation",
                    "verifyJUnitTestSignatures",
                    "verifyChangePolicy",
                    "verifyVersionAlignment",
                    "verifySovereignRuntimeReleaseCandidate",
                    "verifySovereignRuntimeVerificationRepoClosure",
                    "verifySovereignRuntimeConsumerSmoke",
                    "verifySovereignDocumentIntelligenceEvidenceRun",
                    "verifySovereignRuntimeApiBoundary",
                    "verifySovereignRuntimeClosureDocs",
                    "verifySovereignEvidencePackContainsReleaseBundle",
                    "prepareSovereignReleaseArtifacts",
                    "verifySovereignReleaseManifest",
                    "verifyReleaseDocumentationIntegrity",
                    "verifyReleaseRequiredFiles",
                    "verifyAuditClosure",
                    "verify060ZeroEgress",
                ),
            )
            if (includeApiCheck) add("apiCheck")
            if (includeArchitecture) add("verify060Architecture")
        }

    private fun fixtureBuildScript(
        stubsLiteral: String,
        extraBuild: String,
    ): String =
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

        project(":examples:spring-sovereign-starter").tasks.register("e2eTest") {
            doLast { println("Executed stub: e2eTest") }
        }

        layout.buildDirectory.dir("fake-m2/repository/dev/tramai").get().asFile.mkdirs()

        // Isolate mavenLocal
        tasks.named<dev.tramai.build.sovereign.VerifySovereignSignedBundleTask>(
            "verifySovereignRuntimeSignedBundle",
        ) {
            mavenLocalRepositoryDirectory.set(
                layout.buildDirectory.dir("fake-m2/repository/dev/tramai"),
            )
        }

        tasks.named<dev.tramai.build.release.VerifyPublishedArtifactsTask>(
            "verifyPublishedLocalArtifacts",
        ) {
            repositoryDirectory.set(
                layout.buildDirectory.dir("fake-m2/repository/dev/tramai"),
            )
        }

        $extraBuild
        """.trimIndent()

    private fun runner(
        dir: File,
        vararg args: String,
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withGradleVersion("9.0.0")
            .withArguments(*args, "--stacktrace")
            .withEnvironment(
                System.getenv().filterKeys {
                    !it.startsWith("TRAMAI_PUBLISH_") &&
                        !it.startsWith("TRAMAI_SIGNING_") &&
                        !it.startsWith("ORG_GRADLE_PROJECT_")
                },
            ).withPluginClasspath()

    private fun mutateClosure(
        dir: File,
        from: String,
        to: String,
    ) {
        val file = File(dir, "docs/evidence/12.3b-remediation-closure.json")
        val original = file.readText()
        check(from in original) { "closure fixture mutation target not found: $from" }
        file.writeText(original.replace(from, to))
    }

    private fun mutateAudit(
        dir: File,
        from: String,
        to: String,
    ) {
        val file = File(dir, "docs/evidence/12.3a-independent-review-findings.json")
        val original = file.readText()
        check(from in original) { "audit fixture mutation target not found: $from" }
        file.writeText(original.replace(from, to))
    }

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
                ":verifyReleaseMutation",
                ":verifyJUnitTestSignatures",
                ":verifyChangePolicy",
                ":verifyPublicationMetadata",
                ":verifyPublishedLocalArtifacts",
                ":verifyVersionAlignment",
                ":verifySovereignRuntimeReleaseCandidate",
                ":verifySovereignRuntimeVerificationRepoClosure",
                ":verifySovereignRuntimeConsumerSmoke",
                ":verifySovereignDocumentIntelligenceEvidenceRun",
                ":examples:spring-sovereign-starter:e2eTest",
                ":verifySovereignRuntimeApiBoundary",
                ":verifySovereignRuntimeClosureDocs",
                ":verifySovereignOpsObservabilityDocs",
                ":prepareSovereignReleaseArtifacts",
                ":verifySovereignReleaseManifest",
                ":verifyReleaseDocumentationIntegrity",
                ":verifyReleaseRequiredFiles",
                ":verifyAuditClosure",
                ":verify060ZeroEgress",
                ":verify060MaintainabilityRelease",
            )

        for (auth in requiredAuthorities) {
            assertTrue(
                output.contains(auth),
                "verify060MaintainabilityRelease must depend on $auth; missing from output:\n$output",
            )
        }
    }

    @Test
    fun `V-ZERO verify060ZeroEgress executes the physical harness and validates its evidence`() {
        val dir =
            baseFixture(
                extraBuild =
                    """
                    layout.buildDirectory.dir("sovereign-release/artifacts").get().asFile.mkdirs()
                    layout.buildDirectory.file("sovereign-release/release-artifacts-v1.json").get().asFile.apply {
                        parentFile.mkdirs()
                        writeText("{}")
                    }
                    tasks.named("prepareSovereignReleaseArtifacts") {
                        actions.clear()
                        setDependsOn(emptyList<String>())
                    }
                    tasks.named("verifySovereignReleaseManifest") {
                        actions.clear()
                        setDependsOn(emptyList<String>())
                    }
                    file("scripts/verify-zero-egress.sh").apply {
                        parentFile.mkdirs()
                        writeText(
                            "#!/usr/bin/env bash\n" +
                                "set -eu\n" +
                                "mkdir -p build/zero-egress-report\n" +
                                "printf '%s' '{\"schemaVersion\":1,\"deploymentMode\":\"OFFLINE\",\"runtimeBuildSucceeded\":true,\"loopbackProviderInvocationSucceeded\":true,\"loopbackProviderInvocationCount\":1,\"externalTcpProbeBlocked\":true,\"externalDnsProbeBlocked\":true,\"artifactVerificationReceiptCount\":1,\"auditChainValid\":true}' > build/zero-egress-report/zero-egress-report.json\n" +
                                "printf '%s' '{\"releaseBundle\":{\"artifacts\":[{}]}}' > build/zero-egress-report/sovereign-evidence-pack-v1.json\n",
                        )
                    }
                    """.trimIndent(),
            )
        val result = runner(dir, "verify060ZeroEgress", "verifySovereignEvidencePackContainsReleaseBundle").build()
        assertTrue(
            result.output.contains("Evidence pack contains releaseBundle"),
            "Zero-egress evidence must be validated",
        )
    }

    @Test
    fun `M-ZERO failing physical harness fails release verification`() {
        val dir =
            baseFixture(
                extraBuild =
                    """
                    layout.buildDirectory.dir("sovereign-release/artifacts").get().asFile.mkdirs()
                    layout.buildDirectory.file("sovereign-release/release-artifacts-v1.json").get().asFile.apply {
                        parentFile.mkdirs()
                        writeText("{}")
                    }
                    tasks.named("prepareSovereignReleaseArtifacts") { actions.clear(); setDependsOn(emptyList<String>()) }
                    tasks.named("verifySovereignReleaseManifest") { actions.clear(); setDependsOn(emptyList<String>()) }
                    file("scripts/verify-zero-egress.sh").apply {
                        parentFile.mkdirs()
                        writeText("#!/usr/bin/env bash\nexit 7\n")
                    }
                    """.trimIndent(),
            )
        val result = runner(dir, "verify060ZeroEgress").buildAndFail()
        assertTrue(result.output.contains("exited with code 7"), "Harness failures must propagate")
    }

    // ── M1: Remove apiCheck dependency ────────────────────────────────────────

    @Test
    fun `V-AUDIT complete closure register passes with exact cross references`() {
        val result = runner(baseFixture(), "verifyAuditClosure").build()
        assertTrue(result.output.contains("verified all P0/P1 audit findings CLOSED"))
    }

    @Test
    fun `M-AUDIT historical audit disposition mutation fails closed`() {
        val dir = baseFixture()
        mutateAudit(dir, "\"status\": \"AUDIT_COMPLETE\"", "\"status\": \"CLOSED\"")
        val result = runner(dir, "verifyAuditClosure").buildAndFail()
        assertTrue(result.output.contains("12.3a audit.status must remain 'AUDIT_COMPLETE'"))
    }

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
        // Write a 12.3b that omits R12-001 from closedFindings — the P0 remains unresolved
        writeFile(
            dir,
            "docs/evidence/12.3b-remediation-closure.json",
            """
            {
              "schemaVersion": "1.0",
              "closure": {
                "status": "CLOSED",
                "disposition": "REMEDIATION_CLOSED"
              },
              "closedFindings": [
                { "id": "R12-002", "severity": "P1", "status": "CLOSED", "closurePr": 397 },
                { "id": "R12-003", "severity": "P1", "status": "CLOSED", "closurePr": 398 }
              ]
            }
            """.trimIndent(),
        )
        val result = runner(dir, "verifyAuditClosure").buildAndFail()
        val mentionsFinding = result.output.contains("R12-001")
        val mentionsMissingEntry =
            result.output.contains("closedFindings") ||
                result.output.contains("CLOSED") ||
                result.output.contains("must appear")
        assertTrue(
            mentionsFinding && mentionsMissingEntry,
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
              "audit": {
                "status": "AUDIT_COMPLETE",
                "disposition": "READY_FOR_REMEDIATION"
              },
              "findings": [
                { "id": "R12-001", "severity": "P0", "releaseBlocking": true, "owner": "security" },
                { "id": "R12-002", "severity": "P1", "releaseBlocking": true, "owner": "persistence" },
                { "id": "R12-003", "severity": "P1", "releaseBlocking": true, "owner": "build-logic" },
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

    @Test
    fun `M-AUDIT-duplicate duplicate closure finding ID fails closed`() {
        val dir = baseFixture()
        mutateClosure(dir, "\"id\": \"R12-004\"", "\"id\": \"R12-001\"")
        val result = runner(dir, "verifyAuditClosure").buildAndFail()
        assertTrue(result.output.contains("duplicate finding ID"), "Duplicate audit IDs must fail closed")
    }

    @Test
    fun `M-AUDIT-missing missing closure finding fails closed`() {
        val dir = baseFixture()
        mutateClosure(dir, "\"id\": \"R12-015\"", "\"id\": \"R12-014\"")
        val result = runner(dir, "verifyAuditClosure").buildAndFail()
        assertTrue(result.output.contains("missing expected finding IDs"), "Missing audit IDs must fail closed")
    }

    @Test
    fun `M-AUDIT-unexpected unexpected closure finding fails closed`() {
        val dir = baseFixture()
        mutateClosure(dir, "\"id\": \"R12-015\"", "\"id\": \"R12-999\"")
        val result = runner(dir, "verifyAuditClosure").buildAndFail()
        assertTrue(result.output.contains("unexpected finding IDs"), "Unexpected audit IDs must fail closed")
    }

    @Test
    fun `M-AUDIT-severity closure severity must match original register`() {
        val dir = baseFixture()
        mutateClosure(dir, "\"id\": \"R12-004\", \"severity\": \"P2\"", "\"id\": \"R12-004\", \"severity\": \"P3\"")
        val result = runner(dir, "verifyAuditClosure").buildAndFail()
        assertTrue(result.output.contains("severity for R12-004"), "Severity mutation must fail closed")
    }

    @Test
    fun `M-AUDIT-open P2 finding cannot be treated as a deferral`() {
        val dir = baseFixture()
        mutateClosure(
            dir,
            "\"id\": \"R12-004\", \"severity\": \"P2\", \"status\": \"DEFERRED\"",
            "\"id\": \"R12-004\", \"severity\": \"P2\", \"status\": \"OPEN\"",
        )
        val result = runner(dir, "verifyAuditClosure").buildAndFail()
        assertTrue(result.output.contains("R12-004 must have status DEFERRED"), "OPEN P2 must fail closed")
    }

    @Test
    fun `M-AUDIT-owner missing deferred owner fails closed`() {
        val dir = baseFixture()
        mutateClosure(
            dir,
            "\"id\": \"R12-004\", \"severity\": \"P2\", \"status\": \"DEFERRED\", \"owner\": \"engine\"",
            "\"id\": \"R12-004\", \"severity\": \"P2\", \"status\": \"DEFERRED\", \"owner\": \"\"",
        )
        val result = runner(dir, "verifyAuditClosure").buildAndFail()
        assertTrue(result.output.contains("assigned owner"), "Missing deferred owner must fail closed")
    }

    @Test
    fun `M-AUDIT-rationale missing deferred rationale fails closed`() {
        val dir = baseFixture()
        val original =
            "\"id\": \"R12-004\", \"severity\": \"P2\", \"status\": \"DEFERRED\", " +
                "\"owner\": \"engine\", \"rationale\": \"Backwards compatibility\""
        val replacement =
            "\"id\": \"R12-004\", \"severity\": \"P2\", \"status\": \"DEFERRED\", " +
                "\"owner\": \"engine\", \"rationale\": \"\""
        mutateClosure(
            dir,
            original,
            replacement,
        )
        val result = runner(dir, "verifyAuditClosure").buildAndFail()
        assertTrue(result.output.contains("must have a rationale"), "Missing deferred rationale must fail closed")
    }

    // ── M-DOC-1: Valid examples link passes ───────────────────────────────────

    @Test
    fun `M-DOC-1 valid examples link passes documentation integrity check`() {
        val dir = baseFixture()
        writeFile(dir, "examples/README.md", "# Examples")
        writeFile(
            dir,
            "docs/with-examples-link.md",
            "# Doc\nSee [examples](../examples/README.md) for reference.",
        )
        val result = runner(dir, "verifyReleaseDocumentationIntegrity").build()
        assertTrue(
            result.output.contains("successfully verified"),
            "M-DOC-1: Valid examples link must pass: ${result.output.take(800)}",
        )
    }

    // ── M-DOC-2: Broken examples link fails ───────────────────────────────────

    @Test
    fun `M-DOC-2 broken examples link fails documentation integrity check`() {
        val dir = baseFixture()
        writeFile(
            dir,
            "docs/broken-examples-link.md",
            "# Doc\nSee [examples](../examples/does-not-exist.md) for reference.",
        )
        val result = runner(dir, "verifyReleaseDocumentationIntegrity").buildAndFail()
        assertTrue(
            result.output.contains("broken relative link") &&
                result.output.contains("does-not-exist.md"),
            "M-DOC-2: Broken examples link must fail: ${result.output.take(800)}",
        )
    }

    @Test
    fun `M-DOC-3 external documentation links are intentionally skipped`() {
        val dir = baseFixture()
        writeFile(
            dir,
            "docs/external-links.md",
            "# External links\nSee [project](https://example.com/project) and [license](http://example.com/license).",
        )
        val result = runner(dir, "verifyReleaseDocumentationIntegrity").build()
        assertTrue(
            result.output.contains("successfully verified"),
            "M-DOC-3: External links must not require network access: ${result.output.take(800)}",
        )
    }

    @Test
    fun `M-DOC-4 hardcoded developer local path fails documentation integrity check`() {
        val dir = baseFixture()
        writeFile(
            dir,
            "docs/local-path.md",
            "# Local path\nSee `/home/alice/tramai/build/report.json` for the generated report.",
        )
        val result = runner(dir, "verifyReleaseDocumentationIntegrity").buildAndFail()
        assertTrue(
            result.output.contains("Hardcoded local user path"),
            "M-DOC-4: Hardcoded developer paths must fail: ${result.output.take(800)}",
        )
    }

    private fun controlledAuthorityBuild(removeAuthority: String? = null): String {
        val removal =
            removeAuthority
                ?.let {
                    """
                    tasks.named("verify060MaintainabilityRelease") {
                        setDependsOn(dependsOn.filterNot { dependency -> dependency.toString().contains("$it") })
                    }
                    """.trimIndent()
                }.orEmpty()
        return """
            val markerDir = layout.buildDirectory.dir("release-authority-markers")
            layout.buildDirectory.dir("sovereign-release/artifacts").get().asFile.mkdirs()
            layout.buildDirectory.file("sovereign-release/release-artifacts-v1.json").get().asFile.apply {
                parentFile.mkdirs()
                writeText("{}")
            }
            layout.buildDirectory.dir("sovereign-runtime-release-verification-repo").get().asFile.mkdirs()
            val authorities = listOf(
                "check", "spotlessCheck", "verifyStaticAnalysis", "verifyStaticSafetyGuards",
                "verifyCompilerWarnings", "verifyDependencyHygiene", "verifyCancellationSafety",
                "verify060Architecture", "apiCheck", "verifyMaintainabilityBaseline",
                "verifyModuleManifest", "verifyModuleMatrixDrift", "verifyCriticalCoverage",
                "verifyReleaseMutation", "verifyJUnitTestSignatures", "verifyChangePolicy",
                "verifyPublicationMetadata", "verifyPublishedLocalArtifacts", "verifyVersionAlignment",
                "verifySovereignRuntimeReleaseCandidate", "verifySovereignRuntimeVerificationRepoClosure",
                "verifySovereignRuntimeConsumerSmoke", "verifySovereignDocumentIntelligenceEvidenceRun",
                "verifySovereignRuntimeApiBoundary", "verifySovereignRuntimeClosureDocs",
                "verifySovereignOpsObservabilityDocs", "verifySovereignEvidencePackContainsReleaseBundle",
                "prepareSovereignReleaseArtifacts",
                "verifySovereignReleaseManifest", "verifyReleaseDocumentationIntegrity",
                "verifyReleaseRequiredFiles", "verifyAuditClosure", "verify060ZeroEgress"
            )
            authorities.forEach { authority ->
                tasks.named(authority) {
                    setDependsOn(emptyList<String>())
                    actions.clear()
                    doLast {
                        val marker = markerDir.get().asFile.resolve("${"$"}{authority}.marker")
                        marker.parentFile.mkdirs()
                        marker.writeText("executed")
                    }
                }
            }
            project(":examples:spring-sovereign-starter").tasks.named("e2eTest") {
                setDependsOn(emptyList<String>())
                actions.clear()
                doLast {
                    val marker = markerDir.get().asFile.resolve("spring-e2eTest.marker")
                    marker.parentFile.mkdirs()
                    marker.writeText("executed")
                }
            }
            $removal
            """.trimIndent()
    }

    // ── V2: Actual execution proves authority markers ran ─────────────────────

    @Test
    fun `V2 verify060MaintainabilityRelease actual execution proves all required authority markers ran`() {
        val dir =
            baseFixture(
                extraBuild = controlledAuthorityBuild(),
            )
        val result = runner(dir, "verify060MaintainabilityRelease").build()
        val markerDir = File(dir, "build/release-authority-markers")
        val expectedMarkers =
            listOf(
                "check",
                "spotlessCheck",
                "verifyStaticAnalysis",
                "verifyStaticSafetyGuards",
                "verifyCompilerWarnings",
                "verifyDependencyHygiene",
                "verifyCancellationSafety",
                "verify060Architecture",
                "apiCheck",
                "verifyMaintainabilityBaseline",
                "verifyModuleManifest",
                "verifyModuleMatrixDrift",
                "verifyCriticalCoverage",
                "verifyReleaseMutation",
                "verifyJUnitTestSignatures",
                "verifyChangePolicy",
                "verifyPublicationMetadata",
                "verifyPublishedLocalArtifacts",
                "verifyVersionAlignment",
                "verifySovereignRuntimeReleaseCandidate",
                "verifySovereignRuntimeVerificationRepoClosure",
                "verifySovereignRuntimeConsumerSmoke",
                "verifySovereignDocumentIntelligenceEvidenceRun",
                "spring-e2eTest",
                "verifySovereignRuntimeApiBoundary",
                "verifySovereignRuntimeClosureDocs",
                "verifySovereignOpsObservabilityDocs",
                "verifySovereignEvidencePackContainsReleaseBundle",
                "prepareSovereignReleaseArtifacts",
                "verifySovereignReleaseManifest",
                "verifyReleaseDocumentationIntegrity",
                "verifyReleaseRequiredFiles",
                "verifyAuditClosure",
                "verify060ZeroEgress",
            )
        for (marker in expectedMarkers) {
            assertTrue(
                File(markerDir, "$marker.marker").exists(),
                "V2: Authority '$marker' did not execute (marker file missing). Output:\n${result.output.take(800)}",
            )
        }
    }

    // ── V3: Failing authority fails the aggregate ─────────────────────────────

    @Test
    fun `V3 verify060MaintainabilityRelease aggregate fails when required authority fails`() {
        val dir =
            baseFixture(
                extraBuild =
                    """
                    tasks.matching { it.name == "verifyReleaseMutation" }.configureEach {
                        doLast { throw RuntimeException("Simulated mutation failure") }
                    }
                    """.trimIndent(),
            )
        val result = runner(dir, "verify060MaintainabilityRelease").buildAndFail()
        assertTrue(
            result.output.contains("Simulated mutation failure") || result.output.contains("FAILED"),
            "V3: A failing authority must fail the aggregate release command",
        )
    }

    @Test
    fun `M-AGG removing zero-egress authority edge fails closed`() {
        val dir = baseFixture(extraBuild = controlledAuthorityBuild(removeAuthority = "verify060ZeroEgress"))
        val result = runner(dir, "verify060MaintainabilityRelease").buildAndFail()
        assertTrue(
            result.output.contains("verify060ZeroEgress") && result.output.contains("were not executed"),
            "Removing a required aggregate edge must fail closed: ${result.output.take(800)}",
        )
    }

    @Test
    fun `M-AGG removing release mutation authority edge fails closed`() {
        val dir = baseFixture(extraBuild = controlledAuthorityBuild(removeAuthority = "verifyReleaseMutation"))
        val result = runner(dir, "verify060MaintainabilityRelease").buildAndFail()
        assertTrue(
            result.output.contains("verifyReleaseMutation") && result.output.contains("were not executed"),
            "Removing the release mutation edge must fail closed: ${result.output.take(800)}",
        )
    }
}
