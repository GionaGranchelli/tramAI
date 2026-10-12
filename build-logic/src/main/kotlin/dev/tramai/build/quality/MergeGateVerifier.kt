package dev.tramai.build.quality

import java.nio.file.FileSystems
import java.nio.file.Paths

/** Fields in one `name|status|conclusion|headSha` line. */
private const val WORKFLOW_FIELD_COUNT = 4

/** Fields in one `name|state` line. */
private const val CHECK_FIELD_COUNT = 2

/** The separator the fetcher uses between fields. */
private const val FIELD_SEPARATOR = '|'

/**
 * Does a workflow's declared path filter match one changed path? Uses the JDK's own glob matcher rather than
 * a hand-rolled one, so `**` and `*` mean what they mean everywhere else.
 *
 * An unusable pattern counts as a match: applicability is the fail-closed direction, because treating an
 * unparseable filter as inapplicable would let a typo silently drop a required check.
 */
private fun matchesGlob(
    glob: String,
    path: String,
): Boolean =
    runCatching { FileSystems.getDefault().getPathMatcher("glob:$glob").matches(Paths.get(path)) }
        .getOrDefault(true)

/**
 * The identities that apply to this pull request: the unconditional set, plus every conditional check whose
 * parent workflow's filters match a changed path. File-level rather than a member so the rule method stays
 * within the method-length limit without the object growing another function.
 */
private fun applicableIdentities(evidence: MergeGateVerifier.Evidence): List<String> =
    evidence.requiredCheckIdentities +
        evidence.conditionalChecks
            .filter { (_, globs) ->
                globs.any { glob -> evidence.changedPaths.any { path -> matchesGlob(glob, path) } }
            }.keys

/**
 * The refusals for a rollup whose required identities have been decided: an inconsistent read, and every
 * effective identity that is missing or did not succeed. File-level for the same length reason as above.
 */
private fun coverageViolations(evidence: MergeGateVerifier.Evidence): List<MergeGateVerifier.Violation> {
    val effective = applicableIdentities(evidence)
    val violations = mutableListOf<MergeGateVerifier.Violation>()
    if (evidence.checkNames.size != evidence.checkStates.size) {
        violations +=
            MergeGateVerifier.Violation(
                "checks-inconsistent",
                "read ${evidence.checkNames.size} check name(s) but ${evidence.checkStates.size} " +
                    "state(s); the response is inconsistent",
            )
    }
    // Every effective identity must be present, not one per family: a partial read can contain a single
    // member of each family and still be missing the checks that matter.
    val present = { identity: String ->
        evidence.checkNames.any { it == identity || it.startsWith("$identity,") }
    }
    val missing = effective.filterNot(present)
    if (missing.isNotEmpty()) {
        violations +=
            MergeGateVerifier.Violation(
                "required-check-missing",
                "${missing.size} required check(s) absent from the rollup: ${missing.joinToString(", ")}",
            )
    }
    // An effective check must have SUCCEEDED. SKIPPED and NEUTRAL stay acceptable only for checks that are
    // not required, so presence alone cannot authorise a merge.
    val states = evidence.checkNames.zip(evidence.checkStates)
    val notSuccessful =
        effective.filter(present).filter { identity ->
            val state = states.first { it.first == identity || it.first.startsWith("$identity,") }
            state.second != "SUCCESS"
        }
    if (notSuccessful.isNotEmpty()) {
        violations +=
            MergeGateVerifier.Violation(
                "required-check-not-successful",
                "${notSuccessful.size} required check(s) did not succeed: ${notSuccessful.joinToString(", ")}",
            )
    }
    return violations
}

/**
 * The required identities for a run: the built-in set plus any additions, deduplicated. An override may only
 * **add**.
 *
 * Replacing would be a bypass by flag rather than by query: `-PrequiredChecks=tests` would drop the other
 * seventeen identities and still authorise a merge, which is the fail-open shape this gate exists to refuse.
 * Additions-only means the flag cannot narrow the gate, and the only way to change the contract is to change
 * the defaults — in the source, where they are reviewable.
 *
 * File level and `internal` so the invariant is unit-testable: inline in the task it would not be, and this is
 * exactly the rule a later simplification would quietly restore to replacement.
 */
internal fun requiredIdentities(
    defaults: List<String>,
    additions: List<String>,
): List<String> = (defaults + additions).distinct()

/**
 * Decides whether a pull request may be merged, from evidence that has already been fetched.
 *
 * ## Why this exists
 *
 * A merge was performed on 2026-10-12 through a gate whose query was invalid (`gh api … --json
 * statusCheckRollup`; that flag belongs to `gh pr view`). The query returned an empty string, the gate's
 * tally was empty, its numeric comparison failed, and it reported success and merged. The merge happened to
 * be correct — the head was verified elsewhere — but nothing in the gate checked it, and a red pull request
 * would have passed identically.
 *
 * Recalling that is not a safeguard. This is: the same rules, executable and regression-tested, so a gate
 * that reads nothing cannot report success again.
 *
 * ## Shape
 *
 * Pure by construction — it takes already-parsed values and performs no I/O — so every rule is directly
 * testable and the fetching is a separate, replaceable step. Like the other verifiers in this package it
 * does not throw on bad input; it reports violations. Rule groups are separate functions returning lists,
 * which keeps each one readable and each rule's trigger visible in one place.
 *
 * ## What it does not do
 *
 * It cannot verify that the *fetcher* supplied everything the API holds, and cardinality cannot establish
 * it: [Evidence.minSuccesses] is a sanity floor, and a partial response whose omitted entries were failures
 * would still satisfy it. Completeness comes from [Evidence.requiredCheckPrefixes] — the required check
 * identities must be present in the rollup — and a names/states length mismatch is refused as an
 * inconsistent read. The fetcher remains separately responsible for failing closed when `gh` itself errors.
 */
object MergeGateVerifier {
    /** Conclusions and states that permit a merge. Anything else is pending or unsuccessful. */
    val ACCEPTED_CHECK_STATES = setOf("SUCCESS", "NEUTRAL", "SKIPPED")

    /** A workflow run must be in this status with this conclusion. */
    const val REQUIRED_WORKFLOW_STATUS = "COMPLETED"

    const val REQUIRED_WORKFLOW_CONCLUSION = "SUCCESS"

    /** One workflow run, as recorded for a head commit. */
    data class WorkflowRun(
        val name: String,
        val status: String,
        val conclusion: String?,
        val headSha: String,
    )

    /** One status check as the rollup reports it. */
    data class Check(
        val name: String,
        val state: String,
    )

    /** Everything the verifier needs, already fetched and parsed. */
    data class Evidence(
        val expectedHead: String,
        val reportedHead: String?,
        val checkStates: List<String>,
        val workflowRuns: List<WorkflowRun>,
        val requiredWorkflows: List<String>,
        /**
         * A gate that read nothing must not pass; also guards against a query returning a partial set. This is
         * a sanity floor only — it cannot establish completeness, because a partial response whose omitted
         * entries were failures would still satisfy it. Completeness comes from [requiredCheckIdentities].
         */
        val minSuccesses: Int = 10,
        /**
         * The **complete** set of check identities that must be present *and* successful. Each entry is
         * either an exact rollup name or the stable leading part of one, and **every** entry is required:
         * requiring one name per job family is satisfied by a partial read that happens to contain a single
         * member of each family.
         *
         * Matched as `name == identity` or `name.startsWith("$identity,")`, which keeps entries short enough
         * to read while tolerating two GitHub behaviours: the matrix arguments trailing the name, and the
         * 100-character truncation GitHub applies to the longest ones.
         *
         * Empty means the caller does not require identity coverage, and the identity rules do not apply.
         */
        val requiredCheckIdentities: List<String> = emptyList(),
        /**
         * Checks that are required only when their producing workflow is **applicable**: identity to the path
         * filters that workflow declares. A path-filtered workflow GitHub legitimately skipped must not block
         * a merge, and a workflow whose filters do match must not be allowed to go missing — otherwise the
         * gate refuses unrelated work, which is the failure it exists to prevent.
         *
         * The contract here is applicability, not a historical rollup: a green check list records what ran on
         * one pull request, which is not the same as what must run on every pull request.
         */
        val conditionalChecks: Map<String, List<String>> = emptyMap(),
        /** The pull request's changed paths, used to decide applicability. Empty means unread, not "none". */
        val changedPaths: List<String> = emptyList(),
        /**
         * GitHub's own changed-file count, so a **partial** path list is detectable. A truncated response
         * looks complete — a pull request whose 101st file is the only match would otherwise be judged
         * inapplicable from evidence that omits it. Zero means the count was not read, which refuses.
         */
        val changedFilesTotal: Int = 0,
        /** The rollup's check names, paired by position with [checkStates]. */
        val checkNames: List<String> = emptyList(),
    )

    /** A reason a merge is refused, named so the failure is auditable rather than a bare "gate failed". */
    data class Violation(
        val rule: String,
        val detail: String,
    )

    /**
     * All refusals for this evidence, in the order a reader needs them: nothing to tie the checks to, then
     * the checks themselves, then their coverage, then the workflow runs.
     */
    fun verify(evidence: Evidence): List<Violation> {
        // With no expected head there is nothing to tie evidence to, so nothing can be verified at all.
        if (evidence.expectedHead.isBlank()) {
            return listOf(
                Violation(
                    "expected-head-unusable",
                    "no expected head was supplied; without a commit to tie evidence to, nothing can be verified",
                ),
            )
        }
        return headViolations(evidence) +
            checkViolations(evidence) +
            identityViolations(evidence) +
            workflowViolations(evidence)
    }

    /**
     * Exactly one of these fires: no head is a different failure from the wrong head, and reporting both
     * would obscure which one the fetcher produced.
     */
    private fun headViolations(evidence: Evidence): List<Violation> {
        val reported = evidence.reportedHead?.trim()
        // Trim both sides: `gh` returns headRefOid with a trailing newline, and an untrimmed comparison
        // reported "head moved from X to X" — identical SHAs, spurious refusal.
        return when {
            reported.isNullOrBlank() -> {
                listOf(
                    Violation(
                        "head-unavailable",
                        "the API reported no head; merge evidence is unavailable, which is not a pass",
                    ),
                )
            }

            reported != evidence.expectedHead.trim() -> {
                listOf(
                    Violation(
                        "head-mismatch",
                        "head moved from ${evidence.expectedHead.trim()} to $reported; " +
                            "every check below belongs to a commit nobody reviewed",
                    ),
                )
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun checkViolations(evidence: Evidence): List<Violation> {
        // Unavailable evidence is the exact defect this verifier exists for: an empty set is a refusal, not
        // an absence of failures.
        if (evidence.checkStates.isEmpty()) {
            return listOf(
                Violation(
                    "checks-unavailable",
                    "no status checks were read; an empty result means the query failed, not that all checks passed",
                ),
            )
        }
        val violations = mutableListOf<Violation>()
        val notAccepted = evidence.checkStates.filterNot { it in ACCEPTED_CHECK_STATES }
        if (notAccepted.isNotEmpty()) {
            violations +=
                Violation(
                    "check-not-successful",
                    "${notAccepted.size} check(s) are not ${ACCEPTED_CHECK_STATES.joinToString("/")}: " +
                        notAccepted.distinct().joinToString(", "),
                )
        }
        val successes = evidence.checkStates.count { it == "SUCCESS" }
        if (successes < evidence.minSuccesses) {
            violations +=
                Violation(
                    "insufficient-successes",
                    "only $successes check(s) succeeded; fewer than ${evidence.minSuccesses} means the read " +
                        "was partial, which is not a pass",
                )
        }
        return violations
    }

    /**
     * Coverage, not cardinality. A count floor is satisfied by ten successes from a rollup whose remaining
     * entries were never read, so when required identities are configured their absence is a refusal and a
     * names/states mismatch is refused as an inconsistent read.
     */
    private fun identityViolations(evidence: Evidence): List<Violation> {
        val required = evidence.requiredCheckIdentities
        val conditional = evidence.conditionalChecks
        val violations = mutableListOf<Violation>()
        when {
            required.isEmpty() && conditional.isEmpty() -> {
                Unit
            }

            evidence.checkNames.isEmpty() -> {
                violations +=
                    Violation(
                        "check-names-unavailable",
                        "required check identities were configured but no check names were read; " +
                            "completeness cannot be established from states alone",
                    )
            }

            // Applicability is decided from the changed paths, so a gate that cannot read them cannot tell
            // an inapplicable workflow from a missing one. Refusing is the only honest option.
            conditional.isNotEmpty() && evidence.changedPaths.isEmpty() -> {
                violations +=
                    Violation(
                        "changed-paths-unavailable",
                        "${conditional.size} conditional check(s) are configured but the pull request's " +
                            "changed paths were not read; applicability cannot be decided, which is not a pass",
                    )
            }

            // A partial list is not a usable one: some evidence retrieved is not all evidence retrieved.
            // GitHub truncates long responses, so a list shorter than GitHub's own count must not be used to
            // conclude that a workflow is inapplicable.
            conditional.isNotEmpty() && evidence.changedFilesTotal != evidence.changedPaths.size -> {
                violations +=
                    Violation(
                        "changed-paths-incomplete",
                        "read ${evidence.changedPaths.size} of ${evidence.changedFilesTotal} changed path(s); " +
                            "applicability cannot be decided from incomplete evidence",
                    )
            }

            else -> {
                // Only the applicable conditional checks join the required set. A path-filtered workflow that
                // GitHub correctly did not run must not be demanded here.
                violations += coverageViolations(evidence)
            }
        }
        return violations
    }

    private fun workflowViolations(evidence: Evidence): List<Violation> {
        // An empty run set yields BOTH rules, not just the first: "nothing was read" and "this required
        // workflow has no run" are separate facts, and the live exercise output shows both together.
        val unavailable =
            if (evidence.workflowRuns.isEmpty()) {
                listOf(
                    Violation(
                        "workflow-runs-unavailable",
                        "no workflow runs were read for ${evidence.expectedHead}; a required workflow " +
                            "cannot be shown to have passed",
                    ),
                )
            } else {
                emptyList()
            }
        return unavailable + evidence.requiredWorkflows.flatMap { required -> runViolations(evidence, required) }
    }

    /** The refusals for one required workflow, so the caller needs no `continue` and no shared mutable state. */
    private fun runViolations(
        evidence: Evidence,
        required: String,
    ): List<Violation> {
        val expected = evidence.expectedHead.trim()
        // Filtered to the reviewed commit FIRST. Success and SHA-presence must not be satisfied by two
        // different runs: a wrong-SHA success plus an expected-SHA run still in progress would otherwise
        // pass, because one run answered each question.
        val atExpectedHead = evidence.workflowRuns.filter { it.name == required && it.headSha.trim() == expected }
        val ranElsewhere = evidence.workflowRuns.any { it.name == required }
        return if (atExpectedHead.isEmpty()) {
            listOf(
                Violation(
                    if (ranElsewhere) "required-workflow-wrong-commit" else "required-workflow-missing",
                    if (ranElsewhere) {
                        "'$required' did not run at $expected"
                    } else {
                        "'$required' has no workflow run at $expected"
                    },
                ),
            )
        } else if (atExpectedHead.any {
                it.status == REQUIRED_WORKFLOW_STATUS && it.conclusion == REQUIRED_WORKFLOW_CONCLUSION
            }
        ) {
            emptyList()
        } else {
            val run = atExpectedHead.first()
            listOf(
                Violation(
                    "required-workflow-not-successful",
                    "'$required' at $expected is ${run.status.lowercase()}/${run.conclusion?.lowercase()}; " +
                        "$REQUIRED_WORKFLOW_STATUS/$REQUIRED_WORKFLOW_CONCLUSION is required",
                ),
            )
        }
    }

    /** Renders a human-readable refusal, naming the rule that failed for each violation. */
    fun render(
        expectedHead: String,
        violations: List<Violation>,
    ): String =
        if (violations.isEmpty()) {
            "merge gate PASSED at $expectedHead: checks complete and green, required workflows successful"
        } else {
            buildString {
                append("merge gate REFUSED at ").append(expectedHead).append(": ")
                append(violations.size).append(" violation(s)\n")
                violations.forEach {
                    append("  - [")
                        .append(it.rule)
                        .append("] ")
                        .append(it.detail)
                        .append('\n')
                }
            }
        }

    // ── parsing the fetched evidence ────────────────────────────────────────
    //
    // Kept here, pure, so the boundary between "what the API returned" and "what the rules see" is testable
    // without a network: a fetch that produced nothing must parse to nothing, and nothing is then refused by
    // [verify]. Empty or unparseable input never yields a plausible-looking pass.

    /**
     * Parses one conclusion/state per line, as the fetcher prints them. Blank or malformed input yields an
     * empty list — which [verify] refuses — so a failed fetch cannot masquerade as a green set.
     */
    fun parseCheckStates(text: String): List<String> =
        text
            .lineSequence()
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toList()

    /**
     * Parses `name|state` per line, as the fetcher prints them.
     *
     * One line carries both facts so the names and states cannot be fetched from different reads and silently
     * disagree; [verify] still refuses a length mismatch as an inconsistent response.
     */
    fun parseChecks(text: String): List<Check> =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line -> parseCheck(line) }
            .toList()

    /**
     * Parses `name|status|conclusion|headSha` per line. Lines without all four fields are dropped, and an
     * empty result is refused by [verify] as unavailable evidence.
     */
    fun parseWorkflowRuns(text: String): List<WorkflowRun> =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line -> parseWorkflowRun(line) }
            .toList()
}

// At file level so the object stays within its function budget: a top-level private fun is callable
// unqualified from inside the object above, so moving these costs no call-site edits.

private fun parseCheck(line: String): MergeGateVerifier.Check? {
    val parts = line.split(FIELD_SEPARATOR)
    return if (parts.size != CHECK_FIELD_COUNT) {
        null
    } else {
        MergeGateVerifier.Check(name = parts[0].trim(), state = parts[1].trim().uppercase())
    }
}

private fun parseWorkflowRun(line: String): MergeGateVerifier.WorkflowRun? {
    val parts = line.split(FIELD_SEPARATOR)
    if (parts.size != WORKFLOW_FIELD_COUNT) {
        return null
    }
    return MergeGateVerifier.WorkflowRun(
        name = parts[0].trim(),
        // GitHub is inconsistent between endpoints: the Actions API returns lowercase
        // (completed/success) while the status rollup returns uppercase. Normalising here means one
        // casing reaches the rules, so neither shape is silently rejected.
        status = parts[1].trim().uppercase(),
        conclusion = parts[2].trim().takeIf { it.isNotEmpty() && it != "null" }?.uppercase(),
        headSha = parts[3].trim(),
    )
}
