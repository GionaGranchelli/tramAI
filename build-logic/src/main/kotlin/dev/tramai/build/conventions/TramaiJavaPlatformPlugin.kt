package dev.tramai.build.conventions

import dev.tramai.build.quality.ModuleManifest
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlatformExtension

/**
 * Tramai Java platform convention plugin (Epic 9.2c).
 *
 * Configures the standard BOM/platform baseline: allows dependency management
 * across the platform and wires the manifest-derived BOM module set as
 * `api` constraints on every published, release-included module. Reacts to
 * `java-platform` being applied by the module.
 *
 * Behavior-preserving extraction of `tramai-bom`'s historical build script.
 */
class TramaiJavaPlatformPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("java-platform") {
            project.extensions.configure(JavaPlatformExtension::class.java) {
                allowDependencies()
            }
            project.dependencies.constraints {
                val bomModulePaths = ModuleManifest.bomModulePaths(project.rootProject.rootDir)
                bomModulePaths.forEach { path ->
                    add("api", project.dependencies.project(mapOf("path" to path)))
                }
            }
        }
    }
}
