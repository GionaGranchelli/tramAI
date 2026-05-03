# TASK-039A: Platform Tenant Model

- Status: planned
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Parent task: [TASK-039](../tasks/task-039.md)
- Last updated: 2026-05-03

## Purpose

Define the core tenant data model and team/project schema that isolates workflows, runs, and configuration across organizational boundaries. Each tenant holds its own registry of workflows and all run data is scoped to the owning tenant.

## Scope

- Team and project schema with unique tenant identifiers
- Tenant-scoped workflow registry that filters all queries by tenant
- Tenant-aware run queries that prevent cross-tenant data leakage
- Cross-tenant isolation negative tests that assert 403/404 for unauthorized access

## Exit Criteria

- [ ] Team and project tables store tenant_id, name, metadata, and timestamps
- [ ] Workflow registry accepts an optional tenant_id and scopes all operations
- [ ] Run queries accept a tenant filter and return only matching rows
- [ ] Cross-tenant queries for resources in a different tenant return empty or 403
- [ ] Negative tests prove a tenant cannot access another tenant's workflows or runs
