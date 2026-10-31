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

- The fallback locks the **first** candidate it acquires and returns. `isDispatchable()` is
  unconditionally `true` for `PENDING`/`FAILED_RETRYABLE`, so the loop does not accumulate a second
  row lock while holding the first.
- Path 1 returned nothing, so the transaction holds **no** outbox row lock when the fallback blocks.

Therefore two `claimPending` transactions cannot be both legs of a lock cycle, and the fallback's
lock ordering within a single claim cannot deadlock on its own. The remaining degree of freedom is the
**order in which candidates are handed to the loop**.

## 2. Root cause: `ORDER BY created_at` is not a total order

`audit_outbox.created_at` is `TIMESTAMPTZ NOT NULL DEFAULT now()` and is not unique
(`V1__sovereign_persistence.sql`). `now()` is **transaction time**, so every row written by one
transaction carries an identical timestamp. Ties are the normal case, not an edge case.

All claim and scan queries ordered by `created_at` alone. Tied rows come back in whatever order the
scan yields, and that order is not stable across statements. Two claimers can therefore receive
overlapping candidate sets in different orders and acquire row locks in different orders — a
lock-order cycle, reachable only under contention with the fallback engaged, which is why the failure
is intermittent by construction.

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
(`JdbcClaimDeadlockStressTest`): rows seeded back to front with identical timestamps claim as
`row-001 .. row-012`. Removing `, outbox_id` returns them in heap order (descending) and **kills the
test**, which proves both that ties are real in this schema and that the order was previously
unconstrained.

**Contention harness** — PostgreSQL 17, `deadlock_timeout=200ms`, 20 rounds each:

| Scenario | Ops | 40P01 | Other SQLSTATEs | Invariants |
| --- | --- | --- | --- | --- |
| Claimers only (8 × batch 12, ties) | 240 claims | 0 | 0 | no duplicate claims; every row owned by its own worker |
| Claimers + 4 terminal transitions | 240 claims + 300 lost races | 0 | 0 | lost races surface as benign `IllegalArgumentException` |
| Claimers + quarantine scan (undecodable rows) | 240 claims + 120 scans | 0 | 0 | no rows lost; quarantined ciphertext still readable |

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

## 7. Next steps

- 0.7.6g adversarial qualification (next authorized track).
- If further 40P01 evidence appears, capture the server-side deadlock report (both processes and both
  statements) and add the counterpart transaction to the harness. That is the only way to identify a
  second cycle class.
