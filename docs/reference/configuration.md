# Configuration Reference

This reference describes configuration that exists in the current codebase.

## Standalone Builder

The standalone builder exposes:

- `provider(provider, name = ..., default = ...)`
- `model(modelName, providerName)`
- `defaultProvider(providerName)`
- `observer(observer)`

Example:

```kotlin
val aurora = Aurora {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai")
    model("gpt-5.1-chat-latest", "openai")
    defaultProvider("openai")
}
```

## Spring Boot Namespace

Spring configuration binds under:

```yaml
aurora:
```

Top-level keys:

- `default-provider`
- `models`
- `providers`

## Full Current Spring Shape

```yaml
aurora:
  default-provider: openai
  models:
    gpt-5.1-chat-latest: openai
    claude-sonnet-4-20250514: anthropic
    llama3.2: ollama
  providers:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      base-url: https://api.anthropic.com
    openai:
      api-key: ${OPENAI_API_KEY}
      bearer-token: null
      base-url: https://api.openai.com/v1
      organization: null
      project: null
      codex-auth:
        enabled: false
        auth-file: /home/you/.codex/auth.json
    openai-compatible:
      provider-name: compatible
      api-key: null
      bearer-token: ${COMPATIBLE_API_TOKEN}
      base-url: https://compatible.example.com/v1
      codex-auth:
        enabled: false
        auth-file: /home/you/.codex/auth.json
    ollama:
      base-url: http://localhost:11434
```

## Important Notes

- `models` is the main routing table
- provider names in `models` must match registered provider names
- provider beans and property-defined providers are merged in Spring
- bean-backed providers override property-backed providers when names collide
- Aurora already supports per-operation timeout and retry settings through `@Operation(timeoutMillis = ..., maxRetries = ...)`

## What Does Not Exist Yet

These configuration concepts appear in planning documents but are not fully implemented in runtime code yet:

- provider-level timeout policy configuration in standalone or Spring properties
- provider-level retry policy configuration in standalone or Spring properties
- default max tokens and temperature at the framework level
- streaming configuration

Document them only when they land in code.
