package dev.tramai.examples.governed

import dev.tramai.orchestration.GateDecision
import dev.tramai.orchestration.Workflow
import dev.tramai.orchestration.workflow

fun buildClaimTriageWorkflow(
    classifier: ClaimClassifier,
): Workflow<ClaimTriageState, ClaimTriageResult> =
    workflow<ClaimTriageState>(
        name = "claim-triage",
        definitionVersion = "1",
    ) {
        aiStep(
            name = "classify",
            input = { state -> state.claim },
            invoke = classifier::classify,
            merge = { state, classification ->
                state.copy(classification = classification)
            },
        )

        gateStep(name = "policy-check") { state, _ ->
            if (state.classification?.risk == ClaimRisk.RESTRICTED) {
                GateDecision.reject("Restricted claim requires manual handling")
            } else {
                GateDecision.allow()
            }
        }

        gateStep(name = "approval-required") { state, _ ->
            if (state.classification?.risk == ClaimRisk.HIGH && !state.approved) {
                GateDecision.reject("High-risk claim requires human approval")
            } else {
                GateDecision.allow()
            }
        }

        localStep(name = "finalize") { state, _ ->
            state.copy(
                result = ClaimTriageResult(
                    status = "ready-for-review",
                    reason = "Policy and approval gates passed",
                ),
            )
        }
    }.build { state ->
        state.result ?: error("missing result")
    }
