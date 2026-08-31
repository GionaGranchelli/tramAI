package dev.tramai.build.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Dependency
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
        val configDir =
            project.layout.projectDirectory
                .dir("config")
                .dir("dependency-hygiene")
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
                val spec = collectUnit(p)
                if (spec != null) collected += spec
                val sourceSets = p.extensions.findByType(SourceSetContainer::class.java)
                if (sourceSets != null) {
                    listOf("main", "test", "testFixtures").forEach { ss ->
                        val ssObj = sourceSets.findByName(ss)
                        if (ssObj != null) {
                            // Compile + runtime evidence: runtimeOnly coordinates must be
                            // analyzable too (10.1c review BLOCKER 3).
                            classpathSources.add(ssObj.compileClasspath)
                            classpathSources.add(ssObj.runtimeClasspath)
                        }
                    }
                }
                listOf("compileKotlin", "compileTestKotlin", "compileTestFixturesKotlin").forEach { name ->
                    p.tasks.findByName(name)?.let { tasks += it }
                }
            }
            moduleUnits.set(collected)
            val unionClasspath = project.files(classpathSources)
            project.tasks.named("verifyDependencyHygiene") {
                dependsOn(tasks)
                (this as VerifyDependencyHygieneTask).classpathEvidence.from(unionClasspath)
            }
            project.logger.lifecycle("dependency-hygiene: collected ${collected.size} module units")
        }
    }

    private fun collectUnit(p: Project): DependencyUnitSpec? {
        val sourceSets = p.extensions.findByType(SourceSetContainer::class.java) ?: return null
        val declared = collectDeclared(p)
        val importsBySourceSet = collectImports(p)
        return declared.takeIf { it.isNotEmpty() }?.let { decl ->
            DependencyUnitSpec(
                modulePath = p.path,
                declared = decl,
                importsBySourceSet = importsBySourceSet,
            )
        }
    }

    private fun collectDeclared(p: Project): Map<String, List<String>> {
        val declared = mutableMapOf<String, List<String>>()
        val configurations =
            listOf(
                "api",
                "implementation",
                "compileOnly",
                "runtimeOnly",
                "testImplementation",
                "testCompileOnly",
                "testRuntimeOnly",
                "testFixturesApi",
                "testFixturesImplementation",
            )
        configurations.forEach { name ->
            val configuration = p.configurations.findByName(name) ?: return@forEach
            val coordinates =
                configuration.dependencies
                    .mapNotNull { dep -> coordinateOf(dep) }
                    .distinct()
                    .sorted()
            if (coordinates.isNotEmpty()) declared[name] = coordinates
        }
        return declared
    }

    private fun coordinateOf(dep: Dependency): String? {
        if (dep is ProjectDependency) return null
        return if (dep is ModuleDependency && !dep.group.isNullOrBlank() && !dep.name.isNullOrBlank()) {
            "${dep.group}:${dep.name}"
        } else {
            null
        }
    }

    private fun collectImports(p: Project): Map<String, Set<String>> {
        val importsBySourceSet = mutableMapOf<String, Set<String>>()
        listOf("main", "test", "testFixtures").forEach { ss ->
            val symbols = importSymbolsIn(p, ss)
            if (symbols.isNotEmpty()) importsBySourceSet[ss] = symbols
        }
        return importsBySourceSet
    }

    private fun importSymbolsIn(
        p: Project,
        ss: String,
    ): Set<String> {
        val symbols = mutableSetOf<String>()
        listOf("kotlin", "java").forEach { lang ->
            val dir = File(p.projectDir, "src/$ss/$lang")
            if (dir.isDirectory) symbols += importsIn(dir)
        }
        return symbols
    }

    private fun importsIn(dir: File): Set<String> {
        val symbols = mutableSetOf<String>()
        dir
            .walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                file.readLines().forEach { line -> importSymbolOf(line)?.let { symbols += it } }
            }
        return symbols
    }
}

/**
 * Extracts the full import symbol from a Kotlin/Java import line, or null when
 * the line is not a usable import. Handles the forms that matter for dependency
 * evidence (10.1c): plain (`import foo.bar.Baz`), wildcard (`import foo.bar.*`,
 * kept as-is), Java static (`import static foo.Bar.baz` → owner + member), Kotlin
 * alias (`import foo.bar.Baz as X` → the aliased symbol), and Java trailing ';'.
 *
 * Exact-symbol evidence (round-3 review): the dependency gate matches these
 * against full class names from the dependency jars, so sibling artifacts that
 * share a package family can no longer justify each other.
 */
internal fun importSymbolOf(line: String): String? {
    val m = IMPORT_REGEX.matchEntire(line.trim()) ?: return null
    val symbol = m.groupValues[1]
    return symbol.takeIf { it.contains(".") }
}

private val IMPORT_REGEX =
    Regex("^import(?:\\s+static)?\\s+([\\w.*]+?)(?:\\s+as\\s+[\\w.]+)?;?$")
