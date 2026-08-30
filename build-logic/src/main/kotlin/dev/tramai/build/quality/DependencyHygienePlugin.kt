package dev.tramai.build.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Epic 10.1c dependency-hygiene plugin (root-only).
 *
 * One authority: one central exemption catalog
 * (config/dependency-hygiene/exemptions.yml), one verify task
 * (verifyDependencyHygiene). Per-module declared dependencies and source
 * imports are captured at configuration time (projectsEvaluated — apply()
 * runs before subprojects are configured).
 */
class DependencyHygienePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val configDir = project.layout.projectDirectory.dir("config").dir("dependency-hygiene")
        // NOTE: named moduleUnits — inside the task-registration lambda the task's own
        // `units` property shadows this variable; self-assignment silently empties it.
        val moduleUnits = project.objects.listProperty(DependencyUnitSpec::class.java)
        moduleUnits.set(emptyList())

        val tasks = mutableListOf<Task>()
        val classpathSources = mutableListOf<Any>()

        project.tasks.register<VerifyDependencyHygieneTask>("verifyDependencyHygiene") {
            group = "verification"
            description =
                "Repository-wide unused-dependency gate: declared direct dependencies vs source usage, " +
                    "with a module+configuration+coordinate exemption catalog for non-static usages."
            units.set(moduleUnits)
            exemptionFiles.from(configDir.file("exemptions.yml"))
            repositoryRoot.set(project.rootDir.absolutePath)
            reportsDir.set(project.layout.buildDirectory.dir("reports/dependency-hygiene"))
        }

        project.gradle.projectsEvaluated {
            val collected = mutableListOf<DependencyUnitSpec>()
            for (p in project.subprojects.filter { it.name != "tramai-dashboard" }) {
                val spec = collectUnit(p, project.rootDir)
                if (spec != null) collected += spec
                val sourceSets = p.extensions.findByType(SourceSetContainer::class.java)
                if (sourceSets != null) {
                    listOf("main", "test", "testFixtures").forEach { ss ->
                        val ssObj = sourceSets.findByName(ss)
                        if (ssObj != null) classpathSources.add(ssObj.compileClasspath)
                    }
                }
                listOf("compileKotlin", "compileTestKotlin", "compileTestFixturesKotlin").forEach { name ->
                    p.tasks.findByName(name)?.let { tasks += it }
                }
            }
            moduleUnits.set(collected)
            val unionClasspath = project.files(*classpathSources.toTypedArray())
            project.tasks.named("verifyDependencyHygiene") {
                dependsOn(tasks)
                (this as VerifyDependencyHygieneTask).compileClasspath.from(unionClasspath)
            }
            project.logger.lifecycle("dependency-hygiene: collected ${collected.size} module units")
        }
    }

    private fun collectUnit(p: Project, rootDir: File): DependencyUnitSpec? {
        val sourceSets = p.extensions.findByType(SourceSetContainer::class.java) ?: return null
        val declared = mutableMapOf<String, MutableList<String>>()
        val configurations =
            listOf(
                "api", "implementation", "compileOnly", "runtimeOnly",
                "testImplementation", "testCompileOnly", "testRuntimeOnly",
                "testFixturesApi", "testFixturesImplementation",
            )
        for (name in configurations) {
            val configuration = p.configurations.findByName(name) ?: continue
            val coordinates = mutableListOf<String>()
            for (dep in configuration.dependencies) {
                if (dep is ProjectDependency) continue
                if (dep is ModuleDependency && !dep.group.isNullOrBlank() && !dep.name.isNullOrBlank()) {
                    coordinates += "${dep.group}:${dep.name}"
                }
            }
            if (coordinates.isNotEmpty()) declared[name] = coordinates
        }
        val importsBySourceSet = mutableMapOf<String, Set<String>>()
        for (ss in listOf("main", "test", "testFixtures")) {
            val prefixes = mutableSetOf<String>()
            for (lang in listOf("kotlin", "java")) {
                val dir = File(p.projectDir, "src/$ss/$lang")
                if (!dir.isDirectory) continue
                dir.walkTopDown()
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .forEach { file ->
                        val text = file.readText()
                        IMPORT_REGEX.findAll(text).forEach { m ->
                            val parts = m.groupValues[1].split(".")
                            if (parts.size >= 2) prefixes.add(parts.take(2).joinToString("."))
                        }
                    }
            }
            if (prefixes.isNotEmpty()) importsBySourceSet[ss] = prefixes
        }
        if (declared.isEmpty()) return null
        return DependencyUnitSpec(
            modulePath = p.path,
            declared = declared,
            importsBySourceSet = importsBySourceSet,
        )
    }

    private companion object {
        val IMPORT_REGEX = Regex("^\\s*import\\s+([\\w.]+)", setOf(RegexOption.MULTILINE))
    }
}
