package dev.tramai.build.release

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Typed release verification task: verifies that the properties required for a
 * real remote release publish are present (9.2b extraction).
 *
 * Security boundary: the task never holds the actual secrets. It only carries
 * boolean PRESENCE flags, computed from the Gradle properties by the plugin.
 * The secret values themselves never enter task state, diagnostics, build
 * scans, or the configuration cache. Diagnostics name only the missing
 * property, never its value.
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class VerifyReleasePublishInputsTask : DefaultTask() {

    @get:Input
    abstract val releaseUrlPresent: Property<Boolean>

    @get:Input
    abstract val usernamePresent: Property<Boolean>

    @get:Input
    abstract val passwordPresent: Property<Boolean>

    @get:Input
    abstract val signingKeyPresent: Property<Boolean>

    @get:Input
    abstract val signingPasswordPresent: Property<Boolean>

    @get:Input
    abstract val tramaiVersion: Property<String>

    @TaskAction
    fun verify() {
        listOf(
            "tramaiPublishReleaseUrl" to releaseUrlPresent,
            "tramaiPublishUsername" to usernamePresent,
            "tramaiPublishPassword" to passwordPresent,
            "signingKey" to signingKeyPresent,
            "signingPassword" to signingPasswordPresent,
        ).forEach { (propertyName, present) ->
            require(present.get()) {
                "Missing required Gradle property for remote release publishing: $propertyName"
            }
        }
        require(!tramaiVersion.get().endsWith("-SNAPSHOT")) {
            "Remote release validation expects a non-SNAPSHOT tramaiVersion, but found ${tramaiVersion.get()}"
        }
    }
}
