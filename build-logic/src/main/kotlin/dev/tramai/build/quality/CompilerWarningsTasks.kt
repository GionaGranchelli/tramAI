package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
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
    private const val POLL_INTERVAL_MS = 50L
    private const val ERROR_EXCERPT_CHARS = 800
    private const val COMPILER_HEAP = "-Xmx2g"
    private const val K2_JVM_COMPILER = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

    fun compileWarnings(
        unit: CompileUnitSpec,
        kotlinCompilerClasspath: Collection<File>,
        compileClasspath: Collection<File>,
        repositoryRoot: File,
        outputDir: File,
    ): List<WarningEntry> {
        val moduleDir = File(repositoryRoot, unit.modulePath.removePrefix(":").replace(':', File.separatorChar))
        val sources = sourcesFor(moduleDir, unit.sourceSet, repositoryRoot)
        if (sources.isEmpty()) return emptyList()
        val classpath = buildClasspath(moduleDir, unit.sourceSet, compileClasspath)
        val args = buildArgs(unit, classpath, outputDir, sources, moduleDir)
        val output = runKotlinc(kotlinCompilerClasspath, repositoryRoot, unit, args)
        // rc==0 with warnings is NORMAL — extraction is the parse, gating is the baseline diff.
        return CompilerWarningsParser.parse(output)
    }

    private fun sourcesFor(
        moduleDir: File,
        sourceSet: String,
        repositoryRoot: File,
    ): List<String> =
        File(moduleDir, "src/$sourceSet/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { repositoryRoot.toPath().relativize(it.toPath()).toString() }
            .sorted()
            .toList()

    private fun buildClasspath(
        moduleDir: File,
        sourceSet: String,
        compileClasspath: Collection<File>,
    ): String {
        val ownDirs = ownAndFriendDirs(moduleDir, sourceSet).filter { it.isDirectory }.map { it.absolutePath }
        return (ownDirs + compileClasspath.map { it.absolutePath })
            .distinct()
            .joinToString(File.pathSeparator)
    }

    private fun ownAndFriendDirs(
        moduleDir: File,
        sourceSet: String,
    ): List<File> =
        when (sourceSet) {
            "main" -> {
                listOf(
                    File(moduleDir, "build/classes/kotlin/main"),
                    File(moduleDir, "build/classes/java/main"),
                )
            }

            "test" -> {
                listOf(
                    File(moduleDir, "build/classes/kotlin/main"),
                    File(moduleDir, "build/classes/java/main"),
                    File(moduleDir, "build/classes/java/test"),
                    File(moduleDir, "build/classes/kotlin/testFixtures"),
                    File(moduleDir, "build/classes/java/testFixtures"),
                )
            }

            else -> {
                listOf(
                    File(moduleDir, "build/classes/kotlin/main"),
                    File(moduleDir, "build/classes/java/main"),
                )
            }
        }

    private fun buildArgs(
        unit: CompileUnitSpec,
        classpath: String,
        outputDir: File,
        sources: List<String>,
        moduleDir: File,
    ): List<String> {
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
        val friendPaths =
            when (unit.sourceSet) {
                "main" -> {
                    emptyList()
                }

                "test" -> {
                    listOf(
                        File(moduleDir, "build/classes/kotlin/main"),
                        File(moduleDir, "build/classes/kotlin/testFixtures"),
                    ).filter { it.isDirectory }
                }

                else -> {
                    listOf(File(moduleDir, "build/classes/kotlin/main"))
                }
            }
        friendPaths.forEach { args += listOf("-Xfriend-paths=${it.absolutePath}") }
        args += sources
        return args
    }

    private fun runKotlinc(
        kotlinCompilerClasspath: Collection<File>,
        repositoryRoot: File,
        unit: CompileUnitSpec,
        args: List<String>,
    ): String {
        val javaExecutable =
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
        val proc =
            ProcessBuilder(
                listOf(
                    javaExecutable,
                    COMPILER_HEAP,
                    "-cp",
                    kotlinCompilerClasspath.joinToString(File.pathSeparator),
                    K2_JVM_COMPILER,
                ) + args,
            ).directory(repositoryRoot)
                .redirectErrorStream(true)
                .start()
        val output = drain(proc)
        if (proc.isAlive) {
            proc.destroyForcibly()
            throw GradleException(
                "verifyCompilerWarnings: standalone kotlinc for ${unit.modulePath}/${unit.sourceSet} " +
                    "did not exit within $TIMEOUT_MINUTES minutes — failing closed.",
            )
        }
        // FAIL-CLOSED: a non-zero compiler exit means the unit could not be
        // analysed. Tool failure is NOT zero findings — error text contains no
        // `w:` lines, so silently proceeding would green-light the module.
        val rc = proc.waitFor()
        if (rc != 0) {
            throw GradleException(
                "verifyCompilerWarnings: standalone kotlinc for ${unit.modulePath}/${unit.sourceSet} " +
                    "failed (rc=$rc): ${output.take(ERROR_EXCERPT_CHARS)}",
            )
        }
        return output
    }

    private fun drain(proc: Process): String {
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
            Thread.sleep(POLL_INTERVAL_MS)
        }
        // The poll loop breaks as soon as the process dies — drain whatever the
        // pipe still holds (kotlinc emits ALL warnings on stdout; unlike Detekt,
        // there is no report file). readText() blocks only until EOF, which the
        // closed pipe guarantees after process exit.
        output.append(reader.readText())
        return output.toString()
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
    private companion object {
        const val MAX_VIOLATIONS_REPORTED = 50
        const val GIT_DIFF_EXCERPT_CHARS = 500
    }

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
        val reportDir = reportsDir.get().asFile.apply { mkdirs() }
        val baseline = loadBaseline()

        val diff = gitDiff(root, baseRef.get())
        val deltaModules = deltaModules(diff)
        val globalInvalidation = globalConfigInvalidated(diff)
        if (deltaModules.isEmpty() && !baselineChanged(diff) && !globalInvalidation) {
            File(reportDir, "summary.txt").writeText(
                "No modules, baseline, or global build configuration changed in the delta — nothing to verify.\n",
            )
            logger.lifecycle("compiler-warnings: no changed modules; gate passes trivially")
            return
        }
        // Baseline edits change the comparison universe — recompile everything so
        // removals are validated against the current inventory (fail-closed). The
        // same applies to global compiler/build configuration (versions.toml,
        // settings, gradle.properties, build-logic conventions): a Java-only or
        // build-script-only change can introduce Kotlin warnings indirectly.
        val verifyModules =
            if (baselineChanged(diff) || globalInvalidation) {
                logger.lifecycle(
                    if (baselineChanged(diff)) {
                        "compiler-warnings: baseline changed in delta — full verification"
                    } else {
                        "compiler-warnings: global build/version configuration changed in delta — full verification"
                    },
                )
                compileUnits.get().map { it.modulePath }.toSet()
            } else {
                deltaModules
            }
        logger.lifecycle("compiler-warnings: verifying modules ${verifyModules.sorted().joinToString()}")

        val current = collectCurrent(root, reportDir, verifyModules)

        val violations = CompilerWarningsBaselineVerifier.compare(current, baseline)
        writeReports(reportDir, current, baseline, deltaModules, violations)
        if (violations.isNotEmpty()) {
            val more =
                if (violations.size > MAX_VIOLATIONS_REPORTED) {
                    "\n  ... and ${violations.size - MAX_VIOLATIONS_REPORTED} more"
                } else {
                    ""
                }
            throw GradleException(
                "verifyCompilerWarnings: ${violations.size} warning(s) not covered by the baseline.\n" +
                    violations.take(MAX_VIOLATIONS_REPORTED).joinToString("\n") { formatViolation(it) } +
                    more +
                    "\n\nThe baseline is a ceiling, not an allowance budget. Fix the warnings or, for a " +
                    "deliberate removal, update config/warnings/baseline.json (removals only).",
            )
        }
        logger.lifecycle("compiler-warnings: ${current.size} baseline-covered warning identities; gate green")
    }

    private fun formatViolation(v: CompilerWarningsBaselineVerifier.Violation): String =
        "  ${v.path} [${v.diagnostic}] current=${v.currentCount} baseline=${v.baselineCount} ${v.message}"

    private fun loadBaseline(): List<WarningEntry> {
        val baselineFile = baselineFiles.files.firstOrNull { it.exists() }
        val baseline = CompilerWarningsBaselineIo.fromJson(baselineFile?.readText())
        if (baseline == null) {
            throw GradleException(
                "verifyCompilerWarnings: baseline " +
                    "${baselineFile?.absolutePath ?: "<missing>"} is absent or malformed. " +
                    "The gate fails closed without a valid baseline. Regenerate intentionally with " +
                    "bootstrapCompilerWarningsBaseline.",
            )
        }
        // Growth protection: the working-tree baseline must never expand relative
        // to the certified base baseline (allowance-file attack — add warnings +
        // expand baseline in one PR). Bootstrap (base absent) is allowed only once.
        val baseBaseline = gitShowFile(File(repositoryRoot.get()), baseRef.get(), "config/warnings/baseline.json")
        val growth =
            CompilerWarningsBaselineGrowthVerifier.verify(
                CompilerWarningsGrowthInput(baseBaseline, baselineFile?.readText()),
            )
        if (!growth.passed) {
            throw GradleException("verifyCompilerWarnings: ${growth.message}")
        }
        return baseline
    }

    private fun collectCurrent(
        root: File,
        reportDir: File,
        verifyModules: Set<String>,
    ): List<WarningEntry> {
        val current = mutableListOf<WarningEntry>()
        val outBase = File(reportDir, "kotlinc-out").apply { mkdirs() }
        compileUnits
            .get()
            .sortedBy { it.modulePath }
            .filter { it.modulePath in verifyModules }
            .forEach { unit ->
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
        return current
    }

    private fun writeReports(
        reportDir: File,
        current: List<WarningEntry>,
        baseline: List<WarningEntry>,
        deltaModules: Set<String>,
        violations: List<CompilerWarningsBaselineVerifier.Violation>,
    ) {
        File(reportDir, "warnings.txt").writeText(
            current.joinToString("\n") { "${it.path} [${it.diagnostic}] x${it.count} ${it.message}" },
        )
        File(reportDir, "violations.txt").writeText(
            if (violations.isEmpty()) {
                "(none)\n"
            } else {
                violations.joinToString("\n") { formatViolation(it) }
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
    }

    // spotless re-joins single-expression functions; the resulting line exceeds
    // 120 cols, so detekt is told to look the other way.
    @Suppress("MaxLineLength")
    private fun baselineChanged(diff: String): Boolean = diff.lineSequence().any { it.contains("config/warnings/baseline.json") }

    private fun deltaModules(diff: String): Set<String> = compilerDeltaModules(diff)

    private fun gitDiff(
        root: File,
        ref: String,
    ): String {
        val rev = runGit(root, "rev-parse", "--verify", "--quiet", "$ref^{commit}")
        if (rev.exitCode != 0) {
            throw GradleException(
                "verifyCompilerWarnings: base ref '$ref' does not resolve. " +
                    "Pass -PtramaiCompilerWarningsBaseRef=<exact sha> (CI: pull_request.base.sha / event.before).",
            )
        }
        val diff = runGit(root, "diff", "--name-only", "$ref...HEAD")
        if (diff.exitCode != 0) {
            throw GradleException(
                "verifyCompilerWarnings: git diff against '$ref' failed: " +
                    diff.output.take(GIT_DIFF_EXCERPT_CHARS),
            )
        }
        return diff.output
    }

    private data class GitResult(
        val exitCode: Int,
        val output: String,
    )

    private fun runGit(
        root: File,
        vararg args: String,
    ): GitResult {
        val proc =
            ProcessBuilder(listOf("git") + args)
                .directory(root)
                .redirectErrorStream(true)
                .start()
        val out = proc.inputStream.bufferedReader().readText()
        return GitResult(proc.waitFor(), out)
    }

    /** git show <ref>:<repoRelPath> — null when the file does not exist at that ref. */
    private fun gitShowFile(
        root: File,
        ref: String,
        repoRelPath: String,
    ): String? {
        val result = runGit(root, "show", "$ref:$repoRelPath")
        return if (result.exitCode == 0) result.output else null
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
        val stagingDir = File(root, "build/compiler-warnings-bootstrap-staging").apply { mkdirs() }
        for (unit in compileUnits.get().sortedBy { it.modulePath }) {
            val unitKey = unit.modulePath.removePrefix(":").replace(':', '_') + "-" + unit.sourceSet
            val staging = File(stagingDir, "$unitKey.json")
            val entries =
                if (staging.exists()) {
                    // Resume: a previous interrupted bootstrap already captured this
                    // unit (watchdog/harness kills cannot lose progress).
                    CompilerWarningsBaselineIo.fromJson(staging.readText()).orEmpty()
                } else {
                    val out = File(outBase, unitKey)
                    out.mkdirs()
                    val captured =
                        KotlincRunner.compileWarnings(
                            unit,
                            kotlinCompilerClasspath.files,
                            compileClasspath.files,
                            root,
                            out,
                        )
                    staging.writeText(CompilerWarningsBaselineIo.toJson(captured))
                    captured
                }
            all += entries
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

/**
 * Modules whose Kotlin/Java sources or build scripts changed in the diff
 * (10.1c round-4): a Java-only change can still introduce Kotlin warnings
 * (e.g. a deprecated Java API used from Kotlin), so .java is in scope too.
 * Top-level so the selection logic is unit-testable without a task instance.
 */
internal fun compilerDeltaModules(diff: String): Set<String> =
    diff
        .lineSequence()
        .filter { line ->
            line.endsWith(".kt") || line.endsWith(".java") ||
                line.endsWith("build.gradle.kts") || line.endsWith("build.gradle")
        }.mapNotNull { line -> moduleOf(line) }
        .toSet()

private fun moduleOf(line: String): String? {
    val parts = line.split("/")
    return when {
        parts.size >= MIN_DELTA_SEGMENTS && parts[0] == "examples" -> ":examples:${parts[1]}"
        parts.size >= 2 -> ":" + parts[0]
        else -> null
    }
}

private const val MIN_DELTA_SEGMENTS = 3

/**
 * True when the delta touches global compiler/build configuration: a future
 * version-catalog or convention change can introduce Kotlin warnings indirectly
 * (upgraded library annotations, changed compiler options), so the whole
 * repository must be re-verified (10.1c round-4).
 */
internal fun globalConfigInvalidated(diff: String): Boolean =
    diff.lineSequence().any { line ->
        line == "gradle/libs.versions.toml" ||
            line == "gradle.properties" ||
            line == "settings.gradle.kts" ||
            line == "settings.gradle" ||
            line == "build.gradle.kts" ||
            line == "build.gradle" ||
            line.startsWith("build-logic/")
    }
