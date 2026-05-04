# Orchestrator Vision: TramAI as a Fully-Fledged Orchestration Platform

- Status: planned
- Owner: maintainer
- Last updated: 2026-05-04

## Why This Exists

TramAI is already strong at single typed AI-backed operations via `@AiService`, and
the `tramai-orchestration` module provides a workflow DSL for composing those
operations. However, TramAI remains a **JVM library** — it cannot schedule
workflows, expose them over HTTP, spawn external agents, or run across multiple
machines without application code wrapping it.

This document defines a multi-phase effort to transform TramAI from a workflow
library into a **full-featured orchestration platform** that competes with
Temporal, n8n, and Airflow in the JVM/AI space — while keeping its core
identity: type-safe, testable, observable, and framework-agnostic.

## Design Tenets

1. **Every new module is optional.** The core `tramai-orchestration` workflow DSL
   must remain usable standalone. The new modules add capabilities on top.

2. **Workflows remain typed and testable.** No step should require raw prompt
   strings or untyped state maps. The typed state pattern from SPEC-012 is the
   foundation; all new step types extend it.

3. **External integrations are explicit step types, not magic.** Hermes, HTTP,
   shell, MCP — each is a named step in the DSL, not a hidden side effect.

4. **MCP is a first-class protocol.** Every TramAI workflow is automatically
   exposable as an MCP server so that Hermes, Codex CLI, Copilot CLI, and Gemini
   CLI can call it directly.

5. **Observability is mandatory at the hook level, optional at the OTel dependency level.**
   Every new module must emit stable TramAI observer events for externally visible
   lifecycle transitions. The `tramai-observability` module maps those hooks to OpenTelemetry
   spans, events, and metrics when present on the classpath.

## Current Gaps

| Gap | Why It Matters | Module |
|---|---|---|
| No scheduling/cron | Cannot trigger workflows on a timer | `tramai-scheduler` |
| No HTTP API | No way for external agents to start/resume workflows | `tramai-server` |
| No MCP server | Agents cannot discover or call TramAI workflows | `tramai-server` |
| No webhook receiver | Cannot react to GitHub, email, or Slack events | `tramai-server` |
| No agent spawning | Workflow steps cannot call Hermes, shell, or HTTP | `tramai-agent` |
| No per-step timeout | A hanging step stalls the entire workflow | `tramai-orchestration` |
| No per-step retry | Steps fail without the option to retry with backoff | `tramai-orchestration` |
| No distributed workers | Single-JVM only, no horizontal scaling | `tramai-distributed` |

## Module Architecture (Post-Orchestrator)

```
┌──────────────────────────────────────────────────────┐
│                   tramai-platform                      │  ← optional platform
│  (Admin UI, plugin system, multi-tenancy, auth)        │     on top of server
├──────────────────────────────────────────────────────┤
│      ┌─────────────────────┐                          │
│      │  tramai-dashboard    │  ← optional (Vue 3 +    │
│      │  (SPA → JAR → serve) │     Vite, Spring Boot   │
│      └─────────────────────┘     Admin pattern)       │
├──────────────────────────────────────────────────────┤
│                   tramai-server                        │  ← optional service
│  (REST API, MCP server, webhooks, SSE)                 │     layer
│  depends on tramai-dashboard as optional()             │
├──────────────────────────────────────────────────────┤
│            ┌──────────────┐  ┌──────────────────┐     │
│            │tramai-agent  │  │tramai-scheduler   │     │  ← optional modules
│            │(HTTP, shell, │  │(cron, delay,      │     │
│            │ MCP, Hermes) │  │ calendar)          │     │
│            └──────────────┘  └──────────────────┘     │
├──────────────────────────────────────────────────────┤
│              tramai-orchestration                      │  ← core workflow DSL
│  (aiStep, parallelStep, branchStep, gateStep,          │
│   localStep + NEW: timeout, retry)                     │
├──────────────────────────────────────────────────────┤
│    tramai-engine  │  tramai-structured  │  tramai-core  │  ← existing core
└──────────────────────────────────────────────────────┘
```

## Phases Overview

| Phase | Spec | Module | Timeline |
|---|---|---|---|
| **Phase 6** | SPEC-013 | tramai-scheduler | 1-2 weeks |
| **Phase 7** | SPEC-014 | tramai-server | 2-3 weeks |
| **Phase 8** | SPEC-015 | tramai-agent | 2-3 weeks |
| **Phase 9** | SPEC-016 | tramai-distributed | 3-4 weeks |
| **Phase 10** | SPEC-017 | tramai-platform | 4-6 weeks |

## Product Positioning

TramAI Orchestrator occupies a unique niche:

| | Temporal | n8n | Airflow | TramAI Orchestrator |
|---|---|---|---|---|
| **Runtime** | Any (gRPC) | Node.js | Python | **JVM (Kotlin/Java)** |
| **AI-native** | ❌ No | ❌ No | ❌ No | **✅ Type-safe @AiService** |
| **Structured output** | ❌ No | ❌ No | ❌ No | **✅ Built-in** |
| **MCP protocol** | ❌ No | ❌ No | ❌ No | **✅ First-class** |
| **Checkpoint/resume** | ✅ Best-in-class | ❌ No | ✅ Yes | **✅ Built-in** |
| **Visual builder** | ❌ No | ✅ Yes | ✅ Yes | **✅ Phase 10** |
| **OpenTelemetry** | ❌ No | ❌ No | ❌ No | **✅ Built-in** |

**Target customer:** Enterprise Spring Boot teams that want AI orchestration
without leaving the JVM ecosystem.

## Related Documents

- [SPEC-012: Orchestration and Coordination](../specs/spec-012-orchestration-and-coordination.md)
- [Roadmap Summary](../roadmap.md)
- [Current Limitations](../reference/limitations.md)
- [Orchestrator Board](../board/orchestrator-board.md)
