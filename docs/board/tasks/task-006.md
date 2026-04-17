# TASK-006: Implement Anthropic and Ollama providers with routing

- Status: todo
- Priority: high
- Primary spec: [SPEC-003](../../specs/spec-003-provider-integration.md)
- Related ADRs: [ADR-008](../../adr/adr-008.md), [ADR-010](../../adr/adr-010.md)
- Last updated: 2026-04-18

## Rationale

M3 is where Aurora becomes usable beyond stubs. The project needs one local-first and one cloud provider, plus deterministic registry-based routing between them.

## Scope

- implement Anthropic provider
- implement Ollama provider
- add explicit provider registry and operation-level provider selection support
- add provider retry and timeout handling
- define guarded integration test strategy

## Definition Of Done

- real `@AiService` calls succeed against Anthropic and Ollama
- explicit registry resolution works for registered models and explicit provider selection
- transient provider failures can retry under configured limits

## Notes

Do not broaden scope to OpenAI in this milestone.
Do not rely on implicit model-prefix fallback as the primary routing contract.
