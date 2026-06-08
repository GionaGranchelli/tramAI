# PR #19 — Stale Claimed-Continuation Recovery

## Summary

Add explicit, privileged, auditable recovery for stale CLAIMED continuations where the execution outcome may be uncertain.

## Changes

### Domain
- **CANCELLED_UNCERTAIN** — new terminal status for continuations where TramAI cannot prove whether the external side effect occurred
- **Recovery metadata** — `recoveryResolvedBy`, `recoveryResolvedAt`, `recoveryReasonCode` on `ApprovalContinuation`

### Store SPI (read-only)
- `findStaleClaimed(claimedBefore, limit)` — returns CLAIMED continuations with `claimedAt <= claimedBefore`, metadata-only, deterministic ordering
- `forceCancelClaimed(approvalId, expectedVersion, cancelledBy, reasonCode)` — privileged CLAIMED → CANCELLED_UNCERTAIN transition

### Recovery coordinator
- `ApprovalRecoveryCoordinator` SPI with safe exception mapping
- `InMemoryApprovalRecoveryCoordinator` — audit emission, fail-closed on privileged mutations

### Lifecycle audit
- `onStaleClaimDetected()` — best-effort detection emission
- `onClaimedContinuationForceCancelled()` — fail-closed emission for privileged mutation

### Idempotency key foundation
- `IdempotencyKeyUtil.deriveApprovalKey()` — deterministic `sha256(approvalId:toolCallId:digest)`
- `ToolExecutionContext.idempotencyKey` — populated for resumed approval-gated calls, null otherwise

### Design rules
- No automatic CLAIMED → PENDING reset
- No automatic retries or reclaim
- No scrubbed argument restoration
- Version-based optimistic concurrency
- Synchronous fail-closed audit for privileged mutations

### Tests (20+ regression tests)
- Store: filtering, ordering, limit validation, metadata-only, transitions, version fence, concurrent winner
- Coordinator: audit emission, safe metadata, exception leak prevention
- Idempotency: determinism, differential, raw argument absence

## Verification
- 118 tests, BUILD SUCCESSFUL
- publishToMavenLocal: 196 tasks
- SpringBoot example: 28 tasks

## Review Pipeline
| Round | Reviewer | Verdict | Findings |
|-------|----------|---------|----------|
| 1 | agy | APPROVED WITH MINOR CHANGES | 3 P1 → fixed |
| 2 | codex | APPROVED WITH MINOR CHANGES | 0 P0, 0 P1 |
| 3 | agy | APPROVED WITH MINOR CHANGES | 0 P0, 0 P1 |
| 4 | codex | MERGE-READY | 0 P0, 0 P1, 0 P2 |
