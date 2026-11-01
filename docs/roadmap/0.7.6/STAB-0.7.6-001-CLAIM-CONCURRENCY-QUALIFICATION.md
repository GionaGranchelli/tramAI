# STAB-0.7.6-001 — Audit-outbox claim concurrency qualification (Issue #568)

Status: **characterized and fixed at the ordering level**; frequency delta **unmeasured**.
Base: `release/0.7.0` at `d83d38f6`. Work: PR #572.

## 1. What the code does

`claimPending` has two paths:

1. `selectClaimableForUpdateSkipLocked` — one statement, `FOR UPDATE SKIP LOCKED`, ordered.
2. `selectClaimableSerialized` — only reached when path 1 returns **zero** rows
   (`JdbcSovereignOpsAuditOutboxStore`, `claimPending`). It takes a *non-locking* candidate probe,
   then locks candidates **one at a time**, in the order the probe returned them.

Two properties matter, and one of them refutes part of the issue's hypothesis:

- The fallback **can retain a lock on one candidate while acquiring the next**: if a candidate is no
  longer dispatchable after it is locked, the loop moves on and the first row lock is held until the
  transaction ends. Hold-and-wait inside one claim is therefore possible, and the correctness of the
  claim path does not depend on it being impossible.
- Path 1 returned nothing, so the transaction holds no outbox row lock *when it starts* the fallback.
  It acquires them one at a time as it walks the candidate list.

The reachable post-lock divergences are guarded rather than skipped: a row whose column state and
payload disagree fails closed (`audit-outbox-column-status-mismatch`) instead of being claimed, and
that failure releases the lock it had taken.

Because candidates are acquired in sequence, the **order in which they are handed to the loop** is the
property that has to be deterministic.

## 2. Root cause: `ORDER BY created_at` is not a total order

`audit_outbox.created_at` is not unique (`V1__sovereign_persistence.sql`), and the insert path writes
it explicitly from `record.createdAt` — the column default `now()` is not the mechanism. Ties come
from callers supplying the same instant: one decision writing several outbox rows from a single clock
reading, a reconstructed timestamp, a coarse or fixed clock, or a batch whose records were built
together. Those are normal writes, so ties are not an edge case.

All claim and scan queries ordered by `created_at` alone. Tied rows come back in whatever order the
scan yields, and that order is not stable across statements. Two claimers can therefore receive
overlapping candidate sets in different orders and acquire row locks in different orders — a
lock-order cycle, reachable when the fallback engages under contention, which is why such a failure
would be intermittent.

## 3. Inherited, amplified, or introduced?

**Inherited.** At `69ce832d` (pre-quarantine-predicate, PR #566) the same non-total ordering exists in
four sites:

```
$ git grep -n 'ORDER BY created_at' 69ce832d -- .../JdbcSovereignOpsAuditOutboxStore.kt
645:  ... WHERE status = ? ORDER BY created_at ASC LIMIT ?
672:  ORDER BY created_at ASC
749:  ORDER BY created_at ASC
788:  ORDER BY created_at ASC
```

The defect predates Slice 3 and is not attributable to the quarantine predicate.

**Amplification: plausible, unmeasured.** Slice 3 added `quarantineUndecodableEvidence`, a second
locking scan over the same table. That raises the number of transactions contending on `audit_outbox`
rows, so it can increase exposure to the cycle, but nothing in this qualification measures a
frequency change. Recorded as a limit, not a finding.

## 4. Fix

Total order `(created_at, outbox_id)` on every claim and quarantine-scan query. Eligibility is
unchanged; only the freedom to visit tied rows in different orders is removed. The blocking fallback is
untouched — its liveness role is exactly why deleting it was not an option.

## 5. Evidence

**Deterministic discriminator** — `claim order is total when every row shares a timestamp`
(`JdbcClaimDeadlockStressTest`): rows seeded back to front with identical `createdAt` claim as
`row-001 .. row-012`. Removing `, outbox_id` returns them in heap order (descending) and **kills the
test**, which proves the order was previously unconstrained.

**Stale-candidate probe race** — `a candidate whose stored state diverges after the probe fails
closed`: a holder locks every row so the fast path finds nothing; while the claimer is blocked in the
fallback, the holder emits the row that was probed; the claimer then locks it, detects that column and
payload disagree, fails closed with `audit-outbox-column-status-mismatch`, leaves `attempt_count` at 0,
and releases the lock (`FOR UPDATE NOWAIT` succeeds afterwards). The wait is synchronised on
`pg_blocking_pids`, not a sleep, and times out with a diagnostic if the block never establishes.

Limitation: this pins the divergence guard. It does **not** prove that a *legal* terminal transition
racing the fallback makes the loop retain the first lock while acquiring the second — that
interleaving is not reproduced here, and the argument for it is code inspection plus the fact that
acquisition order is now total.

**Contention harness** — PostgreSQL 17, `deadlock_timeout=200ms`, 20 rounds each:

Each scenario **asserts** rather than prints: zero 40P01, only the named benign key permitted in the
SQLSTATE map, a full batch claimed per round, unique ownership, and a bounded run (the suite finishes
in ~19s).

| Scenario | Ops | 40P01 | Other SQLSTATEs | Asserted invariants |
| --- | --- | --- | --- | --- |
| Claimers only (8 × batch 12, ties) | 240 claims | 0 | none | every round claims all 12 rows exactly once; every row owned by its own worker |
| Claimers + 4 terminal transitions | 240 claims + 301 lost races | 0 | none (only `benign:IllegalArgumentException`) | lost races never surface as SQLSTATEs |
| Claimers + quarantine scan (undecodable rows) | 240 claims + 120 scans | 0 | none (`IllegalStateException` decode failures only) | quarantine is non-empty, ciphertext byte-identical to a **pre-run snapshot**, outbox payload unchanged, no rows lost |

## 6. Limits — what this does not establish

1. **40P01 was never reproduced**, before or after the fix. The harness qualifies behaviour; it does
   not measure a deadlock frequency, and no before/after delta is claimed.
2. **No cross-commit runtime comparison.** A reduced harness that compiles at both `69ce832d` and
   `d83d38f6` is a rewrite, not a reduction: the outbox record's constructor and the store's surface
   differ (no quarantine store, different record fields). Inheritance is established statically
   (§3) instead. Since neither commit reproduced 40P01, a runtime comparison would report `0 vs 0`.
3. **Other cycle sources are unenforced.** Any two statements that lock `audit_outbox` rows in
   different orders can still deadlock. The store opens and closes its own transaction per call, so
   a multi-call transaction should not exist today — nothing prevents one. The cheap next step, if
   the owner wants it closed, is a test asserting no store method leaves a transaction open.
4. **Retry was deliberately not added.** A bounded 40P01 retry would mask a lock-order defect that has
   a deterministic fix, and the issue ranks lock ordering ahead of retry.

## 7. Status of Issue #568

**Issue #568 stays open as an investigation of the original deadlock.** This PR is an ordering
hardening with its own regression proof; it does not demonstrate that ordering caused the observed
40P01, because the failure was never reproduced before the fix. Closing it would overstate the
evidence.

## 8. Next steps

- 0.7.6g adversarial qualification (next authorized track).
- If further 40P01 evidence appears, capture the server-side deadlock report (both processes and both
  statements) and add the counterpart transaction to the harness. That is the only way to identify a
  second cycle class.
