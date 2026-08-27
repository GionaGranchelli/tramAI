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
 * Typed release verification task: publishes to Maven Local (via task
 * dependencies wired by the plugin) and verifies POM/module/jar/sources/javadoc
 * artifacts for every publishable Tramai module (9.2b extraction).
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class VerifyPublishedArtifactsTask : DefaultTask() {

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Input
    abstract val expectedGroup: Property<String>

    @get:Input
    abstract val publishableModules: ListProperty<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val expectedVersion = expectedVersion.get()
        val group = expectedGroup.get()
        val baseRepository = repositoryDirectory.get().asFile

        require(baseRepository.isDirectory) {
            "Missing local Maven repository root for $group at ${baseRepository.absolutePath}"
        }

        publishableModules.get().forEach { projectName ->
            val moduleDirectory = baseRepository.resolve("$projectName/$expectedVersion")
            require(moduleDirectory.isDirectory) {
                "Missing local Maven module directory for $projectName at ${moduleDirectory.absolutePath}"
            }

            val baseName = "$projectName-$expectedVersion"
            val requiredFiles = mutableListOf(
                "$baseName.pom",
                "$baseName.module",
            )
            if (projectName != "tramai-bom") {
                requiredFiles += listOf(
                    "$baseName.jar",
                    "$baseName-sources.jar",
                    "$baseName-javadoc.jar",
                )
            }

            requiredFiles.forEach { fileName ->
                val artifact = moduleDirectory.resolve(fileName)
                require(artifact.isFile) { "Missing local Maven artifact for $projectName: ${artifact.absolutePath}" }
                require(artifact.length() > 0) { "Published artifact is empty for $projectName: ${artifact.absolutePath}" }
            }
        }
    }
}
