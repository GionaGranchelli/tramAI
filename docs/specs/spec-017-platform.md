# SPEC-017: TramAI Platform (Dashboard, Plugins, Multi-Tenancy)

- Status: proposed
- Owner: maintainer
- Last updated: 2026-05-03
- Related roadmap milestone: Phase 10 — Platform
- Related ADRs:
- Related docs: [Orchestrator Vision](../architecture/orchestrator-vision.md)

## Problem

With SPEC-013 through SPEC-016 implemented, TramAI has a complete orchestration
engine. The platform adds the human-facing layer: dashboard, plugin system,
multi-tenancy, and operational tooling.

## Scope

- Admin dashboard (Vue.js or server-rendered)
- Plugin system (compile-time DSL plugins + runtime platform plugins)
- Multi-tenancy (teams, projects, RBAC)
- Audit logging (first-class citizen, append-only)
- API keys (hashed, scoped, rate-limited, with rotation)
- Secret management (encrypted at rest in database)
- Run retention and archival (configurable, S3-compatible)
- Worker management (registration, heartbeat, draining, pools)
- Workflow versioning (SemVer, simple)
- Environment configuration (dev/staging/prod)
- Workflow templates and version management

## Non-Goals

- Visual workflow editor (workflows remain code-defined and CI-deployed)

## Functional Requirements

### 1. Plugin System

#### Compile-Time DSL Plugins

Typed Kotlin extension functions in normal library artifacts:

```kotlin
// DSL artifact (compile dependency)
fun <S : Any> WorkflowBuilder<S>.slackMessageStep(
    name: String,
    configure: SlackMessageStepBuilder<S>.() -> Unit
) {
    externalStep(name, SlackMessageStepSpec.from(configure))
}

// Runtime artifact (runtime dependency)
class SlackMessageExecutor : ExternalStepExecutor<SlackMessageSpec> {
    override suspend fun execute(spec: SlackMessageSpec, ctx: StepContext): StepResult
}
```

- DSL extension functions require compile-time dependency (`implementation(...)`)
- Runtime executors can be in separate artifact (`runtimeOnly(...)`)
- Step spec is serializable and included in workflow definition digest
- Missing runtime executor fails loudly at startup with plugin ID and step name
- DSL artifact must NOT depend on tramai-platform

#### Runtime Platform Plugins

Discovered by JAR scanning at startup, register named capabilities:

| Capability | Interface | Example |
|------------|-----------|---------|
| Step executors | `ExternalStepExecutorFactory` | `slack.message` executor |
| Webhook adapters | `WebhookAdapterFactory` | GitHub PR → workflow state |
| Dashboard panels | `DashboardExtension` | Custom dashboard tab |
| Secret resolvers | `SecretResolver` | Vault-backed secrets |

- Runtime plugins register factories by stable type IDs (e.g., `slack.message`)
- Cannot add typed DSL functions to already-compiled workflow code
- Generic runtime-configured steps use `pluginStep(type = "slack.message") { config(...) }`
- Schema validation at build/startup via plugin-provided schema
- Lifecycle: install (drop JAR), enable/disable via API, update (replace JAR)

### 2. Multi-Tenancy

#### Data Model

```
Team ──┬── Project ──┬── Workflow definitions
       │              ├── Workflow runs
       │              ├── API keys
       │              └── Schedules
       ├── Users (roles: admin, developer, viewer)
       ├── Audit log
       └── Configuration (retention, quotas)
```

#### DB Isolation Strategy

Recommended approach for v1: **Row-level security (RLS) with a single database.**

All tables include a `team_id` column. Every query is scoped by `team_id`.
The JDBC implementation enforces `WHERE team_id = ?` on all reads/writes.
Cross-tenant access attempts are tested explicitly (negative tests).

For customers requiring stronger isolation: separate PostgreSQL schemas per team
(optional, enabled per tenant). Connection pooling at the app level.

#### RBAC

| Role | Permissions |
|------|-------------|
| admin | Full access: workflows, runs, API keys, team config, audit log |
| developer | Create/run workflows, view runs, view own API keys |
| viewer | View workflows and runs, no write access |

Roles are per-team. A user can belong to multiple teams with different roles.

### 3. Audit Logging (First-Class Citizen)

- Append-only: records are written once and never modified
- Each record includes: timestamp, actor (user/API key/system), action,
  resource type, resource ID, team_id, status, metadata (JSON)
- Actions logged: workflow start/cancel, API key create/revoke, webhook received,
  schedule create/disable, configuration change, failed auth attempt
- Query API: filter by team, actor, action, resource, time range
- Retention: configurable per team (default 90 days)
- Archival: compressed export to object storage before deletion
- Immutable: even admins cannot delete audit records (only archive + purge)

### 4. API Keys

- Stored as salted hashes only (bcrypt or Argon2)
- Shown once at creation, never retrievable in plaintext
- Prefix identifier for lookup (e.g., `tmr_abc123...`)
- Scopes: `run` (start workflows), `read` (view runs), `admin` (full access)
- Rate limits: per-key burst and refill (configurable)
- Last-used timestamp tracked
- Rotation: keys can be rotated (new key issued, old key expires)
- Expiration: configurable TTL for keys
- Revocation: immediate, by admin or automated policy
- Separate key types: machine tokens (long-lived) vs user tokens (session-bound)

### 5. Secret Management

- Secrets (API keys, tokens, passwords) stored encrypted at rest in the same database
- Encryption: AES-256-GCM per secret value
- Master key in environment variable or external KMS (HashiCorp Vault, AWS KMS)
- Column-level encryption — only secret columns are encrypted, not the whole row
- Secrets never appear in logs, observer events, or API responses
- Secret references in workflow state use `{secret: path/to/key}` syntax,
  resolved at execution time

### 6. Run Retention & Archival

- **Storage:** active and recently completed runs in primary database
- **Retention:** configurable per team/project (default: 90 days successful, 180 days failed)
- **Archival format:** compressed NDJSON per workflow/date partition → S3-compatible storage
- **Archive contents:** run metadata, final status, timestamps, input/output metadata,
  error summaries, step summaries (large payloads referenced, not duplicated)
- **Archive index:** lookup by known run ID, browse by workflow name/date/status
- **No arbitrary historical search in v1** — archives are for compliance and debugging
- **Deletion:** archive first, verify, then delete from primary store
- **Hard deletion from archives:** separate max-retention policy, with explicit tombstones

### 7. Worker Management

- **Registration:** workers register on startup with generated worker ID, version,
  pool name, and capability labels (e.g., `pool=critical`, `gpu=true`, `region=eu`)
- **Heartbeats:** periodic update of `last_seen_at`, active run count, drain flag
- **Draining:** cooperative — stop accepting new work, finish in-flight (up to timeout),
  then unregister
- **Failover detection:** if `last_seen_at` > configured timeout, mark worker lost,
  release leases for retry
- **Capability labels:** simple key/value, exact match in v1
- **Pool targeting:** workflows target a named pool, optionally with required labels
- **No auto-scaling in v1** — workers are manually provisioned

### 8. Workflow Versioning

- SemVer on workflow definitions (`major.minor.patch`)
- Breaking changes increment major version
- In-flight runs complete on the version they started on
- New runs use the latest registered version
- Simple declaration: `workflow<State>("name", version = "1.0.0") { ... }`
- Run history includes the workflow version that produced it
- API responses include workflow version

### 9. Environment Configuration

- `TRAMAI_ENV=development` or `production` (or `staging`)
- Development mode: in-memory stores, no auth, verbose logging
- Production mode: PostgreSQL stores, auth required, redaction on
- Environment label on every run for debugging
- Configurable per module: `tramai.server.environment`, `tramai.scheduler.environment`

### 10. Admin Dashboard (v1 Scope)

- Workflow list: all registered types with version, schedule, last run
- Run history: searchable table (status, date, version, worker)
- Run detail: step-by-step trace with timing, I/O, errors, redacted
- Worker list: registered workers, heartbeats, lease counts, pool
- Schedule list: upcoming ticks, missed ticks, misfire events
- SSE live updates for running workflows
- API key management: create, list, revoke
- Audit log viewer: filterable by actor, action, time range
- **No visual workflow editor**

### 11. Versioning (Cross-Cutting)

| Artifact | Versioned? | Mechanism |
|----------|-----------|-----------|
| Workflow definitions | ✅ SemVer | `version` in workflow builder |
| Step schemas | ✅ Via definition digest | SHA-256 of definition graph |
| Event payloads | ✅ Semantic version | `tramai.event.version` attribute |
| API payloads | ✅ URL prefix or header | `/v1/workflows/...` |
| Plugins | ✅ SemVer | Plugin JAR manifest |
| Stored run state | ✅ Via definition digest | Resuming checks digest match |
| WorkflowStore schema | ✅ Migrations | Flyway/Liquibase |

## Acceptance Criteria

- [ ] Compile-time DSL plugin provides typed extension function available only when artifact is on classpath
- [ ] Runtime plugin JAR in plugins directory registers named step executor without code changes
- [ ] Two teams have fully isolated workflows, runs, API keys, and audit logs
- [ ] Cross-tenant read attempt returns empty results (not 404 — avoids information leak)
- [ ] Audit log records every workflow start, API key action, and webhook receipt
- [ ] API key with "run" scope can start workflows but cannot list them
- [ ] API key is shown once at creation, stored as hash
- [ ] Secret values are encrypted in database, never in logs
- [ ] Run archival exports compressed NDJSON to configurable path; archived runs deletable from primary
- [ ] Worker registers with heartbeat; stale worker detected and leases released
- [ ] Workflow version 1.0.0 and 2.0.0 coexist; in-flight v1 runs complete on v1
- [ ] Dashboard shows run history, worker list, schedule list, audit log
- [ ] Redaction masks secrets in dashboard and observer events
