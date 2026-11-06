package dev.tramai.build.release

import dev.tramai.build.quality.MergeGateVerifier
import dev.tramai.build.quality.pullRequestPathFilters
import dev.tramai.build.quality.requiredIdentities
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

/** One changed path per line, so applicability can be decided from real pull request contents. */
private const val CHANGED_PATHS_JQ = ".[].filename"

/** GitHub's changed-file count, compared against the retrieved paths to detect a partial read. */
private const val CHANGED_FILES_JQ = ".changedFiles"

/**
 * Every check a merge requires, as observed on a green rollup. Each entry is either a complete name or the
 * stable leading part of one, which tolerates both the matrix arguments that trail a name and the
 * 100-character truncation GitHub applies to the longest of them.
 *
 * `validate` is deliberately **not** here: it is the job of a path-filtered workflow, so it is required
 * through [CONDITIONAL_CHECKS] where its applicability can be decided from the pull request's contents.
 */
private val REQUIRED_CHECK_IDENTITIES =
    listOf(
        "api-architecture (verify060Architecture)",
        "quality (compiler-warnings",
        "quality (static",
        "quality (hygiene",
        "quality (junit-test-signatures",
        "contract-tests (formatting",
        "contract-tests (static-safety",
        "contract-tests (compiler-deps",
        "contract-tests (static-analysis",
        "contract-tests (ci-wiring",
        "contract-tests (api-compatibility",
        "tests",
        "consumer-smoke",
        "artifact-prep",
        "performance-policy-tests",
        "zero-egress",
        "GitGuardian Security Checks",
    )

/** The RC workflow, its job, and the file whose `paths:` filter decides whether both are required. */
private const val RC_WORKFLOW_NAME = "Sovereign Runtime Release Candidate"

private const val RC_WORKFLOW_CHECK = "validate"

private const val RC_WORKFLOW_FILE = ".github/workflows/sovereign-runtime-release-candidate.yml"

/**
 * Workflows required on **every** pull request. The path-filtered ones are conditional instead: see
 * [MergeGateVerifier.Evidence.conditionalWorkflows].
 */
private val REQUIRED_WORKFLOWS = listOf("CI", "Architecture Authority")

/**
 * Checks required only when their producing workflow is **applicable**, with the path filters that workflow
 * declares. Transcribed from `.github/workflows/mutation-authority-integration.yml`; if that workflow's
 * `paths:` change, this must change with them.
 *
 * Why not unconditional: a green check list records what ran on one pull request, which is not the contract
 * for what must run on every pull request. Making a path-filtered workflow unconditionally required would
 * refuse unrelated work — the failure this gate exists to prevent.
 *
 * The RC workflow's entry is added at execution time from the filters that workflow itself declares, so the
 * two cannot drift.
 */
private val CONDITIONAL_CHECKS =
    mapOf(
        "Mutation authority transport" to
            listOf(
                "build-logic/src/main/kotlin/dev/tramai/build/quality/**",
                "build-logic/src/test/kotlin/dev/tramai/build/quality/Mutation*.kt",
                "build-logic/build.gradle.kts",
                "config/quality/**",
                ".github/workflows/mutation-authority-integration.yml",
            ),
    )

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
    // The complete set of check identities a merge requires, each matched as an exact name or its
    // "<identity>, ..." matrix form, and EVERY entry required. When the CI matrix changes, change this
    // list: the gate then refuses a rollup that omits an identity, which is the point — a silent job
    // rename must not silently weaken the gate. Override with -PrequiredChecks="a;b" (semicolons, because
    // the names themselves contain commas).
    val override = (findProperty("requiredChecks") as String?)?.trim()
    val parsedOverride = override?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }
    // `require` rather than `throw GradleException`: the same refusal, without a third throw statement in
    // this function (detekt's ThrowsCount counts them).
    require(override == null || !parsedOverride.isNullOrEmpty()) {
        "requiredChecks was supplied but is empty. An empty override would disable required-check " +
            "coverage entirely, which is a refusal rather than a pass; omit the property to use the defaults."
    }
    // Additive, not replacing. An override that replaced the defaults would be a bypass by flag:
    // -PrequiredChecks=tests would drop the other seventeen and still authorise a merge.
    val requiredCheckIdentities =
        requiredIdentities(REQUIRED_CHECK_IDENTITIES, parsedOverride.orEmpty())

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
            // The RC workflow's own path filters decide whether its run and its `validate` job are required
            // for this pull request. Read from the workflow file, so the two cannot drift; a file that cannot
            // be read yields a match-everything filter, which demands the run rather than silently dropping
            // it — the fail-closed direction.
            val rcFilters = pullRequestPathFilters(readWorkflowFile(RC_WORKFLOW_FILE))
            val evidence =
                MergeGateVerifier.Evidence(
                    expectedHead = expectedHeadProperty,
                    reportedHead = fetchHead(prNumberProperty),
                    checkStates = checks.map { it.state },
                    checkNames = checks.map { it.name },
                    requiredCheckIdentities = requiredCheckIdentities,
                    conditionalChecks = CONDITIONAL_CHECKS + (RC_WORKFLOW_CHECK to rcFilters),
                    conditionalWorkflows = mapOf(RC_WORKFLOW_NAME to rcFilters),
                    changedPaths = fetchChangedPaths(prNumberProperty),
                    changedFilesTotal = fetchChangedFilesTotal(prNumberProperty),
                    workflowRuns = fetchRuns(expectedHeadProperty),
                    requiredWorkflows = REQUIRED_WORKFLOWS,
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

/**
 * The text of a repository file an applicability rule comes from; empty when it cannot be read.
 *
 * Empty is not "no filters": [pullRequestPathFilters] turns it into a match-everything filter, so an
 * unreadable workflow file keeps its requirement in force instead of silently dropping it.
 */
private fun Project.readWorkflowFile(relativePath: String): String =
    runCatching { rootProject.file(relativePath).readText() }.getOrElse { failure ->
        if (failure is CancellationException || failure is Error) {
            throw failure
        }
        logger.warn("merge gate could not read $relativePath: ${failure.message}")
        ""
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

/**
 * The pull request's changed paths, retrieved through the **paginated** files endpoint rather than
 * `gh pr view --json files`, which is documented to truncate at 100 files. A partial list is the dangerous
 * shape: it looks complete, so applicability would be decided from missing evidence. Note `filename`, not
 * `path` — the REST shape differs from the `gh pr view` one.
 */
private fun Project.fetchChangedPaths(prNumber: String): List<String> =
    gh("api", "repos/{owner}/{repo}/pulls/$prNumber/files", "--paginate", "--jq", CHANGED_PATHS_JQ)
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/**
 * GitHub's own changed-file count, from the pull request object. The retrieved paths are compared against it
 * so a truncated or partial response is refused rather than trusted: some evidence is not all evidence.
 */
private fun Project.fetchChangedFilesTotal(prNumber: String): Int =
    gh("pr", "view", prNumber, "--json", "changedFiles", "--jq", CHANGED_FILES_JQ).trim().toIntOrNull() ?: 0

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
