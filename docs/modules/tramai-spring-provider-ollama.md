# Module: `tramai-spring-provider-ollama`

> **One-liner:** Spring auto-configuration for the Ollama provider — wires OllamaProvider as a bean with ollama property namespace.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Spring auto-configuration for the Ollama provider: `OllamaProviderAutoConfiguration` + `OllamaProperties` wiring `OllamaProvider` as a bean.

### Public entry points

- `OllamaProviderAutoConfiguration` — auto-configuration
- `OllamaProperties` — configuration properties

Verify against `tramai-spring-provider-ollama/api/tramai-spring-provider-ollama.api`.

### Internal extension points

- Provider property namespace (`tramai.providers.ollama.*`)

### Significant dependencies

- `api(tramai-spring-core)`; `implementation(tramai-ollama)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; provider bean retains injected/default `HttpClient` (no close contract)

### Thread-safety and concurrency

- Provider bean must be safe for concurrent invocation

### Failure semantics

- Provider failures normalized per `OllamaProvider` contracts; misconfiguration fails at startup

### Contract tests / TCKs

- `OllamaProviderAutoConfigurationTest`

### Do not

- Do not add retry/fallback logic here — the engine owns that

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
