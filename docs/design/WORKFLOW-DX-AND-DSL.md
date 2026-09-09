# TramAI 0.7.0 — Workflow DSL, State, and Context DX

> **Status:** P1 roadmap companion  
> **Target release:** TramAI 0.7.0 / first 0.7.x follow-up where necessary  
> **Relationship:** Complements `ROADMAP-0.7.0-RELEASE-CUT.md`, `ROADMAP-0.7.0.md`, the existing `tramai-orchestration` workflow DSL, workflow persistence/replay, and governance-control-plane work  
> **Scope:** Make TramAI workflows easier to author and reason about without creating a second workflow runtime or weakening durability, replay, policy, or Java compatibility.

---

## 1. Decision

TramAI should evolve the existing `tramai-orchestration` builder DSL into a first-class Kotlin workflow authoring experience.

This is **not** a new workflow engine.

The architectural rule is:

```text
Kotlin DSL
explicit builder API / Java-facing API
        │
        └──────────────┬──────────────┘
                       ▼
             canonical workflow definition
                       │
             same definition digest
                       │
                       ▼
                 TramAI runtime
                       │
       policy / execution / checkpoints
       audit / replay / control plane
```

> **The DSL is syntax, not semantics.**

A workflow authored through the ergonomic Kotlin DSL must have the same execution, checkpoint, recovery, replay, policy, audit, and control-plane semantics as the equivalent explicit workflow definition.

The existing builder API remains available, especially for Java and advanced Kotlin users.

---

## 2. Current friction

The current orchestration surface already has a capable DSL:

```kotlin
workflow<MyState>(name = "analysis") {
    aiStep(...)
    localStep(...)
    gateStep(...)
    branchStep(...)
    parallelStep(...)
}.build { state -> ... }
```

It also deliberately keeps concrete step implementations internal and exposes workflow construction through public builder methods. That is the correct foundation.

The main DX friction is that authoring still exposes too much execution plumbing.

Many step APIs require lambdas shaped like:

```kotlin
(S, WorkflowContext) -> ...
(S, Output, WorkflowContext) -> S
```

This creates several problems:

1. workflow code repeatedly shuttles `state` and `context` parameters even though the runtime already owns that lifecycle;
2. state transitions require repetitive merge/copy plumbing;
3. `WorkflowContext` is easy to treat as an untyped second bag of state;
4. different step types expose context inconsistently;
5. it is not immediately obvious which data is durable across checkpoint/resume;
6. nested and parallel execution make context propagation feel mechanical rather than framework-owned;
7. user code must understand runtime mechanics before simple workflows read naturally.

0.7.0 should improve the authoring model without hiding the execution guarantees that make workflows safe.

---

## 3. State and context must have distinct meanings

The workflow model should explicitly separate three concepts.

### 3.1 Durable workflow state — `S`

`S` is the business/workflow state that evolves across steps.

Examples:

```text
claim
classification
review result
payment decision
retrieved data
processed document references
workflow progress needed by later steps
```

Properties:

- typed by the application;
- evolves explicitly between steps;
- checkpointed where workflow persistence requires it;
- restored on resume;
- available to replay/recovery semantics where applicable;
- should contain facts that later workflow decisions depend on.

Core principle:

> **If a value is part of durable workflow meaning, it belongs in state.**

### 3.2 Immutable run context

Run context describes the execution identity and stable ambient metadata for one workflow run.

Conceptually it may contain:

```text
workflow/run identity
correlation identity
actor/tenant/request identity where configured
stable typed attributes
configuration/evidence identity needed by the runtime
```

The exact API is not frozen by this roadmap.

Run context should be immutable after the run begins.

It must **not** be used as a mutable cross-step scratchpad.

Core principle:

> **Context is not a second state store.**

### 3.3 Framework-owned step context

Step context is runtime metadata for the currently executing step.

Conceptually:

```text
step name
step index/path
attempt identity/count where applicable
branch/parallel path
resume/replay metadata
runtime timing/clock access where deliberately exposed
```

Applications may read supported fields, but they do not manually propagate or mutate this context.

The runtime derives it for every step.

---

## 4. Durability and resume contract

The current runtime checkpoints serialized state, while resumed execution receives a `WorkflowContext` again from the caller.

0.7.0 must make the resulting semantic boundary explicit.

### Required invariant

> **Any run-context value that can affect authoritative workflow behavior must be stable across resume/replay, either because TramAI persists it or because TramAI records an immutable identity/digest and verifies the resumed value against it.**

Authoritative behavior includes, where applicable:

- branch/gate decisions;
- provider/model eligibility;
- policy evaluation;
- tool authorization;
- outbound request construction;
- idempotency-key derivation;
- approval decisions/requirements;
- replay descriptors;
- classification or governance decisions.

A caller must not be able to resume a checkpoint with materially different behavior-affecting context and have TramAI silently treat it as the same historical run.

If a value must change during workflow execution and affect later behavior, prefer durable state rather than mutable context.

### Context categories

A future typed context API may distinguish categories such as:

```text
DURABLE / REPLAY_RELEVANT
IDENTITY_ONLY
EPHEMERAL / OBSERVABILITY_ONLY
```

The exact names are implementation details. What matters is that retention and replay semantics are explicit rather than implied by `Map<String, Any?>`.

---

## 5. Workflow execution scope

The ergonomic DSL should avoid making users repeatedly declare raw `(S, WorkflowContext)` arguments.

A runtime-facing scope may conceptually expose:

```kotlin
interface WorkflowStepScope<S> {
    val state: S
    val run: WorkflowRunContext
    val step: WorkflowStepContext
}
```

This is descriptive, not a frozen API.

The runtime owns scope creation and propagation.

Users write against the scope instead of manually carrying state/context through each lambda.

Example direction:

```kotlin
tramaiWorkflow<ClaimState, ClaimResult>("claim-triage") {
    ai("classify") {
        input { state.claim }
        call(classifier::classify)
        output { classification ->
            updateState {
                copy(classification = classification)
            }
        }
    }

    gate("classification-present") {
        allowIf { state.classification != null }
        otherwise("Classification is required")
    }

    result {
        ClaimResult(
            classification = state.classification,
            reviewed = state.reviewed,
        )
    }
}
```

The names above are illustrative. The key DX goal is that simple workflow code reads in terms of the workflow rather than runtime plumbing.

---

## 6. State-transition ergonomics

TramAI should preserve explicit immutable state transitions while removing repetitive merge plumbing.

Avoid making workflow state globally mutable.

Avoid a magic mutable proxy where arbitrary property writes become hidden checkpoint mutations.

Prefer bounded helpers such as:

```kotlin
updateState {
    copy(score = summary.length.toDouble() / 100.0)
}
```

or equivalent typed transformation semantics.

Important properties:

- one step produces an explicit next `S`;
- checkpointing still observes the same canonical state transition;
- branch and parallel semantics remain deterministic;
- no hidden shared mutable state is introduced;
- state transition remains testable independently from the DSL syntax.

The implementation may lower ergonomic state updates to the existing `merge`/transform functions used by `WorkflowBuilder`.

---

## 7. Context ergonomics

### 7.1 Typed attributes

The long-term ergonomic surface should prefer typed keys/accessors over arbitrary string casts.

Illustrative direction:

```kotlin
val TenantId = workflowAttribute<String>("tenant-id")
val RequestId = workflowAttribute<String>("request-id")
```

then:

```kotlin
val tenantId = run[TenantId]
```

rather than:

```kotlin
context.attributes["tenant-id"] as String
```

The exact storage/serialization strategy is implementation work. The public contract must define missing-value, type-mismatch, retention, resume, and safe-observability behavior.

### 7.2 Automatic propagation

Users should not pass context manually from step to step.

Required semantics:

```text
workflow run context
        │
        ├── top-level step A
        ├── top-level step B
        │       └── branch step B.1
        └── parallel step C
                ├── branch/item C.1
                └── branch/item C.2
```

All descendants receive the immutable run-context snapshot automatically.

Each execution receives its own framework-owned step metadata.

Parallel execution must not introduce a shared mutable context map.

### 7.3 Context-aware helpers

Use cases such as idempotency should become natural:

```kotlin
ai("charge") {
    replayPolicy(EXTERNALLY_IDEMPOTENT)
    idempotencyKey {
        "${run.workflowId}:${step.name}:${state.paymentId}"
    }
    // ...
}
```

Again, syntax is illustrative; semantics must lower to the existing authoritative replay/idempotency machinery.

---

## 8. Consistency across step types

0.7.0 should audit the current DSL for inconsistent state/context availability.

Today some APIs are context-aware while others expose only state/value lambdas.

The new ergonomic scope should make the mental model consistent across:

- local steps;
- AI steps;
- HTTP steps;
- shell/agent steps;
- MCP/plugin steps;
- gates;
- branches;
- parallel fan-out;
- delays where contextual inspection is meaningful;
- result selection.

Not every step must expose every runtime field. The goal is one predictable scope model, not maximum ambient authority.

---

## 9. Governance-aware DSL

Workflow DX may expose governance-aware syntax, but the workflow DSL must never become a second policy language.

Allowed direction:

```kotlin
workflow {
    governance {
        policy("document-processing")
        requireZone("finance-secure")
    }
}
```

provided workload-local constraints only narrow authority according to the 0.7.0 policy-composition contract.

Forbidden direction:

```kotlin
workflow {
    policy {
        allowAnythingDeniedByOrganizationPolicy()
    }
}
```

Core invariant:

```text
organization policy
      ∩ environment policy
      ∩ workflow-local constraints
      = effective authority
```

A more ergonomic DSL can never broaden the eligible execution set.

---

## 10. Canonical workflow parity

Every public authoring surface must lower to the same workflow definition model.

Required equivalence:

```text
explicit WorkflowBuilder definition
             ==
ergonomic Kotlin DSL definition
             ↓
same canonical steps
same definition version/digest semantics
same replay descriptors
same persistence semantics
same validation
same runtime behavior
same observation/evidence
```

Where exact byte-for-byte definition equality is not practical, semantic parity must be proven by tests.

The control plane must not care whether a workflow was authored through the explicit API or the DSL.

---

## 11. Java compatibility

The Kotlin DSL is an additional authoring surface, not a replacement for JVM interoperability.

Target model:

```text
Kotlin
  ├── ergonomic DSL
  └── explicit builder API

Java
  └── explicit builder/facade API

             ↓
       canonical definition
```

Do not make Kotlin-only DSL constructs the runtime authority.

Java applications must retain a reasonable path to the same workflow capabilities.

---

## 12. Interaction with `@AiService`

A future Kotlin-native service DSL may be valuable, but it is not part of the initial Workflow DSL 2.0 requirement.

Existing stable annotation-driven AI service declarations remain valid.

Potential later direction:

```kotlin
aiService<Claim, Classification>("classifier") {
    model("mistral")
    system("You classify claims")
    prompt { claim -> claim.text }
}
```

This should be evaluated separately after the workflow DSL/state/context ergonomics are proven.

Do not redesign the stable `@AiService` surface merely to make all APIs visually similar.

---

## 13. P1 implementation scope

### P1.1 DSL 2.0 façade

- Define a coherent `@DslMarker`-protected Kotlin authoring surface.
- Lower to existing/canonical `WorkflowBuilder` semantics.
- Provide ergonomic aliases/builders for the most-used step types.
- Preserve access to advanced explicit builder functions where needed.

### P1.2 Step/run scope model

- Define a read-only runtime step scope.
- Separate durable state, immutable run context, and framework-owned step context.
- Remove manual context shuttling from normal DSL usage.
- Specify nested/parallel inheritance.

### P1.3 State update ergonomics

- Introduce concise typed immutable state-transition helpers.
- Preserve one explicit next-state transition per step.
- Avoid global/shared mutable workflow state.

### P1.4 Typed context attributes

- Define typed attribute access or equivalent safe mechanism.
- Specify missing/type mismatch behavior.
- Define which context data is durable/replay-relevant versus ephemeral.
- Prevent context from becoming a silent mutable state bag.

### P1.5 Resume/replay context integrity

- Identify behavior-affecting context.
- Persist it or content-address/verify it as required.
- Reject or explicitly flag incompatible resume context.
- Include relevant context identity in reconstruction/replay evidence without leaking sensitive values.

### P1.6 DSL parity tests

- Prove DSL and explicit builder workflows produce equivalent canonical behavior.
- Protect definition-digest compatibility semantics.
- Test checkpoint/resume, replay descriptors, branch/parallel propagation, policy decisions, and observer/evidence parity.

---

## 14. P2 / later

Explicit non-blockers:

- replacing all annotation APIs with DSLs;
- code generation for mutable-looking data-class state proxies;
- generic YAML/JSON/string-expression workflow languages;
- visual workflow builder;
- arbitrary context mutation;
- context as dependency-injection/service-locator container;
- cross-language DSL parity at the syntax level;
- automatic source-code round-tripping between visual and Kotlin workflow definitions.

---

## 15. Safety and determinism requirements

The DX layer must not weaken current workflow guarantees.

Required invariants:

1. DSL syntax does not change runtime policy semantics.
2. DSL syntax does not create a second workflow executor.
3. DSL state helpers cannot bypass checkpoint state transitions.
4. run context is immutable during a run.
5. parallel branches cannot race on shared mutable context.
6. resume cannot silently replace behavior-affecting historical context.
7. workflow-local governance constraints cannot broaden higher-scope authority.
8. DSL and explicit-builder workflows share definition compatibility rules.
9. step context is framework-owned and cannot be forged by ordinary workflow code.
10. sensitive context values are not automatically emitted into logs/evidence.

---

## 16. Acceptance criteria

The P1 slice is successful when:

- a simple multi-step workflow can be authored without repeatedly naming raw `state` and `WorkflowContext` lambda parameters;
- state transitions remain typed, explicit, immutable, and checkpoint-compatible;
- users can clearly explain the difference between workflow state, run context, and step context;
- nested and parallel steps receive context automatically with deterministic semantics;
- behavior-affecting context has an explicit resume/replay durability contract;
- typed context access avoids routine `Map<String, Any?>` casting in new Kotlin workflows;
- the ergonomic DSL lowers to the existing/canonical workflow definition rather than introducing separate execution semantics;
- equivalent explicit-builder and DSL workflows pass parity tests for execution, persistence, replay metadata, policy, and evidence;
- existing Java/builder workflows continue to work;
- existing stable AI service annotations remain unaffected.

---

## 17. Example target experience

Illustrative only:

```kotlin
val TenantId = workflowAttribute<String>("tenant-id")

val workflow = tramaiWorkflow<ClaimState, ClaimResult>("claim-triage") {
    ai("classify") {
        input {
            ClassificationRequest(
                claim = state.claim,
                tenantId = run[TenantId],
            )
        }

        call(classifier::classify)

        output { classification ->
            updateState {
                copy(classification = classification)
            }
        }
    }

    gate("review-required") {
        allowIf { state.classification != null }
        otherwise("Classification required before review")
    }

    branch("risk") {
        select {
            if (state.classification == Classification.HIGH) "high" else "normal"
        }

        case("high") {
            // Higher-level policy still owns authorization.
            approval("human-review")
        }

        otherwise {
            local("auto-review") {
                updateState { copy(reviewed = true) }
            }
        }
    }

    result {
        ClaimResult(
            classification = state.classification,
            reviewed = state.reviewed,
        )
    }
}
```

The desired experience is not fewer characters at any cost.

It is:

> **The developer describes the workflow; TramAI owns the execution mechanics.**

---

## 18. Product principle

> **State carries durable workflow meaning. Context carries stable execution meaning. Step metadata is owned by the runtime. The DSL should make those boundaries feel natural instead of mechanical.**
