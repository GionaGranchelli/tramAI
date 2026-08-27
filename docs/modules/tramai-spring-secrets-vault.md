# Module: `tramai-spring-secrets-vault`

> **One-liner:** HashiCorp Vault secret resolution — plugs VaultSecretValueResolver into the core SecretValueResolver chain.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

HashiCorp Vault secret resolution: `VaultSecretValueResolver` + `VaultSecretProperties` + auto-configuration plugged into the core `SecretValueResolver` chain.

### Public entry points

- `VaultSecretValueResolver` — `SecretValueResolver` implementation
- `VaultSecretProperties` — configuration properties
- `VaultSecretValueResolverAutoConfiguration` — auto-configuration

Verify against `tramai-spring-secrets-vault/api/tramai-spring-secrets-vault.api`.

### Internal extension points

- Core secret-resolution chain slot

### Significant dependencies

- `api(tramai-spring-core)`; Spring Vault (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle

### Thread-safety and concurrency

- Resolver must be safe for concurrent resolution calls

### Failure semantics

- Secret-resolution failures surface as typed resolver errors; secrets never logged

### Contract tests / TCKs

- `VaultSecretValueResolverTest`

### Do not

- Do not add file/AWS resolvers here — use the dedicated secrets modules

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
