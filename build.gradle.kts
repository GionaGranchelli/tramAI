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
                // Generated/task-output and caches only — never broaden to
                // production source.
                exclude(
                    "**/build/**",
                    "**/.gradle/**",
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

tasks.register("verifySovereignLabEvidenceBundle") {
    group = "verification"
    description = "Verifies the sovereign lab evidence bundle scaffold."

    dependsOn("verifySovereignLabProfile")

    doLast {
        val script = file("examples/sovereign-lab/create-evidence-bundle.sh")
        require(script.exists()) {
            "Missing evidence bundle script at ${script.absolutePath}"
        }

        val bundleRoot = file("examples/sovereign-lab/build/evidence-bundles")
        val bundle = bundleRoot.resolve("test-bundle")
        if (bundle.exists()) {
            bundle.deleteRecursively()
        }

        val pb = ProcessBuilder("bash", script.absolutePath)
        pb.environment()["TRAMAI_EVIDENCE_BUNDLE_TIMESTAMP"] = "test-bundle"
        pb.inheritIO()
        val process = pb.start()
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            "Evidence bundle script exited with code $exitCode"
        }

        require(bundle.exists()) {
            "Evidence bundle was not created at ${bundle.absolutePath}"
        }

        val requiredFiles = listOf(
            "README.md",
            "manifest.json",
            "MANIFEST.md",
            "command-log.md",
            "environment.md",
            "run-log.md",
            "approval-flow.md",
            "restart-proof.md",
            "jdbc-persistence.md",
            "no-cloud-proof.md",
            "benchmark.md",
            "reports/.gitkeep",
        )

        requiredFiles.forEach { relativePath ->
            val candidate = bundle.resolve(relativePath)
            require(candidate.exists()) {
                "Generated evidence bundle is missing $relativePath at ${candidate.absolutePath}"
            }
        }

        val readmeText = bundle.resolve("README.md").readText()
        require(readmeText.contains("Sovereign Lab Evidence Bundle")) {
            "Generated README.md must be the bundle README, not the template README."
        }
        require(!readmeText.contains("Copy this entire folder", ignoreCase = true)) {
            "Generated README.md must not be copied from evidence-template/README.md."
        }
        require(readmeText.contains("does not certify", ignoreCase = true)) {
            "Generated README.md must avoid certification claims."
        }
        require(readmeText.contains("performance guarantees", ignoreCase = true)) {
            "Generated README.md must avoid production performance guarantee claims."
        }

        val manifestText = bundle.resolve("MANIFEST.md").readText()
        require(manifestText.contains("This bundle does not certify", ignoreCase = true)) {
            "MANIFEST.md must retain non-certification language."
        }

        // ── manifest.json checks ──

        val jsonManifestText = bundle.resolve("manifest.json").readText()
        require(jsonManifestText.contains("\"schemaVersion\": 1")) {
            "manifest.json must declare schemaVersion 1."
        }
        require(jsonManifestText.contains("\"bundleType\": \"sovereign-lab-evidence-bundle\"")) {
            "manifest.json must declare the sovereign lab evidence bundle type."
        }
        require(jsonManifestText.contains("\"localEvidenceScaffold\": true")) {
            "manifest.json must declare this as a local evidence scaffold."
        }
        require(jsonManifestText.contains("\"certifiesProductionReadiness\": false")) {
            "manifest.json must not imply production certification."
        }
        require(jsonManifestText.contains("\"definesPerformanceGuarantees\": false")) {
            "manifest.json must not imply performance guarantees."
        }
        require(jsonManifestText.contains("\"runsLocalModel\": false")) {
            "manifest.json must state that bundle verification does not run a local model."
        }
        require(jsonManifestText.contains("\"runsBenchmark\": false")) {
            "manifest.json must state that bundle verification does not run benchmarks."
        }
        require(jsonManifestText.contains("\"validatesEvidenceTruth\": false")) {
            "manifest.json must state that it does not validate evidence truth."
        }
        requiredFiles
            .filterNot { it == "manifest.json" }
            .forEach { required ->
                require(jsonManifestText.contains("\"$required\"")) {
                    "manifest.json must list required file $required."
                }
            }

        // ── manifest.json file digests ──

        require(jsonManifestText.contains("\"files\": [")) {
            "manifest.json must include file integrity metadata."
        }
        require(jsonManifestText.contains("\"sha256\"")) {
            "manifest.json must include SHA-256 digests."
        }
        require(jsonManifestText.contains("\"sizeBytes\"")) {
            "manifest.json must include file sizes."
        }

        // Recompute SHA-256 digests and verify they match
        requiredFiles
            .filterNot { it == "manifest.json" }
            .forEach { required ->
                val candidate = bundle.resolve(required)
                require(candidate.exists()) {
                    "Cannot recompute digest for missing file $required."
                }
                val digest = candidate.inputStream().use { input ->
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        md.update(buffer, 0, read)
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
                require(jsonManifestText.contains("\"sha256\": \"$digest\"")) {
                    "manifest.json SHA-256 for $required does not match generated file."
                }
                require(jsonManifestText.contains("\"sizeBytes\": ${candidate.length()}")) {
                    "manifest.json sizeBytes for $required does not match generated file."
                }
            }

        // ── standalone verifier ──

        val verifier = file("examples/sovereign-lab/verify-evidence-bundle.sh")
        require(verifier.exists()) {
            "Missing evidence bundle verifier at ${verifier.absolutePath}"
        }

        val finalizer = file("examples/sovereign-lab/finalize-evidence-bundle.sh")
        require(finalizer.exists()) {
            "Missing evidence bundle finalizer at ${finalizer.absolutePath}"
        }

        // Clean generated bundle should pass
        val cleanProcess = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .inheritIO()
            .start()
        val cleanExitCode = cleanProcess.waitFor()
        require(cleanExitCode == 0) {
            "Evidence bundle verifier rejected a clean generated bundle (exit $cleanExitCode)."
        }

        // ── Positive runtime-evidence: add valid records before finalization ──

        val rtEvidenceDir = bundle.resolve("runtime-evidence")
        rtEvidenceDir.mkdirs()

        fun writeRtLine(filename: String, vararg lines: String) {
            rtEvidenceDir.resolve(filename).writeText(lines.joinToString("\n") + "\n")
        }

        // Valid policy decision record
        writeRtLine("policy-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"lifecycle-policy-001","eventType":"policy.decision","workflowRunId":"wf-lc","correlationId":"corr-lc","actor":"policy-engine","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine","module":"v1"},"decision":{"kind":"ALLOW","reasonCode":"policy_allowed"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"providerName":"ollama","classification":"low"}}"""
        )

        // Valid approval decision record
        writeRtLine("approval-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"lifecycle-approval-001","eventType":"approval.decision","workflowRunId":"wf-lc","correlationId":"corr-lc2","actor":"human-approver","createdAt":"2026-07-13T10:00:10Z","source":{"component":"approval-control-plane","module":"approval"},"decision":{"kind":"APPROVED","reasonCode":"approval-approved"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000003","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000004"},"metadata":{"approvalVersion":"1","reasonDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","reasonLength":"29"}}"""
        )

        // Valid provider routing record
        writeRtLine("provider-routing.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"lifecycle-routing-001","eventType":"provider.route","workflowRunId":"wf-lc","correlationId":"corr-lc3","actor":"provider-router","createdAt":"2026-07-13T10:00:20Z","source":{"component":"provider-router","module":"tramai-engine"},"decision":{"kind":"SELECTED","reasonCode":"provider-selected"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000005","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000006"},"metadata":{"requestedModelDigest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","routeIndex":"0","attempt":"1"}}"""
        )

        // Valid tool permission record
        writeRtLine("tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"lifecycle-tool-001","eventType":"tool.permission","workflowRunId":"wf-lc","correlationId":"corr-lc4","actor":"policy-engine","createdAt":"2026-07-13T10:00:30Z","source":{"component":"policy-engine","module":"v1"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000007","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000008"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"HIGH"}}"""
        )

        logger.lifecycle("verifySovereignLabEvidenceBundle: added positive runtime-evidence to ${bundle.absolutePath}")

        // ── lifecycle: edit → fail → finalize → pass → tamper → fail ──

        val evidenceFile = bundle.resolve("command-log.md")
        evidenceFile.appendText("\nOperator captured command output.\n")

        // Verify before finalization must fail (manifest is stale or missing files)
        val preFinalizeProcess = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val preFinalizeOutput = preFinalizeProcess.inputStream.bufferedReader().readText()
        val preFinalizeExitCode = preFinalizeProcess.waitFor()
        require(preFinalizeExitCode != 0) {
            "Evidence bundle verifier must fail after evidence is filled but before finalization."
        }
        require(
            preFinalizeOutput.contains("sha256 mismatch") ||
            preFinalizeOutput.contains("sizeBytes mismatch") ||
            preFinalizeOutput.contains("missing from manifest") ||
            preFinalizeOutput.contains("files missing from manifest")
        ) {
            "Evidence bundle verifier failure before finalization should explain digest or size mismatch or missing files. Output: $preFinalizeOutput"
        }

        // Finalize to refresh manifest digests
        val finalizeProcess = ProcessBuilder("bash", finalizer.absolutePath, bundle.absolutePath)
            .inheritIO()
            .start()
        val finalizeExitCode = finalizeProcess.waitFor()
        require(finalizeExitCode == 0) {
            "Evidence bundle finalizer exited with code $finalizeExitCode"
        }

        // Finalized bundle must pass
        val postFinalizeProcess = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .inheritIO()
            .start()
        val postFinalizeExitCode = postFinalizeProcess.waitFor()
        require(postFinalizeExitCode == 0) {
            "Evidence bundle verifier rejected a finalized bundle (exit $postFinalizeExitCode)."
        }

        // ── Positive runtime-evidence manifest checks ──

        val manifestAfterRt = bundle.resolve("manifest.json").readText()
        for (rtFile in listOf(
            "runtime-evidence/policy-decisions.jsonl",
            "runtime-evidence/approval-decisions.jsonl",
            "runtime-evidence/provider-routing.jsonl",
            "runtime-evidence/tool-permissions.jsonl",
        )) {
            require(manifestAfterRt.contains(rtFile)) {
                "manifest.json must contain runtime-evidence path '$rtFile' after finalization. " +
                    "Manifest: $manifestAfterRt"
            }
        }
        logger.lifecycle(
            "verifySovereignLabEvidenceBundle: positive runtime-evidence finalized " +
                "and verified with 4 files in manifest.json"
        )

        // ── Positive runtime-evidence tamper test ──
        // Tamper WITHOUT re-finalizing so verifier catches stale manifest

        val tamperedRtFile = bundle.resolve("runtime-evidence/policy-decisions.jsonl")
        val originalRtContent = tamperedRtFile.readText()
        tamperedRtFile.appendText("\n{\"tampered\":true}\n")
        val tamperVerifyProc = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val tamperVerifyOutput = tamperVerifyProc.inputStream.bufferedReader().readText()
        val tamperVerifyExit = tamperVerifyProc.waitFor()
        require(tamperVerifyExit != 0) {
            "Verifier must reject a tampered runtime-evidence file, but exit was $tamperVerifyExit. Output: $tamperVerifyOutput"
        }
        require(
            tamperVerifyOutput.contains("sha256 mismatch") ||
            tamperVerifyOutput.contains("sizeBytes mismatch") ||
            tamperVerifyOutput.contains("unknown root field")
        ) {
            "Verifier failure after runtime-evidence tamper should explain digest, size, or unknown field. Output: $tamperVerifyOutput"
        }
        logger.lifecycle(
            "verifySovereignLabEvidenceBundle: tampered runtime-evidence correctly rejected"
        )

        // Restore original content and re-finalize for subsequent tests
        tamperedRtFile.writeText(originalRtContent)
        val restoreFinalizeProc = ProcessBuilder("bash", finalizer.absolutePath, bundle.absolutePath)
            .inheritIO().start()
        require(restoreFinalizeProc.waitFor() == 0) { "Failed to re-finalize after tamper recovery" }

        // ── tool-permissions.jsonl tamper test ──
        val tamperedToolFile = bundle.resolve("runtime-evidence/tool-permissions.jsonl")
        val originalToolContent = tamperedToolFile.readText()
        tamperedToolFile.appendText("\n{\"tampered\":true}\n")
        val tamperToolProc = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val tamperToolOutput = tamperToolProc.inputStream.bufferedReader().readText()
        val tamperToolExit = tamperToolProc.waitFor()
        require(tamperToolExit != 0) {
            "Verifier must reject tampered tool-permissions.jsonl, but exit was $tamperToolExit. Output: $tamperToolOutput"
        }
        require(
            tamperToolOutput.contains("sha256 mismatch") ||
            tamperToolOutput.contains("sizeBytes mismatch") ||
            tamperToolOutput.contains("unknown root field")
        ) {
            "Verifier failure after tool-permissions.jsonl tamper should explain digest, size, or unknown field. Output: $tamperToolOutput"
        }
        logger.lifecycle(
            "verifySovereignLabEvidenceBundle: tampered tool-permissions.jsonl correctly rejected"
        )
        // Restore tool content and re-finalize
        tamperedToolFile.writeText(originalToolContent)
        val restoreToolProc = ProcessBuilder("bash", finalizer.absolutePath, bundle.absolutePath)
            .inheritIO().start()
        require(restoreToolProc.waitFor() == 0) { "Failed to re-finalize after tool-permissions tamper recovery" }

        // Post-finalization tamper must fail
        evidenceFile.appendText("\nTampered after finalization.\n")
        val tamperedAfterProcess = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val tamperedAfterOutput = tamperedAfterProcess.inputStream.bufferedReader().readText()
        val tamperedAfterExit = tamperedAfterProcess.waitFor()
        require(tamperedAfterExit != 0) {
            "Evidence bundle verifier must fail after a finalized bundle is tampered with."
        }
        require(
            tamperedAfterOutput.contains("sha256 mismatch") ||
            tamperedAfterOutput.contains("sizeBytes mismatch") ||
            tamperedAfterOutput.contains("unknown root field") ||
            tamperedAfterOutput.contains("unsupported schemaVersion")
        ) {
            "Evidence bundle verifier failure after tampering should explain digest, size, or structural mismatch. Output: $tamperedAfterOutput"
        }

        // ── copied reports regression ──

        val reportFile = bundle.resolve("reports/generated-report.txt")
        reportFile.parentFile.mkdirs()
        reportFile.writeText("Generated report content\n")

        // Re-finalize with new report
        val reFinalizeProcess = ProcessBuilder("bash", finalizer.absolutePath, bundle.absolutePath)
            .inheritIO()
            .start()
        val reFinalizeExitCode = reFinalizeProcess.waitFor()
        require(reFinalizeExitCode == 0) {
            "Evidence bundle finalizer exited with code $reFinalizeExitCode after adding report."
        }

        // Finalized bundle with copied report must pass
        val withReportProcess = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .inheritIO()
            .start()
        val withReportExitCode = withReportProcess.waitFor()
        require(withReportExitCode == 0) {
            "Evidence bundle verifier rejected a finalized bundle with a copied report."
        }

        // Tampering the copied report must fail
        reportFile.appendText("tampered report\n")
        val tamperedReportProcess = ProcessBuilder("bash", verifier.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val tamperedReportOutput = tamperedReportProcess.inputStream.bufferedReader().readText()
        val tamperedReportExitCode = tamperedReportProcess.waitFor()
        require(tamperedReportExitCode != 0) {
            "Evidence bundle verifier must fail after a copied report is tampered with."
        }
        require(
            tamperedReportOutput.contains("sha256 mismatch") ||
            tamperedReportOutput.contains("sizeBytes mismatch")
        ) {
            "Evidence bundle verifier failure for copied report should explain digest or size mismatch. Output: $tamperedReportOutput"
        }

        // ── Negative fixture tests ──

        // Re-create a clean finalized bundle for negative fixture copies
        if (bundle.exists()) bundle.deleteRecursively()
        val cleanPb = ProcessBuilder("bash", script.absolutePath)
        cleanPb.environment()["TRAMAI_EVIDENCE_BUNDLE_TIMESTAMP"] = "test-bundle"
        cleanPb.inheritIO()
        require(cleanPb.start().waitFor() == 0) { "Failed to re-create clean bundle" }

        val finalizeCleanPb = ProcessBuilder("bash", finalizer.absolutePath, bundle.absolutePath)
        finalizeCleanPb.inheritIO()
        require(finalizeCleanPb.start().waitFor() == 0) { "Failed to finalize clean bundle" }

        val negDir = bundleRoot.resolve("negative-fixtures")
        if (negDir.exists()) negDir.deleteRecursively()
        negDir.mkdirs()

        fun negCopy(name: String): File {
            val target = negDir.resolve(name)
            if (target.exists()) target.deleteRecursively()
            bundle.copyRecursively(target, overwrite = true)
            return target
        }

        fun runExpectFail(
            runner: File,
            bundleDir: File,
            expectMessage: String,
            runnerName: String,
        ) {
            val p = ProcessBuilder("bash", runner.absolutePath, bundleDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            require(code != 0) {
                "Expected $runnerName to fail for ${bundleDir.name}, but exited 0. Output: $out"
            }
            require(out.contains(expectMessage, ignoreCase = true)) {
                "Expected $runnerName failure for ${bundleDir.name} to contain '$expectMessage'. Output: $out"
            }
        }

        fun negRunVerifier(dir: File, msg: String) =
            runExpectFail(verifier, dir, msg, "verifier")

        fun negRunFinalizer(dir: File, msg: String) =
            runExpectFail(finalizer, dir, msg, "finalizer")

        fun mutateManifest(dir: File, pythonCode: String) {
            val fullCode = """
import json, pathlib, sys
bp = pathlib.Path(sys.argv[1])
m = json.loads((bp / "manifest.json").read_text())
$pythonCode
(bp / "manifest.json").write_text(json.dumps(m, indent=2) + "\n")
"""
            val p = ProcessBuilder("python3", "-c", fullCode, dir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val exitCode = p.waitFor()
            require(exitCode == 0) { "manifest mutation failed: $out" }
        }

        // Case 1: Path traversal in requiredFiles
        val traversalDir = negCopy("required-path-traversal")
        mutateManifest(traversalDir, """m["requiredFiles"].append("../evil.md")""")
        negRunVerifier(traversalDir, "safe relative path")
        negRunFinalizer(traversalDir, "safe relative path")

        // Case 2: Absolute path in requiredFiles
        val absDir = negCopy("required-absolute-path")
        mutateManifest(absDir, """m["requiredFiles"].append("/tmp/evil.md")""")
        negRunVerifier(absDir, "safe relative path")
        negRunFinalizer(absDir, "safe relative path")

        // Case 3: Duplicate files[].path
        val dupDir = negCopy("duplicate-file-path")
        mutateManifest(dupDir, """m["files"].append(m["files"][0])""")
        negRunVerifier(dupDir, "duplicate files metadata entry")

        // Case 4: manifest.json self-digest
        // The verifier checks SHA-256 before the self-digest check, so the reject
        // message will be "sha256 mismatch for manifest.json" — which still proves
        // the bundle is rejected because of the manifest.json files[] entry.
        val selfDigestDir = negCopy("manifest-self-digest")
        mutateManifest(selfDigestDir, """m["files"].insert(0, {"path": "manifest.json", "sha256": "0" * 64, "sizeBytes": 0})""")
        negRunVerifier(selfDigestDir, "sha256 mismatch for manifest.json")

        // Case 5: Weakened claim boundary
        val weakenDir = negCopy("weakened-claims")
        mutateManifest(weakenDir, """m["claimBoundary"]["certifiesProductionReadiness"] = True""")
        negRunVerifier(weakenDir, "claimBoundary.certifiesProductionReadiness")
        negRunFinalizer(weakenDir, "claimBoundary.certifiesProductionReadiness")

        // Case 6: Invalid SHA-256
        val badShaDir = negCopy("malformed-sha")
        mutateManifest(badShaDir, """m["files"][0]["sha256"] = "not-a-sha" """)
        negRunVerifier(badShaDir, "sha256")

        // Case 7: Negative sizeBytes
        val negSizeDir = negCopy("negative-size")
        mutateManifest(negSizeDir, """m["files"][0]["sizeBytes"] = -1""")
        negRunVerifier(negSizeDir, "sizeBytes")

        // Case 8: Missing required file
        val missingDir = negCopy("missing-file")
        missingDir.resolve("command-log.md").delete()
        negRunVerifier(missingDir, "required file missing")
        negRunFinalizer(missingDir, "required file missing")

        // ── Symlink negative fixtures ──

        fun createSymlinkOrSkip(link: File, target: File): Boolean {
            return try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
                true
            } catch (ex: UnsupportedOperationException) {
                logger.lifecycle("Skipping symlink fixture: unsupported - ${ex.message}")
                false
            } catch (ex: java.nio.file.FileSystemException) {
                logger.lifecycle("Skipping symlink fixture: creation failed - ${ex.message}")
                false
            }
        }

        // Case 9: Required file symlink
        val requiredSymlinkDir = negCopy("required-file-symlink")
        val originalLog = requiredSymlinkDir.resolve("command-log.md")
        val realLog = requiredSymlinkDir.resolve("real-command-log.md")
        originalLog.copyTo(realLog, overwrite = true)
        originalLog.delete()
        if (createSymlinkOrSkip(originalLog, realLog)) {
            negRunVerifier(requiredSymlinkDir, "symlink")
            negRunFinalizer(requiredSymlinkDir, "symlink")
        }

        // Case 10: Copied report symlink
        val reportSymlinkDir = negCopy("report-file-symlink")
        val reportDir = reportSymlinkDir.resolve("reports")
        reportDir.mkdirs()
        val realReportFile = reportDir.resolve("generated-report-real.txt")
        realReportFile.writeText("generated report content\n")
        val symlinkReportFile = reportDir.resolve("generated-report.txt")
        if (createSymlinkOrSkip(symlinkReportFile, realReportFile)) {
            negRunVerifier(reportSymlinkDir, "symlink")
            negRunFinalizer(reportSymlinkDir, "symlink")
        }

        // Case 11: Unlisted symlink inside bundle
        val unlistedSymlinkDir = negCopy("unlisted-symlink")
        val hiddenLink = unlistedSymlinkDir.resolve("reports/unlisted-link.txt")
        val hiddenTarget = unlistedSymlinkDir.resolve("reports/generated-report.txt")
        if (createSymlinkOrSkip(hiddenLink, hiddenTarget)) {
            negRunVerifier(unlistedSymlinkDir, "symlink")
            negRunFinalizer(unlistedSymlinkDir, "symlink")
        }

        // Case 12: Manifest symlink
        val manifestSymlinkDir = negCopy("manifest-symlink")
        val realManifest = manifestSymlinkDir.resolve("real-manifest.json")
        val manifestFile = manifestSymlinkDir.resolve("manifest.json")
        manifestFile.copyTo(realManifest, overwrite = true)
        manifestFile.delete()
        if (createSymlinkOrSkip(manifestFile, realManifest)) {
            negRunVerifier(manifestSymlinkDir, "symlink")
            negRunFinalizer(manifestSymlinkDir, "symlink")
        }

        // ── Runtime evidence negative fixtures ──

        val rtDir = negDir.resolve("runtime-evidence-fixtures")
        if (rtDir.exists()) rtDir.deleteRecursively()
        rtDir.mkdirs()

        fun writeRtEvidence(bundle: File, filename: String, vararg lines: String) {
            val dir = bundle.resolve("runtime-evidence")
            dir.mkdirs()
            val file = dir.resolve(filename)
            file.writeText(lines.joinToString("\n") + "\n")
        }

        fun createRtNegFixture(name: String): File {
            val target = rtDir.resolve(name)
            if (target.exists()) target.deleteRecursively()
            bundle.copyRecursively(target, overwrite = true)
            return target
        }

        fun negFinalizeRt(bundleDir: File) {
            val p = ProcessBuilder("bash", finalizer.absolutePath, bundleDir.absolutePath)
                .inheritIO().start()
            require(p.waitFor() == 0) { "Finalization failed for ${bundleDir.name}" }
        }

        val validJsonlLine = """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-001","eventType":"policy.decision","workflowRunId":null,"correlationId":null,"actor":null,"createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine","module":"v1"},"decision":{"kind":"ALLOW","reasonCode":"policy_allowed"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"providerName":"ollama"}}"""

        // Case: Malformed JSON line
        val malformedDir = createRtNegFixture("malformed-json-line")
        writeRtEvidence(malformedDir, "policy-decisions.jsonl", "this is not json")
        negFinalizeRt(malformedDir)
        negRunVerifier(malformedDir, "invalid JSON")

        // Case: Blank file (must contain at least one record)
        val blankDir = createRtNegFixture("blank-jsonl-file")
        val blankFile = blankDir.resolve("runtime-evidence/policy-decisions.jsonl")
        blankFile.parentFile.mkdirs()
        blankFile.writeText("")
        negFinalizeRt(blankDir)
        negRunVerifier(blankDir, "must contain at least one record")

        // Case: Wrong schema version
        val badSchemaDir = createRtNegFixture("wrong-schema-version")
        writeRtEvidence(badSchemaDir, "policy-decisions.jsonl",
            """{"schemaVersion":"evidences.v2","eventId":"evt-002","eventType":"policy.decision","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"ALLOW"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"}}"""
        )
        negFinalizeRt(badSchemaDir)
        negRunVerifier(badSchemaDir, "unsupported schemaVersion")

        // Case: Event/file mismatch
        val mismatchDir = createRtNegFixture("event-file-mismatch")
        writeRtEvidence(mismatchDir, "approval-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-003","eventType":"policy.decision","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"ALLOW"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"}}"""
        )
        negFinalizeRt(mismatchDir)
        negRunVerifier(mismatchDir, "does not match expected")

        // Case: Invalid decision kind
        val badKindDir = createRtNegFixture("invalid-decision-kind")
        writeRtEvidence(badKindDir, "policy-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-004","eventType":"policy.decision","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"INVALID_KIND"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"}}"""
        )
        negFinalizeRt(badKindDir)
        negRunVerifier(badKindDir, "unsupported decision.kind")

        // Case: Unknown metadata key
        val badMetaDir = createRtNegFixture("unknown-metadata-key")
        writeRtEvidence(badMetaDir, "policy-decisions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-005","eventType":"policy.decision","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"ALLOW"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"rawPrompt":"this should be rejected"}}"""
        )
        negFinalizeRt(badMetaDir)
        negRunVerifier(badMetaDir, "not allowlisted")

        // Case: Runtime file removed from files[] in manifest
        val missingManifestDir = createRtNegFixture("runtime-file-missing-from-manifest")
        writeRtEvidence(missingManifestDir, "policy-decisions.jsonl", validJsonlLine)
        // Re-finalize (will include the file), then remove it from manifest
        val reFinalProcess = ProcessBuilder("bash", finalizer.absolutePath, missingManifestDir.absolutePath)
            .inheritIO().start()
        require(reFinalProcess.waitFor() == 0) { "Finalization failed for runtime-file-missing-from-manifest" }
        mutateManifest(missingManifestDir,
            """m["files"] = [f for f in m["files"] if f["path"] != "runtime-evidence/policy-decisions.jsonl"]"""
        )
        negRunVerifier(missingManifestDir, "manifest")

        // Case: Unknown JSONL filename
        val unknownFileDir = createRtNegFixture("unknown-runtime-jsonl")
        writeRtEvidence(unknownFileDir, "secret-events.jsonl", validJsonlLine)
        negFinalizeRt(unknownFileDir)
        negRunVerifier(unknownFileDir, "unknown file")

        // ── Tool permission negative fixtures ──

        // Case: tool-permissions.jsonl with invalid decision kind
        val badToolKindDir = createRtNegFixture("tool-permission-invalid-decision")
        writeRtEvidence(badToolKindDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-001","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"REDACT_RESULT","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"HIGH"}}"""
        )
        negFinalizeRt(badToolKindDir)
        negRunVerifier(badToolKindDir, "unsupported decision.kind")

        // Case: tool-permissions.jsonl with missing toolName
        val missingToolNameDir = createRtNegFixture("tool-permission-missing-toolname")
        writeRtEvidence(missingToolNameDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-002","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"HIGH"}}"""
        )
        negFinalizeRt(missingToolNameDir)
        negRunVerifier(missingToolNameDir, "toolName")

        // Case: tool-permissions.jsonl with invalid enforcementPoint
        val badEpDir = createRtNegFixture("tool-permission-bad-enforcementpoint")
        writeRtEvidence(badEpDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-003","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_SOMETHING","riskLevel":"HIGH"}}"""
        )
        negFinalizeRt(badEpDir)
        negRunVerifier(badEpDir, "enforcementPoint")

        // Case: tool-permissions.jsonl with invalid riskLevel
        val badRiskDir = createRtNegFixture("tool-permission-bad-risklevel")
        writeRtEvidence(badRiskDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-004","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"policy-engine"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"ULTRA_HIGH"}}"""
        )
        negFinalizeRt(badRiskDir)
        negRunVerifier(badRiskDir, "riskLevel")

        // Case: tool-permissions.jsonl with wrong source.component
        val badToolSrcDir = createRtNegFixture("tool-permission-wrong-source")
        writeRtEvidence(badToolSrcDir, "tool-permissions.jsonl",
            """{"schemaVersion":"runtime-evidence.v1","eventId":"evt-tool-bad-005","eventType":"tool.permission","createdAt":"2026-07-13T10:00:00Z","source":{"component":"provider-router"},"decision":{"kind":"DENY","reasonCode":"tool_denied"},"digests":{"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000001","payloadDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000002"},"metadata":{"toolName":"payment","enforcementPoint":"BEFORE_TOOL_EXECUTION","riskLevel":"HIGH"}}"""
        )
        negFinalizeRt(badToolSrcDir)
        negRunVerifier(badToolSrcDir, "source.component")

        // Clean up negative fixture directories
        negDir.deleteRecursively()

        // ── Archive export verification ──

        val packager = file("examples/sovereign-lab/package-evidence-bundle.sh")
        require(packager.exists()) {
            "Missing evidence bundle packager at ${packager.absolutePath}"
        }

        // Package the finalized bundle
        val packageProcess = ProcessBuilder("bash", packager.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val packageOutput = packageProcess.inputStream.bufferedReader().readText()
        val packageExitCode = packageProcess.waitFor()
        require(packageExitCode == 0) {
            "Evidence bundle packager failed with code $packageExitCode. Output: $packageOutput"
        }

        val archiveRoot = file("examples/sovereign-lab/build/evidence-archives")
        val archive = archiveRoot.resolve("test-bundle.tar.gz")
        val checksum = archiveRoot.resolve("test-bundle.tar.gz.sha256")

        require(archive.isFile) {
            "Expected evidence bundle archive at ${archive.absolutePath}"
        }
        require(checksum.isFile) {
            "Expected evidence bundle archive checksum at ${checksum.absolutePath}"
        }

        // Verify checksum
        val checksumProcess = ProcessBuilder("sha256sum", "-c", checksum.name)
            .directory(archiveRoot)
            .redirectErrorStream(true)
            .start()
        val checksumOutput = checksumProcess.inputStream.bufferedReader().readText()
        val checksumExitCode = checksumProcess.waitFor()
        require(checksumExitCode == 0) {
            "Evidence bundle archive checksum validation failed. Output: $checksumOutput"
        }

        // Extract and re-verify
        val extractRoot = file("examples/sovereign-lab/build/evidence-archives/extracted")
        if (extractRoot.exists()) extractRoot.deleteRecursively()
        extractRoot.mkdirs()

        val extractProcess = ProcessBuilder(
            "tar", "-xzf", archive.absolutePath, "-C", extractRoot.absolutePath,
        )
            .redirectErrorStream(true)
            .start()
        val extractOutput = extractProcess.inputStream.bufferedReader().readText()
        val extractExitCode = extractProcess.waitFor()
        require(extractExitCode == 0) {
            "Evidence bundle archive extraction failed with code $extractExitCode. Output: $extractOutput"
        }

        val extractedBundle = extractRoot.resolve("test-bundle")
        require(extractedBundle.isDirectory) {
            "Extracted evidence bundle directory not found at ${extractedBundle.absolutePath}"
        }

        val extractedVerify = ProcessBuilder("bash", verifier.absolutePath, extractedBundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val extractedVerifyOutput = extractedVerify.inputStream.bufferedReader().readText()
        val extractedVerifyExitCode = extractedVerify.waitFor()
        require(extractedVerifyExitCode == 0) {
            "Verifier rejected extracted evidence bundle. Output: $extractedVerifyOutput"
        }

        // ── Deterministic archive export regression ──

        fun sha256(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val determinismRoot = archiveRoot.resolve("determinism")
        if (determinismRoot.exists()) determinismRoot.deleteRecursively()
        determinismRoot.mkdirs()

        // First packaging
        val firstPackage = ProcessBuilder("bash", packager.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val firstPackageOutput = firstPackage.inputStream.bufferedReader().readText()
        val firstPackageExitCode = firstPackage.waitFor()
        require(firstPackageExitCode == 0) {
            "First deterministic archive packaging failed. Output: $firstPackageOutput"
        }

        val firstArchive = determinismRoot.resolve("test-bundle-first.tar.gz")
        val firstChecksum = determinismRoot.resolve("test-bundle-first.tar.gz.sha256")
        archive.copyTo(firstArchive, overwrite = true)
        checksum.copyTo(firstChecksum, overwrite = true)

        val firstArchiveSha = sha256(firstArchive)
        val firstChecksumText = firstChecksum.readText()

        // Second packaging
        val secondPackage = ProcessBuilder("bash", packager.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val secondPackageOutput = secondPackage.inputStream.bufferedReader().readText()
        val secondPackageExitCode = secondPackage.waitFor()
        require(secondPackageExitCode == 0) {
            "Second deterministic archive packaging failed. Output: $secondPackageOutput"
        }

        val secondArchive = determinismRoot.resolve("test-bundle-second.tar.gz")
        val secondChecksum = determinismRoot.resolve("test-bundle-second.tar.gz.sha256")
        archive.copyTo(secondArchive, overwrite = true)
        checksum.copyTo(secondChecksum, overwrite = true)

        val secondArchiveSha = sha256(secondArchive)
        val secondChecksumText = secondChecksum.readText()

        require(firstArchiveSha == secondArchiveSha) {
            "Evidence archive export is not deterministic. First SHA-256=$firstArchiveSha, second SHA-256=$secondArchiveSha"
        }

        require(firstChecksumText == secondChecksumText) {
            "Evidence archive checksum sidecar is not deterministic. First=$firstChecksumText Second=$secondChecksumText"
        }

        require(secondChecksumText.startsWith(secondArchiveSha)) {
            "Checksum sidecar does not match archive SHA-256. Sidecar=$secondChecksumText Archive=$secondArchiveSha"
        }

        // ── PR #156: Archive verifier positive test ──

        val archiveVerifier = file("examples/sovereign-lab/verify-evidence-archive.sh")
        require(archiveVerifier.exists()) {
            "Missing evidence archive verifier at ${archiveVerifier.absolutePath}"
        }

        val archiveVerifyProcess = ProcessBuilder("bash", archiveVerifier.absolutePath, archive.absolutePath)
            .redirectErrorStream(true)
            .start()
        val archiveVerifyOutput = archiveVerifyProcess.inputStream.bufferedReader().readText()
        val archiveVerifyExitCode = archiveVerifyProcess.waitFor()

        require(archiveVerifyExitCode == 0) {
            "Evidence archive verifier failed with code $archiveVerifyExitCode. Output: $archiveVerifyOutput"
        }
        require(archiveVerifyOutput.contains("Evidence archive verified:")) {
            "Archive verifier success output missing. Got: $archiveVerifyOutput"
        }

        // ── PR #156: Negative archive fixtures ──

        val archiveNegRoot = archiveRoot.resolve("negative-archives")
        if (archiveNegRoot.exists()) archiveNegRoot.deleteRecursively()
        archiveNegRoot.mkdirs()

        fun runArchiveVerifierExpectFail(archiveFile: File, expected: String) {
            val process = ProcessBuilder("bash", archiveVerifier.absolutePath, archiveFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            require(exitCode != 0) {
                "Expected archive verifier to fail for ${archiveFile.name}, but it passed. Output: $output"
            }
            require(output.contains(expected, ignoreCase = true)) {
                "Expected archive verifier failure to contain '$expected', but output was: $output"
            }
        }

        // Negative 1: Missing checksum sidecar
        val missingChecksumArchive = archiveNegRoot.resolve("missing-checksum.tar.gz")
        archive.copyTo(missingChecksumArchive, overwrite = true)

        runArchiveVerifierExpectFail(missingChecksumArchive, "checksum")

        // Negative 2: Tampered archive
        val tamperedArchive = archiveNegRoot.resolve("tampered.tar.gz")
        val tamperedChecksum = archiveNegRoot.resolve("tampered.tar.gz.sha256")

        archive.copyTo(tamperedArchive, overwrite = true)
        checksum.copyTo(tamperedChecksum, overwrite = true)
        tamperedChecksum.writeText(tamperedChecksum.readText().replace("test-bundle.tar.gz", "tampered.tar.gz"))
        tamperedArchive.appendBytes("tamper".toByteArray())

        runArchiveVerifierExpectFail(tamperedArchive, "checksum mismatch")

        // Negative 3: Unsafe tar entry (path traversal)
        val unsafeArchive = archiveNegRoot.resolve("unsafe-entry.tar.gz")
        val unsafeDir = archiveNegRoot.resolve("unsafe-src")
        if (unsafeDir.exists()) unsafeDir.deleteRecursively()
        unsafeDir.mkdirs()
        unsafeDir.resolve("evil.txt").writeText("evil\n")

        val unsafeCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${unsafeArchive.absolutePath}")
payload = pathlib.Path("${unsafeDir.resolve("evil.txt").absolutePath}")
with tarfile.open(archive, "w:gz") as tar:
    tar.add(payload, arcname="../evil.txt")
"""
        )
            .redirectErrorStream(true)
            .start()
        val unsafeCreateOutput = unsafeCreateProcess.inputStream.bufferedReader().readText()
        val unsafeCreateExit = unsafeCreateProcess.waitFor()
        require(unsafeCreateExit == 0) {
            "Failed to create unsafe archive fixture. Output: $unsafeCreateOutput"
        }

        val unsafeSha = sha256(unsafeArchive)
        unsafeArchive.resolveSibling("${unsafeArchive.name}.sha256")
            .writeText("$unsafeSha  ${unsafeArchive.name}\n")

        runArchiveVerifierExpectFail(unsafeArchive, "safe relative path")

        // Negative 4: Symlink tar entry
        val symlinkArchive = archiveNegRoot.resolve("symlink-entry.tar.gz")
        val symlinkCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${symlinkArchive.absolutePath}")
info = tarfile.TarInfo("test-bundle/link.txt")
info.type = tarfile.SYMTYPE
info.linkname = "target.txt"
with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info)
"""
        )
            .redirectErrorStream(true)
            .start()
        val symlinkCreateOutput = symlinkCreateProcess.inputStream.bufferedReader().readText()
        val symlinkCreateExit = symlinkCreateProcess.waitFor()
        require(symlinkCreateExit == 0) {
            "Failed to create symlink archive fixture. Output: $symlinkCreateOutput"
        }

        val symlinkSha = sha256(symlinkArchive)
        symlinkArchive.resolveSibling("${symlinkArchive.name}.sha256")
            .writeText("$symlinkSha  ${symlinkArchive.name}\n")

        runArchiveVerifierExpectFail(symlinkArchive, "symlink")

        // Negative 5: Sidecar references wrong filename
        val wrongSidecarArchive = archiveNegRoot.resolve("wrong-sidecar-name.tar.gz")
        val wrongSidecar = archiveNegRoot.resolve("wrong-sidecar-name.tar.gz.sha256")
        archive.copyTo(wrongSidecarArchive, overwrite = true)
        val wrongSha = sha256(wrongSidecarArchive)
        wrongSidecar.writeText("$wrongSha  /dev/zero\n")

        runArchiveVerifierExpectFail(wrongSidecarArchive, "must reference")

        // ── PR #157: Expanded negative archive fixtures ──

        fun writeArchiveSidecar(archiveFile: File) {
            archiveFile.resolveSibling("${archiveFile.name}.sha256")
                .writeText("${sha256(archiveFile)}  ${archiveFile.name}\n")
        }

        // Negative 6: Absolute path tar entry
        val absoluteEntryArchive = archiveNegRoot.resolve("absolute-entry.tar.gz")

        val absoluteCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${absoluteEntryArchive.absolutePath}")

# Must use TarInfo directly because tar.add() strips leading slashes
info = tarfile.TarInfo("/evil.txt")
info.type = tarfile.REGTYPE
info.size = 0

with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info)
"""
        )
            .redirectErrorStream(true)
            .start()
        val absoluteCreateOutput = absoluteCreateProcess.inputStream.bufferedReader().readText()
        require(absoluteCreateProcess.waitFor() == 0) {
            "Failed to create absolute-entry archive fixture. Output: $absoluteCreateOutput"
        }

        writeArchiveSidecar(absoluteEntryArchive)
        runArchiveVerifierExpectFail(absoluteEntryArchive, "must not be absolute")

        // Negative 7: Hardlink tar entry
        val hardlinkArchive = archiveNegRoot.resolve("hardlink-entry.tar.gz")

        val hardlinkCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${hardlinkArchive.absolutePath}")
info = tarfile.TarInfo("test-bundle/hardlink.txt")
info.type = tarfile.LNKTYPE
info.linkname = "target.txt"
with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info)
"""
        )
            .redirectErrorStream(true)
            .start()
        val hardlinkCreateOutput = hardlinkCreateProcess.inputStream.bufferedReader().readText()
        require(hardlinkCreateProcess.waitFor() == 0) {
            "Failed to create hardlink archive fixture. Output: $hardlinkCreateOutput"
        }

        writeArchiveSidecar(hardlinkArchive)
        runArchiveVerifierExpectFail(hardlinkArchive, "hardlink")

        // Negative 8: Special file / FIFO tar entry
        val specialFileArchive = archiveNegRoot.resolve("special-file-entry.tar.gz")

        val specialCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${specialFileArchive.absolutePath}")
info = tarfile.TarInfo("test-bundle/fifo")
info.type = tarfile.FIFOTYPE
with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info)
"""
        )
            .redirectErrorStream(true)
            .start()
        val specialCreateOutput = specialCreateProcess.inputStream.bufferedReader().readText()
        require(specialCreateProcess.waitFor() == 0) {
            "Failed to create special-file archive fixture. Output: $specialCreateOutput"
        }

        writeArchiveSidecar(specialFileArchive)
        runArchiveVerifierExpectFail(specialFileArchive, "regular file or directory")

        // Negative 9: Empty archive
        val emptyArchive = archiveNegRoot.resolve("empty-archive.tar.gz")

        val emptyCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${emptyArchive.absolutePath}")
with tarfile.open(archive, "w:gz"):
    pass
"""
        )
            .redirectErrorStream(true)
            .start()
        val emptyCreateOutput = emptyCreateProcess.inputStream.bufferedReader().readText()
        require(emptyCreateProcess.waitFor() == 0) {
            "Failed to create empty archive fixture. Output: $emptyCreateOutput"
        }

        writeArchiveSidecar(emptyArchive)
        runArchiveVerifierExpectFail(emptyArchive, "archive is empty")

        // Negative 10: Multiple top-level directories
        val multiTopArchive = archiveNegRoot.resolve("multi-top-level.tar.gz")
        val multiTopRoot = archiveNegRoot.resolve("multi-top-src")
        if (multiTopRoot.exists()) multiTopRoot.deleteRecursively()
        multiTopRoot.mkdirs()

        val fileA = multiTopRoot.resolve("a.txt")
        val fileB = multiTopRoot.resolve("b.txt")
        fileA.writeText("a\n")
        fileB.writeText("b\n")

        val multiTopCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib
archive = pathlib.Path("${multiTopArchive.absolutePath}")
file_a = pathlib.Path("${fileA.absolutePath}")
file_b = pathlib.Path("${fileB.absolutePath}")
with tarfile.open(archive, "w:gz") as tar:
    tar.add(file_a, arcname="bundle-a/a.txt")
    tar.add(file_b, arcname="bundle-b/b.txt")
"""
        )
            .redirectErrorStream(true)
            .start()
        val multiTopCreateOutput = multiTopCreateProcess.inputStream.bufferedReader().readText()
        require(multiTopCreateProcess.waitFor() == 0) {
            "Failed to create multi-top-level archive fixture. Output: $multiTopCreateOutput"
        }

        writeArchiveSidecar(multiTopArchive)
        runArchiveVerifierExpectFail(multiTopArchive, "exactly one top-level")

        // Negative 11: Invalid sidecar SHA format
        val invalidShaArchive = archiveNegRoot.resolve("invalid-sidecar-sha.tar.gz")
        archive.copyTo(invalidShaArchive, overwrite = true)
        invalidShaArchive.resolveSibling("${invalidShaArchive.name}.sha256")
            .writeText("not-a-sha  ${invalidShaArchive.name}\n")

        runArchiveVerifierExpectFail(invalidShaArchive, "valid SHA-256")

        // Negative 12: Multi-line sidecar
        val multilineSidecarArchive = archiveNegRoot.resolve("multiline-sidecar.tar.gz")
        archive.copyTo(multilineSidecarArchive, overwrite = true)

        val multilineSha = sha256(multilineSidecarArchive)
        multilineSidecarArchive.resolveSibling("${multilineSidecarArchive.name}.sha256")
            .writeText(
                """
                $multilineSha  ${multilineSidecarArchive.name}
                $multilineSha  other.tar.gz
                """.trimIndent() + "\n"
            )

        runArchiveVerifierExpectFail(multilineSidecarArchive, "exactly one line")

        // ── PR #158: Sidecar parser fixtures ──

        fun writeCustomSidecar(archiveFile: File, text: String) {
            archiveFile.resolveSibling("${archiveFile.name}.sha256")
                .writeText(text)
        }

        // Positive: binary-mode sidecar (sha256sum -b)
        val binarySidecarArchive = archiveNegRoot.resolve("binary-sidecar.tar.gz")
        archive.copyTo(binarySidecarArchive, overwrite = true)
        val binarySha = sha256(binarySidecarArchive)
        writeCustomSidecar(binarySidecarArchive, "$binarySha *${binarySidecarArchive.name}\n")

        val binaryProcess = ProcessBuilder("bash", archiveVerifier.absolutePath, binarySidecarArchive.absolutePath)
            .redirectErrorStream(true)
            .start()
        val binaryOutput = binaryProcess.inputStream.bufferedReader().readText()
        val binaryExit = binaryProcess.waitFor()
        require(binaryExit == 0) {
            "Expected binary-mode sidecar to verify, but it failed. Output: $binaryOutput"
        }

        // Negative: extra sidecar field
        val extraFieldSidecarArchive = archiveNegRoot.resolve("extra-field-sidecar.tar.gz")
        archive.copyTo(extraFieldSidecarArchive, overwrite = true)
        val extraFieldSha = sha256(extraFieldSidecarArchive)
        writeCustomSidecar(
            extraFieldSidecarArchive,
            "$extraFieldSha  ${extraFieldSidecarArchive.name} unexpected\n"
        )
        runArchiveVerifierExpectFail(extraFieldSidecarArchive, "exactly a SHA-256 digest and archive filename")

        // Negative: missing filename
        val missingNameSidecarArchive = archiveNegRoot.resolve("missing-name-sidecar.tar.gz")
        archive.copyTo(missingNameSidecarArchive, overwrite = true)
        val missingNameSha = sha256(missingNameSidecarArchive)
        writeCustomSidecar(missingNameSidecarArchive, "$missingNameSha\n")
        runArchiveVerifierExpectFail(missingNameSidecarArchive, "digest and archive filename")

        // Negative: whitespace-only sidecar
        val blankSidecarArchive = archiveNegRoot.resolve("blank-sidecar.tar.gz")
        archive.copyTo(blankSidecarArchive, overwrite = true)
        writeCustomSidecar(blankSidecarArchive, "   \n")
        runArchiveVerifierExpectFail(blankSidecarArchive, "digest and archive filename")

        // Positive: sidecar without trailing newline
        val noTrailingNewlineArchive = archiveNegRoot.resolve("no-trailing-newline-sidecar.tar.gz")
        archive.copyTo(noTrailingNewlineArchive, overwrite = true)
        val noTrailingNewlineSha = sha256(noTrailingNewlineArchive)
        writeCustomSidecar(
            noTrailingNewlineArchive,
            "$noTrailingNewlineSha  ${noTrailingNewlineArchive.name}"
        )
        val noTrailingNewlineProcess = ProcessBuilder("bash", archiveVerifier.absolutePath, noTrailingNewlineArchive.absolutePath)
            .redirectErrorStream(true)
            .start()
        val noTrailingNewlineOutput = noTrailingNewlineProcess.inputStream.bufferedReader().readText()
        val noTrailingNewlineExit = noTrailingNewlineProcess.waitFor()
        require(noTrailingNewlineExit == 0) {
            "Expected sidecar without trailing newline to verify, but it failed. Output: $noTrailingNewlineOutput"
        }

        // Negative: two lines, second line has no trailing newline
        val multilineNoFinalNewlineArchive = archiveNegRoot.resolve("multiline-no-final-newline-sidecar.tar.gz")
        archive.copyTo(multilineNoFinalNewlineArchive, overwrite = true)
        val multilineNoFinalNewlineSha = sha256(multilineNoFinalNewlineArchive)
        writeCustomSidecar(
            multilineNoFinalNewlineArchive,
            "$multilineNoFinalNewlineSha  ${multilineNoFinalNewlineArchive.name}\n$multilineNoFinalNewlineSha  other.tar.gz"
        )
        runArchiveVerifierExpectFail(multilineNoFinalNewlineArchive, "exactly one line")

        // ── PR #159: Top-level file rejection ──

        val topLevelFileArchive = archiveNegRoot.resolve("top-level-file.tar.gz")

        val topLevelFileCreateProcess = ProcessBuilder(
            "python3", "-c", """
import tarfile, pathlib, io
archive = pathlib.Path("${topLevelFileArchive.absolutePath}")
payload = b"not a bundle directory\\n"
info = tarfile.TarInfo("bundle.txt")
info.type = tarfile.REGTYPE
info.size = len(payload)
with tarfile.open(archive, "w:gz") as tar:
    tar.addfile(info, io.BytesIO(payload))
"""
        )
            .redirectErrorStream(true)
            .start()
        val topLevelFileCreateOutput = topLevelFileCreateProcess.inputStream.bufferedReader().readText()
        require(topLevelFileCreateProcess.waitFor() == 0) {
            "Failed to create top-level-file archive fixture. Output: $topLevelFileCreateOutput"
        }
        writeArchiveSidecar(topLevelFileArchive)
        runArchiveVerifierExpectFail(topLevelFileArchive, "top-level entry must be a directory")

        // ── PR #161: Optional archive signature verifier ──

        val signatureVerifier = file("examples/sovereign-lab/verify-evidence-archive-signature.sh")
        require(signatureVerifier.exists()) {
            "Missing evidence archive signature verifier at ${signatureVerifier.absolutePath}"
        }

        val sigArchiveRoot = archiveRoot.resolve("signature-tests")
        if (sigArchiveRoot.exists()) sigArchiveRoot.deleteRecursively()
        sigArchiveRoot.mkdirs()

        // Helper: generate ephemeral RSA keypair for fixture testing
        fun generateKeypair(dir: File): Pair<File, File> {
            dir.mkdirs()
            val privateKey = dir.resolve("fixture-key.pem")
            val publicKey = dir.resolve("fixture-key.pub")
            val genProcess = ProcessBuilder(
                "openssl", "genpkey",
                "-algorithm", "RSA",
                "-pkeyopt", "rsa_keygen_bits:2048",
                "-outform", "PEM",
                "-out", privateKey.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val genOutput = genProcess.inputStream.bufferedReader().readText()
            require(genProcess.waitFor() == 0) {
                "Failed to generate ephemeral signing key. Output: $genOutput"
            }

            val pubProcess = ProcessBuilder(
                "openssl", "rsa",
                "-pubout",
                "-in", privateKey.absolutePath,
                "-outform", "PEM",
                "-out", publicKey.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val pubOutput = pubProcess.inputStream.bufferedReader().readText()
            require(pubProcess.waitFor() == 0) {
                "Failed to extract public key. Output: $pubOutput"
            }

            return Pair(privateKey, publicKey)
        }

        // Helper: sign a checksum sidecar
        fun signChecksum(checksumFile: File, privateKey: File, signatureFile: File) {
            val signProcess = ProcessBuilder(
                "openssl", "dgst", "-sha256",
                "-sign", privateKey.absolutePath,
                "-out", signatureFile.absolutePath,
                checksumFile.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val signOutput = signProcess.inputStream.bufferedReader().readText()
            require(signProcess.waitFor() == 0) {
                "Failed to sign checksum sidecar. Output: $signOutput"
            }
        }

        fun runSignatureVerifierExpectFail(
            archiveFile: File,
            publicKey: File,
            expected: String,
        ) {
            val process = ProcessBuilder(
                "bash", signatureVerifier.absolutePath,
                archiveFile.absolutePath, publicKey.absolutePath,
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            require(exitCode != 0) {
                "Expected signature verifier to fail for ${archiveFile.name}, but it passed. Output: $output"
            }
            require(output.contains(expected, ignoreCase = true)) {
                "Expected signature verifier failure to contain '$expected', but output was: $output"
            }
        }

        // Re-package the finalized bundle into a fresh archive for signature tests
        val sigPackageProcess = ProcessBuilder("bash", packager.absolutePath, bundle.absolutePath)
            .redirectErrorStream(true)
            .start()
        val sigPackageOutput = sigPackageProcess.inputStream.bufferedReader().readText()
        require(sigPackageProcess.waitFor() == 0) {
            "Repackaging for signature tests failed. Output: $sigPackageOutput"
        }

        val sigArchive = archiveRoot.resolve("test-bundle.tar.gz")
        val sigChecksum = archiveRoot.resolve("test-bundle.tar.gz.sha256")
        require(sigArchive.isFile && sigChecksum.isFile) {
            "Re-packaged archive or checksum missing for signature tests."
        }

        // Copy archive + checksum to fixture dir so we don't mutate the originals
        val sigArchiveCopy = sigArchiveRoot.resolve("test-bundle.tar.gz")
        val sigChecksumCopy = sigArchiveRoot.resolve("test-bundle.tar.gz.sha256")
        sigArchive.copyTo(sigArchiveCopy, overwrite = true)
        sigChecksum.copyTo(sigChecksumCopy, overwrite = true)

        // Generate ephemeral keypair
        val (sigPrivateKey, sigPublicKey) = generateKeypair(sigArchiveRoot)

        // Sign the checksum sidecar
        val sigSigFile = sigArchiveRoot.resolve("test-bundle.tar.gz.sha256.sig")
        signChecksum(sigChecksumCopy, sigPrivateKey, sigSigFile)

        // Positive: valid signature + archive verification
        val positiveSigProcess = ProcessBuilder(
            "bash", signatureVerifier.absolutePath,
            sigArchiveCopy.absolutePath, sigPublicKey.absolutePath,
        )
            .redirectErrorStream(true)
            .start()
        val positiveSigOutput = positiveSigProcess.inputStream.bufferedReader().readText()
        val positiveSigExitCode = positiveSigProcess.waitFor()
        require(positiveSigExitCode == 0) {
            "Expected signature verifier to pass for valid signature. Output: $positiveSigOutput"
        }
        require(positiveSigOutput.contains("Evidence archive signature verified:")) {
            "Signature verifier success output missing. Got: $positiveSigOutput"
        }

        // Negative 1: Missing .sha256.sig
        val noSigArchive = sigArchiveRoot.resolve("no-sig.tar.gz")
        val noSigChecksum = sigArchiveRoot.resolve("no-sig.tar.gz.sha256")
        sigArchiveCopy.copyTo(noSigArchive, overwrite = true)
        sigChecksumCopy.copyTo(noSigChecksum, overwrite = true)
        runSignatureVerifierExpectFail(noSigArchive, sigPublicKey, "missing")

        // Negative 2: Tampered checksum sidecar after signing
        val tamperedSigArchive = sigArchiveRoot.resolve("tampered-sidecar.tar.gz")
        val tamperedSigChecksum = sigArchiveRoot.resolve("tampered-sidecar.tar.gz.sha256")
        val tamperedSigSig = sigArchiveRoot.resolve("tampered-sidecar.tar.gz.sha256.sig")
        sigArchiveCopy.copyTo(tamperedSigArchive, overwrite = true)
        sigChecksumCopy.copyTo(tamperedSigChecksum, overwrite = true)
        signChecksum(tamperedSigChecksum, sigPrivateKey, tamperedSigSig)
        // Tamper the sidecar after signing
        tamperedSigChecksum.appendText("tamper\n")
        runSignatureVerifierExpectFail(tamperedSigArchive, sigPublicKey, "FAILED")

        // Negative 3: Wrong public key
        val wrongKeyArchive = sigArchiveRoot.resolve("wrong-key.tar.gz")
        val wrongKeyChecksum = sigArchiveRoot.resolve("wrong-key.tar.gz.sha256")
        val wrongKeySig = sigArchiveRoot.resolve("wrong-key.tar.gz.sha256.sig")
        sigArchiveCopy.copyTo(wrongKeyArchive, overwrite = true)
        sigChecksumCopy.copyTo(wrongKeyChecksum, overwrite = true)
        signChecksum(wrongKeyChecksum, sigPrivateKey, wrongKeySig)
        val (_, wrongPublicKey) = generateKeypair(sigArchiveRoot.resolve("wrong-key-keys"))
        runSignatureVerifierExpectFail(wrongKeyArchive, wrongPublicKey, "FAILED")

        // Negative 4: Tampered archive after valid signature
        // Proves the script chains into verify-evidence-archive.sh after signature verification
        val tamperedArchiveSig = sigArchiveRoot.resolve("tampered-archive.tar.gz")
        val tamperedArchiveChecksum = sigArchiveRoot.resolve("tampered-archive.tar.gz.sha256")
        val tamperedArchiveSigFile = sigArchiveRoot.resolve("tampered-archive.tar.gz.sha256.sig")
        sigArchiveCopy.copyTo(tamperedArchiveSig, overwrite = true)
        // Write a proper sidecar referencing the tampered archive filename
        val tamperedSha = sha256(tamperedArchiveSig)
        tamperedArchiveChecksum.writeText("$tamperedSha  tampered-archive.tar.gz\n")
        signChecksum(tamperedArchiveChecksum, sigPrivateKey, tamperedArchiveSigFile)
        // Tamper the archive content after signature creation
        tamperedArchiveSig.appendBytes("tamper".toByteArray())
        // Signature was over the original checksum; archive is now different.
        // openssl verifies the signature (valid for the signed checksum),
        // then archive verifier rejects because the archive doesn't match the checksum.
        runSignatureVerifierExpectFail(tamperedArchiveSig, sigPublicKey, "checksum mismatch")

        // Negative 5: Missing public key (non-existent file)
        val missingKeyArchive = sigArchiveRoot.resolve("missing-key.tar.gz")
        val missingKeyChecksum = sigArchiveRoot.resolve("missing-key.tar.gz.sha256")
        val missingKeySig = sigArchiveRoot.resolve("missing-key.tar.gz.sha256.sig")
        sigArchiveCopy.copyTo(missingKeyArchive, overwrite = true)
        sigChecksumCopy.copyTo(missingKeyChecksum, overwrite = true)
        signChecksum(missingKeyChecksum, sigPrivateKey, missingKeySig)
        val nonexistentKey = sigArchiveRoot.resolve("nonexistent.pem")
        val missingKeyProcess = ProcessBuilder(
            "bash", signatureVerifier.absolutePath,
            missingKeyArchive.absolutePath, nonexistentKey.absolutePath,
        )
            .redirectErrorStream(true)
            .start()
        val missingKeyOutput = missingKeyProcess.inputStream.bufferedReader().readText()
        val missingKeyExitCode = missingKeyProcess.waitFor()
        require(missingKeyExitCode != 0) {
            "Expected signature verifier to fail for missing public key. Output: $missingKeyOutput"
        }
        require(missingKeyOutput.contains("Public key must be a readable regular file", ignoreCase = true)) {
            "Expected missing public key error, but got: $missingKeyOutput"
        }

        logger.lifecycle("verifySovereignLabEvidenceBundle: generated bundle verified at ${bundle.absolutePath}")
    }
}

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
