# TASK-039: Implement Plugin System and Multi-Tenancy

- Status: done
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Enable third-party extensibility and multi-team isolation on the TramAI
platform.

## Scope

### Plugin System

- `TramaiPlugin` interface with step types, webhook adapters, dashboard tabs
- Plugin discovery: scan plugins directory for JARs with `TramaiPlugin` service
- Plugin lifecycle: install, enable, disable, update via API
- Step type factory: plugins register new `step { ... }` DSL functions
- Webhook adapter factory: plugins parse webhook payloads into workflow state
- Dashboard tab factory: plugins contribute UI elements

### Multi-Tenancy

- Team and project data model with database-level isolation
- API keys with scoped permissions (run, read, admin)
- Rate limiting per API key
- Audit logging for all workflow starts, webhook receipts, and API actions
- `team_id`/`project_id` columns added to checkpoint and lease store tables

## Exit Criteria

- [ ] A demo plugin JAR placed in the plugins directory adds a new step type
- [ ] Plugin step type is usable in workflow definitions without code changes
- [ ] Webhook adapter plugin transforms a GitHub webhook into workflow state
- [ ] Two teams have fully isolated workflows, runs, and audit logs
- [ ] API key with "run-only" scope can start workflows but not list them
- [ ] Rate-limited API key receives 429 after exceeding limits
- [ ] Audit log records actor, action, timestamp for every workflow start

## Implementation Summary

**Completed**: 2026-05-04

### Module
New `tramai-platform` module with Spring Boot + Flyway + JDBC.

### Files (26 files, +3829 lines)
| File | Lines | Purpose |
|---|---|---|
| `Security.kt` | 242 | BCrypt API key hashing, time-aware token bucket rate limiter, scopes |
| `JdbcRepositories.kt` | 442 | Team/Project/API Key/Audit log/Plugin state repositories |
| `PlatformController.kt` | 267 | REST API: plugins, API keys, audit log, tenant-scoped workflows |
| `PlatformWorkflowService.kt` | 318 | Tenant-scoped workflow execution, webhook handling |
| `PlatformModels.kt` | 120 | Team, Project, ApiKey, AuditEntry, PluginState data classes |
| `PluginManager.kt` | 174 | JAR scanning, ServiceLoader, lifecycle (install/enable/disable) |
| `PlatformConfiguration.kt` | 160 | Spring auto-configuration, beans |
| `PluginWorkflowStartupValidator.kt` | 35 | Startup validation of plugin executor registration |
| `V1__platform.sql` | 62 | Flyway migration (team, project, api_key, audit_log, plugin_state tables) |
| `Workflow.kt` | +249 | pluginStep() DSL, ExternalStepExecutorRegistry injection |
| Tests (6 files) | 726 | Plugin discovery, step execution, webhook adapt, team isolation, scope enforcement, rate limiting, audit log, BCrypt verification |

### Review Cycle
- Copilot implemented (16m 33s, 8.3M tokens, +2427 lines)
- Codex reviewed: FAIL (4 findings: unsalted SHA-256 hash, broken rate limiter, H2-specific SQL, global mutable state)
- Copilot fixed all 4 (10m 59s, 4.1M tokens, +589/-114 lines)
- Full suite: 65 tasks, BUILD SUCCESSFUL
