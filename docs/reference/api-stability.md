# API Stability

This page defines the public API stability expectations for TramAI `0.1.x`.

## Stability Policy For `0.1.x`

TramAI `0.1.x` is an early public line, but it is not intended to be arbitrary.

For stable public APIs in `0.1.x`, the project aims to avoid intentional breaking changes in:

- exported public types and method signatures
- documented Spring property names under `tramai.*`
- published module coordinates
- documented provider ids and routing behavior
- documented failure semantics for retries, structured output, and token budgets

Changes that remain acceptable in `0.1.x`:

- bug fixes that correct behavior to match existing docs
- additive public APIs
- stronger validation when the old behavior was ambiguous or unsafe
- internal refactors that do not change the documented contract

## Stable Surface In `0.1.0`

The following modules are treated as stable release surface for `0.1.0`:

- `tramai-core`
- `tramai-engine`
- `tramai-structured`
- `tramai-standalone`
- `tramai-spring`
- `tramai-openai`
- `tramai-anthropic`
- `tramai-ollama`
- `tramai-observability`
- `tramai-orchestration`
- `tramai-testing`
- `tramai-bom`

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

Explicitly out of scope for the frozen `0.1.x` contract:

- typed deadline or budget-hint fields in `WorkflowContext`
- max-round or terminal-predicate controls in `StopPolicy`
- mid-step replay or token-level stream resume

## Experimental Surface

These public APIs are intentionally not covered by the `0.1.x` stability promise.

### Codex/ChatGPT Auth-File Reuse

The OpenAI auth-file path is experimental.

That includes:

- `@ExperimentalCodexAuth`
- `OpenAiProvider.codexAuth(...)`
- `OpenAiCompatibleProvider.codexAuth(...)`
- Spring properties under `tramai.providers.openai.codex-auth.*`
- Spring properties under `tramai.providers.openai-compatible.codex-auth.*`

## Release Baseline Assumptions

The `0.1.x` line is documented against these baseline assumptions:

- Java `25+`
- Kotlin-first APIs with Java-friendly blocking interfaces
- framework-agnostic core with optional Spring integration

If any of those assumptions change, that should be treated as release-level change, not quiet drift.

## Audit Result For `0.1.0`

The current `0.1.0` sweep concludes:

- the stable surface is narrow enough to document honestly
- experimental areas are explicit in code and docs
- orchestration is now part of the documented stable surface with explicit scope boundaries
- the main remaining launch risk is release execution, not undefined public API scope
