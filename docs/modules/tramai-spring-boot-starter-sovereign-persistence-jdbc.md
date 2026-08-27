# Module: `tramai-spring-boot-starter-sovereign-persistence-jdbc`

> **One-liner:** JDBC persistence for sovereign ops — JDBC audit-outbox/approval/lease stores, payload codecs, and auto-configuration.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

JDBC persistence for sovereign ops: JDBC stores for audit outbox, approval mutations, approval requests, worker leases; suspended-invocation payload codec; auto-configuration.

### Public entry points

- `JdbcSovereignOpsAuditOutboxStore`, `JdbcSovereignOpsApprovalMutationStore`, `JdbcSovereignOpsApprovalRequestMutationStore`, `JdbcSovereignOpsWorkerLeaseStore` — JDBC stores
- `DefaultJdbcSuspendedInvocationPayloadCodec`, `JdbcOpsAuditOutboxPayloadCodec` — codecs
- `SovereignJdbcPersistenceAutoConfiguration` — auto-configuration

Verify against `tramai-spring-boot-starter-sovereign-persistence-jdbc/api/tramai-spring-boot-starter-sovereign-persistence-jdbc.api`.

### Internal extension points

- Ops store implementation slot (JDBC-backed)

### Significant dependencies

- `api(tramai-spring-sovereign)`, `api(tramai-spring-boot-starter-sovereign-ops)`, `api(tramai-persistence-jdbc)`, `api(tramai-security)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; connections/pools borrowed from the container datasource

### Thread-safety and concurrency

- Stores must be safe for concurrent access; lease/claim locking is store-scoped

### Failure semantics

- Persistence failures surface as typed store errors; audit stream remains tamper-evident (E2E-verified)

### Contract tests / TCKs

- `JdbcSovereignRuntimeE2ETest` (embedded Postgres), regulated-claim triage E2E

### Do not

- Do not add file persistence here — use the file persistence starter

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
