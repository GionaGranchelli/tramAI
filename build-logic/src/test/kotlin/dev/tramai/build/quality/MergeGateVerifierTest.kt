package dev.tramai.build.quality

import dev.tramai.build.quality.MergeGateVerifier.WorkflowRun
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Positive and negative functional tests for [MergeGateVerifier], as `build-logic/AGENTS.md` requires of
 * every gate: proof that it catches a violation and proof that it passes clean evidence.
 *
 * The first test after the positive case is the regression test for the defect this verifier exists for —
 * a gate that read nothing and reported success. It must fail here if that behaviour ever returns.
 */
class MergeGateVerifierTest {
    private val head = "32d9a4abf4c74965aeaa1dbe86919bf1bf6edeb3"

    /** Stands in for a path-filtered workflow: what actually applies is decided by the changed paths. */
    private val mutation = mapOf("Mutation authority transport" to listOf("config/quality/**"))

    private fun greenEvidence(
        reportedHead: String? = head,
        checkStates: List<String> = List(12) { "SUCCESS" },
        requiredCheckIdentities: List<String> = emptyList(),
        workflowRuns: List<MergeGateVerifier.WorkflowRun> = requiredRuns(head),
        requiredWorkflows: List<String> = REQUIRED,
    ) = MergeGateVerifier.Evidence(
        expectedHead = head,
        reportedHead = reportedHead,
        checkStates = checkStates,
        requiredCheckIdentities = requiredCheckIdentities,
        workflowRuns = workflowRuns,
        requiredWorkflows = requiredWorkflows,
    )

    private fun requiredRuns(sha: String) = REQUIRED.map { WorkflowRun(it, "COMPLETED", "SUCCESS", sha) }

    /**
     * Evidence carrying check names as well as states, for the identity rules. A second helper rather than
     * more parameters on [greenEvidence]: the parameter threshold is 6 and it is inclusive, so six
     * parameters is already a finding.
     */
    private fun namedEvidence(
        checks: List<MergeGateVerifier.Check>,
        identities: List<String>,
    ) = MergeGateVerifier.Evidence(
        expectedHead = head,
        reportedHead = head,
        checkStates = checks.map { it.state },
        checkNames = checks.map { it.name },
        requiredCheckIdentities = identities,
        workflowRuns = requiredRuns(head),
        requiredWorkflows = REQUIRED,
    )

    /** The rules triggered by one non-SUCCESS state alongside a green set. */
    private fun stateRules(extraState: String): List<String> {
        val states = List(12) { "SUCCESS" } + listOf(extraState)
        return rules(MergeGateVerifier.verify(greenEvidence(checkStates = states)))
    }

    private fun rules(violations: List<MergeGateVerifier.Violation>) = violations.map { it.rule }

    // ── positive: clean evidence passes ──────────────────────────────────────

    @Test
    fun `complete green evidence passes`() {
        val violations = MergeGateVerifier.verify(greenEvidence())

        assertEquals(emptyList(), violations, MergeGateVerifier.render(head, violations))
        assertTrue(MergeGateVerifier.render(head, violations).contains("PASSED"))
    }

    @Test
    fun `neutral and skipped checks are accepted alongside successes`() {
        val violations =
            MergeGateVerifier.verify(greenEvidence(checkStates = List(10) { "SUCCESS" } + listOf("NEUTRAL", "SKIPPED")))

        assertEquals(emptyList(), violations, "NEUTRAL and SKIPPED are not failures")
    }

    // ── the regression: reading nothing must not pass ────────────────────────

    @Test
    fun `an empty check set is refused even though no check is failing`() {
        // This is the defect that motivated the verifier: an invalid query returned nothing, and a gate
        // that only looked for failures reported success.
        val violations = MergeGateVerifier.verify(greenEvidence(checkStates = emptyList()))

        assertTrue(
            "checks-unavailable" in rules(violations),
            "reading no checks must refuse; it must never read as 'nothing failed'",
        )
    }

    @Test
    fun `no workflow runs at all is refused`() {
        val violations = MergeGateVerifier.verify(greenEvidence(workflowRuns = emptyList()))

        assertTrue("workflow-runs-unavailable" in rules(violations))
        assertTrue("required-workflow-missing" in rules(violations))
    }

    // ── unavailable or wronghead evidence ────────────────────────────────────

    @Test
    fun `an unavailable head is refused`() {
        val violations = MergeGateVerifier.verify(greenEvidence(reportedHead = null))

        assertTrue("head-unavailable" in rules(violations))
    }

    @Test
    fun `a blank reported head is refused`() {
        assertTrue("head-unavailable" in rules(MergeGateVerifier.verify(greenEvidence(reportedHead = "   "))))
    }

    @Test
    fun `a moved head is refused because the evidence belongs to another commit`() {
        val violations = MergeGateVerifier.verify(greenEvidence(reportedHead = "deadbeef"))

        assertTrue("head-mismatch" in rules(violations))
        assertTrue(MergeGateVerifier.render(head, violations).contains("head-mismatch"))
    }

    @Test
    fun `a blank expected head cannot pass`() {
        val evidence =
            MergeGateVerifier.Evidence(
                expectedHead = "",
                reportedHead = head,
                checkStates = List(12) { "SUCCESS" },
                workflowRuns = requiredRuns(head),
                requiredWorkflows = REQUIRED,
            )
        val violations = MergeGateVerifier.verify(evidence)

        assertTrue("expected-head-unusable" in rules(violations))
    }

    // ── pending and unsuccessful checks ──────────────────────────────────────

    @Test
    fun `a pending check is refused`() {
        assertTrue("check-not-successful" in stateRules("IN_PROGRESS"))
    }

    @Test
    fun `a failed check is refused`() {
        assertTrue("check-not-successful" in stateRules("FAILURE"))
    }

    @Test
    fun `a cancelled check is refused`() {
        assertTrue("check-not-successful" in stateRules("CANCELLED"))
    }

    @Test
    fun `too few successes is refused so a partial read cannot pass`() {
        val violations = MergeGateVerifier.verify(greenEvidence(checkStates = List(3) { "SUCCESS" }))

        assertTrue("insufficient-successes" in rules(violations))
    }

    // ── required workflows ───────────────────────────────────────────────────

    @Test
    fun `a required workflow missing at the head is refused`() {
        val runs = requiredRuns(head).filterNot { it.name == "Architecture Authority" }

        val violations = MergeGateVerifier.verify(greenEvidence(workflowRuns = runs))

        assertTrue("required-workflow-missing" in rules(violations))
    }

    @Test
    fun `a required workflow that ran at another commit is refused`() {
        val runs =
            requiredRuns(head).map {
                if (it.name == "Sovereign Runtime Release Candidate") it.copy(headSha = "deadbeef") else it
            }

        val violations = MergeGateVerifier.verify(greenEvidence(workflowRuns = runs))

        assertTrue(
            "required-workflow-wrong-commit" in rules(violations),
            "a workflow run from another commit must not stand in for this head",
        )
    }

    @Test
    fun `a required workflow that is not successful is refused`() {
        for (state in listOf(
            Triple("IN_PROGRESS", null, "required-workflow-not-successful"),
            Triple("COMPLETED", "FAILURE", "required-workflow-not-successful"),
            Triple("COMPLETED", null, "required-workflow-not-successful"),
        )) {
            val runs =
                requiredRuns(head).map {
                    if (it.name == "CI") it.copy(status = state.first, conclusion = state.second) else it
                }

            val violations = MergeGateVerifier.verify(greenEvidence(workflowRuns = runs))

            assertTrue(
                state.third in rules(violations),
                "CI as ${state.first}/${state.second} must be refused, got ${rules(violations)}",
            )
        }
    }

    @Test
    fun `a refusal names every failing rule and the head it refused`() {
        val violations =
            MergeGateVerifier.verify(
                greenEvidence(reportedHead = "deadbeef", checkStates = emptyList(), workflowRuns = emptyList()),
            )

        val rendered = MergeGateVerifier.render(head, violations)
        assertTrue(rendered.contains("REFUSED"))
        assertTrue(rendered.contains(head), "the refusal states which head it judged")
        for (rule in rules(violations)) {
            assertTrue(rendered.contains(rule), "rule $rule is named in the refusal")
        }
    }

    // ── the fetch boundary: empty input must never look green ────────────────

    @Test
    fun `empty fetched text parses to no checks, which the verifier refuses`() {
        // The regression, at the transport boundary as well as the rule: a failed fetch produced nothing
        // before, and the gate still passed. Nothing must stay nothing, and nothing must be refused.
        val states = MergeGateVerifier.parseCheckStates("")

        assertEquals(emptyList(), states)
        assertTrue(
            "checks-unavailable" in
                rules(MergeGateVerifier.verify(greenEvidence(checkStates = states))),
            "an empty fetch must be refused, not read as 'no checks failed'",
        )
    }

    @Test
    fun `check states parse one per line, ignoring blank lines`() {
        val states = MergeGateVerifier.parseCheckStates("SUCCESS\n\n  SUCCESS  \nNEUTRAL\n")

        assertEquals(listOf("SUCCESS", "SUCCESS", "NEUTRAL"), states)
    }

    @Test
    fun `workflow runs parse name, status, conclusion and commit`() {
        val runs =
            MergeGateVerifier.parseWorkflowRuns(
                "CI|COMPLETED|SUCCESS|$head\n" +
                    "Architecture Authority|COMPLETED|SUCCESS|$head\n" +
                    "Sovereign Runtime Release Candidate|COMPLETED|SUCCESS|$head\n",
            )

        assertEquals(3, runs.size)
        assertEquals("Architecture Authority", runs[1].name)
        assertEquals(head, runs[1].headSha)
    }

    @Test
    fun `a malformed workflow line is dropped rather than invented`() {
        val runs = MergeGateVerifier.parseWorkflowRuns("garbage\nCI|COMPLETED|SUCCESS|$head\nnot|enough|fields\n")

        assertEquals(1, runs.size)
        assertEquals("CI", runs.single().name)
    }

    @Test
    fun `an empty workflow response parses to nothing, which the verifier refuses`() {
        val runs = MergeGateVerifier.parseWorkflowRuns("")

        assertEquals(emptyList(), runs)
        assertTrue("workflow-runs-unavailable" in rules(MergeGateVerifier.verify(greenEvidence(workflowRuns = runs))))
    }

    // ── transport realities: whitespace and the API's mixed casing ───────────
    //
    // All three of these were found by running the real task against real data, not by these tests. They
    // are recorded here so the same class of transport defect cannot come back silently.

    @Test
    fun `a head fetch with a trailing newline is not a mismatch`() {
        // `gh` returns headRefOid with a trailing newline. Untrimmed, the rule reported
        // "head moved from X to X" — identical SHAs, spurious refusal.
        val violations = MergeGateVerifier.verify(greenEvidence(reportedHead = "$head\n"))

        assertTrue(
            "head-mismatch" !in rules(violations),
            "identical SHAs must not read as a moved head: ${rules(violations)}",
        )
    }

    @Test
    fun `the Actions API's lowercase status and conclusion are accepted`() {
        // The Actions API returns completed/success; the status rollup returns COMPLETED/SUCCESS. A
        // case-sensitive comparison rejected a genuinely successful workflow run. Built from the full
        // required set so the run is complete and only the casing is under test.
        val runs = MergeGateVerifier.parseWorkflowRuns(REQUIRED.joinToString("\n") { "$it|completed|success|$head" })

        assertEquals(3, runs.size)
        assertTrue(runs.all { it.status == "COMPLETED" && it.conclusion == "SUCCESS" })
        assertEquals(emptyList(), MergeGateVerifier.verify(greenEvidence(workflowRuns = runs)).map { it.rule })
    }

    @Test
    fun `lowercase check states are accepted`() {
        // Normalisation happens at the transport boundary, so this goes through the parser rather than
        // around it — a fixture that skips the parser would test a layer the fetch never uses.
        val states = MergeGateVerifier.parseCheckStates(List(12) { "success" }.joinToString("\n"))

        assertEquals(List(12) { "SUCCESS" }, states)
        assertTrue("check-not-successful" !in rules(MergeGateVerifier.verify(greenEvidence(checkStates = states))))
    }

    // ── required check identities: cardinality is not completeness (review finding) ──

    @Test
    fun `a required check identity that is absent is refused even when the floor is met`() {
        // The reported defect: ten successes satisfy the floor, yet the rollup may be missing checks
        // whose omitted entries were failures. Presence of the required identities is what establishes
        // coverage; the floor remains a sanity check only.
        val checks = List(10) { MergeGateVerifier.Check("quality (static, $it)", "SUCCESS") }
        val violations =
            MergeGateVerifier.verify(namedEvidence(checks, listOf("quality (static", "contract-tests (formatting")))

        assertTrue(
            "required-check-missing" in rules(violations),
            "an absent required identity must refuse: ${rules(violations)}",
        )
    }

    @Test
    fun `a missing sibling within a matching family is refused`() {
        // The earlier rule required one name per prefix, so a partial read containing a single member of
        // each family passed. Every required identity must be present.
        val checks =
            List(6) { MergeGateVerifier.Check("quality (static, $it)", "SUCCESS") } +
                List(6) { MergeGateVerifier.Check("contract-tests (formatting, $it)", "SUCCESS") }
        val violations =
            MergeGateVerifier.verify(
                namedEvidence(checks, listOf("quality (static", "contract-tests (formatting", "tests")),
            )

        assertTrue("required-check-missing" in rules(violations), "rules: ${rules(violations)}")
    }

    @Test
    fun `a required check that is SKIPPED is refused even though SKIPPED is not a failure`() {
        // SKIPPED and NEUTRAL stay acceptable for checks that are not required; a required one must have
        // succeeded, so presence alone cannot authorise a merge.
        val checks = List(6) { MergeGateVerifier.Check("quality (static, $it)", "SUCCESS") }
        val withSkipped = checks + MergeGateVerifier.Check("contract-tests (formatting, 0)", "SKIPPED")
        val identities = listOf("quality (static", "contract-tests (formatting")
        val violations = MergeGateVerifier.verify(namedEvidence(withSkipped, identities))

        assertTrue("required-check-not-successful" in rules(violations), "rules: ${rules(violations)}")
        assertTrue("check-not-successful" !in rules(violations), "SKIPPED is not a failing check")
    }

    @Test
    fun `a wrong-head success plus an expected-head in-progress run is refused`() {
        // Success and SHA-presence must not be satisfied by two different runs: the earlier rule accepted
        // this pair because one run answered each question.
        val runs =
            listOf(
                WorkflowRun("CI", "COMPLETED", "SUCCESS", "deadbeef"),
                WorkflowRun("CI", "IN_PROGRESS", null, head),
            )
        val violations = MergeGateVerifier.verify(greenEvidence(workflowRuns = runs))

        assertTrue(
            "required-workflow-not-successful" in rules(violations),
            "the expected-head run is not successful: ${rules(violations)}",
        )
    }

    @Test
    fun `all required identities present and green passes`() {
        val checks =
            List(6) { MergeGateVerifier.Check("quality (static, $it)", "SUCCESS") } +
                List(6) { MergeGateVerifier.Check("contract-tests (formatting, $it)", "SUCCESS") }
        val violations =
            MergeGateVerifier.verify(namedEvidence(checks, listOf("quality (static", "contract-tests (formatting")))

        assertEquals(emptyList(), violations, MergeGateVerifier.render(head, violations))
    }

    @Test
    fun `an unrelated pull request without a conditional check passes`() {
        // A path-filtered workflow GitHub legitimately did not run must not be demanded. Making it
        // unconditionally required would refuse unrelated work, the failure this gate exists to prevent.
        val violations =
            MergeGateVerifier.verify(
                greenEvidence()
                    .copy(
                        checkNames = List(12) { "some-check-$it" },
                        conditionalChecks = mutation,
                        changedPaths = listOf("tramai-engine/src/main/kotlin/dev/tramai/engine/Router.kt"),
                    ),
            )

        assertEquals(emptyList(), violations, MergeGateVerifier.render(head, violations))
    }

    @Test
    fun `a change matching a conditional filter requires that check`() {
        val violations =
            MergeGateVerifier.verify(
                greenEvidence()
                    .copy(
                        checkNames = List(12) { "some-check-$it" },
                        conditionalChecks = mutation,
                        changedPaths = listOf("config/quality/0.6.0-baseline.json"),
                    ),
            )

        assertTrue("required-check-missing" in rules(violations), "rules: ${rules(violations)}")
    }

    @Test
    fun `conditional checks configured but changed paths unread is refused`() {
        // Treating an unread path set as "nothing matched" would silently drop the check; the honest
        // verdict for undecidable applicability is a refusal.
        val violations =
            MergeGateVerifier.verify(
                greenEvidence()
                    .copy(
                        checkNames = List(12) { "some-check-$it" },
                        conditionalChecks = mutation,
                        changedPaths = emptyList(),
                    ),
            )

        assertTrue("changed-paths-unavailable" in rules(violations), "rules: ${rules(violations)}")
    }

    @Test
    fun `required identities configured but no names read is refused`() {
        val violations = MergeGateVerifier.verify(greenEvidence(requiredCheckIdentities = listOf("quality (static")))

        assertTrue("check-names-unavailable" in rules(violations))
    }

    @Test
    fun `a names and states count mismatch is refused as inconsistent`() {
        // An inconsistent read cannot be expressed through the helper, which derives both facts from one
        // list: that is the shape working as intended, so this builds the evidence directly.
        val evidence =
            MergeGateVerifier.Evidence(
                expectedHead = head,
                reportedHead = head,
                checkStates = List(12) { "SUCCESS" },
                checkNames = List(11) { "quality (static, $it)" },
                requiredCheckIdentities = listOf("quality (static"),
                workflowRuns = requiredRuns(head),
                requiredWorkflows = REQUIRED,
            )
        val violations = MergeGateVerifier.verify(evidence)

        assertTrue("checks-inconsistent" in rules(violations))
    }

    @Test
    fun `check lines parse name and state together`() {
        val checks =
            MergeGateVerifier.parseChecks(
                "quality (static, spotlessCheck)|SUCCESS\ncontract-tests (formatting, 8)|failure\n",
            )

        assertEquals(2, checks.size)
        assertEquals("quality (static, spotlessCheck)", checks[0].name)
        assertEquals("SUCCESS", checks[0].state)
        assertEquals("FAILURE", checks[1].state)
    }

    private companion object {
        val REQUIRED = listOf("CI", "Architecture Authority", "Sovereign Runtime Release Candidate")
    }
}
