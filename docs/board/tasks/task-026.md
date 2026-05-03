# TASK-026: Implement Delay Step

- Status: planned
- Priority: high
- Primary spec: [SPEC-013](../../specs/spec-013-scheduler.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Add a `delayStep` to the workflow DSL that pauses execution for a configurable
duration, then resumes from the next step.

## Scope

- Add `delayStep(name, duration, unit)` to `AbstractWorkflowBuilder`
- Delay is implemented as a checkpoint that stores the resume-at timestamp
- On resume, check if the delay has elapsed; if not, re-checkpoint and yield
- Observable via `WorkflowObserver.onStepStarted`/`onStepCompleted`

## Exit Criteria

- [ ] A workflow with `delayStep("pause", 5, MINUTES)` pauses and resumes
- [ ] The delay survives JVM restart (stored in checkpoint)
- [ ] A delay of 0 seconds acts as a no-op checkpoint
- [ ] Cancelling a delayed workflow works immediately
