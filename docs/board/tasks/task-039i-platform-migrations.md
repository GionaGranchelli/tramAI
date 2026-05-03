# TASK-039I: Platform Migrations

- Status: planned
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Parent task: [TASK-039](../tasks/task-039.md)
- Last updated: 2026-05-03

## Purpose

Create and verify all database migrations required by the platform feature set (TASK-039A through TASK-039H). Each migration is backward-compatible with the existing schema and includes rollback support plus automated migration tests.

## Scope

- Tenant columns: add tenant_id and related columns to existing workflow, run, and worker tables
- API key tables: create api_keys and key_scopes tables with hashed_key, prefix, tenant_id, scopes, status, expires_at
- Audit tables: create audit_log table with actor_id, actor_type, resource_id, resource_type, action, outcome, metadata, timestamp
- Backward-compatible migration tests: verify that existing data survives the migration and that rollback restores the prior schema

## Exit Criteria

- [ ] Tenant migration adds nullable tenant_id columns with a default (null for backwards compat on single-tenant deployments)
- [ ] API key migration creates api_keys table with indexes on prefix and tenant_id
- [ ] Audit migration creates audit_log table with indexes on actor_id and resource_id
- [ ] Migration tests prove: forward migration succeeds, rollback restores original schema, existing data is preserved
- [ ] Down migrations are provided for all three changesets
