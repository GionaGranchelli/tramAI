package dev.tramai.build.release

import dev.tramai.build.quality.MergeGateVerifier
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.util.concurrent.CancellationException

// The durable form of the merge-gate correction: on 2026-10-12 a merge proceeded through a gate whose query
// was invalid (`gh api … --json`), so it read nothing, reported success, and merged. The merge was correct by
// luck of ordering, not because anything checked it.
//
// Everything that can go wrong at the boundary — `gh` absent, an invalid flag, a non-zero exit, an empty
// response — yields empty evidence, and empty evidence is REFUSED by [MergeGateVerifier]. The rules live there
// and are unit-tested; this is only the transport.
//
// It lives outside [TramaiReleaseVerificationPlugin] deliberately: registering it there pushed that class over
// the `LargeClass` line, and a new gate is not a reason to grow a class that already carries every release
// task in the build.

/** The jq expression yielding one `name|state` line per check. A constant so the line is not 159 characters. */
private const val CHECK_LINES_JQ =
    """.statusCheckRollup[] | "\(.name // .context)|""" +
        """\(if .__typename == "CheckRun" then (.conclusion // .status) else .state end)""""

/** The jq expression yielding one `name|status|conclusion|headSha` line per workflow run. */
private const val WORKFLOW_RUN_LINES_JQ =
    """.workflow_runs[] | "\(.name)|\(.status)|\(.conclusion // "null")|\(.head_sha)""""

/** How much of `gh`'s stderr to quote in a diagnostic; enough to identify the failure, not a log dump. */
private const val GH_STDERR_QUOTE_LIMIT = 400

/**
 * Registers `verifyMergeGate` on the root project.
 *
 * It refuses to authorise a merge unless the exact head has complete, green status checks and the required
 * workflows succeeded at that commit. See [MergeGateVerifier] for the rules and
 * `scripts/verify-merge-gate.sh` for the entry point, including the merge it binds to this commit.
 */
internal fun Project.registerVerifyMergeGate() {
    val expectedHeadProperty = (findProperty("expectedHead") as String?)?.trim().orEmpty()
    val prNumberProperty = (findProperty("prNumber") as String?)?.trim().orEmpty()
    val requiredWorkflows = listOf("CI", "Architecture Authority", "Sovereign Runtime Release Candidate")
    // Check identities that must appear in the rollup. A count floor cannot establish coverage: a partial
    // read whose omitted entries were failures would satisfy it. Overridable with -PrequiredChecks=a,b when
    // the CI partition names change.
    val requiredCheckPrefixes =
        (findProperty("requiredChecks") as String?)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("quality (", "contract-tests (")

    tasks.register("verifyMergeGate") {
        group = "verification"
        description =
            "Refuses to authorise a merge unless the exact head has complete, green status checks and the " +
            "required workflows succeeded at that commit. Fails closed when gh or its data is unavailable."
        doLast {
            val failures = mutableListOf<String>()
            if (expectedHeadProperty.isEmpty()) {
                failures += "expectedHead (the reviewed commit)"
            }
            if (prNumberProperty.isEmpty()) {
                failures += "prNumber"
            }
            if (failures.isNotEmpty()) {
                throw GradleException(
                    "verifyMergeGate requires ${failures.joinToString(" and ")}, e.g. " +
                        "-PexpectedHead=<sha> -PprNumber=<number>. Without an explicit commit there is no " +
                        "evidence to tie the checks to, which is a refusal.",
                )
            }
            val checks = fetchChecks(prNumberProperty)
            val evidence =
                MergeGateVerifier.Evidence(
                    expectedHead = expectedHeadProperty,
                    reportedHead = fetchHead(prNumberProperty),
                    checkStates = checks.map { it.state },
                    checkNames = checks.map { it.name },
                    requiredCheckPrefixes = requiredCheckPrefixes,
                    workflowRuns = fetchRuns(expectedHeadProperty),
                    requiredWorkflows = requiredWorkflows,
                )
            val violations = MergeGateVerifier.verify(evidence)
            val rendered = MergeGateVerifier.render(expectedHeadProperty, violations)
            if (violations.isEmpty()) {
                logger.lifecycle(rendered)
            } else {
                throw GradleException(rendered)
            }
        }
    }
}

/** The head commit the API reports for the pull request, trimmed: `gh` appends a newline. */
private fun Project.fetchHead(prNumber: String): String {
    // Block body with an explicit return on purpose: ktlint collapses an expression body onto one line,
    // which puts this past detekt's 120-character limit. Splitting an expression body achieves nothing.
    val raw = gh("pr", "view", prNumber, "--json", "headRefOid", "--jq", ".headRefOid")
    return raw.trim()
}

/** One line per check, carrying name and state together so they cannot come from different reads. */
private fun Project.fetchChecks(prNumber: String): List<MergeGateVerifier.Check> =
    MergeGateVerifier.parseChecks(
        gh("pr", "view", prNumber, "--json", "statusCheckRollup", "--jq", CHECK_LINES_JQ),
    )

private fun Project.fetchRuns(head: String): List<MergeGateVerifier.WorkflowRun> =
    MergeGateVerifier.parseWorkflowRuns(
        gh("api", "repos/{owner}/{repo}/actions/runs?head_sha=$head&per_page=50", "--jq", WORKFLOW_RUN_LINES_JQ),
    )

/**
 * Runs `gh`, returning stdout only on a clean exit. Any failure — `gh` missing, a bad flag, a non-zero exit —
 * returns an empty string, so the rules refuse instead of passing.
 *
 * `runCatching` rather than `catch (e: Exception)`: a boundary that must fail closed on any consultable
 * failure has no concrete type to name, and detekt's `TooGenericExceptionCaught` is right that spelling a
 * catch for it is unclear about intent. Cancellation and `Error` are rethrown — reading either as "no
 * evidence" would turn a cancelled run or a JVM fault into a governance refusal.
 */
private fun Project.gh(vararg args: String): String =
    runCatching {
        val process =
            ProcessBuilder(listOf("gh") + args)
                .directory(rootProject.projectDir)
                .redirectErrorStream(false)
                .start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code == 0) {
            out
        } else {
            logger.warn(
                "gh ${args.joinToString(" ")} failed (exit $code): ${err.trim().take(GH_STDERR_QUOTE_LIMIT)}",
            )
            ""
        }
    }.getOrElse { failure ->
        if (failure is CancellationException || failure is Error) {
            throw failure
        }
        logger.warn("gh ${args.joinToString(" ")} could not run: ${failure.message}")
        ""
    }
