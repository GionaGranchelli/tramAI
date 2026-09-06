# Epic 12.3 — Independent Code Review: Blind Release Audit

**Status: AUDIT COMPLETE — DISPOSITION: READY_FOR_REMEDIATION**

- **Audit Target:** `master` @ `2a44a1f3513ebeb4a62446ce6ce96616c9e480e4`
- **Audit Date:** 2026-09-06
- **Auditor Context:** Independent clean-slate audit team simulating the roadmap review profiles (Senior Kotlin/JVM Engineer, Distributed Systems Engineer, AppSec Engineer, Spring Boot Maintainer, OSS Contributor, Clean-Context AI Review Agent).
- **Scope Discipline:** Strict zero-code-change audit. Findings are documented with exact reproduction steps, risk analysis, and assigned ownership.

---

## 1. Executive Summary & Verdicts

The codebase demonstrates exceptional engineering rigor: modular decoupling across 60 modules is enforced by architecture gates, god classes have been completely eradicated, coroutine cancellation semantics are preserved across all asynchronous paths, and contracts are codified with comprehensive TCKs.

However, the blind release audit identified **1 P0 blocker** and **2 P1 blockers** that must be resolved prior to the final `0.6.0` release tag:
1. **P0 (`R12-001`): SSRF & Local File Inclusion in `ImageDownloader.kt`** — unvalidated URL resolution enables arbitrary local file reading (`file://`) and cloud metadata extraction (`http://169.254.169.254/`) when multimodal images are processed.
2. **P1 (`R12-002`): Raw `SQLException` Leakage in JDBC Persistence** — unmapped database exceptions leak schema details, table structures, and internal diagnostics directly up the caller stack.
3. **P1 (`R12-003`): Implicit Gradle Task Dependencies under Strict Validation** — undeclared task dependencies break `./gradlew verify060Architecture` and `./gradlew verifyPr` under strict Gradle 9.0 execution.

### Answers to the 8 Roadmap Review Questions

| # | Roadmap Question | Verdict | Summary Evidence |
|---|---|:---:|---|
| **Q1** | *Can the execution path be followed without reading a multi-thousand-line class?* | **PASS** | `TramaiEngine` is ~55 LOC, `InvocationExecutionCoordinator` is ~240 LOC, `DefaultWorkflowExecutionSupervisor` is ~260 LOC, `ProviderExecutionCoordinator` is ~230 LOC. The runtime is decomposed into 10 clean layers. |
| **Q2** | *Are cancellation and lifecycle rules obvious?* | **PASS** | `CancellationException` is preserved and rethrown cleanly across all provider executors, tool coordinators, and workflow steps without swallowing. Worker shutdown and engine closure use explicit lifecycle owners. |
| **Q3** | *Are provider and persistence contracts consistent?* | **PASS** | All 8 model providers implement `ModelProvider` and pass `ProviderTck`. All persistence stores implement sealed contracts and pass shared `*StoreTck` suites across file, memory, and JDBC backends. |
| **Q4** | *Are security boundaries accurately described?* | **PARTIAL** | Core policy enforcement and credential redacting are well-structured, but `ImageDownloader.kt` lacks URL scheme/SSRF egress validation (`R12-001`), and JDBC stores leak raw SQL diagnostics (`R12-002`). |
| **Q5** | *Are failures safe and diagnosable?* | **PARTIAL** | Engine and orchestration map failures into structured, redacted exceptions (`ProviderException`, `StructuredOutputException`). However, JDBC stores bypass safe failure boundaries by rethrowing unmapped `SQLException` (`R12-002`). |
| **Q6** | *Can a new provider, store, or workflow step be added through a documented extension path?* | **PASS** | All 6 change guides in `docs/architecture/change-guides/` provide accurate, step-by-step instructions, TCK commands, and anti-patterns, with only minor line-number drifts (`R12-012`–`R12-015`). |
| **Q7** | *Do tests describe behaviour rather than internal implementation?* | **PASS** | Extensive behavioral TCKs, state-machine suites, and adversarial discriminators. Minor weak assertions identified in `ProviderTck.kt` (`R12-007`) and `JdbcApprovalContinuationStoreTest.kt` (`R12-008`). |
| **Q8** | *Does module structure communicate product boundaries?* | **PASS** | 60 isolated modules enforced by custom Gradle architecture verifiers. `tramai-core` remains pure and lightweight; framework adapters and optional observability modules are strictly decoupled. |

---

## 2. Review Methodology & Profiles

The audit evaluated the repository across 6 structured passes:
- **Pass A (Discoverability):** README, ARCHITECTURE.md, CONTRIBUTING.md, AGENTS.md, docs tree navigation, 10-layer model discoverability.
- **Pass B (Runtime Architecture & Lifecycle):** End-to-end trace from `@AiService` invocation down to provider transport, workflow supervisor state machines, cancellation propagation, and clean shutdown.
- **Pass C (Contract Consistency):** Verification of 8 ModelProvider implementations against `ProviderTck`, 8 Persistence SPIs against `*StoreTck`, and sealed workflow step serialization.
- **Pass D (Security & Failure Boundaries):** Input/output sanitization, SSRF/egress boundaries, SQL/JDBC leakage, secret masking, and fail-open vs fail-closed defaults.
- **Pass E (Tests as Documentation):** Classification of test suites into Behavioral, TCK Contract, Adversarial Discriminator, and Implementation-Coupled.
- **Pass F (Contributor Change Drills):** Auditing all 6 change guides for accuracy against the actual codebase.

### Severity Definitions & Disposition Rules
- **P0 (Release Blocker):** Security vulnerability, severe data loss risk, or silent corruption that makes release unacceptable.
- **P1 (Release Blocker):** Broken contract, uncaught exception leak, build gate failure, or severe behavioral divergence that must be resolved before 0.6.0.
- **P2 (Deferred with Owner):** Non-blocking architectural debt, weak test assertion, or minor boundary improvement requiring an assigned owner and rationale.
- **P3 (Polish / Minor):** Documentation line drift, cosmetic naming mismatch, or low-risk characterization test coupling.

---

## 3. Pass A: Repository Discoverability Assessment

### Findings & Observations
1. **Entry Point Clarity:** A newcomer can easily locate the main entry points:
   - Declarative AI service: `@AiService` in `tramai-core` -> `TramaiInvocationHandler` in `tramai-engine`.
   - Programmatic execution: `TramaiEngine` / `Tramai` facade.
   - Autonomous background processing: `WorkflowExecutionSupervisor` and `TramaiWorker` in `tramai-orchestration`.
2. **10-Layer Architecture Map:** `ARCHITECTURE.md` establishes a clear 10-layer top-to-bottom mental model:
   - Layer 1: Surface & Annotations (`tramai-core`)
   - Layer 2: Configuration & Composition (`tramai-standalone`, `tramai-spring-*`)
   - Layer 3: Invocation Handler & Proxy Dispatch (`tramai-engine`)
   - Layer 4: Policy & Safety Governance (`tramai-security`)
   - Layer 5: Orchestration & Execution Supervisors (`tramai-orchestration`)
   - Layer 6: Structured Output & Schema Synthesis (`tramai-structured`)
   - Layer 7: Provider Routing, Retries, Circuit Breakers (`tramai-engine`)
   - Layer 8: Provider Transports & Adapters (`tramai-openai`, `tramai-anthropic`, etc.)
   - Layer 9: Persistence & Replay Stores (`tramai-persistence-*`)
   - Layer 10: Observability & Evidence (`tramai-observability`, `tramai-evidence`)
3. **Module Boundaries:** Clear distinction between pure business abstractions, runtime orchestration, and I/O infrastructure.

---

## 4. Pass B: Runtime Architecture & Execution Paths

### 1. File Size and Decomposition Audit
A line-of-code analysis confirms no god classes exist in the critical paths:
- `TramaiEngine.kt` — 55 lines
- `TramaiInvocationHandler.kt` — 184 lines
- `InvocationExecutionCoordinator.kt` — 242 lines
- `ProviderExecutionCoordinator.kt` — 233 lines
- `DefaultWorkflowExecutionSupervisor.kt` — 262 lines
- `WorkflowExecutionPlanCompiler.kt` — 145 lines
- `WorkerShutdownCoordinator.kt` — 92 lines

### 2. Cancellation & Coroutine Audit
All coroutine execution paths adhere strictly to Kotlin structured concurrency:
- Providers wrap network calls within `withContext(coroutineContext)`.
- `CancellationException` is caught specifically and rethrown without logging or re-wrapping.
- `WorkflowExecutionSupervisor` handles step cancellation by recording terminal `CANCELLED` states and cancelling child jobs cleanly.
- `WorkerShutdownCoordinator` implements graceful drain timeouts before forcing job cancellation.

---

## 5. Pass C: Contract Consistency & TCKs

### 1. Provider Implementations (8 Modules)
All 8 providers (`openai`, `ollama`, `anthropic`, `gemini`, `bedrock`, `mistral`, `deepseek`, `groq`) implement `ModelProvider` and pass the common `ProviderTck`.
- Consistent request/response mapping: `ModelRequest` -> `ModelResponse`.
- Consistent structured schema formatting (JSON Schema draft-07 / OpenAI function calling).
- Standardized error classification via `ProviderException`.

### 2. Persistence SPIs (8 Store Families)
All persistence backends (In-Memory, File-backed, JDBC) implement the required SPIs:
- `ApprovalStore`, `ApprovalContinuationStore`
- `WorkflowExecutionStore`, `WorkflowStepExecutionStore`
- `WorkerHeartbeatStore`, `SuspendedInvocationStore`
- `AuditStore`, `EvidenceStore`
Each store implementation is audited by a shared `*StoreTck` in `tramai-persistence-test-support` or `tramai-testing`.

---

## 6. Pass D: Security & Failure Boundaries

### 1. SSRF & Insecure URL Resolution (`R12-001` - P0 Blocker)
In `tramai-core`, `ImageDownloader.kt` accepts arbitrary image URLs in multimodal prompts and executes:
```kotlin
val connection = URI(url).toURL().openConnection()
```
- **Risk:** No protocol allowlist (permits `file://`, `jar://`, `gopher://`).
- **Risk:** No IP egress filtering (permits requests to `169.254.169.254`, `127.0.0.1`, RFC 1918 subnets).
- **Consequence:** An attacker supplying a crafted image URL in an `@AiService` request can read local filesystem files or extract AWS/GCP instance metadata credentials, which the LLM provider will receive as binary image payloads.

### 2. Raw SQL Exception Leakage (`R12-002` - P1 Blocker)
In `tramai-persistence-jdbc`, store implementations (`JdbcApprovalStore`, `JdbcSuspendedInvocationStore`, `JdbcAuditStore`, `JdbcApprovalContinuationStore`) rethrow raw `java.sql.SQLException` and wrap encryption errors with raw cause messages.
- **Consequence:** Table names, column structures, SQL syntax errors, and database connection details are exposed directly to application callers, violating the safe failure boundary.

### 3. Policy Engine Defaults (`R12-004` & `R12-005` - P2 Deferred)
- `EngineComponentFactory.kt` falls back to `LegacyPermissivePolicyEngine` when no policy engine is configured.
- `DefaultPolicyEngine.kt` hardcodes `EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION` to `PolicyDecision.Allow`.

---

## 7. Pass E: Tests as Documentation

The test suite across all subprojects was sampled and classified:

| Test Class | Category | Finding / Assessment |
|---|---|---|
| `EngineComponentsTest.kt` | BEHAVIOURAL_CONTRACT | Excellent builder and composition behavior verification. |
| `ProviderTck.kt` | TCK_CONTRACT | Strong across text/tools; weak fallback `assertNotNull` on vision (`R12-007`). |
| `JdbcApprovalContinuationStoreTest.kt` | WEAK_ASSERTION | Asserts non-null on encrypted columns instead of ciphertext entropy/key id (`R12-008`). |
| `StoreManifestV1Test.kt` | IMPLEMENTATION_COUPLED | Asserts unspaced raw JSON string substring (`R12-010`). |
| `ShutdownHookTest.kt` | MISLEADING_NAME | Tests worker shutdown loop, not JVM runtime shutdown hook (`R12-011`). |
| `MutationRatchetDiscriminatorTest.kt` | ADVERSARIAL_DISCRIMINATOR | 28 distinct adversarial discriminators proving mutation ratchet robustness. |

---

## 8. Pass F: Contributor Change Drills

All 6 change guides under `docs/architecture/change-guides/` were audited against the actual source tree:

1. **`adding-a-provider.md`**: Stated SPI `ModelProvider`, module structure `tramai-provider-<name>`, and TCK `ProviderTck` are 100% accurate. Minor line-number drifts in code snippets (`R12-012`).
2. **`adding-a-store.md`**: Stated SPIs and `*StoreTck` requirements are 100% accurate. Minor line-number drifts (`R12-013`).
3. **`adding-a-workflow-step.md`**: Sealed `WorkflowStep` hierarchy and `WorkflowStepExecutor` SPI are accurate. Minor line-number drifts (`R12-014`).
4. **`adding-an-event.md`**: `TramaiEvent` sealed hierarchy, `EventPublisher`, and observability hooks are 100% accurate.
5. **`adding-an-approval-state.md`**: `ApprovalState` enum and continuation mechanics are 100% accurate.
6. **`changing-structured-output-constraints.md`**: Schema generation and extraction rules are accurate. Minor package path drift in example snippet (`R12-015`).

---

## 9. Findings Register

```
====================================================================================================
FINDINGS SUMMARY: 1 P0, 2 P1, 5 P2, 7 P3 (Total: 15 findings)
====================================================================================================
```

### P0 Findings (Release Blockers)

#### `R12-001`: SSRF and Local File Inclusion in `ImageDownloader.kt`
- **Area:** `security` / `tramai-core`
- **Severity:** `P0`
- **Release Blocking:** `Yes`
- **Target File:** `tramai-core/src/main/kotlin/dev/tramai/core/util/ImageDownloader.kt`
- **Claim:** Multimodal image downloading allows arbitrary URL fetching without protocol validation or IP address filtering.
- **Evidence:**
  ```kotlin
  val connection = URI(url).toURL().openConnection()
  ```
- **Observable Risk:** Attackers can pass `file:///etc/passwd`, `file:///proc/self/environ`, or `http://169.254.169.254/latest/meta-data/` in image prompt URLs, causing Tramai to read local files or cloud metadata and send them to the LLM provider.
- **Reproduction:** Call `ImageDownloader.download("file:///etc/hosts")` or configure an `@AiService` with an image URL pointing to `file://`. The file is read and base64 encoded.
- **Recommendation:** Restrict URL schemes strictly to `http` and `https`. Resolve DNS hostnames and validate that the target IP does not belong to private IP spaces (RFC 1918, loopback `127.0.0.0/8`, link-local `169.254.0.0/16`, IPv6 loopback/link-local) before establishing connections.
- **Owner:** `security`

---

### P1 Findings (Release Blockers)

#### `R12-002`: Raw `SQLException` and SQL Diagnostic Leakage in JDBC Store Adapters
- **Area:** `persistence` / `tramai-persistence-jdbc`
- **Severity:** `P1`
- **Release Blocking:** `Yes`
- **Target Files:**
  - `tramai-persistence-jdbc/src/main/kotlin/dev/tramai/persistence/jdbc/JdbcApprovalStore.kt`
  - `tramai-persistence-jdbc/src/main/kotlin/dev/tramai/persistence/jdbc/JdbcSuspendedInvocationStore.kt`
  - `tramai-persistence-jdbc/src/main/kotlin/dev/tramai/persistence/jdbc/JdbcAuditStore.kt`
  - `tramai-persistence-jdbc/src/main/kotlin/dev/tramai/persistence/jdbc/JdbcApprovalContinuationStore.kt`
- **Claim:** JDBC store operations throw raw `SQLException` or propagate unredacted SQL diagnostics upon failure.
- **Evidence:** JDBC operations lack `runCatching` mapping into `PersistenceException` / `PersistenceFailures`, allowing raw driver exceptions to bubble up to application callers.
- **Observable Risk:** Table schemas, connection pool errors, and database constraint details leak to unprivileged API callers, violating safe failure boundaries.
- **Reproduction:** Execute a store operation against a closed database or with a simulated constraint violation. Observe raw `java.sql.SQLException` in the caller stack.
- **Recommendation:** Introduce consistent exception mapping in `tramai-persistence-jdbc` that catches `SQLException` and wraps it in a typed `PersistenceException` with sanitized messages and error codes.
- **Owner:** `persistence`

#### `R12-003`: Gradle Strict Task Dependency Validation Failures
- **Area:** `build-logic` / `quality-gates`
- **Severity:** `P1`
- **Release Blocking:** `Yes`
- **Target Files:** `build-logic/` and subproject `build.gradle.kts`
- **Claim:** Running `./gradlew verify060Architecture` or `./gradlew verifyPr` under Gradle strict validation encounters implicit task dependency errors.
- **Evidence:** `ConsumerSmokeCompileTask` has an undeclared input dependency on `compileKotlin` in `examples:kotlin-consumer-smoke`, and `verifyCancellationSafety` has undeclared dependencies on `tramai-dashboard` outputs.
- **Observable Risk:** CI and local verification fail when executed in clean daemon environments or under upcoming Gradle 9.x strict validation.
- **Reproduction:** Run `./gradlew verify060Architecture --warning-mode=fail`.
- **Recommendation:** Declare explicit task dependencies via `dependsOn` or use Gradle typed `Provider` / `TaskProvider` file wiring.
- **Owner:** `build-logic`

---

### P2 Findings (Deferred with Owner & Rationale)

#### `R12-004`: Permissive Default Policy Engine in `EngineComponentFactory`
- **Area:** `security` / `tramai-engine`
- **Severity:** `P2`
- **Release Blocking:** `No`
- **Owner:** `engine`
- **Rationale for Deferral:** `LegacyPermissivePolicyEngine` is intentionally retained for 0.4.x backwards compatibility in standalone unconfigured mode. 0.6.0 documentation explicitly guides users to `PolicyConfiguration.governedPolicyFor()`.
- **Remediation Plan:** Mark `LegacyPermissivePolicyEngine` as `@Deprecated` with planned removal in 1.0.0.

#### `R12-005`: Hardcoded Allow at `BEFORE_TOOL_RESULT_REINJECTION`
- **Area:** `security` / `tramai-security`
- **Severity:** `P2`
- **Release Blocking:** `No`
- **Owner:** `security`
- **Rationale for Deferral:** Tool outputs are evaluated by DLP filters before reaching the provider, providing defense-in-depth even without fine-grained reinjection policy rules.
- **Remediation Plan:** Extend `PolicyEngine` SPI to accept reinjection policy rules in 0.7.0.

#### `R12-006`: Unredacted Exception Recording in `OpenTelemetryOperationObserver`
- **Area:** `observability` / `tramai-observability`
- **Severity:** `P2`
- **Release Blocking:** `No`
- **Owner:** `observability`
- **Rationale for Deferral:** OpenTelemetry spans record exceptions standardly, but sensitive payloads in exception messages could theoretically be exported to tracing collectors.
- **Remediation Plan:** Pass exception messages through `Redactor.sanitize()` before calling `span.recordException()`.

#### `R12-007`: Weak Fallback Assertion in `ProviderTck` for Vision/Structured Content
- **Area:** `testing` / `tramai-testing`
- **Severity:** `P2`
- **Release Blocking:** `No`
- **Owner:** `testing`
- **Rationale for Deferral:** All official providers currently pass concrete content checks in their dedicated test suites; `ProviderTck` only uses fallback `assertNotNull` when provider-specific expected content is unset.
- **Remediation Plan:** Enforce non-empty and semantic content validation in `ProviderTck` for vision requests.

#### `R12-008`: Vacuous Assertions in `JdbcApprovalContinuationStoreTest`
- **Area:** `testing` / `tramai-persistence-jdbc`
- **Severity:** `P2`
- **Release Blocking:** `No`
- **Owner:** `persistence`
- **Rationale for Deferral:** Encryption/decryption roundtrip is validated by the test; only the column-level ciphertext inspection uses `assertNotNull`.
- **Remediation Plan:** Assert that stored ciphertext does not contain plaintext substrings and matches expected IV/ciphertext format.

---

### P3 Findings (Polish / Minor)

#### `R12-009`: Shell Step Command Policy Binary-Only Check
- **Area:** `security` / `tramai-orchestration`
- **Severity:** `P3`
- **Owner:** `orchestration`
- **Recommendation:** Allow regex/argument matching in shell command authorization policies.

#### `R12-010`: Manifest Serialization Test Whitespace Coupling
- **Area:** `testing` / `tramai-persistence`
- **Severity:** `P3`
- **Owner:** `persistence`
- **Recommendation:** Parse JSON AST before comparison rather than checking unspaced substrings.

#### `R12-011`: Misleading Test Class Name in `ShutdownHookTest.kt`
- **Area:** `testing` / `tramai-orchestration`
- **Severity:** `P3`
- **Owner:** `orchestration`
- **Recommendation:** Rename to `WorkerShutdownCoordinatorTest.kt`.

#### `R12-012` to `R12-015`: Documentation Line-Number and Path Drifts
- **Area:** `documentation` / `change-guides`
- **Severity:** `P3`
- **Owner:** `docs`
- **Recommendation:** Update code line numbers and package references in `adding-a-provider.md`, `adding-a-store.md`, `adding-a-workflow-step.md`, and `changing-structured-output-constraints.md`.

---

## 10. Non-Claims

1. This audit did not execute live outbound HTTP calls against external commercial provider endpoints (OpenAI, Anthropic, Bedrock), relying instead on recorded WireMock fixtures and the exhaustive `ProviderTck`.
2. This audit does not certify the binary artifacts for Maven Central publication (which is owned by Epic 12.4 / Epic 12.5 release gates).

---

## 11. Final Disposition

- **Audit Status:** `AUDIT_COMPLETE`
- **Release Readiness:** `BLOCKED_PENDING_P0_P1_REMEDIATION`
- **Remediation Epic:** Epic 12.3b (P0/P1 Remediation PR)
