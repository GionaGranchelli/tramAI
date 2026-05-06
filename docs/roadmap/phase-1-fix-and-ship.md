# Phase 1: Fix & Ship

> **Document purpose:** Define the three concrete changes to TramAI before public release, with layered documentation, implementation specifications, and acceptance criteria.
>
> **Audience:** TramAI maintainers implementing Phase 1 changes.
>
> **Prerequisite:** Phase 0 audit must be complete. See `docs/roadmap/phase-0-module-audit.md`.
>
> **Status:** Final — reviewed by Codex (v0.128.0), fixes applied. Gemini review skipped (429). Self-verified.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [P0: Multi-Message Annotations](#2-p0-multi-message-annotations)
3. [P0: Module Decision Tree](#3-p0-module-decision-tree)
4. [P1: README with Killer Example](#4-p1-readme-with-killer-example)
5. [Implementation Order & Dependencies](#5-implementation-order--dependencies)
6. [Acceptance Criteria](#6-acceptance-criteria)
7. [Appendix: Reference Implementations](#7-appendix-reference-implementations)

---

## 1. Executive Summary

### What

Phase 1 delivers three changes to TramAI:

1. **Multi-message annotations** (`@System`, `@User`) — extend the annotation model from single-prompt to multi-message, matching the system/user role split that every LLM API uses
2. **Module decision tree** (`docs/module-guide.md`) — a single page that tells a new user exactly which modules to add and when, turning the 12-module architecture from a barrier into a feature
3. **README rewrite** — one 20-line working example that demonstrates TramAI's value proposition in a single code block

### Why

These are the three highest-leverage changes for public release:

| Change | Impact | Effort |
|--------|--------|--------|
| Multi-message annotations + `@SystemPrompt` integration | Closes the #1 DX gap; aligns with Spring AI / LangChain4j mental model | 2 new annotations + engine update + precedence logic |
| Module decision tree | Eliminates "12 modules? Where do I start?" cognitive load | One markdown file |
| README example | First impression determines adoption | One code block + narrative |

### How

Each change is specified in this document with three layers of detail:
- **L1: Quick Start** — what it is, why it matters, what the end result looks like
- **L2: Specification** — exact API surface, behavior, edge cases
- **L3: Internal Mechanics** — implementation notes, data flow, test requirements

### When

Phase 1 takes 2-3 weeks for one implementer, or 1 week with parallel workstreams (annotations and docs can proceed independently).

### Where

| Change | Output file | Module affected |
|--------|-------------|-----------------|
| Multi-message annotations | `tramai-core/.../annotations/System.kt`, `User.kt` | core, engine |
| Module decision tree | `docs/module-guide.md` | documentation |
| README example | `README.md` | root |

---

## 2. P0: Multi-Message Annotations

### L1: Quick Start

#### What
Two new annotations that let developers specify separate system and user messages, matching the role-based message structure that every LLM API (OpenAI, Anthropic, Ollama) uses natively.

#### Why
The current `@Operation(prompt = "...")` collapses everything into a single user message. Most production AI applications require:
- A **system message** to set the model's role, constraints, and context
- A **user message** carrying the actual request with parameter interpolation
- Optionally, **assistant messages** for conversation history or few-shot examples

Without this, developers must concatenate strings — exactly what TramAI was designed to eliminate.

#### End result

```kotlin
@AiService
interface SupportAgent {
    @System("You are a Tier-1 support agent. Be concise and empathetic.")
    @User("Customer issue: {issue}\nAccount tier: {tier}")
    @Operation(model = "gemma4:e2b", tools = ["lookupOrder"])  // prompt is now optional with @User
    suspend fun handle(issue: String, tier: String): SupportResponse
}
```
> **Note:** This example assumes Phase 1 changes are applied (`@Operation.prompt` made optional). During the migration window, `prompt = ""` is equivalent.

---

### L2: Specification

#### Annotation API

##### `@System`

```kotlin
package dev.tramai.core.annotations

/**
 * Defines the system-level instruction for an [Operation].
 *
 * The value is sent as a `system` role message in the provider's chat
 * completions API. Multiple `@System` annotations on the same function
 * are concatenated with newlines in declaration order.
 *
 * If no `@System` annotation is present on the method, the engine
 * checks for a class-level [SystemPrompt] annotation on the [AiService]
 * interface. If that also is absent, a default system message is
 * constructed from the interface name and method signature.
 *
 * When both `@System` (method) and `@SystemPrompt` (class) are present,
 * `@System` takes precedence (method-level wins), and a warning is logged.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class System(
    /** System instruction template with {param} interpolation markers. */
    val value: String,
)
```

##### `@User`

```kotlin
package dev.tramai.core.annotations

/**
 * Defines a user-role message for an [Operation].
 *
 * The value is sent as a `user` role message. Multiple `@User` annotations
 * are sent as separate user messages in order. Parameter interpolation
 * ({paramName}) resolves against the method's parameter names.
 *
 * If neither `@User` nor `@Operation.prompt` is present, the engine
 * constructs a default user message (see L3).
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class User(
    /** User message template with {param} interpolation markers. */
    val value: String,
)
```

##### `@Operation.prompt` deprecation path + `@SystemPrompt` precedence

The existing `@Operation(prompt = "...")` field is **currently required** (no default). In Phase 1, `prompt` is **made optional** by giving it an empty-string default:

```kotlin
annotation class Operation(
    val prompt: String = "",  // ← changed: was required, now optional
    ...
)
```

The precedence rules for message construction are:

1. If `@User` annotations present → build messages from `@System` + `@User` (ignore `prompt`)
2. If `@User` absent AND `prompt != ""` → use `prompt` as single user message (backward compat)
3. If no `@User` AND `prompt == ""` → engine constructs default user message

For system messages:

1. If `@System` annotation present (method-level) → use it (overrides class-level)
2. If `@System` absent AND `@SystemPrompt` present (class-level) → use `@SystemPrompt`
3. If both absent → inject default system message
4. If `@System` AND `@SystemPrompt` both present → `@System` wins (method-level overrides class-level), log a warning

This ensures zero breaking changes for existing code.

#### Message ordering

```
Messages are sent to the provider in this order:
1. All @System values (concatenated or sequential, provider-dependent)
2. All @User values (sequential, in declaration order)
```

#### Interpolation rules

`{paramName}` markers in annotation values resolve to method parameters by name. The engine uses Kotlin's `KParameter.name` for resolution.

```kotlin
@User("Hello {name}, your balance is {balance}")
fun greet(name: String, balance: Double): String
// → "Hello Alice, your balance is 42.50"
```

If a parameter name in the annotation doesn't match any method parameter, the engine throws `TramaiException` at proxy creation time (fail fast).

#### Backward compatibility

| Old style | New style | Behavior |
|-----------|-----------|----------|
| `@Operation(prompt = "X")` | Same (no changes) | Single user message "X" |
| `@Operation(prompt = "X")` | Add `@System("Y")` | System="Y", User="X" |
| — | `@System("X") @User("Y")` | System="X", User="Y" |
| — | `@User("X")` (no `@System`) | Default system + User="X" |
| — | `@System("X")` (no `@User`) | System="X", default user |

---

### L3: Internal Mechanics

#### Default System Message

When no `@System` annotation is present, the engine constructs:

```
You are an AI assistant implementing the "{interfaceName}" service.
Method: {methodName}({parameterTypes})
Return type: {returnType}
```

Example for `fun handle(issue: String, tier: String): SupportResponse`:
```
You are an AI assistant implementing the "SupportAgent" service.
Method: handle(issue: String, tier: String)
Return type: SupportResponse
```

This provides enough context for the model to understand its role without requiring explicit `@System` annotations for simple cases.

#### Proxy dispatch changes

In `tramai-engine`, the proxy handler (`AiServiceProxy` or equivalent) must:

1. Read `@System` annotations from the method
2. Read `@User` annotations from the method
3. If neither exists, fall back to `@Operation.prompt`
4. Build `List<Message>` where `Message` is:
   ```kotlin
   data class Message(
       val role: MessageRole,  // SYSTEM | USER | ASSISTANT | TOOL
       val content: String,
   )
   ```
5. Pass messages to `ModelRequest`

#### ModelRequest changes

`ModelRequest.messages` already supports `List<Message>`. No structural changes needed — only the message construction logic in the engine changes.

#### Provider compatibility

All three existing providers (OpenAI, Anthropic, Ollama) support system messages natively:
- OpenAI: `role: "system"`
- Anthropic: `system` field (top-level, not in messages array)
- Ollama: `role: "system"` (OpenAI-compatible)

The engine must map `MessageRole.SYSTEM` to the appropriate provider format. The Anthropic adapter needs special handling since Anthropic uses a top-level `system` parameter rather than a message role.

#### Test requirements

| Test case | What it verifies |
|-----------|------------------|
| `@System` + `@User` → correct message list | Two messages with correct roles |
| `@Operation.prompt` backward compat (with `prompt` set, no `@User`) | Single user message as before |
| `@Operation.prompt` backward compat (empty `prompt`, `@User` present) | Builds from `@User`, ignores `prompt` |
| `@SystemPrompt` class-level (no `@System`) | Class-level system prompt used |
| `@System` (method) overrides `@SystemPrompt` (class) | Method-level wins, warning logged |
| Multiple `@System` | Concatenation or sequential messages |
| Multiple `@User` | Sequential user messages |
| No annotations → default system | Default system message constructed |
| Unknown param → `TramaiException` | Fail-fast at proxy creation |
| Anthropic system mapping | System → top-level `system` field |
| Interpolation with all param types | String, Int, Double, Boolean, data class |

---

## 3. P0: Module Decision Tree

### L1: Quick Start

#### What
A single markdown file (`docs/module-guide.md`) that answers the question "which modules do I need?" for every possible user persona.

#### Why
12 modules without guidance is friction. A decision tree turns it into a feature: "pick the modules you need, ignore the rest."

#### End result

A new user reads the top of the file and knows exactly what to add to `build.gradle.kts` in under 30 seconds.

---

### L2: Specification

#### Structure

The file has three sections:

1. **Decision tree** (flowchart-style, text-based) — start from "what are you building?" and end with a dependency list
2. **Module reference table** — every module with one-liner, dependency, and when to add it
3. **Quick-start recipes** — common stacks with copy-paste `build.gradle.kts` blocks

#### Decision tree

```text
What are you building?
│
├─ A Spring Boot application?
│   ├─ Need local AI?  → tramai-spring + tramai-ollama
│   ├─ Need OpenAI?    → tramai-spring + tramai-openai
│   └─ Need both?      → tramai-spring + tramai-ollama + tramai-openai
│
├─ A Kotlin CLI or library (no Spring)?
│   ├─ Need local AI?  → tramai-standalone + tramai-ollama
│   └─ Need OpenAI?    → tramai-standalone + tramai-openai
│
├─ Writing tests?
│   └─ Add tramai-testing (always in test scope)
│
├─ Need structured output?
│   └─ tramai-structured is auto-included by engine
│
├─ Need multi-step workflows?
│   ├─ Simple?          → Just use @AiService + @Operation
│   └─ Complex?         → Add tramai-orchestration
│
├─ Need observability?
│   └─ Add tramai-observability (optional, opt-in)
│
└─ Managing multi-module versions?
    └─ Import tramai-bom
```

#### Module reference table

```
| Module | Purpose | Adds deps | When to add |
|--------|---------|-----------|-------------|
| tramai-core | Annotations + contracts | — | Always |
| tramai-engine | Proxy dispatch + execution | core | Always |
| tramai-structured | Schema gen + validation | core, engine | For non-String returns |
| tramai-ollama | Local AI provider | core | Dev / local-first |
| tramai-openai | OpenAI provider | core | Cloud deployment |
| tramai-anthropic | Anthropic provider | core | Anthropic shop |
| tramai-observability | OpenTelemetry spans | engine (opt) | Need tracing |
| tramai-orchestration | Multi-step workflows | engine | Complex pipelines |
| tramai-spring | Spring Boot auto-config | engine + provider | Spring project |
| tramai-standalone | No-Spring entry point | core + engine + structured | Non-Spring project |
| tramai-testing | Test utilities | core | Test scope only |
| tramai-bom | Version alignment | — | Multi-module projects |
| tramai-server | HTTP API + webhooks | orchestration | Platform deployment |
| tramai-scheduler | Cron scheduling | orchestration | Time-based triggers |
| tramai-platform | Multi-tenancy + RBAC | server | SaaS deployment |
| tramai-mcp | MCP server adapter | server | MCP ecosystem |
| tramai-dashboard | Admin UI | server | Visual dashboard |
```

#### Quick-start recipes

```kotlin
// === Recipe 1: Spring Boot + Local AI (most common start) ===
// build.gradle.kts
implementation("dev.tramai:tramai-spring:0.2.0")
implementation("dev.tramai:tramai-ollama:0.2.0")

// === Recipe 2: Kotlin CLI + OpenAI ===
implementation("dev.tramai:tramai-standalone:0.2.0")
implementation("dev.tramai:tramai-openai:0.2.0")

// === Recipe 3: Full stack (Spring + cloud + observability) ===
implementation(platform("dev.tramai:tramai-bom:0.2.0"))
implementation("dev.tramai:tramai-spring")
implementation("dev.tramai:tramai-openai")
implementation("dev.tramai:tramai-anthropic")
implementation("dev.tramai:tramai-observability")
testImplementation("dev.tramai:tramai-testing")
```

---

## 4. P1: README with Killer Example

### L1: Quick Start

#### What
A rewritten `README.md` that leads with a single working code block — "Customer Support AI in 20 lines" — and uses it to demonstrate every key feature: annotations, structured output, tool calling, local AI.

#### Why
The first thing a developer sees determines whether they try your library or close the tab. A working 20-line example that they can run immediately with `ollama pull gemma4:e2b` is worth more than 50 pages of documentation.

---

### L2: Specification

#### README structure

```markdown
# TramAI — Type-safe AI for the JVM

[One-liner tagline]
[Build status] [License] [Latest version]

## Example: Customer Support Agent in 20 lines

```kotlin
// Full working example (see below)
```

## Why TramAI?

// 3-bullet comparison with Spring AI / LangChain4j

## Quick start

// dependency + minimal setup

## Modules

// Link to docs/module-guide.md

## Documentation

// Links
```

#### The example

```kotlin
@AiService
interface SupportAgent {
    @System("You are a support agent. Be concise.")
    @User("Customer issue: {message}")
    @Operation(model = "gemma4:e2b", tools = ["lookupOrder"])  // prompt="" is equivalent during migration
    suspend fun handle(message: String): Response
}

data class Response(
    @AiDescription("Answer to the customer")
    val answer: String,
    @AiDescription("Action taken, if any")
    val action: String? = null
)

@Service
class OrderTool {
    @AiTool(description = "Look up an order by ID")
    fun lookupOrder(@AiDescription("Order UUID") id: String): String =
        "Order $id: shipped on 2026-04-15"
}

// Bootstrap
@SpringBootApplication
class App : TramaiAutoConfiguration()

fun main() {
    val ctx = runApplication<App>()
    val agent = ctx.getBean(SupportAgent::class.java)
    val result = agent.handle("Where is my order #ORD-42?")
    println(result.answer)  // "Your order ORD-42 was shipped on April 15."
}
```

This example demonstrates:
- `@AiService` + `@System` + `@User` + `@Operation` (annotation model)
- `@AiTool` (tool calling)
- `@AiDescription` (structured output)
- `tramai-ollama` (local AI, no API key)
- `tramai-spring` (Spring Boot integration)
- `suspend` function (coroutine support)

#### How to run

```bash
# Prerequisites
brew install ollama  # or: apt install ollama
ollama pull gemma4:e2b

# Run
git clone https://github.com/<owner>/tramai
cd tramai/examples/support-agent
./gradlew bootRun
```

---

## 5. Implementation Order & Dependencies

### Dependency graph

```
Phase 1
├── P0: Multi-message annotations
│   ├── tramai-core: @System, @User annotations (no deps)
│   └── tramai-engine: proxy dispatch + message construction (depends on core)
│
├── P0: Module decision tree
│   └── docs/module-guide.md (no code deps)
│
├── P1: README example
│   └── examples/support-agent/ (depends on all of the above)
│
└── Gate: example must compile and run
```

### Parallel workstreams

| Workstream | Tasks | Can start when | Duration |
|------------|-------|----------------|----------|
| A: Annotations | `@System.kt`, `@User.kt`, engine dispatch update | Immediately | 3-5 days |
| B: Docs | `module-guide.md`, updated `README.md` | Immediately | 2-3 days |
| C: Example | `examples/support-agent/` | After A + B | 2-3 days |

Workstreams A and B are independent and can run in parallel. Workstream C depends on both.

---

## 6. Acceptance Criteria

Every Phase 1 change must pass these gates before the release branch is created:

### Multi-message annotations

- [ ] `@System` annotation compiles and is retained at runtime
- [ ] `@User` annotation compiles and is retained at runtime
- [ ] Engine correctly constructs multi-message `List<Message>` from annotations
- [ ] `@Operation.prompt` backward compat: existing code works unchanged
- [ ] `@Operation.prompt` logs a warning when `@User` is also present
- [ ] Default system message constructed when no `@System` present
- [ ] Unknown parameter interpolation throws `TramaiException` at proxy creation
- [ ] Anthropic provider maps system messages to top-level `system` field
- [ ] All three providers (OpenAI, Anthropic, Ollama) pass with multi-message input
- [ ] Test coverage: all cases from specification §2 L3 table

### Module decision tree

- [ ] `docs/module-guide.md` exists and answers "which modules do I need?" for every persona
- [ ] Every module listed with one-liner, dependency, and when-to-use
- [ ] Decision tree covers: Spring / standalone / test / orchestration / observability / platform
- [ ] Quick-start recipes for 3 most common stacks
- [ ] Links from `README.md` to `docs/module-guide.md`

### README example

- [ ] Example compiles with `./gradlew :examples:support-agent:build`
- [ ] Example runs with `ollama pull gemma4:e2b` (no API key required)
- [ ] Example demonstrates: `@AiService`, `@System`, `@User`, `@Operation`, `@AiTool`, `@AiDescription`
- [ ] Example output is deterministic for a given input
- [ ] `README.md` links to example directory

### Release gate

- [ ] All three changes merged to `main`
- [ ] `./gradlew check` passes on all modules
- [ ] Example compiles and runs
- [ ] Tag `v0.3.0` created

---

## 7. Appendix: Reference Implementations

### 7.1 Spring AI annotations (for comparison)

```java
// Spring AI's existing annotation model
@System("You are a helpful assistant")
@User("${input}")
String chat(String input);
```

TramAI's approach differs by:
- Being Kotlin-native (no `${}` SpEL, uses `{}` like Kotlin string templates)
- Supporting tool references directly in `@Operation.tools`
- Making structured output the default contract for non-String returns

### 7.2 LangChain4j annotations (for comparison)

```java
// LangChain4j's annotation model
@SystemMessage("You are a helpful assistant")
@UserMessage("{{it}}")
String chat(String userMessage);
```

LangChain4j uses `{{it}}` for single-parameter shorthand. TramAI uses named parameters `{name}` to match Kotlin conventions. LangChain4j does not support `@Operation`-style tool routing, timeout, retry, or cache configuration — those are set globally or via builder.

---

> **Next action:** Multi-agent review. See `docs/roadmap/review-workflow.md`.
>
> **Review status:** ⏳ Awaiting multi-agent review
