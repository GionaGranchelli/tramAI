# Module: `spring-ai-authority-boundary`

> **One-liner:** Example application proving that a declared Spring AI tool boundary is governed by TramAI's authority path — the tool call is authorized, the evidence is recorded, and the recorded evidence never claims enforcement TramAI could not observe.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Executable authority-boundary proof for checkpoint 0.7-XR1 (task 0.7-XR1-0001). It demonstrates that evidence
originating at an external runtime — a Spring AI tool callback — can be mapped through TramAI's governance path
without claiming that TramAI authored the application workflow or controlled execution outside its registered
boundary.

### Public entry points

- `SpringAiBoundaryMain` — runs the example
- `GovernedToolCallback`, `SideEffectfulToolCallback` — the governed and ungoverned tool callbacks
- `BoundaryEvidenceRecorder`, `BoundaryOutcome` — evidence capture for the demonstration
- `StubToolRequestingChatModel` — deterministic model stand-in, no network

Repository-facing only (example module — not published).

### Internal extension points

- None — example application

### Significant dependencies

- Spring AI core, `tramai-security` (governance evidence and boundary provenance), coroutines — see
  [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Plain JVM entry point; no Spring Boot application lifecycle

### Thread-safety and concurrency

- Single-threaded demonstration; no shared mutable state outside the recorder used by the example

### Failure semantics

- A tool call outside the declared boundary is refused rather than executed silently
- Recorded evidence carries the origin limitation: a registered boundary's strength never proves the event came
  through it

### Contract tests / TCKs

- `GovernanceBoundaryTest`, `EvidenceIntegrityTest`, `GovernedIdentityCarriageTest`, `FrameworkDispatchTest`,
  `ToolPermissionEvidenceAttributionTest`, `AdversarialDiscriminatorTest` — each includes a discriminator that
  fails when the boundary invariant is broken

### Do not

- Do not treat example code as library API
- Do not read this module as an integration or runtime-governance implementation — it is an evidence-mapping
  demonstration (see `TASK-0.7.4f-SAFE-EXPOSURE-OTEL-MAPPING.md`, XR1)

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — applications-examples layer
- [RuntimeBoundaryProvenance](../../tramai-security/src/main/kotlin/dev/tramai/security/evidence/RuntimeBoundaryProvenance.kt)
  — declares `SPRING_AI_TOOL_CALLBACK` as the XR1 boundary
