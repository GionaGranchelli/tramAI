# Testing Governed Workflows

> **Status:** Guide — explains how to test governed workflows without real model calls.
> **Phase:** Phase 3 — Workflow Ergonomics of the [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md).
> **Pre-requisites:** Familiarity with the [governed workflow quickstart](governed-workflow-quickstart.md) and the [minimal governed workflow example](../../examples/governed-workflow).

---

## What This Guide Covers

A governed workflow should be testable **without** real model calls, real API keys, network access, or provider latency.

This guide explains how to:

- Replace AI-backed services with deterministic fakes
- Test success and failure paths
- Assert gate rejection diagnostics
- Observe completed and failed step trails
- Know when to use provider-level testing tools instead

It uses the `examples:governed-workflow` module as its running example.

---

## Testing Goals

A useful governed workflow test should prove:

| Goal | What it proves |
|------|----------------|
| Happy path returns typed result | The workflow composes steps correctly |
| Policy gates reject the right state | Gate logic matches business rules |
| Approval gates allow or reject correctly | Approval conditions are enforced |
| Failure messages name the rejecting gate | Diagnostics are explainable |
| Completed and failed steps are observable | The step trail is accurate |
| Tests do not depend on external models | CI runs without credentials |

---

## Test Without Real Model Calls

The core technique is simple: **replace AI-backed services with deterministic fakes**.

For a governed workflow, the AI step is driven by a typed interface (e.g. `ClaimClassifier`). A deterministic fake implements that interface with hardcoded or rule-based logic instead of calling a model.

```kotlin
class DeterministicClaimClassifier : ClaimClassifier {
    override suspend fun classify(claim: ClaimInput): ClaimClassification =
        when {
            claim.type == "restricted" -> ClaimClassification(
                risk = ClaimRisk.RESTRICTED,
                category = "restricted",
                confidence = 1.0,
            )
            claim.amount >= 10_000.0 -> ClaimClassification(
                risk = ClaimRisk.HIGH,
                category = "large-claim",
                confidence = 0.95,
            )
            else -> ClaimClassification(
                risk = ClaimRisk.LOW,
                category = "standard",
                confidence = 0.9,
            )
        }
}
```

The real `DeterministicClaimClassifier` exists in the [`examples/governed-workflow`](../../examples/governed-workflow) module.

This approach:

- Makes tests stable in CI
- Requires no credentials, network, or model latency
- Lets you focus on workflow logic (step order, gate behavior, state transitions)
- Isolates failures to workflow logic, not model output

---

## Pattern 1 — Test with a Deterministic Fake Service

Create a workflow instance using the fake service as a shared fixture:

```kotlin
private val workflow = buildClaimTriageWorkflow(
    DeterministicClaimClassifier(),
)
```

This is the same pattern used in [`GovernedWorkflowTest`](../../examples/governed-workflow/src/test/kotlin/dev/tramai/examples/governed/GovernedWorkflowTest.kt). The test file reuses this instance for all scenarios.

---

## Pattern 2 — Test Success and Failure Paths

Use standard JUnit 5 + AssertJ assertions for the happy path:

```kotlin
@Test
fun `low-risk claim passes governed workflow`() = runBlocking {
    val result = workflow.run(
        initialState = ClaimTriageState(claim = lowRiskClaim),
    )

    assertThat(result.status).isEqualTo("ready-for-review")
}
```

For failure paths where a gate rejects execution, assert the exception type:

```kotlin
@Test
fun `restricted claim is rejected by policy gate`() {
    assertThatThrownBy {
        runBlocking {
            workflow.run(
                initialState = ClaimTriageState(claim = restrictedClaim),
            )
        }
    }
        .isInstanceOf(WorkflowGateRejectedException::class.java)
        .hasMessageContaining("Restricted claim")
}
```

Both patterns are demonstrated in the existing [`GovernedWorkflowTest`](../../examples/governed-workflow/src/test/kotlin/dev/tramai/examples/governed/GovernedWorkflowTest.kt).

---

## Pattern 3 — Assert Gate Rejection Diagnostics

The exception message includes the gate name and the rejection reason:

```kotlin
assertThatThrownBy {
    runBlocking {
        workflow.run(
            initialState = ClaimTriageState(claim = restrictedClaim),
        )
    }
}
    .isInstanceOf(WorkflowGateRejectedException::class.java)
    .hasMessageContaining("policy-check")
    .hasMessageContaining("Restricted claim requires manual handling")
```

This proves that a developer can discover **what** failed, **where** it failed, and **why** it failed — directly from the exception.

For the `approval-required` gate, assert the corresponding gate name and reason:

```kotlin
.hasMessageContaining("approval-required")
.hasMessageContaining("High-risk claim requires human approval")
```

---

## Pattern 4 — Observe Diagnostic Step Trails

Use a `WorkflowObserver` to record which steps started, completed, and failed:

```kotlin
private class RecordingWorkflowObserver : WorkflowObserver {
    val startedSteps = mutableListOf<String>()
    val completedSteps = mutableListOf<String>()
    val failedSteps = mutableListOf<String>()

    override fun onStepStarted(
        workflowName: String, stepName: String, context: WorkflowContext,
    ) { startedSteps += stepName }

    override fun onStepCompleted(
        workflowName: String, stepName: String, context: WorkflowContext,
    ) { completedSteps += stepName }

    override fun onStepFailed(
        workflowName: String, stepName: String,
        error: Throwable, context: WorkflowContext,
    ) { failedSteps += stepName }
}
```

Pass it to `workflow.run(...)` and assert the trail:

```kotlin
val observer = RecordingWorkflowObserver()

assertThatThrownBy {
    runBlocking {
        workflow.run(
            initialState = ClaimTriageState(claim = restrictedClaim),
            observer = observer,
        )
    }
}
    .isInstanceOf(WorkflowGateRejectedException::class.java)
    .hasMessageContaining("policy-check")
    .hasMessageContaining("Restricted claim requires manual handling")

assertThat(observer.completedSteps).containsExactly("classify")
assertThat(observer.failedSteps).containsExactly("policy-check")
```

For the success path, assert a clean trail:

```kotlin
assertThat(observer.startedSteps)
    .containsExactly("classify", "policy-check", "approval-required", "finalize")
assertThat(observer.completedSteps)
    .containsExactly("classify", "policy-check", "approval-required", "finalize")
assertThat(observer.failedSteps).isEmpty()
```

These patterns are proven in [`GovernedWorkflowFailureDiagnosticsTest`](../../examples/governed-workflow/src/test/kotlin/dev/tramai/examples/governed/GovernedWorkflowFailureDiagnosticsTest.kt).

---

## Pattern 5 — Test AI Service Wiring Separately

Workflow-level tests with deterministic fakes cover **workflow logic** (step order, gates, state transitions). They do **not** test:

- Prompt wiring
- Structured output schema generation
- Parse retries and repair feedback loops
- Provider routing or fallback behavior

For those concerns, test the `@AiService` interface directly using `MockAiProvider`, `RecordingOperationObserver`, and `TramaiAssertions` from the `tramai-testing` module.

See the general [Testing Guide](testing.md) for details on:

- `MockAiProvider` — return any typed response
- `RecordingOperationObserver` — capture prompts, responses, and events
- `TramaiAssertions` — fluent structured-output assertions
- Retry simulation and provider failure testing

**Rule of thumb:** One `MockAiProvider`-based test per `@AiService` interface is usually sufficient for prompt-wiring coverage. Use deterministic fakes for workflow-level tests.

---

## What Not to Test

You usually should **not** test:

| Concern | Why |
|---------|-----|
| Real model intelligence in CI | Unstable, slow, requires credentials |
| Exact prompt wording in every workflow test | Prompts change; isolate prompt coverage to service-level tests |
| Provider HTTP behavior from workflow tests | That is a provider adapter concern |
| Persistence or approval resume | Covered by dedicated integration tests for those features |
| Legal, business, or regulatory correctness | TramAI provides structural evidence, not business validation |

This matches the stance of the general [Testing Guide](testing.md): application tests should not test every prompt word, provider HTTP specifics, or Jackson itself.

---

## Commands

```bash
# Run the governed workflow example tests
./gradlew :examples:governed-workflow:test

# Run the example to see console output
./gradlew :examples:governed-workflow:run

# Full project validation
./gradlew check
```

Use the module-level test command while iterating. Run the full `check` before opening a PR.

---

## Where to Look Next

| Topic | Link |
|-------|------|
| Governed workflow quickstart | [Quickstart](governed-workflow-quickstart.md) |
| Runnable example | [`examples/governed-workflow`](../../examples/governed-workflow) |
| Workflow test examples | [`GovernedWorkflowTest.kt`](../../examples/governed-workflow/src/test/kotlin/dev/tramai/examples/governed/GovernedWorkflowTest.kt) |
| Diagnostic trail examples | [`GovernedWorkflowFailureDiagnosticsTest.kt`](../../examples/governed-workflow/src/test/kotlin/dev/tramai/examples/governed/GovernedWorkflowFailureDiagnosticsTest.kt) |
| General testing (MockAiProvider, observers) | [Testing Guide](testing.md) |
| Orchestration DSL | [Orchestration Guide](orchestration.md) |
| Approval gateway | [Golden Path Guide](approval-gateway-golden-path.md) |
