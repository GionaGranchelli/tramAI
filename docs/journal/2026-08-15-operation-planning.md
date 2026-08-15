# Implementation Status — 2026-08-15

## What's Been Implemented

**PR #232 — `refactor(engine): extract operation planning`** (Phase 3 / Epic 3.2: Extract operation planning)

- New `dev.tramai.engine.planning` package with 4 internal components:
  - `ServiceDefinitionCompiler` — service validation, @SystemPrompt extraction, method enumeration
  - `OperationDefinitionCompiler` — tool resolution + plan construction (delegates to public `OperationDefinition.create`)
  - `OperationExecutionPlan` — immutable plan (definition + fingerprint + service/method identity)
  - `OperationFingerprintFactory` — canonical cache fingerprint, byte-identical to pre-extraction
- `create()` / `registerService()` share one engine-scoped compiler (no duplicated `ServiceDefinition.create`)
- `ServiceDefinition.operations` → `Map<Method, OperationExecutionPlan>`; handler resolves plan, consumes `plan.definition`
- 23 new tests: compiler suites (Kotlin + Java interop fixture `JavaPlanningService.java`), fingerprint determinism + byte-identity vs legacy, repeated-compilation plan equality, engine wiring
- 20 PR #230 characterization traces byte-identical; `OperationDefinition` public API unchanged (`:tramai-engine:apiCheck` clean)
- Roadmap: Epic 3.2 → ✅ Complete (PR #232)

## What's Missing / Blocked

- Task 3 of Epic 3.2 ("cache safe reusable metadata") deliberately deferred — no process-global reflection cache per 0.6.0 goals; revisit after benchmark evidence
- Plan `fingerprint` field is compile-time metadata; threading it into runtime cache-key construction deferred to Epic 3.3 (reviewer P3)
- Next: Epic 3.3 — Extract provider execution (`ProviderExecutionCoordinator`, `ProviderAttemptExecutor`, `ProviderRetryPolicy`, `ProviderFallbackPolicy`, `ProviderAuthorizationService`)

## Current State

- Branch `refactor/0.6.0-operation-planning` pushed; PR #232 open: https://github.com/GionaGranchelli/tramAI/pull/232
- Local gates green: engine suite (2m14s), characterization ×3 (20/20), `:tramai-engine:apiCheck`, `verifyCancellationSafety`, `verifyPr -PchangeClass=runtime-behaviour`
- Review (delegate_task subagent): APPROVE — no P1/P2, 4 P3 (2 addressed: repeated-compilation equality test + legacy-snapshot comment; 2 deferred to Epic 3.3)
- Known pre-existing (not this PR): `examples/governed-workflow` apiCheck fails on master itself
