package dev.tramai.build.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.component.AdhocComponentWithVariants

/**
 * Tramai test-fixtures convention plugin (Epic 9.2c).
 *
 * Applies Gradle's built-in `java-test-fixtures` plugin so the module can
 * expose a reusable `testFixtures` source set to other modules. Reacts to
 * `java-library` being applied by the module.
 *
 * Behavior-preserving extraction of the repeated `java-test-fixtures`
 * application in modules that already use it.
 *
 * It also **excludes the fixture variants from publication** (0.7.4h1). `java-test-fixtures` attaches
 * `testFixturesApiElements` and `testFixturesRuntimeElements` to the `java` component, so without this
 * every module that owns fixtures publishes a `<module>-<version>-test-fixtures.jar` and two extra
 * metadata variants to every consumer — test-only classes, their dependencies and their metadata
 * shipped as part of the library's contract.
 *
 * The exclusion lives here, once, rather than in each module's build script: the module-level copy it
 * replaces pinned only the module that happened to be audited, which is how the other producer modules
 * stayed published. `verifyNoPublishedTestFixtures` proves the result against the generated
 * `module.json` of every publishable module, so this comment is never the evidence.
 *
 * Internal fixture usage is untouched: consumers depend on the project's `testFixtures*Elements`
 * configurations, which exist whether or not the variants are published.
 */
class TramaiTestFixturesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("java-library") {
            project.pluginManager.apply("java-test-fixtures")
            excludeFixtureVariantsFromPublication(project)
        }
    }

    private fun excludeFixtureVariantsFromPublication(project: Project) {
        project.pluginManager.withPlugin("maven-publish") {
            val javaComponent = project.components.findByName("java") as? AdhocComponentWithVariants
            if (javaComponent == null) {
                throw IllegalStateException(
                    "tramai.test-fixtures: ':${project.name}' applies maven-publish but has no 'java' " +
                        "component to exclude the test-fixtures variants from. Publishing test fixtures " +
                        "is not permitted, so the exclusion cannot be silently skipped.",
                )
            }
            val fixturesConfigurations = listOf("testFixturesApiElements", "testFixturesRuntimeElements")
            javaComponent.apply {
                fixturesConfigurations.forEach { name ->
                    withVariantsFromConfiguration(project.configurations.getByName(name)) { skip() }
                }
            }
        }
    }
}
