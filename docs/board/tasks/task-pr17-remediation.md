# PR #17 Remediation Board

## P0 — Must fix before merge

| ID | Task | Effort | Status |
|----|------|--------|--------|
| R1 | Resume the original workflow instead of returning ToolResult | XL | ⬜ TODO |
| R2 | Remove raw arguments from SuspendedInvocation (wrap in sensitive type) | L | ⬜ TODO |
| R3 | Validate policy-provided approval binding (tool name, digest, timeout) | M | ⬜ TODO |
| R4 | Fix denied resume outcome (cancel continuation, scrub payload) | M | ⬜ TODO |
| R5 | Fix recursive resume-policy RequireApproval (fail closed, no fabricated exception) | M | ⬜ TODO |
| R6 | Add compensation for suspension partial failures (cancel continuation on store failure) | M | ⬜ TODO |

## P1 — Should fix

| ID | Task | Effort | Status |
|----|------|--------|--------|
| R7 | Wrap entire post-claim region as uncertain outcome handling | M | ⬜ TODO |
| R8 | Revalidate claimed payload integrity (re-hash after claim) | S | ⬜ TODO |
| R9 | Expand workflow digest binding (include tool security metadata) | S | ⬜ TODO |
| R10 | Add AuditApprovalLifecycleAuditEmitter adapter | M | ⬜ TODO |
| R11 | Wire approval flow through standalone and Spring composition | M | ⬜ TODO |
| R12 | Replace single mutable resumeHandler with approval-ID-keyed registry | M | ⬜ TODO |

## Execution Order

```
R3 → R2 → R6 → R4 → R5 → R1 (the big one)
                ↓
          R7 → R8 → R9 → R10 → R11 → R12
```

## Progress

- P0: 0/6
- P1: 0/6
