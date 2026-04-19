# Release 0.1.0 Scope and Checklist

This page freezes the intended MVP scope for Tramai `0.1.0` and records the remaining work needed to move from strong alpha to first public release.

## Release Goal

Tramai `0.1.0` should be a credible first public release for JVM backend engineers who want:

- annotated interface methods as the primary AI abstraction
- typed structured output as a first-class path
- explicit provider routing
- observability as an optional module
- standalone and Spring Boot integration
- deterministic test support

It is not the release that tries to cover every advanced LLM feature.

## Frozen In-Scope Surface

The `0.1.0` release is frozen to the following feature set:

- core annotations and runtime proxy execution
- structured output with schema generation, parsing, validation, and retry feedback
- explicit provider registry and operation-level provider override
- Ollama, Anthropic, OpenAI, and OpenAI-compatible providers
- provider timeout and retry hardening
- OpenTelemetry integration through `tramai-observability`
- standalone runtime, Kotlin DSL, Java entry points, and blocking interfaces
- Spring Boot autoconfiguration and `tramai.*` configuration binding
- testing module with deterministic mock provider support and assertion helpers
- documentation baseline, example applications, BOM, and publication wiring

## Explicitly Out Of Scope For 0.1.0

These features are intentionally not part of `0.1.0`:

- streaming responses
- tool calling
- conversation memory
- provider-native structured output optimization
- generated proxies through KSP or other codegen
- additional framework adapters beyond Spring
- agent workflows, planners, or built-in autonomous orchestration

## Current Assessment

What is already strong:

- the core execution path works end-to-end
- structured output works and now includes timeout and provider-retry hardening
- standalone, Spring, observability, testing, and publication conventions all exist in code
- local example applications can demonstrate typed output behavior

What still blocks a credible first public MVP release:

- final release checklist closure and scope discipline
- Maven Central publication with real credentials and signing
- real-provider confidence beyond local and unit-heavy coverage
- one concrete credibility anchor or live-proof usage story

## MVP Checklist

### Documentation and Positioning

- [x] README is concise, accurate, and reflects shipped behavior rather than aspirational APIs
- [x] CONTRIBUTING guidance reflects the current module and quality boundaries
- [ ] CHANGELOG contains a real `0.1.0` entry
- [x] examples are runnable and align with current provider/runtime behavior
- [x] limitations and roadmap pages are consistent with implementation reality

### Quality and Verification

- [x] full automated test suite passes reliably
- [x] targeted failure-path coverage exists for provider timeout, retry, and malformed structured output
- [x] guarded real-provider integration coverage exists for at least Ollama and one cloud provider
- [x] example applications have at least one smoke-level verification path

### API and Scope Discipline

- [ ] public APIs included in `0.1.0` are stable enough to document honestly
- [ ] explicitly experimental features are labeled as experimental in code and docs
- [x] `0.1.0` does not take on streaming, tool calling, or memory as partial promises

### Release and Publishing

- [ ] publication metadata is correct for all publishable modules
- [ ] signed artifacts, sources JARs, and javadoc JARs are produced
- [ ] release workflow can publish on tag with real credentials
- [x] local `publishToMavenLocal` smoke path remains green

### Credibility and Adoption

- [ ] at least one real usage proof or internal integration story is documented
- [ ] provider configuration and structured-output docs are easy for a new user to follow
- [ ] Spring and standalone quickstarts are both credible and current

## Release Exit Criteria

Tramai is ready for `0.1.0` when:

- the checklist above is materially complete
- the board and specs accurately describe current status
- the release scope remains frozen
- no blocker-class mismatch exists between docs and shipped behavior

## Follow-Up After 0.1.0

The first committed post-`0.1.0` design work is:

- streaming responses
- tool calling

Conversation memory remains design work only until those two features are better defined.
