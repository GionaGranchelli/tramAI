package dev.tramai.build.release

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Verifies presence and contents of mandatory 0.6.0 release artifacts (Epic 12.4a).
 */
@DisableCachingByDefault(because = "Release required files verification validates file existence and release markers")
abstract class VerifyReleaseRequiredFilesTask : DefaultTask() {
    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Internal
    abstract val rootDir: Property<File>

    @TaskAction
    fun verify() {
        val root = rootDir.get()
        val version = expectedVersion.get()

        val requiredFiles =
            listOf(
                "CHANGELOG.md" to listOf("## $version", "### Added"),
                "docs/releases/$version-release-readiness.md" to listOf("READY_FOR_0.6.0_RELEASE", version),
                "docs/releases/$version-release-notes.md" to listOf(version, "Release Notes"),
                "docs/releases/$version-migration-guide.md" to listOf(version, "Migration Guide"),
            )

        for ((relPath, requiredTokens) in requiredFiles) {
            val file = File(root, relPath)
            if (!file.isFile) {
                throw GradleException(
                    "verifyReleaseRequiredFiles: Required release file missing: $relPath " +
                        "(expected at ${file.absolutePath})",
                )
            }
            val content = file.readText(Charsets.UTF_8)
            for (token in requiredTokens) {
                if (!content.contains(token)) {
                    throw GradleException(
                        "verifyReleaseRequiredFiles: $relPath must contain required release marker '$token'",
                    )
                }
            }
        }

        logger.lifecycle(
            "verifyReleaseRequiredFiles: verified presence and markers for all " +
                "${requiredFiles.size} 0.6.0 release documents.",
        )
    }
}
