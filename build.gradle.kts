import dev.tramai.build.publishing.TramaiPublishingRepositories
import dev.tramai.build.quality.ModuleManifest

plugins {
    base
    id("tramai.maintainability-baseline")
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.cyclonedx.bom)
    alias(libs.plugins.bcv)
    id("tramai.release-verification")
    id("tramai.sovereign-verification")
    id("tramai.sovereign-lab-verification")
    id("tramai.docs-guards")
    alias(libs.plugins.spotless)
}

// ── Epic 10.1a: incremental Kotlin formatting gate ──
// Spotless with an explicitly pinned KtLint engine (libs.versions.toml). One
// formatting authority for the whole repository, including the build-logic
// included build (reached via root-relative file targets).
//
// Ratchet semantics: only Kotlin sources CHANGED relative to the formatting
// base are checked/formatted. Untouched legacy formatting debt never blocks.
//   -PtramaiFormattingBaseRef=<sha>  exact base (CI PR: pull_request.base.sha,
//                                    CI push: github.event.before)
//   property absent                  origin/master (local default)
//
// spotlessCheck joins the root `check` lifecycle automatically; verifyPr
// depends on it below.
spotless {
    val formattingBaseRef = providers.gradleProperty("tramaiFormattingBaseRef")
        .orElse("origin/master")
    ratchetFrom(formattingBaseRef.get())

    kotlin {
        target(
            fileTree(rootDir) {
                include("**/*.kt")
                // Gradle task-output dirs only — deliberately NARROW. A broad
                // "**/build/**" would also exclude paths containing a package
                // segment literally named `build` (e.g. dev/tramai/build in
                // build-logic), silently exempting real source.
                exclude(
                    "build/**", // root project output
                    "*/build/**", // module output dirs
                    "examples/*/build/**", // example module output dirs
                    "build-logic/build/**", // included-build output dir
                    "**/.gradle/**", // caches — no source lives here
                )
            }
        )
        // Explicitly pinned formatter engine — never Spotless's implicit default.
        ktlint(libs.versions.ktlint.get())
        // .editorconfig (root) is the single style authority.
    }
}

tasks.named("verifyPr") {
    dependsOn("spotlessCheck")
}

// The root project carries the Spotless formatting gate (Epic 10.1a); it needs
// a repository to resolve the pinned KtLint engine. Production modules keep
// their own repositories declared in the subprojects block below.
repositories {
    mavenCentral()
}

sonar {
    properties {
        property("sonar.projectKey", "tramai")
        property("sonar.projectName", "TramAI")
        property("sonar.organization", "gionagranchelli")
        property("sonar.host.url", "http://localhost:9000")
        property("sonar.token", providers.environmentVariable("SONAR_TOKEN").orElse(""))
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.exclusions", "**/*.xml,**/*.properties,**/*.yml,**/*.yaml")
        // Kotlin analysis requires compiled classes
        property("sonar.kotlin.binaries", "**/build/classes/kotlin/**")
        // S6518 false positive — suggests obj[key] but target types lack operator modifier
        property("sonar.issue.ignore.multicriteria", "e1")
        property("sonar.issue.ignore.multicriteria.e1.ruleKey", "kotlin:S6518")
        property("sonar.issue.ignore.multicriteria.e1.resourceKey", "**/*.kt")
    }
}

val tramaiGroup = providers.gradleProperty("tramaiGroup").orElse("dev.tramai")
val tramaiVersion = providers.gradleProperty("tramaiVersion").orElse("0.5.0")
val tramaiProjectUrl = providers.gradleProperty("tramaiProjectUrl").orElse("https://github.com/GionaGranchelli/tramAI")
val tramaiScmUrl = providers.gradleProperty("tramaiScmUrl").orElse("https://github.com/GionaGranchelli/tramAI.git")
val tramaiScmConnection = providers.gradleProperty("tramaiScmConnection").orElse("scm:git:https://github.com/GionaGranchelli/tramAI.git")
val tramaiScmDeveloperConnection = providers.gradleProperty("tramaiScmDeveloperConnection").orElse("scm:git:ssh://git@github.com/GionaGranchelli/tramAI.git")
val tramaiLicenseName = providers.gradleProperty("tramaiLicenseName").orElse("Apache-2.0")
val tramaiLicenseUrl = providers.gradleProperty("tramaiLicenseUrl").orElse("https://www.apache.org/licenses/LICENSE-2.0.txt")
val tramaiDeveloperId = providers.gradleProperty("tramaiDeveloperId").orElse("GionaGranchelli")
val tramaiDeveloperName = providers.gradleProperty("tramaiDeveloperName").orElse("Giona")
val tramaiDeveloperEmail = providers.gradleProperty("tramaiDeveloperEmail").orElse("opensource@giona.dev")
val publishableProjectNames = ModuleManifest.publishableModulePaths(rootDir).map { it.removePrefix(":") }
extra["tramai.publishableModulePaths"] = publishableProjectNames.map { ":$it" }

// Sovereign bundle modules for the dedicated publication dry-run repository.
// Used by the verifySovereignRuntimeSignedBundle task to publish only to a local
// file-based Maven repository — never to a remote — preventing accidental remote
subprojects {
    group = tramaiGroup.get()
    version = tramaiVersion.get()

    repositories {
        mavenCentral()
    }

    // 9.2a: publishing/signing/repository/POM configuration moved into the
    // tramai.publishing convention plugin. The plugin reacts to java-library
    // and java-platform itself (no plugin ordering dependency).
    apply(plugin = "tramai.publishing")
}
// ──────────────────────────────────────────────
// Task: prepareCycloneDxBom
// ──────────────────────────────────────────────
// Plugin is applied above via: alias(libs.plugins.cyclonedx.bom)
// Default output goes to build/reports/cyclonedx/bom.json and is post-processed
// by the copy task below, avoiding typed extension resolution issues.

tasks.register("prepareCycloneDxBom") {
    group = "verification"
    description = "Run cyclonedxBom and place the result plus digest under build/supply-chain/sbom/"
    dependsOn("cyclonedxBom")
    doLast {
        val sbomDir = rootProject.layout.buildDirectory.dir("supply-chain/sbom").get().asFile
        sbomDir.mkdirs()
        val sourceBom = rootProject.layout.buildDirectory.file("reports/cyclonedx/bom.json").get().asFile
        val targetBom = sbomDir.resolve("tramai-cyclonedx-sbom.json")
        if (sourceBom.exists()) {
            sourceBom.copyTo(targetBom, overwrite = true)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hex = digest.digest(targetBom.readBytes())
                .joinToString("") { "%02x".format(it) }
            sbomDir.resolve("tramai-cyclonedx-sbom.sha256")
                .writeText("sha256:$hex")
            logger.lifecycle("SBOM generated: ${targetBom.absolutePath}")
            logger.lifecycle("SBOM digest: build/supply-chain/sbom/tramai-cyclonedx-sbom.sha256")
        } else {
            logger.warn("cyclonedxBom did not produce reports/cyclonedx/bom.json in the build directory; skipping SBOM copy.")
        }
    }
}


// ──────────────────────────────────────────────
// Task: verifySovereignRuntimeReleaseCandidate
// ──────────────────────────────────────────────

val allSubprojectTestTasks = subprojects.flatMap { subproject ->
    subproject.tasks.matching { it.name == "test" }.toList()
}

tasks.register("verifySovereignRuntimeReleaseCandidate") {
    group = "verification"
    description =
        "Runs the canonical local verification chain for the Sovereign Runtime Release Candidate. " +
            "Does not publish remotely, create tags, or release to Maven Central."

    notCompatibleWithConfigurationCache(
        "Sovereign runtime release-candidate verification aggregates execution-time verification tasks.",
    )

    dependsOn(
        allSubprojectTestTasks,
        "verifyReleaseReadiness",
        "verifySovereignRuntimePublication",
        "verifySovereignRuntimeSignedBundle",
        "generateSovereignReleaseEvidenceIndex",
        "verifySovereignRuntimeConsumerSmoke",
        "verifySovereignDocumentIntelligenceEvidenceRun",
        "verifySovereignRuntimeApiBoundary",
    )

    doLast {
        logger.lifecycle("Sovereign runtime release-candidate verification complete.")
        logger.lifecycle("Validated:")
        logger.lifecycle("  - full subproject test suite")
        logger.lifecycle("  - release readiness")
        logger.lifecycle("  - local sovereign runtime publication")
        logger.lifecycle("  - signed bundle dry-run")
        logger.lifecycle("  - release evidence index")
        logger.lifecycle("  - standalone consumer smoke")
        logger.lifecycle("  - sovereign document intelligence evidence run")
        logger.lifecycle("No remote repository was published to.")
        logger.lifecycle("No tag or GitHub release was created.")
    }
}


// ──────────────────────────────────────────────
// Task: verifySovereignLabEvidenceBundle
// ──────────────────────────────────────────────

// ──────────────────────────────────────────────
// Task: verifySovereignLabRuntimeSmoke
// ──────────────────────────────────────────────

tasks.register("verifySovereignLabRuntimeSmoke") {
    group = "verification"
    description = "Runs the sovereign lab runtime smoke test against embedded PostgreSQL."

    dependsOn(":examples:spring-sovereign-starter:e2eTest")

    doLast {
        val reportDir = file(
            "examples/spring-sovereign-starter/build/test-results/e2eTest/"
        )
        val reportFile = reportDir.resolve(
            "TEST-dev.tramai.examples.spring.SovereignLabProfileSmokeTest.xml"
        )

        require(reportFile.exists()) {
            "SovereignLabProfileSmokeTest did not run. " +
                "verifySovereignLabRuntimeSmoke must prove the lab smoke test executed.\n" +
                "Expected report: ${reportFile.absolutePath}"
        }

        val xml = reportFile.readText()
        require(xml.contains("failures=\"0\"") && xml.contains("errors=\"0\"")) {
            "SovereignLabProfileSmokeTest did not pass cleanly. " +
                "Check the test report at:\n  ${reportFile.absolutePath}"
        }

        logger.lifecycle("verifySovereignLabRuntimeSmoke: sovereign lab runtime smoke tests passed.")
    }
}

// ──────────────────────────────────────────────
// Task: verifySovereignLabLocalModel
// ──────────────────────────────────────────────

tasks.register("verifySovereignLabLocalModel") {
    group = "verification"
    description = "Runs the opt-in sovereign lab local-model invocation proof (requires a real local OpenAI-compatible endpoint)."

    dependsOn(":examples:spring-sovereign-starter:localModelTest")

    doFirst {
        if (System.getenv("TRAMAI_ENABLE_LOCAL_MODEL_TEST") != "true") {
            logger.lifecycle(
                "verifySovereignLabLocalModel requires TRAMAI_ENABLE_LOCAL_MODEL_TEST=true."
            )
            logger.lifecycle(
                "Set it and ensure a local OpenAI-compatible endpoint is running."
            )
        }
    }
}

// ──────────────────────────────────────────────
// Task: benchmarkSovereignLabLocalModel
// ──────────────────────────────────────────────

tasks.register("benchmarkSovereignLabLocalModel") {
    group = "verification"
    description = "Runs opt-in sovereign lab local-model benchmark diagnostics."

    dependsOn(":examples:spring-sovereign-starter:localModelBenchmark")

    doFirst {
        if (System.getenv("TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK") != "true") {
            logger.lifecycle(
                "benchmarkSovereignLabLocalModel requires TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK=true."
            )
            logger.lifecycle(
                "Set it and ensure a local OpenAI-compatible endpoint is running."
            )
        }
    }
}

// ──────────────────────────────────────────────
// Task: verifySovereignRuntimeClosure
// ──────────────────────────────────────────────

tasks.register("verifySovereignRuntimeClosure") {
    group = "verification"
    description = "Verifies the Sovereign Runtime closure boundary — the canonical gate for the Sovereignty RC+ / enterprise proof milestone."

    notCompatibleWithConfigurationCache(
        "Sovereign runtime closure verification aggregates execution-time verification tasks.",
    )

    dependsOn(
        "check",
        "verifySovereignRuntimeReleaseCandidate",
        ":examples:spring-sovereign-starter:e2eTest",
        "verifySovereignRuntimeClosureDocs",
        "verifySovereignRuntimeApiBoundary",
    )

    doLast {
        logger.lifecycle("Sovereign runtime closure verification complete.")
        logger.lifecycle("Validated:")
        logger.lifecycle("  - check (full test suite)")
        logger.lifecycle("  - verifySovereignRuntimeReleaseCandidate")
        logger.lifecycle("  - :examples:spring-sovereign-starter:e2eTest")
        logger.lifecycle("  - verifySovereignRuntimeClosureDocs (documentation consistency)")
        logger.lifecycle("  - verifySovereignRuntimeApiBoundary (API stability boundary)")
        logger.lifecycle("Sovereignty roadmap is closed at the RC+ / enterprise proof level.")
    }
}


// Wire the roadmap guard into the default check lifecycle task so it runs
// on every build and protects the roadmap from accidental deletion or drift.
tasks.named("check") {
    dependsOn("verifyPostSovereigntyRoadmap")
}


// Wire into check
tasks.named("check") {
    dependsOn("verifyProductPositioning")
}


// Wire into check
tasks.named("check") {
    dependsOn("verifyReadmePositioning")
    dependsOn("verifyGovernedWorkflowArticle")
    dependsOn("verifyExampleSelectionGuide")
    dependsOn("verifyJvmAiFrameworkComparison")
}


// ──────────────────────────────────────────────
// Task: verify050ReleaseReadiness
// ──────────────────────────────────────────────

tasks.register("verify050ReleaseReadiness") {
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
        val rootDir = rootProject.layout.projectDirectory.asFile
        val expectedVersion = "0.5.0"
        val expectedReleaseDate = project.findProperty("tramaiReleaseDate") as? String
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
        val releaseDocs = listOf(
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
        val prCounts = prPattern.findAll(addedSection).map { it.groupValues[1] }.groupingBy { it }.eachCount()
        val duplicates = prCounts.filter { it.value > 1 }
        require(duplicates.isEmpty()) {
            "Duplicate PR entries in Added section: ${duplicates.keys.joinToString(", ") { "PR #$it appears ${duplicates[it]} times" }}"
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

        logger.lifecycle("verify050ReleaseReadiness: all checks passed.")
        logger.lifecycle("  - Version alignment: verified")
        logger.lifecycle("  - Release readiness: verified")
        logger.lifecycle("  - Workflow API stability boundary: verified")
        logger.lifecycle("  - Sovereign runtime API boundary: verified")
        logger.lifecycle("  - Tool governance example: verified")
        logger.lifecycle("  - 0.5.0 release-readiness doc: verified")
        logger.lifecycle("  - CHANGELOG: 0.5.0 section verified")
        logger.lifecycle("  - STATUS/roadmap: release-ready state verified")
        logger.lifecycle("  - Publish workflow: version alignment check verified")
        logger.lifecycle("  - Release docs: no absolute paths or stale claims")
    }
}

// ──────────────────────────────────────────────
// Task: check

tasks.named("check") {
    dependsOn("verify050ReleaseReadiness")
}
