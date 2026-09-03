# Epic 12.1 — Performance and resource baseline: T0 audit

**Slice:** 12.1a — measurement architecture (facts first, zero production change).

**Branch:** `docs/0.6.0-epic-12.1a-audit` · **Date:** 2026-09-03 · **Master:** `60394445` (Epic 10.5 ✅ COMPLETE via #375/#376; 10.3c ongoing — Signal's lane).

> Guiding question — *not* "how fast should TramAI be?", but:
> **"Which roadmap 12.1 operations currently have zero timing evidence, and what is the smallest reproducible way to baseline them without touching production behaviour?"**

Status legend: ✅ existing evidence · ◐ partial evidence · ❌ no evidence.

---

## 1. Existing benchmark / measurement infrastructure

| Question | Finding |
|---|---|
| JMH / dedicated benchmark module? | **None.** No JMH plugin, no `jmh` dependency, no benchmark source set anywhere in `settings`, `build-logic`, or module builds. |
| Timing assertions in tests? | **None deliberate.** No `measureTime`/`measureTimedValue`-based latency assertions in any `src/test`. The only `TimeSource`/timing machinery is in build-logic verifiers (nondeterminism/compiler-warnings), i.e. build tooling, not runtime measurement. |
| Gradle-level perf knobs | `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.configuration-cache=false` (CI lanes opt out via `--no-configuration-cache` where they do). |
| Existing latency evidence anywhere | Release/sovereign evidence packs carry build times and zero-egress run reports, but no **runtime operation** timings. |
| Precedent for "audit before slicing" | Epic 10.3 T0 audit (`docs/EPIC-10.3-coverage-mutation.md`) — facts/gap/cost model first, enforcement after measurement. 12.1 follows the same shape. |

**Finding:** 12.1 needs a benchmark harness from zero. No infra exists to reuse; the lazy baseline is a JUnit-5 based timing harness inside the existing test JVMs (no new Gradle module, no JMH runner) or a standalone `examples/` micro-benchmark module — decided in 12.1a, measured evidence first.

---

## 2. Roadmap benchmark requirements → evidence map

Owning types verified against master `d9e40c2e`. "Behavioural tests" = correctness coverage that already exists and defines the fixture surface a benchmark must reuse.

| # | Roadmap operation | Owning types (verified) | Existing behavioural tests | Timing evidence | Verdict |
|---|---|---|---|---|---|
| 1 | Service proxy creation | `TramaiEngine.kt` (`Proxy.newProxyInstance`), `invocation/TramaiInvocationHandler.kt` | `TramaiEngineTest`, `LifecycleDispatcherContractTest`, `InvocationRegistryLockTest` | ❌ | ◐ fixture exists, no timing |
| 2 | Operation-plan compilation | `engine/planning/ServiceDefinitionCompiler.kt`, `OperationExecutionPlan.kt` | `EnginePlanningIntegrationTest`, `TramaiEngineTest` | ❌ | ◐ |
| 3 | Cached invocation dispatch | `engine/cache/OperationCacheCoordinatorTest`, `InMemoryOperationResponseCache` | `OperationCacheCoordinatorTest`, `InMemoryOperationResponseCacheTest` | ❌ | ◐ |
| 4 | Structured contract compilation | `tramai-structured/descriptor/KotlinStructuredTypeCompiler.kt`, `JacksonJavaBeanStructuredTypeCompiler.kt`, `StructuredTypeCompiler.kt` | structured contract/schema tests | ❌ | ◐ |
| 5 | Structured validation | `descriptor/StructuredValueValidator.kt`, `StructuredJsonShapeValidator.kt` | structured validation tests | ❌ | ◐ |
| 6 | Provider routing | `core/ProviderRegistryTest.kt` + engine provider tests (`ProviderCircuitBreakerLifecycle*`) | registry + routing-plan behavioural suites | ❌ | ◐ |
| 7 | Tool-call governance overhead | `ToolRegistryTest.kt`, tool coordinators in engine | `ToolRegistryTest`, `ToolSafeFailureContractTest` | ❌ | ◐ |
| 8 | Approval suspend/resume | `core/approval/*` (`ApprovalGateCoordinator` etc.), engine approval | `ApprovalResumeEngineTest`, `ResumeOperationRegistryTest` | ❌ | ◐ |
| 9 | Evidence export | `engine/evidence/ProviderRoutingRuntimeEvidenceExporter.kt` | `ProviderRoutingRuntimeEvidenceExporterTest` | ❌ | ◐ |
| 10 | Workflow checkpoint/resume | `orchestration/CheckpointPoller.kt`, worker lifecycle machinery | `WorkerLifecycleControllerTest`, `WorkerLifecyclePropertyTest`, `TramaiWorkerCancellationContractTest` | ❌ | ◐ |
| 11 | Worker polling (empty vs loaded) | `orchestration/TramaiWorker.kt`, `CheckpointPoller.kt` | `TramaiWorkerTest`, `WorkerLifecycle*Test` | ❌ | ◐ (correctness only; no queue-depth timing) |

**Pattern:** every operation has a behavioural test surface but **zero** latency/throughput evidence. 12.1b baselines are all net-new measurements.

---

## 3. Roadmap resource checks → evidence map

| # | Resource check | Existing evidence (verified) | Gap | Verdict |
|---|---|---|---|---|
| 1 | Runtime close → zero owned jobs | `LifecycleDispatcherContractTest` + engine close/lifecycle suites; closeable-runtime ownership landed earlier | Explicit "zero owned coroutines after close" assertion per runtime type may be implicit, not a named contract | ◐ |
| 2 | Shutdown-hook cleanup | `orchestration/WorkerShutdownCoordinator.kt` (+ `WorkerShutdownCoordinatorTest`); `WorkerLifecycleController.kt` registers hooks | Hook-removal-after-close assertion exists for worker coordinator; runtime-level hook cleanup not proven repo-wide | ◐ |
| 3 | Subprocess terminated after cancellation/close | `orchestration/ProcessSupport.kt`, `AgentCliSupport.kt`, `CodexStep.kt`, `ShellStep.kt`, `HermesStep.kt`, `McpStep.kt`; `TramaiWorkerCancellationContractTest` | Subprocess-kill-on-cancel specific test not located | ◐/❌ |
| 4 | HTTP response always closed | `core/provider/transport/ProviderTransport.kt`; bounded body reads fixed in 10.1d (#349); `StreamingExecutionCoordinatorTest` | Explicit stream-closure assertions live in provider tests, not a single lifecycle contract | ◐ |
| 5 | File/JDBC descriptors bounded | `JdbcSovereignRuntimeE2ETest`, `JdbcApprovalStoreTckTest`, store TCKs, persistence-file suites | No descriptor-count assertion anywhere (would need /proc or JMX) | ❌ |
| 6 | Registries/caches bounded growth | `InMemoryOperationResponseCache` (budget), `StructuredDescriptorCache`, `ToolRegistry`, `ProviderRegistry`, `TrustedReplayEnvelopeRegistry`, `ResumeOperationRegistry` | Boundedness asserted per cache where budget exists; **registry** boundedness (unbounded maps) likely unasserted | ◐ |
| 7 | Repeated create/close → no retained state | lifecycle suites | No dedicated create/close × N retention probe | ❌ |

**Finding:** the roadmap's "no known resource leak" acceptance is *partially* proven by construction (lifecycle suites, bounded-read fixes, shutdown coordinator) but none of the seven checks is a named, repeatable proof. 12.1c is mostly a **test-hardening** slice, not new production machinery — RED→GREEN only if a probe exposes a real defect.

---

## 4. Proposed 12.1a measurement methodology (draft, zero code)

- **Harness:** JUnit-5 timing tests in the owning module's existing test source set, gated by a system property (`-Dtramai.benchmark=true`) so default `test` stays timing-free and CI-fast; OR a dedicated `tramai-benchmarks` module if cross-module fixtures force it. Decision deferred to 12.1a PR after the inventory above.
- **Method per op:** warm-up ≥ N iterations, then M timed iterations; report p50/p95/mean; throughput ops (worker polling) counted in ops/sec with queue-depth fixture (empty vs loaded).
- **Environment metadata recorded with every run:** JDK version, OS, runner label/machine, git SHA, date; commit with the result.
- **Output format:** committed JSON baseline under `config/quality/performance/` (mirrors `0.6.0-baseline.json` precedent); noise/tolerance = inter-run spread of the reference fixture, not arbitrary ±%.
- **CI placement:** latency/throughput runs in **release/deep lane** (or workflow_dispatch), NOT every PR; resource/leak probes (12.1c) DO run per-PR since they are cheap correctness tests.
- **Explicit non-goals:** no production-code changes; no performance *targets* (12.1b outcome is a credible 0.6.0 reference point); no mutation/PIT/config-quality files (Signal's lane).

## 5. Cost model (planning estimates — will be replaced by 12.1a measurements)

| Slice | Contents | Estimated cost | Verdict |
|---|---|---|---|
| 12.1a | methodology doc + harness decision + this map, zero prod change | docs-only + tiny harness spike | **PR-viable now** |
| 12.1b | 11 runtime baselines, committed JSON | minutes per op in a release lane | **release/deep lane** |
| 12.1c | 7 resource/lifecycle probes as per-PR tests | seconds–low minutes on existing suites | **PR-viable** |
| 12.1d | regression policy (material-regression def, release-only vs blocking) | docs after real measurements | **after 12.1b** |

## 6. Gap analysis summary

- **Missing measurement:** all 11 timing baselines (net-new).
- **Measured but unenforced:** none (nothing measured yet).
- **Correctness-covered, timing-blind:** all 11 operations.
- **Resource proofs partial:** shutdown-hook ✅ (worker), HTTP closure ◐, subprocess ◐, close-ownership ◐, FD ❌, registry boundedness ◐, repeated create/close ❌.
- **Boundary:** this epic does not touch `config/quality/mutation-*`, PIT implementation, mutation classifications/baselines/targetTests, or any 10.3 authority (Signal's lane). It also makes **no production behaviour change**; any defect exposed by 12.1c is fixed in its own RED→GREEN PR, not inside the audit.

## 7. Proposed slice sequence

1. **12.1a** measurement architecture (this audit → harness decision → committed methodology). Do not optimize production.
2. **12.1b** runtime performance baseline → `0.6.0` reference table.
3. **12.1c** resource/lifecycle proof (7 probes; RED→GREEN per defect).
4. **12.1d** regression policy — only after real measurements exist.

*This is the Epic 12.1 opener (12.1a): measurement-only audit. No production behaviour change; no mutation/PIT/config-quality files (Signal's lane). Next slices 12.1b–d proceed only after this audit is accepted.*
