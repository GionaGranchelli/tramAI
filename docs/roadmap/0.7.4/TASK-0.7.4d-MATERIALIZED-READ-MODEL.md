# TASK-0.7.4d — Durable, rebuildable governance read model

Status: **PHASES 0–4 IMPLEMENTED AND QUALIFIED. Phase 5 (adversarial battery) and Phase 6 (gate run)
PENDING.** Not merged.

- Base commit: `052cc27fb9979a4b5fb467d06c6eed3ba87176d3` (`release/0.7.0` tip, the 0.7.4c3 squash of PR #534)
- Task branch: `task/0.7.4d-materialized-governance-read-model`, worktree `/home/gionag/tramai-d1`
- Change class: `runtime-behaviour`
- Predecessor: PR #534 — 0.7.4c3, **QUALIFIED — VERIFIED COVERAGE SEMANTICS**
- Baseline evidence: `evidence/0.7.4d-materialized-read-model/baseline.txt`

## 1. Baseline, measured at the base commit

| Check | Command | Result |
| --- | --- | --- |
| Release containment | `git merge-base --is-ancestor 052cc27f origin/release/0.7.0` | VERIFIED: the tip *is* `052cc27f` |
| Governance projection suite | `:tramai-security:test --tests 'dev.tramai.security.governance.*'` | VERIFIED: **207 tests, 0 failures** |
| Durable audit store, real backend | `:tramai-persistence-jdbc:test --tests '*JdbcAuditStoreTckTest*'` | VERIFIED: **43 tests, 0 failures**, real PostgreSQL via Testcontainers |

The second row is the environment probe for this task: it establishes that a real JDBC backend exists
here, so "durable" is testable rather than assertable.

## 2. Where durable storage legally lives, and why

**The store, its codec and its migration live in `:tramai-security`, internal.** The alternatives are
not preferences, they are illegal:

| Candidate | Verdict | The rule that decides it |
| --- | --- | --- |
| Store in `:tramai-persistence-jdbc` | ILLEGAL | It cannot see the projection: those types are `internal` to `:tramai-security`. Making them public changes a published module's API dump — the workaround the task forbids. |
| `:tramai-security` depends on `:tramai-persistence-jdbc` | ILLEGAL | Cycle: `tramai-persistence-jdbc/build.gradle.kts:13` already declares `api(project(":tramai-security"))`, and `module-boundaries.yml` states cycles are detected by the verifier. |
| New module in the persistence layer | ILLEGAL | Same visibility wall as row 1. |
| Coverage semantics redefined in `:tramai-control-plane` | REJECTED | `core-contracts → governance-security` is a forbidden edge, and a second model of one governance decision is what the epic forbids. |
| Store in `:tramai-security` speaking JDBC | **CHOSEN** | No new module edge, no cycle, no public API change. `adding-a-store.md`: implementations live in the owning persistence module *or the module that will own the new backend* — the shape `tramai-memory-store`, `tramai-scheduler` and `tramai-platform` already use, the last of those shipping its own `db/migration/`. |

**Consequence, stated rather than implied:** the materialized read model is not reachable from
`:tramai-persistence-jdbc` consumers. It is an internal, composition-wired capability of the governance
module, which is what 0.7.4e's query API will surface. §8 records what that boundaries off.

## 3. What is durable, versioned, stale, rebuildable, unknown

- **Durable**: the identity, the two coverage claims, the facts with their authority and freshness, and
  the per-source provenance. Three tables, typed columns, no serialized blob, so a field that should
  not be stored has nowhere to go: there is no column for a prompt, an argument, a result, a credential,
  or arbitrary audit metadata. Registration *content* is deliberately not duplicated — the
  authoritative registration record remains the only place registration state is read from.
- **Versioned**: `format_version` (a stored row carrying anything else is refused, not upgraded), the
  `classification_revision` the decision coverage was made under, and per-source versions.
- **Stale**: exactly what a fresh authoritative observation disagrees with, per source. A snapshot may
  be current for registration and behind for audit; there is no overall freshness flag, because there
  is no single source.
- **Rebuildable**: a rebuild is the same materialization. Equal source facts produce an equal semantic
  digest, and the store reports `ALREADY_CURRENT` instead of writing an equal row.
- **Unknown**: everything a fresh observation did not establish. Currency is `UNKNOWN`, never `CURRENT`,
  when nothing was observed.
- **Cannot be inferred from a stored snapshot**: that a decision did not occur (emission is optional);
  that a verified range proves event origin; that an `ALLOW` is completion; that registration content is
  current; that the two sources were read at one instant.

## 4. Schema and ordering contract

`governance_projection_snapshot` (identity + provenance + `semantic_digest` + `materialized_at`),
`governance_projection_fact` (one row per non-decision fact: authority, freshness, established,
must-not-infer, version, watermark), `governance_projection_decision` (one row per family: raw coverage
and decision coverage side by side, plus the narrow decision record).

Ordering is the pair **(audit head position, registration version)**, compared lexicographically, in
SQL. Audit first because the decision records derive from the audit stream: a candidate built from an
older head is weaker evidence even when registration has moved on. Equal pair with equal content is a
no-op; equal pair with different content — including a different head hash at the same purported
position — is a conflict, refused rather than resolved by arrival order. Zero in either column means
"not established": authoritative versions and audit positions both start at one, so zero is not a value
either source can produce.

## 5. Transaction and concurrency guarantees actually available

A publish is one transaction. The header, its facts and its decisions commit together, so an interrupted
write leaves the previous snapshot whole — verified by fault injection between the header write and the
child rows. PostgreSQL's default READ COMMITTED is sufficient for the conditional upsert; nothing here
needs or claims SERIALIZABLE. **Not available and not claimed**: an atomic cross-store snapshot of
registration and audit together. The sources are read independently and `read_consistency` in the row
says exactly that (`INDEPENDENT_SOURCES`).

## 6. Real durable-backend qualification

Against real PostgreSQL 17 (Testcontainers), through the production path with no stub between the
decision and the persisted view: emitter → `AuditStore.appendNext` →
`AuditBackedGovernanceDecisionFactSource` → `GovernanceProjectionReader` → materializer → store →
load. Verified: the snapshot outlives the session that wrote it and matches the source-recomputed
reference field by field; an advanced audit stream makes it stale per source without unmaking the
historical range it verified; currency is never claimed without a fresh observation; a rebuild after an
append covers what it now observed and a further rebuild is `ALREADY_CURRENT`.

Counts at the head: store tests **18/18**, materializer tests **6/6**, qualification tests **4/4**, full
governance suite recorded in §7.

## 7. Verification

- `:tramai-security:test --tests 'dev.tramai.security.governance.*'` — recorded below once the phase-6 run completes
- gate run: **PENDING** (Phase 6)

## 8. Limitations and non-claims

- **The audit half of the qualification is an in-memory store.** A test using `JdbcAuditStore` would have
  to live in `:tramai-persistence-jdbc`, which cannot see the read model — the visibility wall from §2
  cuts both ways. The durable audit store has its own real-PostgreSQL TCK (43 tests, §1); the two halves
  are qualified separately because no single module may hold both.
- **No incremental refresh**, deliberately: an incremental path would have to assume delivery order, and
  the storage contract rejects regressions rather than promising order. A rebuild is bounded by the
  audit read, which is capped by the source's configured maximum.
- **Registration content is not materialized** — by design, so the read model cannot become a second
  answer to an authoritative question. A consumer needing registration content reads the authority.
- **Non-decision recognition remains by producer vocabulary**, not an authenticated family marker;
  `classification_revision` makes a vocabulary change visible rather than silent.
- **No freshness daemon, no background refresh, no cache invalidation.** Currency is a caller-supplied
  observation, never a self-declared state.
- Unchanged from earlier slices: T1 workload-origin authentication, event-origin authenticity (S19), live
  `provider.route` production, bundle-backed decision reads. No baseline regeneration, suppression,
  deviation increase or architecture-rule change is part of this task, and none was needed.

## 9. What remains for 0.7.4e

The read model is internal, so the query API cannot be built by widening `:tramai-persistence-jdbc`: it
needs a deliberate public seam. 0.7.4e should decide the shape (a typed query port in `:tramai-security`
with the projection exposed through it, or a dedicated module in a layer allowed to see governance) and
must not resolve it by making these internal types public as a side effect. The storage contract is
already the right shape for it: identity-keyed reads, per-source currency against a fresh observation,
and the two coverage claims reported independently.

## 10. Phases

- [x] Phase 0 — baseline and storage-boundary decision (§1–§2)
- [x] Phase 1 — materialization contract (internal snapshot, read model, outcomes, per-source currency)
- [x] Phase 2 — durable storage (three-table schema, transactional publish, ordering, refusals)
- [x] Phase 3 — deterministic materialization and rebuild
- [x] Phase 4 — real durable-backend qualification (§6)
- [ ] Phase 5 — adversarial mutation battery
- [ ] Phase 6 — gate run and evidence completion
