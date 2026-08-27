# Module: `tramai-spring-secrets-aws`

> **One-liner:** AWS Secrets Manager secret resolution — plugs AwsSecretsManagerSecretValueResolver into the core SecretValueResolver chain.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

AWS Secrets Manager secret resolution: `AwsSecretsManagerSecretValueResolver` + properties + auto-configuration, plugged into the core `SecretValueResolver` chain.

### Public entry points

- `AwsSecretsManagerSecretValueResolver` — `SecretValueResolver` implementation
- `AwsSecretsManagerProperties` — configuration properties
- `AwsSecretsManagerSecretValueResolverAutoConfiguration` — auto-configuration

Verify against `tramai-spring-secrets-aws/api/tramai-spring-secrets-aws.api`.

### Internal extension points

- Core secret-resolution chain slot

### Significant dependencies

- `api(tramai-spring-core)`; AWS SDK secretsmanager (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle

### Thread-safety and concurrency

- Resolver must be safe for concurrent resolution calls

### Failure semantics

- Secret-resolution failures surface as typed resolver errors; secrets never logged

### Contract tests / TCKs

- `AwsSecretsManagerSecretValueResolverTest` (mock AWS SDK)

### Do not

- Do not add file/vault resolvers here — use the dedicated secrets modules

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
