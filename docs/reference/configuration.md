# Configuration Reference

This reference describes configuration that exists in the current codebase.

## Standalone Builder

For the standalone builder API, use the dedicated reference:

- [Standalone Builder Reference](./standalone-builder.md)

## Spring Boot Namespace

Spring configuration binds under:

```yaml
tramai:
```

Top-level keys:

- `profile`
- `default-provider`
- `models`
- `fallbacks`
- `resilience`
- `cost`
- `cache`
- `secrets`
- `providers`

### `tramai.profile`

Selects the active runtime profile.

| Value | Runtime |
|---|---|
| *(missing)* | standard (default) |
| `standard` / `STANDARD` | standard |
| `sovereign` / `SOVEREIGN` | sovereign |

- Values are case-insensitive.
- Any other value fails loudly at startup — there is never a silent fallback to a runtime.
- Exactly one runtime authority is active; `standard` and `sovereign` never coexist.

Runtime and platform modules add their own namespaces:

- `tramai.server.*`
- `tramai.mcp.*`
- `tramai.dashboard.*`
- `tramai.platform.*`

## Full Current Spring Shape

```yaml
tramai:
  default-provider: openai
  models:
    gpt-4o: openai
    gpt-4o-mini: openai
    claude-sonnet-4-20250514: anthropic
    llama3.2: ollama
  fallbacks:
    gpt-4o:
      - provider: openai
        model: gpt-4o-mini
      - provider: ollama
        model: llama3.2
  resilience:
    circuit-breaker:
      enabled: true
      failure-threshold: 3
      open-duration-millis: 30000
    retry:
      max-retry-after-millis: 20000
      jitter-ratio: 0.1
  cost:
    token-budget:
      hard-max-tokens-per-attempt: 4000
      hard-max-tokens-per-operation: 12000
      soft-max-tokens-per-operation: 8000
  cache:
    in-memory:
      enabled: true
      max-entries: 1000
  secrets:
    vault:
      enabled: false
      base-url: https://vault.example.com
      token: null
      token-secret-ref: env:VAULT_TOKEN
      namespace: null
      mount-path: secret
      kv-version: 2
      default-field: value
    aws-secrets-manager:
      enabled: false
      region: eu-west-1
      endpoint: null
      access-key-id: null
      access-key-id-secret-ref: env:AWS_ACCESS_KEY_ID
      secret-access-key: null
      secret-access-key-secret-ref: env:AWS_SECRET_ACCESS_KEY
      session-token: null
      session-token-secret-ref: null
      default-field: value
  providers:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      api-key-secret-ref: null
      base-url: https://api.anthropic.com
    openai:
      api-key: ${OPENAI_API_KEY}
      api-key-secret-ref: null
      bearer-token: null
      bearer-token-secret-ref: null
      base-url: https://api.openai.com/v1
      organization: null
      project: null
      codex-auth:
        enabled: false
        auth-file: /home/you/.codex/auth.json
    openai-compatible:
      provider-name: compatible
      api-key: null
      api-key-secret-ref: null
      bearer-token: ${COMPATIBLE_API_TOKEN}
      bearer-token-secret-ref: null
      base-url: https://compatible.example.com/v1
      codex-auth:
        enabled: false
        auth-file: /home/you/.codex/auth.json
    ollama:
      base-url: http://localhost:11434
```

## Important Notes

- `models` is the main routing table
- `fallbacks` is an ordered list of explicit backup routes per requested model
- provider names in `models` must match registered provider names
- provider beans and property-defined providers are merged in Spring
- bean-backed providers override property-backed providers when names collide
- Tramai already supports per-operation timeout and retry settings through `@Operation(timeoutMillis = ..., maxRetries = ...)`
- `tramai.cache.in-memory.*` enables the built-in in-memory response cache for cacheable non-streaming operations
- `tramai.resilience.retry.max-retry-after-millis` caps provider-supplied `Retry-After` delays
- `tramai.resilience.retry.jitter-ratio` adds positive jitter to scheduled retry delays
- `tramai.resilience.circuit-breaker.*` controls engine-owned provider health protection
- `tramai.cost.token-budget.hard-max-tokens-per-attempt` fails a single provider attempt when reported usage is too high
- `tramai.cost.token-budget.hard-max-tokens-per-operation` fails a logical operation when cumulative reported usage exceeds budget across retries or tool loops
- `tramai.cost.token-budget.soft-max-tokens-per-operation` emits an engine event without failing the call
- `*-secret-ref` fields are resolved through Spring `SecretValueResolver` beans; built-in resolvers support `env:NAME` and `file:/path/to/secret.txt`
- `tramai.secrets.vault.*` enables the bundled Vault resolver for `vault:path[#field]` references
- `tramai.secrets.aws-secrets-manager.*` enables the bundled AWS Secrets Manager resolver for `aws-secretsmanager:secret-id[#field]` references

## Workflow Server Configuration

These properties exist in the current server module:

```yaml
tramai:
  server:
    max-request-body-bytes: 1048576
    max-run-history-size: 1000
    sse-event-buffer-size: 100
    max-audit-entries: 10000
    webhooks:
      secret: ""
      max-request-body-bytes: ${tramai.server.max-request-body-bytes}
```

Current behavior:

- `tramai.server.max-request-body-bytes` limits workflow run request bodies
- `tramai.server.webhooks.max-request-body-bytes` can override the workflow limit for webhook requests
- `tramai.server.max-run-history-size` bounds in-memory retained run records
- `tramai.server.sse-event-buffer-size` bounds replayable in-memory SSE history per run
- `tramai.server.max-audit-entries` bounds the in-memory audit store used by the server module
- `tramai.server.webhooks.secret` configures the GitHub-style HMAC verifier used by `POST /webhooks/{name}`

## MCP Configuration

The MCP adapter binds under:

```yaml
tramai:
  mcp:
    stdio:
      enabled: false
    sse:
      enabled: false
      host: 127.0.0.1
      port: 8091
      path: /mcp
```

Current behavior:

- MCP auto-configuration activates only when the workflow server beans are present
- `stdio.enabled=true` starts an MCP stdio session inside the application process
- `sse.enabled=true` starts a separate embedded Ktor server for MCP over SSE
- the SSE transport is independent from the Spring Boot HTTP port

## Dashboard Configuration

The dashboard-related runtime keys currently used by code are:

```yaml
tramai:
  dashboard:
    enabled: true
    auth:
      required: false
      provider: custom
```

Current behavior:

- `tramai.dashboard.enabled=false` disables the dashboard static resources
- the same flag also disables the server's worker, schedule, and audit endpoints that are currently grouped with the dashboard feature
- `tramai.dashboard.auth.required` influences the generated `/tramai-settings.js` runtime payload for the SPA
- `tramai.dashboard.auth.provider` is only used when auto-detection cannot infer a provider

## Platform Configuration

The platform module currently exposes one direct property:

```yaml
tramai:
  platform:
    plugins:
      dir: ${java.io.tmpdir}/tramai-platform-plugins
```

Current behavior:

- the plugin manager scans this directory for JARs implementing `TramaiPlugin`
- plugin state is persisted in the platform database; dropping a JAR is not the whole lifecycle story
- teams, projects, API keys, audit, and plugin state require a JDBC `DataSource` plus the platform migration set

## What Does Not Exist Yet

These configuration concepts appear in planning documents but are not fully implemented in runtime code yet:

- provider-level timeout policy configuration in standalone or Spring properties
- default max tokens and temperature at the framework level
- streaming configuration
- preflight token estimation before a provider call is sent

Document them only when they land in code.
