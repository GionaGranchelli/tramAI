# Change Guide: Adding a Workflow Step

**Applies to:** `tramai-orchestration` step types, the `WorkflowBuilder` DSL, and the workflow persistence/execution seams.

## TL;DR

Workflow steps are a **builder DSL over a sealed internal step contract** — there is no public `WorkflowStep` interface for external implementation. Adding a built-in step means: a new sealed subclass + a DSL function + exhaustive-`when` updates + tests. Adding an *external* step means the `ExternalStepExecutorRegistry`.

## 1. Understand the step model

- Runtime contract: `InternalWorkflowStep<S>` (sealed) — `tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowStepExecution.kt:80-89`: `val name`, `suspensionMode` (default `NONE`), `suspend fun execute(request: WorkflowStepExecutionRequest<S>): WorkflowStepExecutionResult<S>` (sealed `Completed(state)` / `Suspended`, L26-32).
- Built-in impls are sealed subclasses: `LocalWorkflowStep` (:93), `AiWorkflowStep` (:104), `GateWorkflowStep` (:123), `PluginWorkflowStep` (:140), `DelayWorkflowStep` (`WorkflowDelayCoordinator.kt:23`, the only built-in with `suspensionMode = TOP_LEVEL_CHECKPOINT`, :28); Shell/Http/Hermes/Codex/Mcp/Branch/Parallel in their own files.
- DSL: `WorkflowBuilder.kt:16` extends `AbstractWorkflowBuilder<S>` (:71, owns the step list :72, `appendStep` :377, `stepsSnapshot` :384). Step functions: `localStep` :75, `aiStep` ×4 :96-161, `httpStep` :180, `shellStep` :190, `hermesStep` :206/220, `codexStep` :235/249, `mcpStep` :264/280, `pluginStep` :297/310, `gateStep` :324, `delayStep` :334, `branchStep` :349, `parallelStep` :363. Entry: `workflow(name, definitionVersion)` (`Workflow.kt:98-114`), `build(...)` (`WorkflowBuilder.kt:26-37`), build-time validation (duplicate names, static command policies, nested-suspension rejection) at `WorkflowBuilder.kt:470-496`.

## 2. External/plugin steps (no new sealed type needed)

- `ExternalStepExecutorRegistry.register(factory)` — `WorkflowStepExecution.kt:195`; interfaces `ExternalStepExecutorFactory` :173, `ExternalStepExecutor` :178, `ExternalStepExecutorResolver` :186-190. Consumed via `pluginStep` + `Workflow.requiredExternalStepTypes()` (`Workflow.kt:78`). Missing executor → `ExternalStepExecutorNotRegisteredException` (:182).

## 3. Wiring points for a new BUILT-IN step (beyond class + DSL)

| Point | File | Why |
|---|---|---|
| `replayDescriptor` exhaustive `when` | `Workflow.kt:120-136` | replay of the new step must round-trip |
| Definition digest | `WorkflowDefinitionCompatibility.kt:19-46` | sha256 over canonical definition — new fields change digests (golden test must be updated deliberately) |
| Worker binding | `WorkflowBindingRegistry.bind(workflow, persistence)` `WorkflowBindingRegistry.kt:82` | step execution needs the persistence session |
| Suspension rule | `WorkflowBuilder.kt:484-496` + ASM guard `WorkflowStepExecutionArchitectureTest.kt:29` | only `TOP_LEVEL_CHECKPOINT` steps may suspend, top-level only |

## 4. Execution path + stores you integrate with (usually unchanged)

- All steps route through the shared wrapper `WorkflowStepExecutor.executeStep` (`WorkflowStepExecutor.kt:36-54`): `StepCounter` budget (:67), observer events, failure sanitisation, `CancellationException` passthrough.
- Stores (SPIs in `tramai-orchestration`): `WorkflowCheckpointStore` (`WorkflowPersistence.kt:72-171`, `WorkflowStateCodec<S>` :63), `WorkflowLeaseStore` (`WorkflowLease.kt:33-51`, `WorkflowLeaseCheckpointFence` :56-72), `StepAttemptRecordStore` (`StepAttemptRecord.kt:93-121`). A new step type does not change these; a new *store implementation* is the `adding-a-store` guide.
- Lifecycle ownership: `WorkflowExecutionSupervisor` (:37), `WorkerLifecycleController` (:67), `WorkerShutdownCoordinator` — steps execute through the runner, they don't touch these directly.

## 5. Mandatory contract tests

- Step-level test following the pattern of `WorkflowStepFailureBoundaryTest.kt`, `WorkflowReplayDecisionPolicyTest.kt`.
- Replay/failure boundary + digest: `WorkflowDefinitionDigestGoldenTest.kt`, `BinaryCompatibilityFixtureTest.kt` (fixture `src/test/resources/binary-compat/BinaryCompatFixture.kt`), `WorkflowCheckpointLegacyMigrationContractTest.kt`.
- Store TCKs only if you also add a store: `WorkflowCheckpointStoreTck.kt:32`, `WorkflowLeaseStoreTck.kt`, `WorkflowLeaseCheckpointFenceTck.kt` (tramai-testing) + enrollment guards (`WorkflowLeaseStoreTckEnrollmentArchitectureTest.kt` etc.).

## 6. Module boundaries

- `:tramai-orchestration` = layer `runtime-execution`, published, preview — `config/quality/module-catalog.yml:196-201`.
- `config/quality/module-boundaries.yml` forbidden edges: `published → internal` (:69-72), `published → applications-examples` (:60-62), `published → excluded` (:65-67), self-edges (:75), cycles (programmatic). A step inside orchestration may depend on any **published** module in an allowed layer; **not** internal/excluded/application/example modules, and no cycles.
- New public DSL additions must be classified in `docs/workflow-api-stability-boundary.md` (guarded by `verifyWorkflowApiStabilityBoundary`, wired into `check`).

## 7. Mandatory verification

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

**Guardrails:** `verifyMaintainabilityBaseline` must stay green with **no** baseline/deviation edits; adding a step type forces exhaustive-`when` updates (compiler will tell you where); suspension mode is frozen by the ASM architecture guard — do not add new suspending steps casually.
