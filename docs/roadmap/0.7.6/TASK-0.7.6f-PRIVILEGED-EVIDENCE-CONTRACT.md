# 0.7.6f — Privileged Evidence & Durable Attribution Provenance

**Status:** Contract for review, revision 5. No implementation until accepted.
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
| Outbox storage | `audit_outbox` table: `event_key TEXT NOT NULL` + unique index, status, attempt counters, **evidence fields inside the encrypted `encrypted_payload` BYTEA** | — |
| Outbox store contract | `SovereignOpsAuditOutboxStore.append` accepts **PREPARED only**, rejects duplicate `outboxId`/`eventKey`, exposes `findByEventKey`, `get`, `listByStatus`, `listExpiredEmitting` | — |
| Store conformance | `SovereignOpsAuditOutboxStoreTck` (`tramai-testing`) — including *every non-PREPARED initial status rejected*, *duplicate eventKey rejected with fixed code*, *duplicate rejection leaves no orphan* | — |
| Implementations | `InMemory…`, `Jdbc…`, `File…` outbox stores (plus governed wrappers) | — |
| Refusals | — | **nothing** |

Decision intents exist for **both** approve and deny: `auditIntentFor` sets `approvalStatus` from the decision type.
Historical outbox rows therefore already contain successful approvals, and the legacy decoding rule in §6 depends on
that.

Outbox-first semantics for a successful mutation already hold in both mutation stores: append `PREPARED`, transition,
then `PENDING`; a failed transition marks the intent `FAILED_PERMANENT`. R4 preserves that; it does not replace it.

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
**if and only if one was consulted**, the caller's `expectedVersion`, and the approval correlation digest —
**without** creating or modifying an approval row, without changing `approvals.version`, and without emitting an
event a consumer could read as a decision. A refusal that is existence-blind must stay existence-blind: recording it
may not introduce a state lookup (§5.2).

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

**Canonical bytes** (no delimiter assumption; length-prefixing keeps the encoding injective for any issuer or subject
text):

```
identityDigestBytes = SHA-256( "tramai/actor-identity" ‖ 0x00 ‖ u32be(1) ‖ lp(issuer) ‖ lp(subject) )
lp(x)               = u32be(len(x)) ‖ x        // x as UTF-8, length in bytes
```

Domain string and integer version are inside the digest input, so a future v2 encoding cannot collide with v1 and
neither can be confused with an unrelated digest over similar text.

**Printable representations differ, and the existing ones are frozen.** These values are stored in `TEXT` columns
and compared as strings, so their textual form is part of the contract. `SovereignOpsApprovalDecisionControlPlane`'s
`sha256Hex` already emits the prefixed form, and every digest it produced is persisted that way:

| Value | Persisted form | Status |
| --- | --- | --- |
| `aggregateIdDigest`, `reasonDigest` (existing) | `sha256:` ‖ 64 lowercase hex — the current `sha256Hex` output | **frozen**; not reformatted, no row rewritten |
| `decision_actor_hash` (existing) | `sha256:` ‖ 64 lowercase hex — the current `sha256Hex(actor)` output in `JdbcSovereignOpsApprovalMutationStore` | **frozen**; not reformatted, no row rewritten |
| `decision_actor_identity_digest`, `actorIdentityDigest` (new) | 64 lowercase hex, no prefix | new format |
| refusal event key (§5.2) | `refusal-sha256:` ‖ 64 lowercase hex | new format |

No existing digest is reformatted: changing `aggregateIdDigest`'s textual form would break equality against every
durable row already written, for no security benefit. New values get a distinct, self-describing form so a reader can
tell which encoding it is holding. Byte-level encodings elsewhere in this document are *hash inputs* only; they are
never persisted.

**Legacy rows.** `NULL` issuer means "recorded before 0.7.6f; issuer unknown". It must never be read as the current
issuer, and no consumer may treat a legacy row as verified provenance.

**Migration.** Additive `V10` DDL: two nullable columns, no backfill, no constraint that legacy rows could violate,
no new unique index (duplicate decisions are prevented by the version fence, not a digest index).

## 5. Decision B — refusal evidence with no mutation transaction

| Option | Verdict |
| --- | --- |
| Log or metric only | Rejected: not durable, not reconstructable. |
| Direct audit emission, bypassing the outbox | Rejected: second audit path with its own failure semantics, and the existing dispatch guarantees would not apply. |
| **Append refusal evidence to the same outbox, dispatchable on commit** | **Chosen**, with an explicit store operation (§5.1), genuine idempotency (§5.2) and kind-correct dispatch (§6). |

### 5.1 A separate dispatchable append, not a weakened `append`

`append` accepts `PREPARED` only, and the TCK *requires* every non-`PREPARED` initial status to be rejected. Refusal
evidence has no transition to wait for, so it must be dispatchable at commit. This is a **new contract operation**,
added alongside `append` and never weakening it:

```kotlin
suspend fun appendDispatchable(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord
```

- **Preconditions:** `record.status == PENDING`, non-blank `outboxId`/`eventKey`, `evidenceKind` is one of the
  refusal kinds. Any other status is rejected with the same fixed code `append` uses; the check is not bypassable.
- **Default implementation throws**
  `UnsupportedOperationException("tramai-sovereign-ops-outbox-dispatchable-append-unsupported")`, mirroring the safe
  default already used by `SovereignOpsAuditEmitter.approvalDeniedFromOutbox`. A store that has not implemented it
  cannot be used for refusal evidence, and the refusal path fails closed rather than silently writing nowhere or
  falling back to a weaker path.
- **Implementations required for 0.7.6f:** `InMemory`, `Jdbc`, `File`. Each commits the record as `PENDING` in one
  atomic write and preserves the existing duplicate-code behaviour for `outboxId`/`eventKey`. Governed wrappers
  delegate and keep their own invariants.
- **Non-durable configuration fails closed at startup** (`isDurable() == false`), matching the existing posture,
  rather than being discovered under load.
- **TCK extension (mandatory):** every enrolled store gains tests for the new operation — accepts `PENDING`, rejects
  every other initial status and duplicates with the existing fixed codes, leaves no orphan on rejection,
  round-trips all evidence fields exactly — and the pre-existing *PREPARED-only* tests must still pass unchanged.

### 5.2 Idempotency: canonical key, hashed for storage, atomic insert-or-find

A unique index is not idempotency. Today a duplicate `eventKey` raises, so without explicit handling the second
identical refused request would surface a server failure instead of an idempotent refusal.

**Canonical key input** (domain-separated, length-prefixed):

```
keyBytes = "tramai/refusal-evidence" ‖ 0x00 ‖ u32be(1)
           ‖ lp(operation) ‖ lp(approvalIdDigest) ‖ lp(actorIdentityDigest)
           ‖ lp(refusalReason.wireName) ‖ policyField ‖ lp(expectedVersion.toString())
policyField = 0x00                                   // policy version NOT consulted
            | 0x01 ‖ lp(policyVersion)               // policy version consulted
```

**Stored form.** `audit_outbox.event_key` is `TEXT NOT NULL` with a unique index, and PostgreSQL `text` cannot hold
NUL bytes, so the canonical bytes are never stored directly. The stored key is:

```
eventKey = "refusal-sha256:" ‖ lowercaseHex( SHA-256(keyBytes) )      // prefix + 64 hex chars
```

The length-prefixed structure survives because it is the *hash input*; the stored value is printable, bounded and
indexable. `approvalIdDigest` here is the digest of the caller-supplied approval id, which requires no lookup.

**Policy version is optional, and its absence is encoded explicitly.** `capabilityRefusal` runs before any lookup —
by design, it is the only refusal in the system that is existence-blind — so a `MISSING_CAPABILITY` refusal has no
consulted policy version and may not acquire one. The single-byte tag distinguishes "not consulted" from "consulted
and empty", so the two can never collide, and the refusal path performs **zero approval-store reads**.

Every content-bearing dimension is in the key, so a genuine retry of the identical request maps to the identical key
and an attempt differing in any evidence-bearing way maps to a different one. Approve and deny refusals on the same
target cannot collide, because `operation` is in the key.

**Atomic insert-or-find** is the required semantics of
`SovereignOpsAuditOutboxOperations.recordRefusal(evidence)`:

1. compute the key; `findByEventKey(key)` → if present, verify equivalence and return `Duplicate(existing)`;
2. otherwise attempt `appendDispatchable`;
3. on the store's duplicate-key rejection, re-`findByEventKey` and verify equivalence → `Duplicate(existing)`.

**Equivalence is over immutable logical facts, not delivery metadata.** The compared set is exactly the key inputs
plus `evidenceKind`: `operation`, `approvalIdDigest`, `actorIdentityDigest`, `refusalReason`, policy-consulted state
(and value when consulted), `expectedVersion`, `evidenceKind`. Everything describing delivery — `createdAt`, outbox
`status`, `attemptCount`, claim owner and claim timestamps, `emittedAt`, `lastErrorCode` — is excluded, because those
legitimately differ between a stored record and a retry, and treating them as identity would turn a correct
collision into a false integrity error.

**Correlation on retry.** `correlationId` is neither a key input nor part of equivalence: it describes the delivery
that first recorded the refusal, not the refusal itself. The **first recorded correlation is retained and
authoritative**; a retry presenting a different correlation is deduped onto the original record and does not
overwrite it. *Ceiling, stated:* evidence therefore cannot show that a retry arrived under a different correlation.
If per-delivery correlation is ever needed, it belongs in a dispatch-attempt record, not in the evidence row.

**Failure on mismatch.** A key match with different logical facts fails closed with
`tramai-outbox-event-key-collision` rather than returning someone else's record as if it were this attempt's
evidence.

**Pinned response.** `Duplicate` is a success for the caller's purpose: the refusal is returned as the ordinary
authorization refusal. A first attempt and an idempotent retry are indistinguishable to the caller.

**Counter ceiling.** Distinct repeated attempts (a caller retrying after changing `expectedVersion`, or after the
policy configuration changed) produce additional records, which is intended — they are different attempts. A count of
identical retries is not recoverable without an explicit attempt-counter column.

### 5.3 Evidence failure is not fail-open

Refusal evidence is written **before** the refusal is returned. If the write fails, or the configured store cannot
record it, the caller receives a server-side failure for an operation that was **not** recorded as a refusal — and
the mutation does not happen either way. An evidence failure may never convert into "the decision proceeded", may
never be reported as a recorded refusal, and a retry after such a failure must re-attempt evidence recording.

### 5.4 Non-disclosure is unchanged

The HTTP surface keeps its current shape: the capability gate answers before any state lookup, so a caller without
the capability learns nothing about existence — and §5.2 keeps that true for the evidence path as well; the
per-approval role refusal keeps its documented bounded existence disclosure. Evidence is privileged storage and does
not widen what a caller sees. Refusal identifiers are digests, never raw approval ids.

## 6. Evidence kinds and kind-correct dispatch

`evidenceKind` becomes an explicit, versioned field on the outbox record, serialized into the payload:

| `evidenceKind` | Written by | `approvalStatus` / `approvalVersion` | Emitted as |
| --- | --- | --- | --- |
| `APPROVAL_APPROVED` | decision plane, outbox-first | `APPROVED` / new version | `enforcementPoint = "sovereign-ops.approval.decide"`, `decision = "APPROVED"`, `reasonCode = "sovereign-ops-approval-granted"` |
| `APPROVAL_DENIED` | decision plane, outbox-first | `DENIED` / new version | `enforcementPoint = "sovereign-ops.approval.decide"`, `decision = "DENIED"`, `reasonCode = "sovereign-ops-admin-denial"` (existing string preserved) |
| `AUTHORIZATION_REFUSAL` | refusal path, dispatchable | both **null** | `enforcementPoint = "sovereign-ops.approval.authorization"`, `decision = "DENIED"`, `reasonCode = "sovereign-ops-authorization-refused"`, metadata carries the refusal reason wire name |

- `approvalStatus` becomes **nullable** so a refusal carries no status at all instead of a sentinel.
  `previousApprovalStatus`/`previousApprovalVersion` are added for R2. This is a public-API change on
  `SovereignOpsAuditOutboxRecord`: `apiDump` plus an appended active `api-migrations.yml` entry, no behavioural break
  for decision kinds.
- **Dispatch entry point.** `SovereignOpsAuditEmitter` is a `fun interface`, so
  `suspend fun evidenceFromOutbox(record)` is added as a **default** method. The default is **kind-aware**, because
  binary compatibility is not semantic correctness:
  - `APPROVAL_DENIED` → delegates to the legacy `approvalDeniedFromOutbox` (correct today);
  - `APPROVAL_APPROVED` → throws `UnsupportedOperationException("tramai-sovereign-ops-approval-evidence-unsupported")`;
  - `AUTHORIZATION_REFUSAL` → throws `UnsupportedOperationException("tramai-sovereign-ops-refusal-evidence-unsupported")`.

  An older third-party emitter therefore stops emitting approvals rather than continuing to label them as denials. The
  record is retained in the outbox and reaches `FAILED_PERMANENT` per below, so the evidence is preserved until the
  emitter is updated — visible, not silently wrong.
- `AuditEngineSovereignOpsAuditEmitter` overrides `evidenceFromOutbox` and maps all three kinds per the table, which
  fixes §1.3.
- **Dispatcher mapping is total and explicit.** Dispatch selects on `evidenceKind`; an unknown kind is
  `FAILED_PERMANENT` with error code `unsupported-evidence-kind`, never a retry loop and never a best-effort guess.
  `UnsupportedOperationException` from an emitter is likewise classified permanent. Everything else keeps today's
  retryable behaviour.
- **Legacy decoding is derived from persisted state, never assumed.** A record without `evidenceKind` is classified
  from its persisted `approvalStatus`, which the decoder reads from the record (the field is inside the encrypted
  payload, so this needs no extra lookup):

  | Legacy `approvalStatus` | Decoded `evidenceKind` |
  | --- | --- |
  | `APPROVED` | `APPROVAL_APPROVED` |
  | `DENIED` | `APPROVAL_DENIED` |
  | missing, inconsistent, or unrecognized | **fail closed** — `FAILED_PERMANENT` with `undecodable-legacy-evidence`; never guessed |

  Legacy records are flagged in emitted metadata as `legacyEvidence = true`. Defaulting an unknown legacy record to
  `APPROVAL_DENIED` would reproduce exactly the false attribution this task exists to remove.
- **Built-in-vs-third-party emitter guarantee.** The table above is an exact output guarantee for
  `AuditEngineSovereignOpsAuditEmitter`. The legacy fallback path for third-party emitters may keep
  `enforcementPoint = "sovereign-ops.approval.deny"` for `APPROVAL_DENIED`; that is an explicit compatibility
  exception, recorded here so a difference in emitted fields is not mistaken for a defect.
- **Historical events are not rewritten.** Audit events already emitted with mislabelled attribution (§1.3) stay in
  the stream as emitted; evidence correction is forward-only from the deployment of 0.7.6f. Reclassification of
  already-emitted events is out of scope and is recorded as a known limitation, not repaired by rewriting history.
  `evidenceVersion` absent decodes as `1`.
- **Correlation.** The emitted event's stream id stays derived from `aggregateIdDigest`, and `outboxId`/`eventKey` are
  carried in audit metadata so an event ties back to exactly one outbox record. For refusals the stream is the same
  approval-correlation stream, which is what makes "who was refused on this approval" answerable.
- **Existing key defect (§1.4).** 0.7.6f does **not** rewrite decision event keys: that would break dedupe of
  in-flight records and re-key durable rows. The finding is recorded, refusal keys never embed raw ids, and correcting
  decision keys is a separate change with its own migration.

### 6.1 Decode boundary and recovery for undecodable evidence

Requiring `FAILED_PERMANENT` for undecodable evidence is only meaningful if a record can *reach* that state. It cannot
today, and the reason is structural rather than incidental:

- `JdbcSovereignOpsAuditOutboxStore` decrypts and decodes inside `claimPending`, and `validateQueryableColumns`
  compares the *decoded* status/failure fields against the row columns. A quarantined row whose ciphertext still says
  the old status would therefore fail validation on every read.
- `FileSovereignOpsAuditOutboxStore` stores one encrypted `.tram.enc` file per record with the status **inside** the
  payload, so changing a status rewrites the ciphertext; and `rebuildIndex()` decodes every committed entry on
  startup, so a corrupt record blocks initialization before any quarantine could run.
- The dispatcher can only mark a record it successfully claimed, and it cannot claim what the store cannot decode.

The promise "the row stays visible through `listByStatus(FAILED_PERMANENT)`" is therefore withdrawn: a decoded listing
cannot include a record that cannot be decoded. Recovery gets its own metadata-only surface.

**Two operations, both metadata-level, neither decoding evidence:**

```kotlin
suspend fun quarantineUndecodableEvidence(limit: Int, now: Instant): QuarantineReport
suspend fun listQuarantined(limit: Int): List<QuarantinedEvidenceSummary>
suspend fun readQuarantinedCiphertext(outboxId: String): ByteArray?   // stored bytes, never decoded
```

`QuarantinedEvidenceSummary` carries only: `outboxId`, `eventKey`, `payloadDigest` (the stored `payload_digest` when
present, otherwise SHA-256 of the stored bytes), `label`, `quarantinedAt`, and a bounded failure detail. It never
carries decoded evidence fields.

#### JDBC — metadata-level quarantine

- A dedicated table `audit_outbox_quarantine(outbox_id PK → audit_outbox, label, quarantined_at, failure_detail,
  payload_digest)`, created by the same additive `V10` migration as §4. The row's own `status` is set to
  `FAILED_PERMANENT` and `last_failure_type` to the label; `encrypted_payload`, its encryption columns, `outbox_id`
  and `event_key` are **never rewritten and never deleted**.
- `listByStatus` **excludes quarantined ids by anti-join** rather than attempting to decode them; otherwise one
  quarantined row would make every page of `FAILED_PERMANENT` throw. This is an intentional behaviour change and part
  of the contract, not an optimisation.
- Quarantined rows are read only through `listQuarantined()` (metadata) and `readQuarantinedCiphertext(outboxId)` (raw
  stored bytes for operator recovery). No method returns them as decoded records.

#### File store — durable marker in a separate namespace

- Quarantine writes `quarantine/<digest>.quarantine.json` containing the same metadata as above, created atomically
  (temp file, fsync, rename) so a crash leaves either no marker or a complete one. The original `.tram.enc` file is
  left **byte-identical**; nothing is rewritten, moved or deleted.
- `rebuildIndex()` becomes **resilient**: a record that fails strict decode or validation must not abort the rebuild.
  It is indexed as *decode-failed* using its file digest only (no decode), markers already on disk are honoured and
  never decoded, and initialization therefore cannot be blocked by one bad record. Quarantine completes the durable
  marker on the next startup recovery or dispatch run.
- `listByStatus` excludes records carrying a marker; `listQuarantined()` reads the markers; `readQuarantinedCiphertext`
  returns the untouched file bytes.

#### Per-record corruption versus a systemic decryption failure

A wrong deployment key must never quarantine healthy evidence. Before quarantining anything:

1. **Control requirement.** The operation decodes the scanned batch first. If **no** dispatchable record decodes
   successfully, it aborts with `tramai-outbox-decryption-unavailable`, quarantines nothing, and leaves every row
   untouched. Successful decodes in the same batch are the control that proves the key is usable.
2. **Signature check.** Identical failure detail across every scanned row is treated as systemic and also aborts;
   quarantine requires per-record failures with distinguishable causes (or at least one control record).

*Ceiling, stated:* a deployment in which every dispatchable record is genuinely corrupt cannot satisfy the control
requirement, so the operation refuses to act and requires operator intervention. That is the safe direction — it
prefers manual action over mass-quarantining evidence — and it is recorded rather than hidden.

#### Restart behaviour (pinned)

| Backend | After restart |
| --- | --- |
| JDBC | Quarantine rows are ordinary durable table rows: the record is still `FAILED_PERMANENT`, still excluded from `claimPending`/`listByStatus`, still listed by `listQuarantined()`, its ciphertext still readable by `outbox_id`. No re-attempt to decode, no re-quarantine. |
| File | Markers are durable files honoured by `rebuildIndex()`, so startup never decodes a quarantined record and never fails because of it. Same guarantees as JDBC. |

Quarantine is idempotent on both backends: an already-quarantined record is skipped, and a decodable record is never
touched — successful decoding is a precondition for the operation to act at all.

#### Labels, never merged, never reinterpreted

| Label | Meaning | Applied by |
| --- | --- | --- |
| `undecodable-evidence` | The payload cannot be decrypted or deserialized, or decodes without the fields its own kind requires | store quarantine (§6.1) |
| `corrupt-evidence` | The payload decodes but contradicts its stored queryable columns (the existing `validateQueryableColumns` failure) | store quarantine (§6.1) |
| `unsupported-evidence-kind` | The payload decodes correctly and is internally consistent, but its kind is unknown or the emitter cannot represent it | dispatcher / emitter (§6) |

The first two are storage-integrity findings about one row's bytes; the third is a deployment gap with the evidence
intact. **No label permits reinterpretation**: an undecodable record is never emitted as `APPROVAL_DENIED`, and an
unsupported kind is never best-effort mapped to a decision.

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
| 1 | **Two independently configured issuer boundaries, each verifying its own tokens, produce the same `sub` and different identity digests, and reconstruct as distinct operators through the real JDBC path** | R1 | subject-only attribution; proves the digest binding instead of assuming one decoder accepts two issuers |
| 2 | Attribution cannot be forged from outside: a request carrying actor-shaped headers/body fields has no effect on recorded attribution, and `ApprovalDecisionCommand` exposes no actor parameter at all (asserted structurally on the type) | R1 | caller-constructed attribution |
| 3 | Approve, restart, then reconstruct operation, actor, authorization outcome, **recorded previous status/version**, resulting status/version, run correlation and outbox→event correlation from storage alone | R2 | evidence that lives only in memory, or previous state derived from the final row |
| 4 | **Each refusal reason leaves exactly one refusal record with `evidenceKind = AUTHORIZATION_REFUSAL`, and the `approvals` row is unchanged in status and version** | R3 | refusal implemented as a mutation |
| 5 | **A `MISSING_CAPABILITY` refusal persists its evidence while performing zero approval-store reads** (store instrumented to record reads), and its record is marked policy-not-consulted | R3, §5.2 | a lookup bought with an existence-blind refusal |
| 6 | **Retrying the identical refused request twice yields one record and an ordinary refusal both times**; the stored record keeps the first correlation and its original `createdAt` | §5.2 | duplicate-key surfacing as failure; delivery metadata treated as identity |
| 7 | Equivalence: a stored refusal whose delivery metadata differs (status, attempt count, timestamps, different retry correlation) is accepted as `Duplicate`, while one with different logical facts fails `tramai-outbox-event-key-collision` | §5.2 | false collision, or accepting someone else's evidence |
| 8 | **Event key round-trips through the real JDBC store**: printable, `refusal-sha256:`-prefixed, 79 characters, no NUL, accepted by `TEXT NOT NULL` + unique index | §5.2 | a key that cannot be stored |
| 9 | **Injected evidence-write failure: no mutation, no recorded refusal, non-2xx outcome, and a retry re-attempts the evidence write** | §5.3 | fail-open evidence |
| 10 | **Dispatcher kind mapping: one approved, one denied and one refused record each produce the correct `enforcementPoint`/`decision`/`reasonCode`, and no refusal is emitted as a decision** | §6, §1.3 | the current hardcoded `DENIED` |
| 11 | **Default emitter fallback: a legacy-style emitter emits `APPROVAL_DENIED`, and reaches `FAILED_PERMANENT` for `APPROVAL_APPROVED` and `AUTHORIZATION_REFUSAL`** | §6 | an old emitter continuing to label approvals as denials |
| 12 | Legacy `approvalStatus=APPROVED` decodes as `APPROVAL_APPROVED`; `DENIED` as `APPROVAL_DENIED`; missing/inconsistent/unrecognized fails closed as `undecodable-legacy-evidence` | §6 | blanket legacy defaulting to denial |
| 13 | Unknown `evidenceKind` and an emitter `UnsupportedOperationException` both land `FAILED_PERMANENT`, not in a retry loop | §6 | infinite retry of unrepresentable evidence |
| 14 | Transition failure leaves the intent `FAILED_PERMANENT` and the approval unchanged, in both mutation stores | R4 | orphaned intent dispatched |
| 15 | TCK: `appendDispatchable` accepts `PENDING`, rejects other statuses and duplicates with the existing fixed codes, leaves no orphan, round-trips evidence exactly — while the existing PREPARED-only tests still pass | §5.1 | a weakened `append` |
| 16 | Restart: write all three evidence kinds, drop process state, dispatch from storage only | R2, R5 | dispatch depending on cached state |
| 17 | Legacy approval row with `NULL` issuer reconstructs with issuer explicitly unknown, never as the current issuer | R1, R5 | guessed legacy provenance |

| 18 | **JDBC: a batch containing one valid and one undecodable record dispatches the valid record and quarantines the other; the row is listed by `listQuarantined()` with its label and payload digest, its ciphertext is byte-identical before and after (digest compared), and a second run quarantines nothing further** | §6.1 | a bad row starving the batch, or evidence being destroyed |
| 19 | **JDBC: `listByStatus(FAILED_PERMANENT)` and `claimPending` exclude quarantined rows instead of throwing, and `readQuarantinedCiphertext` returns the untouched bytes** | §6.1 | a listing that cannot survive one corrupt row |
| 20 | **File store: the same mixed batch — marker written atomically, `.tram.enc` byte-identical, neighbours untouched and still readable, and `rebuildIndex()` after restart does not fail or re-decode the quarantined record** | §6.1 | encrypted-file rewrite, or initialization blocked by one bad record |
| 21 | **Systemic key failure: with a wrong deployment key so no dispatchable record decodes, the operation aborts with `tramai-outbox-decryption-unavailable` and quarantines nothing** | §6.1 | mass-quarantining healthy evidence |
| 22 | `corrupt-evidence` (payload contradicts its queryable columns) carries its own label, distinct from `undecodable-evidence` and from `unsupported-evidence-kind` | §6.1 | merging storage corruption with configuration gaps |
| 23 | Quarantine touches neither decodable records nor `PREPARED` records | §6.1 | over-eager quarantine |
| 24 | A newly written decision record persists `aggregateIdDigest`, `reasonDigest` **and** `decision_actor_hash` in the existing `sha256:`-prefixed form, and the new identity digest in bare 64-hex form | §4 | reformatting durable digests in passing |

## 9. Open items for the owner

| Item | Position |
| --- | --- |
| Audit events already emitted with approvals mislabelled as denials (§1.3) | Forward-only correction. Historical events stay as emitted; reclassification is out of scope and recorded as a known limitation. |
| Retention of refusal evidence | Inherits the audit outbox's lifecycle; no new retention policy here. |
| Counting identical retries / per-delivery correlation | Not supported by design (§5.2 ceilings); needs an attempt-counter or dispatch-attempt record. |
| Issuer normalisation | Stored verbatim as verified by the decoder; changing it would change identity equality. |
| Legacy rows / legacy payloads | Issuer permanently unknown; evidence kind derived from persisted `approvalStatus`, undecodable records fail closed. |
| Persisted digest formats (`sha256:` prefix on `aggregateIdDigest`, `reasonDigest` and `decision_actor_hash`) | Frozen. Reformatting would break equality against every existing row; new values use a distinct self-describing form. |
| Quarantine operations (§6.1) | New store API in 0.7.6f: quarantine, metadata listing and raw-ciphertext read. Quarantined rows are excluded from decoded listings by contract; recovery is metadata-level only. |
| Mass corruption | If every dispatchable record is genuinely corrupt, the control requirement makes quarantine refuse to act (§6.1). Intentional: manual intervention over mass-quarantining evidence. |
| Decision event keys embedding raw approval ids (§1.4) | Recorded finding; correction is a separate change with its own migration. |
