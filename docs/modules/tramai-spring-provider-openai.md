# Module: `tramai-spring-provider-openai`

> **One-liner:** Spring auto-configuration for OpenAI/OpenAI-compatible providers — wires provider beans with openai property namespace and Codex auth support.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Spring auto-configuration for OpenAI/OpenAI-compatible providers: `OpenAiProviderAutoConfiguration` + `OpenAiProperties`/`OpenAiCompatibleProperties`/`CodexAuth` wiring provider beans.

### Public entry points

- `OpenAiProviderAutoConfiguration` — auto-configuration
- `OpenAiProperties`, `OpenAiCompatibleProperties` — configuration properties
- `CodexAuth` — Codex auth support

Verify against `tramai-spring-provider-openai/api/tramai-spring-provider-openai.api`.

### Internal extension points

- Provider property namespace (`tramai.providers.openai.*`)

### Significant dependencies

- `api(tramai-spring-core)`; `implementation(tramai-openai)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; provider beans retain injected/default `HttpClient` (no close contract)

### Thread-safety and concurrency

- Provider beans must be safe for concurrent invocation

### Failure semantics

- Provider failures normalized per `OpenAiProvider` contracts; misconfiguration fails at startup

### Contract tests / TCKs

- `OpenAiProviderAutoConfigurationTest`

### Do not

- Do not add retry/fallback logic here — the engine owns that

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
