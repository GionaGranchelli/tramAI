# 0.3.1 — Streaming Stability and Memory Persistence

0.3.1 is a patch release focused on streaming stability, memory persistence, proxy-aware tool scanning, and usage accounting for reasoning tokens.

## Features

### Reasoning Token Usage

`thinkingTokens: Int?` was added to both:

- `ModelResponse`
- `UsageMetrics`

OpenAI reasoning-token usage is parsed from `completion_tokens_details.reasoning_tokens` and wired through the engine. Azure OpenAI receives the same usage accounting path where the provider response includes the field.

## Bug Fixes

### Chat Memory Persistence

Conversation turns are now persisted correctly after:

- streaming completion
- structured output success

This closes a persistence gap where successful AI calls could update the caller-visible result without reliably recording the completed turn in configured chat memory.

### Proxy-Aware Tool Scanning

Tool discovery now handles proxied Spring/MCP tool handlers more reliably. This matters when application infrastructure wraps tool beans and the scanner needs to inspect the user-facing tool contract rather than only the proxy class.

## Refactoring

Provider streaming implementations were decomposed into smaller HTTP error handling and response parsing helpers.

Affected providers:

- OpenAI
- Anthropic
- Azure OpenAI

This keeps the streaming behavior easier to audit and reduces provider-specific parsing complexity.

## Tests

Additional coverage was added around:

- proxy-aware tool scanning
- MCP tool handlers
- `TokenAwareChatMemory`
- streaming and memory persistence behavior

## Notes

- TramAI `0.3.1` targets Java `21+`.
- This release preserves the `0.3.0` public module surface.
- Consumer dependency coordinates should use `dev.tramai:<module>:0.3.1` or the `tramai-bom:0.3.1`.
