package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Typed documentation/quality verifier tasks (Epic 9.2d-a3a — residual surgical
 * CC hygiene). Each replaces an ordinary doLast closure whose execution only
 * reads declared inputs; none touches Task.project during execution
 * (configuration-cache safe).
 */

/**
 * Fails when docs/reference/module-matrix.md differs from the authoritative
 * module catalog (config/quality/module-catalog.yml).
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class ModuleMatrixDriftVerifierTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleMatrixFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val moduleCatalogFile: ConfigurableFileCollection

    @get:Input
    abstract val rootDir: Property<File>

    @TaskAction
    fun verify() {
        val target = moduleMatrixFile.get().asFile
        val expected = ModuleManifest.matrix(rootDir.get())
        if (!target.isFile || target.readText() != expected) {
            throw GradleException(
                "[${DiagnosticCode.GENERATED_DOCUMENT_DRIFT}] Module matrix drift: run ./gradlew generateModuleMatrix"
            )
        }
    }
}

/**
 * Fails if any @Test function uses an expression body whose inferred return
 * type is not provably Unit (JUnit silently skips non-void @Test methods).
 * Pure scan over declared source inputs — no project model access.
 */
@DisableCachingByDefault(because = "Verification task has no output artifact")
abstract class JUnitTestSignatureVerifierTask : DefaultTask() {

    /** Root to walk (same as historical closure: project.rootDir). */
    @get:Input
    abstract val scanRoot: Property<File>

    /** Files the scanner actually inspects (test-source trees); content key. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testSources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        // Historical semantics: scan(rootDir.toPath()) walks the whole root
        // and filters to src/test|src/testFixtures .kt files. The declared
        // testSources input is the invalidation key; the walk reproduces the
        // exact historical behavior (same file set, same diagnostics).
        val violations = JUnitTestSignatureVerifier.scan(scanRoot.get().toPath())
        if (violations.isNotEmpty()) {
            throw GradleException(
                "verifyJUnitTestSignatures: ${violations.size} @Test function(s) with " +
                    "non-Unit expression bodies would be silently skipped by JUnit.\n" +
                    JUnitTestSignatureVerifier.render(violations),
            )
        }
    }
}

/**
 * Compiles the Java/Kotlin consumer smoke fixture against the stable API and
 * writes the semantic-compatibility marker (Epic 10.2). Typed replacement for
 * the register + doLast closure: declared inputs (sources, classpaths,
 * toolchain executable, workDir) and outputs (classes dir, marker json), no
 * Task.project at execution.
 */
@DisableCachingByDefault(because = "Marker output is evidence, not a build artifact")
abstract class ConsumerSmokeCompileTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Classpath
    abstract val compileClasspath: ConfigurableFileCollection

    @get:Optional
    @get:Classpath
    abstract val kotlinCompilerClasspath: ConfigurableFileCollection

    @get:Input
    abstract val extension: Property<String>

    /** Toolchain executables, resolved eagerly by the plugin (JDK 21). */
    @get:Optional
    @get:Input
    abstract val javaExecutable: Property<String>

    @get:Optional
    @get:Input
    abstract val javacExecutable: Property<String>

    /** Process working directory (historical: root project dir). */
    @get:Input
    abstract val workDir: Property<File>

    @get:OutputDirectory
    abstract val classesDir: DirectoryProperty

    @get:OutputFile
    abstract val markerFile: RegularFileProperty

    @TaskAction
    fun compile() {
        val sourcesList = sources.files
            .filter { it.isFile && it.extension == (if (extension.get() == "kotlin") "kt" else "java") }
            .map { it.absolutePath }
        val classpath = compileClasspath.asPath
        val kotlinClasspath = if (kotlinCompilerClasspath.files.isEmpty()) "" else kotlinCompilerClasspath.asPath

        val outDir = classesDir.get().asFile.apply { mkdirs() }
        outDir.listFiles()?.forEach { it.deleteRecursively() }

        var exitCode = -1
        if (extension.get() == "java") {
            val javac = javacExecutable.get()
            exitCode = runProcess(
                listOf(javac, "-cp", classpath, "-d", outDir.absolutePath) + sourcesList,
                workDir.get(),
            )
        } else {
            val java = javaExecutable.get()
            exitCode = runProcess(
                listOf(
                    java,
                    "-cp", kotlinClasspath,
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-classpath", classpath,
                    "-d", outDir.absolutePath,
                ) + sourcesList,
                workDir.get(),
            )
        }
        val classCount = outDir.walkTopDown().count { it.isFile && it.extension == "class" }
        val marker = mapOf(
            "sources" to sourcesList.size,
            "classes" to classCount,
            "exitCode" to exitCode,
            "ok" to (sourcesList.isNotEmpty() && classCount > 0 && exitCode == 0),
        )
        markerFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(ReportNormalizer.toJson(marker))
        }
    }

    private fun runProcess(command: List<String>, workDir: File): Int {
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        // Historical behavior preserved: log only on failure, warn with prefix.
        if (exit != 0 && output.isNotBlank()) {
            logger.warn("consumer compile failed (exit $exit):\n${output.take(1500)}")
        }
        return exit
    }
}
