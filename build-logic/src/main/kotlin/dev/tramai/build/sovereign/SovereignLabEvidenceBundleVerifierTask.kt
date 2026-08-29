package dev.tramai.build.sovereign

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Typed CC-safe replacement for the 1396-line verifySovereignLabEvidenceBundle
 * doLast closure in root build.gradle.kts (Epic 9.2d-a3b2b). Thin by design:
 * it only assembles the [EvidenceScripts] input set and delegates the frozen
 * scenario sequence to [EvidenceBundleScenarioRunner]. Task name,
 * dependsOn("verifySovereignLabProfile") and all observable behavior are
 * unchanged.
 *
 * The 6 scripts are declared as exact [InputFiles] (never a directory, #333
 * discipline). They are marked [Optional] deliberately: the runner fails
 * closed with its own historical require() diagnostics ("Missing evidence
 * bundle script at ..."), and non-optional input validation would replace
 * those messages with Gradle's generic file-not-found error.
 *
 * The generated bundles under examples/sovereign-lab/build are working state,
 * not repository inputs — declared [Internal].
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class SovereignLabEvidenceBundleVerifierTask : DefaultTask() {

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val createScript: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val verifierScript: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val finalizerScript: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packagerScript: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val archiveVerifierScript: RegularFileProperty

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val signatureVerifierScript: RegularFileProperty

    /** examples/sovereign-lab/build — the scripts define the bundle path contract. */
    @get:Internal
    abstract val workingDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val scripts = EvidenceScripts(
            create = createScript.get().asFile,
            verifier = verifierScript.get().asFile,
            finalizer = finalizerScript.get().asFile,
            packager = packagerScript.get().asFile,
            archiveVerifier = archiveVerifierScript.get().asFile,
            signatureVerifier = signatureVerifierScript.get().asFile,
        )
        EvidenceBundleScenarioRunner(
            scripts = scripts,
            workDir = workingDirectory.get().asFile,
            adapter = ProcessBuilderProcessAdapter(),
            log = { logger.lifecycle(it) },
        ).run()
    }
}
