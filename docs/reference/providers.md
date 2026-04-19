# Provider Reference

This page summarizes the current provider modules.

## `tramai-anthropic`

Class:

- `AnthropicProvider`

Purpose:

- Anthropic Messages API integration

Auth:

- API key

Config shape:

```yaml
tramai:
  providers:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      base-url: https://api.anthropic.com
```

## `tramai-openai`

Classes:

- `OpenAiProvider`
- `OpenAiCompatibleProvider`
- `CodexAuthFileTokenSource` (experimental)

Purpose:

- public OpenAI API integration
- OpenAI-compatible endpoint integration

Auth modes:

- API key
- explicit bearer token
- local Codex auth file, experimental

Config shape:

```yaml
tramai:
  providers:
    openai:
      api-key: ${OPENAI_API_KEY}
```

## `tramai-ollama`

Class:

- `OllamaProvider`

Purpose:

- local Ollama chat API integration

Config shape:

```yaml
tramai:
  providers:
    ollama:
      base-url: http://localhost:11434
```

## Capability Summary

Current shared baseline:

- request/response normalization
- raw string operations
- structured output via Tramai's schema-in-prompt pipeline
- explicit provider registry integration
- operation-level timeout propagation
- engine-owned retries for retryable provider failures

Not implemented across the provider layer yet:

- streaming
- tool calling
- native provider-specific structured output modes
- externalized provider-level retry-policy configuration
- externalized provider-level timeout-policy configuration

## Choosing A Provider

Use:

- Anthropic when your app is centered on Claude models
- OpenAI when your app is centered on OpenAI models or compatible gateways
- Ollama for local development and self-hosted local runs

Use multiple providers when different tasks have different needs.
