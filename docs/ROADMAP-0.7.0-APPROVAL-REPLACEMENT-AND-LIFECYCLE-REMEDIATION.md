# TramAI 0.7.0 — Approval Validity, Replacement, and Lifecycle Remediation

> **Status:** P1 roadmap companion  
> **Target release:** TramAI 0.7.0 / first 0.7.x follow-up where necessary  
> **Relationship:** Complements `ROADMAP-0.7.0-RELEASE-CUT.md`, approval/continuation security semantics, runtime control, tool invocation contracts, tool-effect metadata, audit/evidence, and governance reconstruction/replay  
> **Scope:** Make approval lifetime policy-driven instead of universally time-limited, preserve single-use exact-action authority, and provide a safe replacement path when approval authority becomes invalid or expires.

---

## 1. Decision

TramAI should stop treating **approval** and **expiry** as the same concept.

Some governed actions need a short authorization window. Others can safely remain approvable or executable for much longer, potentially without a wall-clock expiry, as long as the authority remains bound to the exact governed action and cannot be reused or widened.

The canonical model should therefore separate:

```text
ApprovalStatus
    = decision state

PendingDecisionLifetime
    = how long a PENDING approval may receive a human decision

ExecutionAuthorityLifetime
    = how long an APPROVED exact action remains eligible to execute

ConsumptionPolicy
    = how many times the approved authority may be consumed
```

The existing decision state machine remains authoritative:

```text
PENDING
  ├─► APPROVED
  ├─► DENIED
  └─► TIMED_OUT

APPROVED / DENIED / TIMED_OUT = terminal decision states
```

This roadmap does **not** add `TIMED_OUT -> PENDING` or otherwise reopen a terminal approval.

Core principles:

> **Expiry is policy, not the definition of approval.**

> **No expiry does not mean reusable authority.**

> **Replacement creates new authority; it never revives old authority.**

---

## 2. Why one universal timeout is too coarse

A single TTL for every approval/tool continuation conflates different risk profiles.

Examples:

```text
readDocument(documentId = 123)
```

may remain reasonable to execute later if it is single-use, still authorized, and bound to the same document.

By contrast:

```text
schedulePayment(amount = 18_400, iban = ...)
```

is time-sensitive and high-impact. A short execution-authority lifetime is appropriate because business state, account state, policy, and operator intent can change quickly.

Other examples:

| Action | Likely lifetime posture |
| --- | --- |
| read internal document | potentially indefinite single-use |
| deterministic analysis | potentially indefinite single-use |
| generate report | potentially indefinite single-use |
| send external email | usually bounded |
| publish content | bounded |
| delete record | strongly bounded |
| change access permissions | strongly bounded |
| schedule/transfer payment | strongly bounded |
| unknown-effect tool | bounded by default |

This is policy guidance, not a hard-coded universal mapping.

---

## 3. Four concepts, not one expiry timestamp

### 3.1 Approval decision status

`ApprovalStatus` answers:

> What decision was made?

It remains:

```text
PENDING
APPROVED
DENIED
TIMED_OUT
```

### 3.2 Pending decision lifetime

`PendingDecisionLifetime` answers:

> Until when may a human decide this still-pending request?

Conceptually:

```text
UNTIL(timestamp)
UNTIL_CANCELLED
INDEFINITE
```

Exact public types are not frozen.

An indefinite pending lifetime means the approval request does not automatically time out because of wall-clock age. It does not guarantee that the underlying operation will still be executable once approved.

### 3.3 Execution authority lifetime

`ExecutionAuthorityLifetime` answers:

> Once approved, until when may this exact governed action be consumed?

Conceptually:

```text
UNTIL(timestamp)
UNTIL_REVOKED
INDEFINITE
```

A runtime may still apply hard safety revalidation before execution.

### 3.4 Consumption policy

`ConsumptionPolicy` answers:

> How many times may the authority execute?

For 0.7.0, TramAI should keep:

```text
SINGLE_USE
```

as the normal and required initial contract for side-effecting tool continuation.

A future reusable/standing authorization model is a separate capability and is **not** implied by no-expiry support.

---

## 4. Critical invariants for no-expiry approval

An indefinite lifetime must not become ambient or reusable permission.

The following must all remain true:

```text
noExpiry != reusableAuthority
```

```text
noExpiry != mutableArguments
```

```text
noExpiry != permissionToCallTheToolForever
```

```text
noExpiry != bypassCurrentHardSafetyControls
```

```text
noExpiry + SINGLE_USE
    = one exact approved action may be consumed once without a wall-clock deadline
```

This is the primary safe use case TramAI should support.

---

## 5. Exact-action binding

Approval authority must remain cryptographically/logically bound to what was actually reviewed.

At minimum, an executable approval should be attributable to safe immutable identity for the relevant fields, for example:

```text
approvalId
workloadId / workloadVersion
operation identity/fingerprint
tool identity/version where applicable
tool-call identity
arguments digest
workflow definition/configuration identity where relevant
tenant / authority scope
classification identity/source where relevant
policy/configuration identity where required
```

The exact retained evidence must follow TramAI privacy/minimization rules; raw sensitive arguments are not required merely to prove binding.

If an argument or authoritative identity changes, the existing approval must not silently authorize the new action.

Example:

```text
approved:
schedulePayment(amount = 100)

attempted later:
schedulePayment(amount = 1_000)

=> NOT THE SAME AUTHORITY
=> fresh governance / approval required
```

---

## 6. Runtime revalidation after approval

Human approval is one authority boundary, not a permanent override of runtime safety.

Even when `ExecutionAuthorityLifetime = INDEFINITE`, execution may need to revalidate non-negotiable controls such as:

- tool still registered/enabled;
- tenant/workload still authorized;
- emergency deny/revocation;
- actor/service authority still valid where required;
- tool identity/version still compatible;
- exact arguments digest still matches;
- idempotency/consumption state still safe;
- continuation has not already been claimed/completed/cancelled;
- policy explicitly marked as execution-time mandatory.

Example:

```text
Day 1
human approves exact payment

Day 2
security policy disables payment tool

Day 3
resume

=> approval still historically APPROVED
=> execution denied by current hard safety rule
=> no tool execution
```

The audit trail must distinguish:

```text
human decision = APPROVED
execution authority = INVALIDATED / DENIED AT EXECUTION
```

rather than rewriting history to say the human denied it.

---

## 7. Relationship to tool effect and risk

Approval lifetime should be policy-driven and may use tool metadata as an input.

The 0.7.0 architecture already preserves future effect categories such as:

```text
READ_ONLY
REVERSIBLE
COMPENSATABLE
IRREVERSIBLE
UNKNOWN
```

A minimal effect/risk descriptor can help choose safe defaults:

```text
READ_ONLY
    -> may permit INDEFINITE + SINGLE_USE

IRREVERSIBLE
    -> bounded lifetime by default

UNKNOWN
    -> bounded lifetime / fail conservative
```

But TramAI should not hard-code a simplistic equation such as:

```text
READ_ONLY => never expires
```

Organization/environment/workload policy remains authoritative.

Conceptually:

```text
approvalLifetime = policy(
    toolEffect,
    toolRisk,
    classification,
    workload,
    environment,
    organization,
    business context,
)
```

No generic expression-language policy DSL is required for this slice.

---

## 8. Suggested policy model

Exact API names are illustrative.

Conceptually:

```kotlin
approvalPolicy {
    pendingDecisionLifetime = ApprovalLifetime.until(10.minutes)
    executionAuthorityLifetime = ApprovalLifetime.until(10.minutes)
    consumption = ApprovalConsumption.SingleUse
}
```

For a low-risk durable action:

```kotlin
approvalPolicy {
    pendingDecisionLifetime = ApprovalLifetime.indefinite()
    executionAuthorityLifetime = ApprovalLifetime.untilRevoked()
    consumption = ApprovalConsumption.SingleUse
}
```

The key architecture is more important than syntax:

```text
policy
  ↓
resolved approval-lifetime contract
  ↓
approval record + continuation authority
  ↓
audit/reconstruction
```

---

## 9. Replacement still matters

Configurable lifetime does not eliminate the need for replacement.

A bounded approval can expire. An indefinite approval can later become unusable through revocation, changed input, changed policy, changed action identity, or other invalidation.

The first public implementation should still keep replacement deliberately narrow:

```text
EXPIRED
```

but the architecture should support future reasons:

```text
ApprovalReplacementReason
  EXPIRED            // initial
  POLICY_INVALIDATED // later
  REQUEST_CHANGED    // later
  ACTION_CHANGED     // later
  OPERATOR_REISSUE   // later, policy controlled
```

Do not define replacement as an expiry-only concept internally if that would prevent these future reasons.

---

## 10. Replacement never reopens the old approval

Rejected model:

```text
TIMED_OUT
   │
   └─► PENDING     ❌
```

Preferred model:

```text
Approval A
status = TIMED_OUT
replacedBy = Approval B

Approval B
status = PENDING
replacementOf = Approval A
replacementReason = EXPIRED
```

`REPLACED` does not need to become an `ApprovalStatus`.

Core invariant:

> **Replacement creates new authority; it never revives expired authority.**

---

## 11. Application vs TramAI responsibility

### Application owns

The application decides:

- whether the business operation may be attempted again;
- which current document/request/business facts should be used;
- whether upstream model inference or analysis must rerun;
- whether the proposed action is still appropriate;
- business-specific duplicate-processing rules;
- application notifications/reminders.

### TramAI owns

TramAI should own:

- approval lifetime contract resolution;
- exact-action authority binding;
- single-use consumption semantics;
- proof that old authority is no longer usable;
- continuation fencing/non-claimability;
- replacement creation/registration;
- durable old → new lineage;
- optimistic-concurrency/race safety;
- authorization of operator replacement/revocation controls;
- typed evidence and reconstruction;
- prevention of stale continuation reuse.

---

## 12. Fresh authority, not stale continuation cloning

Unsafe abstraction:

```kotlin
reissueApproval(oldApprovalId)
```

if it means:

```text
load old continuation
copy old action/arguments
assign new lifetime
make it executable again
```

The original action may be stale because any of the following changed:

- source input/document;
- classification;
- policy;
- actor/tenant authorization;
- tool metadata/version;
- workload/workflow version;
- provider/model result;
- business data;
- idempotency state;
- external system state.

Preferred conceptual boundary:

```kotlin
replaceApproval(
    previousApprovalId = oldApprovalId,
    request = freshApprovalRequest,
    reason = ApprovalReplacementReason.EXPIRED,
)
```

The replacement's lifetime is **re-evaluated from current policy**.

It may therefore be:

```text
bounded
until revoked
indefinite
```

It is no longer correct to require every replacement to receive a wall-clock deadline.

The old approval's historical lifetime/deadline remains immutable evidence.

---

## 13. Continuation safety

After replacement:

```text
old approval       = terminal / historical
old continuation   = terminal or non-claimable
new approval       = independent approval
new continuation   = independent fresh continuation where applicable
```

Required invariant:

```text
replace(A) -> B

claimForExecution(A.continuation) = impossible
```

Even after B is approved, A can never regain execution authority.

Sensitive historical tool arguments must not be copied for replacement convenience.

---

## 14. Concurrency and race safety

Representative race:

```text
T1 approver clicks APPROVE
T2 operator clicks REISSUE
T3 expiry/revocation boundary occurs
```

Exactly one authoritative path may win.

Required properties:

- version-gated transition/replacement;
- no double replacement from one authority generation;
- no approve-after-terminal replacement path;
- no replacement after successful consumption unless a future explicit retry contract allows it;
- no two viable continuations;
- deterministic conflict result for losing callers.

If approval/consumption wins first, replacement must fail rather than create competing authority.

---

## 15. Replacement lineage

Replacement relationships must be durable and queryable.

Conceptual evidence:

```text
ApprovalReplacement
  previousApprovalId
  replacementApprovalId
  reason
  requestedBy
  requestedAt
  workloadId
  workflowRunId / correlationId where applicable
  previousContinuationIdentity
  replacementContinuationIdentity where applicable
  previousLifetimePolicyIdentity
  replacementLifetimePolicyIdentity
  policy/configuration identity where safe/useful
```

Useful query directions:

```text
A.replacedBy -> B
B.replacementOf -> A
```

The initial slice does not need arbitrary approval graphs.

---

## 16. Audit and semantic evidence

The control plane should record typed semantic events for both lifetime and replacement behavior.

Possible events:

```text
ApprovalCreated
ApprovalApproved
ApprovalTimedOut
ApprovalAuthorityExpired
ApprovalAuthorityRevoked
ApprovalExecutionRevalidationFailed
ApprovalReplacementRequested
ApprovalReplaced
ApprovalReplacementRejected
ApprovalConsumed
```

Names are illustrative.

Safe evidence should make it possible to answer:

```text
What lifetime policy applied?
Was there a wall-clock deadline?
Was the approval single-use?
Was it revoked or expired?
Which exact action was bound?
Was hard safety revalidated before execution?
Was it replaced?
Which approval replaced it?
Was the tool executed exactly once?
```

Do not duplicate raw sensitive tool arguments merely for observability.

---

## 17. Governance reconstruction

Example bounded flow:

```text
Tool proposal
    ↓
Approval A created
    ↓
Approval A TIMED_OUT
    ↓
Replacement requested
    ↓
Approval B created with current lifetime policy
    ↓
Approval B approved
    ↓
execution-time hard-safety revalidation
    ↓
Continuation B claimed
    ↓
Tool execution once
```

Example indefinite single-use flow:

```text
Tool proposal
    ↓
Approval A created
pending lifetime = INDEFINITE
    ↓
Approved two days later
execution lifetime = UNTIL_REVOKED
consumption = SINGLE_USE
    ↓
hard-safety revalidation
    ↓
Tool execution once
    ↓
consumed
```

Reconstruction must not infer expiry where none existed or imply reusable permission from an indefinite lifetime.

---

## 18. Runtime control and RBAC

Approval lifecycle controls must use runtime authority boundaries, not direct store mutation.

Conceptually:

```text
Dashboard/API
   ↓
typed approval command
   ↓
authentication
   ↓
authorization/RBAC
   ↓
runtime approval authority
   ↓
versioned operation
   ↓
typed outcome/evidence
```

Potential controls over time:

```text
approve
deny
replace expired approval
revoke execution authority      // if supported by the selected lifetime mode
```

Potential typed outcomes include:

```text
REPLACED
NOT_EXPIRED
ALREADY_REPLACED
ALREADY_DECIDED
ALREADY_CONSUMED
AUTHORITY_EXPIRED
AUTHORITY_REVOKED
ACTION_BINDING_MISMATCH
VERSION_CONFLICT
NOT_AUTHORIZED
REPLACEMENT_REQUEST_INVALID
```

---

## 19. Dashboard 2.0 experience

The dashboard must show the actual lifetime contract instead of assuming every approval has an expiry timestamp.

Examples:

```text
Payment approval
Status: PENDING
Decision deadline: 10:27
Execution authority after approval: 10 minutes
```

```text
Document-read approval
Status: PENDING
Decision deadline: No expiry
Execution authority after approval: Until revoked
Consumption: Single use
```

Expired flow:

```text
Payment approval
Status: TIMED_OUT
Expired: 3 minutes ago

[ Reissue approval ]
```

After replacement:

```text
Approval A
Status: TIMED_OUT
Replacement: AP-1847

AP-1847
Status: PENDING
Lifetime: current policy
```

The UI must never imply that A was reopened or its historical deadline changed.

---

## 20. Interaction with required-tool contracts

A required-tool operation may require approval.

A required-tool contract is satisfied only when the required governed tool transition actually succeeds.

Therefore:

```text
required tool proposed
    ↓
approval pending indefinitely
```

is not terminal success.

Likewise:

```text
required tool proposed
    ↓
approval expired
    ↓
replacement requested
```

is not terminal success.

Only successful authorized execution of the required tool satisfies the tool invocation contract.

Approval lifetime never bypasses:

- tool policy;
- permission;
- schema validation;
- idempotency;
- current hard-safety controls;
- side-effect safety.

---

## 21. Interaction with learning traces

Learning traces should distinguish:

```text
approval lifetime mode
approval decision
execution revalidation
expiry/revocation
replacement lineage
consumption outcome
```

This is valuable evaluation metadata, but raw approval/tool content remains subject to the learning-trace privacy gate.

No-expiry approval does not imply indefinite retention of raw training data.

---

## 22. Suggested initial public contract

Exact APIs remain open, but the first public model should expose the semantics explicitly rather than hiding them behind one `timeoutMillis`.

Conceptually:

```text
ApprovalLifetime
  Until(timestamp)
  UntilRevoked
  Indefinite

ApprovalConsumption
  SingleUse
```

Potential approval request policy:

```text
pendingDecisionLifetime
executionAuthorityLifetime
consumption
```

Replacement remains:

```text
ReplaceExpiredApprovalCommand
  previousApprovalId
  expectedVersion
  freshApprovalRequest
  requestedBy / authorization context
  reason = EXPIRED
```

Do not add a generic lifecycle expression language.

---

## 23. Persistence requirements

Supported stores must persist enough information to preserve semantics across restart.

At minimum:

- approval decision status;
- resolved pending lifetime mode/deadline where applicable;
- resolved execution-authority lifetime mode/deadline where applicable;
- consumption state;
- exact-action identity/digest required for safe claim;
- revocation/invalidation state where supported;
- replacement lineage;
- old-continuation non-claimability;
- policy/configuration identity needed for reconstruction.

Equivalent behavior should be proven for reference/in-memory, file, and JDBC implementations where those stores support the approval contract.

---

## 24. Deterministic discriminator tests

Minimum tests should include:

### A. Bounded pending expiry

```text
PENDING + UNTIL(t)
now >= t
approve
=> rejected / TIMED_OUT path
```

### B. Indefinite pending approval

```text
PENDING + INDEFINITE
advance clock substantially
approve
=> allowed if all other authority checks pass
```

### C. Indefinite does not mean reusable

```text
APPROVED
execution lifetime = INDEFINITE
consumption = SINGLE_USE
execute once
execute again
=> second execution rejected
```

### D. Exact argument binding

```text
approve argsDigest=A
attempt execution argsDigest=B
=> rejected
```

### E. Current hard deny wins

```text
approval remains valid
current mandatory safety policy disables tool
resume
=> denied
=> zero tool execution
```

### F. Expired replacement

```text
A expires
replace A -> B
A remains TIMED_OUT
B is independent PENDING
```

### G. Replacement recomputes lifetime

```text
A used old 90-second policy
replace after configuration change
B resolves current lifetime policy
A historical lifetime unchanged
```

### H. Replacement may be no-expiry

```text
A bounded and expired
current policy permits INDEFINITE + SINGLE_USE
replace A -> B
B has INDEFINITE lifetime
A remains historical/terminal
```

### I. Old continuation never resumes

```text
replace A -> B
claim A
=> impossible
=> zero tool execution
```

### J. Approval/replacement race

```text
approve A || replace A
=> exactly one authoritative path
=> never two viable continuations
```

### K. Persistence parity

```text
persist/restart
=> lifetime mode, consumption, action binding, replacement lineage preserved
```

### L. Reconstruction

```text
reconstruct
=> accurately shows lifetime mode, expiry/revocation, replacement, decision, consumption
```

Mutation tests should kill implementations that:

- treat `INDEFINITE` as reusable;
- skip arguments-digest binding;
- skip hard-safety revalidation;
- reactivate old continuation during replacement;
- lose replacement lineage;
- replace current lifetime policy with historical defaults.

---

## 25. Security invariants

```text
terminalApprovalCanBecomePendingAgain = false
```

```text
noExpiryImpliesReusableAuthority = false
```

```text
noExpiryAllowsArgumentMutation = false
```

```text
singleUseCanExecuteTwice = false
```

```text
currentHardDenyCanBeBypassedByOldApproval = false
```

```text
replacementCreatesFreshApprovalIdentity = true
```

```text
oldContinuationExecutableAfterReplacement = false
```

```text
replacementCopiesSensitiveHistoricalArgumentsImplicitly = false
```

```text
replacementLifetimeIsResolvedFromCurrentPolicy = true
```

```text
replacementHistoryIsReconstructable = true
```

---

## 26. P1 acceptance criteria

This roadmap slice is complete when:

1. approval lifetime is no longer structurally hard-coded as one universal timeout;
2. the runtime distinguishes pending-decision lifetime, execution-authority lifetime, and consumption;
3. at least bounded and indefinite/until-revoked single-use semantics are representable without weakening authority binding;
4. no-expiry approval cannot execute twice or mutate approved arguments;
5. execution can revalidate explicit hard safety controls before consuming old approval authority;
6. expired approvals remain terminal;
7. an authorized caller can request an expired-approval replacement through a typed runtime boundary;
8. replacement uses a fresh explicit request and recomputes current lifetime policy;
9. the old continuation cannot execute after replacement;
10. durable old → new lineage survives supported persistence/restart;
11. approve/replace/consume races resolve deterministically and fail closed;
12. semantic evidence/reconstruction show lifetime, invalidation, replacement, and consumption correctly;
13. Dashboard 2.0 exposes actual lifetime semantics through runtime APIs rather than assuming every approval has a deadline.

---

## 27. Initial-slice non-goals

Do not require:

- reusable/standing approvals;
- generic long-lived delegated authority;
- reopening `TIMED_OUT` approvals;
- retrying denied approvals;
- replacing already consumed approvals;
- arbitrary approval graphs;
- generic lifecycle-rule/ABAC expression language;
- automatic cloning of historical arguments;
- automatic workflow/model rerun without application intent;
- notification/email infrastructure;
- business-specific duplicate-processing logic;
- generic compensation/rollback;
- approval delegation/escalation language;
- SLA/reminder scheduler.

---

## 28. Roadmap priority

This capability remains **P1**.

P0 already requires fail-closed execution authority and must not permit an expired, revoked, mismatched, or already-consumed continuation to execute.

P1 improves the product by making lifetime semantics realistic and operationally recoverable:

```text
P0 safety
invalid authority cannot execute

P1 lifecycle
approval lifetime can be policy-driven
+ exact authority can be durable without becoming reusable
+ expired authority can be safely replaced
```

If implementation uncovers a current path where expired/revoked/mismatched/already-consumed approval authority can execute, that defect is P0 correctness/security independent of this P1 feature.

---

## 29. Product statement

> **Approval lifetime follows policy. Long-lived authority stays exact and single-use. Replacement creates fresh authority without rewriting history.**
