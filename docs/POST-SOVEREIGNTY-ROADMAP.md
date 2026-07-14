# Post-Sovereignty TramAI Roadmap

> **Status:** Active roadmap — defines the next phase after Sovereign Lab Evidence Handoff v1.
>
> Release train: TramAI 0.5.0  
> Development version: 0.5.0-SNAPSHOT  
> Baseline: TramAI 0.4.0 completed and published the Sovereign Runtime and Sovereign Evidence Handoff milestones.

---

## Executive Decision

**Sovereign Lab Evidence Handoff v1 is complete.**

PR #162 closed the signature-verification handoff layer without adding runtime behavior, signing automation, key management, attestation, upload flow, evidence-truth validation, regulatory certification, or production-readiness claims. The evidence-archive verification chain now covers safe extraction, SHA-256 sidecar validation, optional detached signature verification, negative fixtures, deterministic export regression, and reviewer handoff.

The next roadmap pivots back to TramAI core product value:

**Make governed AI workflows easy to build, test, debug, review, and operate on the JVM.**

The next phase is not more archive hardening. The next phase is:

**Workflow Ergonomics + API Stability + Structured Output Contracts + Runtime Evidence**

---

## Strategic Objective

TramAI should become:

> A Kotlin-first, Java-friendly runtime for governed AI workflows, with typed contracts, structured output repair, human approval, policy enforcement, auditability, and evidence export.

### What Changes After Sovereignty

| Before | Now |
|--------|-----|
| Can TramAI prove a sovereign/local evidence chain structurally? | Can a JVM team build a real AI workflow with TramAI faster, safer, and more clearly than with generic AI frameworks? |

---

## Roadmap Overview

| Phase | Epic | Purpose | Priority | Status |
|---|---|---|---|---|
| 0 | Roadmap/RFC | Align scope and stop sovereignty drift | P0 | ✅ Complete |
| 1 | Workflow API Stability | Define stable/preview/internal workflow APIs | P0 | ✅ Complete |
| 2 | Structured Output Contracts | Make typed contract lifecycle explicit and tested | P0 | ✅ Complete |
| 3 | Workflow Ergonomics | Improve developer experience for real workflows | P0 | ✅ Complete |
| 4 | Approval & Human Gates | Make approval flows easier and safer to use | P1 | ✅ Complete |
| 5 | Runtime Evidence Export | Connect real runtime decisions to evidence artifacts | P1 | ✅ Complete — PR #200 adds dedicated tool.permission family |
| 6 | Tool/MCP Governance | Govern tool calls and future MCP integrations | P2 | ✅ Complete — PR #201 adds runnable tool-governance example |
| 7 | Product Narrative | Prepare docs/articles/talk material | P2 | ✅ Complete |
| 8 | Deferred Future Tracks | Release Console, compliance mapping, attestation | P3 | ⏸ Deferred |

---

## Phase 0 — Roadmap/RFC

### Epic 0: Post-Sovereignty Roadmap Definition

**Goal:** Create the canonical roadmap document that declares Sovereignty complete and defines the next focus.

**Deliverable:** This document.

**Acceptance criteria:**
- [x] Roadmap document exists.
- [x] It explicitly says sovereignty handoff v1 is complete.
- [x] It lists next epics.
- [x] It lists non-goals.
- [x] It does not claim production readiness, certification, legal compliance, or EU AI Act conformity.
- [x] README.md links to it.
- [x] CHANGELOG.md records it.
- [x] `./gradlew check` passes.

---

## Phase 1 — Workflow API Stability

### Epic 1: Workflow API Boundary

**Goal:** Define which workflow APIs are stable, preview, internal, or deferred, preventing the project from growing by accident.

**Status:** ✅ Boundary documented in [docs/workflow-api-stability-boundary.md](workflow-api-stability-boundary.md).

**Why it matters:** TramAI already has many serious runtime pieces — approvals, audit, policy, persistence, local routing, evidence. The next question is: which APIs can users safely build against?

**Proposed PRs:**

| PR | Title | Purpose | Status |
|----|-------|---------|--------|
| #165 | docs(workflow): define workflow API stability boundary | Document stable/preview/internal APIs | ✅ Merged |
| #166 | test(workflow): verify workflow API boundary | Add Gradle/API boundary guard | ✅ Merged |
| #167 | docs(workflow): add workflow lifecycle model | Explain workflow states and transitions | ✅ Merged |

**Tasks:**
1. ✅ Inventory workflow-facing APIs
2. ✅ Classify APIs as stable/preview/internal/deferred
3. ✅ Add a machine-checked API boundary guard
4. ✅ Document lifecycle: request → policy → provider/tool → approval → audit → result
5. ✅ Add "allowed claims / forbidden claims" for workflow stability

**Acceptance criteria:**
- API boundary document exists.
- Stable, preview, internal, deferred categories are explicit.
- Gradle check fails if required API boundary docs disappear.
- No accidental promotion of internal APIs.
- No production-readiness overclaim.

---

## Phase 2 — Structured Output Contracts

### Epic 2: Contract Lifecycle and Validation

**Goal:** Make TramAI's typed structured-output story precise and test-backed.

**Product thesis:** TramAI should make typed JVM structured output safer and more testable than hand-written prompt/schema glue.

**Proposed PRs:**

| PR | Title | Purpose | Status |
|----|-------|---------|--------|
| #168 | docs(structured-output): document contract generation lifecycle | Explain when/how contracts are generated | ✅ Merged |
| #169 | test(structured-output): verify contract evolution behavior | Prove fields/validators are picked up | ✅ Merged |
| #170 | docs(structured-output): define validator extension model | Clarify built-in vs future custom validators | ✅ Merged |
| #171 | test(structured-output): harden repair feedback loop | Prove repair messages are useful and deterministic enough | ✅ Merged |
| #172 | test(java): add structured-output Java boundary smoke | Ensure Java-friendly path works | ✅ Merged |

**Tasks:**
1. ✅ Document contract generation source: Kotlin type / annotations / validators
2. ✅ Clarify whether contracts are rebuilt per call or cached
3. ✅ Add tests for new field added to return type
4. ✅ Add tests for `@AIRange` and `@AIMinItems` behavior
5. ✅ Define extension point for custom validators, even if not implemented yet
6. ✅ Document repair feedback: what assistant sees, what user sees, what gets retried
7. ✅ Add Java-facing smoke test for structured output

**Acceptance criteria:**
- Contract lifecycle is documented.
- Contract evolution is test-covered.
- Built-in validators are documented.
- Custom validator extension point is either documented as deferred or implemented.
- Repair loop has regression tests.
- Java-friendly structured-output usage is covered.
- No unsupported claims about perfect validation or guaranteed model correctness.

---

## Phase 3 — Workflow Ergonomics

### Epic 3: Developer Experience for Governed Workflows

**Goal:** Make the "first real workflow" easier to write.

A new TramAI user should understand:
- How do I define a workflow?
- How do I enforce policy?
- How do I request approval?
- How do I persist/audit it?
- How do I test it?
- How do I know what failed?

**Proposed PRs:**

| PR | Title | Purpose | Status |
|----|-------|---------|--------|
| #173 | docs(workflow): add governed workflow quickstart | End-to-end conceptual quickstart | ✅ Merged |
| #174 | example(workflow): add minimal governed workflow example | Runnable example | ✅ Merged |
| #175 | test(workflow): add workflow failure diagnostics smoke | Prove errors are explainable | ✅ Merged |
| #176 | docs(workflow): add testing guide for governed workflows | How to test without real model calls | ✅ Merged |
| #177 | docs(workflow): add workflow troubleshooting guide | Common failures and fixes | ✅ Merged |

**Tasks:**
1. Pick one simple example domain
2. Add a minimal workflow using typed input/output
3. Add policy gate
4. Add optional human approval gate
5. Add audit/persistence note
6. Add fake provider or no-model test path
7. Add troubleshooting section

**Acceptance criteria:**
- A developer can run the example without external model credentials.
- The example shows typed contract use.
- The example shows policy enforcement.
- The example shows at least one failure path.
- The example links to structured-output and approval docs.
- It avoids claiming production readiness.
- `./gradlew check` passes.

---

## Phase 4 — Approval & Human Gates

### Epic 4: Approval Workflow Ergonomics

**Goal:** Make human approval not just powerful, but easy to integrate.

**Proposed PRs:**

| PR | Title | Purpose | Status |
|----|-------|---------|--------|
| #178 | docs(approval): add approval workflow ergonomics guide | Explain common approval patterns | ✅ Merged |
| #179 | example(approval): add approved/denied resume example | Runnable approval scenario | ✅ Merged |
| #180 | test(approval): verify approval decision evidence | Tie approval decisions to audit/evidence | ✅ Merged |
| #181 | test(approval): verify repeat denial evidence | Complete denied duplicate-decision evidence coverage | ✅ Merged |
| #182 | docs(approval): define approval failure taxonomy | Expired, denied, missing role, invalid actor, etc. | ✅ Merged |

**Tasks:**
1. Document approval request lifecycle
2. Document approved / denied / expired behavior
3. Add one example with role-based approval
4. Add tests for approval failure diagnostics
5. Document evidence emitted by approval decisions
6. Clarify what TramAI does not decide: legal/business correctness

**Acceptance criteria:**
- Approval lifecycle is documented.
- Example shows approved and denied paths.
- Expired/missing/invalid approval cases are documented.
- Tests cover approval decision output.
- Audit/evidence relationship is described.
- No claim that human approval proves correctness or compliance.

---

## Phase 5 — Runtime Evidence Export

### Epic 5: Runtime Decisions → Evidence Artifacts

**Goal:** Bridge the sovereignty work back to real runtime behavior. Real policy/approval/provider decisions can be exported into reviewable evidence.

**Proposed PRs:**

| PR | Title | Purpose | Status |
|----|-------|---------|--------|
| #183 | docs(evidence): define runtime evidence export model | Design doc | ✅ Merged |
| #184 | test(evidence): export policy decision evidence | Export allow/deny/require-approval policy decisions | ✅ Merged |
| #185 | test(evidence): export approval decision evidence | Export approved/denied human approval decisions | ✅ Merged |
| #186 | test(evidence): export provider routing evidence | Export selected/fallback/blocked provider route decisions | ✅ Merged |
| #187 | docs(evidence): map runtime events to evidence bundle sections | Human-readable mapping | ✅ Merged |
| #199 | Wire runtime evidence into sovereign evidence bundles | Bundle writer, verifier rules, lifecycle | ✅ Merged |
| #200 | Add dedicated tool.permission runtime evidence family | Partition tool events from policy.decision | ✅ Merged |

**Tasks:**
1. Define runtime evidence record shape
2. Decide which events are exportable
3. Add policy decision export
4. Add approval decision export
5. Add provider routing decision export
6. Document evidence bundle section mapping for runtime decisions
7. Verify exported evidence is structurally checked, not truth-certified

**Acceptance criteria:**
- Runtime evidence model is documented.
- At least one real policy decision can be exported.
- Exported evidence appears in a bundle section.
- Verifier checks structure/digests but does not validate truth.
- Claim boundaries remain explicit.
- Existing sovereign evidence checks still pass.

---

## Phase 6 — Tool and MCP Governance

### Epic 6: Tool Permission and MCP Safety Model

**Goal:** Prepare TramAI for governed tool use and future MCP integration.

**Proposed PRs:**

| PR | Title | Purpose | Status |
|----|-------|---------|--------|
| #188 | docs(security): define tool permission model | Trust zones and permissions | ✅ Merged |
| #189 | docs(mcp): define MCP governance boundary | What MCP support should/should not mean | ✅ Merged |
| #190 | test(tooling): audit tool exposure policy decisions | Tool exposure audit events | ✅ Merged |
| #191 | test(tooling): prove fail-closed tool execution denial | Execution denial, ordering, retries, audit, evidence | ✅ Merged |
| #200 | feat(evidence): add dedicated tool.permission runtime evidence family | Partition tool events from generic policy.decision | ✅ Merged |
| #201 | example(tooling): add governed tool permission example | Three scenarios with ALLOW/DENY/REQUIRE_APPROVAL + tool.permission evidence | ✅ Merged |

**Tasks:**
1. Define trusted/internal/external tool classes
2. Define policy decision points for tools
3. Define approval-required tool classes
4. Audit tool exposure and execution policy decisions
5. Verify denied-tool execution through the generic policy audit path and dedicated tool.permission evidence path (PR #201 adds a runnable example)
6. Define MCP connector non-goals

**Acceptance criteria:**
- Tool permission model exists.
- MCP boundary is documented.
- Policy-denied tool calls are test-covered.
- Tool audit event exists or is designed.
- No claim that MCP integrations are automatically safe.

---

## Phase 7 — Product Narrative and Adoption

### Epic 7: Public-Facing Developer Story

**Goal:** Prepare material for README clarity, articles, conference talks, pilot discussions, and grant updates.

**Proposed PRs:**

| PR | Title | Purpose |
|----|-------|---------|
| #192 | [docs(product): define TramAI positioning](product/positioning.md) | ✅ Canonical thesis, audiences, boundaries, and messaging |
| #193 | docs(readme): rewrite README around governed workflows | ✅ Governed first-run story and adoption paths |
| #194 | [docs(article): draft governed JVM AI workflow article](articles/governed-ai-workflows-for-the-jvm.md) | ✅ Publishable article and companion talk outline |
| #195 | [docs(examples): add example selection guide](../examples/README.md) | ✅ Decision tree, prerequisites, capability depth, and learning paths |
| #196 | [docs(comparison): position TramAI alongside Spring AI and LangChain4j](comparison/jvm-ai-frameworks.md) | ✅ Dated, official-source comparison and selection guide |

**Tasks:**
1. Define one-sentence positioning
2. Define target users
3. Define "why not just Spring AI/LangChain4j?"
4. Add comparison table with careful wording
5. Add article draft
6. Add talk outline

**Acceptance criteria:**
- README explains TramAI in less than 30 seconds.
- README links to the right quickstart.
- Comparison avoids unfair or unverifiable claims.
- Article draft exists.
- Claims are guarded: no compliance/certification/production overreach.

---

**Outcome:** ✅ TramAI is easier to explain, adopt, evaluate, and position alongside established JVM AI frameworks.

---

## Phase 8 — Deferred / Optional Tracks

These are real possibilities but intentionally deferred. Each has a first-PR concept note ready for when the runtime/product foundation is stronger.

### Option A — Release Console

A control-plane concept for viewing workflows, policies, approvals, evidence, and release gates.

**First PR when started:** `docs(product): define TramAI Release Console concept`

### Option B — Compliance Mapping

Map TramAI runtime/evidence features to governance controls.

**First PR when started:** `docs(compliance): map TramAI evidence to governance-support controls`

**Boundary:** supporting evidence, not legal compliance proof.

### Option C — Signing / Attestation v2

Move from optional signature verification to a more formal signing/attestation model.

**First PR when started:** `docs(sovereign): define evidence attestation v2 non-goals`

---

## Recommended Execution Order

### Milestone 1 — Roadmap and API Boundary

| PR | Title |
|----|-------|
| #164 | docs(roadmap): add post-sovereignty TramAI roadmap | ✅ Merged |
| #165 | docs(workflow): define workflow API stability boundary | ✅ Merged |
| #166 | test(workflow): verify workflow API boundary | ✅ Merged |
| #167 | docs(workflow): add workflow lifecycle model | ✅ Merged |

**Outcome:** Clear post-sovereignty direction and stable API boundaries.

### Milestone 2 — Structured Output Contracts

| PR | Title | Status |
|----|-------|--------|
| #168 | docs(structured-output): document contract generation lifecycle | ✅ Merged |
| #169 | test(structured-output): verify contract evolution behavior | ✅ Merged |
| #170 | docs(structured-output): define validator extension model | ✅ Merged |
| #171 | test(structured-output): harden repair feedback loop | ✅ Merged |
| #172 | test(java): add structured-output Java boundary smoke | ✅ Merged |

**Outcome:** Typed contract story becomes a strong differentiator.

### Milestone 3 — Workflow Ergonomics

| PR | Title |
|----|-------|
| #173 | docs(workflow): add governed workflow quickstart |
| #174 | example(workflow): add minimal governed workflow example |
| #175 | test(workflow): add workflow failure diagnostics smoke |
| #176 | docs(workflow): add testing guide for governed workflows |
| #177 | docs(workflow): add workflow troubleshooting guide |

**Outcome:** A real developer can understand and run a governed workflow.

### Milestone 4 — Approval and Evidence Bridge

| PR | Title | Status |
|----|-------|--------|
| #178 | docs(approval): add approval workflow ergonomics guide | ✅ Merged |
| #179 | example(approval): add approved-denied resume example | ✅ Merged |
| #180 | test(approval): verify approval decision evidence | ✅ Merged |
| #181 | test(approval): verify repeat denial evidence | ✅ Merged |
| #182 | docs(approval): define approval failure taxonomy | ✅ Merged |
| #183 | docs(evidence): define runtime evidence export model | ✅ Merged |
| #184 | test(evidence): export policy decision evidence | ✅ Merged |
| #185 | test(evidence): export approval decision evidence | ✅ Merged |
| #186 | test(evidence): export provider routing evidence | ✅ Merged |
| #187 | docs(evidence): map runtime events to evidence bundle sections | ✅ Merged |
| #199 | feat(evidence): wire runtime decisions into sovereign evidence bundles | ✅ Merged |
| #200 | feat(evidence): add dedicated tool.permission runtime evidence family | ✅ Merged |

**Outcome:** Runtime decisions produce reviewable evidence.

### Milestone 5 — Tool Governance and Product Narrative

| PR | Title | Status |
|----|-------|--------|
| #188 | docs(security): define tool permission model | ✅ Merged |
| #189 | docs(mcp): define MCP governance boundary | ✅ Merged |
| #190 | test(tooling): audit tool exposure policy decisions | ✅ Merged |
| #191 | test(tooling): prove fail-closed tool execution denial | ✅ Merged |
| #192 | docs(product): define TramAI positioning | ✅ Merged |
| #193 | docs(readme): rewrite README around governed workflows | ✅ Merged |
| #194 | docs(article): draft governed JVM AI workflow article | ✅ Merged |
| #195 | docs(examples): add example selection guide | ✅ Merged |
| #196 | docs(comparison): position TramAI alongside Spring AI and LangChain4j | ✅ Merged |
| #200 | feat(evidence): add dedicated tool.permission runtime evidence family | ✅ Merged |

**Outcome:** ✅ TramAI is easier to explain, adopt, evaluate, and position alongside established JVM AI frameworks.

---

## Remaining 0.5.0 Sequence

The following work remains for the 0.5.0 development train before a release candidate can be declared:

| Item | Area | Description | Status |
|------|------|-------------|--------|
| Tool-governance usage example | Documentation | Add tool governance usage examples | ✅ Complete — PR #201 |
| Final 0.5.0 release readiness | Release | Declare RC, verify all acceptance criteria, publish | 🔧 Pending |

---

## Non-Goals

The following are intentionally not in this roadmap:

| Non-Goal | Rationale |
|----------|-----------|
| Production certification | Requires independent validation outside this roadmap |
| Legal / regulatory compliance | TramAI provides evidence support, not compliance proof |
| EU AI Act conformity | Would require legal review, not engineering scope |
| Security certification (SOC2, ISO 27001) | Organizational-level certification, not project scope |
| Evidence truth validation | Structural tamper-evidence only — verifiers check digests, not truth |
| Benchmark guarantees | Benchmarks are diagnostic, not performance commitments |
| Signing / attestation production system | Optional detached signatures exist; key management is deferred |
| Release Console | UI/control-plane concept deferred to phase 8+ |
| Compliance mapping framework | Deferred to phase 8+ |

---

## Claim Boundaries

This roadmap:

- ✅ Defines a clear post-sovereignty direction
- ✅ Describes API stability categories and boundaries
- ✅ Plans typed contract lifecycle documentation and testing
- ✅ Plans executable governed workflow examples
- ✅ Plans runtime evidence export (structural, not truth-certifying)
- ✅ Defers key management, compliance mapping, and Release Console
- ❌ Does not claim production readiness
- ❌ Does not claim legal compliance or certification
- ❌ Does not claim benchmark superiority
- ❌ Does not claim EU AI Act conformity

---

## Global Acceptance Criteria

This roadmap is successful when TramAI can demonstrate:

1. **Clear post-sovereignty direction** — Sovereignty v1 is complete, future work is not endless archive hardening.
2. **Stable workflow API story** — Stable/preview/internal/deferred APIs are defined, boundary checks prevent drift.
3. **Strong typed contract story** — Contract lifecycle documented, evolution tested, repair feedback tested, Java/Kotlin usage covered.
4. **Usable governed workflow path** — A developer can run a minimal governed workflow; policy/approval/audit concepts are visible; failure modes are understandable.
5. **Runtime evidence bridge** — At least one real policy or approval decision can be exported into evidence; evidence remains structural, not truth/certification.
6. **Careful public narrative** — README is clear, comparisons are fair, no legal/compliance/production overclaims.

---

## Moving from Roadmap to Implementation

Each phase epic moves from roadmap to implementation when:

1. The epic's acceptance criteria are documented and testable (not aspirational).
2. The first PR in the epic has been opened with explicit scope and non-goals.
3. PR descriptions reference this roadmap and state which phase/epic they implement.
4. `./gradlew check` passes on every PR.

---

## Related Documents

| Document | Purpose |
|----------|---------|
| [ROADMAP.md](../ROADMAP.md) | Original enterprise roadmap (superseded by this document) |
| [docs/STATUS.md](STATUS.md) | Current project status |
| [docs/sovereignty-closure-plan.md](sovereignty-closure-plan.md) | Sovereignty closure plan (historical) |

---

*Last updated: 2026-07-12*
