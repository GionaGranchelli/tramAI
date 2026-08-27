package dev.tramai.build.release

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Typed release verification task: publishes to a configured file-based Maven
 * repository (via task dependencies wired by the plugin) and verifies the
 * generated signature files (9.2b extraction).
 *
 * Security: this task only supports file:// repositories for local verification
 * and rejects non-file URLs before inspecting anything. It never holds the
 * signing key material — only boolean presence flags computed by the plugin.
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class VerifySignedPublicationBundleTask : DefaultTask() {

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Input
    abstract val expectedGroup: Property<String>

    @get:Input
    abstract val publishableModules: ListProperty<String>

    @get:Input
    abstract val signingKeyPresent: Property<Boolean>

    @get:Input
    abstract val signingPasswordPresent: Property<Boolean>

    @get:Input
    abstract val repositoryUrl: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        require(signingKeyPresent.get()) {
            "Missing signingKey. Provide -PsigningKey=<ascii-armored-private-key>."
        }
        require(signingPasswordPresent.get()) {
            "Missing signingPassword. Provide -PsigningPassword=<key-password>."
        }

        val repositoryUrl = repositoryUrl.orNull
        require(!repositoryUrl.isNullOrBlank()) {
            "Missing publish repository URL. Provide -PtramaiPublishReleaseUrl=file:///... or -PtramaiPublishSnapshotUrl=file:///..."
        }
        require(repositoryUrl.startsWith("file:")) {
            "verifySignedPublicationBundle only supports file:// repositories for local verification, but got $repositoryUrl"
        }

        val repositoryDirectory = repositoryDirectory.get().asFile
        val expectedVersion = expectedVersion.get()
        val groupPath = expectedGroup.get().replace('.', '/')

        publishableModules.get().forEach { projectName ->
            val moduleDirectory = repositoryDirectory.resolve("$groupPath/$projectName/$expectedVersion")
            require(moduleDirectory.isDirectory) {
                "Missing published module directory for $projectName at ${moduleDirectory.absolutePath}"
            }
            val publishedFiles = moduleDirectory.listFiles()?.filter(File::isFile).orEmpty()

            fun requirePublishedArtifact(
                description: String,
                predicate: (String) -> Boolean,
            ) {
                val matchingFiles = publishedFiles.filter { predicate(it.name) }
                require(matchingFiles.isNotEmpty()) {
                    "Missing published $description for $projectName in ${moduleDirectory.absolutePath}"
                }
                require(matchingFiles.all { it.length() > 0 }) {
                    "Published $description is empty for $projectName in ${moduleDirectory.absolutePath}"
                }
            }

            requirePublishedArtifact("POM", { it.endsWith(".pom") })
            requirePublishedArtifact("POM signature", { it.endsWith(".pom.asc") })
            requirePublishedArtifact("Gradle module metadata", { it.endsWith(".module") })
            requirePublishedArtifact("Gradle module metadata signature", { it.endsWith(".module.asc") })

            if (projectName != "tramai-bom") {
                requirePublishedArtifact(
                    "binary jar",
                    { it.endsWith(".jar") && !it.endsWith("-sources.jar") && !it.endsWith("-javadoc.jar") },
                )
                requirePublishedArtifact(
                    "binary jar signature",
                    { it.endsWith(".jar.asc") && !it.endsWith("-sources.jar.asc") && !it.endsWith("-javadoc.jar.asc") },
                )
                requirePublishedArtifact("sources jar", { it.endsWith("-sources.jar") })
                requirePublishedArtifact("sources jar signature", { it.endsWith("-sources.jar.asc") })
                requirePublishedArtifact("javadoc jar", { it.endsWith("-javadoc.jar") })
                requirePublishedArtifact("javadoc jar signature", { it.endsWith("-javadoc.jar.asc") })
            }
        }
    }
}
