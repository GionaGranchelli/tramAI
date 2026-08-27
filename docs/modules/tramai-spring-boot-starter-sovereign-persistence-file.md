# Module: `tramai-spring-boot-starter-sovereign-persistence-file`

> **One-liner:** File persistence for sovereign ops — file-backed audit-outbox store, store key loading, and file persistence auto-configuration.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

File persistence for sovereign ops: `FileSovereignOpsAuditOutboxStore`, `SovereignStoreKeyLoader`, sovereign file persistence properties + auto-configuration.

### Public entry points

- `FileSovereignOpsAuditOutboxStore` — audit-outbox store implementation
- `SovereignFilePersistenceProperties` — configuration properties
- `SovereignStoreKeyLoader` — store key loading
- `SovereignFilePersistenceAutoConfiguration` — auto-configuration

Verify against `tramai-spring-boot-starter-sovereign-persistence-file/api/tramai-spring-boot-starter-sovereign-persistence-file.api`.

### Internal extension points

- Ops store implementation slot (file-backed)

### Significant dependencies

- `api(tramai-spring-sovereign)`, `api(tramai-persistence-file)`, `api(tramai-spring-boot-starter-sovereign-ops)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; file resources managed by the store

### Thread-safety and concurrency

- Store must be safe for concurrent access; file locking is store-scoped

### Failure semantics

- Persistence failures surface as typed store errors; no silent partial writes

### Contract tests / TCKs

- `SovereignFilePersistenceTest`, sovereign ops E2E (file profile)

### Do not

- Do not add JDBC logic here — use the JDBC persistence starter

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
