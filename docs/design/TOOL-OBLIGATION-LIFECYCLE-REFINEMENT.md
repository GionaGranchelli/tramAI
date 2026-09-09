# TramAI 0.7.0 — Tool Obligation Lifecycle Refinement

> **Status:** P1 design-refinement companion  
> **Target release:** TramAI 0.7.0 / first 0.7.x follow-up where necessary  
> **Relationship:** Refines `ROADMAP-0.7.0-TOOL-INVOCATION-CONTRACTS.md`; it does not create a second tool-contract subsystem or change the normalized P1 priority  
> **Purpose:** Capture the additional design requirements exposed by real-model behavior where trusted application facts require a governed tool transition, but the model returns semantically plausible typed content without emitting the required tool call.  
> **Design rule:** The fail-closed invariant is firm. The exact dynamic-obligation API, lifecycle states, provider-turn mapping, and resume semantics must be refined carefully before public API freeze.

---

## 1. Finding

The existing tool-contract roadmap correctly distinguishes:

```text
Tool exposure
    !=
Tool invocation requirement
```

and already targets:

```text
AUTO
REQUIRED_ANY
REQUIRED_NAMED(tool)
```

with:

```text
terminalSuccess
    =>
declaredToolContractSatisfied
```

A real-model workflow exposed a deeper case.

Trusted application logic can already know that a governed transition is mandatory before the model is invoked.

Example:

```text
invoice amount = EUR 18,400
trusted amount rule => HIGH risk
HIGH risk policy => schedule-payment transition required
```

The model may nevertheless return a perfectly parseable assessment such as:

```text
risk = HIGH
action = REQUEST_HUMAN_APPROVAL
```

without emitting:

```text
schedule-payment(...)
```

If TramAI accepts that typed assessment as terminal success, no real approval continuation exists. The model has only **described** a governance action; TramAI has not actually entered that governance state.

Core distinction:

> **Model output may describe governance intent. Only TramAI runtime state proves that a governed transition exists.**

---

## 2. Why this belongs in TramAI

Applications can temporarily detect the mismatch and fail closed, but they cannot safely manufacture the missing TramAI continuation.

The application should not:

- fabricate an approval record;
- invent a challenge/continuation token;
- directly call the governed side-effecting tool as a substitute;
- manually recreate TramAI audit/evidence semantics;
- treat a model field such as `REQUEST_HUMAN_APPROVAL` as equivalent to an active approval workflow.

Those approaches create a parallel authority path.

The canonical framework path remains:

```text
trusted requirement
      ↓
model proposes required tool
      ↓
TramAI tool validation/governance
      ↓
approval continuation where required
      ↓
suspend
      ↓
approve/deny/timeout
      ↓
resume
      ↓
execute exactly once
      ↓
final model result
```

Therefore the missing capability is a refinement of TramAI's tool invocation contract, not a frontend condition or application-specific fake approval mechanism.

---

## 3. Trusted facts must resolve obligation before provider invocation

The model must not be authoritative for deciding whether a mandatory governed transition exists.

Preferred direction:

```text
trusted application/workload facts
          +
authoritative policy/configuration
          ↓
resolve tool obligation
          ↓
provider invocation
```

For example:

```text
amount < threshold
→ AUTO

amount >= threshold
→ REQUIRED_NAMED(schedule-payment)
```

This is fundamentally different from:

```text
model returns HIGH
→ application decides afterward that a tool should have been required
```

The latter is too late to guarantee the governed transition.

Core invariant:

> **A behavior-affecting tool obligation must be resolved from trusted invocation context or authoritative policy, not inferred solely from model output that the obligation is meant to constrain.**

---

## 4. Static contract remains the semantic baseline

The existing static contract remains useful and should not be destabilized unnecessarily.

```text
AUTO
REQUIRED_ANY
REQUIRED_NAMED(tool)
```

remains the canonical resolved result.

The refinement is that the operation may eventually support an authoritative resolution step:

```text
ToolObligationResolver
        ↓
ResolvedToolObligation
        ↓
AUTO | REQUIRED_ANY | REQUIRED_NAMED(tool)
```

The roadmap deliberately does **not** freeze the public API shape yet.

Illustrative only:

```kotlin
toolRequirement { invocation ->
    if (invocation.amount >= highRiskThreshold) {
        required(schedulePayment)
    } else {
        auto()
    }
}
```

Do not infer from this example that the final API must expose arbitrary lambdas in annotations, persist executable closures, or use this exact DSL.

Do not introduce a generic string-expression language such as:

```text
"amount >= 5000"
```

as part of this refinement.

---

## 5. Obligation is not authorization

A required tool is still not automatically permitted.

The framework must preserve:

```text
TOOL OBLIGATION
    ↓
what governed transition is required for success
```

separately from:

```text
TOOL AUTHORITY
    ↓
whether the proposed transition may execute
```

Example:

```text
schedule-payment REQUIRED
        ↓
model proposes schedule-payment
        ↓
schema / identity validation
        ↓
policy
        ↓
permission
        ↓
human approval
        ↓
idempotency / replay controls
        ↓
execution
```

It is valid for TramAI to say simultaneously:

```text
required transition = schedule-payment
current authority    = AWAITING_APPROVAL
```

or:

```text
required transition = schedule-payment
current authority    = DENIED_BY_POLICY
```

A requirement never widens authority.

---

## 6. The contract must be phase-aware

The provider constraint must not be applied blindly to every turn.

For a side-effecting tool that requires approval, the lifecycle may be:

```text
UNSATISFIED
    ↓
TOOL_PROPOSED
    ↓
AWAITING_APPROVAL
    ↓
AUTHORIZED
    ↓
EXECUTING
    ↓
EXECUTED
    ↓
SATISFIED
```

These names are conceptual, not frozen public enums.

The important semantic boundary is:

```text
before required transition succeeds
→ terminal normal content is invalid

once required transition succeeds
→ final normal/typed model content may be valid
```

This prevents a static required-tool flag from forcing the same side-effecting tool again after resume.

---

## 7. Provider forcing must depend on contract phase

For an OpenAI-compatible provider, the pre-tool turn may map:

```text
REQUIRED_NAMED(schedule-payment)
```

to a native constraint equivalent to:

```json
{
  "tool_choice": {
    "type": "function",
    "function": {
      "name": "schedule-payment"
    }
  }
}
```

After the governed tool transition has executed and the invocation-level contract is satisfied, the resumed provider call must not blindly keep forcing `schedule-payment`.

Conceptually:

```text
PRE-TOOL TURN
contract = UNSATISFIED
provider forcing = REQUIRED_NAMED(schedule-payment)

POST-TOOL / RESUMED TURN
contract = SATISFIED
provider forcing = AUTO / no required-tool forcing
```

The final turn may then produce the operation's typed result.

This phase-aware provider mapping is a required design property, but the exact provider request API must be refined carefully because providers differ in tool-loop semantics and native capability.

---

## 8. Approval suspension is part of the tool contract lifecycle

A required tool that needs human approval is not yet contract-satisfied when the model merely proposes it.

Example:

```text
model calls schedule-payment
        ↓
tool governance = approval required
        ↓
TramAI creates real approval continuation
        ↓
operation suspends
```

At this point:

```text
operation state = AWAITING_APPROVAL
```

not:

```text
terminal success
```

The required transition becomes satisfied only at the correct authoritative point defined by the operation semantics.

For side-effecting tools, the safest default interpretation is that the obligation is satisfied by successful governed execution, not merely by the model emitting a syntactically valid tool call.

The exact distinction among **observed**, **accepted**, **authorized**, **executed**, and **satisfied** must be defined carefully during implementation.

---

## 9. Model assessment is not runtime governance state

This refinement makes an important product distinction explicit.

```text
ModelAssessment
    !=
RuntimeGovernanceState
```

Examples:

```text
model: "REQUEST_HUMAN_APPROVAL"
```

is descriptive model output.

```text
TramAI: ApprovalContinuation(PENDING)
```

is authoritative runtime state.

Similarly:

```text
model: "PAYMENT_SCHEDULED"
```

must never prove that a payment executed.

Authoritative execution/evidence must come from TramAI's tool runtime and the underlying tool result.

Suggested invariant:

> **No model-authored field, status, or natural-language statement may substitute for a required authoritative tool/approval transition.**

---

## 10. Fail-closed mismatch behavior

A resolved required-tool obligation plus a model response that omits the required transition must not become successful application output.

Example:

```text
trusted facts => REQUIRED_NAMED(schedule-payment)
model returns HIGH + REQUEST_HUMAN_APPROVAL
model emits no schedule-payment call
```

Required result:

```text
TOOL CONTRACT UNSATISFIED
no payment
no fabricated approval
no misleading terminal success
```

Exact exception/result/HTTP mapping belongs to the integration layer, but the framework outcome must be typed and distinguishable.

The application may map this to an explicit non-success response while the framework contract is being introduced.

---

## 11. Short-term application guard

Before the canonical TramAI capability is implemented, an application may defensively detect:

```text
trusted condition requires governed tool
+
model returned without required tool transition
```

and fail closed.

This is an acceptable temporary compatibility guard if it:

- does not execute the tool;
- does not manufacture approval state;
- does not report a completed governed workflow;
- exposes an explicit mismatch/failure;
- preserves existing TramAI continuation behavior whenever the real tool call is present.

This guard is not the final authority model and should be removable once TramAI owns the contract end-to-end.

---

## 12. Cross-provider semantics

The contract must behave consistently across operation profiles such as:

```text
LOCAL
LOCAL_NVIDIA
CLOUD
EU_CLOUD
GLOBAL_CLOUD
```

where those profiles use providers/models capable of the operation.

The governance contract is provider-neutral.

Provider differences should affect:

- whether native tool forcing is available;
- request serialization;
- tool-loop wire format;
- capability evidence;
- failure reason when required native enforcement is demanded but unsupported.

Provider differences must not silently change:

```text
required tool omitted
→ terminal success rejected
```

into:

```text
required tool omitted
→ success on provider X
```

---

## 13. Interaction with structured output

Structured output correctness and tool-obligation correctness remain separate.

A response can be perfectly valid structurally and semantically while still violating the required tool contract.

Example:

```text
PaymentAssessment(
  risk = HIGH,
  action = REQUEST_HUMAN_APPROVAL
)
```

may pass JSON/schema/semantic validation.

But if:

```text
resolved obligation = REQUIRED_NAMED(schedule-payment)
```

and no real tool transition occurred, the overall operation cannot terminate successfully.

Ordering requirement:

```text
provider output
      ↓
tool obligation / tool-loop semantics
      ↓
governance + approval + execution
      ↓
final typed output validation
      ↓
terminal success
```

Exact implementation layering must preserve existing repair/retry behavior and avoid creating duplicate side effects.

---

## 14. Interaction with retries and idempotency

Phase-aware obligations make retry boundaries security-critical.

Before tool execution:

```text
required tool omitted
→ optional bounded contract repair/retry later
```

may be possible.

After tool execution:

```text
final model result malformed
```

must never cause TramAI to restart the pre-tool turn in a way that re-executes the side effect.

The refinement must define:

- which phase may be retried;
- which provider message history is reused;
- how contract state survives retry/resume;
- how tool-call identity/idempotency is preserved;
- how already executed side effects are fenced;
- how structured-output repair interacts with a satisfied tool obligation.

No public dynamic-obligation API should be frozen until these boundaries are explicit.

---

## 15. Interaction with approval validity and replacement

A required-tool invocation may suspend on approval and later encounter:

- approval timeout;
- approval revocation/invalidation;
- safe approval replacement;
- changed current policy;
- execution-time hard safety denial.

The tool obligation itself does not revive expired authority.

If approval A expires and is replaced by approval B:

```text
required tool obligation
        ↓
approval A TIMED_OUT
        ↓
replacement creates B
        ↓
old continuation remains unusable
        ↓
only B can authorize the fresh governed path
```

The invocation-level tool contract must remain consistent with approval lineage and exactly-once execution semantics.

This interaction requires explicit design review with the approval-lifecycle companion before implementation is frozen.

---

## 16. Evidence and control-plane semantics

The control plane should be able to explain both the **resolved obligation** and its lifecycle.

Illustrative evidence:

```text
Operation: high-risk-payment
Trusted condition: amount threshold matched
Resolved tool obligation: REQUIRED_NAMED(schedule-payment)
Provider native forcing: NAMED / FALLBACK_RUNTIME_ENFORCEMENT

Turn 1:
  required tool proposed

Governance:
  approval required

Tool contract lifecycle:
  UNSATISFIED
  → AWAITING_APPROVAL
  → AUTHORIZED
  → EXECUTED
  → SATISFIED

Turn 2:
  final typed assessment accepted
```

Failure example:

```text
Resolved tool obligation: REQUIRED_NAMED(schedule-payment)
Model response: normal typed assessment
Required call observed: false
Terminal response: rejected
Reason: REQUIRED_NAMED_TOOL_NOT_CALLED
```

Evidence should not expose raw sensitive tool arguments by default.

---

## 17. Deterministic discriminator tests

In addition to the existing tool-contract TCK coverage, this refinement requires cases derived from the real failure mode.

### A. Trusted condition requires tool, model omits it

```text
trusted condition => REQUIRED_NAMED(schedule-payment)
model returns valid HIGH / REQUEST_HUMAN_APPROVAL assessment
model emits no tool call

expected:
  no terminal success
  no payment
  no fabricated approval
  explicit contract failure
```

### B. Required tool enters approval

```text
trusted condition => REQUIRED_NAMED(schedule-payment)
model emits schedule-payment
policy => approval required

expected:
  real TramAI continuation created
  operation suspends
  state = AWAITING_APPROVAL
  payment count = 0
```

### C. Approve and resume

```text
approve continuation
resume

expected:
  schedule-payment executes exactly once
  tool contract becomes SATISFIED
  resumed provider turn may return final typed content
```

### D. Resumed turn must not force tool again

```text
required tool already executed
resumed provider request

expected:
  no required-named forcing for the already satisfied obligation
  model may return terminal typed result
  payment count remains 1
```

### E. Low-risk invocation

```text
trusted condition => AUTO

expected:
  ordinary tool-choice semantics
  no artificial required-tool failure
```

### F. Provider ignores native forcing

```text
provider advertises named forcing
request includes named tool choice
provider/model returns ordinary content

expected:
  TramAI still rejects terminal success
```

### G. Provider lacks native forcing

```text
resolved obligation = REQUIRED_NAMED
provider native forcing unavailable
```

Expected behavior depends on explicit capability/policy configuration, but must never silently degrade to AUTO.

### H. Duplicate approval/resume

```text
required tool approved and executed once
second approval/resume attempt

expected:
  deterministic conflict/replay-safe result
  side-effect count unchanged
```

### I. Typed result cannot impersonate runtime state

```text
model returns action = PAYMENT_SCHEDULED
actual tool execution count = 0

expected:
  cannot satisfy required tool contract
```

Mutation tests should prove that removing the phase-aware terminal gate or re-enabling forcing after satisfaction causes discriminator failure.

---

## 18. Mandatory design-refinement pass before API freeze

This is the part that must be handled carefully.

The roadmap considers these invariants sufficiently clear to implement toward:

1. required-tool semantics are provider-neutral;
2. trusted facts/policy may require a tool before model execution;
3. required normal-content mismatch fails closed;
4. model-authored governance wording cannot substitute for runtime state;
5. provider-native forcing is not the sole authority;
6. forcing must stop once the relevant obligation is satisfied;
7. approval/governance/idempotency remain authoritative;
8. already executed side effects must never be duplicated by retry/resume.

However, the following details require an explicit refinement/design review before stable public API freeze:

- whether dynamic obligation resolution belongs in operation metadata, workflow DSL, policy evaluation, or a dedicated resolver SPI;
- how trusted invocation inputs are represented without introducing mutable ambient context;
- whether the resolved obligation itself is checkpointed/digested for resume/replay integrity;
- the exact lifecycle states and which are public versus internal;
- whether `SATISFIED` means accepted tool call, authorized call, or successful execution for different tool categories;
- how read-only versus side-effecting tools may differ;
- provider capability negotiation and behavior when native forcing is unavailable;
- how many provider turns may occur while an obligation remains unsatisfied;
- retry/repair budgets and failure taxonomy;
- structured-output repair after tool execution;
- approval timeout/replacement interaction;
- exactly-once/idempotency behavior across restart/resume;
- evidence retention and privacy of resolved trusted conditions;
- Java/Kotlin annotation and DSL ergonomics;
- compatibility behavior for existing `tools = [...]` operations.

Core gate:

> **Do not freeze the dynamic tool-obligation API until phase, resume, approval, retry, idempotency, provider-capability, and evidence semantics are reviewed together.**

This refinement should be treated like the learning-trace privacy gate: implementation experiments may proceed, but the public contract should not be declared complete while the cross-cutting safety semantics remain ambiguous.

---

## 19. Initial implementation recommendation

A safe staged implementation would be:

### Stage 1 — static required-tool contract

- `AUTO`, `REQUIRED_ANY`, `REQUIRED_NAMED`;
- OpenAI-compatible native mapping;
- engine-level fail-closed enforcement;
- invocation-level satisfaction state;
- final content allowed after satisfaction;
- adversarial provider tests.

### Stage 2 — authoritative dynamic resolution

- resolve one of the existing canonical contract values from trusted invocation context;
- record/digest the resolved result;
- prove resume/replay integrity;
- keep provider mapping phase-aware.

### Stage 3 — richer repair/capability behavior

- bounded contract repair if justified;
- richer provider capability policy;
- tool-effect-aware satisfaction semantics where necessary.

This sequencing preserves a small stable core while allowing careful refinement of the harder cross-cutting parts.

---

## 20. Non-goals

This refinement does not require:

- a generic rules engine;
- string-expression policy;
- model-generated dynamic authorization;
- arbitrary planner graphs;
- arbitrary multi-tool ordering constraints;
- application-created fake TramAI continuations;
- automatic execution merely because the model labels something high risk;
- bypassing existing approval/tool governance;
- unlimited re-prompt loops;
- making every tool-using operation required-tool by default.

---

## 21. Acceptance criteria for the refinement

The refinement is ready for implementation/API finalization when:

- static required-tool semantics remain backward-compatible and canonical;
- a trusted pre-model condition can resolve a required tool without relying on model self-reporting;
- the resolved obligation is stable across the supported invocation/resume path;
- terminal model content is rejected while the obligation remains unsatisfied;
- an actual approval continuation, not model wording, represents `AWAITING_APPROVAL`;
- provider-native forcing is applied only while required by the current phase;
- the resumed/final turn can return normal typed content after successful required execution;
- already executed tools cannot be forced or executed a second time accidentally;
- provider capability gaps fail explicitly rather than degrading silently;
- structured-output validation cannot substitute for tool-contract satisfaction;
- evidence can distinguish model assessment from authoritative governance state;
- cross-provider deterministic tests cover the real high-risk omission case;
- the dynamic-obligation API has passed the mandatory cross-cutting refinement review.

---

## 22. Product principle

> **The model may describe what should happen. Trusted policy determines what must happen. TramAI proves whether it actually happened.**

And:

> **A required tool is an invocation obligation, not a permanent provider setting. Force it only while required, govern it normally, and fail closed until the obligation is truly satisfied.**
