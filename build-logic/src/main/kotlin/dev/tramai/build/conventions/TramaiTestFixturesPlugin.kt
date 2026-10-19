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
 * Scope and limit (0.7.4h1 review): this runs inside `withPlugin("java-library")`, which is the only
 * path that creates fixture variants, so there is no reachable state where a module owns fixtures, is
 * publishable and skips the exclusion. A project that applies `maven-publish` without `java-library`
 * never constructs fixture variants, so it has nothing to exclude and this convention does not fail it
 * — the "no java component" case is therefore not a guard here and is not claimed as one. What is
 * claimed is enforced by `TramaiTestFixturesPublicationTest` (real generated metadata, fixture module,
 * both plugin orders) and by the release gate, not by this callback.
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
            // Present by construction: java-library applied above is what creates this component, and it
            // is what attaches the fixture variants to it.
            val javaComponent = project.components.named("java").get() as AdhocComponentWithVariants
            val fixturesConfigurations = listOf("testFixturesApiElements", "testFixturesRuntimeElements")
            javaComponent.apply {
                fixturesConfigurations.forEach { name ->
                    withVariantsFromConfiguration(project.configurations.getByName(name)) { skip() }
                }
            }
        }
    }
}
