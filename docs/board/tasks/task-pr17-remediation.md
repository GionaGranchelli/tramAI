# PR #17 Remediation Board

## P0 — Must fix before merge

| ID | Task | Effort | Status |
|----|------|--------|--------|
| R1 | Resume the original workflow instead of returning ToolResult | XL | ✅ DONE |
| R2 | Remove raw arguments from SuspendedInvocation (wrap in sensitive type) | L | ✅ DONE |
| R3 | Validate policy-provided approval binding (tool name, digest, timeout) | M | ✅ DONE |
| R4 | Fix denied resume outcome (cancel continuation, scrub payload) | M | ✅ DONE |
| R5 | Fix recursive resume-policy RequireApproval (fail closed, use NestedApprovalNotSupportedException) | M | ✅ DONE |
| R6 | Add compensation for suspension partial failures (cancel continuation on store failure) | M | ✅ DONE |

## P1 — Should fix

| ID | Task | Effort | Status |
|----|------|--------|--------|
| R7 | Wrap entire post-claim region as uncertain outcome handling | M | ✅ DONE |
| R8 | Revalidate claimed payload integrity (re-hash after claim) | S | ✅ DONE |
| R9 | Expand workflow digest binding (include tool security metadata) | S | 🔴 DEFERRED |
| R10 | Add AuditApprovalLifecycleAuditEmitter adapter | M | 🔴 DEFERRED |
| R11 | Wire approval flow through standalone and Spring composition | M | 🔴 DEFERRED |
| R12 | Replace single mutable resumeHandler with approval-ID-keyed registry | M | 🔴 DEFERRED |

## Execution Order

```
R3 → R2 → R6 → R4 → R5 → R1 (the big one)
                ↓
          R7 → R8 → R9 → R10 → R11 → R12
```

## Progress

- P0: 6/6 ✅
- P1: 2/6 ✅ (R7, R8) | 4/6 🔴 DEFERRED (R9-R12)

## Implementation Note

R9-R12 are deferred to a follow-up PR because they cover:
- Workflow digest expansion (tool security metadata binding) — requires spec alignment
- Standalone/Spring wiring — wiring infrastructure changes, separate from engine logic
- Registry-based resume handler — cleanup/enhancement, not a correctness fix
- Audit adapter — observability layer, not engine correctness
