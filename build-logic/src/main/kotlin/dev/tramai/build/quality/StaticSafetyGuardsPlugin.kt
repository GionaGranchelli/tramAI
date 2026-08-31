package dev.tramai.build.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

class StaticSafetyGuardsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val sources = project.fileTree(project.rootDir) {
            include("*/src/main/kotlin/**/*.kt", "*/src/main/java/**/*.java")
            exclude("build/**", "*/build/**", "build-logic/**", "examples/**", "**/.gradle/**")
        }
        project.tasks.register<VerifyStaticSafetyGuardsTask>("verifyStaticSafetyGuards") {
            group="verification"; description="Fail-closed production static safety guards for lifecycle, HTTP, logging, and forbidden APIs."
            configFile.set(project.layout.projectDirectory.file("config/quality/static-safety-guards.yml")); sourceFiles.from(sources)
            repositoryRoot.set(project.rootDir.absolutePath); reportsDir.set(project.layout.buildDirectory.dir("reports/static-safety-guards"))
        }
    }
}
