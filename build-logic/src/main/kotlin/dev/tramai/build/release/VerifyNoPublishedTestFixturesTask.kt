package dev.tramai.build.release

import dev.tramai.build.publishing.PublishedFixtureLeakVerifier
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Typed release verification task: proves that no publishable module publishes a test-fixtures variant
 * or artifact (0.7.4h1).
 *
 * Inspects the generated `module.json` for every publishable module — the metadata a consumer's
 * resolution actually reads — rather than trusting the presence of an exclusion in a build script.
 * Fails closed: a module whose metadata is missing is a finding, not a pass, because a gate that
 * silently skips unaudited modules cannot claim the boundary holds.
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class VerifyNoPublishedTestFixturesTask : DefaultTask() {
    @get:Input
    abstract val publishableModules: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleMetadataFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val modules = publishableModules.get()
        if (modules.isEmpty()) {
            throw GradleException(
                "verifyNoPublishedTestFixtures: the publishable module set is empty, so nothing was " +
                    "audited. The module catalog is the authority for that set and must not degrade to " +
                    "an empty list.",
            )
        }

        val byPath = moduleMetadataFiles.files.associateBy { it.absolutePath }
        val leaks =
            PublishedFixtureLeakVerifier.verify(modules = modules) { module ->
                val relative = "${module.removePrefix(":")}/build/publications/maven/module.json"
                byPath.entries.firstOrNull { it.key.endsWith(relative) }?.value
            }

        if (leaks.isNotEmpty()) {
            val detail = leaks.joinToString(separator = "\n") { "  - ${it.describe()}" }
            throw GradleException(
                "verifyNoPublishedTestFixtures: ${leaks.size} unexpected test-fixtures publication(s) " +
                    "in the generated publication metadata:\n$detail\n" +
                    "Test fixtures are an internal testing surface. A module that needs them for another " +
                    "module's tests still exposes the project's testFixtures configurations; publishing the " +
                    "variant as well ships test-only classes, their dependencies and their metadata to every " +
                    "consumer. The exclusion lives in TramaiTestFixturesPlugin so every module inherits it.",
            )
        }
        logger.lifecycle(
            "verifyNoPublishedTestFixtures: ${modules.size} publishable module(s) audited against their " +
                "generated publication metadata; no test-fixtures variant or artifact published.",
        )
    }
}
