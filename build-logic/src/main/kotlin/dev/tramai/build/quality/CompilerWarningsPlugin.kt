package dev.tramai.build.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

/**
 * Epic 10.1c compiler-warning gate plugin.
 *
 * One authority: one pinned kotlin-compiler-embeddable (== repo Kotlin version),
 * one central baseline (config/warnings/baseline.json), one verify task
 * (verifyCompilerWarnings), one bootstrap task (bootstrapCompilerWarningsBaseline).
 *
 * Applied at the ROOT build (covers all subprojects) AND at the build-logic
 * included build (Gradle 9 removed cross-build project access, so the included
 * build self-applies the plugin and the root chains both task references).
 *
 * Compile units are captured at configuration time from every project the
 * plugin instance covers (same classpath/args/jvm-target/friend-paths as the
 * real build), so the standalone compiler reproduces the exact warning
 * inventory (T0.5-proven).
 */
class CompilerWarningsPlugin : Plugin<Project> {
    companion object {
        const val KOTLIN_COMPILER_VERSION = "2.3.0"
        const val DEFAULT_JVM_TARGET = "21"
    }

    override fun apply(project: Project) {
        val kotlinCompiler =
            project.configurations.create("kotlinCompiler") {
                isCanBeConsumed = false
                isCanBeResolved = true
            }
        project.dependencies.add(
            "kotlinCompiler",
            "org.jetbrains.kotlin:kotlin-compiler-embeddable:$KOTLIN_COMPILER_VERSION",
        )

        val configDir =
            project.layout.projectDirectory
                .dir("config")
                .dir("warnings")

        // Subprojects are not configured when the root plugin's apply() runs —
        // their SourceSetContainer/compile tasks don't exist yet. Register the
        // tasks now (so root wiring can reference them) and fill the compile
        // units after evaluation.
        val units = project.objects.listProperty(CompileUnitSpec::class.java)
        units.set(emptyList())

        // NOTE (documented deviation): build-logic is NOT covered by this gate.
        // kotlin-dsl compilation needs the embedded compiler plugin machinery
        // (default imports/accessors) which a standalone kotlinc cannot reproduce,
        // and cross-build output capture is unreliable. Its 67 warnings remain in
        // the T0 inventory but are not re-verified. See docs/EPIC-10.1-code-quality.md.

        project.tasks.register<VerifyCompilerWarningsTask>("verifyCompilerWarnings") {
            group = "verification"
            description =
                "Repository-wide Kotlin compiler-warning gate: standalone compiler (pinned " +
                "kotlin-compiler-embeddable $KOTLIN_COMPILER_VERSION) against the frozen baseline; " +
                "new or additional warnings fail."
            kotlinCompilerClasspath.from(kotlinCompiler)
            compileUnits.set(units)
            baselineFiles.from(configDir.file("baseline.json"))
            baseRef.set(project.providers.gradleProperty("tramaiCompilerWarningsBaseRef").orElse("origin/master"))
            repositoryRoot.set(project.rootDir.absolutePath)
            reportsDir.set(project.layout.buildDirectory.dir("reports/compiler-warnings"))
        }

        project.tasks.register<BootstrapCompilerWarningsBaselineTask>("bootstrapCompilerWarningsBaseline") {
            group = "verification"
            description =
                "Generation of config/warnings/baseline.json from the current warning inventory " +
                "(all modules). Deterministic; afterwards the baseline only shrinks."
            kotlinCompilerClasspath.from(kotlinCompiler)
            compileUnits.set(units)
            repositoryRoot.set(project.rootDir.absolutePath)
            baselineOutputFile.set(configDir.file("baseline.json").asFile)
        }

        // Fill the compile units and compile-task dependencies once every
        // project is configured (apply() runs before subproject evaluation; the
        // root's afterEvaluate would also fire too early — use projectsEvaluated).
        project.gradle.projectsEvaluated {
            wireTaskInputs(project, units)
        }
    }

    private fun wireTaskInputs(
        project: Project,
        units: ListProperty<CompileUnitSpec>,
    ) {
        val collected = collectCompileUnits(project)
        units.set(collected)
        val allProjects = collectProjects(project)
        // P3-C: decide the compile closure at CONFIGURATION time so the verify
        // task does not drag the whole repository's compile graph behind a
        // workflow/docs-only delta. NONE -> zero compile tasks; MODULES -> the
        // affected closure only; FULL -> everything (same as before). The task
        // action still re-derives the impact at execution time as the
        // authority, so a stale classification can only cost compilation, not
        // correctness.
        val allModulePaths = collected.map { it.modulePath }.toSet()
        val dependents = computeDependentsByModule(project)
        val dependentsMap = dependents.associate { it.modulePath to it.dependents.toSet() }
        val diff = configTimeGitDiff(project)
        if (diff == null) {
            project.logger.lifecycle(
                "compiler-warnings: cannot read diff at configuration time — wiring all compile tasks (fail-closed)",
            )
        }
        val baselineChanged =
            diff?.lineSequence()?.any { it.contains("config/warnings/baseline.json") } == true
        val impact =
            if (diff == null) {
                CompilerWarningsImpact.Full
            } else {
                resolveCompilerWarningsImpact(diff, dependentsMap)
            }
        val wiredPaths = compileTaskModulePaths(impact, baselineChanged, allModulePaths)

        val wiredProjects = allProjects.filter { it.path in wiredPaths }
        val verifyCompileTasks = mutableListOf<Task>()
        val verifyClasspathSources = mutableListOf<Any>()
        wiredProjects.forEach { p ->
            val wiring = collectProjectWiring(p)
            verifyCompileTasks.addAll(wiring.compileTasks)
            verifyClasspathSources.addAll(wiring.classpathSources)
        }
        // NOTE (P3-C): classpath scoping is REQUIRED, not cosmetic. A file
        // collection built from a source-set compileClasspath carries built-by
        // task dependencies — including ALL modules' classpaths in the verify
        // task's @Classpath input would force every module to compile even when
        // zero compile tasks are wired via dependsOn.
        val verifyClasspath = project.files(verifyClasspathSources)

        val bootstrapCompileTasks = mutableListOf<Task>()
        val bootstrapClasspathSources = mutableListOf<Any>()
        allProjects.forEach { p ->
            val wiring = collectProjectWiring(p)
            bootstrapCompileTasks.addAll(wiring.compileTasks)
            bootstrapClasspathSources.addAll(wiring.classpathSources)
        }
        val bootstrapClasspath = project.files(bootstrapClasspathSources)

        val sourceTreeList = mutableListOf<Any>()
        allProjects.forEach { p ->
            listOf("main", "test", "testFixtures").forEach { ss ->
                val dir = File(p.projectDir, "src/$ss/kotlin")
                if (dir.isDirectory) sourceTreeList.add(p.fileTree(dir))
            }
        }
        project.tasks.named("verifyCompilerWarnings") {
            dependsOn(verifyCompileTasks)
            (this as VerifyCompilerWarningsTask).compileClasspath.from(verifyClasspath)
            (this as VerifyCompilerWarningsTask).sourceTrees.from(sourceTreeList)
            (this as VerifyCompilerWarningsTask).moduleDependents.set(dependents)
        }
        project.tasks.named("bootstrapCompilerWarningsBaseline") {
            // Bootstrap regenerates the FULL baseline — it must always compile
            // everything, regardless of the delta.
            dependsOn(bootstrapCompileTasks)
            (this as BootstrapCompilerWarningsBaselineTask).compileClasspath.from(bootstrapClasspath)
            (this as BootstrapCompilerWarningsBaselineTask).sourceTrees.from(sourceTreeList)
        }
        project.logger.lifecycle(
            "compiler-warnings: collected ${collected.size} compile units, " +
                "${verifyCompileTasks.size}/${bootstrapCompileTasks.size} compile tasks wired for verify",
        )
    }

    /** Returns the git name-only diff vs baseRef, or null when unavailable. */
    private fun configTimeGitDiff(project: Project): String? {
        val baseRef =
            project.providers
                .gradleProperty("tramaiCompilerWarningsBaseRef")
                .orElse("origin/master")
                .get()
        val root = project.rootDir
        return try {
            val rev = runGit(root, "rev-parse", "--verify", "--quiet", "$baseRef^{commit}")
            if (rev.exitCode != 0) return null
            val diff = runGit(root, "diff", "--name-only", "$baseRef...HEAD")
            if (diff.exitCode != 0) null else diff.output
        } catch (_: Exception) {
            null
        }
    }

    private data class GitResult(val exitCode: Int, val output: String)

    private fun runGit(root: File, vararg args: String): GitResult {
        val proc =
            ProcessBuilder(listOf("git") + args)
                .directory(root)
                .redirectErrorStream(true)
                .start()
        val out = proc.inputStream.bufferedReader().readText()
        return GitResult(proc.waitFor(), out)
    }

    /**
     * Reverse edges: for every module that declares a project dependency, record
     * the dependency as key and the consumer as dependent. Deterministic sorted
     * output (config-cache + up-to-date safe).
     */
    private fun computeDependentsByModule(project: Project): List<ModuleDependentsSpec> {
        // Compile-relevant scopes only. runtimeOnly/testRuntimeOnly are not on
        // the consumer's compile classpath, so they cannot surface a warning in
        // a dependent's compile and only enlarge the closure (P3-A is about
        // avoiding unnecessary compilation without losing coverage).
        val productionConfigs = setOf("api", "implementation", "compileOnly")
        val testConfigs = setOf("testApi", "testImplementation", "testCompileOnly")
        val dependents = mutableMapOf<String, MutableSet<String>>()
        for (consumer in collectProjects(project)) {
            val consumerPath = consumer.path
            for (configName in productionConfigs + testConfigs) {
                val config = consumer.configurations.findByName(configName) ?: continue
                addProjectDependents(config, consumerPath, dependents)
            }
        }
        return dependents
            .map { (modulePath, deps) -> ModuleDependentsSpec(modulePath, deps.sorted()) }
            .sortedBy { it.modulePath }
    }

    private fun addProjectDependents(
        config: org.gradle.api.artifacts.Configuration,
        consumerPath: String,
        dependents: MutableMap<String, MutableSet<String>>,
    ) {
        // FAIL-CLOSED (P3-A): reading declared project dependencies never
        // resolves the configuration; if it still throws, propagating the
        // failure beats silently dropping a reverse edge — a dropped edge could
        // leave a dependent unverified when a changed module recompiles.
        config.dependencies
            .withType(org.gradle.api.artifacts.ProjectDependency::class.java)
            .forEach { dep ->
                val depPath = (dep as org.gradle.api.artifacts.ProjectDependency).path
                dependents.getOrPut(depPath) { mutableSetOf() }.add(consumerPath)
            }
    }

    private data class ProjectWiring(
        val compileTasks: List<Task>,
        val classpathSources: List<Any>,
    )

    private fun collectProjectWiring(p: Project): ProjectWiring {
        val compileTasks = mutableListOf<Task>()
        val classpathSources = mutableListOf<Any>()
        listOf(
            "compileKotlin",
            "compileTestKotlin",
            "compileTestFixturesKotlin",
            "compileJava",
            "compileTestJava",
            "compileTestFixturesJava",
        ).forEach { name ->
            p.tasks.findByName(name)?.let { compileTasks += it }
        }
        val sourceSets = p.extensions.findByType(SourceSetContainer::class.java)
        if (sourceSets != null) {
            listOf("main", "test", "testFixtures").forEach { ss ->
                val ssObj = sourceSets.findByName(ss)
                if (ssObj != null) classpathSources.add(ssObj.compileClasspath)
            }
        }
        return ProjectWiring(compileTasks, classpathSources)
    }

    private fun collectProjects(project: Project): List<Project> {
        // tramai-dashboard is a node/npm frontend module — its compile chain needs
        // a working node environment (unavailable on some builders) and it has zero
        // compiler warnings in the T0 inventory, so it is deliberately excluded.
        return project.subprojects.filter { it.name != "tramai-dashboard" }
    }

    private fun collectCompileUnits(project: Project): List<CompileUnitSpec> =
        collectProjects(project).flatMap { p ->
            val sourceSets =
                p.extensions.findByType(SourceSetContainer::class.java)
                    ?: return@flatMap emptyList()
            listOf("main", "test", "testFixtures").mapNotNull { ss ->
                if (sourceSets.findByName(ss) == null) return@mapNotNull null
                if (!File(p.projectDir, "src/$ss/kotlin").isDirectory) return@mapNotNull null
                val compileTaskName =
                    when (ss) {
                        "main" -> "compileKotlin"
                        "test" -> "compileTestKotlin"
                        else -> "compileTestFixturesKotlin"
                    }
                val compileTask =
                    p.tasks.findByName(compileTaskName) as? KotlinCompile
                        ?: return@mapNotNull null
                CompileUnitSpec(
                    modulePath = if (p.path.startsWith(":")) p.path else ":" + p.path,
                    sourceSet = ss,
                    compilerArgs = compileTask.compilerOptions.freeCompilerArgs.getOrElse(emptyList()),
                    jvmTarget =
                        compileTask.compilerOptions.jvmTarget.orNull
                            ?.target ?: DEFAULT_JVM_TARGET,
                )
            }
        }
}
