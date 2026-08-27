package dev.tramai.build.sovereign

import dev.tramai.build.release.ReleaseManifestVerifier
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Verifies that build/sovereign-release/release-artifacts-v1.json is
 * internally consistent with the JAR files in build/sovereign-release/artifacts/
 * (9.2b extraction). Verification-only — no output artifact, build caching
 * intentionally disabled.
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class VerifySovereignReleaseManifestTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifactsDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        ReleaseManifestVerifier.verify(
            manifestDir = manifestFile.get().asFile.parentFile,
            artifactsDir = artifactsDirectory.get().asFile,
        )
    }
}
