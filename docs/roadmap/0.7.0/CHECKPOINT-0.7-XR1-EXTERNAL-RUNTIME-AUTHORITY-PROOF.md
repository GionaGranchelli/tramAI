# Checkpoint 0.7-XR1 — External Runtime Authority Proof

**Status:** ⚪ Planned  
**Placement:** after 0.7.3 decision semantics; before 0.7.4 evidence/projection contracts are frozen  
**Reference runtime:** Spring AI  
**Change class:** architecture/release proof

## Executive decision

Prove that TramAI can authoritatively govern a declared execution boundary initiated by a **non-TramAI-authored JVM AI workload** without requiring the workload to be rewritten as a TramAI workflow and without introducing a second policy model.

This is a narrow architecture proof. It is not a productionized framework-adapter programme.

## Required flow

```text
Spring AI workload
        ↓
tool/action intent
        ↓
TramAI workload + configuration + run correlation
        ↓
authoritative TramAI governance evaluator
        ↓
ALLOW / DENY / REQUIRE_APPROVAL
        ↓
execute / reject / governed suspension
        ↓
correlated outcome + evidence
```

The exact Spring AI integration mechanism is implementation-specific and may remain experimental.

## Core invariants

```text
external runtime != governance authority
governed workload != TramAI-authored workflow
DENY => no execution at the declared boundary
REQUIRE_APPROVAL => no execution before authoritative approval
adapter failure in authoritative mode => no silent fail-open
observed != enforced
projection/query state cannot authorize execution
```

## Required proof

1. **ALLOW** — an allowed action executes exactly through the declared governed boundary and the outcome correlates to the TramAI decision.
2. **DENY** — a denied action does not execute.
3. **REQUIRE_APPROVAL** — the action cannot execute before approval; after valid approval the continuation preserves workload/run/decision identity.
4. **Identity continuity** — workload, configuration/version, run, policy/decision and correlation identity survive the external-runtime boundary.
5. **Failure semantics** — loss/unavailability of the governance call does not silently become permission where the integration declares authoritative enforcement.
6. **No second policy model** — Spring-specific code translates execution intent but does not recreate TramAI authorization or approval semantics.
7. **Evidence provenance** — emitted evidence identifies the external runtime/integration and represents only the enforcement strength TramAI can prove.
8. **No projection bypass** — the adapter cannot authorize by writing directly to control-plane read models, evidence stores or UI state.

## Enforcement-strength requirement

The reference proof must be **authoritative at the specific declared boundary**: TramAI's denial must be able to prevent that operation.

0.7 may introduce an internal/preview representation capable of distinguishing concepts equivalent to:

```text
AUTHORITATIVE
INSTRUMENTED
OBSERVED
IMPORTED
```

Exact names and public API stability are deferred to the 0.7.4 evidence/projection work.

The integration must never imply that governing one Spring AI boundary proves every possible execution path in the host application is governed.

## Non-goals

- production-quality Spring Boot starter or five-minute adoption flow;
- broad Spring AI feature coverage;
- Koog, LangChain4j, Embabel, ADK or other adapter implementation;
- stable public adapter SPI;
- automatic framework discovery;
- policy simulation/decision preview;
- governance contract-testing SDK;
- Dashboard-specific policy behavior;
- MCP/A2A productization.

Those belong to later roadmap work unless a narrow seam is required to complete this proof safely.

## Exit

XR1 is complete when a non-TramAI-authored Spring AI workload demonstrates the same authoritative TramAI governance meaning used by native execution, and the findings are sufficient for 0.7.4 to finalize evidence authority/provenance without assuming TramAI owns the agent runtime.

If the proof requires a second policy model or reveals that core workload/decision semantics are inherently TramAI-workflow-specific, stop and report the architectural mismatch before 0.7.4 contracts are frozen.
