# Module: `tramai-platform`

> **One-liner:** Multi-tenant operational layer above the workflow server with API key authentication, token-bucket rate limiting, JDBC audit logging, and a runtime plugin system.

---

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Platform services: API-key auth/rate limiting, audit log, plugin manager, workflow services — the control plane layered over `tramai-server`/`tramai-orchestration`.

### Public entry points

- `ApiKeyService` / `ApiKeyAuthenticator` / `ApiKeyRateLimiter` / `ApiKeyRepository` — API-key auth and rate limiting
- `AuditLogService` / `AuditLogRepository` / `AuditLogEntry` — audit
- `PlatformConfiguration`, `PlatformController`, `PlatformWorkflowService`, `PluginManager`

Verify against `tramai-platform/api/tramai-platform.api`.

### Internal extension points

- Repository implementations (JDBC); plugin lifecycle internals behind the public plugin manager

### Significant dependencies

- `api(tramai-orchestration)`; `implementation(tramai-server)`; Spring JDBC/web/security-crypto, Flyway, Jackson, coroutines (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; plugin lifecycle via `PluginManager`

### Thread-safety and concurrency

- Spring singletons; controllers must be safe for concurrent HTTP requests

### Failure semantics

- Auth/rate-limit failures surface as typed HTTP responses; audit failures are not silent

### Contract tests / TCKs

- `PlatformControllerTest`, `SecurityTest`, `PluginStateRepositoryTest`, `PluginWorkflowStartupValidatorTest`

### Do not

- Do not add provider adapters here — platform is a control plane

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — operations-observability layer

## L1: Quick Start (30-second read)

### What

`tramai-platform` is a **multi-tenant HTTP operational layer** that wraps the Tramai workflow server. It adds team/project scoping, API key authentication and authorization, per-key token-bucket rate limiting, append-only JDBC audit logging, runtime plugin discovery and lifecycle management, and webhook adaptation — all behind a Spring Boot REST API.

The platform **reuses** the same workflow names and run model from `tramai-server`, but wraps them in a tenant-aware API surface authenticated via `X-API-Key` headers.

### Why

A single `tramai-server` instance handles one namespace. Real-world deployments serve multiple teams that each need isolated workflows, runs, API keys, and configuration. Without a platform layer, you hand-roll tenancy, authentication, rate limiting, and audit. `tramai-platform` provides:

- **Multi-tenancy** — teams and projects with row-level `(team_id, project_id)` isolation enforced on every query
- **Authentication** — API keys stored as BCrypt hashes, scoped to `run` / `read` / `admin`, shown once at creation
- **Rate limiting** — per-key token bucket with configurable burst capacity and refill rate, returned as standard `X-RateLimit-*` headers
- **Audit trail** — append-only JDBC audit log recording every workflow start, API key action, and webhook receipt
- **Plugin extensibility** — runtime JARs implementing `TramaiPlugin` contribute step executors, webhook adapters, and dashboard extensions without code changes
- **Webhook reception** — plugin-registered webhook adapters transform external payloads (GitHub, etc.) into workflow initial state

### When to use

Use `tramai-platform` when you need to operate Tramai as a **multi-tenant service**:

- Serving **multiple teams or projects** with isolated workflow runs and API keys
- Requiring **API-key-based authentication** with scoped access control
- Enforcing **per-key rate limits** with standard HTTP 429 responses
- Maintaining an **audit trail** of all operational actions
- Extending at **runtime** with plugin JARs that add step executors or webhook adapters

Do **not** use it for library-embedded usage (a single process calling workflows directly) — `tramai-engine` + `tramai-orchestration` is the right choice for that. Reach for the platform when you need to expose Tramai as a **network service** with operational guardrails.

### How to add

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-platform")  // version from the TramAI BOM
}
```

**Bill of Materials:**

```kotlin
val tramaiVersion: String by project
    implementation(platform("dev.tramai:tramai-bom:$tramaiVersion"))
implementation("dev.tramai:tramai-platform")
```

`tramai-platform` is a Spring Boot application (`@SpringBootApplication` on `TramaiPlatformApplication`). It auto-configures all beans via `PlatformConfiguration` — see the [configuration reference](../reference/configuration.md) for property overrides.

### Where to go next

| Topic | Link |
|-------|------|
| Platform operations guide | `docs/guides/platform.md` |
| Full platform spec | `docs/specs/spec-017-platform.md` |
| Workflow server module | `docs/modules/tramai-server.md` |
| Orchestration module | `docs/modules/tramai-orchestration.md` |

---

## L2: Usage Guide (5-minute read)

### Platform setup

`tramai-platform` expects a `javax.sql.DataSource` on the classpath (e.g., PostgreSQL via Spring Boot auto-configuration). On startup, `PlatformConfiguration` creates every bean needed:

| Bean | Source | Purpose |
|------|--------|---------|
| `WorkflowRegistry` | `PlatformConfiguration` | Collects workflow registrations from the classpath |
| `PlatformWorkflowService` | `PlatformConfiguration` | Tenant-aware workflow execution and querying |
| `ApiKeyService` | `PlatformConfiguration` | API key creation, listing, and revocation |
| `ApiKeyAuthenticator` | `PlatformConfiguration` | API key verification and scope checks |
| `ApiKeyRateLimiter` | `PlatformConfiguration` | Per-key token-bucket rate limiting |
| `TeamProjectRegistry` | `PlatformConfiguration` | Team/project existence validation |
| `TenantWorkflowRunStores` | `PlatformConfiguration` | Per-(team,project) run store isolation |
| `PluginManager` | `PlatformConfiguration` | Plugin JAR scanning and lifecycle |
| `AuditLogService` | `PlatformConfiguration` | Append-only audit log writes |
| `WebhookAdapterRegistry` | `PlatformConfiguration` | Plugin-registered webhook adapter resolution |

The database tables required are:

- `platform_team` — team records
- `platform_project` — project records scoped to a team
- `platform_api_key` — hashed API keys with scopes and rate limit config
- `platform_audit_log` — append-only audit entries
- `platform_plugin` — plugin state (enabled/disabled/error)

**Important:** There are not yet public HTTP endpoints to create teams and projects. Tenant bootstrap is expected to happen through application code, seed data, migrations, or direct repository usage.

### REST API

All authenticated endpoints use the `X-API-Key` header. Three scope levels control authorization:

| Scope | Grants |
|-------|--------|
| `run` | Start workflows |
| `read` | List and view workflow runs |
| `admin` | Full access: manage API keys, view audit log, manage plugins |

The `admin` scope **grants** every scope. See `ApiKeyScope.grants()` in code.

#### Workflow endpoints

| Method | Path | Scope required | Description |
|--------|------|---------------|-------------|
| `POST` | `/workflows/{name}/run` | `run` | Start a workflow run (optionally with `Idempotency-Key`) |
| `GET` | `/workflows/{name}/runs` | `read` | List runs (pagination via `offset` and `limit`) |
| `GET` | `/workflows/{name}/runs/{id}` | `read` | Get run detail with step history |

Rate limit headers are returned on every workflow run response:

- `X-RateLimit-Limit` — burst capacity of the key
- `X-RateLimit-Remaining` — tokens remaining in the bucket
- `X-RateLimit-Reset` — epoch seconds when the bucket resets

When a rate limit is exceeded, the endpoint returns **429 Too Many Requests** with `Retry-After` plus the same `X-RateLimit-*` headers.

**Idempotency:** When `Idempotency-Key` is provided, duplicate workflow start requests with the same key return the existing run response instead of creating a new run.

#### API key endpoints

| Method | Path | Scope required |
|--------|------|---------------|
| `POST` | `/api-keys` | `admin` |
| `GET` | `/api-keys` | `admin` |
| `DELETE` | `/api-keys/{id}` | `admin` |

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

Keys are returned with their raw value only at creation time. The raw value is prefixed `tmr_` for easy identification. Keys are stored as BCrypt hashes and cannot be retrieved in plaintext after creation.

#### Audit log endpoint

| Method | Path | Scope required |
|--------|------|---------------|
| `GET` | `/audit-log` | `admin` |

Optional query parameters: `team` (scoped to authenticated team), `action` (filter by action name, e.g., `workflow.start`, `api_key.create`).

#### Webhook endpoint

| Method | Path | Auth |
|--------|------|------|
| `POST` | `/webhooks/{name}` | No API key (uses query parameters) |

Query parameters: `teamId`, `projectId`, `source` (the registered webhook adapter source ID — e.g., `github.push`, `demo.webhook`).

The webhook adapter transforms the raw payload into workflow initial state. The resulting workflow run is recorded as actor `webhook:{sourceId}`.

#### Plugin management endpoints

| Method | Path | Scope required |
|--------|------|---------------|
| `GET` | `/plugins` | `admin` |
| `POST` | `/plugins/install` | `admin` |
| `POST` | `/plugins/{id}/enable` | `admin` |
| `POST` | `/plugins/{id}/disable` | `admin` |

`POST /plugins/install` accepts a JSON body with a `jarPath` field pointing to a `.jar` file on the filesystem. The plugin is copied into the managed plugin directory and discovered on the next refresh.

### API key management

The `ApiKeyService` handles creation, listing, and revocation:

- **Creation** — validates team/project existence, generates a `tmr_`-prefixed raw key, BCrypt-hashes it, stores the record, and returns the raw key exactly once. Validates that scopes are non-empty and rate-limit parameters are positive.
- **Listing** — returns all non-revoked keys for a (team, project) pair, ordered by creation date descending.
- **Revocation** — soft-deletes by setting `revoked_at`, immediately invalidating the key for authentication. Cannot be undone.
- **Last-used tracking** — every successful authentication updates `last_used_at` on the key record.

All API key mutations are recorded in the audit log with actor ID, action type (`api_key.create`, `api_key.revoke`), and metadata.

### Plugin system

Plugins are **runtime JARs** that implement the `TramaiPlugin` interface:

```kotlin
interface TramaiPlugin {
    val id: String
    val version: String
    fun stepExecutors(): List<ExternalStepExecutorFactory>
    fun webhookAdapters(): List<WebhookAdapterFactory>
    fun dashboardExtensions(): List<DashboardExtension>
}
```

The `PluginManager`:

1. Scans a configured directory (`tramai.platform.plugins.dir`, defaults to `${java.io.tmpdir}/tramai-platform-plugins`) for `.jar` files
2. Loads each JAR with a `URLClassLoader` and discovers `TramaiPlugin` implementations via `ServiceLoader`
3. Persists plugin state (version, JAR path, enabled/disabled, status) in the `platform_plugin` table
4. Registers step executors and webhook adapters from **enabled** plugins into their respective registries
5. On failure to load a JAR, logs the error and skips it (does not fail startup)

Plugin state is persisted independently of JAR presence — a plugin that was previously enabled stays enabled on restart if the JAR is found.

Lifecycle operations via the API:

- **Install** — copy a JAR into the managed directory and refresh
- **Enable** — set `enabled = true` in the database and refresh (loads executors/adapters)
- **Disable** — set `enabled = false` in the database and refresh (removes executors/adapters)

At startup, `PluginDiscoveryRunner` runs the refresh and then validates that all required external step types referenced by registered workflows have a corresponding executor. If not, `PluginWorkflowStartupValidationException` is thrown with details of which step types are missing.

#### Webhook adapters

The `WebhookAdapterRegistry` maps source IDs (e.g., `github.push`) to factory functions. When a webhook arrives at `POST /webhooks/{name}?source=github.push`, the registry creates an adapter instance that transforms the raw payload and headers into the workflow's initial state map.

```kotlin
fun interface WebhookAdapter {
    suspend fun adapt(
        payload: String,
        headers: Map<String, String>,
    ): Map<String, Any?>
}
```

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy

`tramai-platform` is designed as a **thin security and tenancy layer** over `tramai-server`'s workflow execution model. It deliberately decouples:

1. **Authentication** — who is making the request (`ApiKeyAuthenticator`)
2. **Authorization** — what they are allowed to do (`ApiKeyScope`)
3. **Rate limiting** — how much they can do (`ApiKeyRateLimiter`)
4. **Tenant isolation** — what data they can see (`team_id`, `project_id` filters)
5. **Plugin extensibility** — what capabilities are available at runtime (`PluginManager`)
6. **Audit** — what happened and who did it (`AuditLogService`)

This separation means each concern can be tested independently, swapped, or extended without changing the workflow execution core.

### Security layer

The security stack is applied per-request in `PlatformController` before any business logic:

```
Request → ApiKeyAuthenticator.authenticate(apiKey)
       → ApiKeyAuthenticator.requireScope(principal, scope)
       → ApiKeyRateLimiter.check(record)
       → PlatformWorkflowService
```

**Authentication (`ApiKeyAuthenticator`):**

The `X-API-Key` header is extracted and matched against stored BCrypt hashes. Lookup uses the first 16 characters of the raw key as a prefix index (`prefix` column on `platform_api_key`). This avoids full-table scans while keeping the prefix short enough to not leak the full key. Failed authentication throws `AuthenticationException` → HTTP 401.

**Authorization (`ApiKeyScope`):**

Three scopes with a hierarchical grant model:
- `READ` grants `READ`
- `RUN` grants `RUN`
- `ADMIN` grants `ADMIN`, `READ`, and `RUN`

Missing scope throws `AuthorizationException` → HTTP 403.

**Rate limiting (`ApiKeyRateLimiter`):**

Token-bucket algorithm per API key, maintained in a `ConcurrentHashMap` keyed by key ID:

- **Burst capacity** — maximum accumulated tokens (configurable per key, default 10)
- **Refill rate** — tokens per second (configurable per key, default 1.0)
- **State** — each bucket tracks `tokens` and `lastRefillNanos`; synchronized per key
- **Decision** — returns whether allowed, remaining tokens, limit, retry-after seconds, and reset epoch

Token computation uses `System.nanoTime()` for monotonic precision, synchronized per bucket state to avoid race conditions. When the bucket is empty, `ceil((1.0 - tokens) / refillRate)` is returned as `Retry-After`.

**Error handling (`PlatformErrorHandler`):**

A `@RestControllerAdvice` maps all exceptions to RFC 9457 `ProblemDetail` responses:

| Exception | HTTP Status | Problem type |
|-----------|-------------|-------------|
| `IllegalArgumentException` / `PlatformBadRequestException` | 400 | `https://tramai.dev/problems/400` |
| `AuthenticationException` | 401 | `https://tramai.dev/problems/401` |
| `AuthorizationException` | 403 | `https://tramai.dev/problems/403` |
| `RateLimitExceededException` | 429 | `https://tramai.dev/problems/429` |
| `PluginNotFoundException` | 404 | `https://tramai.dev/problems/404` |
| `Throwable` (catch-all) | 500 | `https://tramai.dev/problems/500` |

### Tenant isolation

Tenant isolation is enforced at **two levels**: data model and query scoping.

**Data model:**

```
Team (id, name)
  └── Project (id, team_id, name)
       ├── Workflow runs (via TenantWorkflowRunStores)
       ├── API keys (via platform_api_key.team_id + project_id)
       └── Audit log (via platform_audit_log.team_id)
```

**Run store isolation (`TenantWorkflowRunStores`):**

A `ConcurrentHashMap` keyed by `(teamId, projectId)` holds per-tenant `WorkflowRunStore` instances. Each store is created lazily on first access and contains only the runs for that tenant. This provides **runtime-level isolation** — a workflow run in team-A/project-X cannot be listed or viewed from team-B/project-Y, even with a valid API key.

**API key scoping:**

Every API key is bound to exactly one `(teamId, projectId)`. Authentication resolves the principal, and `PlatformWorkflowService` passes these IDs through to:
- `TeamProjectRegistry.requireProject()` — validates both team and project exist
- `TenantWorkflowRunStores.get()` — scopes run store access
- `AuditLogService.record()` — stamps every audit entry with `teamId`

**Cross-tenant protection:**

Cross-tenant read attempts return empty results (not 404) to avoid leaking tenant existence. This is enforced in `PlatformController.auditLog()` by checking the requested team against the authenticated principal, and in every repository query by the `WHERE team_id = ?` clause.

### JDBC repositories

All persistence uses raw JDBC via `java.sql.Connection` — no ORM. Five repositories exist, each operating on its own table:

| Repository | Table | Key columns | Notes |
|-----------|-------|-------------|-------|
| `TeamRepository` | `platform_team` | `id`, `name` | `create()` and `exists()` |
| `ProjectRepository` | `platform_project` | `id`, `team_id`, `name` | `create()` and `exists()` by `(teamId, projectId)` |
| `ApiKeyRepository` | `platform_api_key` | `id`, `team_id`, `project_id`, `prefix`, `hashed_key`, `scopes`, `burst_capacity`, `refill_tokens_per_second`, `created_at`, `revoked_at`, `last_used_at` | `create()`, `findById()`, `findActiveByPrefix()`, `list()`, `revoke()`, `updateLastUsed()` |
| `AuditLogRepository` | `platform_audit_log` | `id` (auto), `timestamp`, `actor_id`, `action`, `resource_type`, `resource_id`, `team_id`, `metadata_json` | `append()` only (never updated); `list()` with optional action filter |
| `PluginStateRepository` | `platform_plugin` | `id`, `version`, `jar_path`, `enabled`, `status`, `error`, `updated_at` | `find()`, `upsert()` (insert or update), `setEnabled()` |

Key design decisions:

- **BCrypt for API keys** — `PasswordEncoder` (BCrypt) hashes raw keys before storage. The prefix column enables O(1) lookup by the first 16 characters without exposing the full hash.
- **Thread-safe connections** — every repository method uses `dataSource.connection.use { ... }` to acquire and release connections properly.
- **Append-only audit** — `AuditLogRepository` only inserts; there are no update or delete operations. The `AuditLogService` stamps the timestamp from `Clock.systemUTC()`.
- **Plugin state upsert** — `PluginStateRepository.upsert()` checks for an existing record and either inserts or updates accordingly. `setEnabled()` updates the status column atomically.
- **Scope encoding** — API key scopes are serialized as a sorted comma-separated string (e.g., `"admin,read,run"`) and deserialized back to `Set<ApiKeyScope>`.

### Plugin lifecycle

The plugin lifecycle has four phases, managed entirely through `PluginManager`:

```
1. DISCOVERY (startup + refresh)
   │
   ├─ Scan plugin directory for *.jar files
   ├─ Load each JAR via URLClassLoader
   ├─ Discover TramaiPlugin via ServiceLoader
   ├─ Persist/update plugin state in database
   └─ Register step executors + webhook adapters (if enabled)

2. INSTALL (POST /plugins/install)
   │
   ├─ Copy JAR into plugin directory
   └─ Trigger full DISCOVERY

3. ENABLE/DISABLE (POST /plugins/{id}/enable|disable)
   │
   ├─ Update enabled flag in database
   └─ Trigger full DISCOVERY

4. VALIDATION (startup, after DISCOVERY)
   │
   └─ Check all registered workflows have required external step executors
       └─ Fail startup with PluginWorkflowStartupValidationException if not
```

**Discovery (`PluginManager.refresh()`):**

This is the core method, synchronized to prevent concurrent modification:

1. Lists all `.jar` files in the plugin directory
2. For each JAR, calls `loadJar()` which:
   - Creates an isolated `URLClassLoader` (parent = platform classloader)
   - Uses `java.util.ServiceLoader` to find all `TramaiPlugin` implementations
   - Validates non-blank `id` and `version`
   - Returns a `DiscoveredPlugin` with step executors, webhook adapters, and dashboard extensions
3. For each discovered plugin, looks up persisted state — defaults to `enabled = true` if no record exists
4. Collects step executors and webhook adapters only from **enabled** plugins
5. Atomically replaces the registries (`stepExecutorRegistry.replaceAll()`, `webhookAdapterRegistry.replaceAll()`)
6. Replaces the in-memory view cache

**Startup validation (`PluginWorkflowStartupValidator.validate()`):**

Called after discovery at startup. Iterates all registered workflows, collects required external step type IDs (from `pluginStep` definitions), and checks each against the `ExternalStepExecutorRegistry`. If any step type is unresolved, throws `PluginWorkflowStartupValidationException` with a detailed message listing every missing executor per workflow.

**Error handling during loading:**

If a JAR fails to load (missing `TramaiPlugin` service, invalid plugin, class loading error), the error is logged and the JAR is **skipped** — it does not block startup or other plugins. The `DiscoveredPlugin` internal data class includes the plugin reference, executors, adapters, and extensions for building `PluginView` responses.

### Plugin view model

The `PluginView` returned by the API includes:

| Field | Description |
|-------|-------------|
| `id` | Plugin identifier |
| `version` | Plugin version string |
| `status` | `enabled`, `disabled`, or `error` |
| `jarPath` | Filesystem path to the JAR |
| `error` | Error message if status is `error` |
| `stepTypes` | Sorted list of registered step executor type IDs |
| `webhookSources` | Sorted list of registered webhook adapter source IDs |
| `dashboardExtensions` | Sorted list of dashboard extension IDs |

### Module boundary

```
tramai-platform
  ├── api: tramai-server (WorkflowRegistry, WorkflowRunStore, etc.)
  ├── api: tramai-orchestration (ExternalStepExecutorRegistry, etc.)
  ├── impl: spring-boot-starter-web (RestController)
  ├── impl: spring-boot-starter-security (BCrypt)
  ├── impl: JDBC (raw java.sql)
  └── test: [various providers for integration tests]
```

**Owns:**
- `PlatformController` — all REST endpoints for workflows, API keys, audit log, plugins, webhooks
- `PlatformConfiguration` — Spring `@Configuration` that wires all beans
- `PlatformWorkflowService` — tenant-aware workflow execution, run querying, webhook adaptation with `WorkflowObserver` eventing
- `ApiKeyService`, `ApiKeyAuthenticator`, `ApiKeyRateLimiter` — API key management, authentication, and token-bucket rate limiting
- `PluginManager`, `PluginWorkflowStartupValidator` — plugin JAR discovery, lifecycle, and startup validation
- `TeamRepository`, `ProjectRepository`, `ApiKeyRepository`, `AuditLogRepository`, `PluginStateRepository` — JDBC repositories
- `AuditLogService` — append-only audit log writing
- `TeamProjectRegistry`, `TenantWorkflowRunStores`, `WebhookAdapterRegistry` — tenant isolation and webhook routing
- `PlatformModels` — data classes (`Team`, `Project`, `ApiKeyRecord`, `ApiKeyScope`, `AuthenticatedApiKey`, `TramaiPlugin`, `PluginView`, `PluginStatus`, `WebhookAdapter`, `WebhookAdapterFactory`, `DashboardExtension`, `AuditLogEntry`)
- `PlatformErrorHandler` — `@RestControllerAdvice` mapping exceptions to RFC 9457 `ProblemDetail`
- `TramaiPlatformApplication` — `@SpringBootApplication` entry point
