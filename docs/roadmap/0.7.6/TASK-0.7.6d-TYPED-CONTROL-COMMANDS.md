# TASK 0.7.6d — Typed Control Commands & Fail-Closed Approval Concurrency

**Epic:** 0.7.6 — Authentication, Authorization & Runtime Control
**Predecessor:** 0.7.6c (merged, `148254a1`) — the OIDC/JWT operator authentication boundary
**Base:** `release/0.7.0` at `148254a1`
**Priority:** P1 — authoritative state integrity

## Why this task exists

0.7.6a recorded finding **F2**: the approval decision path substituted the server's current version for a
version the caller never supplied.

```kotlin
expectedVersion = command.expectedVersion ?: approval.version   // SovereignOpsApprovalDecisionControlPlane.kt:109,118
```

A compare-and-set that is handed the current version always succeeds, so the store's optimistic concurrency
was decorative: a caller could omit its observed version and still overwrite a decision that another operator
made in between. Worst case is not a wrong error message — it is a second decision committed over an existing
one, with two dispatchable audit facts for one approval.

The invariant this task establishes:

> Every state-dependent approval decision carries the version its caller observed. If authoritative state has
> changed, the command fails without changing the approval or committing a dispatchable audit fact.

## Phase 0 — baseline and reproducer

Baseline: `release/0.7.0` at `148254a1` (PR #557 merged). The controller passed
`request.expectedVersion` (nullable) into a command whose `expectedVersion` defaulted to `null`, and the
control plane resolved `null` into the version it had just read.

**Reproducer (pristine base).** `SovereignOpsApprovalDecisionControlPlaneTest` at `148254a1` builds its
decision command without a version (`decisionCommand("approval-1")`) and asserts `Approved`. That test passed
at the base, which means: approval status `PENDING → APPROVED`, approval version `0 → 1`, and one `PENDING`
audit-outbox record — all from a command that never stated which version it saw. The same helper drove every
other decision test, so the unsafe path was the *default* path.

Compatibility surface at the base: 19 construction sites of `ApprovalDecisionCommand` (2 controllers, 3 ops
tests, 9 JDBC tests, 2 examples tests, 1 rest test helper, plus the declaration), a public API dump for
`tramai-spring-boot-starter-sovereign-ops` and `-rest`, and a JSON contract (`expectedVersion` nullable).

## Phase 1/2 — the contract change (a deliberate break)

```kotlin
data class ApprovalDecisionCommand(
    val approvalId: ApprovalId,
    val actorId: String,
    val actorRole: ApproverRole,
    val expectedVersion: Long,      // required: the caller's observation
    val comment: String? = null,
    val correlationId: String? = null,
) {
    init { require(expectedVersion >= 0) { "tramai-approval-invalid-expected-version" } }
}
```

- **No default, no nullable form, no compatibility overload.** A nullable field plus a convenience constructor
  is exactly how the unsafe path would survive: any overload that accepts "no version" must either fail (so it
  is not a convenience) or substitute (so it is the defect). The safe contract and the convenience contract
  cannot coexist, so only the safe one ships.
- `expectedVersion` is the fourth parameter, between `actorRole` and the optional fields, so it is a required positional argument in Kotlin while `comment` and `correlationId` keep their defaults.
- There is no Java-callable constructor: the real constructor is private, the only public one is Kotlin's synthetic default-arguments bridge (`int` mask + `DefaultConstructorMarker`), and `ApprovalId`/`ApproverRole` are inline value classes erased to `String` with name-mangled accessors. A command is built from Kotlin or handed to Java by existing code.
- Negative versions are rejected at construction: the approval version domain starts at 0, so a negative
  precondition can never be satisfied and must not become a mutation attempt.
- The actor fields are documented as **untrusted claims**. Holding a `OperatorPrincipal` from 0.7.6c is not
  evidence that a command came from that operator; operator authorization is 0.7.6e.

`ApprovalResumeCommand` keeps nullable versions **on purpose**: those fields are read by the internal resume and
recovery path, where a process resuming after a restart legitimately has no caller-observed version. Making
them required is durable resume fencing, which is 0.7.7 and would need its own architecture decision.

## Phase 3/4 — typed stale outcome and fail-closed ordering

```kotlin
data class Stale(val approvalId: ApprovalId, val expectedVersion: Long, val currentVersion: Long)
```

Order inside `decide()`: `NotFound` → **version precondition** → `Expired` → authorization → CAS. Two
consequences worth reviewing:

- The version check runs **before** `AlreadyApproved`/`AlreadyDenied`. A caller holding a stale version must
  not receive an apparently successful idempotent answer for a decision it never made.
- The mutation store is called with `command.expectedVersion` verbatim. The initial read is not the guarantee;
  the store's compare-and-set is. A `ApprovalStoreConflictException` at that point re-reads authoritative state
  and reports `Stale` with the persisted version, so a lost race is described by the state that actually won.
  The in-memory store now signals CAS loss with the same exception the JDBC store uses, instead of a bare
  `require(...)`, so both implementations speak one dialect.

A decision that would push the version past `Long.MAX_VALUE` is refused (`tramai-approval-version-exhausted`)
rather than allowed to wrap into a value that looks like a fresh approval.

## Phase 5 — REST precondition

The JSON `expectedVersion` field stays the single representation (no `If-Match` header that could disagree).

| Request | Response |
|---|---|
| field absent | **400** `INVALID_PRECONDITION` / `tramai-approval-expected-version-required` |
| `"expectedVersion": null` | **400**, same code |
| negative | **400** `tramai-approval-invalid-expected-version` |
| stale | **409** `STALE` with `expectedVersion` + `currentVersion` |
| current version | unchanged (`200` / `404` / `409` per existing semantics) |

A rejected precondition never becomes a control-plane command, so it cannot reach the mutation store. Error
bodies carry a stable code and no approval metadata, token material or stack trace. `actorId`/`actorRole`
authority semantics are untouched — they remain untrusted until 0.7.6e.

## Phase 7 — adversarial evidence

In-memory (`ApprovalDecisionConcurrencyTest`, 10 tests) and JDBC/PostgreSQL
(`JdbcSovereignOpsApprovalDecisionControlPlaneTest`, 2 new tests). Every negative assertion inspects
authoritative state and outbox records, not only the response:

| Discriminator | Evidence |
|---|---|
| D1 missing version (HTTP) | 400, `verifyNoInteractions(controlPlane)` |
| D2 explicit null (HTTP) | 400, `verifyNoInteractions(controlPlane)` |
| D3 stale command | `Stale(0 → 1)`, approval still `APPROVED` at version 1, exactly one `PENDING` outbox record, no `PREPARED`/`FAILED` orphan |
| D4 concurrent approve vs deny | two threads rendezvous inside the store read; exactly one commit, one `Stale`, version incremented once, one dispatchable audit fact — in memory **and** on PostgreSQL |
| D5 terminal state + stale version | `Stale`, not `AlreadyApproved` |
| D6 terminal state + current version | `AlreadyApproved`/`AlreadyDenied` preserved |
| D7 NotFound / expired | typed outcomes, zero mutation, empty outbox |
| D8 store parity | the stale and racing assertions run against both stores |
| D9 no authority confusion | the command API has no principal/authentication parameter; a forged `OperatorPrincipal` changes nothing |
| D10 no second authority | a capture wrapper proves the mutation authority receives `0L` verbatim, and receives **nothing at all** for a stale command |

## API migration registry

The registry's ACTIVE/LANDED lifecycle is respected: the two landed entries are preserved verbatim and the
0.7.6d transitions are appended as new ACTIVE entries, so history is never rewritten in place.

| Module | Landed (unchanged) | Appended ACTIVE (0.7.6d) |
|---|---|---|
| `:tramai-spring-boot-starter-sovereign-ops` | `7100484d… → 5e773044…` (0.7.1d run attribution) | `5e773044… → fc86f30f…` |
| `:tramai-spring-boot-starter-sovereign-ops-rest` | `557d7bc5… → 7f3e38e6…` (0.7.6c OIDC boundary) | `7f3e38e6… → c9bb77f9…` |

Hashes were recomputed from the committed dumps, not copied:
`sha256(git show 5ebfd497:<module>/api/<module>.api)` and `sha256(<module>/api/<module>.api)`.

## Mutation evidence

Applied to this tree, restored after each, control run recorded at the end.

| Mutant | Result |
|---|---|
| M1 caller version ignored, server uses the version it just read | see report |
| M2 version check moved after the terminal-state shortcuts | see report |
| M3 CAS loss no longer re-reads authoritative state | see report |
| M4 REST substitutes a default version instead of rejecting | see report |
| M5 command accepts a negative precondition | see report |

## Established by 0.7.6d

- A required caller-observed approval version; no implicit latest-version substitution anywhere on the
  decision path (control plane, controller, store call).
- An explicit `Stale` outcome carrying both versions, ordered ahead of terminal-state idempotency.
- State-preserving stale rejection: approval status, version and outbox are unchanged by a rejected command.
- Compare-and-set as the final authority, with race losers reported from persisted state — verified against
  in-memory and PostgreSQL stores.

## Not established by 0.7.6d

- Authentication enforcement on privileged routes (0.7.6e).
- Trusted actor attribution; `actorId`/`actorRole` remain caller claims (0.7.6e/f).
- `requiredRole` enforcement and operator capability checks (0.7.6e).
- A complete server-wide authorized control boundary (0.7.6e/g).
- Durable cancellation/resume fencing, and required resume versions (0.7.7).

Approval decisions must not be described as authorization-safe. They are version-fenced, which is a different
property.
