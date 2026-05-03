# TASK-039C: Audit Log

- Status: planned
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Parent task: [TASK-039](../tasks/task-039.md)
- Last updated: 2026-05-03

## Purpose

Build an append-only audit log that records all security-relevant operations on the platform. Each entry identifies the actor, resource, action, and timestamp, and the log supports configurable retention and a query API for downstream consumption.

## Scope

- Append-only audit storage model (no updates, no deletes)
- Actor/resource/action schema: actor_id, actor_type, resource_id, resource_type, action, outcome, metadata JSON, timestamp
- Retention configuration with age-based or count-based pruning
- Query API with filters for actor, resource, action, time range, and pagination

## Exit Criteria

- [ ] Audit records are append-only — existing records cannot be modified or deleted via the application API
- [ ] Every mutating operation (run create, key rotate, workflow deploy) produces an audit entry
- [ ] Retention policy can be configured per environment (e.g., 90 days in prod)
- [ ] Query API returns paginated results filtered by actor, resource, action, or time range
- [ ] Tests prove immutability, correct actor attribution, and retention enforcement
