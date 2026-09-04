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

Owning types verified against master `60394445` (post-10.5). "Behavioural tests" = correctness coverage that already exists and defines the fixture surface a benchmark must reuse.

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

## 4. 12.1a measurement methodology (decided, zero code)

**Harness decision:** JUnit-5 timing tests in the **owning module's existing test source set**, annotated `@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")`.

- Default `test` runs: benchmarks are **skipped** — zero cost, zero flake in ordinary PR CI (JUnit reports them as skipped).
- Deep lane: Gradle injects `-Dtramai.benchmark=true`; benchmarks execute and emit results. No new Gradle module, no JMH runner, no new dependency — the ladder: existing JUnit test JVM + existing fixture surface per owning module.
- Bench ops that cross module boundaries live in the module that owns the *entry point* (e.g. proxy creation in `tramai-engine`, structured compile/validation in `tramai-structured`) and reuse that module's existing behavioural fixtures.

**Per-op method (what the harness implements):**
- *Latency ops:* warm-up ≥ 3 iterations (JIT/caches), then ≥ 10 timed iterations, each iteration measured individually with `System.nanoTime()`; report mean, p50, p95 and iteration count.
- *Throughput ops* (worker polling empty vs loaded): fixed-duration sampling (≥ 1 s) counted in ops/sec at two fixture queue depths (0 and ≥ 100 pending).
- *Fixture determinism:* each op reuses its behavioural test fixture (see map §2); any fixture that allocates external state (HTTP/process) is excluded from latency benches and covered only by resource probes (12.1c).

**Environment metadata (recorded with every result emission):** JDK version (`java.version`), JVM vendor, OS name/arch, Gradle JVM args, machine hostname/runner label, git SHA, UTC timestamp, property `tramai.benchmark.iterations` override if set.

**Output format:** one JSON document per deep-lane run, one object per op: `{operation, module, gitSha, env{...}, samples:[...] or opsPerSec, stats{mean,p50,p95}, unit}`. Emitted to the test's module build dir (`build/reports/benchmark/<run-timestamp>.json`).

**Committed baseline:** introduced only in 12.1b (`config/quality/performance/0.6.0-performance-baseline.json`, mirroring the `0.6.0-baseline.json` precedent). Noise/tolerance methodology: inter-run spread of a reference fixture across ≥ 3 deep-lane runs — never arbitrary ±%.

**CI/deep-lane placement:** timing runs execute in the **release/deep lane** (`workflow_dispatch` release certification, where `verifySovereignRuntimeClosure` already runs) — never in ordinary PR CI, which must stay timing-free. Resource/lifecycle probes (12.1c) are cheap per-PR correctness tests and run on every PR.

**Explicit non-goals:** no production-code changes; no performance *targets* or thresholds (12.1b outcome is a credible 0.6.0 reference point; 12.1d defines regression policy only after real measurements exist); no mutation/PIT/config-quality files (Signal's lane).

## 5. Cost model (planning estimates — will be replaced by 12.1a measurements)

| Slice | Contents | Estimated cost | Verdict |
|---|---|---|---|
| 12.1a | this audit (evidence map + decided methodology) → small harness PR (harness + 1–2 representative benchmarks, no thresholds, no 11-op enrollment) | docs + ~2 test files | **PR-viable now** |
| 12.1b | 11 runtime baselines, committed JSON | minutes per op in a release lane | **release/deep lane** |
| 12.1c | 7 resource/lifecycle probes as per-PR tests | seconds–low minutes on existing suites | **PR-viable** |
| 12.1d | regression policy (material-regression def, release-only vs blocking) | docs after real measurements | **after 12.1b** |

## 6. Gap analysis summary

- **Missing measurement:** all 11 timing baselines (net-new).
- **Measured but unenforced:** none (nothing measured yet).
- **Correctness-covered, timing-blind:** all 11 operations.
- **Resource proofs partial:** shutdown-hook ◐ (worker coordinator ✅, runtime-level hook cleanup not proven repo-wide), HTTP closure ◐, subprocess ◐, close-ownership ◐, FD ❌, registry boundedness ◐, repeated create/close ❌.
- **Boundary:** this epic does not touch `config/quality/mutation-*`, PIT implementation, mutation classifications/baselines/targetTests, or any 10.3 authority (Signal's lane). It also makes **no production behaviour change**; any defect exposed by 12.1c is fixed in its own RED→GREEN PR, not inside the audit.

## 7. Proposed slice sequence

1. **12.1a** measurement architecture: this audit (evidence map + decided methodology §4) **→ small harness PR** (timing harness + 1–2 representative benchmarks proving reproducibility, env metadata, output format, warm-up/iteration policy, deep-lane behaviour — no thresholds, no 11-op enrollment yet). Do not optimize production.
2. **12.1b** runtime performance baseline → `0.6.0` reference table.
3. **12.1c** resource/lifecycle proof (7 probes; RED→GREEN per defect).
4. **12.1d** regression policy — only after real measurements exist.

---

## 8. 12.1b completion record

**Status: 12.1b ✅ COMPLETE** (2026-09-04; merged via #380, #381, #382, #383).

- **Enrolled operations:** B01–B11 — 12 operation identities (`B11` measured
  at both `empty` and `loaded` depths). Owning-module benchmark classes under
  `src/test/.../benchmark/`, gated by `tramai.benchmark=true` (skipped by
  default in ordinary PR CI), using the canonical `BenchmarkHarness`
  (`tramai-testing` testFixtures) and the module-local `BenchmarkSupport`
  copies in `tramai-core`/`tramai-structured` (documented bounded exception:
  base modules cannot take the upward fixtures edge).
- **Measurement authority:** exact commit
  `d2e6beef0e1253caf13218216d936377263b25a5` (branch
  `epic/12.1b-measurement-authority`, = master after #382; no
  benchmark-relevant code changed before/during measurement).
- **Three independent deep-lane runs:** `33811488740` (22:07:11Z),
  `33812722200` (22:22:19Z), `33813904962` (22:37:00Z) — all success, all
  12/12 population, no missing/duplicate ids, nothing discarded. Variance
  review in `docs/EPIC-12.1b-baseline-evidence.md` (B06 run-3 mean outlier
  recorded; p50 is the reference metric for micro-latency ops).
- **Committed baseline:** `config/quality/performance/0.6.0-performance-baseline.json`
  — measuredCommit provenance, methodology/schema version, per-operation
  fixture/metric/unit, and per run the mean/p50/p95 **plus the raw sample
  population** (durable in-repo evidence; no artifact-store dependency for
  audit).
- **Policy:** evidence only — no thresholds, no regression gate, no
  enforcement, no production change. 12.1d owns regression policy after
  12.1c resource/lifecycle proof.

## 9. 12.1c completion record

**Status: 12.1c ✅ COMPLETE** (2026-09-04; merged via #385, #386, #387).

All seven resource/lifecycle requirements have named, repeatable proof. Every
slice: test-only, no production change, no timing thresholds, no
sleeps-as-correctness (latch/process-exit/registry observability instead), no
platform-specific contract masquerading as portable (`/proc` is gated and
corroborative only), no leak hidden by GC. Signal's mutation/PIT/config-quality
authority untouched throughout.

| # | Requirement | Portable proof | Linux-only corroboration | Owning test/suite | Merged PR / commit | Production defect |
|---|---|---|---|---|---|---|
| 1 | Runtime close → zero owned jobs | close() returns only after a provider-parked invocation and its cleanup terminate (latch-gated); repeated close is a no-op; post-close work rejected | — | `RuntimeCloseOwnedJobContractTest` (+ existing TramaiEngineTest close suites) | #385 → `d069f79` | none |
| 2 | Shutdown-hook cleanup | hook registered while worker live, removed on normal shutdown: coordinator flag + JVM `removeShutdownHook` returns false | — | `WorkerShutdownCoordinatorTest` (2 added tests) | #385 → `d069f79` | none |
| 3 | Subprocess kill-on-cancel | real bounded subprocess fixtures; `cancelAndJoin`; root + descendant terminated (process-exit polling, no fixed sleeps) | — | `SubprocessCancellationContractTest` (existing 27-test suite; referenced by #386, not duplicated) | #386 → `74a4fb9` (reference) | none |
| 4 | HTTP response/stream always closed | instrumented close-observable streams: close on success, mid-read IOException, CancellationException, overflow, response-level success/failure | — | `BoundedBodyReadTest` (5 added); SSE path already in `ProviderSseTest` | #386 → `74a4fb9` | none |
| 5 | File/JDBC descriptors bounded | JDBC: instrumented DataSource — live connections 0 after every op, 30 cycles never grow. File: real FileChannel/lock path — `pathLockRegistrySize()` back to baseline every cycle + after 50 distinct paths | `/proc/self/fd` over 200 JDBC + 200 file cycles (gated `@EnabledOnOs(LINUX)`, bounded-growth only) | `JdbcConnectionReleaseProbeTest`, `FileDescriptorReleaseProbeTest` | #387 → `92dbb8c` | none |
| 6 | Registries/caches bounded | audit of every named candidate; module-global `pathLocks` registry is lifecycle-bounded by reference counting (`releasePathLock` removes at `users==0`) and proven to return to baseline; all others instance-scoped by construction | — | registry audit (PR #387 body) + `pathLockRegistrySize()` assertions; existing `FileWorkflowPersistenceCancellationContractTest` cancellation/lock scenarios | #387 → `92dbb8c` | none (no eviction semantics invented) |
| 7 | Repeated create/close × N | engine 25× create→invoke→close→close cycles retain no owned state; worker 10× cycles leave no accumulated JVM hooks | — | `RuntimeCloseOwnedJobContractTest`, `WorkerShutdownCoordinatorTest` | #385 → `d069f79` | none |

**Cross-cutting acceptance:** no timing thresholds anywhere; deterministic
ownership signals only; exact-head CI + MB green for every merged slice
(#385 `d069f79`, #386 `74a4fb9` after rebase onto post-#385 master, #387
`92dbb8c` after rebase onto post-#386 master); no production defect was
exposed by any probe, so no RED→GREEN production-fix PR was required.

**Next:** 12.1d regression policy, using the real 12.1b performance variance
plus the deterministic 12.1c resource proofs. Do not start before this record
is accepted.

## 10. 12.1d completion record and Epic 12.1 closure

**Status: 12.1d ✅ COMPLETE — Epic 12.1 ✅ COMPLETE** (2026-09-04; 12.1d merged
via #389 → `235769c`).

Policy adopted in `docs/EPIC-12.1d-regression-policy.md`, enforced by
`scripts/performance_regression_verifier.py` (stdlib-only; 23 classification
semantics tests in `scripts/test_performance_regression_verifier.py`), wired
into `ci.yml` (fast timing-free unit job on every PR) and the deep lane
(`sovereign-runtime-release-candidate.yml`, workflow_dispatch only).

- **Resource/lifecycle regressions remain deterministic CI blockers** — the
  seven 12.1c proofs (#385/#386/#387) are correctness contracts; no
  statistical confirmation; the 12.1d verifier does not govern them.
- **Ordinary PR CI remains timing-free** — B01–B11 stay gated behind
  `tramai.benchmark=true`; timing drift is never an ordinary PR failure.
- **Deep lane compares the complete 12-op population** against the committed
  0.6.0 baseline (missing/duplicate identity, malformed JSON, wrong commit,
  schema/authority mismatch, skipped execution all fail closed).
- **Reference/envelope semantics tested**: latency p50 / throughput mean
  ops/sec; reference = median of the 3 recorded values; envelope = min..max —
  boundaries derived from measured 12.1b variance, no arbitrary percentage.
- **Single-run drift never becomes a confirmed regression** — one deep run
  yields only WITHIN / REGRESSION_CANDIDATE / IMPROVEMENT_CANDIDATE.
- **Three-run confirmation semantics tested** — CONFIRMED_REGRESSION only
  when all three runs are worse than the worst observed 0.6.0 boundary;
  straddle → INCONCLUSIVE_NOISE.
- **Authority/applicability explicit** — benchmark-authority fingerprint over
  harness/benchmark/fixture files (production code excluded); env
  compatibility class-based; OS/arch/JDK-family/methodology mismatch →
  NON_COMPARABLE when recorded; runner instances/hostnames never invalidate.
- **Structural failures fail closed** (INVALID_MEASUREMENT / NON_COMPARABLE →
  non-zero verifier exit).
- **No automatic baseline weakening** — confirmed regression requires explicit
  release adjudication (fix / accept with rationale / re-authority); the
  0.6.0 baseline stays immutable with provenance and ≥3-run replacement
  protocol.
- **Exact-head CI + MB green** on the merged slice (#389 `235769c`).

Validated end-to-end against the real 12.1b deep-lane archives: run1 verifies
12/12 WITHIN_BASELINE_VARIANCE; the 3-run confirm classifies
INCONCLUSIVE_NOISE (the runs define the envelope — no regression).

### Epic 12.1 — final acceptance

| Slice | Deliverable | Merged |
|---|---|---|
| 12.1a | measurement-only audit (methodology §4, map §2/§3) | #377 |
| 12.1b | 12-operation benchmark baseline, ≥3 independent runs, committed baseline + durable raw samples (evidence only) | #380–#384 |
| 12.1c | 7 deterministic resource/lifecycle proofs (jobs, hooks, subprocess, HTTP, file/JDBC descriptors, registries incl. pathLocks, create/close cycles) | #385–#388 |
| 12.1d | regression policy: hard resource failures + measured-drift performance protocol, verifier + tests + deep-lane integration | #389 |

No production defect was found by any 12.1c probe; no thresholds were invented
anywhere; the 0.6.0 baseline and its raw samples remain the durable,
immutable evidence foundation for 0.6.0 release review. Signal's
mutation/PIT/config-quality authority untouched throughout the epic.

*This is the Epic 12.1 opener (12.1a): measurement-only audit. No production behaviour change; no mutation/PIT/config-quality files (Signal's lane). Next slices 12.1b–d proceed only after this audit is accepted.*
