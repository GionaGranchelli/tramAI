# Board: PR #17 — Engine Approval Suspension & Resume

## Phase: Foundation (Tasks 1-3)

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| T1 | EngineExecutionIdentity + thread through engine loop | §5.2 | — | M | ✅ DONE |
| T2 | SuspendedInvocationStore SPI + InMemory impl | §5.1 | T1 | M | ✅ DONE |
| T3 | Add evaluate() to PolicyEnforcementHelper | §5.3 | — | S | ✅ DONE |

## Phase: Suspension (Tasks 4 + 6 + 7)

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| T4 | Replace ApprovalRequiredException with real suspension in executeTool() | §5.4-5.5 | T1, T2, T3 | L | ✅ DONE |
| T6 | Lifecycle audit events | §6 | T4 | S | ✅ DONE |
| T7 | Workflow digest helper | §5.2 | — | S | ✅ DONE |

## Phase: Resume (Task 5)

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| T5 | Add resumeApproval() to TramaiEngine | §5.7-5.8 | T4 | L | ✅ DONE |

## Phase: Tests (Tasks 8-10)

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| T8 | Suspension happy-path tests | — | T4 | M | ✅ DONE |
| T9 | Resume happy-path tests | — | T5 | M | ✅ DONE |
| T10 | Edge cases: uncertain outcomes, partial failure, expiry | — | T5 | M | ✅ DONE |

## Execution Order

```
T1 → T2 → T3 → T4 → T6 → T7 (parallel with T4)
                ↓
                T5
                ↓
          T8 → T9 → T10
```

## Quick Start

Implement in this order:
1. T1 (EngineExecutionIdentity) + T3 (evaluate)
2. T2 (SuspendedInvocationStore)
3. T7 (workflow digest)
4. T4 (suspension flow)
5. T6 (audit events)
6. T5 (resume)
7. T8-T10 (tests)

## Progress

- Phase Foundation: 3/3
- Phase Suspension: 3/3
- Phase Resume: 1/1
- Phase Tests: 3/3
- Total: 10/10

## Final Status (Fix Round 4 — June 2026)

All 10 tasks are complete. The following 5 focused correctness fixes were applied in the final round:

### Fix 1: Move revealSensitiveContext() inside post-claim uncertain-outcome boundary
Moved `revealSensitiveContext()` and `revealForResume()` from before the try/catch block to inside it (after `claimForExecution()`), so that any failure during reveal is caught by the universal uncertain-outcome handler and the continuation remains CLAIMED.

### Fix 2: Centralize nested approval handling
Updated the `NestedApprovalNotSupportedException` catch in `resumeApprovalInternal()` to emit `onUncertainOutcome` with the parent approval ID before rethrowing. Previously it silently rethrew without audit.

### Fix 3: Restore structured-output ordering parity
Moved `BEFORE_RESPONSE_RETURN` enforcement for `ReturnKind.STRUCTURED` from `finalizeResumedOperation()` (where it ran for all return kinds before dispatch) into `resumeStructuredResult()` where it runs **only on parse success**. Parse failure no longer trips `BEFORE_RESPONSE_RETURN`, and invalid data is never persisted to memory.

### Fix 4: Regression tests
Added 6 new tests across two test files:
- `ApprovalEngineEdgeCaseTest`: missing sensitive context after claim emits uncertain outcome once
- `ApprovalResumeEngineTest`: later provider-turn nested approval fails closed without child state, nested approval exception uses parent approval ID, structured parse failure does not persist invalid memory, conversation memory survives suspension and resume, resumed Unit operation returns Unit

### Fix 5: Task board update
This file.

✅ DONE | 🔄 IN PROGRESS | ❌ BLOCKED
