# Changelog

## Unreleased

- No unreleased changes yet.

## 0.1.0

Release entry prepared for the first public release of TramAI.

### Added

- `tramai-core`, `tramai-engine`, and `tramai-structured`
- provider modules for Anthropic, OpenAI, OpenAI-compatible APIs, and Ollama
- optional `tramai-observability`
- `tramai-standalone`, `tramai-spring`, `tramai-testing`, and `tramai-bom`
- deterministic testing helpers including mock and simulated-failure providers
- raw text streaming support (`Flow<StreamChunk>`) in core and major providers (OpenAI, Anthropic, Ollama)
- engine-owned, provider-portable tool calling orchestration with `@AiTool` discovery
- repository documentation, ADRs, specs, task board, and contributor guidance
- Kotlin Spring Boot example project backed by locally published artifacts

### Changed

- provider resolution now uses an explicit registry
- timeout and retry hardening is covered in engine and provider tests
- Spring Boot example modernized to support asynchronous Flow streaming

### Notes

- TramAI `0.1.x` targets Java `25+`.
- `tramai-orchestration` ships as an optional experimental module while its API settles.
- Add the final release date when the `v0.1.0` tag and Maven Central publication complete.
