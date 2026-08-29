package dev.tramai.build.sovereign

import java.io.File

/**
 * Process boundary for the sovereign-lab evidence-bundle scenario (Epic 9.2d-a3b2b).
 *
 * Every external process launched by [EvidenceBundleScenarioRunner] routes through
 * this single adapter — bash scripts, python3 -c mutations, sha256sum -c,
 * tar -xzf and openssl — so the runner is pure Kotlin with no Gradle/Project
 * dependency at execution time and can be driven by a fake adapter in tests.
 */
interface EvidenceBundleProcessAdapter {

    /**
     * Runs [executable] with [arguments] in [workingDirectory], merging
     * [environment] into the inherited process environment.
     *
     * [mode] selects the output contract:
     * - [ProcessOutputMode.INHERIT] mirrors the historical `inheritIO()`
     *   calls — the child's stdout/stderr go live to the Gradle console and
     *   the returned [ProcessResult.output] is empty.
     * - [ProcessOutputMode.CAPTURE] mirrors the historical
     *   `redirectErrorStream(true)` calls — combined stdout+stderr are
     *   captured and returned for the require() diagnostics that read them.
     */
    fun run(
        executable: File,
        arguments: List<String>,
        environment: Map<String, String>,
        workingDirectory: File,
        mode: ProcessOutputMode = ProcessOutputMode.CAPTURE,
    ): ProcessResult
}

/** Historical process-output semantics preserved call-by-call (9.2d-a3b2b P2-1). */
enum class ProcessOutputMode {
    /** Mirrors `ProcessBuilder.inheritIO()` — output streams live to the console. */
    INHERIT,

    /** Mirrors `ProcessBuilder.redirectErrorStream(true)` — output captured. */
    CAPTURE,
}

/** Result of an adapter process launch. */
data class ProcessResult(val exitCode: Int, val output: String)

/**
 * [ProcessBuilder] implementation. Preserves the historical per-call output
 * semantics: INHERIT forwards the child streams to the console (output is
 * empty in the result), CAPTURE merges stderr into stdout and returns it.
 */
class ProcessBuilderProcessAdapter : EvidenceBundleProcessAdapter {

    override fun run(
        executable: File,
        arguments: List<String>,
        environment: Map<String, String>,
        workingDirectory: File,
        mode: ProcessOutputMode,
    ): ProcessResult {
        val pb = ProcessBuilder(listOf(executable.path) + arguments)
            .directory(workingDirectory)
        pb.environment().putAll(environment)
        val process = when (mode) {
            ProcessOutputMode.INHERIT -> {
                pb.inheritIO()
                pb.start()
            }
            ProcessOutputMode.CAPTURE -> {
                pb.redirectErrorStream(true)
                pb.start()
            }
        }
        val output = if (mode == ProcessOutputMode.CAPTURE) {
            process.inputStream.bufferedReader().readText()
        } else {
            ""
        }
        val exitCode = process.waitFor()
        return ProcessResult(exitCode, output)
    }
}
