# TramAI 0.7.0 — Epic/Task Spec Contract

Use this structure for new Epic specs and for substantial candidate specs.

## Required Epic sections

1. **Executive decision** — one paragraph describing the architectural/product decision.
2. **Problem** — concrete current limitation, not future aspiration.
3. **Operator/user outcome** — what becomes possible after completion.
4. **Baseline** — current modules/types/stores/evidence and known constraints; audit before invention.
5. **Scope** — required behavior.
6. **Non-goals** — explicit later-release or unrelated work.
7. **Authority boundaries** — source of truth, mutation path, projection/telemetry role.
8. **Invariants** — concise machine-testable semantic rules.
9. **Architecture/contracts** — types, commands, events, query/read models, persistence and module direction as applicable.
10. **Failure semantics** — unknown/missing/stale/duplicate/concurrent/restart behavior.
11. **Security & privacy** — authorization, payload exposure, secret handling, audit attribution.
12. **Compatibility** — API/TCK/module/backward-compatibility expectations and later-release seams.
13. **Acceptance criteria** — deterministic completion rules.
14. **Adversarial proof** — plausible incorrect implementations that tests must reject.
15. **Mutation expectations** — high-value semantic mutations to kill or justify.
16. **Dependencies** — HARD/SOFT/NONE with exact contracts consumed.
17. **Task decomposition** — independently reviewable candidates.
18. **Documentation/evidence** — artifacts that must be updated/generated.

## Required task/candidate fields

```text
ID
Parent Epic
Objective
Derivation / evidence for why this task exists
Files/modules expected to change
Files/modules explicitly out of scope
Contract/invariant affected
Implementation boundary
Focused verification
Adversarial discriminator
Mutation expectation (or N/A with reason)
Integration dependency
Definition of done
```

## Quality rule

A task may refine implementation details but may not silently widen its parent Epic. If implementation proves the Epic spec wrong or incomplete, amend/review the Epic spec before continuing.

## Evidence rule

Prefer typed, deterministic evidence over console text. High-value gates should fail closed when evidence is unavailable rather than reporting false success.
