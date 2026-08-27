package dev.tramai.build.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Tramai test-fixtures convention plugin (Epic 9.2c).
 *
 * Applies Gradle's built-in `java-test-fixtures` plugin so the module can
 * expose a reusable `testFixtures` source set to other modules. Reacts to
 * `java-library` being applied by the module.
 *
 * Behavior-preserving extraction of the repeated `java-test-fixtures`
 * application in modules that already use it.
 */
class TramaiTestFixturesPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("java-library") {
            project.pluginManager.apply("java-test-fixtures")
        }
    }
}
