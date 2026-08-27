package dev.tramai.build.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Tramai Kotlin library convention plugin (Epic 9.2c).
 *
 * Configures the standard JVM/Kotlin baseline for library modules: Java 21
 * toolchain, sources JAR, Kotlin 21 toolchain/jvmTarget, and JUnit Platform
 * for the test task. Reacts to `org.jetbrains.kotlin.jvm` (and `java-library`)
 * being applied by the module — the module keeps its base plugins; this plugin
 * only removes the repeated configuration blocks.
 *
 * Behavior-preserving extraction: modules that already declare exactly this
 * configuration migrate to `id("tramai.kotlin-library")`; modules with
 * divergent configuration (extra compiler args, no sources JAR, no JUnit
 * Platform) keep their deltas locally — the convention never adds behavior a
 * module did not have.
 */
class TramaiKotlinLibraryPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            project.extensions.configure(JavaPluginExtension::class.java) {
                toolchain.languageVersion.set(JavaLanguageVersion.of(21))
                withSourcesJar()
            }
            project.extensions.configure(KotlinJvmProjectExtension::class.java) {
                jvmToolchain(21)
                compilerOptions.jvmTarget.set(JvmTarget.fromTarget("21"))
            }
            project.tasks.withType(Test::class.java).configureEach {
                useJUnitPlatform()
            }
        }
    }
}
