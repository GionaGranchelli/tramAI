# Troubleshooting Governed Workflows

> **Status:** Guide — explains common governed workflow failures and fixes.
> **Phase:** Phase 3 — Workflow Ergonomics of the [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md).
> **Prerequisites:** Familiarity with the [governed workflow quickstart](governed-workflow-quickstart.md), the [minimal governed workflow example](../../examples/governed-workflow), and the [governed workflow testing guide](governed-workflow-testing.md).

---

## What This Guide Covers

A governed workflow can fail at multiple points. This guide explains how to recognise, diagnose, and fix the most common failures using a **symptom → likely cause → inspect → fix** model.

It does **not** replace the testing guide. The [testing guide](governed-workflow-testing.md) teaches how to write tests that *prevent* failures. This guide teaches how to debug failures that *already occurred*.

---

## Fast Triage Table

Start here. Match your symptom to the most likely cause, inspect the right evidence, and apply the fix.

| Symptom | Likely Cause | Inspect | Fix |
|---------|-------------|---------|-----|
| `WorkflowGateRejectedException` at `policy-check` | State before the gate has a restricted or unsafe classification | Classification produced by the preceding step; `observer.failedSteps` includes the gate name | Adjust test input, change the deterministic fake classifier, update the gate predicate, or assert the rejection if intentional |
| `WorkflowGateRejectedException` at `approval-required` | High-risk state lacks explicit approval (`approved == false`) | `state.classification.risk` and `state.approved` before the gate | Provide an approved state for the test input or route through an approval gateway |
| AI step throws provider exception | Model provider is unavailable, misconfigured, or the routing key does not match | Provider configuration, routing key in the `@Operation` annotation, network connectivity | Replace the real provider with a deterministic fake in workflow tests; verify provider config in production |
| `StructuredOutputException` on parse | Provider returned invalid JSON or a response that does not match the generated schema | Service-level test with `MockAiProvider`; `RecordingOperationObserver` capture of raw response | Test the `@AiService` interface directly with `MockAiProvider`; verify schema evolution is backward-compatible |
| `StructuredOutputException` on validation | Response parsed correctly but failed a validator constraint (`@AiRange`, `@AiMinItems`, etc.) | Validator annotation values on the return type; the raw AI response | Adjust validator bounds or the expected test input |
| Missing final result | The final `localStep` or result mapper did not populate the expected state field | Result mapper lambda in `.build { ... }`; the state after the last completed step | Ensure every path through the workflow writes the required state field |
| Observer trail is unexpected | Step order, gate behaviour, or failure location differs from what the test asserts | `observer.startedSteps`, `observer.completedSteps`, `observer.failedSteps` | Read the observer trail as the actual execution contract; update the assertion or fix the workflow definition |
| Test requires real credentials or network | A deterministic fake was not wired; the real `@AiService` implementation is used in the test | Workflow constructor or test fixture — does it accept a `ClaimClassifier` parameter? | Replace the real service with `DeterministicClaimClassifier` (or equivalent); see the [testing guide](governed-workflow-testing.md) |
| Workflow resumes from unexpected state | Persistence restored a checkpoint that does not match the current workflow version | `definitionVersion` in `workflow<S>` declaration; checkpoint store content | Increment `definitionVersion` when workflow structure changes; clear stale checkpoints |
| Workflow does not compile | Type mismatch in an `aiStep`, `merge` function, or `.build` result mapper | Compiler error pointing to a specific step or lambda | Verify that `aiStep(input = ...)` returns the type the `invoke` function expects; ensure `.build { ... }` returns the declared workflow output type |

---

## 1 — Workflow Does Not Compile

### Symptom

The workflow definition does not compile. The Kotlin compiler error points to a step definition, a lambda, or the `.build { ... }` result mapper.

### Likely cause

A type mismatch in one of the step wiring lambdas.

### Inspect

Read the compiler error carefully. Common locations:

- `aiStep(input = { state -> ... })` — the lambda must return the type that `invoke` accepts.
- `aiStep(merge = { state, result -> ... })` — the `result` parameter has the return type of `invoke`; the lambda must return the workflow state type `S`.
- `gateStep { state, context -> ... }` — must return `GateDecision`.
- `localStep { state, context -> ... }` — must return `S`.
- `.build { state -> ... }` — must return the workflow's declared output type.

### Fix

Align the lambda types with each step's contract. The orchestration DSL is type-safe: if it compiles, the step boundaries are correct.

---

## 2 — Workflow Fails at a Policy Gate

### Symptom

The workflow throws `WorkflowGateRejectedException`. The exception message contains the gate name (e.g. `policy-check`) and a rejection reason.

### Likely cause

The state that reached the gate triggered the rejection predicate. In the claim triage workflow, `policy-check` rejects claims classified as `restricted`.

### Inspect

1. Check the exception message for the gate name and rejection reason.
2. Check `observer.failedSteps` — it should include the rejecting gate name.
3. Check `observer.completedSteps` — it should include the steps that ran *before* the gate, proving where execution stopped.
4. Inspect the state produced by the preceding step — in the claim triage example, look at `classification.risk`.

### Fix

Depending on intent:

| Intent | Fix |
|--------|-----|
| The rejection was accidental (test input mismatch) | Change the test input so the preceding step produces a non-restricted classification |
| The deterministic fake classifier produced the wrong result | Adjust the fake's rule logic for the given input |
| The business rule changed | Update the `gateStep` predicate |
| The rejection is the expected behaviour | Assert the exception in the test — see [Pattern 3 in the testing guide](governed-workflow-testing.md#pattern-3--assert-gate-rejection-diagnostics) |

---

## 3 — Workflow Fails at an Approval Gate

### Symptom

The workflow throws `WorkflowGateRejectedException`. The exception message contains the gate name `approval-required` and a reason such as `"High-risk claim requires human approval"`.

### Likely cause

The state is high-risk (`classification.risk == "high"` or equivalent) and `approved` is `false`. The approval gate enforces that high-risk outcomes require explicit approval before proceeding.

### Inspect

1. Confirm the exception names `approval-required`.
2. Check the classification produced by the preceding AI step — is the risk level high?
3. Check `state.approved` — is it `false`?

### Fix

| Intent | Fix |
|--------|-----|
| You want the workflow to proceed without approval | Set `approved = true` in the test's initial state, or change the gate predicate |
| You want to test the approval-required path | Keep the state as-is and assert the `WorkflowGateRejectedException` |
| You want to add actual approval suspension | Use the `ApprovalGateway` API instead of a simple `gateStep` — see the [Approval Gateway Golden Path guide](approval-gateway-golden-path.md) |

**Important:** The approval gate in the governed workflow example is a simplified placeholder. It demonstrates the approval *boundary* but does not implement full persisted approval suspension/resume. Real approval suspension requires the `ApprovalGateway` API.

---

## 4 — AI Step Fails Because Provider Is Unavailable

### Symptom

The workflow throws a provider-level exception during an `aiStep`. The error suggests a connection failure, timeout, or routing mismatch.

### Likely cause

The provider configuration is missing, the routing key in the `@Operation` annotation does not match a registered provider, or the provider endpoint is unreachable.

### Inspect

1. Check the `@Operation(model = "..." )` value on the `@AiService` method — does it match a registered provider?
2. Check provider registration — is the provider configured in your application context or provider registry?
3. Verify network connectivity if using a remote provider.

### Fix

For **workflow-level tests**: replace the real provider with a deterministic fake. The workflow test should never depend on a real provider being available. See the [testing guide](governed-workflow-testing.md#pattern-1--test-with-a-deterministic-fake-service).

For **service-level tests**: use `MockAiProvider` from `tramai-testing` to simulate provider behaviour, including failure modes. See the general [Testing Guide](testing.md).

For **production**: verify the provider configuration matches the routing key and the endpoint is healthy.

---

## 5 — AI Step Returns Invalid or Unparseable Structured Output

### Symptom

The workflow throws `StructuredOutputException` during an `aiStep`. The error indicates that the provider's response could not be parsed or validated against the generated schema.

### Likely cause

- The provider returned invalid JSON or a response that does not match the expected schema shape.
- A validator annotation (`@AiRange`, `@AiMinItems`, etc.) rejected a value in the parsed response.
- The return type changed (e.g., a new required field was added) but the provider still sends the old shape.

### Inspect

This is a **provider-level concern**, not a workflow-concern. Do not debug structured output parsing through the whole workflow.

1. Test the `@AiService` interface directly with `MockAiProvider` — see [Pattern 5 in the testing guide](governed-workflow-testing.md#pattern-5--test-ai-service-wiring-separately).
2. Use `RecordingOperationObserver` to capture the raw provider response and the generated schema.
3. Use `TramaiAssertions` for fluent structured-output assertions.

### Fix

| Cause | Fix |
|-------|-----|
| Provider sends wrong shape | Adjust the prompt or response format instructions in the `@AiService` / `@Operation` annotations |
| Schema changed and provider needs updating | Ensure backward-compatible schema evolution (add optional fields, not breaking changes) |
| Validator rejects valid responses | Adjust `@AiRange` or `@AiMinItems` bounds, or add expected test input that produces in-range values |

For the full structured output lifecycle, see the [Structured Output Contract Lifecycle](../structured-output-contract-lifecycle.md).

---

## 6 — Workflow Produces Missing or Unexpected State

### Symptom

The workflow completes without throwing an exception, but the result is missing a required field, or the output is not what the test expects.

### Likely cause

The final `localStep` or the `.build { ... }` result mapper did not populate the expected field. This is common when a gate rejection is not triggered (so the workflow continues) but a later step does not write a required value.

### Inspect

1. Run the workflow with a small test `WorkflowObserver` implementation (like the recording observer shown in the [governed workflow testing guide](governed-workflow-testing.md#pattern-4--observe-diagnostic-step-trails)) to see which steps completed.
2. Check the state after each step — are all required fields populated?
3. Check the result mapper in `.build { state -> ... }` — does every code path return the expected type?

### Fix

Ensure that every step that runs writes the state fields the result mapper expects. If a step is skipped by a branch, the result mapper must handle the missing field (e.g. `state.result ?: error("missing result")`).

---

## 7 — Observer Trail Does Not Match Expectations

### Symptom

A test asserts a specific step order or gate failure location, but the observer trail shows different started, completed, or failed steps.

### Likely cause

The test's expectation does not match the actual execution contract. The observer trail is **always** the ground truth for step execution order.

### Inspect

Read the observer trail as-is:

- `observer.startedSteps` — every step that began execution, in order.
- `observer.completedSteps` — steps that finished successfully, in order.
- `observer.failedSteps` — steps that failed (including gate rejections), in order.

A gate rejection appears as a failed step, not a completed step.

### Fix

| Mismatch | Fix |
|----------|-----|
| Trail has more steps than expected | A branch or parallel step executed additional steps — update the assertion |
| Trail has fewer steps than expected | A gate rejected earlier than expected, or a conditional branch was not taken — check the state |
| A gate appears in `completedSteps` but you expected a rejection | The gate predicate evaluated to `allow()` — check the state that reached the gate |
| A gate appears in `failedSteps` but you expected completion | The gate predicate evaluated to `reject(...)` — this is correct behaviour, update the assertion |

---

## 8 — Persistence or Resume Does Not Behave as Expected

### Symptom

A workflow with `WorkflowPersistence` resumes from a state that does not match expectations, or checkpoint/restore behaviour is surprising.

### Likely cause

- The `definitionVersion` changed but old checkpoints were not cleared, so the restored state does not match the current workflow structure.
- The workflow was not configured with a persistence store, so no checkpoints were recorded.
- The expected step boundaries were not checkpointed (only top-level steps produce checkpoints).

### Inspect

1. Confirm that `WorkflowPersistence` was supplied to `workflow.run(..., persistence = ...)`.
2. Check the `definitionVersion` in the workflow declaration — old checkpoints with a different version may be incompatible.
3. Review the [Orchestration Persistence Guide](orchestration-persistence.md) for the checkpointing model.

### Fix

| Situation | Fix |
|-----------|-----|
| Wrong version restored | Increment `definitionVersion` when workflow structure changes; clear old checkpoint data |
| No persistence configured | Supply a `WorkflowPersistence` implementation (file-backed, JDBC, or in-memory for tests) |
| Resume is not happening at the expected step | Only top-level step boundaries are checkpointed — nested or sub-step boundaries are not |

---

## 9 — Test Is Flaky or Requires Real Model Credentials

### Symptom

A workflow test sometimes passes and sometimes fails, or it requires an API key to run.

### Likely cause

The test is using the real `@AiService` implementation instead of a deterministic fake. Real provider calls introduce network latency, rate limits, credential requirements, and non-deterministic output.

### Inspect

Check the test fixture or workflow constructor — is it receiving a `ClaimClassifier` implementation that makes real provider calls?

### Fix

Replace the real service with a deterministic fake:

```kotlin
// Before (flaky, requires credentials):
val workflow = buildClaimTriageWorkflow(RealClaimClassifier(provider))

// After (deterministic, no credentials):
val workflow = buildClaimTriageWorkflow(DeterministicClaimClassifier())
```

The [testing guide](governed-workflow-testing.md) explains the full pattern, including how deterministic fakes work and how to set them up.

A governed workflow should be testable without:
- Model calls
- API keys
- Network access
- Provider latency

If a test requires any of these, it is not following the governed workflow testing pattern.

---

## What This Guide Does Not Prove

This guide helps diagnose common workflow failures. It does **not**:

- Prove production readiness
- Replace domain review or business validation
- Cover every possible failure mode — only the most common patterns from governed workflows
- Diagnose provider-specific failures beyond unavailability and structured output issues
- Diagnose persistence store failures (connection errors, migration conflicts, encryption key issues) — those are infrastructure concerns
- Diagnose legal, compliance, or regulatory failures — TramAI provides structural evidence, not compliance validation
- Claim that fixing a test failure means the AI output is correct or the workflow is production-ready

---

## Where to Look Next

| Topic | Link |
|-------|------|
| Governed workflow quickstart | [Quickstart](governed-workflow-quickstart.md) |
| Runnable example | [`examples/governed-workflow`](../../examples/governed-workflow) |
| Testing governed workflows | [Testing Guide](governed-workflow-testing.md) |
| General testing (MockAiProvider, observers) | [Testing Guide](testing.md) |
| Approval workflow ergonomics | [Approval Ergonomics Guide](approval-workflow-ergonomics.md) |
| Approval gateway | [Golden Path Guide](approval-gateway-golden-path.md) |
| Structured output contracts | [Contract Lifecycle](../structured-output-contract-lifecycle.md) |
| Orchestration DSL | [Orchestration Guide](orchestration.md) |
| Orchestration persistence | [Persistence Guide](orchestration-persistence.md) |
| Workflow lifecycle model | [Lifecycle Model](../workflow-lifecycle-model.md) |
| Workflow API stability | [Stability Boundary](../workflow-api-stability-boundary.md) |
