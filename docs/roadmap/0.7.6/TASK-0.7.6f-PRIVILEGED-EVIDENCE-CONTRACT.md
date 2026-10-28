# 0.7.6f — Privileged Evidence & Durable Attribution Provenance

**Status:** Contract for review. No implementation until this is accepted.
**Base:** `release/0.7.0` at `d4bde521` (0.7.6e delivered as `440d63fc`).
**Scope:** attribution and evidence for privileged approval decisions and their authorization refusals.

## 1. Why this task exists

0.7.6e made the decision path authorization-safe: identity is verified, capabilities and approver roles are derived
from server configuration, and the mutation is version-fenced behind the authorization gate. What it did **not** do
is make the decision *reconstructable* from durable evidence afterwards, and it records nothing at all when a
decision is **refused**.

Two concrete defects follow from that, both visible in the merged code:

1. **Attribution loses the issuer.** `SovereignOpsApprovalDecisionControlPlane` passes `actor = authority.subject`
   into the audit intent and the mutation command, and `JdbcSovereignOpsApprovalMutationStore` stores
   `decision_actor_hash = sha256(actor)`. `OperatorAuthority` has carried `(issuer, subject)` since 0.7.6e, and the
   issuer is discarded on the way to durable state. `sub` is only unique *within* an issuer, so the durable record
   cannot distinguish two operators who share a subject string across two identity providers.
2. **Refusals leave no trace.** `ApprovalDecisionResult.Unauthorized` is returned to the caller and nothing is
   written: no outbox record, no audit event, no approval mutation. A privileged control plane that cannot say who
   was refused, for what, and why is not auditable — the refusals are exactly the security-relevant events.

## 2. What is already durable (inventory, not aspiration)

| Evidence | Where | Carries today |
| --- | --- | --- |
| Approval decision | `approvals.decision_actor_hash`, `decision_type`, `status`, `version`, `decided_at` | subject digest, decision, resulting status, version |
| Decision audit intent | `SovereignOpsAuditOutboxRecord` (`aggregateType`, `aggregateIdDigest`, `operation`, `eventKey`, `actor`, `workflowRunId`, `correlationId`, `approvalStatus`, `approvalVersion`, `reasonDigest`, `reasonLength`) | actor **as subject text**, operation, resulting status, version, run correlation |
| Audit event | `audit_events` (`event_type`, `event_hash`, `previous_event_hash`, `sanitized_actor`, encrypted payload) | hash-chained stream, sanitized actor |
| Refusals | — | **nothing** |

Both stores already implement outbox-first semantics for a successful mutation: append `PREPARED`, transition, then
mark `PENDING`; a failed transition marks the intent `FAILED_PERMANENT` and the approval is unchanged. That
relationship is the property R4 must preserve, not replace.

## 3. Requirements

**R1 — Durable identity is `(issuer, subject)`, sourced only from `OperatorAuthority`.**
No caller-supplied actor string may reach durable state. The audit intent and the mutation command carry the
verified identity; the issuer is persisted next to the existing subject digest, and a single identity digest binds
both so that one stored value answers "which operator, at which issuer" unambiguously. Attribution from any other
source (command field, header, principal constructed by a caller) is a violation.

**R2 — A completed decision is reconstructable from durable evidence alone.**
For every successful approve/deny, the durable set must answer, without reading process state or logs: which
operation, which actor `(issuer, subject)`, which authorization outcome, previous status and version, resulting
status and version, the workflow/run it belongs to, and the audit correlation that ties the outbox record to the
emitted event. Evidence carries an explicit version marker so a consumer can tell which fields it may rely on.

**R3 — Refusals are durable, and are not mutations.**
A refused decision writes evidence that records the operation, the actor `(issuer, subject)`, the refusal reason
from the closed reason set, and the approval correlation — **without** creating or modifying an approval row,
without changing `approvals.version`, and without emitting a decision audit event that a consumer could read as a
successful decision. The refusal reason set is the closed set already carried by
`ApprovalUnauthorizedReason` (missing capability, policy unavailable, role not permitted); free text is not part of
the contract.

**R4 — The transactional relationship between a successful mutation and its audit intent is preserved.**
In-memory and JDBC continue to guarantee: intent appended before the transition, dispatchable only after the
transition commits, and an orphaned intent marked permanently failed rather than dispatched. Refusal evidence is
the one new write path, and it must not disturb this ordering or introduce a second dispatch mechanism.

**R5 — The result is proven, not asserted.**
Adversarial tests (forged actor strings, wrong issuer, refusal paths, evidence failure), restart/reconstruction
tests (evidence written, process restarted, decision reconstructed from storage alone) and evidence-failure tests
(storage refuses the write: the mutation must still not happen, and the outcome must not be reported as recorded).

## 4. Decision A — how `(issuer, subject)` becomes durable without breaking the actor format

**Options considered.**

| Option | Verdict |
| --- | --- |
| Concatenate into the existing actor string (`issuer\|subject`, `issuer#subject`) | Rejected. Needs an escaping rule, invites ambiguous parses, and silently changes the meaning of a column every existing consumer reads. |
| Rehash `decision_actor_hash` over `(issuer, subject)` | Rejected. It is a silent reinterpretation of an existing column: pre-0.7.6f rows and post-0.7.6f rows would be indistinguishable but not comparable, and no backfill is possible because only the digest survives. |
| Store the issuer in `sanitized_metadata` JSON | Rejected. Attribution buried in a free-form field is not enforceable and not queryable as evidence. |
| **Additive columns: issuer + a digest that binds both** | **Chosen** |

**Decision.** Two nullable columns on `approvals`: `decision_actor_issuer TEXT` and
`decision_actor_identity_digest TEXT`, where the digest is computed with an explicit domain prefix over
`issuer`, a separator that cannot occur in an issuer, and `subject`. The existing `decision_actor_hash` keeps its
exact current meaning and is **frozen**: it remains `sha256(subject)`, it is **not** recomputed, and no row is
rewritten. The outbox record and audit payload gain `actorIssuer` and `actorIdentityDigest`.

**Why the freeze matters.** `decision_actor_hash` is the only correlation key that exists for decisions taken
before this task. Redefining it would make old and new rows silently incomparable while looking identical.
Keeping it, and adding a digest that binds both fields, gives an unambiguous key for new evidence and preserves
the old one for legacy correlation.

**Legacy rows.** A `NULL` issuer means "recorded before 0.7.6f; issuer unknown". It must never be interpreted as
"same issuer as the current request", and no consumer may treat a legacy row as evidence of verified provenance.
The reconstruction test asserts exactly this: legacy evidence reconstructs with an explicitly unknown issuer rather
than a guessed one.

**Migration.** Additive `V10` DDL only: two nullable columns, no backfill, no constraint that would fail on legacy
rows. A partial unique index is not introduced — duplicate decisions are prevented by the version fence, not by a
digest index.

## 5. Decision B — refusal evidence with no mutation transaction

There is no approval transition to be atomic with, so an outbox-first `PREPARED → PENDING` sequence has no second
half. Three options:

| Option | Verdict |
| --- | --- |
| Log or metric only | Rejected. Not durable, not reconstructable, disappears with process lifetime. |
| Emit an audit event directly, bypassing the outbox | Rejected. Creates a second audit path with its own failure semantics, and the dispatch guarantees that already exist for decisions would not apply. |
| **Append a refusal record to the same outbox, immediately dispatchable** | **Chosen** |

**Decision.** A refusal appends one outbox record with its own aggregate type and event key, entering `PENDING`
directly, because the only reason decisions use `PREPARED` first is the coupling to a transition that does not
exist here. It carries the operation, actor `(issuer, subject)` and identity digest, the closed refusal reason, the
approval correlation digest, and the policy version that was consulted. It carries no approval status and no
version, so it cannot be mistaken for a decision record.

**Idempotency and its ceiling.** The event key is derived from `(approval correlation, refusal reason, identity
digest)`, so a client retrying the same refused request collapses onto one record via the existing unique
`event_key` index rather than filling the outbox. *Ceiling, stated rather than hidden:* repeat attempts therefore
cannot be counted from evidence. If attempt counting is needed later, it belongs in an explicit counter column, not
in a second record per attempt.

**Evidence failure is not fail-open.** The refusal record is written **before** the refusal is returned. If the
write fails, the caller receives a server-side failure for an unrecorded operation instead of an authorization
refusal that claims a decision was made; and in every case the mutation does **not** happen. An evidence failure
may never convert into "the decision proceeded", and it may never be reported as a recorded refusal.

**Non-disclosure is unchanged.** The HTTP surface keeps the current shape: the capability gate answers before any
state lookup, so a caller without the capability learns nothing about existence, and the per-approval role refusal
keeps its documented, bounded existence disclosure. Durable evidence is privileged storage; it does not widen what
the caller sees. The refusal record's own identifiers are digests, not raw approval ids, matching the existing
outbox rule.

## 6. What this task deliberately does not cover

- `GET /{approvalId}` authentication (F3) — unchanged, still recorded as unresolved.
- Persisting the originally required role (F5) — unchanged; the policy stays keyed on the approval's persisted
  `policyVersion`.
- Java call-site coverage for the L4 guard — separate task.
- Durable resume fencing — 0.7.7.
- Any change to the authorization *design* delivered in 0.7.6e. This task adds evidence, not new authority.

## 7. Test obligations

Each row is a discriminator: it must fail when the requirement is violated and pass when it holds, with both
transcripts recorded.

| # | Test | Requirement | Discriminates against |
| --- | --- | --- | --- |
| 1 | Two tokens with an identical `sub` from two different issuers produce different identity digests and reconstruct as different operators | R1 | subject-only attribution |
| 2 | A caller-supplied actor string in the command is ignored; the recorded actor is the verified authority | R1 | caller-constructed attribution |
| 3 | Approve, then reconstruct operation, actor, outcome, previous/resulting status and version, run correlation and audit correlation from storage alone | R2 | evidence that only exists in memory or logs |
| 4 | Refusal for missing capability, for unavailable policy and for a wrong role each leave exactly one refusal record, and `approvals` is byte-identical (status and version unchanged) | R3 | refusal implemented as a mutation |
| 5 | Retrying a refused request twice yields one refusal record | R3 | outbox flooding |
| 6 | A failure injected into the refusal write yields no mutation, no claimed recorded refusal, and a non-2xx outcome | R3 | fail-open evidence |
| 7 | Transition failure leaves the intent `FAILED_PERMANENT` and the approval unchanged, in both stores | R4 | orphaned intent dispatched |
| 8 | Restart: write evidence, drop all process state, reconstruct from storage only | R2, R5 | reconstruction that depends on cached state |
| 9 | Legacy row with `NULL` issuer reconstructs with issuer explicitly unknown, never as the current issuer | R1, R5 | silent legacy reinterpretation |

Tests 1, 4, 5 and 6 are adversarial and must run against real wiring (application context, real HTTP or real
store), not against a helper that reimplements the rule. Tests 3 and 8 must read from the durable stores, not from
the in-memory objects that wrote them.

## 8. Open items for the owner

None of these block the contract; each is a scope statement, not a gap.

| Item | Position |
| --- | --- |
| Retention of refusal evidence | Inherits the audit outbox's existing lifecycle. No new retention policy is introduced here. |
| Attempt counting for refused requests | Not supported by design (§5 ceiling). Requires an explicit counter column if wanted. |
| Issuer normalisation | The issuer is stored verbatim as verified by the decoder. Any normalisation (trailing slash, case) is out of scope; changing it would alter identity equality. |
| Legacy rows | Permanently issuer-unknown. No backfill exists because only the digest was stored. |
