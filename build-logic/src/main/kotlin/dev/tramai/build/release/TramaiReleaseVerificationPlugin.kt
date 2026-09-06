package dev.tramai.build.release

import dev.tramai.build.publishing.TramaiPublishingRepositories
import dev.tramai.build.quality.ModuleCatalog
import dev.tramai.build.quality.ModuleManifest
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Registers the generic release verification tasks as typed DefaultTasks
 * (Epic 9.2b). Applied to the root project. Task names and observable
 * semantics are identical to the historical root build script.
 */
class TramaiReleaseVerificationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        registerVerifyPublicationMetadata(project)
        registerVerifyPublishedLocalArtifacts(project)
        registerVerifyReleasePublishInputs(project)
        registerVerifySignedPublicationBundle(project)
        registerVerifyReleaseReadiness(project)
        registerVerify050ReleaseReadiness(project)
        registerRootApiCheckAggregator(project)
        registerVerifyReleaseDocumentation(project)
        registerVerifyReleaseRequiredFiles(project)
        registerVerifyAuditClosure(project)
        registerVerify060MaintainabilityRelease(project)
    }

    private fun registerRootApiCheckAggregator(project: Project) {
        project.plugins.withId("org.jetbrains.kotlinx.binary-compatibility-validator") {
            if (project == project.rootProject && project.tasks.findByName("apiCheck") == null) {
                project.tasks.register("apiCheck") {
                    group = "verification"
                    description = "Aggregates apiCheck across all subprojects with binary compatibility validation."
                    project.subprojects.forEach { sub ->
                        sub.tasks.matching { it.name == "apiCheck" }.all {
                            this@register.dependsOn(this)
                        }
                    }
                }
            }
        }
    }

    /**
     * Publishable module set, resolved lazily at task realization from the
     * module catalog — the single canonical publishability authority (9.2d-b1).
     * A missing or corrupt catalog throws (fail closed): the authority must
     * never degrade to an empty module set.
     */
    private fun publishableModuleNames(project: Project): List<String> =
        ModuleManifest
            .publishableModulePaths(project.rootDir)
            .map { it.removePrefix(":") }
            .sorted()

    private fun jarPublicationModuleNames(project: Project): List<String> = publishableModuleNames(project) - "tramai-bom"

    /**
     * Publication descriptions, resolved from the module catalog (9.2c-c).
     * The publisher reads the catalog in its own plugin code path; the
     * verifier receives this map as a typed @Input (never calling publisher
     * code), so a defect in the publisher's lookup cannot change what the
     * verifier expects. Byte-parity with the legacy policy is pinned by the
     * D5 oracle test.
     *
     * Tolerant by design: entries missing from the catalog are simply not in
     * the map, and the verifier's requireNotNull fails closed at verification
     * time. A missing/broken catalog yields an error result with an empty
     * modules map; the verifier then fails closed for every publishable
     * module. The catalog parser itself also reports
     * MODULE_CATALOG_MISSING_DESCRIPTION and ModuleManifest.catalog() throws
     * on any catalog error, so the real repo fails at configuration already;
     * TestKit fixtures without a catalog still configure cleanly.
     */
    private fun catalogDescriptions(project: Project): Map<String, String> {
        val catalog = ModuleCatalog.fromRootDir(project.rootDir).parse()
        return publishableModuleNames(project)
            .mapNotNull { moduleName ->
                val description = catalog.modules[":$moduleName"]?.description?.takeIf { it.isNotBlank() }
                description?.let { moduleName to it }
            }.toMap()
    }

    private fun registerVerifyPublicationMetadata(project: Project) {
        project.tasks.register<VerifyPublicationMetadataTask>("verifyPublicationMetadata") {
            group = "verification"
            description = "Verifies generated Maven POM metadata for every publishable Tramai module."

            val publishableModuleNames = publishableModuleNames(project)
            val jarPublicationModuleNames = jarPublicationModuleNames(project)

            expectedGroup.set(project.providers.gradleProperty("tramaiGroup").orElse("dev.tramai"))
            expectedVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
            expectedProjectUrl.set(project.providers.gradleProperty("tramaiProjectUrl").orElse("https://github.com/GionaGranchelli/tramAI"))
            expectedScmUrl.set(project.providers.gradleProperty("tramaiScmUrl").orElse("https://github.com/GionaGranchelli/tramAI.git"))
            expectedScmConnection.set(
                project.providers.gradleProperty("tramaiScmConnection").orElse("scm:git:https://github.com/GionaGranchelli/tramAI.git"),
            )
            expectedScmDeveloperConnection.set(
                project.providers
                    .gradleProperty(
                        "tramaiScmDeveloperConnection",
                    ).orElse("scm:git:ssh://git@github.com/GionaGranchelli/tramAI.git"),
            )
            expectedLicenseName.set(project.providers.gradleProperty("tramaiLicenseName").orElse("Apache-2.0"))
            expectedLicenseUrl.set(
                project.providers.gradleProperty("tramaiLicenseUrl").orElse("https://www.apache.org/licenses/LICENSE-2.0.txt"),
            )
            expectedDeveloperId.set(project.providers.gradleProperty("tramaiDeveloperId").orElse("GionaGranchelli"))
            expectedDeveloperName.set(project.providers.gradleProperty("tramaiDeveloperName").orElse("Giona"))
            expectedDeveloperEmail.set(project.providers.gradleProperty("tramaiDeveloperEmail").orElse("opensource@giona.dev"))

            this.expectedDescriptions.set(catalogDescriptions(project))
            this.publishableModules.set(publishableModuleNames)
            this.jarPublicationModules.set(jarPublicationModuleNames)
            pomFiles.from(
                publishableModuleNames.map { moduleName ->
                    project.layout.projectDirectory.file("$moduleName/build/publications/maven/pom-default.xml")
                },
            )
            dependsOn(publishableModuleNames.map { ":$it:generatePomFileForMavenPublication" })
        }
    }

    private fun registerVerifyPublishedLocalArtifacts(project: Project) {
        project.tasks.register<VerifyPublishedArtifactsTask>("verifyPublishedLocalArtifacts") {
            group = "verification"
            description = "Publishes to Maven Local and verifies POM/module/jar/sources/javadoc artifacts for every Tramai module."

            val publishableModuleNames = publishableModuleNames(project)

            expectedVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
            expectedGroup.set(project.providers.gradleProperty("tramaiGroup").orElse("dev.tramai"))
            this.publishableModules.set(publishableModuleNames)

            // Resolved through a Provider so the task never touches System.getProperty
            repositoryDirectory.fileProvider(
                project.providers
                    .systemProperty("user.home")
                    .map { home -> File(home, ".m2/repository/${expectedGroup.get().replace('.', '/')}") },
            )
            dependsOn(publishableModuleNames.map { ":$it:publishToMavenLocal" })
        }
    }

    private fun registerVerifyReleasePublishInputs(project: Project) {
        project.tasks.register<VerifyReleasePublishInputsTask>("verifyReleasePublishInputs") {
            group = "verification"
            description = "Verifies that the properties required for a real remote release publish are present."

            releaseUrlPresent.set(
                project.providers
                    .gradleProperty("tramaiPublishReleaseUrl")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )
            usernamePresent.set(
                project.providers
                    .gradleProperty("tramaiPublishUsername")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )
            passwordPresent.set(
                project.providers
                    .gradleProperty("tramaiPublishPassword")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )
            signingKeyPresent.set(
                project.providers
                    .gradleProperty("signingKey")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )
            signingPasswordPresent.set(
                project.providers
                    .gradleProperty("signingPassword")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )
            tramaiVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
        }
    }

    private fun registerVerifySignedPublicationBundle(project: Project) {
        project.tasks.register<VerifySignedPublicationBundleTask>("verifySignedPublicationBundle") {
            group = "verification"
            description = "Publishes to a configured file-based Maven repository and verifies generated signature files."

            val publishableModuleNames = publishableModuleNames(project)

            expectedVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
            expectedGroup.set(project.providers.gradleProperty("tramaiGroup").orElse("dev.tramai"))
            this.publishableModules.set(publishableModuleNames)
            signingKeyPresent.set(
                project.providers
                    .gradleProperty("signingKey")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )
            signingPasswordPresent.set(
                project.providers
                    .gradleProperty("signingPassword")
                    .map { it.isNotBlank() }
                    .orElse(false),
            )

            val version = project.providers.gradleProperty("tramaiVersion").orElse("0.5.0")
            val releaseUrl = project.providers.gradleProperty("tramaiPublishReleaseUrl")
            val snapshotUrl = project.providers.gradleProperty("tramaiPublishSnapshotUrl")
            repositoryUrl.set(
                project.provider {
                    TramaiPublishingRepositories.selectRepositoryUrl(
                        version.get(),
                        releaseUrl.orNull,
                        snapshotUrl.orNull,
                    )
                },
            )
            repositoryDirectory.fileProvider(
                repositoryUrl.map { url ->
                    if (url.isNullOrBlank()) {
                        File(
                            project.layout.projectDirectory
                                .dir("build/verify-signed-publication-repo")
                                .asFile.absolutePath,
                        )
                    } else {
                        File(java.net.URI(url))
                    }
                },
            )
            dependsOn(publishableModuleNames.map { ":$it:publish" })
        }
    }

    private fun registerVerifyReleaseReadiness(project: Project) {
        project.tasks.register("verifyReleaseReadiness") {
            group = "verification"
            description = "Runs the repo-local release verification checks for publication metadata and published artifacts."

            val jarPublicationModuleNames = jarPublicationModuleNames(project)
            dependsOn(
                jarPublicationModuleNames.map { ":$it:test" },
                "verifyPublicationMetadata",
                "verifyPublishedLocalArtifacts",
                "verifySovereignOpsObservabilityDocs",
            )
        }
    }

    /**
     * Aggregates all 0.5.0 release-readiness verification tasks (Epic 9.2d-b2).
     * The document/file inspection implementation moved verbatim from the root
     * build script into this plugin, so the root build remains composition-only.
     * Deliberately NOT configuration-cache compatible: it aggregates
     * execution-time verification tasks (C3 = 1 deliberate, per 9.2d scope).
     */
    private fun registerVerify050ReleaseReadiness(project: Project) {
        project.tasks.register("verify050ReleaseReadiness") {
            group = "verification"
            description = "Aggregates all 0.5.0 release-readiness verification tasks."
            notCompatibleWithConfigurationCache("Release readiness aggregates execution-time verification tasks.")

            dependsOn(
                "verifyVersionAlignment",
                "verifyReleaseReadiness",
                "verifyWorkflowApiStabilityBoundary",
                "verifySovereignRuntimeApiBoundary",
                "verifyToolGovernanceExample",
            )

            doLast {
                verify050ReadinessDocs(project.rootProject)
            }
        }
    }

    /**
     * The 0.5.0 release-readiness document/file inspection (moved verbatim
     * from the root build script; extracted into its own function so the
     * registration stays short). Fails closed with the exact historical
     * diagnostics.
     */
    private fun verify050ReadinessDocs(rootProject: Project) {
        val rootDir = rootProject.layout.projectDirectory.asFile
        val expectedVersion = "0.5.0"
        val expectedReleaseDate =
            rootProject.findProperty("tramaiReleaseDate") as? String
                ?: error("tramaiReleaseDate must be set in gradle.properties")

        // 0.5.0 release-readiness document exists
        val releaseReadinessDoc = rootDir.resolve("docs/releases/$expectedVersion-release-readiness.md")
        require(releaseReadinessDoc.isFile) {
            "Missing $expectedVersion release-readiness document at ${releaseReadinessDoc.path}"
        }

        // CHANGELOG has 0.5.0 section
        val changelog = rootDir.resolve("CHANGELOG.md")
        val changelogText = changelog.readText()
        require(changelogText.contains("## $expectedVersion - $expectedReleaseDate")) {
            "CHANGELOG.md must contain ## $expectedVersion - $expectedReleaseDate section"
        }

        // STATUS and roadmap state are correct
        val statusDoc = rootDir.resolve("docs/STATUS.md")
        val statusText = statusDoc.readText()
        require(statusText.contains("0.5.0 release candidate prepared")) {
            "STATUS.md must mention 0.5.0 release candidate prepared"
        }

        val roadmap = rootDir.resolve("docs/POST-SOVEREIGNTY-ROADMAP.md")
        val roadmapText = roadmap.readText()
        require(roadmapText.contains("Release prepared — publication pending")) {
            "Roadmap must indicate release prepared — publication pending"
        }

        // Publish workflow has tag/version matching
        val publishWorkflow = rootDir.resolve(".github/workflows/publish.yml")
        val publishText = publishWorkflow.readText()
        require(publishText.contains("Verify version alignment") || publishText.contains("version alignment")) {
            "Publish workflow must contain version alignment check"
        }

        // No absolute /home/... links in release docs (allow placeholder /home/...)
        val localHomePath = Regex("""/home/(?!\.\.\.)[^/\s]+/""")
        val releaseDocs =
            listOf(
                rootDir.resolve("docs/reference/release-validation.md"),
                rootDir.resolve("docs/reference/releasing.md"),
                rootDir.resolve("docs/releases/$expectedVersion-release-readiness.md"),
                rootDir.resolve("docs/releases/sovereign-runtime-release-readiness.md"),
            )
        for (doc in releaseDocs) {
            if (!doc.isFile) continue
            val docText = doc.readText()
            require(!localHomePath.containsMatchIn(docText)) {
                "${doc.name} must not contain absolute /home/<user>/ paths — use repository-relative links"
            }
        }

        // No duplicate PR entries in the Added section
        val addedSection = changelogText.substringAfter("### Added").substringBefore("### Changed")
        val prPattern = Regex("""\(PR #(\d+)\)""")
        val prCounts =
            prPattern
                .findAll(addedSection)
                .map { it.groupValues[1] }
                .groupingBy { it }
                .eachCount()
        val duplicates = prCounts.filter { it.value > 1 }
        require(duplicates.isEmpty()) {
            "Duplicate PR entries in Added section: ${duplicates.keys.joinToString(
                ", ",
            ) { "PR #$it appears ${duplicates[it]} times" }}"
        }

        // No stale "no DB outbox" or "single-node only" claims in sovereign-runtime-release-readiness.md
        val sovereignReadiness = rootDir.resolve("docs/releases/sovereign-runtime-release-readiness.md")
        if (sovereignReadiness.isFile) {
            val sovereignText = sovereignReadiness.readText()
            require(!sovereignText.contains("Database persistence is future work")) {
                "sovereign-runtime-release-readiness.md must not claim 'Database persistence is future work'"
            }
            require(!sovereignText.contains("No DB-backed outbox")) {
                "sovereign-runtime-release-readiness.md must not claim 'No DB-backed outbox'"
            }
            require(!sovereignText.contains("worker assumes single-node operation")) {
                "sovereign-runtime-release-readiness.md must not claim 'worker assumes single-node operation'"
            }
        }

        rootProject.logger.lifecycle("verify050ReleaseReadiness: all checks passed.")
        rootProject.logger.lifecycle("  - Version alignment: verified")
        rootProject.logger.lifecycle("  - Release readiness: verified")
        rootProject.logger.lifecycle("  - Workflow API stability boundary: verified")
        rootProject.logger.lifecycle("  - Sovereign runtime API boundary: verified")
        rootProject.logger.lifecycle("  - Tool governance example: verified")
        rootProject.logger.lifecycle("  - 0.5.0 release-readiness doc: verified")
        rootProject.logger.lifecycle("  - CHANGELOG: 0.5.0 section verified")
        rootProject.logger.lifecycle("  - STATUS/roadmap: release-ready state verified")
        rootProject.logger.lifecycle("  - Publish workflow: version alignment check verified")
        rootProject.logger.lifecycle("  - Release docs: no absolute paths or stale claims")
    }

    private fun registerVerifyReleaseDocumentation(project: Project) {
        project.tasks.register<VerifyReleaseDocumentationTask>("verifyReleaseDocumentationIntegrity") {
            group = "verification"
            description = "Verifies repository documentation link integrity and path hygiene."
            rootDir.set(project.rootDir)
            val docFiles =
                project.fileTree(project.rootDir) {
                    include("**/*.md")
                    exclude(
                        "build/**",
                        "*/build/**",
                        "examples/*/build/**",
                        "build-logic/build/**",
                        "**/.gradle/**",
                        "**/node_modules/**",
                        "**/vendor/**",
                    )
                }
            documentationFiles.from(docFiles)
        }
    }

    private fun registerVerifyReleaseRequiredFiles(project: Project) {
        project.tasks.register<VerifyReleaseRequiredFilesTask>("verifyReleaseRequiredFiles") {
            group = "verification"
            description = "Verifies presence and required content markers of 0.6.0 release documents."
            rootDir.set(project.rootDir)
            expectedVersion.set("0.6.0")
        }
    }

    private fun registerVerifyAuditClosure(project: Project) {
        project.tasks.register<VerifyAuditClosureTask>("verifyAuditClosure") {
            group = "verification"
            description =
                "Verifies that all P0/P1 audit findings from Epic 12.3 are CLOSED and all P2/P3 deferrals documented."
            auditFindingsFile.set(
                project.layout.projectDirectory.file("docs/evidence/12.3a-independent-review-findings.json"),
            )
        }
    }

    private fun registerVerify060MaintainabilityRelease(project: Project) {
        project.tasks.register("verify060MaintainabilityRelease") {
            group = "verification"
            description =
                "Authoritative, fail-closed TramAI 0.6.0 release verification command composing release contracts."
            notCompatibleWithConfigurationCache(
                "0.6.0 release verification aggregates execution-time verification tasks.",
            )

            wireCoreQualityAuthorities(this)
            wireArchitectureAndMaintainabilityAuthorities(this)
            wireSovereignAndConsumerAuthorities(this, project)
            wireDocumentationAndAuditAuthorities(this, project)

            doLast {
                val publishable = publishableModuleNames(project)
                if (publishable.isEmpty()) {
                    throw GradleException("verify060MaintainabilityRelease: Publishable module set is empty.")
                }
                printReleaseSummary(publishable.size)
            }
        }
    }

    private fun wireCoreQualityAuthorities(task: org.gradle.api.Task) {
        task.dependsOn("check")
        task.dependsOn("spotlessCheck")
        task.dependsOn("verifyStaticAnalysis")
        task.dependsOn("verifyStaticSafetyGuards")
        task.dependsOn("verifyCompilerWarnings")
        task.dependsOn("verifyDependencyHygiene")
        task.dependsOn("verifyCancellationSafety")
    }

    private fun wireArchitectureAndMaintainabilityAuthorities(task: org.gradle.api.Task) {
        task.dependsOn("verify060Architecture")
        task.dependsOn("apiCheck")
        task.dependsOn("verifyMaintainabilityBaseline")
        task.dependsOn("verifyModuleManifest")
        task.dependsOn("verifyModuleMatrixDrift")
        task.dependsOn("verifyCriticalCoverage")
        task.dependsOn("verifyMutationRatchet")
        task.dependsOn("verifyJUnitTestSignatures")
        task.dependsOn("verifyChangePolicy")
        task.dependsOn("verifyPublicationMetadata")
        task.dependsOn("verifyPublishedLocalArtifacts")
        task.dependsOn("verifyVersionAlignment")
    }

    private fun wireSovereignAndConsumerAuthorities(
        task: org.gradle.api.Task,
        project: Project,
    ) {
        task.dependsOn("verifySovereignRuntimeReleaseCandidate")
        task.dependsOn("verifySovereignRuntimeVerificationRepoClosure")
        task.dependsOn("verifySovereignRuntimeConsumerSmoke")
        task.dependsOn("verifySovereignDocumentIntelligenceEvidenceRun")
        task.dependsOn("verifySovereignRuntimeApiBoundary")
        task.dependsOn("verifySovereignRuntimeClosureDocs")
        task.dependsOn("verifySovereignOpsObservabilityDocs")
        task.dependsOn("prepareSovereignReleaseArtifacts")
        task.dependsOn("verifySovereignReleaseManifest")

        if (project.findProject(":examples:spring-sovereign-starter") != null) {
            task.dependsOn(":examples:spring-sovereign-starter:e2eTest")
        }
    }

    private fun wireDocumentationAndAuditAuthorities(
        task: org.gradle.api.Task,
        project: Project,
    ) {
        task.dependsOn("verifyReleaseDocumentationIntegrity")
        task.dependsOn("verifyReleaseRequiredFiles")
        task.dependsOn("verifyAuditClosure")

        val buildLogicTestTask =
            project.gradle.includedBuilds
                .firstOrNull { it.name == "build-logic" }
                ?.task(":test")
        if (buildLogicTestTask != null) {
            task.dependsOn(buildLogicTestTask)
        }
    }

    private fun org.gradle.api.Task.printReleaseSummary(publishableCount: Int) {
        logger.lifecycle("================================================================================")
        logger.lifecycle("TramAI 0.6.0 Release Verification: ALL GATES PASSED")
        logger.lifecycle("  - Lifecycle & Tests: check, allSubprojectTestTasks")
        logger.lifecycle("  - Formatting & Analysis: spotlessCheck, verifyStaticAnalysis, verifyStaticSafetyGuards")
        logger.lifecycle("  - Compiler & Dependencies: verifyCompilerWarnings, verifyDependencyHygiene")
        logger.lifecycle("  - Cancellation & Concurrency: verifyCancellationSafety, workflow state machines")
        logger.lifecycle("  - Architecture & Contracts: verify060Architecture, ProviderTck, StoreTck")
        logger.lifecycle("  - Binary Compatibility: apiCheck across $publishableCount publishable modules")
        logger.lifecycle("  - Consumers: Kotlin & Java smoke, Spring sovereign starter E2E")
        logger.lifecycle("  - Sovereign Runtime & Evidence: sovereign bundle dry-run, zero-egress, release manifest")
        logger.lifecycle("  - Publication: Maven Local resolution, metadata, POMs, sources/javadoc JARs")
        logger.lifecycle("  - Documentation & Audit: link integrity, 0.6.0 release artifacts, 12.3 audit closed")
        logger.lifecycle("Verdict: READY_FOR_0.6.0_RELEASE")
        logger.lifecycle("================================================================================")
    }
}
