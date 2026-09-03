import java.lang.management.ManagementFactory

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
    id("tramai.supply-chain")
    id("tramai.docs-guards")
    id("tramai.static-analysis")
    id("tramai.compiler-warnings")
    id("tramai.dependency-hygiene")
    id("tramai.static-safety-guards")
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

// ── Epic 10.2: BCV public-surface boundary ──
// BCV's committed API dumps are the signature authority. Declarations carrying
// a sanctioned non-public marker are technically public only for cross-module
// composition — they are not stable application-facing API and "may change or
// move in any release" (their own KDoc). nonPublicMarkers removes them from
// the dump so Contract-1/Contract-2 stay byte-exact on the true stable
// surface. Adding a marker here is the ONLY way to opt a declaration out of
// the stable freeze: an unmarked new public declaration still enters the dump
// and Contract-2 still fails.
apiValidation {
    nonPublicMarkers.add("dev.tramai.core.provider.transport.ExperimentalProviderTransportApi")
    nonPublicMarkers.add("dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi")
}

// ── Epic 10.1b: baseline-backed Kotlin static analysis ──
// One repository-level Detekt authority (tramai.static-analysis plugin): one
// pinned Detekt version, one central config (config/detekt/detekt.yml), one
// central baseline (config/detekt/baseline.xml), one aggregate task
// (verifyStaticAnalysis), one report location
// (build/reports/static-analysis/). The gate is ratcheted against the exact
// PR/push base:
//   -PtramaiStaticAnalysisBaseRef=<sha>  exact base (CI PR: pull_request.base.sha,
//                                        CI push: github.event.before)
//   property absent                  origin/master (local default)
// verifyStaticAnalysis joins the root `check` lifecycle and verifyPr below.
tasks.named("check") {
    dependsOn("verifyStaticAnalysis")
}
tasks.named("verifyPr") {
    dependsOn("verifyStaticAnalysis")
}

// ── Epic 10.1c: baseline-backed compiler-warning gate ──
// One repository-level authority (tramai.compiler-warnings plugin): one pinned
// kotlin-compiler-embeddable (== repo Kotlin version), one central baseline
// (config/warnings/baseline.json), one verify task (verifyCompilerWarnings).
// The gate recompiles delta modules with the standalone compiler using the same
// classpaths/args as the real build and fails on warnings not covered by the
// baseline. Ratcheted against the exact PR/push base:
//   -PtramaiCompilerWarningsBaseRef=<sha>  exact base (CI PR: pull_request.base.sha,
//                                          CI push: github.event.before)
//   property absent                  origin/master (local default)
// verifyCompilerWarnings joins the root `check` lifecycle and verifyPr below.
tasks.named("check") {
    dependsOn("verifyCompilerWarnings")
}
tasks.named("verifyPr") {
    dependsOn("verifyCompilerWarnings")
}

// ── Epic 10.1c: unused-dependency gate ──
// One authority (tramai.dependency-hygiene plugin): one central exemption
// catalog (config/dependency-hygiene/exemptions.yml), one verify task
// (verifyDependencyHygiene). No unused direct MAIN-scope dependency may exist
// without an explicitly documented non-static usage exemption.
tasks.named("check") {
    dependsOn("verifyDependencyHygiene")
}
tasks.named("verifyPr") {
    dependsOn("verifyDependencyHygiene")
}

// ── Epic 10.1d: lifecycle/security static safety guards ──
tasks.named("check") { dependsOn("verifyStaticSafetyGuards") }
tasks.named("verifyPr") { dependsOn("verifyStaticSafetyGuards") }
tasks.named("check") { dependsOn("verifyCancellationSafety") }
tasks.named("verifyPr") { dependsOn("verifyCancellationSafety") }

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

    // Epic 12.1a/b benchmark harness: forward the deep-lane activation flag
    // (-Dtramai.benchmark=true) and run metadata to every module's test worker
    // JVMs. Absent the flag nothing is forwarded and benchmark classes stay
    // skipped, so ordinary PR CI remains timing-free.
    tasks.withType<Test>().configureEach {
        providers.systemProperty("tramai.benchmark").orNull?.let { flag ->
            systemProperty("tramai.benchmark", flag)
            systemProperty(
                "tramai.benchmark.gitSha",
                providers.exec { commandLine("git", "rev-parse", "HEAD") }
                    .standardOutput.asText.get().trim(),
            )
            systemProperty(
                "tramai.benchmark.out",
                layout.buildDirectory.dir("reports/benchmark").get().asFile.absolutePath,
            )
            systemProperty(
                "tramai.benchmark.gradleJvmArgs",
                ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "),
            )
            providers.systemProperty("tramai.benchmark.iterations").orNull?.let {
                systemProperty("tramai.benchmark.iterations", it)
            }
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
// Task: verifySovereignRuntimePullRequest
// ──────────────────────────────────────────────
// PR-scoped sovereign verification (Epic 10.5 P3-E, decision B): proves the
// sovereign-specific release chain on a pull request WITHOUT re-running the
// repository test/check graph — mandatory CI already proves tests, quality
// gates, compiler warnings, coverage, cancellation, and publishToMavenLocal
// on the exact commit. Including `check` / the all-subproject test fan-out
// here duplicated the full CI stack on the RC lane (~19-30 min measured,
// #372/#374). The full independent certification remains
// verifySovereignRuntimeClosure --rerun-tasks for workflow_dispatch/release.

tasks.register("verifySovereignRuntimePullRequest") {
    group = "verification"
    description =
        "Runs the sovereign-specific release-chain proof for a pull request (bundle dry-run, " +
            "verification-repo closure, consumer smoke, doc-intel evidence run, spring e2e, API " +
            "boundary, closure docs). Does NOT re-run repository tests/quality gates — CI proves " +
            "those on the same commit."

    notCompatibleWithConfigurationCache(
        "Sovereign runtime PR verification aggregates execution-time verification tasks.",
    )

    dependsOn(
        "verifySovereignRuntimeSignedBundle",
        "verifySovereignRuntimeVerificationRepoClosure",
        "verifySovereignRuntimeConsumerSmoke",
        "verifySovereignDocumentIntelligenceEvidenceRun",
        ":examples:spring-sovereign-starter:e2eTest",
        "verifySovereignRuntimeApiBoundary",
        "verifySovereignRuntimeClosureDocs",
        "verifySovereignOpsObservabilityDocs",
    )

    doLast {
        logger.lifecycle("Sovereign runtime PR verification complete.")
        logger.lifecycle("Validated:")
        logger.lifecycle("  - signed bundle dry-run (local file repo only)")
        logger.lifecycle("  - verification repo closure")
        logger.lifecycle("  - standalone consumer smoke")
        logger.lifecycle("  - sovereign document-intelligence evidence run")
        logger.lifecycle("  - :examples:spring-sovereign-starter:e2eTest")
        logger.lifecycle("  - verifySovereignRuntimeApiBoundary (API stability boundary)")
        logger.lifecycle("  - verifySovereignRuntimeClosureDocs (documentation consistency)")
        logger.lifecycle("No remote repository was published to.")
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
// Task: check
//
// verify050ReleaseReadiness is deliberately NOT wired into the normal
// developer check lifecycle: it is release-only orchestration and stays
// notCompatibleWithConfigurationCache (C3 = 1 deliberate). Normal test/check
// must remain configuration-cache reusable; the release gate is invoked
// explicitly by release tooling (see .github/workflows/publish.yml, which
// runs it with --no-configuration-cache).
