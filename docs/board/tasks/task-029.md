# TASK-029: Implement REST API for Workflow Management

- Status: done
- Priority: high
- Primary spec: [SPEC-014](../../specs/spec-014-server.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Expose TramAI workflows over HTTP so external systems can start, resume, list,
and inspect workflow executions.

## Scope

- `POST /workflows/{name}/run` — start a workflow with JSON state
- `POST /workflows/{name}/resume` — resume by workflow ID
- `GET /workflows/{name}/runs` — list runs with status and pagination
- `GET /workflows/{name}/runs/{id}` — inspect a single run
- `DELETE /workflows/{name}/runs/{id}` — cancel a running workflow
- Workflow registry that maps names to typed workflow definitions
- JSON Schema generation from typed state for request validation
- RFC 7807 problem details for error responses
- OpenAPI documentation generated from workflow definitions

## Exit Criteria

- [x] `POST /workflows/invoice/run` with valid JSON starts the workflow
- [x] `POST /workflows/invoice/run` with invalid JSON returns 400 + problem detail
- [x] `GET /workflows/invoice/runs` returns paginated list
- [x] `DELETE /workflows/invoice/runs/{id}` cancels a running workflow
- [x] Two different workflow types are registered and accessible
- [x] OpenAPI spec is auto-generated and accessible at `/openapi.json`
- [x] Idempotency-Key header returns same run ID on duplicate requests
