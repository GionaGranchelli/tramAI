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

---

## Using encrypted file-backed stores

By default, the sovereign starter uses in-memory stores for audit, approvals,
continuations, and suspended invocations. This is fine for demos and local
development, but state is lost on restart.

For restart-safe workflows, add the file persistence starter:

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":tramai-spring-boot-starter-sovereign"))
    implementation(project(":tramai-spring-boot-starter-sovereign-persistence-file"))
}
```

Then configure encrypted file persistence:

```yaml
tramai:
  sovereign:
    persistence:
      type: file
      base-dir: ./data/tramai-sovereign
      encryption:
        key-env: TRAMAI_SOVEREIGN_STORE_KEY
```

Generate a 256-bit key:

```bash
openssl rand -base64 32
```

Set the environment variable before running:

```bash
export TRAMAI_SOVEREIGN_STORE_KEY="<generated-base64-key>"
```

When `type: file` is configured, the file persistence auto-configuration:
- Runs **before** the base starter, so file-backed store beans are registered first
- The base starter's `@ConditionalOnMissingBean` sees the file-backed beans and backs off
- All four stores (audit, approvals, continuations, suspended invocations) use
  encrypted file-backed implementations
- Stores survive application restarts

### Fail-closed validation

| Condition | Error code |
|---|---|
| `type=file` but `base-dir` missing | `tramai-sovereign-file-persistence-missing-base-dir` |
| Both `key-env` and `key-file` missing | `tramai-sovereign-file-persistence-missing-key-source` |
| Both `key-env` and `key-file` set | `tramai-sovereign-file-persistence-ambiguous-key-source` |
| `key-env` set but env var missing/blank | `tramai-sovereign-file-persistence-missing-key-env` |
| Key file missing | `tramai-sovereign-file-persistence-key-file-missing` |
| Invalid key (bad base64 / wrong size) | `tramai-sovereign-file-persistence-invalid-key` |
| Base dir cannot be created | `tramai-sovereign-file-persistence-base-dir-unavailable` |

### Expected directory layout

```
<base-dir>/
  .tramai.lock
  manifest.json
  approvals/
  continuations/
  audit/
  suspended/
```

### Key requirements

- Exactly one key source: `key-env` (environment variable) or `key-file` (file)
- Key must be base64-encoded 256-bit AES key (decodes to 32 bytes)
- Plaintext keys in YAML are **not** supported
- Keys are never logged and never appear in exception messages

### Test

```bash
./gradlew :tramai-spring-boot-starter-sovereign-persistence-file:test
```

### Migration guide

1. Add the `tramai-spring-boot-starter-sovereign-persistence-file` dependency
2. Generate a key with `openssl rand -base64 32`
3. Set `TRAMAI_SOVEREIGN_STORE_KEY` in your environment
4. Add `tramai.sovereign.persistence.*` to `application.yml`
5. Restart — existing in-memory state is lost (this is expected)
6. Verify: state survives subsequent restarts
