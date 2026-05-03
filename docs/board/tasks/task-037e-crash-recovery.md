# TASK-037E: Crash Recovery and Idempotency Policy

- Status: planned
- Priority: high
- Primary spec: [SPEC-016](../../specs/spec-016-distributed-execution.md)
- Parent task: [TASK-037](../tasks/task-037.md)
- Last updated: 2026-05-03

## Purpose

Define the model for recovering workflows that crashed mid-step and enforce re-execution policies based on each step's declared idempotency level, preventing data corruption on resume.

## Scope

- Started-but-not-checkpointed step model: detect steps recorded as "started" in the step attempt table but without a corresponding completed checkpoint
- Step attempt records: table or column tracking `stepId`, `attemptNumber`, `status (STARTED / COMPLETED / FAILED)`, `startedAt`, `workerId`
- Re-execution policy levels on `@Step` annotation or equivalent:
  - `PURE`: safe to re-execute unconditionally — no side effects
  - `IDEMPOTENT`: may have side effects but re-execution produces the same result
  - `EXTERNALLY_IDEMPOTENT`: requires an idempotency key from the step output to safely deduplicate
  - `NON_REPLAYABLE`: must NOT be re-executed — fail the workflow with a clear error on resume
- Recovery flow on lease takeover: query step attempt records for the last step → determine its re-execution policy → either resume, re-execute, or fail the workflow
- `NON_REPLAYABLE` steps produce a `NonReplayableStepException` with step name and workflow run ID in the message

## Exit Criteria

- [ ] Step attempt table records STARTED / COMPLETED / FAILED per step and attempt
- [ ] Recovery correctly identifies the last uncompleted step
- [ ] `PURE` steps are re-executed on resume without error
- [ ] `IDEMPOTENT` steps are re-executed on resume
- [ ] `EXTERNALLY_IDEMPOTENT` steps require an idempotency key
- [ ] `NON_REPLAYABLE` steps cause a `NonReplayableStepException` with context
