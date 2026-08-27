# Change Guide: Adding a Workflow Step

## Start here

Workflow steps are a **builder DSL over a sealed internal step contract** — there is no public `WorkflowStep` interface for external implementation. Adding a built-in step means: a new sealed subclass + a DSL function + exhaustive-`when` updates + tests. Adding an *external* step means the `ExternalStepExecutorRegistry`.

Start from [`ARCHITECTURE.md`](../../../ARCHITECTURE.md) (workflow/worker ownership → `tramai-orchestration`), read the module card [`docs/modules/tramai-orchestration.md`](../../modules/tramai-orchestration.md), then follow this guide.

## Owning module

- `tramai-orchestration` — layer `runtime-execution`, `publishability: published`, `apiStability: preview` (`config/quality/module-catalog.yml:196-201`).
- Contracts and TCK fixtures additionally live in `tramai-testing`.

## Authoritative contracts

- Runtime step contract: `InternalWorkflowStep<S>` (sealed) — `tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowStepExecution.kt:80-89`: `val name`, `suspensionMode` (default `NONE`), `suspend fun execute(request: WorkflowStepExecutionRequest<S>): WorkflowStepExecutionResult<S>` (sealed `Completed(state)` / `Suspended`, L26-32).
- Builder DSL: `WorkflowBuilder.kt:16` extends `AbstractWorkflowBuilder<S>` (:71, owns step list :72, `appendStep` :377, `stepsSnapshot` :384). Step functions: `localStep` :75, `aiStep` ×4 :96-161, `httpStep` :180, `shellStep` :190, `hermesStep` :206/220, `codexStep` :235/249, `mcpStep` :264/280, `pluginStep` :297/310, `gateStep` :324, `delayStep` :334, `branchStep` :349, `parallelStep` :363.
- External step SPI: `ExternalStepExecutorRegistry.register(factory)` (`WorkflowStepExecution.kt:195`); `ExternalStepExecutorFactory` :173, `ExternalStepExecutor` :178, `ExternalStepExecutorResolver` :186-190. Missing executor → `ExternalStepExecutorNotRegisteredException` (:182).

## Files normally changed

- New step class (sealed subclass of `InternalWorkflowStep<S>`) in `tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/` (template: `LocalWorkflowStep` :93, `AiWorkflowStep` :104, `GateWorkflowStep` :123, `PluginWorkflowStep` :140; `DelayWorkflowStep` in `WorkflowDelayCoordinator.kt:23`).
- DSL function in `AbstractWorkflowBuilder` (`WorkflowBuilder.kt`).
- **Exhaustive-`when` wiring points (compiler-enforced):**
  - `replayDescriptor` `when` — `Workflow.kt:120-136`
  - definition digest — `WorkflowDefinitionCompatibility.kt:19-46` (sha256 over canonical definition)
  - worker binding — `WorkflowBindingRegistry.bind(workflow, persistence)` `WorkflowBindingRegistry.kt:82`
  - suspension rule — `WorkflowBuilder.kt:484-496` (nested suspension rejected)
- Tests in `tramai-orchestration/src/test/kotlin/.../` (step-level + replay/failure boundary).
- `docs/workflow-api-stability-boundary.md` — new public DSL additions must be classified (guarded by `verifyWorkflowApiStabilityBoundary`).

## NOT changed

- **`WorkflowExecutionSupervisor`** (:37), **`WorkerLifecycleController`** (:67), **`WorkerShutdownCoordinator`** — steps execute through the runner; they don't touch these directly.
- **Store SPIs** — `WorkflowCheckpointStore`, `WorkflowLeaseStore`, `StepAttemptRecordStore` only change when you add a *store implementation* (see `adding-a-store.md`).
- **Suspension semantics** — only `suspensionMode = TOP_LEVEL_CHECKPOINT` steps may suspend, top-level only; ASM-guarded by `WorkflowStepExecutionArchitectureTest.kt:29` (checks the getter returns the constant directly).
- **Analyzer/baseline** — `config/quality/0.6.0-baseline.json` never changes in the same PR.

## Required tests / TCK

- Step-level test following `WorkflowStepFailureBoundaryTest.kt` / `WorkflowReplayDecisionPolicyTest.kt` patterns.
- Replay/digest: `WorkflowDefinitionDigestGoldenTest.kt` (digest changes must be deliberate), `BinaryCompatibilityFixtureTest.kt` (fixture `src/test/resources/binary-compat/BinaryCompatFixture.kt`), `WorkflowCheckpointLegacyMigrationContractTest.kt`.
- Store TCKs only if you also add a store: `WorkflowCheckpointStoreTck.kt:32`, `WorkflowLeaseStoreTck.kt`, `WorkflowLeaseCheckpointFenceTck.kt` (tramai-testing) + enrollment guards (`WorkflowLeaseStoreTckEnrollmentArchitectureTest.kt` etc.).
- Cancellation: `verifyCancellationSafety` (cancellation scanner) + `WorkflowCancellationContractTest.kt`.

## Compatibility

- **Definition digest changes are breaking:** the sha256 canonical digest (`WorkflowDefinitionCompatibility.kt:19-46`) drives recovery/fencing — a new step with different digest semantics invalidates in-flight checkpoints. Update the golden test deliberately.
- **Public DSL = public API:** new DSL functions are covered by `docs/workflow-api-stability-boundary.md` and `verifyWorkflowApiStabilityBoundary` (wired into `check`); `apiCheck` enforces the BCV dump.
- **Replay compatibility:** the `replayDescriptor` `when` must round-trip the new step, or replay of in-flight workflows fails.

## Failure / cancellation / lifecycle

- All steps route through `WorkflowStepExecutor.executeStep` (`WorkflowStepExecutor.kt:36-54`): `StepCounter` budget (:67), observer events, **failure sanitisation** (safe public errors, no internal exception leakage), `CancellationException` passthrough.
- Lifecycle: step execution is bounded by the worker lifecycle (supervisor → lease → recovery); a suspending step creates a durable checkpoint before suspension (checkpoint store semantics in `adding-a-store.md`).

## Verification

```bash
./gradlew :tramai-orchestration:test
./gradlew verifyWorkflowApiStabilityBoundary
./gradlew apiCheck
./gradlew verifyCancellationSafety
./gradlew verifyPr
./gradlew verifyChangePolicy -PchangeClass=runtime-behaviour   # or public-api
# publish smoke (CI parity):
./gradlew publishToMavenLocal && ./gradlew -p examples/kotlin-springboot-example test -PtramaiVersion=$(grep '^tramaiVersion=' gradle.properties | cut -d= -f2)
```

## Common mistakes

- Adding a step without updating the exhaustive-`when` wiring — the compiler will tell you, but only after you touch the right file.
- New suspending steps — suspension mode is frozen by the ASM architecture guard; don't add them casually.
- Depending on internal/excluded/application modules — `config/quality/module-boundaries.yml` forbids `published → internal` (:69-72), `published → applications-examples` (:60-62), `published → excluded` (:65-67), self-edges (:75), cycles (programmatic).
- Forgetting `docs/workflow-api-stability-boundary.md` classification — `verifyWorkflowApiStabilityBoundary` fails.

## Related ADRs / specs

- [ADR-017](../../adr/adr-017.md) — keep orchestration typed, workflow-owned, optional above tramai-engine
- [spec-001-core-engine.md](../../specs/spec-001-core-engine.md) — core + engine contract
- [`docs/workflow-api-stability-boundary.md`](../../workflow-api-stability-boundary.md) — public DSL stability rules
