# Module: `tramai-spring-provider-anthropic`

> **One-liner:** Spring auto-configuration for the Anthropic provider — wires AnthropicProvider as a bean with anthropic property namespace.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Spring auto-configuration for the Anthropic provider: `AnthropicProviderAutoConfiguration` + `AnthropicProperties` wiring `AnthropicProvider` as a bean.

### Public entry points

- `AnthropicProviderAutoConfiguration` — auto-configuration
- `AnthropicProperties` — configuration properties

Verify against `tramai-spring-provider-anthropic/api/tramai-spring-provider-anthropic.api`.

### Internal extension points

- Provider property namespace (`tramai.providers.anthropic.*`)

### Significant dependencies

- `api(tramai-spring-core)`; `implementation(tramai-anthropic)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; provider bean retains injected/default `HttpClient` (no close contract)

### Thread-safety and concurrency

- Provider bean must be safe for concurrent invocation

### Failure semantics

- Provider failures normalized per `AnthropicProvider` contracts; misconfiguration fails at startup

### Contract tests / TCKs

- `AnthropicProviderAutoConfigurationTest`

### Do not

- Do not add retry/fallback logic here — the engine owns that

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
