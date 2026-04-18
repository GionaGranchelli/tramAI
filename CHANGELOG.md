# Changelog

## Unreleased

## 0.1.0-SNAPSHOT

### Added

- `aurora-core`, `aurora-engine`, and `aurora-structured`
- provider modules for Anthropic, OpenAI, OpenAI-compatible APIs, and Ollama
- optional `aurora-observability`
- `aurora-standalone`, `aurora-spring`, `aurora-testing`, and `aurora-bom`
- deterministic testing helpers including mock and simulated-failure providers
- repository documentation, ADRs, specs, task board, and contributor guidance
- Kotlin Spring Boot example project backed by locally published artifacts

### Changed

- provider resolution now uses an explicit registry
- timeout and retry hardening is covered in engine and provider tests
- release docs now freeze the `0.1.0` scope and separate it from later streaming and tool-calling work

### Notes

- This is still a snapshot line, not a final public release tag.
- The eventual `0.1.0` release entry should be cut from this section once publish operations complete.
