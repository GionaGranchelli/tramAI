# API Stability

This page defines the public API stability expectations for Tramai `0.3.x`.

## Stability Policy For `0.3.x`

Tramai `0.3.x` is still pre-`1.0`, but it is not intended to be arbitrary.

For stable public APIs in `0.3.x`, the project aims to avoid intentional breaking changes in:

- exported public types and method signatures
- documented Spring property names under `tramai.*`
- published module coordinates
- documented provider ids and routing behavior
- documented failure semantics for retries, structured output, and token budgets

Changes that remain acceptable in `0.3.x`:

- bug fixes that correct behavior to match existing docs
- additive public APIs
- stronger validation when the old behavior was ambiguous or unsafe
- internal refactors that do not change the documented contract

## Stable Consumer Surface In `0.3.x`

The following consumer-facing modules are the primary stable surface in `0.3.x`:

- `tramai-core`
- `tramai-engine`
- `tramai-structured`
- `tramai-standalone`
- `tramai-spring`
- `tramai-openai`
- `tramai-azure-openai`
- `tramai-anthropic`
- `tramai-bedrock`
- `tramai-gemini`
- `tramai-deepseek`
- `tramai-ollama`
- `tramai-observability`
- `tramai-orchestration`
- `tramai-memory`
- `tramai-embedding`
- `tramai-rag`
- `tramai-vectorstore-spi`
- `tramai-vectorstore-chroma`
- `tramai-vectorstore-pgvector`
- `tramai-testing`
- `tramai-bom`

These modules reflect the core library plus optional, application-level capabilities that are already documented as normal consumer dependencies.

## Fast-Moving Runtime And Platform Surface

The repository also contains newer operational modules:

- `tramai-memory-store`
- `tramai-scheduler`
- `tramai-server`
- `tramai-mcp`
- `tramai-platform`
- `tramai-dashboard`

Those modules are real and implemented, but they should be treated as fast-moving until they are explicitly documented as frozen published API. They are part of the repository story, not the narrowest compatibility promise.

Stable contract areas include:

- `@AiService`, `@Operation`, and `@SystemPrompt`
- provider registry and explicit model routing
- standalone builder and Spring Boot configuration binding
- structured-output schema generation, parsing, and validation loop
- engine-owned retries, fallback routing, circuit breaking, caching, and token budgets
- observability module spans and metric instrument names documented in the guides
- orchestration workflow definition, observation, checkpoint/resume, and optional lease contracts documented in the guides

Stable orchestration contract:

- `workflow(...)`, `WorkflowBuilder`, and `Workflow`
- `WorkflowContext(workflowId, attributes)`
- `StopPolicy(maxStepExecutions, maxParallelBranches)`
- `WorkflowObserver`
- `GateDecision`
- step shapes: `localStep(...)`, `aiStep(...)`, `gateStep(...)`, `branchStep(...)`, `parallelStep(...)`
- checkpoint/resume SPI: `WorkflowPersistence`, `WorkflowStateCodec`, `WorkflowCheckpointStore`, `WorkflowCheckpoint`
- optional lease SPI: `WorkflowLeaseStore`, `WorkflowLeasePolicy`, `WorkflowLease`

Explicitly out of scope for the frozen `0.3.x` core compatibility contract:

- typed deadline or budget-hint fields in `WorkflowContext`
- max-round or terminal-predicate controls in `StopPolicy`
- mid-step replay or token-level stream resume

## Experimental Surface

These public APIs are intentionally not covered by the `0.3.x` stability promise.

### Codex/ChatGPT Auth-File Reuse

The OpenAI auth-file path is experimental.

That includes:

- `@ExperimentalCodexAuth`
- `OpenAiProvider.codexAuth(...)`
- `OpenAiCompatibleProvider.codexAuth(...)`
- Spring properties under `tramai.providers.openai.codex-auth.*`
- Spring properties under `tramai.providers.openai-compatible.codex-auth.*`

## Release Baseline Assumptions

The `0.3.x` line is documented against these baseline assumptions:

- Java `21+`
- Kotlin-first APIs with Java-friendly blocking interfaces
- framework-agnostic core with optional Spring integration

If any of those assumptions change, that should be treated as release-level change, not quiet drift.

## Current Audit Summary

The current `0.3.x` documentation sweep concludes:

- the core and application-facing library surface is broad but still documentable as a coherent contract
- experimental areas should stay explicit in code and docs
- the biggest documentation risk is no longer missing modules, but confusing stable consumer modules with newer operational surfaces
- top-level docs should always make the stability split visible instead of burying it in release notes or board history
