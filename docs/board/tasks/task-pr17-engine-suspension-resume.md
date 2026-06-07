# Board: PR #17 — Engine Approval Suspension & Resume

## Phase: Foundation (Tasks 1-3)

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| T1 | EngineExecutionIdentity + thread through engine loop | §5.2 | — | M | ⬜ TODO |
| T2 | SuspendedInvocationStore SPI + InMemory impl | §5.1 | T1 | M | ⬜ TODO |
| T3 | Add evaluate() to PolicyEnforcementHelper | §5.3 | — | S | ⬜ TODO |

## Phase: Suspension (Tasks 4 + 6 + 7)

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| T4 | Replace ApprovalRequiredException with real suspension in executeTool() | §5.4-5.5 | T1, T2, T3 | L | ⬜ TODO |
| T6 | Lifecycle audit events | §6 | T4 | S | ⬜ TODO |
| T7 | Workflow digest helper | §5.2 | — | S | ⬜ TODO |

## Phase: Resume (Task 5)

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| T5 | Add resumeApproval() to TramaiEngine | §5.7-5.8 | T4 | L | ⬜ TODO |

## Phase: Tests (Tasks 8-10)

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| T8 | Suspension happy-path tests | — | T4 | M | ⬜ TODO |
| T9 | Resume happy-path tests | — | T5 | M | ⬜ TODO |
| T10 | Edge cases: uncertain outcomes, partial failure, expiry | — | T5 | M | ⬜ TODO |

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

- Phase Foundation: 0/3
- Phase Suspension: 0/3
- Phase Resume: 0/1
- Phase Tests: 0/3
- Total: 0/10

⬜ TODO | 🔄 IN PROGRESS | ✅ DONE | ❌ BLOCKED
