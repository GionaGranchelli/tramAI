# Module: `tramai-spring-boot-starter-local-provider-openai`

> **One-liner:** Local (non-egress) OpenAI-compatible provider starter — auto-configures a loopback/offline ModelProvider bean for development.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Local (non-egress) OpenAI-compatible provider starter: auto-configures an OpenAI-compatible `ModelProvider` bean for offline/loopback development.

### Public entry points

- `OpenAiCompatibleProviderAutoConfiguration` — auto-configuration
- `TramaiProviderProperties` — configuration properties

Verify against `tramai-spring-boot-starter-local-provider-openai/api/tramai-spring-boot-starter-local-provider-openai.api`.

### Internal extension points

- Provider property namespace (`tramai.providers.*`)

### Significant dependencies

- `api(tramai-openai)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle

### Thread-safety and concurrency

- Spring singleton provider; must be safe for concurrent invocation

### Failure semantics

- Provider misconfiguration surfaces at context startup

### Contract tests / TCKs

- `OpenAiCompatibleProviderAutoConfigurationTest` (in sovereign-lab smoke/E2E)

### Do not

- Do not add cloud-egress providers here — this is the local-development surface

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
