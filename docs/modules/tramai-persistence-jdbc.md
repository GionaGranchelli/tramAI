# Module: `tramai-persistence-jdbc`

> **One-liner:** JDBC-backed persistence stores for sovereign approvals, audit and workflow state.
> **Classification:** persistence · published (preview API, release-included) — see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Implements the sovereign store SPIs on PostgreSQL/JDBC: approval stores, continuation stores, audit store, suspended-invocation store, replay envelope codecs. Published as of Epic 9.1c (#303); consumed directly or via `tramai-spring-boot-starter-sovereign-persistence-jdbc`.

### Public entry points

- `dev.tramai.persistence.jdbc.JdbcApprovalStore`, `JdbcApprovalContinuationStore`, `JdbcAuditStore`, `JdbcSuspendedInvocationStore`
- `JdbcReplayEnvelopeCodec`, `JdbcAuditPayloadCodec`, `JdbcContinuationArgumentsCodec`
- `SovereignJdbcPersistence` — composition root

### Internal extension points

- Store SPI implementations (approval / continuation / audit / suspended invocation)

### Significant dependencies

- `api(tramai-core)`, `api(tramai-engine)`, `api(tramai-security)`; Jackson codecs (implementation scope)

### Lifecycle ownership

- Exactly-once continuation claim semantics; lazy expiry on pending continuations

### Thread-safety and concurrency

- JDBC-backed stores rely on transaction isolation; connection management is store-scoped. Do not invent guarantees beyond what the store's own documentation states.

### Failure semantics

- Transaction failures propagate as typed store exceptions; no silent partial writes

### Contract tests / TCKs

- `JdbcApprovalStoreTckTest`, `JdbcApprovalContinuationStoreTckTest`, `JdbcAuditStoreTckTest`, `JdbcSuspendedInvocationStoreTckTest` — enrolled in the shared TCKs

### Do not

- Do not copy file-store behavior as the spec; the TCKs are authoritative
- Do not add Spring dependencies here

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — persistence layer
- `docs/architecture/sovereign-jdbc-persistence-design.md`
