# Epic 9.1b — Module Overlap and Consolidation Review

**Track:** B (module architecture)
**Date:** 2026-08-26
**Scope:** Epic 9.1 Tasks 5–6 — review all 58 modules for overlapping responsibilities or insufficient independent value; recommend consolidation **only** where ownership or dependency clarity objectively improves.
**PR type:** review-only. No production code, no module deletion, no Gradle restructuring, no manifest reclassification.

---

## 1. Method

Every module received a disposition against six retention signals:

1. **Responsibility** — is the stated responsibility distinct?
2. **Independent extension point** — can it be extended/used alone?
3. **Dependency isolation** — what does it prevent leaking?
4. **Release/API independence** — do consumers select it separately?
5. **Lifecycle/ownership** — does it have its own owner and cadence?
6. **Rationale quality** — does the manifest rationale articulate a meaningful boundary?

A consolidation candidate requires **multiple signals aligned toward merging** AND a compatibility analysis for published artifacts. Small LOC alone is never sufficient — a 40-line module can be architecturally more valuable than a 4,000-line one.

Verdicts: **KEEP** (boundary justified), **CLARIFY** (boundary correct, rationale/docs/naming need fixing), **CONSOLIDATE** (merging measurably improves architecture; requires separate implementation PR).

---

## 2. Full disposition (58/58)

### 2.1 Core contracts

| Module | Pub | Verdict | Evidence |
|--------|-----|---------|----------|
| `:tramai-core` | published | KEEP | Zero-dependency contract root; all modules depend on it. Rationale specific. |
| `:tramai-bom` | published | KEEP | Release alignment platform; derived from manifest (B1). Distinct release role. |

### 2.2 Runtime execution

| Module | Pub | Verdict | Evidence |
|--------|-----|---------|----------|
| `:tramai-engine` | published | KEEP | Orchestration/retry/fallback owner. Rationale specific. |
| `:tramai-structured` | published | KEEP | Schema/extraction/deserialisation owner. Boundary enforced by guardrails. |
| `:tramai-orchestration` | published | KEEP | Workflow/checkpoint/resume semantics. |
| `:tramai-standalone` | published | KEEP | Framework-free entry point; distinct consumer choice (no Spring). |

### 2.3 Governance / security

| Module | Pub | Verdict | Evidence |
|--------|-----|---------|----------|
| `:tramai-security` | published | KEEP | Policy/approval/audit/evidence owner. |
| `:tramai-sovereign` | published | KEEP | Sealed governed runtime boundary; distinct deployment profile. |

### 2.4 Provider adapters

| Module | Pub | Verdict | Evidence |
|--------|-----|---------|----------|
| `:tramai-anthropic` | published | KEEP | Provider isolation; optional dependency (vendor SDK); independent consumer choice. |
| `:tramai-azure-openai` | published | KEEP | Same pattern. |
| `:tramai-bedrock` | published | KEEP | Same pattern. |
| `:tramai-deepseek` | published | KEEP | Same pattern. |
| `:tramai-gemini` | published | KEEP | Same pattern. |
| `:tramai-ollama` | published | KEEP | Same pattern. |
| `:tramai-openai` | published | KEEP | Same pattern. |

Providers A ↔ B are intentionally isolated: separate vendor SDKs, separate release cadences, separate consumer choice. Consolidating providers would merge incompatible dependency trees — the clearest case of physical isolation being the architecture.

### 2.5 Persistence

| Module | Pub | Verdict | Evidence |
|--------|-----|---------|----------|
| `:tramai-persistence-file` | published | KEEP | File-backed sovereign stores; published (consumers use directly). |
| `:tramai-persistence-jdbc` | internal | **CLARIFY** | See §3.1 — internal classification conflicts with its published starter exposing it via `api(...)`; inconsistent with the published file sibling. |

### 2.6 Framework integrations

| Module | Pub | Verdict | Evidence |
|--------|-----|---------|----------|
| `:tramai-spring-core` | published | KEEP | Profile-neutral service discovery; the unified integration core. |
| `:tramai-spring` | published | CLARIFY | Documented "legacy Spring facade over tramai-spring-core; not the onboarding entry point" (modules.md L22). Boundary is retained for back-compat, but naming/docs confuse (see §3.2). |
| `:tramai-spring-boot-starter` | published | KEEP | Unified composition starter (core + sovereign + providers). Distinct onboarding role. |
| `:tramai-spring-sovereign` | published | KEEP | Sovereign-into-Spring composition; distinct profile. |
| `:tramai-spring-provider-anthropic` | published | KEEP | Auto-config per provider; optionality (add only the provider you use). |
| `:tramai-spring-provider-ollama` | published | KEEP | Same. |
| `:tramai-spring-provider-openai` | published | KEEP | Same. |
| `:tramai-spring-secrets-aws` | published | KEEP | Secret-source isolation (AWS vs file vs Vault); independent choice. |
| `:tramai-spring-secrets-file` | published | KEEP | Same. |
| `:tramai-spring-secrets-vault` | published | KEEP | Same. |
| `:tramai-spring-boot-starter-local-provider-openai` | published | KEEP | Dev/smoke-only local provider; distinct purpose from real provider adapters. |
| `:tramai-spring-boot-starter-sovereign-ops` | published | KEEP | Sovereign ops composition root. |
| `:tramai-spring-boot-starter-sovereign-ops-actuator` | published | KEEP | Actuator endpoints; optional dependency isolation (health/monitoring opt-in). |
| `:tramai-spring-boot-starter-sovereign-ops-micrometer` | published | KEEP | Micrometer bridge; optionality. |
| `:tramai-spring-boot-starter-sovereign-ops-observability` | published | KEEP | OTel instrumentation; optionality. |
| `:tramai-spring-boot-starter-sovereign-ops-rest` | published | KEEP | REST control-plane; optionality. |
| `:tramai-spring-boot-starter-sovereign-persistence-file` | published | KEEP | Persistence starter; distinct backend choice. |
| `:tramai-spring-boot-starter-sovereign-persistence-jdbc` | published | **CLARIFY** | See §3.1 — published starter `api(project(":tramai-persistence-jdbc"))` exposes an internal module's classes on the consumer compile classpath. |

The sovereign-ops starter family (ops/actuator/micrometer/observability/rest) looks duplicative from a dependency graph but enforces exactly the optionality TramAI wants: a consumer picks only the ops surface they need, and each optional dependency (actuator, micrometer, OTel) stays out of the base classpath. This is §1.4's "starter ↔ core" intentional-thin-module category — retained.

### 2.7 Operations / observability

| Module | Pub | Verdict | Evidence |
|--------|-----|---------|----------|
| `:tramai-observability` | published | KEEP | Optional OTel integration; opt-in dependency. |
| `:tramai-platform` | published | KEEP | Composable runtime wiring (engine + server + ops). |
| `:tramai-server` | internal | CLARIFY | Generic rationale placeholder (see §3.3); boundary itself is justified (HTTP surface above orchestration/scheduler). |
| `:tramai-mcp` | internal | CLARIFY | Same — adapter role justified, rationale placeholder. |
| `:tramai-dashboard` | internal | CLARIFY | Same — UI packaging justified, rationale placeholder. |

### 2.8 Higher capabilities

| Module | Pub | Verdict | Evidence |
|--------|-----|---------|----------|
| `:tramai-embedding` | published | KEEP | Embedding generation; distinct capability. |
| `:tramai-memory` | published | KEEP | Conversation memory primitives. |
| `:tramai-memory-store` | internal | CLARIFY | Rationale placeholder; boundary (durable store impl vs public memory API) is justified. |
| `:tramai-rag` | published | KEEP | RAG pipelines. |
| `:tramai-scheduler` | published | KEEP | Scheduled/deferred execution. |
| `:tramai-vectorstore-spi` | published | KEEP | SPI boundary — the contract root for stores. |
| `:tramai-vectorstore-chroma` | published | KEEP | Adapter isolation (vendor SDK). |
| `:tramai-vectorstore-pgvector` | published | KEEP | Adapter isolation (JDBC/pgvector). |

### 2.9 Testing support

| Module | Pub | Verdict | Evidence |
|--------|-----|---------|----------|
| `:tramai-testing` | published | KEEP | Published test fixtures/TCK support for consumers. |
| `:tramai-spring-consumer-boundary` | internal | CLARIFY | Rationale placeholder; boundary (consumer-facing test harness) justified. |
| `:tramai-spring-consumer-selective` | internal | CLARIFY | Same. |

### 2.10 Applications / examples (7)

| Module | Verdict | Evidence |
|--------|---------|----------|
| `:examples:approval-resume` | KEEP | Executable example; excluded from release. |
| `:examples:governed-workflow` | KEEP | Same. |
| `:examples:sovereign-document-intelligence` | KEEP | Same. |
| `:examples:sovereign-offline-verification` | KEEP | Same. |
| `:examples:spring-sovereign-starter` | KEEP | Same. |
| `:examples:support-agent` | KEEP | Same. |
| `:examples:tool-governance` | KEEP | Same. |

Examples are intentionally separate applications; consolidating them reduces documentation value (each demonstrates one capability in isolation). KEEP.

---

## 3. Findings requiring action

### 3.1 (CLARIFY) `tramai-persistence-jdbc` internal vs published starter exposing it

- `:tramai-persistence-jdbc` is classified `internal` in the manifest.
- `:tramai-spring-boot-starter-sovereign-persistence-jdbc` (published) declares `api(project(":tramai-persistence-jdbc"))` — its public API surface includes an internal module's classes.
- Sibling `:tramai-persistence-file` is `published`; consumers of the file starter get a published dependency.

**Impact:** published artifact's API surface references internal-module types; inconsistent sibling treatment; consumers may transitively depend on a module marked internal (affects dependency policy checks and API baseline attribution).

**Recommendation (CLARIFY, not CONSOLIDATE):** resolve the classification inconsistency — either publish `tramai-persistence-jdbc` (consistent with the file sibling and the JDBC store's actual consumer role) or make the starter's exposure `implementation` instead of `api` if its classes are not part of the intended consumer contract. Decision belongs to the JDBC-persistence track; no change in this PR.

### 3.2 (CLARIFY) `tramai-spring` legacy facade naming

- modules.md L22 already documents it as the legacy facade; the onboarding path is `tramai-spring-boot-starter` / `tramai-spring-core`.
- Rationale in the manifest ("Spring Boot auto-configuration and bean wiring") does not convey the legacy/back-compat role.

**Recommendation:** update the manifest rationale to state the back-compat role explicitly (docs-only; no code change).

### 3.3 (CLARIFY) 7 internal modules with generic placeholder rationales

The following internal modules share the boilerplate rationale "Provides an internal supporting capability for TramAI" (or "internal testing support") — which does not articulate the boundary that AC2 requires:

- `:tramai-server`, `:tramai-mcp`, `:tramai-dashboard`, `:tramai-memory-store`, `:tramai-persistence-jdbc`, `:tramai-spring-consumer-boundary`, `:tramai-spring-consumer-selective`

The M7 mutation guard only rejects blank values, so these pass validation while failing the *spirit* of AC2 ("every module has a documented reason to exist").

**Recommendation:** replace boilerplate with specific rationale per module (docs-only change to `config/quality/module-catalog.yml` rationale fields + regenerated matrix if it renders rationale). No module boundary changes.

---

## 4. Candidate table

| Category | Count | Modules |
|----------|-------|---------|
| **A — KEEP** | 49 | All provider adapters, all examples, all vector stores, all capability modules, engine/structured/orchestration/standalone, security/sovereign, core/BOM, observability/platform, spring-core/starter/sovereign/provider/secret starters, sovereign-ops starter family, persistence-file, testing |
| **B — CLARIFY** | 9 | `tramai-persistence-jdbc`, `tramai-spring-boot-starter-sovereign-persistence-jdbc`, `tramai-spring`, `tramai-server`, `tramai-mcp`, `tramai-dashboard`, `tramai-memory-store`, `tramai-spring-consumer-boundary`, `tramai-spring-consumer-selective` |
| **C — CONSOLIDATE** | **0** | — |

**Zero consolidation candidates.** Every 58-module boundary survives the six-signal test: the modules that look similar from a dependency graph (providers, starters, adapters, SPI+implementations) are the modules whose physical separation *is* the optionality/independence contract.

## 5. Task closure statement

- **Task 5 (review overlap / insufficient value): complete** — all 58 modules reviewed with disposition and evidence (§2), suspicious families explicitly tested (§2.4 providers, §2.6 starters, §2.5/§2.8 SPI↔implementation, §2.10 examples).
- **Task 6 (consolidate where it improves clarity): complete** — no consolidation improves ownership or dependency clarity sufficiently to justify compatibility and churn costs. 11 CLARIFY items are documentation/rationale fixes, not module merges.

This is a legitimate successful outcome per the 9.1b framing: 58 reviewed, 0 justified consolidations.

## 6. Follow-up actions (separate PRs, not this one)

| # | Action | Type |
|---|--------|------|
| 1 | Resolve `tramai-persistence-jdbc` classification vs published starter exposure (§3.1) | Requires JDBC-persistence track decision; may be a build/manifest change |
| 2 | Replace 7 boilerplate rationales with specific ones (§3.3) | Docs-only (catalog rationale + matrix regen if rendered) |
| 3 | Fix `tramai-spring` legacy rationale (§3.2) | Docs-only |

None of these is a module consolidation. If §3.1 lands as "publish the JDBC store," that is a classification change, not a merge.

## 7. Verification

- `./gradlew verifyModuleManifest` — must pass (catalog unchanged in this PR).
- `./gradlew verifyMaintainabilityBaseline` — must pass.
- `./gradlew verifyPr` — must pass.
- `git diff` — must contain only this document (plus, if applied, rationale text edits — none applied here).

## 8. Acceptance criteria (from 9.1b spec)

1. ✅ All 58 modules reviewed (dispositions in §2).
2. ✅ Every module classified KEEP / CLARIFY / CONSOLIDATE.
3. ✅ Overlapping pairs/groups have explicit architectural analysis (§2.4, §2.5, §2.6, §2.8, §2.10).
4. ✅ Small LOC never used as consolidation criterion (explicitly rejected in §1).
5. ✅ Published modules reviewed with compatibility significance in mind (all published KEEP verdicts state why; the only published CLARIFY items are docs/rationale, not merges).
6. ✅ Optional dependency/framework/provider isolation treated as independent value (sovereign-ops family, providers, secrets, vector stores).
7. ✅ No consolidation recommended without objective dependency/ownership improvement (none recommended).
8. ✅ Review PR contains no consolidation implementation.
9. ✅ No consolidation candidates → no follow-up implementation PRs (CLARIFY actions are docs/classification, not merges).
10. ✅ Task 6 explicitly closed as "reviewed; consolidation not beneficial."
11. ⏳ Final module model reflected into 9.1 closure artifact — done via this review + the audit; closes when CLARIFY actions land.
12. ✅ verifyPr green (verified in this PR's CI).
