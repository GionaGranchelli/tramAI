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
     * [environment] into the inherited process environment, and returns the
     * captured stdout+stderr and exit code.
     */
    fun run(
        executable: File,
        arguments: List<String>,
        environment: Map<String, String>,
        workingDirectory: File,
    ): ProcessResult
}

/** Result of an adapter process launch. */
data class ProcessResult(val exitCode: Int, val output: String)

/**
 * [ProcessBuilder] implementation. Always captures combined stdout+stderr and
 * returns it; the historical calls that used inheritIO() never read the output,
 * so capture-only is behavior-preserving (output is only surfaced in the
 * require() diagnostics that already included it on master).
 */
class ProcessBuilderProcessAdapter : EvidenceBundleProcessAdapter {

    override fun run(
        executable: File,
        arguments: List<String>,
        environment: Map<String, String>,
        workingDirectory: File,
    ): ProcessResult {
        val pb = ProcessBuilder(listOf(executable.path) + arguments)
            .directory(workingDirectory)
            .redirectErrorStream(true)
        pb.environment().putAll(environment)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return ProcessResult(exitCode, output)
    }
}
