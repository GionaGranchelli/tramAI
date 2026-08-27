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
 * for the `test` task only.
 *
 * The convention reacts to BOTH `java-library` and `org.jetbrains.kotlin.jvm`
 * being applied (order-independent): java-library first, Kotlin first, or
 * convention first all configure successfully. A Kotlin-only module gains
 * nothing — the historical modules all had both base plugins.
 *
 * Behavior-preserving extraction: modules that already declare exactly this
 * configuration migrate to `id("tramai.kotlin-library")`; modules with
 * divergent configuration (extra compiler args, no sources JAR, no JUnit
 * Platform) keep their deltas locally — the convention never adds behavior a
 * module did not have. Only the `test` task is configured — never future or
 * custom Test tasks (integration-test source sets are deferred).
 */
class TramaiKotlinLibraryPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("java-library") {
            project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                project.extensions.configure(JavaPluginExtension::class.java) {
                    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
                    withSourcesJar()
                }
                project.extensions.configure(KotlinJvmProjectExtension::class.java) {
                    jvmToolchain(21)
                    compilerOptions.jvmTarget.set(JvmTarget.fromTarget("21"))
                }
                project.tasks.named("test", Test::class.java) {
                    useJUnitPlatform()
                }
            }
        }
    }
}
