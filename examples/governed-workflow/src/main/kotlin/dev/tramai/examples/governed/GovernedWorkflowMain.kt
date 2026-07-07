package dev.tramai.examples.governed

import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val classifier = DeterministicClaimClassifier()
    val workflow = buildClaimTriageWorkflow(classifier)

    println("=== Governed Workflow Example: Claim Triage ===\n")

    // Scenario 1 — Low-risk claim passes
    val lowRisk = ClaimInput(
        claimId = "CL-001",
        amount = 500.0,
        type = "general",
        description = "Damaged goods",
    )
    val result1 = workflow.run(initialState = ClaimTriageState(claim = lowRisk))
    println("✓ Low-risk claim: ${result1.status} — ${result1.reason}")

    // Scenario 2 — Restricted claim fails at policy gate
    val restricted = ClaimInput(
        claimId = "CL-002",
        amount = 0.0,
        type = "restricted",
        description = "Restricted material",
    )
    try {
        workflow.run(initialState = ClaimTriageState(claim = restricted))
        error("Restricted claim unexpectedly passed")
    } catch (e: dev.tramai.orchestration.WorkflowGateRejectedException) {
        println("✓ Restricted claim: rejected — Restricted claim requires manual handling")
    }

    // Scenario 3 — High-risk claim without approval fails
    val highRisk = ClaimInput(
        claimId = "CL-003",
        amount = 50_000.0,
        type = "liability",
        description = "Large liability claim",
    )
    try {
        workflow.run(initialState = ClaimTriageState(claim = highRisk, approved = false))
        error("High-risk unapproved claim unexpectedly passed")
    } catch (e: dev.tramai.orchestration.WorkflowGateRejectedException) {
        println("✓ High-risk unapproved claim: rejected — High-risk claim requires human approval")
    }

    // Scenario 4 — High-risk claim with approval succeeds
    val result4 = workflow.run(
        initialState = ClaimTriageState(claim = highRisk, approved = true),
    )
    println("✓ High-risk approved claim: ${result4.status} — ${result4.reason}")

    println("\n=== All scenarios demonstrated ===")
    println("This example uses a deterministic fake classifier — no model required.")
}
