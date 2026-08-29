package dev.tramai.build.sovereign

import dev.tramai.build.release.ReleaseManifestVerifier
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Typed CC-safe replacements for the five root build.gradle.kts lab/evidence
 * closures (Epic 9.2d-a3b2a). Each task declares the EXACT files it reads as
 * [InputFiles] (never a directory), has no project access at execution, and
 * preserves the historical diagnostics byte-for-byte (every require() message
 * and the failure order are unchanged).
 *
 * Missing-file inputs are marked [Optional] deliberately: the historical
 * closures fail closed with their OWN require() diagnostics (e.g.
 * "sovereign-evidence-missing-evidence-pack"), and non-optional input
 * validation would replace those messages with Gradle's generic
 * "file does not exist" error.
 */

/**
 * Verifies the physical sovereign lab profile and documentation exist
 * (examples/spring-sovereign-starter application-sovereign-lab.yml +
 * examples/sovereign-lab content). Was a C3 configuration-cache offender as a
 * doLast closure in the root build script.
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class SovereignLabProfileVerifierTask : DefaultTask() {

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val labProfileFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val labReadmeFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val evidenceFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val benchmarkTemplateFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val evidenceBundleScriptFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestTemplateFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val commandLogTemplateFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val finalizeScriptFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseReadinessFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reviewerGuideFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val evidenceChainFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packagerScriptFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archiveVerifierScriptFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archiveSigningDocFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val labProfile = labProfileFile.get().asFile
        require(labProfile.exists()) {
            "Missing sovereign lab Spring profile at ${labProfile.absolutePath}"
        }

        val labReadme = labReadmeFile.get().asFile
        require(labReadme.exists()) {
            "Missing sovereign lab README at ${labReadme.absolutePath}"
        }

        val labProfileText = labProfile.readText()

        // YAML root must be exactly one 'tramai:' key
        require(Regex("^tramai:", RegexOption.MULTILINE).findAll(labProfileText).count() == 1) {
            "Sovereign lab profile must define the 'tramai:' root key exactly once. " +
                "Found ${Regex("^tramai:", RegexOption.MULTILINE).findAll(labProfileText).count()}. Duplicate root keys are not valid YAML."
        }

        require(labProfileText.contains("  sovereign:")) {
            "Sovereign lab profile must include tramai.sovereign configuration."
        }

        require(labProfileText.contains("  providers:")) {
            "Sovereign lab profile must include tramai.providers configuration under the same tramai root."
        }

        val labText = labReadme.readText()
        require(labText.contains("local model", ignoreCase = true)) {
            "Sovereign lab README must explain local model setup."
        }
        require(labText.contains("PostgreSQL", ignoreCase = true)) {
            "Sovereign lab README must explain PostgreSQL setup."
        }
        require(labText.contains("no cloud", ignoreCase = true) || labText.contains("zero egress", ignoreCase = true)) {
            "Sovereign lab README must explain no-cloud / zero-egress intent."
        }
        require(labText.contains("[EVIDENCE.md]")) {
            "Sovereign lab README must link to the evidence capture guide (EVIDENCE.md)."
        }

        val evidence = evidenceFile.get().asFile
        require(evidence.exists()) {
            "Missing sovereign lab evidence capture guide at ${evidence.absolutePath}"
        }
        val evidenceText = evidence.readText()
        require(evidenceText.contains("verifySovereignLabLocalModel")) {
            "Sovereign lab evidence guide must reference verifySovereignLabLocalModel."
        }
        require(evidenceText.contains("local-lab-provider")) {
            "Sovereign lab evidence guide must reference local-lab-provider."
        }
        require(evidenceText.contains("TRAMAI_LOCAL_BASE_URL")) {
            "Sovereign lab evidence guide must reference TRAMAI_LOCAL_BASE_URL."
        }
        require(evidenceText.contains("SuspendedForApproval")) {
            "Sovereign lab evidence guide must reference SuspendedForApproval."
        }
        require(evidenceText.contains("restart", ignoreCase = true)) {
            "Sovereign lab evidence guide must explain restart durability proof."
        }
        require(
            evidenceText.contains("no cloud", ignoreCase = true) ||
            evidenceText.contains("zero egress", ignoreCase = true) ||
            evidenceText.contains("No Cloud", ignoreCase = true),
        ) {
            "Sovereign lab evidence guide must explain no-cloud / zero-egress proof."
        }

        // ── PR #141+: Local model benchmark documentation guard ──

        require(evidenceText.contains("benchmarkSovereignLabLocalModel")) {
            "Sovereign lab evidence guide must reference benchmarkSovereignLabLocalModel."
        }
        require(evidenceText.contains("TRAMAI_ENABLE_LOCAL_MODEL_BENCHMARK")) {
            "Sovereign lab evidence guide must document the benchmark opt-in gate."
        }
        require(
            evidenceText.contains("does not define production performance thresholds", ignoreCase = true) ||
            evidenceText.contains("does not define production performance", ignoreCase = true),
        ) {
            "Sovereign lab evidence guide must state the benchmark is diagnostic, not a production threshold."
        }

        val benchmarkTemplate = benchmarkTemplateFile.get().asFile
        require(benchmarkTemplate.exists()) {
            "Missing sovereign lab benchmark evidence template at ${benchmarkTemplate.absolutePath}"
        }

        // ── PR #143: Evidence bundle scaffold guards ──

        require(evidenceText.contains("create-evidence-bundle.sh")) {
            "Sovereign lab evidence guide must document evidence bundle creation."
        }

        val evidenceBundleScript = evidenceBundleScriptFile.get().asFile
        require(evidenceBundleScript.exists()) {
            "Missing sovereign lab evidence bundle helper script at ${evidenceBundleScript.absolutePath}"
        }
        val bundleScriptText = evidenceBundleScript.readText()
        require(
            bundleScriptText.contains("does not certify", ignoreCase = true) ||
            bundleScriptText.contains("does not define production", ignoreCase = true),
        ) {
            "Evidence bundle helper must avoid implying certification or production guarantees."
        }

        val manifestTemplate = manifestTemplateFile.get().asFile
        require(manifestTemplate.exists()) {
            "Missing sovereign lab evidence bundle manifest template at ${manifestTemplate.absolutePath}"
        }

        val commandLogTemplate = commandLogTemplateFile.get().asFile
        require(commandLogTemplate.exists()) {
            "Missing sovereign lab command log evidence template at ${commandLogTemplate.absolutePath}"
        }

        // ── PR #148: Finalization script and doc guard ──

        require(evidenceText.contains("finalize-evidence-bundle.sh")) {
            "Sovereign lab evidence guide must document evidence bundle finalization."
        }

        val finalizeScript = finalizeScriptFile.get().asFile
        require(finalizeScript.exists()) {
            "Missing sovereign lab evidence bundle finalizer script at ${finalizeScript.absolutePath}"
        }

        // ── PR #149: Release readiness checklist guard ──

        val releaseReadiness = releaseReadinessFile.get().asFile
        require(releaseReadiness.exists()) {
            "Missing sovereign lab release readiness checklist at ${releaseReadiness.absolutePath}"
        }

        val releaseReadinessText = releaseReadiness.readText()
        listOf(
            "verifySovereignRuntimeReleaseCandidate",
            "verifySovereignLabProfile",
            "verifySovereignLabRuntimeSmoke",
            "verifySovereignLabEvidenceBundle",
            "finalize-evidence-bundle.sh",
            "verify-evidence-bundle.sh",
            "certifiesProductionReadiness",
            "definesPerformanceGuarantees",
            "validatesEvidenceTruth",
            "Forbidden Claims",
            "Release Candidate Blockers",
        ).forEach { required ->
            require(releaseReadinessText.contains(required)) {
                "Sovereign lab release readiness checklist must mention $required."
            }
        }

        // ── PR #150: Reviewer guide guard ──

        val reviewerGuide = reviewerGuideFile.get().asFile
        require(reviewerGuide.exists()) {
            "Missing sovereign lab evidence reviewer guide at ${reviewerGuide.absolutePath}"
        }

        val reviewerGuideText = reviewerGuide.readText()
        listOf(
            "verify-evidence-bundle.sh",
            "manifest.json",
            "finalizedUtc",
            "claimBoundary",
            "files[]",
            "SHA-256",
            "certifiesProductionReadiness",
            "validatesEvidenceTruth",
            "What Verification Does Not Check",
            "Safe Reviewer Statement",
        ).forEach { required ->
            require(reviewerGuideText.contains(required)) {
                "Sovereign lab reviewer guide must mention $required."
            }
        }

        require(reviewerGuideText.contains("does not certify production readiness")) {
            "Reviewer guide must avoid production-readiness overclaims."
        }

        // ── PR #162: Signature handoff reviewer guard ──

        listOf(
            "verify-evidence-archive-signature.sh",
            ".tar.gz.sha256.sig",
            "reviewer-public-key.pem",
            "does **not**:",
            "prove operator identity beyond the key trust model",
            "prove evidence truth",
            "prove legal compliance",
            "certify production readiness",
        ).forEach { required ->
            require(reviewerGuideText.contains(required)) {
                "Sovereign lab reviewer guide must mention signature verifier handoff: $required."
            }
        }

        // ── PR #162: Signature handoff release-readiness guard ──

        val handoffReadinessText = releaseReadinessFile.get().asFile.readText()
        listOf(
            "verify-evidence-archive-signature.sh",
            "detached signature",
            "optional provenance evidence",
            "not certify production readiness",
            "or replace an audit",
        ).forEach { required ->
            require(handoffReadinessText.contains(required)) {
                "Sovereign lab release readiness checklist must mention signature handoff: $required."
            }
        }

        // ── PR #162: Signature handoff evidence-chain guard ──

        val handoffEvidenceChainText = evidenceChainFile.get().asFile.readText()
        listOf(
            ".tar.gz.sha256.sig",
            "verify-evidence-archive-signature.sh",
            "caller-supplied public key",
        ).forEach { required ->
            require(handoffEvidenceChainText.contains(required)) {
                "Sovereign lab evidence chain overview must mention signature artifacts: $required."
            }
        }

        // ── PR #152: Packager guard ──

        val packagerScript = packagerScriptFile.get().asFile
        require(packagerScript.exists()) {
            "Missing sovereign lab evidence bundle packager at ${packagerScript.absolutePath}"
        }

        val labReadmeText = labReadme.readText()
        listOf(
            "package-evidence-bundle.sh",
            ".tar.gz",
            ".tar.gz.sha256",
            "sha256sum -c",
            "does not sign",
            "does not certify",
            "verify-evidence-bundle.sh",
        ).forEach { required ->
            require(
                labReadmeText.contains(required) ||
                    evidenceText.contains(required) ||
                    reviewerGuideText.contains(required)
            ) {
                "Sovereign lab archive export docs must mention $required."
            }
        }

        // ── PR #153: Evidence chain overview guard ──

        val evidenceChain = evidenceChainFile.get().asFile
        require(evidenceChain.exists()) {
            "Missing sovereign lab evidence chain overview at ${evidenceChain.absolutePath}"
        }

        val evidenceChainText = evidenceChain.readText()
        listOf(
            "create → export runtime records → write runtime-evidence → fill → finalize → verify → readiness → review → package → extract → re-verify",
            "create-evidence-bundle.sh",
            "finalize-evidence-bundle.sh",
            "verify-evidence-bundle.sh",
            "package-evidence-bundle.sh",
            "RELEASE-READINESS.md",
            "REVIEWER-GUIDE.md",
            "claimBoundary",
            "certifiesProductionReadiness",
            "validatesEvidenceTruth",
            "does not certify production readiness",
        ).forEach { required ->
            require(evidenceChainText.contains(required)) {
                "Sovereign lab evidence chain overview must mention $required."
            }
        }

        // ── PR #156: Archive verifier guard ──

        val archiveVerifierScript = archiveVerifierScriptFile.get().asFile
        require(archiveVerifierScript.exists()) {
            "Missing sovereign lab evidence archive verifier at ${archiveVerifierScript.absolutePath}"
        }

        val readinessText = releaseReadiness.readText()
        listOf(
            "verify-evidence-archive.sh",
            "SHA-256 sidecar",
            "temporary directory",
            "unsafe archive entries",
            "verify-evidence-bundle.sh",
            "does not sign",
            "does not certify",
        ).forEach { required ->
            require(
                labReadmeText.contains(required) ||
                    evidenceChainText.contains(required) ||
                    reviewerGuideText.contains(required) ||
                    readinessText.contains(required)
            ) {
                "Sovereign lab archive verifier docs must mention $required."
            }
        }

        // ── PR #160: Archive signing boundary guard ──

        val archiveSigningDoc = archiveSigningDocFile.get().asFile
        require(archiveSigningDoc.exists()) {
            "Missing sovereign lab archive signing boundary doc at ${archiveSigningDoc.absolutePath}"
        }

        val archiveSigningText = archiveSigningDoc.readText()
        listOf(
            "checksum sidecar",
            "transfer integrity",
            "signer identity",
            "operator identity",
            "regulatory compliance",
            "production readiness",
            "future optional archive signing",
        ).forEach { required ->
            require(archiveSigningText.contains(required)) {
                "Sovereign lab archive signing boundary doc must mention $required."
            }
        }

        require(evidenceChainText.contains("ARCHIVE-SIGNING.md")) {
            "Sovereign lab evidence chain overview must reference ARCHIVE-SIGNING.md."
        }

        logger.lifecycle("verifySovereignLabProfile: all sovereign lab profile checks passed.")
    }
}

/**
 * Assembles all sovereign audit outputs into build/sovereign-evidence/.
 * Copy-assembly task: exact input files declared as [InputFiles], output
 * bundle as [OutputDirectory]. Diagnostics byte-identical to the historical
 * root closure (sovereign-evidence-missing-* require messages).
 */
abstract class PrepareSovereignEvidenceBundleTask : DefaultTask() {

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val evidencePackFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val zeroEgressReportFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sbomFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sbomDigestFile: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releaseManifestFile: RegularFileProperty

    /** Release artifacts directory — used for the require() diagnostics and as the jar-fileTree base. */
    @get:Internal
    abstract val releaseArtifactsDirectory: DirectoryProperty

    /** Exact release JAR files (never the whole directory — #333 lesson). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifactsJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun assemble() {
        val outputDir = outputDirectory.get().asFile
        val supplyChainDir = outputDir.resolve("supply-chain")
        val releaseDir = outputDir.resolve("release")
        val releaseArtifactsDir = outputDir.resolve("release/artifacts")

        // Required input paths
        val evidencePack = evidencePackFile.get().asFile
        val zeroEgressReport = zeroEgressReportFile.get().asFile
        val sbom = sbomFile.get().asFile
        val sbomDigest = sbomDigestFile.get().asFile
        val releaseManifest = releaseManifestFile.get().asFile
        val releaseArtifactsSrc = releaseArtifactsDirectory.get().asFile

        // Fail closed on missing inputs
        require(evidencePack.exists()) { "sovereign-evidence-missing-evidence-pack" }
        require(zeroEgressReport.exists()) { "sovereign-evidence-missing-zero-egress-report" }
        require(sbom.exists()) { "sovereign-evidence-missing-sbom" }
        require(sbomDigest.exists()) { "sovereign-evidence-missing-sbom-digest" }
        require(releaseManifest.exists()) { "sovereign-evidence-missing-release-manifest" }
        require(releaseArtifactsSrc.isDirectory()) { "sovereign-evidence-missing-release-artifacts-dir" }
        val jarFiles = releaseArtifactsSrc.listFiles { f -> f.name.endsWith(".jar") }?.toList() ?: emptyList()
        require(jarFiles.isNotEmpty()) { "sovereign-evidence-empty-release-artifacts-dir" }

        // Clean output
        if (outputDir.exists()) outputDir.deleteRecursively()
        supplyChainDir.mkdirs()
        releaseDir.mkdirs()
        releaseArtifactsDir.mkdirs()

        // Copy files
        evidencePack.copyTo(outputDir.resolve("sovereign-evidence-pack-v1.json"), overwrite = true)
        zeroEgressReport.copyTo(outputDir.resolve("zero-egress-report.json"), overwrite = true)
        sbom.copyTo(supplyChainDir.resolve("tramai-cyclonedx-sbom.json"), overwrite = true)
        sbomDigest.copyTo(supplyChainDir.resolve("tramai-cyclonedx-sbom.sha256"), overwrite = true)
        releaseManifest.copyTo(releaseDir.resolve("release-artifacts-v1.json"), overwrite = true)

        // Copy JARs in deterministic filename order
        jarFiles.sortedBy { it.name }.forEach { jar ->
            jar.copyTo(releaseArtifactsDir.resolve(jar.name), overwrite = true)
        }

        logger.lifecycle("Sovereign evidence bundle assembled: ${outputDir.absolutePath}")
        logger.lifecycle("  Files: ${outputDir.walkTopDown().count { it.isFile }}")
    }
}

/**
 * Verifies that build/sovereign-evidence/release/release-artifacts-v1.json is
 * internally consistent with the JAR files in build/sovereign-evidence/release/artifacts/.
 * Thin composition over the pure build-logic verifier (9.2b extraction).
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class VerifySovereignEvidenceBundleReleaseManifestTask : DefaultTask() {

    /** build/sovereign-evidence/release — passed to the verifier as manifestDir. */
    @get:Internal
    abstract val bundleReleaseDirectory: DirectoryProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    /** Exact JAR files in build/sovereign-evidence/release/artifacts. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifactJars: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val manifestDir = bundleReleaseDirectory.get().asFile
        val artifactsDir = manifestDir.resolve("artifacts")
        ReleaseManifestVerifier.verify(manifestDir, artifactsDir)
    }
}

/**
 * Verifies that build/zero-egress-report/sovereign-evidence-pack-v1.json
 * contains releaseBundle (present and non-null).
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class VerifySovereignEvidencePackContainsReleaseBundleTask : DefaultTask() {

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val evidencePackFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val evidencePackPath = evidencePackFile.get().asFile

        require(evidencePackPath.exists()) {
            "sovereign-evidence-pack-missing: ${evidencePackPath.absolutePath}"
        }

        val text = evidencePackPath.readText()
        val hasReleaseBundle = text.contains("\"releaseBundle\":") &&
            !text.contains("\"releaseBundle\": null") &&
            !text.contains("\"releaseBundle\": null,")

        require(hasReleaseBundle) {
            "sovereign-evidence-pack-missing-release-bundle: ${evidencePackPath.absolutePath}"
        }

        logger.lifecycle("Evidence pack contains releaseBundle: ${evidencePackPath.absolutePath}")
    }
}
