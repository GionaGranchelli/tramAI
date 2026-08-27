# Module: `tramai-spring-secrets-file`

> **One-liner:** File-based secret resolution — plugs a file-backed resolver into the core SecretValueResolver chain.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

File-based secret resolution: `FileSecretProperties` + `FileSecretValueResolverAutoConfiguration` plugged into the core `SecretValueResolver` chain.

### Public entry points

- `FileSecretProperties` — configuration properties
- `FileSecretValueResolverAutoConfiguration` — auto-configuration

Verify against `tramai-spring-secrets-file/api/tramai-spring-secrets-file.api`.

### Internal extension points

- Core secret-resolution chain slot

### Significant dependencies

- `api(tramai-spring-core)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle

### Thread-safety and concurrency

- Resolver must be safe for concurrent resolution calls

### Failure semantics

- Secret-resolution failures surface as typed resolver errors; secrets never logged

### Contract tests / TCKs

- `FileSecretValueResolverTest`

### Do not

- Do not add cloud/vault resolvers here — use the dedicated secrets modules

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
