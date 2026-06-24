# Sovereignty Milestone — Closure Mini Roadmap

> **Context:** This document is the tactical closure plan for the Sovereignty milestone.
> The high-level Enterprise Roadmap is at [`ROADMAP.md`](../ROADMAP.md).
> This plan defines how we close the Sovereignty chapter cleanly, not how we build the next one.

---

## 1. Current State

With [PR #91](https://github.com/dev-tramai/tramai/pull/91) merged, the Sovereignty roadmap is functionally complete.

The repo now has:

| Capability | Status |
|---|---|
| File-backed sovereign persistence | ✅ |
| JDBC/PostgreSQL persistence | ✅ |
| Approval store | ✅ |
| Suspended invocation store | ✅ |
| Approval continuation store | ✅ |
| Audit store | ✅ |
| Audit outbox store | ✅ |
| Transactional approval denial + audit outbox | ✅ |
| Worker lease coordination | ✅ |
| Spring Boot JDBC auto-config | ✅ |
| Production deployment runbook | ✅ |
| Regulated claim triage executable E2E | ✅ |
| CI-backed E2E proof | ✅ |

Recent merged PRs show this sequence clearly: JDBC approval store, suspended invocation store, continuation store, audit store, outbox store, auto-config, restart E2E, worker leases, transactional mutation boundary, production runbook, and regulated E2E proof.

---

## 2. The Honest Answer

We are **85–90% done**.

Not because features are missing, but because the roadmap needs closure discipline.

The current RC boundary doc still says the JDBC stack is not fully reflected in the RC boundary and explicitly calls out that the document is stale compared to the current implementation.

Also, the only meaningful roadmap item still not completed:

> **Key rotation**

The post-RC roadmap lists key rotation as the remaining uncompleted item, while JDBC persistence, worker coordination, deployment guide, and regulated workflow examples are already checked off.

---

## 3. Recommended Closing Plan

### PR #92 — Sovereignty Closure Boundary

**Goal:** freeze the meaning of "Sovereign Runtime complete".

This should be a docs + verification PR.

**Changes:**

- Rename/extend the RC boundary into a Sovereign Runtime Closure Boundary.
- Remove the stale note saying the RC doc does not reflect JDBC.
- Define what is now included:
  - policy routing
  - DLP-aware routing
  - file persistence
  - JDBC persistence
  - approvals
  - audit chain
  - audit outbox
  - worker leases
  - observability
  - deployment runbook
  - executable regulated scenario
- Define what is explicitly **not** included:
  - production UI
  - reviewer dashboard
  - Maven Central release
  - full REST control plane
  - production certification
  - broad workflow DSL
  - key rotation, unless you decide to implement it now

**Why this matters:** It prevents the roadmap from becoming infinite.

### PR #93 — Final Sovereign Verification Gate

**Goal:** create one canonical command that says:

```
./gradlew verifySovereignRuntimeClosure
```

It should depend on:

- `verifySovereignRuntimeReleaseCandidate`
- `:examples:spring-sovereign-starter:e2eTest`
- release evidence index
- zero-egress evidence
- regulated claim triage docs link validation
- production runbook presence
- status matrix consistency

This is the "no more vibes" PR. After this, closure is machine-verifiable.

### PR #94 — API Stabilization Pass

**Goal:** decide what is public, experimental, internal, and test-only.

This is important because `docs/STATUS.md` still marks many areas as "Implemented / evolving", including JDBC persistence, audit outbox, orchestration, MCP, scheduling, HTTP server, and multi-tenancy.

For Sovereignty closure, you do not need to stabilize all TramAI APIs.

You only need to stabilize the **Sovereign Runtime surface**:

- `ApprovalStore`
- `SuspendedInvocationStore`
- `ApprovalContinuationStore`
- `AuditStore`
- `SovereignOpsAuditOutboxStore`
- `SovereignOpsApprovalMutationStore`
- worker lease SPI/config
- Spring Boot configuration properties
- operational status/health contracts

Everything else can remain evolving.

### Optional PR #95 — Key Rotation Decision

This is the fork in the road.

**Option A — Defer key rotation**

Add a clear statement:

> Key rotation is deferred to Sovereign Runtime GA / production certification and is not required for the current Sovereignty roadmap closure.

This is acceptable if the goal is RC / enterprise proof, not production-certified security.

**Option B — Implement minimal key rotation**

- key ID in encrypted records
- active key for writes
- old keys allowed for reads
- no automatic re-encryption yet
- runbook section for rotation procedure

This is more work and can easily become 3–5 PRs.

**Recommendation:** defer key rotation explicitly. Do not let it drag the roadmap into another rabbit hole.

---

## 4. When Are We Closing It?

### Realistic answer

Close the Sovereignty roadmap after **3 more PRs**:

| PR | Name | Purpose |
|---|---|---|
| #92 | Sovereignty closure boundary | Freeze scope |
| #93 | Final closure verification gate | Machine-check closure |
| #94 | Sovereign API stabilization pass | Mark stable vs evolving |

Then close it.

### Timeline

Given current pace:

- **Aggressive:** close by Friday, 26 June 2026
- **Safer:** close by early next week
- **Do not extend beyond:** PR #95

If key rotation is pulled into scope, the roadmap does not close this week. It becomes another production-security roadmap.

---

## 5. What Comes After Sovereignty

Once closed, the next roadmap should not be called Sovereignty anymore.

It should become one of these:

### Option 1 — TramAI Workflow Runtime

**Focus:**
- workflow DSL
- suspension/resume ergonomics
- state machine expression
- approval gateway abstraction
- developer experience

This is the most natural next step because PR #91 proves the workflow is possible, but still through a test harness.

### Option 2 — Enterprise Integration Layer

**Focus:**
- REST control plane
- reviewer UI hooks
- external identity/roles
- audit export
- admin APIs
- deployment templates

This is more product-facing.

### Option 3 — Sovereign Runtime GA

**Focus:**
- key rotation
- API compatibility
- migration guarantees
- production certification
- Maven Central release
- security review

This is more formal and heavier.

---

## 6. Recommendation

Close Sovereignty as:

> **Sovereign Runtime RC+ / Enterprise Proof Complete**

Do not call it GA yet.

Then move to:

> **Post-Sovereignty Roadmap: Workflow Ergonomics + API Stabilization**

### Immediate next task

**[PR #92](https://github.com/dev-tramai/tramai/pull/92)** — `docs(sovereign): define Sovereign Runtime closure boundary`

This PR should say clearly:

- what is complete
- what is deferred
- what command proves closure
- what roadmap comes next

---

## 7. Summary

- PR #91 was the last big feature proof.
- The remaining work is not feature-building; it is **closure**.
- Sovereignty should close after **3 small PRs**.
- Key rotation should be explicitly deferred unless you want another long roadmap.
- Target closure: **Friday 26 June 2026** if disciplined, otherwise **early next week**.
- Next task: **PR #92** — Sovereign Runtime closure boundary.

---

*Last updated: 2026-06-24*
