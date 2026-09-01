package dev.tramai.build.sovereign

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.register

/**
 * Registers the sovereign LAB / evidence tasks as typed CC-safe tasks
 * (Epic 9.2d-a3b2a). Applied to the root project. Task names and observable
 * semantics are identical to the historical root build script closures.
 *
 * The zero-egress report (build/zero-egress-report files) is produced by
 * scripts/verify-zero-egress.sh — a shell harness, not a Gradle task — so no
 * producer edge exists for it; CI order covers it. prepareSovereignEvidenceBundle
 * deliberately has NO producer edges: its file-exists requires are the ordering
 * guarantee, and wiring cyclonedxBom would drag the pre-existing
 * prepareCycloneDxBom configuration-cache offender into the task graph.
 * verifySovereignEvidenceBundleReleaseManifest depends on prepareSovereignEvidenceBundle
 * because prepare writes its inputs (Gradle 9 undeclared-output validation).
 */
class TramaiSovereignLabVerificationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        registerVerifySovereignLabProfile(project)
        registerVerifySovereignLabEvidenceBundle(project)
        registerPrepareSovereignEvidenceBundle(project)
        registerVerifySovereignEvidenceBundleReleaseManifest(project)
        registerVerifySovereignEvidencePackContainsReleaseBundle(project)
        registerVerifySovereignDocumentIntelligenceEvidenceRun(project)
        registerVerifySovereignLabRuntimeSmoke(project)
        registerVerifySovereignLabLocalModel(project)
        registerBenchmarkSovereignLabLocalModel(project)
    }

    private fun registerVerifySovereignLabProfile(project: Project) {
        project.tasks.register<SovereignLabProfileVerifierTask>("verifySovereignLabProfile") {
            group = "verification"
            description = "Verifies the physical sovereign lab profile and documentation exist."
            labProfileFile.set(
                project.layout.projectDirectory.file("examples/spring-sovereign-starter/src/main/resources/application-sovereign-lab.yml"),
            )
            labReadmeFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/README.md"))
            evidenceFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/EVIDENCE.md"))
            benchmarkTemplateFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/evidence-template/benchmark.md"))
            evidenceBundleScriptFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/create-evidence-bundle.sh"))
            manifestTemplateFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/evidence-template/MANIFEST.md"))
            commandLogTemplateFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/evidence-template/command-log.md"))
            finalizeScriptFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/finalize-evidence-bundle.sh"))
            releaseReadinessFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/RELEASE-READINESS.md"))
            reviewerGuideFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/REVIEWER-GUIDE.md"))
            evidenceChainFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/EVIDENCE-CHAIN.md"))
            packagerScriptFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/package-evidence-bundle.sh"))
            archiveVerifierScriptFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/verify-evidence-archive.sh"))
            archiveSigningDocFile.set(project.layout.projectDirectory.file("examples/sovereign-lab/ARCHIVE-SIGNING.md"))
        }
    }

    private fun registerVerifySovereignLabEvidenceBundle(project: Project) {
        project.tasks.register<SovereignLabEvidenceBundleVerifierTask>("verifySovereignLabEvidenceBundle") {
            group = "verification"
            description = "Verifies the sovereign lab evidence bundle scaffold."
            dependsOn("verifySovereignLabProfile")
            createScript.set(project.layout.projectDirectory.file("examples/sovereign-lab/create-evidence-bundle.sh"))
            verifierScript.set(project.layout.projectDirectory.file("examples/sovereign-lab/verify-evidence-bundle.sh"))
            finalizerScript.set(project.layout.projectDirectory.file("examples/sovereign-lab/finalize-evidence-bundle.sh"))
            packagerScript.set(project.layout.projectDirectory.file("examples/sovereign-lab/package-evidence-bundle.sh"))
            archiveVerifierScript.set(project.layout.projectDirectory.file("examples/sovereign-lab/verify-evidence-archive.sh"))
            signatureVerifierScript.set(project.layout.projectDirectory.file("examples/sovereign-lab/verify-evidence-archive-signature.sh"))
            evidenceTemplates.from(
                project.fileTree("examples/sovereign-lab/evidence-template") {
                    include("*.md")
                },
            )
            workingDirectory.set(project.layout.projectDirectory.dir("examples/sovereign-lab/build"))
        }
    }

    private fun registerPrepareSovereignEvidenceBundle(project: Project) {
        project.tasks.register<PrepareSovereignEvidenceBundleTask>("prepareSovereignEvidenceBundle") {
            group = "verification"
            description = "Assembles all sovereign audit outputs into build/sovereign-evidence/."

            evidencePackFile.set(project.layout.buildDirectory.file("zero-egress-report/sovereign-evidence-pack-v1.json"))
            zeroEgressReportFile.set(project.layout.buildDirectory.file("zero-egress-report/zero-egress-report.json"))
            sbomFile.set(project.layout.buildDirectory.file("supply-chain/sbom/tramai-cyclonedx-sbom.json"))
            sbomDigestFile.set(project.layout.buildDirectory.file("supply-chain/sbom/tramai-cyclonedx-sbom.sha256"))
            releaseManifestFile.set(project.layout.buildDirectory.file("sovereign-release/release-artifacts-v1.json"))
            releaseArtifactsDirectory.set(project.layout.buildDirectory.dir("sovereign-release/artifacts"))
            artifactsJars.from(
                project.layout.buildDirectory
                    .dir("sovereign-release/artifacts")
                    .map { dir -> dir.asFileTree.matching { include("*.jar") } },
            )
            outputDirectory.set(project.layout.buildDirectory.dir("sovereign-evidence"))

            // Master semantics preserved: NO producer edges (the file-exists
            // require()s are the ordering guarantee — fail loudly if the sbom
            // or pack is missing). Wiring cyclonedxBom here would drag the
            // pre-existing prepareCycloneDxBom CC offender into this task's
            // graph; master had no dependsOn and stays that way.
        }
    }

    private fun registerVerifySovereignEvidenceBundleReleaseManifest(project: Project) {
        project.tasks.register<VerifySovereignEvidenceBundleReleaseManifestTask>("verifySovereignEvidenceBundleReleaseManifest") {
            group = "verification"
            description =
                "Verifies that build/sovereign-evidence/release/release-artifacts-v1.json is internally consistent with the JAR files in build/sovereign-evidence/release/artifacts/."

            manifestFile.set(project.layout.buildDirectory.file("sovereign-evidence/release/release-artifacts-v1.json"))
            artifactJars.from(
                project.layout.buildDirectory
                    .dir("sovereign-evidence/release/artifacts")
                    .map { dir -> dir.asFileTree.matching { include("*.jar") } },
            )
            // Required edge: prepareSovereignEvidenceBundle WRITES this task's
            // inputs (release/artifacts + manifest). Without the explicit
            // dependsOn Gradle 9 flags the undeclared task-output overlap.
            // prepareSovereignEvidenceBundle has no producer edges, so this
            // does not pull prepareCycloneDxBom or any CC offender.
            dependsOn("prepareSovereignEvidenceBundle")
        }
    }

    private fun registerVerifySovereignEvidencePackContainsReleaseBundle(project: Project) {
        project.tasks.register<VerifySovereignEvidencePackContainsReleaseBundleTask>("verifySovereignEvidencePackContainsReleaseBundle") {
            group = "verification"
            description = "Verifies that build/zero-egress-report/sovereign-evidence-pack-v1.json contains releaseBundle."

            evidencePackFile.set(project.layout.buildDirectory.file("zero-egress-report/sovereign-evidence-pack-v1.json"))
        }
    }

    private fun registerVerifySovereignDocumentIntelligenceEvidenceRun(project: Project) {
        val documentIntelligenceRunCommand =
            listOf(
                if (System.getProperty("os.name").lowercase().contains("windows")) "gradlew.bat" else "./gradlew",
                ":examples:sovereign-document-intelligence:run",
                "--no-configuration-cache",
                "--args=--release-bundle-manifest=${project.rootProject.layout.buildDirectory.get().asFile.absolutePath}/sovereign-release/release-artifacts-v1.json",
            )

        project.tasks.register<Exec>("verifySovereignDocumentIntelligenceEvidenceRun") {
            group = "verification"
            description =
                "Runs the sovereign document intelligence reference example against the generated release bundle " +
                "manifest. Validates evidence pack generation against release artifacts."

            dependsOn("prepareSovereignReleaseArtifacts", "verifySovereignReleaseManifest")

            workingDir = project.projectDir
            commandLine(documentIntelligenceRunCommand)
        }
    }

    /**
     * Sovereign lab runtime smoke (Epic 9.2d-b2 slice B). Moved from the root
     * build script; the XML report inspection is now a typed CC-safe task with
     * the report file declared as an input. dependsOn preserved exactly.
     */
    private fun registerVerifySovereignLabRuntimeSmoke(project: Project) {
        project.tasks.register<VerifySovereignLabRuntimeSmokeTask>("verifySovereignLabRuntimeSmoke") {
            group = "verification"
            description = "Runs the sovereign lab runtime smoke test against embedded PostgreSQL."

            dependsOn(":examples:spring-sovereign-starter:e2eTest")

            smokeReportFile.set(
                project.layout.projectDirectory.file(
                    "examples/spring-sovereign-starter/build/test-results/e2eTest/TEST-dev.tramai.examples.spring.SovereignLabProfileSmokeTest.xml",
                ),
            )
        }
    }

    /**
     * Opt-in sovereign lab local-model invocation proof (Epic 9.2d-b2 slice B).
     * Env-gate + dependsOn moved verbatim from the root build script; the
     * plugin owns the registration, the root build stays composition-only.
     */
    private fun registerVerifySovereignLabLocalModel(project: Project) {
        project.tasks.register("verifySovereignLabLocalModel") {
            group = "verification"
            description = "Runs the opt-in sovereign lab local-model invocation proof (requires a real local OpenAI-compatible endpoint)."

            dependsOn(":examples:spring-sovereign-starter:localModelTest")

            doFirst {
                if (System.getenv("TRAMAI_ENABLE_LOCAL_MODEL_TEST") != "true") {
                    logger.lifecycle(
                        "verifySovereignLabLocalModel requires TRAMAI_ENABLE_LOCAL_MODEL_TEST=true.",
                    )
                    logger.lifecycle(
                        "Set it and ensure a local OpenAI-compatible endpoint is running.",
                    )
                }
            }
        }
    }

    /**
     * Opt-in sovereign lab local-model benchmark diagnostics (Epic 9.2d-b2
     * slice B). Env-gate + dependsOn moved verbatim from the root build
     * script.
     */
    private fun registerBenchmarkSovereignLabLocalModel(project: Project) {
        project.tasks.register("benchmarkSovereignLabLocalModel") {
            group = "verification"
            description = "Runs opt-in sovereign lab local-model benchmark diagnostics."

            dependsOn(":examples:spring-sovereign-starter:localModelBenchmark")

            doFirst {
                if (System.getenv("TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK") != "true") {
                    logger.lifecycle(
                        "benchmarkSovereignLabLocalModel requires TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK=true.",
                    )
                    logger.lifecycle(
                        "Set it and ensure a local OpenAI-compatible endpoint is running.",
                    )
                }
            }
        }
    }
}
