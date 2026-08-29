package dev.tramai.build.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register

/**
 * Epic 10.1b static-analysis plugin.
 *
 * One repository-level authority: one pinned Detekt version, one central
 * configuration (config/detekt/detekt.yml), one central baseline
 * (config/detekt/baseline.xml), one aggregate verification task
 * (verifyStaticAnalysis), one report location
 * (build/reports/static-analysis/). No per-module Detekt configurations, no
 * module-local baselines, no "tramai.quality" convention plugin.
 *
 * Applied only at the root project.
 */
class StaticAnalysisPlugin : Plugin<Project> {

    companion object {
        const val DETEKT_VERSION = "1.23.8"
    }

    override fun apply(project: Project) {
        val detektCli =
            project.configurations.create("detektCli") {
                isCanBeConsumed = false
                isCanBeResolved = true
                isTransitive = false
            }
        project.dependencies.add("detektCli", "io.gitlab.arturbosch.detekt:detekt-cli:$DETEKT_VERSION:all")

        val configDir = project.layout.projectDirectory.dir("config").dir("detekt")
        val sources =
            project.fileTree(project.rootDir) {
                // Source universe: every Kotlin source set (main/test/custom) in
                // every module, build-logic, and the examples. The `src` path
                // segment keeps the `dev.tramai.build` package (whose segment is
                // `build`, not `src`) fully included.
                include("**/src/**/*.kt")
                // NARROW, root-relative task-output exclusions only. A broad
                // "**/build/**" would also exclude paths containing a package
                // segment literally named `build` (e.g. dev/tramai/build in
                // build-logic), silently exempting real source.
                exclude(
                    "build/**", // root project output
                    "*/build/**", // module output dirs
                    "examples/*/build/**", // example module output dirs
                    "build-logic/build/**", // included-build output dir
                    "**/.gradle/**", // caches — no source lives here
                )
            }

        project.tasks.register<VerifyStaticAnalysisTask>("verifyStaticAnalysis") {
            group = "verification"
            description =
                "Repository-wide Kotlin static analysis (Detekt $DETEKT_VERSION, pinned) with " +
                    "frozen legacy baseline and fail-closed growth protection."
            detektClasspath.from(detektCli)
            sourceFiles.from(sources)
            configFile.set(configDir.file("detekt.yml"))
            baselineFiles.from(configDir.file("baseline.xml"))
            baseRef.set(project.providers.gradleProperty("tramaiStaticAnalysisBaseRef").orElse("origin/master"))
            changeClass.set(project.providers.gradleProperty("changeClass").orElse(""))
            reportsDir.set(project.layout.buildDirectory.dir("reports/static-analysis"))
            repositoryRoot.set(project.rootDir.absolutePath)
        }
    }
}
