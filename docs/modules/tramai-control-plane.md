# Module: `tramai-control-plane`

> **One-liner:** authoritative workload-registration and lifecycle-state boundary for the TramAI control plane (Epic 0.7.1).
> **Classification / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml)

## Architecture

### Responsibility

Owns the first canonical MUTABLE authority of the 0.7 control plane: the registration boundary that binds the immutable identity vocabulary
(`dev.tramai.core.identity`) to a governed configuration fingerprint, safe owner/purpose metadata, a registration lifecycle and a monotonic
state version. It answers *"what workload deployment is authoritatively registered, against which governed configuration revision, with what
lifecycle/metadata and state version?"* — durably and without silent overwrites.

### Public entry points

- `dev.tramai.controlplane.WorkloadRegistrationAuthority` — the rules state machine: register (never an upsert), metadata mutation, lifecycle
  transitions, stale-writer rejection. Rules live here, never in a persistence implementation.
- `dev.tramai.controlplane.WorkloadRegistrationStore` — persistence boundary (`find` / atomic `create` / atomic `compareAndSet`), shared by the
  InMemory reference implementation and the JDBC durable implementation.
- Types: `RegisteredWorkload`, `ConfigurationFingerprint`, `WorkloadLifecycleState` (ACTIVE/SUSPENDED/RETIRED, RETIRED terminal),
  `WorkloadStateVersion` (starts at 1, monotonic, overflow-safe).

### Internal extension points

- `InMemoryWorkloadRegistrationStore` — reference implementation used by tests and the authority suite.
- `WorkloadRegistrationStoreTck` + harness + fixtures (testFixtures source set) — the shared contract executed against every store implementation;
  a new persistence implementation only needs a runner.

### Significant dependencies

- `api(project(":tramai-core"))` only. No `tramai-server`, `tramai-platform`, Spring, dashboard, Jackson or JDBC in this module.
- Persistence adapters (`tramai-persistence-jdbc`) depend TOWARD this authority contract, never the reverse.

### Lifecycle ownership

`WorkloadLifecycleState` (ACTIVE/SUSPENDED/RETIRED) is the lifecycle of the REGISTRATION — deliberately distinct from workflow-run lifecycle.
Suspending/retiring a registration never cancels running workflows. RETIRED is terminal. `WorkloadStateVersion` advances on every successful
authoritative mutation and never changes identity.

### Safe exposure model (0.7.1f)

`WorkloadExposure` is the single generic control-plane payload. It contains only deployment
identity, bounded owner/purpose metadata, registration lifecycle, and state version. The
`configurationFingerprint` is a DECLARATION INPUT, NOT READABLE: a client originates it when it
declares a registration, and it is never reflected back through a generic read, an outcome, or HTTP
— it is the CAS/rebinding witness `WorkloadRegistrationStore.compareAndSet` compares.
`WorkloadRegistrationStore` remains the deliberate exception because its SPI must retain
`RegisteredWorkload` for CAS.

### Thread-safety and concurrency

- Every store mutation is atomic; every authority mutation is a version-guarded compare-and-set — stale writers lose, no last-writer-wins.
- `InMemoryWorkloadRegistrationStore` serializes under a single lock (reference semantics; `ponytail:` per-scope locks if it ever leaves the
  reference role).
- `JdbcWorkloadRegistrationStore` (in `tramai-persistence-jdbc`) runs registration inside an explicit transaction; the database primary keys
  enforce one registration per deployment scope and one fingerprint per configuration revision.

### Failure semantics

- Registration is not an upsert: identical declaration → `AlreadyRegistered`; conflicting declaration → typed `Rejected` with
  `RegistrationConflictReason` (configuration rebinding is rejected from ANY deployment scope).
- Stale mutations return typed `Stale` outcomes; the authoritative record is never partially applied.
- Database errors are wrapped (fail closed) without leaking SQL/vendor diagnostics; validation failures are `IllegalArgumentException`.

### Contract tests / TCKs

- `WorkloadRegistrationStoreTck` (19 cases) runs against BOTH `InMemoryWorkloadRegistrationStore` and `JdbcWorkloadRegistrationStore`
  (runners: `InMemoryWorkloadRegistrationStoreTckTest`, `JdbcWorkloadRegistrationStoreTckTest`).
- `WorkloadRegistrationAuthorityTest` (26 cases) pins registration idempotency/conflict semantics, the lifecycle graph, version semantics and
  concurrent-writer races.
- `JdbcWorkloadRegistrationStoreTest` proves durability across store instances (restart) and cross-instance rebinding rejection.

### Do not

- Do not make registration an upsert or a configuration-update mechanism for startup/bootstrap code.
- Do not cancel running workflows when a registration is suspended/retired.
- Do not embed `WorkloadStateVersion` in any identity type.
- Do not replace `WorkflowRegistry` executable-definition lookup.
- Do not add HTTP mechanics here (If-Match/ETag parsing, status codes, problem DTOs) or a web
  dependency of any kind: since 0.7.1e the framework-neutral command/query contract lives in this
  module and the transport adapter lives in `tramai-server`.
- Do not add a projection engine, a generic CQRS layer or arbitrary metadata maps: 0.7.1e ships the
  read classification (`QueryConsistency`, `ClassifiedRead`) and the read-only guarantee, not a
  projection store.
- Do not expose `RegisteredWorkload` on a generic control-plane port or outcome, add `Map`/`Any`/
  `JsonNode` to `WorkloadExposure`, or read an authority witness off a generic surface.
- Do not re-implement registration, lifecycle or version rules in an adapter: `WorkloadRegistrationAuthority`
  implements `WorkloadControlPlaneCommands`/`WorkloadControlPlaneQueries` directly, so an adapter only
  parses transport input and maps typed outcomes. Reproducing `find -> compare -> mutate` outside the
  authority creates a second authority.

### Related architecture

- Identity vocabulary: `tramai-core` / `dev.tramai.core.identity` (0.7.1b) — immutable ids consumed here.
- Baseline audit and Epic decomposition: `docs/roadmap/0.7.0/EPIC-0.7.1-CONTROL-PLANE-AUTHORITY.md` and
  `docs/roadmap/0.7.0/TASK-0.7.1c-AUTHORITATIVE-REGISTRATION-STATE-BOUNDARY.md`.
- Durable store: `tramai-persistence-jdbc` (migration `V8__control_plane_workload_registration.sql`).
