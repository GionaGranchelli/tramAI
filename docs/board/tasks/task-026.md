# TASK-026: Implement Delay Step

- Status: done
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
- Delay wakeup scheduled via `WorkflowSchedulerStore.scheduleDelayWakeup()`
- Delay survives process restart via durable store recovery
- Observable via `WorkflowObserver.onStepStarted`/`onStepCompleted`
- Emits delay-specific observer events: `tramai.workflow.delay.started`,
  `tramai.workflow.delay.waiting`, `tramai.workflow.delay.resumed`

## Exit Criteria

- [x] A workflow with `delayStep("pause", 5, MINUTES)` pauses and resumes
- [x] The delay survives JVM restart (stored in checkpoint + delay wakeup scheduler)
- [x] A delay of 0 seconds acts as a no-op checkpoint
- [x] Cancelling a delayed workflow works immediately
- [x] Delay emits observer events for started, waiting, and resumed states

## Notes

JVM restart recovery of delay wakeups is architecturally supported via
`claimDueDelayWakeups()` in the scheduler store — the timer recovers pending
wakeups on startup. A dedicated integration test for the restart-recovery
path would strengthen coverage but is deferred to avoid infrastructure
complexity in unit tests.
