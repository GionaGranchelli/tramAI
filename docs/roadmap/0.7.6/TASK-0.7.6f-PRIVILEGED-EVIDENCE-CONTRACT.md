# 0.7.6f — Privileged Evidence & Durable Attribution Provenance

**Status:** Contract for review, revision 2. No implementation until accepted.
**Base:** `release/0.7.0` at `d4bde521` (0.7.6e delivered as `440d63fc`).
**Scope:** attribution and evidence for privileged approval decisions and their authorization refusals.

## 1. Why this task exists

0.7.6e made the decision path authorization-safe. It did not make a decision *reconstructable* from durable
evidence afterwards, and it records nothing at all when a decision is **refused**.

1. **Attribution loses the issuer.** `SovereignOpsApprovalDecisionControlPlane` passes `actor = authority.subject`;
   `JdbcSovereignOpsApprovalMutationStore` stores `decision_actor_hash = sha256(actor)`. `OperatorAuthority` carries
   `(issuer, subject)` and documents that the issuer is kept so two issuers can be distinguished — and durable
   state drops it. `sub` is unique only within an issuer.
2. **Refusals leave no trace.** `ApprovalDecisionResult.Unauthorized` returns to the caller and writes nothing: no
   outbox record, no audit event, no mutation.

Two further defects were found while writing this revision. Both are in shipping code and both are in scope,
because the evidence this task adds would otherwise reproduce them:

3. **Approvals are audited as denials.** `AuditEngineSovereignOpsAuditEmitter.approvalDeniedFromOutbox` hardcodes
   `enforcementPoint = "sovereign-ops.approval.deny"`, `decision = "DENIED"`, `reasonCode = "sovereign-ops-admin-denial"`
   regardless of `record.approvalStatus`, and `SovereignOpsAuditOutboxDispatcher` calls it for **every** claimed
   record. A successful approval therefore reaches the audit engine as a denial.
4. **Event keys embed the raw approval id.** `eventKeyFor` produces `"approval-approved.<approvalId>"` /
   `"approval-denied.<approvalId>"`, and `eventKey` is a durable indexed column — contradicting
   `SovereignOpsAuditOutboxRecord`'s stated invariant "Never stores raw approval ID (only `aggregateIdDigest`)".
   `operation` is also set to this same string, so it is not a semantic operation name.

## 2. What is already durable (inventory, verified against the merged source)

| Evidence | Where | Carries today |
| --- | --- | --- |
| Approval decision | `approvals.decision_actor_hash`, `decision_type`, `status`, `version`, `decided_at` | subject digest, decision, resulting status, version |
| Decision audit intent | `SovereignOpsAuditOutboxRecord`: `aggregateIdDigest`, `operation`, `eventKey`, `actor`, `workflowRunId`, `correlationId`, `approvalStatus` (non-null), `approvalVersion`, `reasonDigest`, `reasonLength` | actor **as subject text**, resulting status and version — but **no previous status/version** |
| Outbox store contract | `SovereignOpsAuditOutboxStore.append` accepts **PREPARED only**, rejects duplicate `outboxId`/`eventKey`, exposes `findByEventKey`, `get`, `listByStatus`, `listExpiredEmitting` | — |
| Store conformance | `SovereignOpsAuditOutboxStoreTck` (`tramai-testing`) — including *every non-PREPARED initial status rejected*, *duplicate eventKey rejected with fixed code*, *duplicate rejection leaves no orphan* | — |
| Implementations | `InMemory…`, `Jdbc…`, `File…` outbox stores (plus governed wrappers) | — |
| Refusals | — | **nothing** |

Outbox-first semantics for a successful mutation already hold in both mutation stores: append `PREPARED`,
transition, then `PENDING`; a failed transition marks the intent `FAILED_PERMANENT`. R4 preserves that; it does not
replace it.

## 3. Requirements

**R1 — Durable identity is `(issuer, subject)`, sourced only from `OperatorAuthority`.** No caller-supplied actor
string reaches durable state. The issuer is persisted beside the existing subject digest, and one identity digest
binds both.

**R2 — A completed decision is reconstructable from durable evidence alone.** For each successful approve/deny the
durable set answers: operation, actor `(issuer, subject)`, authorization outcome, **previous status and version as
recorded at decision time**, resulting status and version, workflow/run correlation, and the audit correlation that
ties the outbox record to the emitted event. Previous state is *recorded evidence*, not derived from the final
approval row.

**R3 — Refusals are durable and are not mutations.** A refused decision writes evidence recording the operation, the
actor `(issuer, subject)`, the refusal reason from the closed `ApprovalUnauthorizedReason` set, the policy version
consulted, the caller's `expectedVersion`, and the approval correlation digest — **without** creating or modifying an
approval row, without changing `approvals.version`, and without emitting an event a consumer could read as a
decision.

**R4 — The mutation/intent transactional relationship is preserved** in both mutation stores, and the one new write
path must not disturb the ordering or add a second dispatch mechanism.

**R5 — The result is proven**, not asserted: adversarial, restart/reconstruction and evidence-failure tests.

## 4. Decision A — durable `(issuer, subject)`

| Option | Verdict |
| --- | --- |
| Concatenate into the existing actor string | Rejected: needs an escaping rule, invites ambiguous parses, silently changes a column every consumer reads. |
| Rehash `decision_actor_hash` over both fields | Rejected: silent reinterpretation. Old and new rows would look identical yet be incomparable, and no backfill is possible because only the digest survives. |
| Store the issuer in `sanitized_metadata` JSON | Rejected: unqueryable, unenforceable as evidence. |
| **Additive columns: issuer + a versioned digest that binds both** | **Chosen** |

**Decision.** Two nullable columns on `approvals`: `decision_actor_issuer TEXT` and
`decision_actor_identity_digest TEXT`; the outbox record and audit payload gain `actorIssuer` and
`actorIdentityDigest`. `decision_actor_hash` is **frozen** at `sha256(subject)` — not recomputed, no row rewritten.

**Exact digest encoding** (no asserted delimiter restriction; length-prefixing makes the encoding injective for any
issuer or subject bytes):

```
identityDigest = SHA-256( "tramai/actor-identity" ‖ 0x00 ‖ u32be(1) ‖ lp(issuer) ‖ lp(subject) )
lp(x)          = u32be(len(x)) ‖ x        // x as UTF-8, length in bytes
```

The domain string and the integer version are both part of the input, so a future v2 encoding cannot collide with
v1 and neither can be confused with an unrelated digest that happens to hash similar text. The same construction with
its own domain string is used for refusal event keys (§5.2), so the two can never be equal.

**Legacy rows.** `NULL` issuer means "recorded before 0.7.6f; issuer unknown". It must never be read as the current
issuer, and no consumer may treat a legacy row as verified provenance.

**Migration.** Additive `V10` DDL: two nullable columns, no backfill, no constraint that legacy rows could violate,
no new unique index (duplicate decisions are prevented by the version fence, not a digest index).

## 5. Decision B — refusal evidence with no mutation transaction

| Option | Verdict |
| --- | --- |
| Log or metric only | Rejected: not durable, not reconstructable. |
| Direct audit emission, bypassing the outbox | Rejected: second audit path with its own failure semantics, and the existing dispatch guarantees would not apply. |
| **Append refusal evidence to the same outbox, dispatchable on commit** | **Chosen**, but it needs an explicit store operation (§5.1), genuine idempotency (§5.2) and kind-correct dispatch (§6) — none of which exist today. |

### 5.1 A separate dispatchable append, not a weakened `append`

`append` accepts `PREPARED` only, and the TCK *requires* every non-`PREPARED` initial status to be rejected.
Refusal evidence has no transition to wait for, so it must be dispatchable at commit. This is a **new contract
operation**, added alongside `append` and never weakening it:

```kotlin
suspend fun appendDispatchable(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord
```

- **Preconditions:** `record.status == PENDING`, non-blank `outboxId`/`eventKey`, `evidenceKind` is one of the
  refusal kinds. Any other status is rejected with the same fixed code `append` uses; the check is not bypassable.
- **Default implementation throws** `UnsupportedOperationException("tramai-sovereign-ops-outbox-dispatchable-append-unsupported")`,
  mirroring the safe default already used by `SovereignOpsAuditEmitter.approvalDeniedFromOutbox`. A store that has
  not implemented it cannot be used for refusal evidence, and the refusal path fails closed rather than silently
  writing nowhere or falling back to a weaker path.
- **Implementations required for 0.7.6f:** `InMemory`, `Jdbc`, `File` outbox stores. Each commits the record as
  `PENDING` in one atomic write and preserves the existing duplicate-code behaviour for `outboxId`/`eventKey`.
  The governed wrappers delegate and keep their own invariants.
- **Non-durable configuration fails closed.** `isDurable() == false` (the in-memory store) is acceptable only in
  tests and explicit in-memory deployments, exactly as today for decision intents. A refusal path configured with a
  non-durable store must be refused at startup or at first use — the contract chooses **at startup**, matching the
  existing fail-closed posture, rather than discovering the gap under load.
- **TCK extension (mandatory):** every store enrolled in `SovereignOpsAuditOutboxStoreTck` gains tests for the new
  operation — accepts `PENDING`, rejects every other initial status, rejects duplicates with the existing fixed
  codes, leaves no orphan on rejection, round-trips all evidence fields exactly, and the pre-existing
  *PREPARED-only* tests must still pass unchanged.

### 5.2 Idempotency: canonical key + atomic insert-or-find

A unique index is not idempotency. Today a duplicate `eventKey` raises, so without explicit handling the second
identical refused request would surface a server failure instead of an idempotent refusal.

**Canonical refusal event key** (domain-separated, length-prefixed, same primitives as §4):

```
refusalEventKey = "tramai/refusal-evidence" ‖ 0x00 ‖ u32be(1) ‖ lp(operation) ‖ lp(approvalIdDigest)
                  ‖ lp(actorIdentityDigest) ‖ lp(refusalReason.wireName) ‖ lp(policyVersion)
                  ‖ lp(expectedVersion.toString())
```

Every content-bearing dimension of the evidence is in the key, so a genuine retry of the identical request maps to
the identical key and an attempt that differs in any evidence-bearing way maps to a different one. Decisions on the
same target cannot collide, because `operation` is in the key; approve and deny refusals are distinct records.

**Atomic insert-or-find** is the required semantics of a new operations-level method
`SovereignOpsAuditOutboxOperations.recordRefusal(evidence)`:

1. compute the key; `findByEventKey(key)` → if present, verify equivalence and return `Duplicate(existing)`;
2. otherwise attempt `appendDispatchable`;
3. on the store's duplicate-key rejection, re-`findByEventKey` and verify equivalence → `Duplicate(existing)`.

**Equivalence is verified, not assumed.** The existing record must match the requested evidence on every key input
plus `evidenceKind`. A mismatch against an existing key means the key composition is defective (or a genuine digest
collision): fail closed with `tramai-outbox-event-key-collision` rather than returning someone else's record as if it
were this attempt's evidence.

**Pinned response.** `Duplicate` is a success for the caller's purpose: the refusal is returned as the ordinary
authorization refusal. Journalers must not be able to distinguish first-attempt from retry — an idempotent retry is
not an error and must not become one.

**Ceiling, stated rather than hidden.** Distinct repeated attempts (a caller retrying after changing
`expectedVersion`, or after the policy configuration changed) produce additional records, which is intended: they are
different attempts. What is *not* recoverable is a count of identical retries; that needs an explicit attempt counter
column if it is ever wanted.

### 5.3 Evidence failure is not fail-open

Refusal evidence is written **before** the refusal is returned. If the write fails, or the configured store cannot
record it, the caller receives a server-side failure for an operation that was **not** recorded as a refusal — and
the mutation does not happen either way. An evidence failure may never convert into "the decision proceeded", may
never be reported as a recorded refusal, and a retry after such a failure must re-attempt evidence recording, not
take a shortcut past it.

### 5.4 Non-disclosure is unchanged

The HTTP surface keeps its current shape: the capability gate answers before any state lookup, so a caller without
the capability learns nothing about existence; the per-approval role refusal keeps its documented bounded existence
disclosure. Evidence is privileged storage and does not widen what a caller sees. Refusal identifiers are digests,
never raw approval ids (§1.4, §7).

## 6. Evidence kinds and kind-correct dispatch

`evidenceKind` becomes an explicit, versioned field on the outbox record, serialized into the payload:

| `evidenceKind` | Written by | `approvalStatus` / `approvalVersion` | Emitted as |
| --- | --- | --- | --- |
| `APPROVAL_APPROVED` | decision plane, outbox-first | `APPROVED` / new version | `enforcementPoint = "sovereign-ops.approval.decide"`, `decision = "APPROVED"`, `reasonCode = "sovereign-ops-approval-granted"` |
| `APPROVAL_DENIED` | decision plane, outbox-first | `DENIED` / new version | `enforcementPoint = "sovereign-ops.approval.decide"`, `decision = "DENIED"`, `reasonCode = "sovereign-ops-admin-denial"` (existing string preserved) |
| `AUTHORIZATION_REFUSAL` | refusal path, dispatchable | both **null** | `enforcementPoint = "sovereign-ops.approval.authorization"`, `decision = "DENIED"`, `reasonCode = "sovereign-ops-authorization-refused"`, metadata carries the refusal reason wire name |

- `approvalStatus` becomes **nullable** so a refusal carries no status at all, instead of a sentinel that would be
  indistinguishable from a real status. `previousApprovalStatus`/`previousApprovalVersion` are added for R2.
  This is a public-API change on `SovereignOpsAuditOutboxRecord`: `apiDump` and an appended active
  `api-migrations.yml` entry are required, with no behavioural break for decision kinds.
- **Emitter surface.** `SovereignOpsAuditEmitter` is a `fun interface`, so the dispatch entry point is added as a
  *default* method `suspend fun evidenceFromOutbox(record)`. Its default delegates to the existing
  `approvalDeniedFromOutbox` for decision kinds — preserving third-party emitters — and throws
  `UnsupportedOperationException("tramai-sovereign-ops-refusal-evidence-unsupported")` for
  `AUTHORIZATION_REFUSAL`. `AuditEngineSovereignOpsAuditEmitter` overrides it and maps the three kinds per the table,
  which fixes §1.3.
- **Dispatcher mapping is total and explicit.** Dispatch selects on `evidenceKind`; an unknown kind is
  `FAILED_PERMANENT` with error code `unsupported-evidence-kind`, never a retry loop and never a best-effort guess.
  `UnsupportedOperationException` from an emitter is likewise classified permanent, because retrying an emitter that
  cannot express refusals would spin forever. Everything else keeps today's retryable behaviour.
- **Legacy payload decoding.** A record without `evidenceKind` is decoded as `APPROVAL_DENIED`, the only kind that
  could exist before this task, and is flagged in the emitted metadata (`legacyEvidence = true`) rather than being
  silently reinterpreted. `evidenceVersion` absent decodes as `1`.
- **Correlation.** The emitted event's stream id remains derived from `aggregateIdDigest`, and `outboxId`/`eventKey`
  are carried in audit metadata so an event can be tied back to exactly one outbox record. For refusals the stream is
  the same approval-correlation stream, which is what makes "who was refused on this approval" answerable from the
  audit trail.
- **Existing key defect (§1.4).** 0.7.6f does **not** rewrite decision event keys: changing them would break dedupe
  of records already in flight and re-key durable rows. The finding is recorded here, refusal keys never embed raw
  ids, and correcting decision keys is a separate change with its own migration.

## 7. Out of scope

F3 (`GET /{approvalId}` authentication), F5 (persisting the originally required role), Java L4 coverage, and 0.7.7
durable resume fencing. No change to the authorization design delivered in 0.7.6e: this task adds evidence, not
authority.

## 8. Test obligations

Each row is a discriminator: it must fail when the requirement is violated and pass when it holds, both transcripts
recorded. **Bold** rows must run against real wiring (application context, real HTTP and/or a real store), not a
helper that reimplements the rule.

| # | Test | Req | Discriminates against |
| --- | --- | --- | --- |
| 1 | **Two independently configured issuer boundaries, each verifying its own tokens, produce the same `sub` and different identity digests, and reconstruct as distinct operators through the real JDBC path** | R1 | subject-only attribution; also proves the digest binding rather than assuming one decoder accepts two issuers |
| 2 | Attribution cannot be forged from outside: a request carrying actor-shaped headers/body fields has no effect on recorded attribution, and `ApprovalDecisionCommand` exposes no actor parameter at all (asserted structurally on the type) | R1 | caller-constructed attribution; replaced the vacuous "ignored command field" test, since 0.7.6e removed that field |
| 3 | Approve, restart, then reconstruct operation, actor, authorization outcome, **recorded previous status/version**, resulting status/version, run correlation and outbox→event correlation from storage alone | R2 | evidence that lives only in memory, or previous state derived from the final row |
| 4 | **Each refusal reason leaves exactly one refusal record with `evidenceKind = AUTHORIZATION_REFUSAL`, and the `approvals` row is unchanged in status and version** | R3 | refusal implemented as a mutation |
| 5 | **Retrying the identical refused request twice yields one record and an ordinary refusal both times** (no server error on the retry) | R3 | duplicate-key surfacing as failure |
| 6 | **Injected evidence-write failure: no mutation, no recorded refusal, non-2xx outcome, and a retry re-attempts the evidence write** | R3 | fail-open evidence |
| 7 | **Dispatcher kind mapping: one approved, one denied and one refused record each produce the correct `enforcementPoint`/`decision`/`reasonCode`, and no refusal is ever emitted as a decision** | §6, §1.3 | the current hardcoded `DENIED` |
| 8 | Unknown `evidenceKind` and an emitter `UnsupportedOperationException` both land `FAILED_PERMANENT`, not in a retry loop | §6 | infinite retry of unrepresentable evidence |
| 9 | Legacy record without `evidenceKind` dispatches as `APPROVAL_DENIED` with the legacy flag | §6 | silent reinterpretation of old payloads |
| 10 | Key collision: an existing record with the same key but different evidence fails closed with `tramai-outbox-event-key-collision` | §5.2 | returning someone else's evidence |
| 11 | Transition failure leaves the intent `FAILED_PERMANENT` and the approval unchanged, in both mutation stores | R4 | orphaned intent dispatched |
| 12 | TCK: `appendDispatchable` accepts `PENDING`, rejects every other status and duplicates with the existing fixed codes, leaves no orphan, round-trips evidence exactly — while the existing PREPARED-only tests still pass | §5.1 | a weakened `append` |
| 13 | Restart: write all three evidence kinds, drop process state, dispatch from storage only | R2, R5 | dispatch depending on cached state |
| 14 | Legacy approval row with `NULL` issuer reconstructs with issuer explicitly unknown, never as the current issuer | R1, R5 | guessed legacy provenance |

## 9. Open items for the owner

| Item | Position |
| --- | --- |
| Retention of refusal evidence | Inherits the audit outbox's lifecycle; no new retention policy here. |
| Counting identical retries | Not supported by design (§5.2 ceiling); needs an explicit counter column. |
| Issuer normalisation | Stored verbatim as verified by the decoder. Changing it would change identity equality. |
| Legacy rows / legacy payloads | Issuer permanently unknown; payload decodes as `APPROVAL_DENIED` with a legacy flag. |
| Decision event keys embedding raw approval ids (§1.4) | Recorded finding; correction is a separate change with its own migration, deliberately not bundled here. |
