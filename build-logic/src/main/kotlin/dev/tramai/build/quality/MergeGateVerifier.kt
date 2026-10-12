package dev.tramai.build.quality

/**
 * Decides whether a pull request may be merged, from evidence that has already been fetched.
 *
 * ## Why this exists
 *
 * A merge was performed on 2026-10-12 through a gate whose query was invalid
 * (`gh api … --json statusCheckRollup`; that flag belongs to `gh pr view`). The query returned an empty
 * string, the gate's tally was empty, its numeric comparison failed, and it reported success and merged.
 * The merge happened to be correct — the head was independently verified elsewhere — but nothing in the
 * gate checked it, and a red pull request would have passed identically.
 *
 * Recalling that is not a safeguard. This is: the same rules, executable and regression-tested, so a
 * gate that reads nothing cannot report success again.
 *
 * ## Shape
 *
 * Pure by construction — it takes already-parsed values and performs no I/O — so every rule is directly
 * testable and the fetching is a separate, replaceable step. Like the other verifiers in this package it
 * does not throw on bad input; it reports violations.
 *
 * ## What it does not do
 *
 * It cannot verify that the *fetcher* supplied everything the API holds: it can see an empty or partial
 * set, not a set that is silently missing entries. [Evidence.minSuccesses] is the guard against that,
 * and the fetcher is responsible for failing closed when `gh` itself errors.
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

    /**
     * Everything the verifier needs, already fetched and parsed.
     *
     * @param expectedHead the exact commit the reviewer approved; the whole point is that a moving head
     *   invalidates a merge decision, so every piece of evidence is checked against it.
     * @param reportedHead the head the API reports now, or null if unavailable.
     * @param checkStates conclusion/state of every status check on the pull request, in any order.
     * @param workflowRuns runs recorded for [expectedHead].
     * @param requiredWorkflows workflows that must have a successful run at [expectedHead].
     * @param minSuccesses a gate that read nothing, or a suspiciously small set, must not pass.
     */
    data class Evidence(
        val expectedHead: String,
        val reportedHead: String?,
        val checkStates: List<String>,
        val workflowRuns: List<WorkflowRun>,
        val requiredWorkflows: List<String>,
        val minSuccesses: Int = 10,
    )

    data class Violation(
        val rule: String,
        val detail: String,
    )

    /**
     * Returns every reason the evidence does not permit a merge. An empty list means the evidence is
     * complete, green, and attached to the expected commit.
     */
    fun verify(evidence: Evidence): List<Violation> {
        val violations = mutableListOf<Violation>()

        if (evidence.expectedHead.isBlank()) {
            violations +=
                Violation(
                    "expected-head-unusable",
                    "the expected head is blank, so no evidence can be tied to it",
                )
        }

        when {
            evidence.reportedHead?.trim().isNullOrBlank() -> {
                violations +=
                    Violation(
                        "head-unavailable",
                        "the API reported no head; merge evidence is unavailable, which is not a pass",
                    )
            }

            // Trim both sides: `gh` returns `headRefOid` with a trailing newline, and an untrimmed
            // comparison reported "head moved from X to X" — identical SHAs, spurious refusal. The rule
            // compares identities, so it must not be defeated by transport whitespace.
            evidence.reportedHead?.trim() != evidence.expectedHead.trim() -> {
                violations +=
                    Violation(
                        "head-mismatch",
                        "head moved from ${evidence.expectedHead} to ${evidence.reportedHead}; " +
                            "every check below belongs to a commit nobody reviewed",
                    )
            }
        }

        // Unavailable evidence is the exact defect this verifier exists for: an empty set is a refusal.
        if (evidence.checkStates.isEmpty()) {
            violations +=
                Violation(
                    "checks-unavailable",
                    "no status checks were read; an empty result means the query failed, not that all checks passed",
                )
        } else {
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
                        "only $successes successful check(s); at least ${evidence.minSuccesses} are required, " +
                            "so a partial read cannot pass",
                    )
            }
        }

        if (evidence.workflowRuns.isEmpty()) {
            violations +=
                Violation(
                    "workflow-runs-unavailable",
                    "no workflow runs were read for ${evidence.expectedHead}; " +
                        "a required workflow cannot be shown to have passed",
                )
        }

        for (required in evidence.requiredWorkflows) {
            val matching = evidence.workflowRuns.filter { it.name == required }
            if (matching.isEmpty()) {
                violations +=
                    Violation(
                        "required-workflow-missing",
                        "'$required' has no workflow run at ${evidence.expectedHead}",
                    )
                continue
            }
            val wrongCommit = matching.filter { it.headSha != evidence.expectedHead }
            if (wrongCommit.isNotEmpty() && matching.none { it.headSha == evidence.expectedHead }) {
                violations +=
                    Violation(
                        "required-workflow-wrong-commit",
                        "'$required' ran at ${wrongCommit.joinToString(", ") { it.headSha }}, " +
                            "not at ${evidence.expectedHead}",
                    )
                continue
            }
            val atHead = matching.first { it.headSha == evidence.expectedHead }
            if (atHead.status != REQUIRED_WORKFLOW_STATUS || atHead.conclusion != REQUIRED_WORKFLOW_CONCLUSION) {
                violations +=
                    Violation(
                        "required-workflow-not-successful",
                        "'$required' is ${atHead.status}/${atHead.conclusion ?: "no conclusion"}; " +
                            "$REQUIRED_WORKFLOW_STATUS/$REQUIRED_WORKFLOW_CONCLUSION is required",
                    )
            }
        }

        return violations
    }

    // ── parsing the fetched evidence ────────────────────────────────────────
    //
    // Kept here, pure, so the boundary between "what the API returned" and "what the rules see" is
    // testable without a network: a fetch that produced nothing must parse to nothing, and nothing is
    // then refused by [verify]. Empty or unparseable input never yields a plausible-looking pass.

    /**
     * Parses one conclusion/state per line, as the fetcher prints them. Blank or malformed input yields
     * an empty list — which [verify] refuses — so a failed fetch cannot masquerade as a green set.
     */
    fun parseCheckStates(text: String): List<String> =
        text
            .lineSequence()
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
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
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 4) {
                    null
                } else {
                    WorkflowRun(
                        name = parts[0].trim(),
                        // GitHub is inconsistent between endpoints: the Actions API returns lowercase
                        // (completed/success) while the status rollup returns uppercase. Normalising here
                        // means one casing reaches the rules, so neither shape is silently rejected.
                        status = parts[1].trim().uppercase(),
                        conclusion = parts[2].trim().takeIf { it.isNotEmpty() && it != "null" }?.uppercase(),
                        headSha = parts[3].trim(),
                    )
                }
            }.toList()

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
}
