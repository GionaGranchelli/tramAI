# Module: `tramai-spring-sovereign`

> **One-liner:** Sovereign-profile Spring integration — auto-configures the sovereign TramAI runtime (authority guard, sovereign AI-service proxy, sovereign properties) for profile-enabled Boot applications.

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Sovereign profile Spring integration: the sovereign composition layer under the unified starter. Provides the top-level `SovereignTramaiAutoConfiguration` that wires the sovereign runtime into a Boot context, plus the `SovereignTramaiProperties` namespace.

### Public entry points

- `SovereignTramaiAutoConfiguration` — auto-configuration (top-level public type in the API dump)
- `SovereignTramaiProperties` — configuration properties

Verify against `tramai-spring-sovereign/api/tramai-spring-sovereign.api` — only these two are top-level public types.

### Internal extension points

- `SovereignTramaiProfileAutoConfiguration` — internal profile-activation wiring (not public API)
- `StandardProfileSovereignAuthorityGuardAutoConfiguration` — internal authority-guard wiring (not public API)
- `SovereignAiServiceProxyAutoConfiguration` — internal sovereign AI-service proxy wiring (not public API)
- Sovereign profile wiring; authority-guard seam

### Significant dependencies

- `api(tramai-core)`, `api(tramai-security)`, `api(tramai-sovereign)`, `api(tramai-spring-core)` — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; sovereign runtime owned by the container

### Thread-safety and concurrency

- Spring singletons; proxies must be safe for concurrent invocation

### Failure semantics

- Sovereignty violations surface as typed guard/authority errors

### Contract tests / TCKs

- Sovereign spring tests + E2E (sovereign-lab profile)

### Do not

- Do not add ops/persistence here — those live in the sovereign-ops/persistence starters

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — framework-integrations layer
