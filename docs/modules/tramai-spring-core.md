# Module: `tramai-spring-core`

> **One-liner:** Profile-neutral Spring integration core — auto-configures the TramAI runtime, scans @AiService interfaces and @AiTool beans, and binds tramai.* properties for all Spring adapters.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Profile-neutral Spring integration core: `Tramai` runtime wiring, `@AiService` proxy registration, `@AiTool` bean scanning, secret resolution chain, security classification — the shared base for all Spring adapters.

### Public entry points

- `TramaiAutoConfiguration`, `AiServiceProxyAutoConfiguration`, `SecurityClassificationAutoConfiguration` — auto-configurations
- `SpringSecretChain`, `SecretValueResolver`, `SpringBuiltInSecretValueResolver` — secret resolution
- `AiToolScanner` — tool discovery
- `EnableTramai` — annotation opt-in for non-Boot Spring contexts

Verify against `tramai-spring-core/api/tramai-spring-core.api`.

### Internal extension points

- `SecretValueResolver` implementation slot (secrets modules plug in here)

### Significant dependencies

- `api(tramai-standalone)`; Spring Boot autoconfigure/context, Jackson (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; beans managed by the container

### Thread-safety and concurrency

- Spring singletons; proxies must be safe for concurrent invocation

### Failure semantics

- Misconfigured services/providers surface as context startup failures or typed proxy errors

### Contract tests / TCKs

- `TramaiAutoConfigurationTest`, `AiServiceProxyAutoConfigurationTest`, `AiToolScannerTest`

### Do not

- Do not add provider adapters or sovereign composition here — those live in dedicated modules

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
- [modules.md](../architecture/modules.md) — framework-integrations layer policy
