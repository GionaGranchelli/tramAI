# Module: `timeline-reconstruction`

> **One-liner:** Example (Kotlin and Java) that reads recorded run evidence through the shipped timeline query and reconstructs it, including real PostgreSQL qualification of every reconstruction status.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Reference integration for Epic 0.7.5: query a run's recorded attempts and governance facts with `SemanticTimelineQuery`, reconstruct the run with `EvidenceBackedReconstructor`, and render a narrative from typed fields only. It owns no evidence, no persistence and no orchestration — it is a reader.

### Public entry points

- `TimelineReconstructionExample` — Kotlin example: in-memory evidence, the shipped read path, and the narrative renderer; its top-level `main` is the one `application.mainClass` points at
- `TimelineReconstructionJavaExample` — the same example in Java, proving the API is callable from a Java consumer
- `TimelineReconstructionPersistenceTest` — PostgreSQL qualification of `SUPPORTED`, `PARTIAL` and the empty-history `PARTIAL`, plus the uninstrumented `UNSUPPORTED` path

Repository-facing only (example module — not published).

### Internal extension points

- None — example application

### Significant dependencies

- `implementation(:tramai-core)`, `implementation(:tramai-security)`, `implementation(:tramai-persistence-jdbc)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)
- `testImplementation(testFixtures(:tramai-security))` — the governance projection seeder, so the tests read a projection written by the shipped materializer rather than a hand-inserted row

### Lifecycle ownership

- Main-function lifecycle; the timeline query and the reconstructor are synchronous reads owned by the caller

### Thread-safety and concurrency

- The reconstructor is stateless apart from its two injected ports; concurrent reconstructions share no mutable state

### Failure semantics

- A collaborator failure propagates unchanged; a read-only port is a contract on an implementation, not a proof about it (documented on `SemanticTimelineQuery` and `HistoricalConfigurationSource`)
- A supplied historical configuration reference is reported as `HISTORICAL_CONFIGURATION_UNVERIFIED`: source-declared, never authenticated

### Contract tests / TCKs

- `TimelineReconstructionExampleTest` (Kotlin, 2), `TimelineReconstructionLauncherSmokeTest` (launcher, 1), `TimelineReconstructionJavaExampleTest` (Java, 2), `TimelineReconstructionPersistenceTest` (PostgreSQL, 5)

### Do not

- Do not treat example code as library API
- Do not read current configuration as a stand-in for historical configuration, and do not infer execution from a governance decision

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — applications-examples layer
- [Epic 0.7.5 qualification](../roadmap/0.7.5/evidence/0.7.5h-integration-examples/qualification.md)
