# Platform Operations

`tramai-platform` is the multi-tenant, API-key-protected operational layer above the workflow server.

It is not the entry point for a normal library consumer. It is the repo's current answer to teams that need tenancy, scoped access, plugins, and audit around workflow execution.

## What The Platform Adds

Current code adds:

- team/project scoping
- API key authentication
- scope-based authorization
- per-key token-bucket rate limiting
- audit logging in JDBC
- plugin discovery and lifecycle
- webhook adaptation through plugin-registered sources

The platform reuses the same workflow names and run model, but wraps them in a tenant-aware API surface.

## Current API Surface

### Workflow endpoints

| Method | Path | Scope required |
| --- | --- | --- |
| `POST` | `/workflows/{name}/run` | `run` |
| `GET` | `/workflows/{name}/runs` | `read` |
| `GET` | `/workflows/{name}/runs/{id}` | `read` |
| `POST` | `/webhooks/{name}` | webhook path, no API key header |

### API key endpoints

| Method | Path | Scope required |
| --- | --- | --- |
| `POST` | `/api-keys` | `admin` |
| `GET` | `/api-keys` | `admin` |
| `DELETE` | `/api-keys/{id}` | `admin` |

### Audit and plugin endpoints

| Method | Path | Scope required |
| --- | --- | --- |
| `GET` | `/audit-log` | `admin` |
| `GET` | `/plugins` | `admin` |
| `POST` | `/plugins/install` | `admin` |
| `POST` | `/plugins/{id}/enable` | `admin` |
| `POST` | `/plugins/{id}/disable` | `admin` |

## Authentication Model

Platform requests use:

`X-API-Key: <raw key>`

Current scope values are:

- `run`
- `read`
- `admin`

Current authorization rule:

- `admin` grants every scope

## Team And Project Boundary

The platform enforces workflow-run isolation by `(teamId, projectId)`.

Important current limitation:

- there are repositories and tables for teams and projects
- but there are not yet public HTTP endpoints in this module to create teams and projects

Today that means tenant bootstrap is expected to happen through application code, seed data, migrations, or direct repository usage.

That is visible in the tests and should be treated as real current behavior, not missing documentation.

## API Keys

Current implementation details:

- keys are stored as BCrypt hashes
- the raw key is only returned at creation time
- each key belongs to exactly one team and one project
- each key has burst capacity and refill rate controls
- last-used timestamps are tracked
- revocation is explicit

Example creation payload:

```json
{
  "teamId": "team-a",
  "projectId": "project-a",
  "name": "ci-runner",
  "scopes": ["run", "read"],
  "burstCapacity": 20,
  "refillTokensPerSecond": 5.0
}
```

## Rate Limiting

Rate limiting is enforced per API key.

When a request is rejected, the platform returns `429` plus:

- `Retry-After`
- `X-RateLimit-Limit`
- `X-RateLimit-Remaining`
- `X-RateLimit-Reset`

This behavior is covered by tests and should be treated as contract-level.

## Audit Log

The platform persists audit entries in JDBC.

Current stored fields include:

- timestamp
- actor id
- action
- resource type
- resource id
- team id
- metadata JSON

The current read endpoint is:

`GET /audit-log?action=workflow.start`

The server module also has a separate in-memory `/audit` endpoint. Do not confuse the two:

- `tramai-server` -> `/audit`
- `tramai-platform` -> `/audit-log`

## Plugin Model

Plugins are runtime JARs that implement `TramaiPlugin`.

Current plugin capabilities:

- external step executors
- webhook adapters
- dashboard extensions

The plugin manager:

- scans a configured directory for JARs
- uses `ServiceLoader`
- persists plugin state in JDBC
- can enable or disable discovered plugins

Current property:

```yaml
tramai:
  platform:
    plugins:
      dir: ${java.io.tmpdir}/tramai-platform-plugins
```

Important operational fact:

- dropping a JAR in the directory is not the whole story
- enable/disable state is also persisted in `platform_plugin`

## Webhook Adapters

The platform webhook endpoint adds a source dimension:

`POST /webhooks/{name}?teamId=...&projectId=...&source=demo.webhook`

Current behavior:

- the webhook source id resolves through the plugin webhook adapter registry
- the adapter transforms the payload into workflow initial state
- the resulting workflow run is recorded as actor `webhook:{source}`

## Database Footprint

The platform migration currently creates:

- `platform_team`
- `platform_project`
- `platform_api_key`
- `platform_audit_log`
- `platform_plugin`

That means the platform should be treated as JDBC-backed from the beginning.

## Dashboard Relationship

The dashboard module is separate from the platform module.

Current reality in the repo:

- the dashboard can render against server-style endpoints
- the platform exposes extra admin surfaces for API keys, audit logs, and plugins
- the dashboard auth bootstrap detects the presence of the platform API key authenticator and reports `apikey` as auth provider

## What The Platform Does Not Yet Do

The specs discuss more than the current runtime implements. Today the platform does not yet give you a full public surface for:

- team creation and user/RBAC management
- encrypted secret storage APIs
- archival/export management
- full workflow version management APIs

Document those as roadmap/platform scope, not as implemented user-facing behavior.

## Related Pages

- [Workflow Server](./server.md)
- [MCP Integration](./mcp.md)
- [SPEC-017: TramAI Platform](../specs/spec-017-platform.md)
