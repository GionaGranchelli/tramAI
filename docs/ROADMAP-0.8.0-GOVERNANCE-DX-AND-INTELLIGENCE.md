# TramAI 0.8.0 — Governance DX & Intelligence

> **Status:** Authoritative release roadmap for TramAI 0.8.0  
> **Depends on:** TramAI 0.7.0 Governed AI Control Plane  
> **Purpose:** Make governance easier to define, test, simulate, debug, and explain without creating parallel runtime semantics.  
> **Long-term sequence:** [`LONG-TERM-ROADMAP-0.7-0.10.md`](LONG-TERM-ROADMAP-0.7-0.10.md)

---

## 1. Release thesis

> **Define governance once. Test it before production. Explain the result everywhere.**

0.8 builds developer and governance-authoring capabilities over the authoritative runtime, evidence, control, and reconstruction contracts proven in 0.7.

The central invariant is:

```text
one canonical governance/runtime meaning
        ↓
execution / simulation / testing / debugger / evidence
```

No DSL, annotation layer, YAML/configuration format, simulator, debugger, or testing surface may become a second policy or workflow engine.

---

## 2. Candidate release scope

### 2.1 Workflow DX and canonical authoring

- Workflow DSL 2.0;
- explicit separation of durable workflow state, immutable run context, and framework-owned step context;
- typed immutable state-update ergonomics;
- nested/parallel propagation semantics;
- resume/replay context integrity;
- parity between ergonomic authoring and canonical workflow definitions.

Detailed design: [`design/WORKFLOW-DX-AND-DSL.md`](design/WORKFLOW-DX-AND-DSL.md).

### 2.2 Governance Vocabulary and Facts

- shared typed governance vocabulary across supported routing/tool/approval/data/runtime boundaries;
- typed Governance Facts or equivalent;
- provenance/authority ordering;
- organization extension vocabulary without redefining core TramAI semantics;
- restrictive constraint composition;
- evidence suitable for simulation/testing/reconstruction.

Detailed design: [`design/GOVERNANCE-VOCABULARY-AND-FACTS.md`](design/GOVERNANCE-VOCABULARY-AND-FACTS.md).

### 2.3 Structured semantic contracts

- deterministic post-generation semantic validation;
- safe repair feedback;
- typed violations/reason codes;
- cross-field and invocation-aware invariants;
- schema/runtime validation parity;
- no invalid typed value crossing the application boundary.

Detailed design: [`design/STRUCTURED-SEMANTIC-CONTRACTS.md`](design/STRUCTURED-SEMANTIC-CONTRACTS.md).

### 2.4 Typed tool invocation and obligation contracts

- provider-neutral tool invocation requirements;
- distinction between tool availability and required invocation;
- required-any / required-named semantics where adopted;
- fail-closed interaction with governance and approval;
- bounded obligation lifecycle/refinement where real-model behavior requires it;
- provider-native mapping without provider-specific semantics becoming canonical.

Detailed design:

- [`design/TOOL-INVOCATION-CONTRACTS.md`](design/TOOL-INVOCATION-CONTRACTS.md)
- [`design/TOOL-OBLIGATION-LIFECYCLE-REFINEMENT.md`](design/TOOL-OBLIGATION-LIFECYCLE-REFINEMENT.md)

### 2.5 Policy simulation, replay, contract testing, and debugger

- policy simulation / decision preview;
- deterministic governance replay where recorded evidence permits it;
- governance contract tests that execute production semantics offline;
- developer-local governance debugger;
- richer deterministic findings/incident analysis.

Forensic reconstruction itself remains a 0.7 capability. Public deterministic policy replay belongs here.

Detailed design:

- [`design/GOVERNANCE-RECONSTRUCTION-AND-REPLAY.md`](design/GOVERNANCE-RECONSTRUCTION-AND-REPLAY.md)
- [`design/ECOSYSTEM-GOVERNANCE-STRATEGY.md`](design/ECOSYSTEM-GOVERNANCE-STRATEGY.md)

### 2.6 Classification ergonomics

- richer metadata-driven classification integration where useful;
- explicit provenance and precedence;
- no content or metadata source may silently downgrade stronger classification.

Detailed design: [`design/DOCUMENT-METADATA-CLASSIFICATION.md`](design/DOCUMENT-METADATA-CLASSIFICATION.md).

### 2.7 Approval lifecycle refinement

- policy-driven approval lifetime where justified;
- exact single-use execution authority;
- safe replacement/reissue semantics;
- durable lineage and race safety;
- no reopening of terminal historical authority.

Existing 0.7 safety defects remain P0 defects if discovered; broader lifecycle productization belongs here.

Detailed design: [`design/APPROVAL-VALIDITY-REPLACEMENT-AND-LIFECYCLE.md`](design/APPROVAL-VALIDITY-REPLACEMENT-AND-LIFECYCLE.md).

---

## 3. Dependencies on 0.7

0.8 assumes 0.7 has established stable enough contracts for:

- workload/configuration identity;
- classification-before-provider-exposure;
- named trust topology and restrictive policy composition;
- authorization / viability / selection separation;
- typed governance evidence and structured reason paths;
- control-plane projection/query;
- authorized runtime control;
- authoritative persisted suspended-run cancellation and future-resume fencing;
- forensic reconstruction without side effects.

0.8 may improve authoring and analysis around those contracts but must not redefine their authority model casually.

---

## 4. Explicit non-goals

0.8 does not require:

- enterprise IdP productization, KMS/Vault/HSM breadth, Docker/Helm packaging, or CISO/operator productization owned by 0.9;
- governed raw-content learning capture, dataset export, FinOps breadth, or adaptive optimization owned by 0.10;
- a generic business-rules engine;
- a general ontology/knowledge-graph platform;
- a second workflow executor;
- a second policy engine;
- a visual workflow builder as a release blocker;
- generic YAML/JSON workflow execution semantics independent from canonical runtime definitions.

---

## 5. Release proof

0.8 should be able to demonstrate that a developer can:

```text
define a governed workload
        ↓
express canonical workflow/governance intent ergonomically
        ↓
preview/simulate a decision
        ↓
run deterministic governance contract tests
        ↓
execute through the same authoritative TramAI runtime
        ↓
inspect structured reasons/debug information
        ↓
reconstruct/replay deterministic governance meaning where evidence permits
```

The same inputs and policy semantics must not produce one meaning in simulation and another meaning in runtime merely because different code paths exist.

---

## 6. Design-note rule

Detailed documents under [`design/`](design/) preserve architecture explorations created during earlier roadmap work. Their internal historical version labels do not determine implementation ownership.

This file and `LONG-TERM-ROADMAP-0.7-0.10.md` determine 0.8 scope.
