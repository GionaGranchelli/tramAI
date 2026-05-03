# TASK-039: Implement Plugin System and Multi-Tenancy

- Status: planned
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
