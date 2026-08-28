package dev.tramai.build.conventions

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Tramai testing convention plugin (Epic 9.2c-b).
 *
 * Adds the common test-dependency baseline — JUnit BOM (platform), AssertJ,
 * and Kotlin test/JUnit5 — to the module's `testImplementation`
 * configuration. Reacts to `java` being applied, and reads coordinates from
 * the root build's version catalog (`gradle/libs.versions.toml`) instead of
 * hard-coding them.
 *
 * Scope discipline (per B7 survey): owns exactly these three dependencies and
 * nothing else — no testLogging, no compiler flags, no static analysis, no
 * `junit-jupiter` (13 modules add that explicitly and keep it local), no
 * production dependencies. Modules that do not have the exact trio (11
 * partial, 4 none) do not apply this plugin.
 */
class TramaiTestingPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("java") {
            val libs = project.extensions.getByType(VersionCatalogsExtension::class.java)
                .named("libs")

            fun catalogLibrary(alias: String) =
                libs.findLibrary(alias).orElseThrow {
                    GradleException(
                        "tramai.testing requires version-catalog alias '$alias' in gradle/libs.versions.toml",
                    )
                }

            project.dependencies.add("testImplementation", project.dependencies.platform(catalogLibrary("junit-bom")))
            project.dependencies.add("testImplementation", catalogLibrary("assertj-core"))
            project.dependencies.add("testImplementation", catalogLibrary("kotlin-test-junit5"))
        }
    }
}
