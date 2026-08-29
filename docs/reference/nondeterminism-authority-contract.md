# Nondeterminism Authority Contract

> **Epic 8.3 closure document** — the final, machine-enforced statement of who owns every
> direct nondeterministic source in TramAI production code. Historical evidence from 8.3a is
> preserved in [`time-semantics-contract.md`](time-semantics-contract.md); this document is the
> closure contract that supersedes it.

## Goal

**No incidental nondeterministic source participates directly in a domain decision.**

Every production source of wall time, monotonic time, randomness, identity/capability
generation, or scheduler execution nondeterminism is either:

- behind an explicit **semantic authority** (a named component that owns the nondeterminism
  by design), or
- documented as an intentional **composition / public-compatibility boundary** (an
  injectable seam or frozen public default), or
- **rejected by the build.**

There is no fourth state. The mechanism that guarantees this is the machine-enforced
semantic allowlist (`config/quality/runtime-nondeterminism.yml`) and its fail-closed
verifier (`verifyRuntimeNondeterminism`).

## Explicit statement

> **Zero direct nondeterminism calls is NOT the invariant.**

Cryptographically opaque IDs, security entropy, and timing defaults are legitimate
nondeterminism. The invariant is:

> **Zero unclassified direct production nondeterminism.**

A direct call that is classified — owned by a named authority or documented as an
intentional boundary — is compliant. A direct call with no classification fails the build.

## Authority matrix

| Nondeterminism dimension | Owning authority | Disposition | Where |
|---|---|---|---|
| Wall / business time | `Clock` (injected) | COMPOSITION_BOUNDARY | Public engine/store clock defaults; `TramaiEngine`, lease stores, memory stores, caches |
| Monotonic elapsed time | `MonotonicTimeSource` | AUTHORITY | `MonotonicTime.kt` (`System::nanoTime` seam); observability/diagnostic timing is a composition boundary |
| Retry randomness | `RetryJitterSource` | AUTHORITY | `tramai-core.retry.RetryJitterSource` — single jitter manufacture point (8.3b1), shared by engine retry policy and orchestration HTTP backoff |
| Engine execution identity | `EngineIdentitySource` | AUTHORITY | `workflowRunId`/`correlationId` (8.3b2a) |
| Step-attempt identity | `StepAttemptIdentitySource` | AUTHORITY | `attemptId` (8.3b2b, #318 durable chronology) |
| Checkpoint incarnation / fencing | `newCheckpointGeneration()` | CAPABILITY_AUTHORITY | `WorkflowPersistence` — ABA/incarnation fencing |
| Workflow lease capability | `LeaseIdentitySource` | CAPABILITY_AUTHORITY | 8.3d centralized lease-ID manufacture (3 stores) |
| Scheduler claim capability | `ClaimTokenSource` | CAPABILITY_AUTHORITY | 8.3d centralized claim-token manufacture (6 sites) |
| Scheduler lifecycle ownership | `SchedulerLoopOwner` | CAPABILITY_AUTHORITY | 8.3c owned scheduler scope + `ownerId` |
| Server-created run identity | server composition | COMPOSITION_BOUNDARY | `PlatformWorkflowService`, `WorkflowController` |
| Security entropy | crypto components | AUTHORITY | `SecureRandom()` inside AesGcm encryption, token generators, payload crypto |
| Public defaults | frozen contracts | PUBLIC_COMPATIBILITY_BOUNDARY | `WorkflowCheckpoint.savedAtEpochMillis`, `WorkflowObservation.workflowId`, `RetryAfter.clock` |
| Example composition | examples | COMPOSITION_BOUNDARY | `examples/*` demo identity/timestamps |

## Classification taxonomy

Every allowlist entry uses exactly one of four dispositions:

- **AUTHORITY** — the site IS the named authority; nondeterminism is the component's purpose.
- **CAPABILITY_AUTHORITY** — the site manufactures a capability (lease ID, claim token,
  checkpoint generation) through one centralized authority.
- **COMPOSITION_BOUNDARY** — an injectable seam (default `Clock`/monotonic parameter) or a
  composition point the owning layer controls; callers supply determinism.
- **PUBLIC_COMPATIBILITY_BOUNDARY** — a frozen public default deliberately kept for
  backward compatibility; documented, not refactored.

No `OTHER`, `LEGACY`, `IGNORE`, or wildcard dispositions exist. No wildcard paths.

## Enforcement model

1. **Canonical scanner** — `NondeterminismInventory` (build-logic). Deterministic, scoped to
   production `src/main`, path-normalized, includes callable references
   (`System::nanoTime`, `System::currentTimeMillis`), the Kotlin `Random` singleton forms,
   and every historical timing/identity/randomness pattern. Output ordering is stable.
   Finding identity is `(module, file, source)` — **line numbers never participate**.
2. **Semantic allowlist** — `config/quality/runtime-nondeterminism.yml`. One entry per
   identity: module, file, source, category, scanner classification, disposition, authority,
   occurrence count, rationale.
3. **Fail-closed verifier** — `verifyRuntimeNondeterminism` (typed, configuration-cache
   safe). Rejects:
   - unclassified findings (new direct source with no entry),
   - stale entries (entry whose source no longer exists),
   - mismatched classifications (category/scanner classification drift),
   - occurrence growth/shrinkage (a second direct source cannot hide behind one entry),
   - duplicate identities, unknown dispositions, blank rationale/authority, malformed schema.
4. **Stale-entry rejection** — removing a direct source without removing its allowlist
   entry fails the build; the allowlist is a live contract, not a snapshot.
5. **CI integration** — `verifyMaintainabilityBaseline` requires `verifyRuntimeNondeterminism`
   (and therefore `verifyPr`, the full maintainability gate, and the maintainability CI
   workflow all enforce it). There is no certification path that bypasses it.

## Baseline accounting

The maintainability baseline (`maintainability-deviations.yml`) still measures raw
nondeterminism counts per module; the semantic allowlist is a separate, stricter layer.
Scanner-pattern additions (callable references, `Random` singleton) added
`MQ-0023` (core), `MQ-0024` (memory-store), `MQ-0025` (platform) with full evidence;
`MQ-0022` (scheduler) remains with its rationale pointed at the allowlist. Deviations
document raw-count deltas; the allowlist documents semantic ownership. The two layers are
complementary, not redundant.

## Historical evidence

Referenced, not duplicated (see `time-semantics-contract.md` and the individual PRs):

- 8.3a — wall vs monotonic time semantics (`time-semantics-contract.md`)
- 8.3b1 — retry randomness authority (`RetryJitterSource`)
- #318 — durable step-attempt chronology (`StepAttemptIdentitySource`)
- 8.3b2a — engine execution identity (`EngineIdentitySource`)
- 8.3b2b — step-attempt identity authority
- 8.3c — scheduler lifecycle ownership (`SchedulerLoopOwner`)
- 8.3d PR 1 — residual runtime authority centralization (lease/claim/jitter, `#335`)
- 8.3d PR 2 — machine-enforced closure (this document + allowlist + verifier)
