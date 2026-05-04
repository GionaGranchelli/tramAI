# Changelog

## Unreleased

- No unreleased changes yet.

## 0.2.0

Release entry for the current repository milestone.

### Added

- `tramai-scheduler` for cron scheduling and durable schedule stores
- `tramai-server` for REST, webhook, OpenAPI, and SSE workflow operations
- `tramai-mcp` for exposing workflows through the MCP protocol
- `tramai-platform` for multi-tenancy, API keys, rate limiting, plugins, and audit logging
- `tramai-dashboard` for the optional Vue 3 admin UI
- worker-pool orchestration features including lease-based work stealing, fencing, heartbeat registry, and graceful shutdown

### Changed

- documentation and repository structure now reflect the broader `0.2.0` operational surface
- orchestration moved from an early optional add-on to a documented core runtime pillar

### Notes

- Tramai `0.2.x` targets Java `25+`.
- See [docs/releases/CHANGELOG-0.2.0.md](docs/releases/CHANGELOG-0.2.0.md) for the detailed module-by-module release summary.

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
