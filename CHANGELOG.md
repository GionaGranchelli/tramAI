# Changelog

## Unreleased

### Added

- **ApprovalStore compatibility TCK (PR #267, Epic 8.1a).** One shared
  behavioral contract for every `ApprovalStore` implementation, in
  tramai-testing testFixtures: `ApprovalStoreTck` runs **37 cases** —
  creation/read (10: PENDING round-trip, missing-ID null, duplicate
  conflict, non-zero version / non-PENDING / pre-populated decision /
  consumption fields / blank ID / future requestedAt / invalid expiry all
  rejected), transitions (10: APPROVED/DENIED with decided fields + version
  1, expired→TIMED_OUT, timeout-before-expiry and approve/deny-after-expiry
  rejected, terminal re-transition rejected for every terminal status,
  stale-version conflict, missing-approval not-found, version increments
  exactly once),
  consumption/replay (15: fresh consumption with replayed=false + consumed
  fields + version 2, wrong token / wrong consumedBy / stale version /
  PENDING / DENIED / TIMED_OUT / expired-unconsumed rejected, exact replay
  returns the same durable record with no write and stays valid after
  expiry, replay with stale version and wrong token rejected on the replay
  path, consume on missing approval not-found, fresh consumption records
  the advanced clock instant, rejected consumption is non-mutating), and
  concurrency (2: concurrent transition race — exactly one wins and one
  conflicts with version 1; concurrent identical consumption — one fresh +
  one replay receipt referencing the same durable record). Both race cases
  run on parallel workers with a start barrier, so they genuinely overlap
  rather than serializing on the test event loop. Deterministic
  `MutableClock` owned by the TCK (no sleeps, no `Instant.now()`); typed
  failure taxonomy pinned (Conflict / NotFound / TokenRejected /
  NotConsumable / IllegalTransition / IllegalArgumentException). Three
  runners execute the same contract: `InMemoryApprovalStoreTckTest`
  (tramai-security), `FileApprovalStoreTckTest` (tramai-persistence-file,
  encrypted per-case temp-dir harness), `JdbcApprovalStoreTckTest`
  (tramai-persistence-jdbc, PostgreSQL testcontainers, table reset per
  case) — 37/37 each. `ApprovalStoreTckEnrollmentArchitectureTest`
  scans every module's main source set for concrete `ApprovalStore`
  implementations (including body-less declarations) and requires a
  `<Store>TckTest` runner in the same module that actually extends the TCK,
  so a future `RedisApprovalStore` cannot merge unenrolled. Mutation
  evidence (each restored): removing the expectedVersion check, allowing
  terminal-state re-transitions, incrementing version during exact replay,
  replacing consumedAt during exact replay, allowing expired fresh
  consumption, and splitting the consume into check-then-act without
  atomicity each turn TCK cases RED. One production change: the JDBC
  consume path now takes a `FOR UPDATE` row lock inside an explicit
  transaction so concurrent identical deliveries serialize into one fresh +
  one replay (previously the loser could fail the conditional UPDATE).
  Zero public API change, no
  persisted format or schema changes, no existing tests deleted — existing
  suites remain the implementation-specific regression oracle (encryption,
  permissions, corruption, record format for file; SQL schema, JSON mapping,
  connection cleanup for JDBC). Epic 8.1 stays IN PROGRESS. Reference:
  `docs/reference/persistence-store-compatibility-contract.md`.

- **ApprovalContinuationStore compatibility TCK (PR #269, Epic 8.1b).** One
  shared behavioral contract for every `ApprovalContinuationStore`
  implementation — memory, encrypted file, and JDBC — pinning the
  PENDING/CLAIMED/COMPLETED/EXPIRED/CANCELLED/CANCELLED_UNCERTAIN state
  machine, strict optimistic concurrency, and the exactly-once release of
  raw sensitive arguments (the only API path that exposes them).
  `ApprovalContinuationStoreTck` (tramai-testing testFixtures) runs **50
  cases** — creation/read (13), claim (5), exactly-once release (2), expiry
  (8), cancellation (4), completion (5), recovery (8), sweep (3), and
  concurrency (3 real parallel races with a start barrier, 5 iterations
  each). Typed failure taxonomy pinned (Conflict / NotFound / NotClaimable /
  NotCompletable / IllegalArgumentException). Three runners execute the same
  contract (50/50 each): `InMemoryApprovalContinuationStoreTckTest`,
  `FileApprovalContinuationStoreTckTest` (per-case encrypted temp dir),
  `JdbcApprovalContinuationStoreTckTest` (PostgreSQL testcontainers, table
  reset per case). The TCK exposed **three real JDBC divergences**, all
  fixed: late `cancel()` now persists EXPIRED then fails Conflict (was
  CANCELLED), `create()` validates `argumentsDigest` against the payload
  (was accepted unchecked), and `claimForExecution()` checks version before
  status (was NotClaimable on stale-version claims of CLAIMED rows). A
  follow-up review found a fourth in the same family — the claim CAS-loss
  re-read mapped a lost claim/cancel race to NotClaimable instead of
  Conflict; fixed with the same version-before-status precedence and pinned
  by a deterministic interleaving regression (gated codec: claim blocks at
  decrypt, cancel wins, released claim must throw Conflict). The shared
  race assertions were tightened from "a typed failure" to exactly
  `Conflict` on the loser. The
  enrollment guard from #267 was extracted into a shared
  `StoreEnrollmentScanner` and extended to continuation stores. Mutation
  evidence (8 mutations, each restored): claim leaves arguments stored,
  claim skips version increment, claim drops version + status guards
  (concurrent claims both succeed), CLAIMED lazy-expires / sweep touches
  CLAIMED, late cancel produces CANCELLED, complete ignores claimedBy,
  forceCancelClaimed accepts PENDING, findStaleClaimed drops secondary
  ordering — each turns shared TCK cases RED. Zero public API change, no
  persisted format or schema changes, no existing tests deleted. Epic 8.1
  stays IN PROGRESS. Reference:
  `docs/reference/persistence-store-compatibility-contract.md`.

- **Structured-output contract TCK (PR #266, Epic 7.2).** One reusable test
  kit drives the entire structured-output lifecycle per fixture — descriptor
  compilation → generated schema → raw JSON shape validation →
  deserialization → runtime value validation → deterministic repair
  feedback — from a single source of truth (`StructuredOutputContractCase`),
  so no layer maintains independent fixture lists. The matrix
  (`JacksonStructuredOutputContractTckTest`, 28 cases) covers Kotlin data
  classes, JavaBeans, nullability, missing primitives (rejected at SHAPE
  before Jackson primitive defaults hide them), nested objects, generic and
  nested collections, root arrays, **root scalars (enum/integer/double/
  boolean)**, `@AiRange`/`@AiMinItems`/`@AiDescription`, unknown properties
  (rejected at SHAPE even under a lenient ObjectMapper), recursion,
  unsupported maps, malformed JSON, prose/fenced JSON extraction, and repair
  determinism — plus the #262 enum regression class: root/nested/nullable
  enums, every declared value succeeds, unknown values fail through
  deserialization, and the legacy `{name, ordinal}` object form stays
  rejected (enum membership remains deliberately delegated to Jackson,
  verified by the TCK). SHAPE vs VALUE_VALIDATION stages (shared summary by
  design) are proven by exercising the layers directly — shape validator
  rejects / shape accepts + Jackson succeeds + value validator rejects — so
  message-phrasing refactors cannot keep a case green. Compile-failure
  fixtures assert `IllegalArgumentException`/`IllegalStateException`
  specifically. `StructuredContractFingerprintEvolutionTest` (18 tests)
  mutates exactly one semantic element at a time and proves each changes the
  SHA-256 hash, with inverse tests proving `ValueAccessor`, compiler
  instances, and `Object.typeName` never leak into it. The fingerprint fix:
  `typeName` is compiler/diagnostic metadata, not part of the JSON contract —
  equivalent Kotlin and JavaBean DTOs now fingerprint identically (previously
  the class name participated in the hash, so fixture-class-name changes
  could mask ignored mutations). Fingerprint stays internal; zero public API
  change. Mutation evidence (5): ignoring `@AiRange`, disabling required-
  property shape enforcement, dropping fingerprint components, reverting the
  complete-JSON extractor path, and removing unknown-property shape rejection
  each turn the TCK RED. Two production inconsistencies surfaced and fixed:
  (1) the extractor now accepts a complete trimmed JSON value before
  object/array bracket search, so structured scalar roots round-trip instead
  of failing "Could not extract JSON content" (prose-wrapped scalars remain
  un-extractable); (2) `additionalProperties:false` is now enforced by the
  shape validator (`Property 'x' is not allowed`), independent of the
  consumer's Jackson configuration — previously delegated to Jackson
  deserialization, it was silently weakened by a custom ObjectMapper with
  `FAIL_ON_UNKNOWN_PROPERTIES=false`. Reference:
  `docs/reference/structured-output-contract-tck.md`.

- **Authoritative structured type descriptor (PR #265, Epic 7.1).** The
  structured-output implementation is no longer one ~900-line handler with
  four independent dispatch trees (schema Kotlin/JavaBean, raw-JSON shape
  Kotlin/JavaBean, value validation Kotlin/JavaBean). Each target type is now
  compiled exactly once into an immutable, language-neutral
  `StructuredTypeDescriptor` (Scalar / Enum / Collection / Object with
  explicit nullability, requiredness, descriptions, ranges, min-items, and a
  `ValueAccessor`), then schema generation, JSON shape validation, value
  validation, fingerprinting, and caching consume that descriptor.
  `KotlinStructuredTypeCompiler` owns all Kotlin reflection;
  `JacksonJavaBeanStructuredTypeCompiler` owns all Jackson introspection; an
  immutable active-path `CompileContext` gives one shared recursion contract
  (siblings of the same type compile fully; genuine cycles fail with the
  language-neutral error "Recursive structured output type is unsupported").
  `Enum` is a first-class descriptor kind because PR #262 showed schema/parser
  drift re-enters when enums are collapsed into another category; JavaBean
  enums are now compiled to enum descriptors instead of falling to
  "Unsupported". The renderer, shape validator, and value validator are pure
  descriptor consumers (no KType/KClass/JavaType), enforced by a mutation-
  resistant ASM architecture guard. `StructuredContractFingerprint` computes a
  stable SHA-256 fingerprint from a canonical walk (runtime accessors
  excluded) and stays internal — Epic 7.2 will pressure-test it before any
  stable API decision. `StructuredDescriptorCache` is instance-scoped
  (automatically bound to the handler's `ObjectMapper`), concurrency-safe via
  `computeIfAbsent`, and never caches failed compilations. Behaviour
  preserved: all pre-existing structured-output tests (63) stay green, error
  messages unchanged, except the recursion message unified to language-neutral
  wording; missing Kotlin required properties now fail shape validation (they
  previously fell through to deserialization), matching the schema `required`
  list. Descriptor-focused suites added: compilation, JavaBean parity,
  schema/validation agreement, fingerprint stability, cache concurrency.

- **Shared provider transport utilities (PR #258, Epic 6.2).** New
  `dev.tramai.core.provider.transport` package in `tramai-core` centralises
  the low-level HTTP/stream mechanics that were duplicated across adapters:
  `parseRetryAfterMillis` (injected `java.time.Clock`, default `systemUTC` —
  the hidden `System.currentTimeMillis()` wall-clock dependency in
  `Retry-After` date parsing is gone), `rejectedProviderHttpResponse` (one
  primitive for the rejected-response lifecycle: bounded 8 KiB body read,
  deterministic closure, debug-metadata logging, fail-open diagnostic
  observer delivery, `Retry-After` propagation — the caller decides throw vs
  `StreamChunk.Error`), `providerJsonRequest` (URI + JSON `Content-Type` +
  normalized timeout + POST framing), and SSE framing helpers
  (`readSseDataPayload`, `sseDataPayload`, `sseEventName` — prefix stripping
  and field skipping only; payload interpretation stays in each adapter).
  Migrated in order: OpenAI-compatible (DeepSeek benefits via delegation),
  Azure OpenAI, Anthropic, Gemini, Ollama; Bedrock intentionally keeps its
  AWS SDK transport. Authentication headers, endpoints, JSON wire formats,
  tool semantics, usage extraction, and stream interpretation remain
  adapter-owned so each provider's wire contract stays visible in its
  source. No universal provider transport abstraction (Epic 6.2 guardrail).
  New transport unit tests: `ProviderRetryAfterTest`,
  `ProviderHttpResponseTest`, `ProviderSseTest`; all eight #257 TCK runners
  stay green. API surface: 6 additive transport functions recorded in the
  `tramai-core` dump; no existing signature changed.

- **Provider Technology Compatibility Kit (PR #257, Epic 6.1).** Every published provider now runs the same deterministic, offline contract in `tramai-testing` test fixtures: `ProviderTck` (~29 tests per runner) against a `StubHttpClient` (JDK-21 `HttpClient` stub with canned responses, transport failures, cancellation arm, and body-close tracking) plus protocol-shaped fixture bodies per wire format (OpenAI-compatible, Anthropic, Ollama, Gemini). The harness pins the expected provider id and the exact capability set from the test, not from the provider under test — a provider cannot skip a contract by returning `supportsCapability(...) == false` (capability pins require their fixture specs at construction). Coverage: identity, cancellation, safe-error redaction (credentials/parser detail/bounded preview), HTTP timeout propagation, retryable-status mapping, numeric Retry-After, usage/reasoning-token extraction, outbound tool serialization + tool-call parsing (tool-only responses are not empty-text failures), vision base64+mime encoding, structured output, streaming (order, single Complete, fullText, terminal Error, malformed termination, closure on cancellation/early stop/completion). Runners for OpenAI-compatible, OpenAI, Azure OpenAI, Anthropic, Ollama, Gemini, Bedrock, and DeepSeek all green; `ProviderTckEnrollmentArchitectureTest` fails the build if a roadmap provider loses its runner or a new `ModelProvider` appears without one. Contract doc: `docs/reference/provider-compatibility-contract.md`. The TCK forced three production fixes: Anthropic tool translation (outbound `tools`/`input_schema`, inbound `tool_use` → `ToolCall`, tool-only responses valid), Ollama `VISION` + `STREAMING` pinned via a protocol-aware `VisionSpec` (base64 image payload without a MIME marker, per the Ollama wire protocol), and Bedrock — internal `BedrockRuntimeClientFactory` seam (AWS types stay out of the public API), production-owned client closure, and real incremental streaming via `BedrockRuntimeAsyncClient.invokeModelWithResponseStream` (was a synchronous whole-result single token). No shared transport abstraction introduced (Epic 6.2 owns that); zero public API diff.

- **Typed runtime event catalogue (PR #254, Epic 5.2).** `tramai-core` now owns every runtime event identifier, attribute key, and metric descriptor in `dev.tramai.core.observation.event`: `RuntimeEventCatalogue` (domain, sensitivity, audit/evidence eligibility, allowed + required attributes, metric mapping per event), typed `RuntimeAttributeKey<T>` with one canonical value type per key, `RuntimeMetrics` (20 descriptors), and a compile-time `RuntimeEvents` registry. `RuntimeEvent.of(...)` builds validated events and rejects out-of-schema keys, missing required attributes, and wrong value types at construction (value types are also enforced at runtime against generic erasure); catalogue initialisation fails fast on duplicates and type conflicts. The engine, workflow, worker/operation OTEL observers, scheduler, server/platform run-store protocol, and sovereign ops outbox metrics all consume the catalogue — event and metric names preserved byte-for-byte, `OperationObservation.onEngineEvent(RuntimeEvent)` is an additive overload (no public API break), and dynamic workflow context stays under the explicitly declared `DynamicAttributeNamespaces.WORKFLOW_CONTEXT`. A fail-closed two-layer architecture test — ASM LDC bytecode scan of the four core modules' built jars plus a repository-wide source scan of every `tramai-*` module (with a declared Spring config-property-namespace allowance) — rejects any `tramai.*` identifier literal outside the catalogue (mutation-verified on both layers); it drove the migration of 40+ real un-catalogued literals (approval replay, token budget, provider route, workflow security/delay/http/mcp/shell, persistence leases/checkpoints, runner suspension, scheduler wake-ups, run-store SSE events, sovereign outbox worker). Reference docs are generated from the catalogue (`docs/reference/runtime-event-catalogue.md`) and drift-checked by a committed-doc test. Note: size/attempt/route-index/prompt/response/duration/exit-code attribute values are now `Long` (canonical catalogue types) where legacy code mixed `Int`.

- **Separated idempotency, retryability, and replayability (PR #253, Epic 5.1).** Tool failures are now classified as transient independently of repetition safety: `ToolInvocationExecutor` maps every generic tool exception to `ToolResult.TransientFailure`, the attempt budget is uniform, and `ToolRetryPolicy` alone combines retryable-failure × repeat-safe (`tool.idempotent`) × attempts-remaining into Retry/Stop. A non-idempotent tool still never executes twice. `TramaiTool.idempotent` now documents repetition safety, not retryability. On the workflow side, `WorkflowStepReplayDescriptor` became two-dimensional — `WorkflowStepReplayability` × `WorkflowStepRepetitionSafety` × idempotency key — with a pure `WorkflowReplayDecisionPolicy` as the single recovery-decision owner; `WorkflowRecoveryCoordinator`/`WorkflowRecoveryController`/`WorkflowExecutionSupervisor` consume the descriptor, and steps were reclassified (shell/hermes/codex/mcp/parallel = REPLAYABLE+UNSAFE; plugin = NON_REPLAYABLE+UNSAFE; HTTP POST/PATCH without key = REPLAYABLE+UNSAFE). The legacy `ReplayPolicy` enum remains as the schema-v1 persistence-compatibility encoding, architecture-guarded to the codec/JDBC boundary and the retained public aiStep overloads; no checkpoint/attempt-schema change, existing records remain readable. Recovery contract, durable file/JDBC recovery, worker takeover, and store-contract suites all green; #215–#218 semantics unchanged.

- **Worker state-machine decomposition (PR #251).** TramaiWorker is now a thin public façade over internal, instance-scoped subsystems — `WorkerLifecycleController` (sole root-coroutine owner), `CheckpointPoller`, `LeaseCoordinator`, `LeaseRenewalLoop`, `WorkflowExecutionSupervisor`, `WorkerHeartbeatPublisher`, `WorkerShutdownCoordinator`, and `WorkflowRecoveryCoordinator`. Pure structural extraction: polling, leasing, renewal, execution, heartbeat, shutdown sequencing, and recovery semantics preserved verbatim; zero public API diff; no checkpoint/persistence schema change.

- **Instance-scoped typed workflow bindings (PR #250).** Removed the process-global `WorkerWorkflowBindings` singleton, `registerWorkerBinding(...)`, and the implicit registration side effects in `Workflow.run()`/`resume()`; introduced `WorkflowBindingRegistry { bind(workflow, persistence) }` — an immutable, composition-time-validated registry that pairs `Workflow<S, R>` with `WorkflowPersistence<S>` so the state-codec relationship is compiler-checked and never reconstructed through name-only lookup or unchecked casts. `TramaiWorker` now takes `workflowBindings` explicitly; checkpoint resolution uses workflow name + definition version (multiple versions of the same name can coexist). Same name/version with conflicting state/result types or a different workflow definition is rejected when the registry is built; duplicate registration fails deterministically. Preview API break: the worker constructor's `workflowRegistry: Map<...>` parameter is replaced by `WorkflowBindingRegistry`, and `registerWorkerBinding` is removed (api dump updated intentionally). Epic 4.3 is complete.

- **Authoritative provider routing plan (PR #229).** Introduced `ProviderRoutingPlan` in `tramai-core` — the single immutable snapshot of configured provider routing (`providers`, `routes`, `defaultProvider`) with typed `ProviderId`/`ModelId` value classes and fail-fast build-time validation. Duplicate provider registration now fails with `ConfigurationException` instead of silently replacing the earlier registration; blank IDs, unknown primary/fallback providers, duplicate fallback routes, and unknown defaults are rejected at construction. `ProviderRegistry` remains a public compatibility façade over the plan with unchanged API and resolution order (`ProviderRoute`/`ResolvedProviderRoute` JVM shapes unchanged). The engine freezes the plan into `EngineComponents.ProviderComponents`; standalone composes through the plan builder; `SovereignTramai.Builder` deleted its shadow routing state (`registeredProviders`, `primaryModelRoutes`, `fallbackRoutes`, `defaultProviderName`, `FallbackRoute`) and now validates the shared plan via `SovereignRoutingValidationPolicy`; Spring resolves property-provider vs bean precedence into one unique provider set before the plan builder (explicit beans still override property-backed providers; genuine duplicate user beans fail deterministically). Routing-related sovereign evidence and artifact-verification targets derive from the same frozen plan. Epic 2.2 is complete.

- Refactored the engine's internal runtime composition into an immutable component snapshot; zero public API change and no execution-semantic changes, except the intentional fail-fast rejection of invalid partial approval composition at the engine component boundary.

- **Explicit runtime lifecycle ownership (PR #226).** `Tramai` and `SovereignTramai` are now `AutoCloseable` and own exactly one lazily-created runtime (one engine) shared by every `create()`/`runtime()` call — previously every `create()` leaked an unreachable engine. Closing is idempotent and concurrency-safe; after close, `create()`/`runtime()` and old proxies fail fast with a fixed `IllegalStateException` before any provider work. `TramaiEngine.close()` cancels once and awaits engine-hierarchy termination (self-close safe), and terminates in-flight suspend invocations; the caller continuation is always resumed exactly once. Spring closes the shared runtime via `destroyMethod = "close"`, so multiple `@AiService` beans share one owned engine. TramAI closes only resources it creates; externally supplied providers/stores/clients/observers remain caller-owned. API surface addition is additive: `Tramai`/`SovereignTramai` gain `close()`; all constructor descriptors remain byte-identical to 0.5.0 (note: adding the `AutoCloseable` supertype is source-compatible but affects compiled negative-`instanceof` checks). Epic 1.3 Runtime Lifecycle Ownership is complete.

- **Safe persistence failure boundaries (PR #225).** Persistence stores expose fixed, cause-free failure text; raw paths, SQL, and payloads flow only to `PersistenceFailureDiagnosticObserver`; worker observers receive safe failures; existing exception and store ABI is preserved by the binary fixture. Epic 1.2 Safe Error Boundaries is complete.

- **Safe provider and built-in workflow-step failure boundaries (PRs #222, #223).** Provider HTTP rejections and built-in HTTP, shell, MCP, Codex, and Hermes workflow failures expose fixed cause-free public exceptions with typed failure codes. Original failure detail is retained only by an explicitly configured, fail-open diagnostic observer; public workflow events omit URLs, commands, raw tool names, and failure reasons. Existing public exception constructor descriptors remain compatible with 0.5.0 clients.

- **Safe tool-failure boundaries (PR #219).** Established the first slice of Epic 1.2: raw exception details no longer cross built-in model-visible tool boundaries, while original causes remain available to an explicitly configured fail-open `ToolFailureDiagnosticObserver`. Added diagnostic-only `ToolFailureCode` classifications (`tool.input.invalid`, `tool.execution.failed`, `tool.execution.retry_exhausted`) with fixed model-visible defaults; caller-visible failure mapping remains pending. `ToolResult` retains exactly its four 0.5.0 variants without deprecations, preserving exhaustive-`when` source compatibility, and adds `safeInvalidInput(...)`/`safePermanentFailure(...)` factories for validated or fixed text. `ModelVisibleToolMessage` is a regular class with a private constructor and validated `@JvmStatic trusted(...)` factory (non-blank, ≤512 chars, code-point-aware rejection of control, separator, and FORMAT characters), with no generated `copy` or destructuring bypass. `ToolInvalidInputException(String)` remains public with diagnostic-only text plus `withSafeModelMessage(...)`. The engine and standalone adapter never derive model-visible text from `Throwable.message`, classify diagnostics from their own control flow, emit fixed retry-exhaustion text, preserve cancellation, and treat diagnostic-sink failures as fail-open. The standalone builder still freezes the observer at `build()`. Custom tools written against the short-lived round-1 `SafeInvalidInput`/`SafePermanentFailure` variants must migrate to the safe factories or stable plain constructors. Caller-visible tool failure mapping and approval boundaries remain later slices.

- **Durable file and JDBC step-attempt stores (PR #218).** Added independent `FileStepAttemptRecordStore` and `JdbcStepAttemptRecordStore` implementations with canonical schema-versioned encoding, SHA-256 record fingerprints, strict fail-closed decoding, exact attempt identity preservation, atomic compare-and-set, deterministic ordering, cancellable file/JDBC operations, and configurable validated JDBC identifiers. A shared 20-case TCK covers the in-memory, file, and JDBC contracts, while restart-level file/JDBC tests prove recovery approvals, approved idempotency keys, safe partial transitions, and retained failed-workflow evidence. Checkpoint and attempt stores remain explicitly composed and independently managed; this adds no cross-store transaction or exactly-once external-side-effect guarantee.

- **Worker-visible recovery retry approvals (PR #217).** Added `StepAttemptResolutionAction` and key-bound `WorkflowRecoveryController.retryStep(...)`. Retry approval remains durable on the unresolved `UNKNOWN` attempt until a leased worker consumes it before execution; externally idempotent retries execute only when the current workflow definition produces the exact operator-approved key. Approval, consumption, and stale-approval voiding are atomic compare-and-set transitions on the attempt record, so a failed or stale operator request can never overwrite a concurrent successful authorization. Mismatches fail closed into recovery-required state, and `failWorkflow` records best-effort `WORKFLOW_FAILED` attempt evidence after successful checkpoint deletion. This does not provide exactly-once external side effects or `confirmCompleted`.

- **Workflow recovery semantics (PR #215).** Added durable `RECOVERY_REQUIRED` state for unknown-step outcomes: `WorkflowRecoveryReason`, `WorkflowRecoveryRecord`, `WorkflowRecoveryState` (Normal/Required) on `WorkflowCheckpoint`, `requireRecovery()`/`clearRecovery()` on `WorkflowCheckpointStore`, `WorkflowRecoveryController` (`retryStep`/`failWorkflow`), worker skip of Required checkpoints, lease-fenced recovery persistence, idempotency-key stability verification (`IDEMPOTENCY_KEY_MISMATCH`), JDBC `recovery_state` column + `migrationSql()`, File/Markdown/JDBC persistence, fail-closed decoding (`WorkflowCheckpointCorruptionException`), and `WorkflowRecoveryStateException`. New doc: `docs/concepts/workflow-delivery-semantics.md`.

  **Binary compatibility notice (intentional for 0.6.0):** the primary constructors of the public data classes `WorkflowCheckpoint` (new `recoveryState` field) and `StepAttemptRecord` (new `resolutionReason`, `resolutionAtEpochMillis`, `resolutionAction`, and `approvedIdempotencyKey` fields) changed, which replaces the old all-arg constructors and the generated `copy`/`copy$default` descriptors. Source compatibility is preserved via default values, but already-compiled consumers of those descriptors may observe `NoSuchMethodError`. This is accepted for 0.6.0; the API dump (`api/tramai-orchestration.api`) is the authoritative new surface.

- **Subprocess & OS-lock cancellation contracts (PR #216).** Introduced an internal shared `CancellableProcessLifecycle` (`ProcessSupport.kt`) used by ShellStep, AgentCliSupport (Hermes/Codex) and the MCP subprocess transport: attach-then-active-check cancellation handler, idempotent pipe-closing `requestTermination` (unblocks stdout/stderr readers), cancellable `onExit`-based `awaitExit`, and bounded `terminateAndAwait` with graceful → forced escalation and survivor reporting (`ProcessTreeSurvivorException`); cleanup failures are suppressed onto the primary `CancellationException`/domain exception and never replace it; MCP cleanup runs exactly once and cancellation never triggers reconnect. File persistence now maps `FileLockInterruptionException` (thrown by `FileChannel.lock()` on thread interrupt) to `CancellationException` in both lock helpers, so a cancelled cross-process OS-lock wait surfaces as cancellation, not a persistence failure. Added `FileLockHolderMain` helper JVM + cross-process lock-cancellation contract tests (marker-file handshake, no sleeps) and `SubprocessCancellationContractTest` (20 tests: pre-cancelled no-spawn, tree termination for Shell/Hermes/Codex/MCP, blocked-reader cancellation, no-reconnect, timeout-vs-cancellation, cleanup-failure suppression, escalation, bounded waits, idempotent cleanup, observer-failure ownership, handler disposal, reparented-descendant cleanup, and MCP cleanup-suppressed-under-cancellation). No public API changes.

- Coroutine cancellation correctness (PR #207). Introduced shared `rethrowIfCancellation()` extension in `tramai-core`. Added rethrow calls across all 7 audited provider adapter paths (OpenAI, Anthropic, Bedrock, Gemini, Ollama, DeepSeek, AzureOpenAI), engine execution (TramaiEngine, TramaiWorker), workflow steps (ShellStep, McpStep, HttpStep, Workflow), AgentCliSupport, JdbcWorkflowLeaseStore, and ProviderFailures. Extended `KotlinCancellationCatchScanner` to recognize `rethrowIfCancellation()` as a safe pattern. The scanner now scans all non-example modules (removed earlier per-module scoping). Added `ensureActive()` calls before `TimeoutCancellationException` to domain-exception mapping in TramaiEngine, ShellStep, McpStep, and AgentCliSupport. Created `CancellationContractTest` and `KotlinCancellationCatchScannerTest` additions. Aligned MQ-0005 deviation baseline to the measured 65-finding canonical population (baseline: 65, allowed: 65). PR regressions are enforced separately by verifyCancellationSafety. Replaced occurrence-based identity comparison with risk-population matching to prevent positional-shift false positives. Replaced committed-baseline local mode with auto-resolved merge-base comparison against origin/master. Marked Epic 1.1 in progress with runtime-boundary characterization note.

- Canonical public API and resolved dependency baseline (PR #205). Replaced filesystem-only API dump discovery with module-catalog-aware probe. Replaced cache-path dependency parsing with Gradle's `Configuration.incoming.resolutionResult` traversal. New `ApiBaselineVerifier` and `DependencyBaselineVerifier` with 12 typed diagnostic codes. New `CanonicalGradleProbe` for isolated worktree measurement. Zero runtime changes.

- **Outbound HTTP network boundary correctness (PR #227).** `httpStep` targets are governed by an explicit `OutboundNetworkPolicy` set per workflow and frozen at build (`outboundNetworkPolicy`, default `OutboundNetworkPolicies.defenceInDepth()`). The default policy allows public destinations and denies restricted IP space; governed policies additionally require an explicit hostname allowlist. Admission is two-phase: scheme and hostname decisions occur before DNS, then resolution and restricted-address filtering occur; DNS failure fails closed as a policy rejection. Redirect-following supplied clients are rejected before any request is sent. A transport that can prove its connected address re-validates it after connection but before request bytes are written and fails closed if it does not prove validation. JDK connect-time DNS resolution means application pre-resolution remains defence-in-depth, with firewall, proxy, service mesh, or Kubernetes NetworkPolicy as the authoritative egress boundary. `HttpStepConfig` and `httpStep` signatures are unchanged; the builder policy property is an additive public API.

## 0.5.0 - 2026-07-16

### Added

- Governed tool permission usage example (PR #201). Added a new `examples/tool-governance/` module with three deterministic, credential-free scenarios demonstrating ALLOW (customer lookup), DENY (account deletion), and REQUIRE_APPROVAL (payment processing) tool permission outcomes with dedicated `tool.permission` evidence. The example proves that exposure permission is independent from execution permission, that denied and approval-required tools never execute, and that tool events are partitioned from generic `policy.decision` evidence. Added a developer-facing [`governed-tool-use.md`](docs/guides/governed-tool-use.md) guide covering all three enforcement points, tool metadata, outcome handling, evidence inspection, privacy boundaries, and non-compliance claims. Added a documentation guard (`verifyToolGovernanceExample`) verifying module registration, file existence, guide content, and roadmap completeness. Updated the example selection guide, tool-calling guide, tool permission model, roadmap, and STATUS.md. No new runtime APIs, policy outcomes, REDACT_RESULT, ALLOW_INTERNAL_ONLY, MCP support, or compliance claims.
- Dedicated tool.permission runtime evidence family (PR #200). Tool enforcement events (`BEFORE_TOOL_EXPOSURE`, `BEFORE_TOOL_EXECUTION`, `BEFORE_TOOL_RESULT_REINJECTION`) are now partitioned into a dedicated `tool.permission` evidence family written to `runtime-evidence/tool-permissions.jsonl`, separate from generic `policy.decision`. Added `ToolPermissionRuntimeEvidenceExporter` with strict filtering: invalid decisions (`REDACT_RESULT`, `ALLOW_INTERNAL_ONLY`) are silently skipped. Metadata is enriched with the top-level `enforcementPoint` field. The bundle writer, contract validator, and shell verifier are all updated with tool.permission file mappings, decision allowlists, source component checks, metadata allowlists, and family-specific validation (required `toolName`, valid enforcement point, bounded risk levels, code-shaped reason codes). Added 26 exporter unit tests (sanitised metadata, deterministic digests, invalid decision filtering, empty input), 5 regression tests in the policy exporter (tool events excluded from `policy.decision`), 5 negative verifier fixtures, and a positive lifecycle test in the sovereign bundle (finalize → verify → tamper → reject → restore → re-finalize). Updated the runtime evidence export model and bundle map documentation. No automatic evidence export, Spring auto-configuration, or compliance claims.
- Runtime evidence bundle wiring (PR #199). Added `RuntimeEvidenceBundleWriter` in `tramai-security` that groups `RuntimeEvidenceRecord`s by event type and writes them atomically into a sovereign evidence bundle's `runtime-evidence/` section as three JSONL files (`policy-decisions.jsonl`, `approval-decisions.jsonl`, `provider-routing.jsonl`). Fail-closed validation checks schema version, event type, decision kind, event ID uniqueness, and digest format. The section is replaced atomically, preventing stale or partially written evidence. Extended the sovereign evidence bundle verifier with runtime-evidence JSONL structural validation: per-line JSON parsing, event/file correspondence, allowed decision kinds, expected source components, digest format, ISO-8601 timestamps, metadata allowlists enforced independently per event family, and unknown file rejection. Added manifest completeness hardening: every actual bundle file must have a corresponding `files[]` entry. Added 18 bundle writer unit tests, a three-exporter integration test, and 8 runtime-evidence negative verifier fixtures. Extracted `RuntimeEvidenceJsonlWriter` and `JsonObjectWriter` into a standalone file (no behavior change). Documentation updates to bundle map, export model, evidence chain, EVIDENCE.md, REVIEWER-GUIDE.md, RELEASE-READINESS.md, and roadmap. No automatic evidence export, Spring auto-configuration, new database tables, or compliance claims.
- 0.5.0 development baseline (PR #197). Changed the repository development version to 0.5.0-SNAPSHOT, recorded 0.4.0 as the latest stable release, aligned the post-sovereignty roadmap with the 0.5.0 train, and added verification against version-boundary drift. No runtime behavior or public APIs changed.
- Workflow API stability boundary documentation (PR #165). The boundary classifies TramAI workflow-facing APIs as stable, preview, internal, or deferred, and defines allowed and forbidden claims for workflow API stability. Covers AI service declaration, structured output, policy, approval gateway, sovereign configuration, testing, and exception types. This does not add runtime behavior, API changes, model calls, benchmark execution, compliance claims, production-readiness claims, or certification claims.
- Workflow API stability boundary verification (PR #166). The new `verifyWorkflowApiStabilityBoundary` Gradle task uses section-scoped checks to verify that the workflow API boundary document exists, contains the required stable/preview/internal/deferred classifications with correct API references, and that forbidden claims are properly rejected. The task is wired into `check`. This does not add runtime behavior, API changes, model calls, benchmark execution, runtime evidence export, compliance claims, production-readiness claims, or certification claims.
- Workflow lifecycle model documentation (PR #167). The lifecycle model explains how governed TramAI workflows move from request through contract binding, policy evaluation, provider/tool execution, structured output repair, approval gates, audit/persistence, and typed result or failure. It also reserves a future place for runtime evidence export. Cross-links to the workflow API stability boundary and the post-sovereignty roadmap. This does not add runtime behavior, API changes, model calls, benchmark execution, runtime evidence export, compliance claims, production-readiness claims, or certification claims.
- Structured output contract lifecycle documentation (PR #168). The document explains how TramAI moves from Kotlin/Java return types through contract/schema generation, validator annotations, provider output parsing, validation, repair feedback, retry exhaustion, and typed result or failure. It identifies stable, preview, internal, and deferred structured-output surfaces and records open questions for follow-up tests. Based on current code — where behavior was not proven, it is explicitly noted. This does not add runtime behavior, API changes, model calls, benchmark execution, runtime evidence export, compliance claims, production-readiness claims, or certification claims.
- Structured output contract evolution tests (PR #169). The tests verify that generated contracts reflect return-type field changes, that `@AIRange` and `@AIMinItems` contribute to schema generation and validation, and that parse/validation failures produce repair-friendly feedback. This does not add runtime behavior, API changes, custom validator support, model calls, benchmark execution, compliance claims, production-readiness claims, or certification claims.
- Structured output validator extension model documentation (PR #170). The document defines the future design boundary for custom structured-output validators, including annotation-based validators, SPI-based validators, schema contribution, runtime validation, repair feedback, stability boundaries, Java compatibility, security considerations, and open questions. This does not add runtime behavior, API changes, custom validator implementation, model calls, benchmark execution, compliance claims, production-readiness claims, or certification claims.
- Structured output repair feedback loop tests (PR #171). The tests verify that parse and validation failures replay the failed assistant output, add repair feedback as a user message, retry structured output generation, respect retry limits, and throw diagnostic StructuredOutputException errors on exhaustion. This does not add runtime behavior, API changes, custom validator implementation, Java boundary changes, model calls, benchmark execution, compliance claims, production-readiness claims, or certification claims.
- Java structured-output boundary smoke test (PR #172). The test proves a Java-defined @AiService interface with a Java DTO return type works through the TramAI standalone structured-output path: service creation, provider call with generated schema in the prompt, and Jackson deserialization into the Java DTO. It also documents a known gap: Java POJO properties are not visible to Kotlin reflection (KClass.memberProperties), so the generated schema has empty properties. This does not add runtime behavior, API changes, custom validator implementation, model calls, benchmark execution, compliance claims, production-readiness claims, or certification claims.
- JavaBean structured-output schema support (PR #198). Java DTOs with conventional bean accessors now contribute real properties, required fields, nested types, collection item schemas, and TramAI field annotations to generated structured-output contracts. JavaBean values now receive matching structural and annotation validation. Existing Kotlin schema behavior remains unchanged. Java records, arbitrary immutable POJOs, Java nullability inference, custom validators, and public API changes remain out of scope.
- Governed workflow quickstart guide (PR #173). The new [`docs/guides/governed-workflow-quickstart.md`](docs/guides/governed-workflow-quickstart.md) provides an end-to-end conceptual quickstart for building governed workflows with TramAI. Covering typed contracts, workflow step composition, policy gates, optional approval gates, persistence/audit notes, testing without real model calls, and common failure paths. Uses a claim triage domain. Links to the orchestration DSL, workflow lifecycle model, structured output contracts, approval gateway guide, testing guide, and roadmap. This is documentation only — does not add runtime behavior, API changes, a runnable example, model calls, benchmark execution, compliance claims, production-readiness claims, or certification claims.
- Minimal governed workflow example (PR #174). The new [`examples/governed-workflow`](examples/governed-workflow) module provides a runnable, deterministic claim triage workflow with typed contracts, a policy gate, an approval gate, and local step finalization. Demonstrates four scenarios: low-risk pass, restricted-claim policy rejection, high-risk approval rejection, and approved high-risk pass. No external model or API credentials required. Builds on the conceptual quickstart from PR #173. Links to orchestration DSL, structured output contracts, approval gateway guide, and testing docs. This does not add runtime behavior, API changes, real model calls, persisted approval resume, JDBC/file persistence wiring, benchmark execution, compliance claims, production-readiness claims, or certification claims.
- Workflow failure diagnostics smoke tests (PR #175). The tests verify that the governed workflow example exposes explainable failure diagnostics for policy-gate and approval-gate rejection paths, including exception type, gate name, rejection reason, completed step trail, failed step trail, and clean success-path observation. This does not add runtime behavior, API changes, model calls, persistence wiring, a new diagnostics API, compliance claims, production-readiness claims, or certification claims.
- Governed workflow testing guide (PR #176). The new [`docs/guides/governed-workflow-testing.md`](docs/guides/governed-workflow-testing.md) guide explains how to test governed workflows without real model calls, including deterministic fake services, success/failure path tests, gate rejection assertions, diagnostic step trails, and when to use provider-level testing tools such as `MockAiProvider`. This does not add runtime behavior, API changes, model calls, persistence wiring, compliance claims, production-readiness claims, or certification claims.
- Governed workflow troubleshooting guide (PR #177). The new [`docs/guides/governed-workflow-troubleshooting.md`](docs/guides/governed-workflow-troubleshooting.md) guide explains common governed workflow failures and fixes using a symptom → likely cause → inspect → fix model. Covers policy gate rejection, approval gate rejection, provider failures, structured output parse/validation failures, observer trail mismatches, missing final state, flaky tests caused by real model calls, and persistence/resume expectation mismatches. Includes a fast triage table and diagnostic guidance for each failure mode. This does not add runtime behavior, API changes, model calls, persistence wiring, a new troubleshooting API, compliance claims, production-readiness claims, or certification claims.
- Approval workflow ergonomics guide (PR #178). The new [`docs/guides/approval-workflow-ergonomics.md`](docs/guides/approval-workflow-ergonomics.md) guide explains practical approval workflow patterns and lifecycle: when to request human approval, the full lifecycle (requested, approved, denied, expired, invalid actor, missing role), six common patterns (approval before high-risk action, approval after AI classification, denial as first-class outcome, expired approval windows, role-based constraints, and approval evidence/audit notes), and what approval does and does not prove. This does not add runtime behavior, API changes, persistence wiring, approval resume behavior, new approval states, compliance claims, production-readiness claims, or certification claims.
- Approval resume example (PR #179). The new [`examples/approval-resume`](examples/approval-resume) module provides a minimal, runnable TramAI approval workflow with deterministic tests. Demonstrates four scenarios: low-value expense bypasses approval, high-value expense suspends for approval, approved expense resumes and reimburses exactly once, and denied expense does not reimburse. Uses embedded PostgreSQL (no Docker required) and exercises the `ApprovalGateway`, `ApprovalDecisionControlPlane`, `ApprovalResumeControlPlane`, and `SovereignTramaiRuntime` APIs.
- Approval decision evidence tests (PR #180). Strengthened [`JdbcSovereignOpsApprovalDecisionControlPlaneTest`](tramai-spring-boot-starter-sovereign-persistence-jdbc/src/test/kotlin/dev/tramai/spring/sovereign/persistence/jdbc/JdbcSovereignOpsApprovalDecisionControlPlaneTest.kt) to verify that approved and denied decisions emit durable, structured audit outbox evidence: aggregate type, event key, operation, actor, workflow run ID, correlation ID, approval status/version, reason digest/length, and status. New tests prove that raw decision comments are never stored directly (only digest + length), and that repeat decisions return typed `AlreadyApproved` results without creating duplicate mutation evidence.
- Repeat denial evidence test (PR #181). Added `repeat deny returns AlreadyDenied without creating duplicate evidence` to complete the duplicate-decision proof for the deny path, matching the existing repeat-approve coverage.
- Approval failure taxonomy guide (PR #182). The new [`docs/guides/approval-failure-taxonomy.md`](docs/guides/approval-failure-taxonomy.md) guide documents approval request outcomes (`Suspended`, `AlreadyApproved`, `AlreadyDenied`, `Expired`) and decision-control-plane outcomes (`Approved`, `Denied`, `AlreadyApproved`, `AlreadyDenied`, `Expired`, `NotFound`, `Conflict`) with terminal vs retryable classification, evidence semantics, and the distinction between workflow-facing and operator-facing boundaries. Closes Phase 4 — Approval & Human Gates.
- Runtime evidence export model (PR #183). The new [`docs/evidence/runtime-evidence-export-model.md`](docs/evidence/runtime-evidence-export-model.md) design doc defines the record shape, event families (policy decisions, approval decisions, provider routing), evidence bundle placement, verifier responsibilities, and privacy/sanitisation rules for exporting runtime decisions into reviewable evidence artifacts. No implementation, exporter, or bundle generation is included. Links from the sovereign lab evidence chain have been added.
- Policy decision runtime evidence export (PR #184). Added the first Phase 5 runtime evidence exporter for policy decisions, converting audited ALLOW, DENY, and REQUIRE_APPROVAL events into runtime-evidence.v1 records with strict `sha256:<64 lowercase hex>` digests, safe metadata preservation, and unsafe metadata exclusion. New types: `RuntimeEvidenceRecord`, `RuntimeEvidenceJsonlWriter`, `PolicyDecisionRuntimeEvidenceExporter`, and `EvidenceDigest`. Tests verify export shape, digest format, metadata safety, and JSONL validity. No approval export, provider routing export, bundle integration, compliance claims, or production-readiness claims are included.
- Approval decision runtime evidence export (PR #185). Added runtime-evidence.v1 export for approval decision outbox records, converting approved and denied human approval decisions into approval.decision records via `ApprovalDecisionRuntimeEvidenceExporter`. Tests verify approved/denied export shape, strict SHA-256 digest format, JSONL validity, safe digest/length metadata, raw approval/comment exclusion, event key digest (not raw), and non-approval record skipping. No provider routing export, bundle integration, compliance claims, or production-readiness claims are included.
- Provider routing runtime evidence export (PR #186). Added runtime-evidence.v1 export for provider routing decisions via `ProviderRoutingRuntimeEvidenceExporter`, converting selected, fallback, and blocked route decisions into provider.route records. Raw provider/model names are never exported — only SHA-256 digests. Fallback reason codes are allowlisted to six stable values. Tests verify selected/fallback/blocked export shape, strict digest format, JSONL validity, raw-name exclusion, reason-code allowlisting, and canonical digest determinism. No bundle integration, runtime route wiring, compliance claims, or production-readiness claims are included.
- Runtime evidence bundle mapping (PR #187). Added [`docs/evidence/runtime-evidence-bundle-map.md`](docs/evidence/runtime-evidence-bundle-map.md), a human-readable mapping from runtime-evidence.v1 event families to sovereign evidence bundle sections. The guide defines expected JSONL locations for policy decisions, approval decisions, and provider routing records, optional reviewer summary placement, verifier responsibilities, sanitisation boundaries, and non-claims. No bundle generation, runtime wiring, verifier implementation, compliance claims, or production-readiness claims are included.
- Tool permission model (PR #188). The new [`docs/security/tool-permission-model.md`](docs/security/tool-permission-model.md) document defines TramAI's governed tool permission model for Phase 6. Covers tool trust classes (INTERNAL, APPLICATION, EXTERNAL_API, DATA_ACCESS, STATE_CHANGING, HIGH_IMPACT, MCP_REMOTE), risk classes (READ_ONLY, WRITE, DESTRUCTIVE, FINANCIAL, LEGAL_MEDICAL, PRIVILEGED), permission decisions (ALLOW, DENY, REQUIRE_APPROVAL, REDACT_RESULT, ALLOW_INTERNAL_ONLY), default approval-required tool categories, policy enforcement points, audit/evidence expectations, and the relationship to future MCP governance. This does not add runtime enforcement, new Kotlin APIs, MCP connector support, tool audit events, approval wiring changes, compliance claims, production-readiness claims, or certification claims.
- MCP governance boundary (PR #189). The new [`docs/security/mcp-governance-boundary.md`](docs/security/mcp-governance-boundary.md) document defines the governance boundary for future MCP support in TramAI. Covers the MCP trust boundary (what TramAI can govern vs what it cannot prove), MCP tool classification into the `MCP_REMOTE` trust class with risk class mapping, token and audience rules (no passthrough, audience validation, least privilege, no raw token logging, explicit allowlist, approval for high-impact tools), permission decision mapping (current vs future), server identity and tool claims, and audit/evidence expectations. States explicitly that TramAI does not implement an MCP connector today. No runtime behavior, MCP connector code, token exchange, new Kotlin APIs, compliance claims, production-readiness claims, or certification claims are included.
- Tool exposure audit event tests (PR #190). Added [`ToolExposurePolicyAuditWiringTest`](tramai-engine/src/test/kotlin/dev/tramai/engine/ToolExposurePolicyAuditWiringTest.kt) in `tramai-engine` and [`AuditEngineToolDecisionAuditEmitterTest`](tramai-security/src/test/kotlin/dev/tramai/security/audit/AuditEngineToolDecisionAuditEmitterTest.kt) in `tramai-security`. These tests prove that `BEFORE_TOOL_EXPOSURE` emits one audit call per declared tool with `toolName` and `toolSecurity` in the policy context, that denied tool exposure emits audit before `PolicyViolationException`, and that audit failure at tool exposure blocks provider invocation. Emitter shape tests prove that `toolName` and `riskLevel` appear in safe audit metadata, that `REQUIRE_APPROVAL` tool decisions are auditable, that raw tool arguments, secrets, API keys, prompts, and tokens are excluded from durable audit records, and that tool audit event chains pass `AuditChainVerifier`. These tests cover tool exposure permission decisions, not tool invocation — at `BEFORE_TOOL_EXPOSURE` the provider has not been called and the tool has not executed. No new tool runtime-evidence exporter, MCP connector support, `tool-permissions.jsonl`, `REDACT_RESULT`, `ALLOW_INTERNAL_ONLY`, compliance claims, production-readiness claims, or certification claims are included.
- Tool execution denial tests (PR #191). Added [`ToolExecutionPolicyDenialTest`](tramai-engine/src/test/kotlin/dev/tramai/engine/ToolExecutionPolicyDenialTest.kt) in `tramai-engine` and [`ToolExecutionDenialEvidenceIntegrationTest`](tramai-sovereign/src/test/kotlin/dev/tramai/sovereign/ToolExecutionDenialEvidenceIntegrationTest.kt) in `tramai-sovereign`. Engine-level tests (8) prove fail-closed behavior when policy denies a model-requested tool at `BEFORE_TOOL_EXECUTION`: tool never executes, denial is audited before exception propagation, policy context carries canonical tool metadata, no result reinjection or provider continuation occurs after denial, audit failure blocks execution, policy is reevaluated before retries, and later denied tools stop remaining processing. Sovereign integration tests (4) prove the full end-to-end chain: durable audit records contain only safe tool metadata, denied executions export as generic `policy.decision` runtime evidence (not dedicated `tool.permission`), raw tool arguments and secrets are absent from audit and evidence, and the denial event chain passes `AuditChainVerifier`. Corrected the tool permission model documentation: `BEFORE_TOOL_EXECUTION` and `BEFORE_TOOL_RESULT_REINJECTION` are already active enforcement points, not future-only. No dedicated `tool.permission` evidence type, MCP support, new policy decisions, or public API changes are included.
- Product positioning (PR #192). The new [`docs/product/positioning.md`](docs/product/positioning.md) document establishes the canonical TramAI product positioning: tagline, one-sentence description, thirty-second explanation, problem thesis, product category (governed AI workflow runtime), target audiences, representative use cases, six product pillars grounded in implemented capabilities, current maturity matrix, claim boundaries, and a messaging guide per audience. The historical [`docs/security/PRODUCT-THESIS.md`](docs/security/PRODUCT-THESIS.md) is replaced with a compatibility pointer to the canonical document. The [MCP governance boundary](docs/security/mcp-governance-boundary.md) document is corrected to distinguish the implemented MCP workflow server (`tramai-mcp`, stdio + SSE) from the not-yet-implemented governed MCP client/connector. The [README](README.md) links to the canonical positioning; the historical [ROADMAP.md](ROADMAP.md) gains a banner linking to the current roadmap and positioning. No runtime behavior, public API, example, or provider integration changes are included.
- README governed-workflow rewrite (PR #193). Reorganized the root README around the canonical product positioning, a zero-credential governed workflow first-run path (`./gradlew :examples:governed-workflow:run`), the governed execution lifecycle, product pillars with configuration-aware claim language, maturity boundaries, and task-oriented navigation to basic, approval, and sovereign examples. Replaced the plain model call as the primary example and compressed module/status inventory into linked references. No runtime behavior or public APIs changed.
- Governed JVM AI workflow article (PR #194). Added a publication-ready introduction to governed AI workflows for JVM teams, using the deterministic claim-triage workflow to explain typed contracts, policy before side effects, human approval as a lifecycle, controlled routing, evidence and operational recovery, composable adoption, maturity boundaries, and non-claims. Added a companion 30/45-minute conference-talk outline with speaker claim boundaries. No runtime behavior or public APIs changed.
- Example selection guide (PR #195). Added an [`examples/README.md`](examples/README.md) guide that distinguishes learning demos, provider-backed integrations, durable approval proofs, sovereign reference workflows, offline verification harnesses, and the physical sovereign lab. The guide documents prerequisites, commands, infrastructure, governance depth, non-claims, and recommended learning paths. No runtime behavior, public APIs, or example implementations changed.
- JVM AI framework comparison (PR #196). Added a dated, official-source comparison of TramAI, Spring AI 2.0.0, and LangChain4j 1.17.2. The guide documents shared capabilities, framework strengths, governance distinctions, maturity boundaries, selection criteria, and conceptual coexistence patterns without claiming feature absence, compliance, universal superiority, or drop-in compatibility. No runtime behavior or public APIs changed.
- Post-Sovereignty TramAI roadmap document (PR #164). Declares Sovereign Lab Evidence Handoff v1 complete and defines the next phase: workflow ergonomics, API stability, structured output contracts, approval ergonomics, runtime evidence export, tool/MCP governance, and product narrative. Adds a Gradle guard requiring the roadmap to exist and contain key terms. This does not add runtime behavior, API changes, model calls, benchmark execution, compliance claims, production-readiness claims, signature/attestation automation, or Release Console work.
- Synced sovereign lab archive signature verification handoff documentation (PR #162).

### Changed

- README and architecture documentation realigned around governed AI workflows (PR #193).
- Development baseline 0.4.x → 0.5.0 (PR #197). Changed the repository development version to 0.5.0-SNAPSHOT, recorded 0.4.0 as the latest stable release, aligned the post-sovereignty roadmap with the 0.5.0 train, and added verification against version-boundary drift. No runtime behavior or public APIs changed.
- Evidence bundle structure to accommodate runtime-evidence/ section for policy decisions, approval decisions, and provider routing records (PR #199).
- Java structured-output behavior: JavaBean DTOs now contribute real properties to generated schemas, matching Kotlin behavior (PR #198).

### Verified

- Workflow API stability boundary verification (PR #166).
- Approval decision evidence tests proving approved and denied decisions emit durable audit evidence with proper sanitization (PR #180, PR #181).
- Tool exposure audit event chain and evidence family partitioning (PR #190, PR #200).
- Denied tools never execute — fail-closed behavior proven at engine and sovereign integration levels (PR #191).
- Approval-suspended tools never execute in the resume path (PR #179).
- Sovereign evidence bundle tamper rejection lifecycle: finalize → verify → tamper → reject → restore → re-finalize (PR #200).
- Standalone consumer resolution from the sovereign runtime verification repository (PR #197).
- Local publication closure through signed bundle dry-run and consumer smoke test (PR #197).
- Approved-resume worker lifecycle with real SPI queue name `ApprovedContinuationResumeQueue` (PR #112–PR #117).
- Reviewer UI gated behind `rest-control-plane-enabled` flat property (PR #110).

### Known limitations and non-goals

- No stable 1.0 public API.
- No certification, compliance claims, or production-readiness claims.
- No key rotation.
- No production-grade reviewer UI or enterprise IAM.
- No governed MCP connector (documented boundary only).
- No evidence-truth validation — structural tamper-evidence only.
- No Maven Central release of sovereign runtime modules confirmed.
- Remote publication pending tag and Central Portal acceptance.

## 0.4.0 — Sovereign Evidence Handoff

### Added

- Sovereign lab evidence capture workflow.
- Machine-readable evidence bundle manifest.
- Finalization and standalone bundle verification.
- Archive export with checksum sidecar.
- Safe archive verifier with strict sidecar parsing and tar-entry validation.
- Archive verifier negative fixture coverage.
- Optional detached signature verification for archive checksum sidecars.
- Reviewer guide, release-readiness checklist, evidence-chain overview, archive signing boundary documentation, and handoff documentation.
- Deterministic archive export regression coverage.
