# Spring Boot Sovereign Starter Example

A minimal Spring Boot application that demonstrates how to use the TramAI
Sovereign runtime starter in a standard enterprise project.

## What this example proves

A Spring Boot developer can use TramAI Sovereign by:

1. **Adding the starter dependency** to `build.gradle.kts`
2. **Configuring sovereign policy** in `application.yml`
3. **Providing a `ModelProvider` bean** (here: a deterministic local provider)
4. **Injecting `SovereignTramaiRuntime`** (auto-configured by the starter)
5. **Creating a typed `@AiService` interface** with `@Operation(model = "…")`
6. **Executing the service** through the runtime

## Configuration

```yaml
tramai:
  sovereign:
    enabled: true
    allowed-models:
      - local-invoice-model
    allowed-providers:
      - deterministic-local-provider
    provider-zones:
      deterministic-local-provider: LOCAL
    models:
      local-invoice-model: deterministic-local-provider
```

### Why this is sovereign

| Property | What it enforces |
|---|---|
| `allowed-models` | Only `local-invoice-model` may be invoked |
| `allowed-providers` | Only `deterministic-local-provider` is trusted |
| `provider-zones` | The provider is explicitly marked `LOCAL` — no cloud routing |
| `models` | Routes the logical model name to the registered provider |

If any of these are misconfigured or missing, the runtime fails closed with a
descriptive error code (e.g. `tramai-sovereign-spring-missing-allowed-models`).

### No cloud call is made

- The only registered provider is `DemoModelProvider`, which returns a
  hard-coded JSON response.
- No HTTP client, API key, or network dependency is configured.
- The sovereign runtime enforces that only the configured provider and model
  are accessible.

## How to run

```bash
# From the repository root
./gradlew :examples:spring-sovereign-starter:bootRun
```

Because the application has no web dependency and uses a `CommandLineRunner`,
it starts, runs the analysis, prints the result, and exits cleanly.

### Expected output

```
Sovereign invoice analysis result:
InvoiceAnalysisResult(
    summary=Invoice requires review before payment.,
    riskLevel=MEDIUM,
    detectedRisks=[Restricted customer reference present, High-value invoice, Short payment window],
    recommendedAction=Route to finance approval workflow
)
```

## How the starter wires the runtime

1. `SovereignTramaiAutoConfiguration` reads `tramai.sovereign.*` properties.
2. It creates `SovereignProfileConfiguration` with validated allowlists.
3. It creates an `InMemoryModelRegistry` with the model from `models` entries.
4. It creates default in-memory stores for audit, approval, and continuation.
5. When a `ModelProvider` bean is found (our `DemoModelProvider`), it builds
   `SovereignTramai` and exposes `SovereignTramaiRuntime`.
6. The application injects `SovereignTramaiRuntime` and calls
   `runtime.create(InvoiceAiService::class)`.

All beans use `@ConditionalOnMissingBean`, so users can override any default.

## Test

```bash
# From the repository root
./gradlew :examples:spring-sovereign-starter:test
```

The test verifies:
- Spring context starts with `SovereignTramaiRuntime`
- `analyzeInvoice` returns a deterministic result through the sovereign runtime
- `riskLevel` is `MEDIUM`
- `detectedRisks` is non-empty
