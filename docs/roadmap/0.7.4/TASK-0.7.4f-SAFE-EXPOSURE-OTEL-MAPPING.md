# TASK 0.7.4f — Safe Exposure and OpenTelemetry Mapping

Epic 0.7.4. Status: Phase 0 complete (decision recorded before implementation).

## 1. Baseline

| Item | Value |
|---|---|
| Integration branch | `release/0.7.0` |
| Stated starting point | `d3ca761362a923acc316c8e96b83a336137fc3b2` |
| Live `origin/release/0.7.0` at task start | `d3ca761362a923acc316c8e96b83a336137fc3b2` |
| Divergence | none — `git log d3ca7613..origin/release/0.7.0` is empty |
| Task branch | `task/0.7.4f-safe-exposure-otel-mapping` |
| 0.7.4e (PR #536) | merged as squash `d3ca7613`, verified present on the branch |

The starting point was verified against the live branch, not assumed.

## 2. Source inventory (measured, not assumed)

| Source | Location | What it contributes here |
|---|---|---|
| Governance query contract | `tramai-security/.../governance/query/GovernanceQueryContract.kt` | `GovernanceQueryService`, `GovernanceQueryRequest`, four result states |
| Governance view | `.../governance/query/GovernanceQueryView.kt` | `GovernanceProjectionView`, `GovernanceDecisionFact`, `GovernanceFactSummary`, `ReasonCodeExposure`, `SourceCurrency`, `RawCoverage`/`RecordCoverage` |
| Stored projection read model | `.../governance/GovernanceProjectionReadModel.kt`, `GovernanceProjectionSnapshot.kt` | `SourceCurrency` internals, `SemanticDigest`, classification revision |
| Evidence provenance vocabulary | `tramai-security/.../evidence/RuntimeBoundaryProvenance.kt` | `AUTHORITATIVE`/`INSTRUMENTED`/`OBSERVED`/`IMPORTED`, `boundary.id`, `boundary.enforcementStrength`, registered XR1 boundary `spring-ai-tool-callback` |
| Runtime evidence contract | `.../evidence/RuntimeEvidenceContractValidator.kt`, `ToolPermissionRuntimeEvidenceExporter.kt` | `runtime-evidence.v1`, `source.component` attribution |
| Event catalogue | `tramai-core/.../observation/event/` — `RuntimeEventCatalogue.kt`, `RuntimeEvents.kt`, `RuntimeAttributes.kt`, `RuntimeMetrics.kt`, `RuntimeEventCatalogueRenderer.kt` | The only legal source of event names, attribute keys, value types and metric instruments |
| OTEL observers | `tramai-observability/.../OpenTelemetry{Operation,Workflow,TramaiWorker}Observer.kt`, `OpenTelemetryAttributes.kt` | Existing emission paths; `implementation(libs.opentelemetry.api)` |
| Catalogue guard | `tramai-observability/build.gradle.kts` test inputs + runtime-event-catalogue architecture test | Scans **every** module's `src/main` Kotlin, so an unregistered literal anywhere fails the build |
| Spring AI reference path | `examples/spring-ai-authority-boundary`, `RuntimeBoundaryProvenance.SPRING_AI_TOOL_CALLBACK` | The XR1 external-runtime case (evidence mapping only, no new integration) |

## 3. Dependency assessment (the decisive question)

- `tramai-observability` currently declares `api(project(":tramai-core"))` and
  `implementation(project(":tramai-orchestration"))`. It has **no** edge to `:tramai-security`.
- `config/quality/module-boundaries.yml` forbids edges by layer. `operations-observability → governance-security`
  is **not** in `forbiddenEdges`.
- The published-module rules forbid `published → internal`. `module-catalog.yml` records
  `:tramai-observability` as `layer: operations-observability, publishability: published, apiStability: preview`
  and `:tramai-security` as `layer: governance-security, publishability: published, apiStability: preview`,
  so the pair is **published → published** and the rule does not apply.
- No cycle is introduced: `:tramai-security` has no dependency on `:tramai-observability`.

**Decision: the OTEL adapter lives in `:tramai-observability`, which gains an
`api(project(":tramai-security"))` edge.**

The edge is `api`, not `implementation`, and this is a deliberate amendment to the first version of this
record. The public safe-exposure type has to carry the authority vocabulary (`FactAuthority`, `FactFreshness`,
`DecisionKind`, `Completeness`, `ReasonCodeExposure`) because "reuse the repository's existing
classifications" forbids mirroring them, and a mirrored copy would drift from the source of truth the whole
slice exists to preserve. An `implementation` edge cannot expose those types publicly, so the weaker edge
would force the duplication rather than avoid it. Both modules are `published`/`preview`, so this is a
published → published edge with no forbidden layer transition.

The governance projection, persistence and authority machinery remain unaware of OpenTelemetry: nothing in
`:tramai-security` imports anything from this module.

Rejected alternatives: placing the adapter in `:tramai-security` (would make governance depend on
OpenTelemetry, which the task forbids); a new module (not the smallest legal arrangement, and it would add a
published module for one adapter).

### Public API effect

- `:tramai-security`: **none**. The governance query contract is consumed, not changed.
- `:tramai-observability`: additive — the safe exposure type, the exporter, and typed governance
  event/attribute accessors. Admitted through the existing hash-bound `api-migrations.yml` process if the
  module's dump changes.
- `:tramai-core`: additive if new catalogue keys are required, because the catalogue is the only legal
  place to register an event name or attribute key.

## 4. Exposure matrix

Authorised by meaning, and only where the underlying evidence legitimately establishes the claim.

| Candidate | Include | Why |
|---|---|---|
| Decision family (policy/tool/route) | yes | Fixed enum, no payload |
| Recorded decision category | yes | Categorical |
| Fact authority classification | yes | The vocabulary that carries the whole point of the slice |
| Fact freshness classification | yes | Categorical, per-source |
| Registration currency (independent) | yes | Never collapsed into a global freshness flag |
| Audit currency (independent) | yes | Same |
| Decision-record completeness | yes | Kept independent of raw stream coverage |
| Raw stream coverage: whether a verified range exists | yes (boolean + categorical) | The range bounds themselves are identifiers |
| `ReasonCodeExposure` state | yes | Exposed/withheld/not-recorded — never the code itself unless already `EXPOSED` |
| Evidence/provenance classification where established | yes | `AUTHORITATIVE`/`INSTRUMENTED`/`OBSERVED`/`IMPORTED` where the source establishes it |
| Version/lag indicators | yes, if published by the source | Classification revision, registration version as **categorical presence**, not raw values |
| Raw stream range bounds | **no** | Identifiers; correlate to events |
| Reason code text when `WITHHELD` | **no** | Already withheld at the query boundary; re-opening it here would defeat 0.7.4e |
| Prompts, completions, tool arguments/results, documents, credentials, raw exceptions, raw provider responses | **no** | Protected payloads |
| `established`, `mustNotInfer`, `currency.note` free-text | **no** | Free text, unbounded, authored for humans |
| Arbitrary audit metadata / `Map<String, Any?>` | **no** | The exact mechanism this task closes |
| Workload/deployment/run/correlation identifiers as metric labels | **no** | High cardinality, correlatable |
| Hashes and digests | **no** by default | Identifiers are not automatically safe; no consumer need established |

The policy is an explicit allowlist over typed fields. `toString()`, reflection, object serialization and
generic map forwarding are excluded by construction: the mapper reads named properties and cannot see
anything else.

## 5. Risks and current protections (measured in this repository)

| Risk | Current protection | Relevance here |
|---|---|---|
| Unregistered event/attribute literals | Repository-wide catalogue scan in `:tramai-observability` tests | New keys must be registered in `:tramai-core`, which is why the catalogue is the integration point |
| Arbitrary attribute maps on OTEL paths | Catalogue keys are typed `RuntimeAttributeKey<T>`; validator rejects mismatched types | Confirms the typed-key approach rather than free-form `setAttribute(String, Any)` |
| Registered strength read as verified origin | `RuntimeBoundaryProvenance` documents explicitly that resolving a declared identifier's strength does not authenticate the event's origin; pinned by `ToolPermissionBoundaryProvenanceTest` | The mapper must never emit a strength for an event whose origin it did not observe |
| Reason codes leaking through a "safe" path | 0.7.4e: approved-vocabulary exposure with `ReasonCodeExposure` states | The mapper reads the state, never re-derives the text |
| Missing/corrupt projection read as healthy | 0.7.4e: `NotMaterialized`/`UnsupportedFormat`/`InvalidStoredProjection` are distinct results | The exporter maps these to explicit non-success telemetry and never to a governance-success signal |
| Coverage collapse | 0.7.4e: raw and record coverage are structurally independent | They stay separate attributes |

## 6. Phase plan

1. **Phase 1** — typed safe-exposure contract in `:tramai-observability`: a pure, allowlisted representation
   built from named governance fields, with the result-state distinction carried explicitly.
2. **Phase 2** — authority and provenance preservation: `AUTHORITATIVE`/`INSTRUMENTED`/`OBSERVED`/`IMPORTED`
   only where established, registered boundary strength never promoted to verified origin, unknown never
   upgraded.
3. **Phase 3** — the OTEL adapter over that contract, using catalogue-registered keys, best-effort and
   non-authoritative, wired explicitly.
4. **Phase 4** — real export qualification with the in-memory OTEL SDK reading rendered records, asserting
   both presence of permitted fields and absence of forbidden ones.
5. **Phase 5** — focused mutation campaign over the dangerous rules.
6. **Phase 6** — gates, exact-head CI, PR.

No coverage threshold, mutation baseline, static-analysis baseline, gate definition or CI workflow will be
changed to obtain a green result.

## XR1 - Spring AI external-runtime evidence mapping

The declared boundary already exists: `RuntimeBoundaryProvenance.SPRING_AI_TOOL_CALLBACK` is documented in code as
"the declared XR1 boundary: the Spring AI callback, consumed by `GovernedToolCallback`", with the closed strength
vocabulary `AUTHORITATIVE`, `INSTRUMENTED`, `OBSERVED`, `IMPORTED` and the reserved metadata keys `boundary.id` and
`boundary.enforcementStrength`.

That object's own contract states the limit this mapping must respect: resolving a registered boundary's strength
does **not** authenticate the origin of the event naming that boundary, and a producer that names a registered
identifier obtains that registration's strength without having gone through the boundary. `GovernanceDecisionFact`
carries `boundaryId` and `boundaryStrength` for exactly that reason - they are *declared*, not verified.

**What this slice does with that evidence.** Two things, both mapped through existing typed fields, no new
integration and no production code:

- The decision's evidence classification is exported as `POLICY_AUTHORIZATION_RECORD`, never
  `AUTHORITATIVE_PERSISTED`. A policy authorization record is a decision, not proof that the operation executed or
  was prevented, and it is not proof that TramAI authored the application workflow.
- `boundaryId` and `boundaryStrength` are **excluded** from the exposure entirely. A registered strength is not
  evidence that the call passed through the boundary, so exporting it as an attribute would present a declaration
  as a classification. The declared origin stays unexported rather than being exported as a weaker label.

Coverage gaps stay visible: the XR1 projection reports `GAPS_PRESENT` with no verified raw range, so
`verified_range = ABSENT` is exported and completeness is not upgraded.

Pinned by two tests: `GovernanceExposureMapperTest.spring ai boundary evidence maps without claiming authorship or
enforcement` (real query DTOs through the validating constructors) and
`GovernanceTelemetryExporterTest.spring ai boundary evidence is exported without an enforcement claim` (the rendered
span records). Neither asserts that TramAI controlled execution paths outside its registered boundary, because it
does not.

**Not claimed.** That the Spring AI callback prevented an action in any particular deployment: prevention is the
registration's declared strength plus its prevention tests, not something an exported attribute establishes.
