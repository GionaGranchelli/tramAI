package dev.tramai.build.sovereign

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

/**
 * Validates that the sovereign runtime verification repo contains all required
 * dev.tramai artifacts for the consumer smoke build (9.2b extraction). Fails
 * if any required module, POM, metadata, or JAR is missing. Supports unique
 * Maven SNAPSHOT naming via [MavenPublishedArtifactResolver].
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class VerifySovereignRuntimeVerificationRepoClosureTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val expectedGroup: Property<String>

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Input
    abstract val moduleNames: ListProperty<String>

    @TaskAction
    fun verify() {
        val groupPath = expectedGroup.get().replace('.', '/')
        val expectedVersion = expectedVersion.get()
        val repoDir = repositoryDirectory.get().asFile

        require(repoDir.isDirectory) {
            "Missing verification repo at ${repoDir.absolutePath}. Run verifySovereignRuntimeSignedBundle first."
        }

        val bomOnlyModules = setOf("tramai-bom")

        moduleNames.get().forEach { moduleName ->
            val moduleDir = repoDir.resolve("$groupPath/$moduleName/$expectedVersion")
            require(moduleDir.isDirectory) {
                "Missing module directory in verification repo for $moduleName at ${moduleDir.absolutePath}"
            }

            // POM is required for every module
            val pom = MavenPublishedArtifactResolver.resolve(moduleDir, moduleName, expectedVersion, "pom")
            require(pom.isFile) {
                "Missing POM in verification repo for $moduleName at ${pom.absolutePath}"
            }
            require(pom.length() > 0) {
                "Empty POM in verification repo for $moduleName"
            }

            // Gradle module metadata is required for every module — must be non-empty
            val moduleMetadata = MavenPublishedArtifactResolver.resolve(moduleDir, moduleName, expectedVersion, "module")
            require(moduleMetadata.isFile) {
                "Missing module metadata in verification repo for $moduleName at ${moduleMetadata.absolutePath}"
            }
            require(moduleMetadata.length() > 0) {
                "Empty module metadata in verification repo for $moduleName"
            }

            // JAR is required for runtime modules (not BOM)
            if (moduleName !in bomOnlyModules) {
                val jar = MavenPublishedArtifactResolver.resolve(moduleDir, moduleName, expectedVersion, "jar")
                require(jar.isFile) {
                    "Missing JAR in verification repo for $moduleName at ${jar.absolutePath}"
                }
                require(jar.length() > 0) {
                    "Empty JAR in verification repo for $moduleName"
                }
            }
        }

        logger.lifecycle("verifySovereignRuntimeVerificationRepoClosure — PASSED")
        logger.lifecycle("  Required modules: ${moduleNames.get().size} (all present and non-empty)")
        logger.lifecycle("  Repository: ${repoDir.absolutePath}")
    }
}
