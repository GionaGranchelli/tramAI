package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Classpath
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One compile unit for the standalone Kotlin compiler — fully serializable so
 * the tasks stay configuration-cache compatible (no Project captured at
 * execution time). No paths are stored: sources and own outputs are derived
 * from modulePath/sourceSet at execution (Gradle refuses path-like strings in
 * @Input values), and source changes invalidate the task via the @InputFiles
 * sourceTrees collection instead.
 */
data class CompileUnitSpec(
    @get:Input val modulePath: String,
    @get:Input val sourceSet: String,
    @get:Input val compilerArgs: List<String>,
    @get:Input val jvmTarget: String,
)

/** Runs the standalone compiler and parses warnings (shared by verify + bootstrap). */
internal object KotlincRunner {
    private const val TIMEOUT_MINUTES = 10L
    private const val READ_BUFFER_SIZE = 4096

    fun compileWarnings(
        unit: CompileUnitSpec,
        kotlinCompilerClasspath: Collection<File>,
        compileClasspath: Collection<File>,
        repositoryRoot: File,
        outputDir: File,
    ): List<WarningEntry> {
        val javaExecutable =
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
        val moduleDir = File(repositoryRoot, unit.modulePath.removePrefix(":").replace(':', File.separatorChar))
        // Own outputs + friend paths derived from the fixed Gradle layout.
        val ownDirs =
            listOf(
                File(moduleDir, "build/classes/kotlin/main"),
                File(moduleDir, "build/classes/java/main"),
            ) +
                if (unit.sourceSet == "test") {
                    listOf(
                        File(moduleDir, "build/classes/java/test"),
                        File(moduleDir, "build/classes/kotlin/testFixtures"),
                        File(moduleDir, "build/classes/java/testFixtures"),
                    )
                } else {
                    emptyList()
                }
        val friendPaths =
            when (unit.sourceSet) {
                "main" -> emptyList()
                "test" ->
                    listOf(
                        File(moduleDir, "build/classes/kotlin/main"),
                        File(moduleDir, "build/classes/kotlin/testFixtures"),
                    ).filter { it.isDirectory }
                else -> listOf(File(moduleDir, "build/classes/kotlin/main"))
            }
        val sources =
            File(moduleDir, "src/${unit.sourceSet}/kotlin")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { repositoryRoot.toPath().relativize(it.toPath()).toString() }
                .sorted()
                .toList()
        if (sources.isEmpty()) return emptyList()

        val classpath =
            (ownDirs.filter { it.isDirectory }.map { it.absolutePath } + compileClasspath.map { it.absolutePath })
                .distinct()
                .joinToString(File.pathSeparator)
        val args =
            mutableListOf(
                "-classpath",
                classpath,
                "-d",
                outputDir.absolutePath,
                "-jvm-target",
                unit.jvmTarget,
                "-Xrender-internal-diagnostic-names",
            )
        unit.compilerArgs.forEach { args += it }
        friendPaths.forEach { args += listOf("-Xfriend-paths", it.absolutePath) }
        args += sources
        val proc =
            ProcessBuilder(
                listOf(javaExecutable, "-Xmx2g", "-cp", kotlinCompilerClasspath.joinToString(File.pathSeparator),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler") + args,
            ).directory(repositoryRoot)
                .redirectErrorStream(true)
                .start()
        val output = StringBuilder()
        val reader = proc.inputStream.bufferedReader()
        val buf = CharArray(READ_BUFFER_SIZE)
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(TIMEOUT_MINUTES)
        while (System.nanoTime() < deadline) {
            if (reader.ready()) {
                val n = reader.read(buf)
                if (n > 0) output.append(buf, 0, n)
            }
            if (!proc.isAlive) break
            Thread.sleep(50)
        }
        if (proc.isAlive) {
            proc.destroyForcibly()
            throw GradleException(
                "verifyCompilerWarnings: standalone kotlinc for ${unit.modulePath}/${unit.sourceSet} " +
                    "did not exit within $TIMEOUT_MINUTES minutes — failing closed.",
            )
        }
        // The poll loop breaks as soon as the process dies — drain whatever the
        // pipe still holds (kotlinc emits ALL warnings on stdout; unlike Detekt,
        // there is no report file). readText() blocks only until EOF, which the
        // closed pipe guarantees after process exit.
        output.append(reader.readText())
        // FAIL-CLOSED: a non-zero compiler exit means the unit could not be
        // analysed. Tool failure is NOT zero findings — error text contains no
        // `w:` lines, so silently proceeding would green-light the module.
        val rc = proc.waitFor()
        if (rc != 0) {
            throw GradleException(
                "verifyCompilerWarnings: standalone kotlinc for ${unit.modulePath}/${unit.sourceSet} " +
                    "failed (rc=$rc): ${output.take(800)}",
            )
        }
        // rc==0 with warnings is NORMAL — extraction is the parse, gating is the baseline diff.
        return CompilerWarningsParser.parse(output.toString())
    }
}

/**
 * Epic 10.1c compiler-warning gate.
 *
 * Recompiles the delta modules' Kotlin source sets with the standalone compiler
 * (kotlin-compiler-embeddable, pinned to the repo Kotlin version) using the same
 * classpaths, compiler args, jvm target, and friend paths as the real Gradle
 * build, then compares the extracted warning inventory against the committed
 * baseline. Fail-closed: missing/malformed baseline, compile errors, unparseable
 * output, or new/additional warnings fail the task.
 */
abstract class VerifyCompilerWarningsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinCompilerClasspath: ConfigurableFileCollection

    @get:Classpath
    @get:InputFiles
    abstract val compileClasspath: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceTrees: ConfigurableFileCollection

    @get:Nested
    abstract val compileUnits: ListProperty<CompileUnitSpec>

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFiles: ConfigurableFileCollection

    @get:Input
    abstract val baseRef: Property<String>

    @get:Internal
    abstract val repositoryRoot: Property<String>

    @get:OutputDirectory
    abstract val reportsDir: DirectoryProperty

    @TaskAction
    fun verify() {
        val root = File(repositoryRoot.get())
        val baselineFile = baselineFiles.files.firstOrNull { it.exists() }
        val baseline = CompilerWarningsBaselineIo.fromJson(baselineFile?.readText())
        if (baseline == null) {
            throw GradleException(
                "verifyCompilerWarnings: baseline ${baselineFile?.absolutePath ?: "<missing>"} is absent or malformed. " +
                    "The gate fails closed without a valid baseline. Regenerate intentionally with " +
                    "bootstrapCompilerWarningsBaseline.",
            )
        }
        val reportDir = reportsDir.get().asFile.apply { mkdirs() }

        val diff = gitDiff(root, baseRef.get())
        val deltaModules = deltaModules(diff)
        if (deltaModules.isEmpty() && !baselineChanged(diff)) {
            File(reportDir, "summary.txt").writeText(
                "No Kotlin modules or the baseline changed in the delta — nothing to verify.\n",
            )
            logger.lifecycle("compiler-warnings: no changed modules; gate passes trivially")
            return
        }
        // Baseline edits change the comparison universe — recompile everything so
        // removals are validated against the current inventory (fail-closed).
        val verifyModules =
            if (baselineChanged(diff)) {
                logger.lifecycle("compiler-warnings: baseline changed in delta — full verification")
                allUnitModules()
            } else {
                deltaModules
            }
        logger.lifecycle("compiler-warnings: verifying modules ${verifyModules.sorted().joinToString()}")

        val current = mutableListOf<WarningEntry>()
        val outBase = File(reportDir, "kotlinc-out").apply { mkdirs() }
        for (unit in compileUnits.get().sortedBy { it.modulePath }) {
            if (unit.modulePath !in verifyModules) continue
            val out = File(outBase, unit.modulePath.removePrefix(":").replace(':', '_') + "-" + unit.sourceSet)
            out.mkdirs()
            current +=
                KotlincRunner.compileWarnings(
                    unit,
                    kotlinCompilerClasspath.files,
                    compileClasspath.files,
                    root,
                    out,
                )
        }
        if (":build-logic" in verifyModules) {
            // build-logic is deliberately excluded from this gate (see the plugin's
            // documented deviation) — nothing to capture.
            logger.lifecycle("compiler-warnings: :build-logic excluded from the gate (kotlin-dsl limitation)")
        }

        val violations = CompilerWarningsBaselineVerifier.compare(current, baseline)
        File(reportDir, "warnings.txt").writeText(
            current.joinToString("\n") { "${it.path} [${it.diagnostic}] x${it.count} ${it.message}" },
        )
        File(reportDir, "violations.txt").writeText(
            if (violations.isEmpty()) "(none)\n" else
                violations.joinToString("\n") {
                    "${it.path} [${it.diagnostic}] current=${it.currentCount} baseline=${it.baselineCount} ${it.message}"
                },
        )
        File(reportDir, "summary.txt").writeText(
            buildString {
                appendLine("TramAI compiler-warnings summary")
                appendLine("===============================")
                appendLine("baseline identities           : ${baseline.size}")
                appendLine("current delta identities      : ${current.size}")
                appendLine("violations                    : ${violations.size}")
                appendLine("delta modules                 : ${deltaModules.sorted().joinToString(", ")}")
            },
        )
        if (violations.isNotEmpty()) {
            val more = if (violations.size > 50) "\n  ... and ${violations.size - 50} more" else ""
            throw GradleException(
                "verifyCompilerWarnings: ${violations.size} warning(s) not covered by the baseline.\n" +
                    violations.take(50).joinToString("\n") {
                        "  ${it.path} [${it.diagnostic}] current=${it.currentCount} baseline=${it.baselineCount} ${it.message}"
                    } +
                    more +
                    "\n\nThe baseline is a ceiling, not an allowance budget. Fix the warnings or, for a " +
                    "deliberate removal, update config/warnings/baseline.json (removals only).",
            )
        }
        logger.lifecycle("compiler-warnings: ${current.size} baseline-covered warning identities; gate green")
    }

    private fun allUnitModules(): Set<String> =
        compileUnits.get().map { it.modulePath }.toSet()

    private fun baselineChanged(diff: String): Boolean =
        diff.lineSequence().any { it.contains("config/warnings/baseline.json") }

    private fun deltaModules(diff: String): Set<String> =
        diff.lineSequence()
            .filter { it.endsWith(".kt") }
            .mapNotNull { line ->
                val parts = line.split("/")
                when {
                    parts.size >= 3 && parts[0] == "examples" -> ":examples:${parts[1]}"
                    parts.size >= 2 -> ":" + parts[0]
                    else -> null
                }
            }
            .toSet()

    private fun gitDiff(root: File, ref: String): String {
        val rev = runGit(root, "rev-parse", "--verify", "--quiet", "$ref^{commit}")
        if (rev.exitCode != 0) {
            throw GradleException(
                "verifyCompilerWarnings: base ref '$ref' does not resolve. " +
                    "Pass -PtramaiCompilerWarningsBaseRef=<exact sha> (CI: pull_request.base.sha / event.before).",
            )
        }
        val diff = runGit(root, "diff", "--name-only", "$ref...HEAD")
        if (diff.exitCode != 0) {
            throw GradleException("verifyCompilerWarnings: git diff against '$ref' failed: ${diff.output.take(500)}")
        }
        return diff.output
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
}

/**
 * One-time baseline bootstrap: compiles every module/source-set, aggregates the
 * full warning inventory, and writes config/warnings/baseline.json.
 *
 * Deterministic: sorted paths/identities, no timestamps. Run once during the
 * 10.1c adoption PR; afterwards the baseline only shrinks.
 */
abstract class BootstrapCompilerWarningsBaselineTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinCompilerClasspath: ConfigurableFileCollection

    @get:Classpath
    @get:InputFiles
    abstract val compileClasspath: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceTrees: ConfigurableFileCollection

    @get:Nested
    abstract val compileUnits: ListProperty<CompileUnitSpec>

    @get:Internal
    abstract val repositoryRoot: Property<String>

    @get:Internal
    abstract val baselineOutputFile: Property<File>

    @TaskAction
    fun bootstrap() {
        val root = File(repositoryRoot.get())
        val all = mutableListOf<WarningEntry>()
        val outBase = File(root, "build/compiler-warnings-bootstrap-out").apply { mkdirs() }
        for (unit in compileUnits.get().sortedBy { it.modulePath }) {
            val out = File(outBase, unit.modulePath.removePrefix(":").replace(':', '_') + "-" + unit.sourceSet)
            out.mkdirs()
            all +=
                KotlincRunner.compileWarnings(
                    unit,
                    kotlinCompilerClasspath.files,
                    compileClasspath.files,
                    root,
                    out,
                )
        }
        val file = baselineOutputFile.get()
        file.parentFile.mkdirs()
        file.writeText(CompilerWarningsBaselineIo.toJson(all))
        logger.lifecycle(
            "bootstrapCompilerWarningsBaseline: ${all.size} identities / ${all.sumOf { it.count }} occurrences " +
                "written to ${file.absolutePath}",
        )
    }
}
