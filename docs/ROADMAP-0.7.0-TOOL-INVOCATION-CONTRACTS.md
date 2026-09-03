# TramAI 0.7.0 — Typed Tool Invocation Contracts

> **Status:** P1 roadmap companion  
> **Target release:** TramAI 0.7.0 / first 0.7.x follow-up where necessary  
> **Relationship:** Complements `ROADMAP-0.7.0-RELEASE-CUT.md`, tool governance, structured semantic contracts, provider capabilities, workflow DX, audit/evidence, and governance reconstruction/replay  
> **Scope:** Let an operation declare whether tool use is optional, required, or constrained to a specific tool, and make TramAI enforce that declaration as an execution contract rather than relying on prompt compliance.

---

## 1. Decision

TramAI should add a provider-neutral **tool invocation contract** to AI operations.

Today an operation can expose tools, but exposure and invocation are different concepts:

```text
tools = [schedule-payment]
```

means:

> the model may call this tool.

It does **not** mean:

> this operation is invalid unless the model calls this tool.

For governed workflows that distinction matters.

The target contract is:

```text
Tool exposure
    !=
Tool invocation requirement
```

and:

> **If an operation declares required tool use, ordinary terminal model content is not a valid outcome until that tool-use contract has been satisfied.**

This must be enforced by TramAI, not merely expressed in the prompt.

---

## 2. Current gap

The current stable `@Operation` surface includes a list of available tools together with retry/timeout/cache settings, but no tool-choice requirement.

The OpenAI-compatible provider serializes tool definitions through the provider `tools` field and parses returned `message.tool_calls`, but does not currently express an OpenAI-compatible `tool_choice` constraint.

Therefore the current execution model is effectively:

```text
TramAI exposes tools
       ↓
model chooses whether to invoke one
       ↓
TramAI accepts either tool_calls or ordinary content
```

This can be hidden by deterministic test providers that always emit the expected tool call.

With smaller/local models, prompt adherence is not sufficient to treat required tool execution as a framework invariant.

---

## 3. Product principle

The capability should be framed as an execution contract, not as an OpenAI-specific request option.

OpenAI-compatible `tool_choice` is one provider mapping of the contract.

Conceptually:

```text
Operation tool contract
        ↓
provider-native constraint when supported
        +
TramAI runtime validation
        ↓
valid tool transition or explicit contract failure
```

Provider-native enforcement is useful, but TramAI remains authoritative for deciding whether the operation contract was satisfied.

---

## 4. Semantic model

The exact public API names are not frozen by this roadmap.

The framework should support at least these semantics:

```text
AUTO
REQUIRED_ANY
REQUIRED_NAMED(tool)
```

A later/optional `NONE`/forbidden mode may be useful where tools are registered in a wider invocation context but must not be used by one operation.

### 4.1 AUTO

Existing behavior.

Tools may be exposed, but the model may return normal content without calling one.

```text
available tools = {A, B}
model returns content
→ valid

model calls A
→ tool governance/execution path
```

This preserves backward compatibility.

### 4.2 REQUIRED_ANY

At least one allowed/exposed tool must be invoked before the operation can produce a valid terminal model result.

```text
available tools = {A, B}
model returns normal content first
→ contract not satisfied

model calls B
→ tool governance/execution
→ requirement satisfied
```

### 4.3 REQUIRED_NAMED(tool)

A specific declared tool must be invoked before terminal completion is valid.

```text
available tools = {schedule-payment, explain-payment}
required tool = schedule-payment

model returns text
→ invalid

model calls explain-payment only
→ requirement still unsatisfied

model calls schedule-payment
→ tool governance/execution path
→ requirement satisfied
```

The named tool must be part of the operation's declared/exposed tool set.

Invalid static combinations should fail during operation-definition compilation/startup where possible.

---

## 5. Public API direction

The user-facing goal may look conceptually like:

```kotlin
@Operation(
    model = "local-model",
    tools = ["schedule-payment"],
    toolChoice = ...,
)
```

but the exact annotation shape must respect JVM/Kotlin annotation constraints and API compatibility.

Possible implementation shapes include an enum plus an optional required tool name, a dedicated annotation value, or an operation-definition model compiled from the annotation.

Illustrative only:

```kotlin
@Operation(
    model = "local-model",
    tools = ["schedule-payment"],
    toolSelection = ToolSelection.REQUIRED_NAMED,
    requiredTool = "schedule-payment",
)
```

The roadmap freezes the **semantics**, not this exact syntax.

The Workflow DSL 2.0 may later expose the same contract more naturally:

```kotlin
ai("payment-decision") {
    tools(schedulePayment)
    requireTool(schedulePayment)
    // ...
}
```

Both authoring surfaces must lower to the same canonical operation/tool contract.

---

## 6. Runtime invariant

For a required-tool operation:

```text
terminalSuccess
    ⇒
requiredToolContractSatisfied
```

For a named requirement:

```text
terminalSuccess
    ⇒
requiredTool ∈ successfully accepted tool-call sequence
```

"Accepted" is important.

A model merely emitting the tool name does not bypass governance.

The required tool still passes through:

```text
model proposes tool call
        ↓
tool identity/schema validation
        ↓
policy / permission evaluation
        ↓
approval if required
        ↓
tool execution or governed denial
```

A required-tool declaration never widens authority.

---

## 7. Required tool does not override governance

Core invariant:

```text
required(tool X)
    ∧
policy denies(tool X)
    ↓
operation cannot satisfy contract
```

It must **not** become:

```text
required(tool X)
    ↓
force X despite policy
```

If governance denies the required tool, TramAI should expose a deterministic terminal outcome such as a tool-contract/governance conflict rather than accepting ordinary model text as fallback success.

Similarly, human approval requirements remain authoritative.

A model cannot satisfy the contract by requesting a tool whose execution is not authorized.

---

## 8. Provider capability mapping

The contract is provider-neutral, while providers differ in native enforcement support.

Examples:

```text
OpenAI-compatible
  AUTO           → omit/use provider auto choice
  REQUIRED_ANY   → tool_choice = required where supported
  REQUIRED_NAMED → named function tool_choice where supported
```

Other providers may expose equivalent mechanisms or only basic tool calling.

TramAI should distinguish at least conceptually between:

```text
TOOL_CALLING
NATIVE_REQUIRED_TOOL_CHOICE
NATIVE_NAMED_TOOL_CHOICE
```

The exact provider-capability API can be designed during implementation.

### Framework enforcement remains authoritative

A provider without native forcing may still be usable for a required-tool contract if TramAI can safely enforce the terminal-output contract itself:

```text
provider cannot force tool choice
        ↓
request sent with available tools
        ↓
model returns ordinary content
        ↓
TramAI rejects it as unsatisfied tool contract
```

Provider-native forcing improves reliability and reduces wasted turns; it must not be the only enforcement layer.

If a deployment/profile requires native enforcement specifically, that should be an explicit capability/policy constraint rather than an accidental provider-specific behavior.

---

## 9. Tool-loop semantics

The requirement applies to the **operation invocation**, not necessarily to every model turn in a tool loop.

Example:

```text
turn 1
model → schedule-payment(...)

TramAI governs + executes tool

tool result → model

turn 2
model → final explanation
```

If `schedule-payment` was the required named tool and its governed invocation was accepted/executed according to the operation semantics, the final explanatory content may be valid.

The runtime therefore needs an invocation-level tool-contract state rather than checking only the final provider response.

Conceptually:

```text
ToolContractState
  required
  observed calls
  accepted calls
  denied calls
  satisfied
```

This state should participate in safe evidence/reconstruction without exposing sensitive arguments by default.

---

## 10. Failure semantics

Required-tool contract failure must be explicit and typed.

Examples of failure reasons:

```text
REQUIRED_TOOL_NOT_CALLED
REQUIRED_NAMED_TOOL_NOT_CALLED
REQUIRED_TOOL_NOT_DECLARED
REQUIRED_TOOL_UNSUPPORTED
REQUIRED_TOOL_DENIED_BY_POLICY
REQUIRED_TOOL_APPROVAL_DENIED
TOOL_CONTRACT_EXHAUSTED
```

Exact codes are implementation work, but failures must not collapse into generic parse errors or successful text responses.

A required-tool failure is different from:

- provider transport failure;
- structured-output validation failure;
- tool execution failure;
- policy denial;
- approval denial.

The causal chain should remain visible.

---

## 11. Retry / repair semantics

Tool-contract retries should be bounded and explicit.

Do not silently reuse `providerRetries`, which is for transient provider/transport failures.

Do not automatically conflate tool-contract repair with structured-output `maxRetries` without documenting that semantic change.

A first implementation may simply fail when the provider/model violates the required-tool contract.

A later bounded repair loop may re-prompt/retry with safe contract feedback, but it must have:

- an explicit attempt budget;
- deterministic exhaustion behavior;
- no duplicate external side effects;
- awareness of whether the required tool was already executed;
- replay/idempotency safety;
- safe diagnostics that do not leak sensitive tool inputs.

Once a required side-effecting tool has executed, retrying the pre-tool turn must never accidentally execute it again without the existing replay/idempotency protections.

---

## 12. Interaction with structured semantic contracts

Tool invocation contracts and structured-output contracts share the same philosophy:

> **The model proposes. Contracts decide what may cross the application boundary.**

But they are different validation layers.

```text
Tool invocation contract
  → did the required governed transition happen?

Structured semantic contract
  → is the returned typed value semantically valid?
```

An operation may require both.

Example:

```text
required tool = schedule-payment
        ↓
tool governed + executed
        ↓
model produces PaymentDecision
        ↓
semantic validator checks amount/status/reference
        ↓
terminal success
```

Neither contract should silently substitute for the other.

---

## 13. Interaction with audit, reconstruction, and replay

The control plane should be able to explain:

```text
Operation: schedule-approved-payment
Tool contract: REQUIRED_NAMED(schedule-payment)
Provider native enforcement: SUPPORTED / NOT_SUPPORTED

Model turn 1:
  schedule-payment requested

Governance:
  permission allowed
  approval required
  approval granted

Tool execution:
  accepted/executed

Tool contract:
  SATISFIED

Terminal response:
  accepted
```

Or:

```text
Tool contract: REQUIRED_NAMED(schedule-payment)
Model returned normal content
Tool contract: UNSATISFIED
Terminal response: REJECTED
```

Reconstruction should preserve the declared tool contract and satisfaction result.

Policy replay must not re-execute the tool.

---

## 14. Deterministic provider/TCK coverage

The existing deterministic provider tests must not hide the distinction between "tool available" and "tool required".

Required test categories:

1. AUTO + normal content → accepted.
2. AUTO + tool call → normal governed tool path.
3. REQUIRED_ANY + no tool call → rejected.
4. REQUIRED_ANY + allowed tool call → satisfied.
5. REQUIRED_NAMED(A) + call B only → rejected/unsatisfied.
6. REQUIRED_NAMED(A) + call A → satisfied.
7. required named tool absent from declared tools → fail at definition/startup.
8. required tool requested but denied by policy → no fallback text success.
9. required tool requires approval and approval denied → contract not satisfied.
10. provider-native required choice mapping emitted when supported.
11. named provider-native choice mapping emitted when supported.
12. provider without native choice still cannot return successful ordinary content for a required contract.
13. provider claims native enforcement but returns no required call → TramAI still rejects terminal success.
14. contract state survives the supported tool loop and is visible in evidence.
15. retries cannot duplicate an already executed side effect.

Mutation tests should remove the terminal contract check and prove the discriminator suite fails.

---

## 15. P1 implementation scope

### P1.1 Canonical contract model

- Define provider-neutral tool-selection/invocation requirement semantics.
- Preserve current AUTO behavior as the compatibility default.
- Validate named requirements against declared tools.

### P1.2 Operation authoring

- Extend operation metadata/API without breaking existing operations.
- Compile annotation/DSL authoring into the same canonical tool contract.
- Keep Java/Kotlin API usability explicit.

### P1.3 Engine enforcement

- Track invocation-level tool-contract satisfaction.
- Reject terminal success when a required contract remains unsatisfied.
- Keep governance, approval, and tool execution authoritative.
- Provide typed failure/reason codes.

### P1.4 Provider mapping

- Map required/required-named semantics to native provider features where available.
- Model provider support explicitly.
- Preserve framework enforcement independently from provider compliance.

### P1.5 Evidence and testing

- Emit safe tool-contract declaration/satisfaction evidence.
- Add provider TCK/contract tests with both compliant and adversarial responses.
- Add mutation tests for removed enforcement.

---

## 16. Priority

This capability is **P1** for the normalized 0.7.0 release cut.

It materially strengthens TramAI's typed-contract positioning and makes local/smaller-model workflows more reliable, but the central governance control-plane loop can exist without it.

However, a narrower correctness rule becomes mandatory wherever TramAI itself claims that an operation has a required tool transition:

> **Once a required-tool contract is exposed as a supported API, TramAI must fail closed rather than silently accept terminal content that violates it.**

Partial provider support must be reported explicitly rather than silently degrading required semantics to AUTO.

---

## 17. Non-goals

This slice does not require:

- a generic planner/agent language;
- arbitrary tool-order constraint graphs;
- model-generated dynamic tool policy;
- bypassing tool policy or approval;
- automatic compensation for tool effects;
- unlimited tool-repair loops;
- provider-specific APIs leaking into core operation semantics;
- forcing every operation with tools to use a tool;
- replacing prompts with tool contracts for ordinary conversational intent.

Future work may consider richer sequences such as "A before B" only if concrete governed use cases justify the complexity.

---

## 18. Acceptance criteria

The slice is complete when:

- operations can distinguish tool availability from required tool invocation;
- AUTO remains backward compatible;
- required-any and required-named semantics are represented canonically;
- a required named tool must be declared/exposed;
- providers use native forcing where supported without making native forcing the sole enforcement layer;
- ordinary terminal content cannot satisfy a required-tool operation before the contract is met;
- calling a different tool does not satisfy a named requirement;
- required tool declarations never bypass policy, permissions, approvals, or tool validation;
- governance denial/approval denial cannot degrade into successful normal-content fallback;
- failure reasons are typed and observable safely;
- deterministic/TCK tests include adversarial "model refuses/skips required tool" cases;
- tool-contract evidence can be reconstructed without replaying the tool;
- provider-specific request mapping and engine-level contract enforcement are tested separately;
- tool retries/repair cannot duplicate already executed side effects.

---

## 19. Product principle

> **Making a tool available is a capability. Requiring a tool is a contract. TramAI should enforce the contract instead of hoping the prompt convinces the model.**
