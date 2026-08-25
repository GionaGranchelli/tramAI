# Sovereign JDBC Production Deployment Runbook

## Purpose

This runbook describes how to safely deploy TramAI Sovereign Runtime with PostgreSQL-backed persistence in a production or production-like environment.

It covers topology, required configuration, encryption keys, worker leases, migrations, health checks, failure modes, and operational verification.

This document is **not** a stable 1.0 API declaration. It is a production-readiness boundary for the current `master` branch.

## Deployment topology

### Single-node

```
Spring Boot app
  └── PostgreSQL
        ├── approvals
        ├── suspended_invocations
        ├── approval_continuations
        ├── audit_events
        ├── audit_outbox
        └── worker_leases
```

Use case: local enterprise app, regulated prototype, controlled demo, one worker loop.

### Multi-node

```
Spring Boot node A ──┐
Spring Boot node B ──┼── PostgreSQL
Spring Boot node C ──┘       ├── approvals
                              ├── suspended_invocations
                              ├── approval_continuations
                              ├── audit_events
                              ├── audit_outbox
                              └── worker_leases
```

Key rule: **only one audit outbox worker actively runs per lease name**. Worker lease coordination prevents duplicate dispatch cycles across nodes. Set `lease-enabled: true` and configure unique `worker-id` per node.

## Required dependencies

```kotlin
implementation("dev.tramai:tramai-spring-boot-starter-sovereign-persistence-jdbc:<version>")
runtimeOnly("org.postgresql:postgresql")
```

Also required (typically already present):

```kotlin
implementation("dev.tramai:tramai-spring-boot-starter:<version>")
implementation("dev.tramai:tramai-spring-boot-starter-sovereign-ops:<version>")
```

## Required database migrations

The following migrations exist and **must be applied in order** before the application starts:

| Migration | Purpose |
|-----------|---------|
| V1 | Foundation schema (approvals, suspended_invocations, audit_events, audit_outbox, worker_leases) |
| V2 | Approval continuations table |
| V3 | Audit event hardening constraints |
| V4 | Audit outbox hardening constraints |
| V5 | Worker lease hardening constraints |
| V6 | Approval resume credential custody (`tramai_approval_resume_credentials` table with encrypted credential storage and expiry-tracking index) |
| V7 | Approval continuations resume retry (adds retry metadata for the auto-resume worker) |

Migration SQL files live under:
```
tramai-persistence-jdbc/src/main/resources/tramai/persistence/jdbc/postgres/
```

**Rules:**
- Apply migrations in order. Do not skip.
- Do not partially apply a migration. If a migration fails, roll it back before retrying.
- Do not run the worker before all required tables exist.
- Do not run multi-node workers without lease support enabled.
- **Do not manually edit encrypted payload columns.**

TramAI does not currently bundle a migration runner (Flyway, Liquibase). You must apply migrations with your own tooling, or place the SQL files in your Flyway/Liquibase migration directory.

### Resume credential store

The `tramai_approval_resume_credentials` table (V6) stores encrypted resume credentials for the auto-resume worker. It shares the same AES-256-GCM encryption key (`TRAMAI_SOVEREIGN_STORE_KEY`) as the other sovereign stores.

**Configuration note:** The resume credential store is automatically enabled when:
- `tramai.sovereign.persistence.type=jdbc` is set
- The V6 migration has been applied
- `tramai.sovereign.ops.approved-resume-worker.enabled: true` (if you intend to use auto-resume)

The credential store does not require separate configuration — it uses the same `DataSource`, encryption configuration, and schema as the other JDBC stores.

### Auto-resume worker

The approved-continuation auto-resume worker polls for approved continuations, reads encrypted resume credentials, and replays suspended workflows through the engine resume runtime.

**Configuration:**

```yaml
tramai:
  sovereign:
    ops:
      approved-resume-worker:
        enabled: true
```

**Prerequisites:**
- V6 and V7 migrations applied
- V2 migration (approval_continuations table) applied
- Encryption key (`TRAMAI_SOVEREIGN_STORE_KEY`) configured
- REST control plane or other mechanism to approve suspensions

**Runtime behavior:**
- Each cycle claims rows where the approval is APPROVED, the continuation is PENDING, the continuation and credential are not expired, retry delay has elapsed, and the row is unclaimed or its lease has expired
- Reads the sealed resume credential from `tramai_approval_resume_credentials`
- Decrypts the credential using the same AES-256-GCM key
- Replays the continuation through `ApprovalResumeControlPlane`
- On transient failure, the record is retried with exponential backoff (V7 schema)
- On terminal failure, the continuation is marked CANCELLED

**Worker lifecycle metrics:**
The auto-resume worker participates in the same observability surface as the audit outbox worker — status, health, Micrometer, and OpenTelemetry metrics are available through the existing ops-actuator and ops-micrometer/ops-observability modules.

For detailed approved-resume worker dashboards, alert examples, and triage guidance, see the [Approved Resume Worker Observability runbook](./approved-resume-worker-observability.md).

## Required configuration

### Minimal production YAML

```yaml
tramai:
  sovereign:
    enabled: true
    persistence:
      type: jdbc
      jdbc:
        claim-lease-duration: 5m
        max-claim-limit: 500
      encryption:
        key-env: TRAMAI_SOVEREIGN_STORE_KEY

    ops:
      enabled: true
      mutations-enabled: false
      outbox:
        worker:
          enabled: true
          dispatch-pending: true
          recover-prepared: true
          lease-enabled: true
          lease-name: sovereign-audit-outbox
          worker-id: ${HOSTNAME}
          lease-duration: 30s
          lease-heartbeat-interval: 10s

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/tramai
    username: tramai
    password: ${TRAMAI_DB_PASSWORD}
```

### Configuration reference

| Property | Required | Default | Notes |
|----------|----------|---------|-------|
| `tramai.sovereign.persistence.type` | Yes | — | Must be `jdbc` for JDBC persistence |
| `tramai.sovereign.persistence.encryption.key-env` | Yes | — | Environment variable holding the AES key |
| `tramai.sovereign.ops.outbox.worker.enabled` | No | `false` | Enable the audit outbox background worker |
| `tramai.sovereign.ops.outbox.worker.lease-enabled` | No | `false` | Enable multi-node lease coordination |
| `tramai.sovereign.ops.outbox.worker.lease-name` | No | `sovereign-ops-audit-outbox-worker` | Must be identical across all nodes |
| `tramai.sovereign.ops.outbox.worker.worker-id` | No | Random UUID | Must be unique per node. Use `${HOSTNAME}`. |
| `tramai.sovereign.ops.outbox.worker.lease-duration` | No | `2m` | How long a lease is valid before expiry |
| `tramai.sovereign.ops.outbox.worker.lease-heartbeat-interval` | No | `30s` | How often the worker heartbeats (must be < `lease-duration`) |

### Configuration validation

On startup, if `lease-enabled: true`, the application validates:

- `lease-duration > 0`
- `lease-heartbeat-interval > 0`
- `lease-heartbeat-interval < lease-duration`

Violations cause **startup failure** (fail closed).

## Encryption key requirements

**Environment variable:** `TRAMAI_SOVEREIGN_STORE_KEY`

**Format:** Base64-encoded 256-bit AES key.

**Decoded value must be exactly 32 bytes.**

Generate a key:
```bash
openssl rand -base64 32
```

**Forbidden:**
- Plaintext keys in YAML or properties files
- Keys shorter than 32 bytes
- Keys in version control
- Keys in logs, metrics, or exception messages

**Key must be set in the process environment:**
```bash
export TRAMAI_SOVEREIGN_STORE_KEY="$(openssl rand -base64 32)"
```

The application reads the key from the environment variable named by `encryption.key-env`. If the key is missing or invalid, startup **fails closed** — no stores are created, no traffic is accepted.

**Key rotation** is not yet supported. Plan for key rotation out of band (migrate data, then rotate).

## Worker lease configuration

### Single-node (default)

```yaml
tramai:
  sovereign:
    ops:
      outbox:
        worker:
          enabled: true
          lease-enabled: false
```

Lease is disabled. The worker runs recovery and dispatch without coordination. Safe for single-node deployments.

### Multi-node

```yaml
tramai:
  sovereign:
    ops:
      outbox:
        worker:
          enabled: true
          lease-enabled: true
          lease-name: sovereign-audit-outbox
          worker-id: ${HOSTNAME}
          lease-duration: 30s
          lease-heartbeat-interval: 10s
```

**Requirements for multi-node:**

1. All nodes must share the same PostgreSQL database.
2. All nodes must use the same `lease-name`.
3. Each node must have a unique `worker-id` (use `${HOSTNAME}` or a pod name).
4. `lease-heartbeat-interval` must be less than `lease-duration`.
5. The `worker_leases` table must exist (created by V1 migration).

**Runtime behavior:**
- On each `runOnce()` cycle, the worker tries to acquire the lease.
- If the lease is held by another active node, the cycle is skipped (`lease-held-by-other`).
- If the lease is expired, the current node takes it.
- During execution, the worker heartbeats at `lease-heartbeat-interval`.
- If the heartbeat fails (another node stole the lease, or the lease disappeared), the current worker cycle is cancelled (`SovereignOpsWorkerLeaseLostException`).

## Audit outbox dispatch model

The audit outbox worker runs a continuous loop with two runtime toggles:

```
runOnce()
  ├── recoverPrepared()    — recover stale PREPARED records (toggle: recover-prepared)
  └── retryPending()       — claim and dispatch PENDING records (toggle: dispatch-pending)
```

Retryable or expired EMITTING records are handled by the outbox claim/retry logic (SKIP LOCKED + attempt count + next_attempt_at), not by a separate worker configuration property.

**Dispatch is claim-based:** each dispatch uses `SELECT ... FOR UPDATE SKIP LOCKED` on the `audit_outbox` table. Multiple workers (with lease disabled) cannot claim the same row — PostgreSQL row-level locking prevents that. The lease prevents multiple workers from *running the recovery/dispatch cycle* simultaneously, which is the higher-level coordination concern.

**Status lifecycle:**
```
PREPARED → PENDING → DISPATCHED
                       ├── FAILED (retryable)
                       └── DEAD (terminal)
```

## Health and readiness checks

### Actuator endpoints (optional)

If `tramai-spring-boot-starter-sovereign-ops-actuator` is on the classpath:

```yaml
tramai:
  sovereign:
    ops:
      actuator:
        worker-status:
          enabled: true
        worker-health:
          enabled: true
```

**Worker status endpoint:**
```
GET /actuator/tramaiSovereignOpsWorker
```

Returns cycle statistics: success count, failure count, last run timestamp, current state.

**Worker health component:**
```
GET /actuator/health
```

Includes `tramaiSovereignOpsWorker` health indicator (UP / DOWN / DEGRADED).

### Database health

Ensure your Spring Boot application has a DataSource health indicator:

```yaml
management:
  health:
    db:
      enabled: true
```

**What these endpoints expose:**
- Worker cycle statistics
- Worker health status
- Database connectivity status

**What they intentionally do NOT expose:**
- Prompts sent to models
- Model responses
- Raw exception messages or stack traces
- Sensitive identifiers or PII
- Tool arguments or intermediate workflow data

## Operational verification checklist

Before enabling production traffic, verify:

- [ ] PostgreSQL is reachable from the application.
- [ ] All migrations (V1–V7) are applied in order.
- [ ] `tramai.sovereign.persistence.type=jdbc` is set.
- [ ] `TRAMAI_SOVEREIGN_STORE_KEY` is set and loads successfully.
- [ ] Application does **not** fall back to in-memory stores.
- [ ] `AuditStore` is JDBC-backed (`JdbcAuditStore`).
- [ ] `ApprovalStore` is JDBC-backed (`JdbcApprovalStore`).
- [ ] `SuspendedInvocationStore` is JDBC-backed (`JdbcSuspendedInvocationStore`).
- [ ] `ApprovalContinuationStore` is JDBC-backed (`JdbcApprovalContinuationStore`).
- [ ] `SovereignOpsAuditOutboxStore` is JDBC-backed (`JdbcSovereignOpsAuditOutboxStore`).
- [ ] `SovereignOpsApprovalMutationStore` is JDBC-backed (`JdbcSovereignOpsApprovalMutationStore`).
- [ ] `SovereignOpsWorkerLeaseStore` is available (if `lease-enabled: true`).
- [ ] `ApprovalResumeCredentialStore` is JDBC-backed (`JdbcApprovalResumeCredentialStore`).
- [ ] `tramai_approval_resume_credentials` table exists (V6 migration applied).
- [ ] Auto-resume worker starts only when configured (`approved-resume-worker.enabled: true`).
- [ ] Health/readiness endpoint reports expected state.
- [ ] No prompts, model outputs, replay envelopes, or PII appear in metrics/logs.
- [ ] Application logs do not contain plaintext encryption keys.

## Failure modes and expected behavior

| Failure | Expected behavior |
|---------|-------------------|
| Missing `DataSource` with `type=jdbc` | Startup fails closed |
| Missing encryption key | Startup fails closed |
| Invalid encryption key size (not 32 bytes) | Startup fails closed |
| Outbox worker lease missing while `lease-enabled: true` | Startup fails closed |
| `lease-heartbeat-interval >= lease-duration` with leasing enabled | Startup fails closed |
| Audit dispatcher unavailable | Mutations fail closed |
| Outbox dispatch fails after mutation commit | Mutation remains committed; outbox retries on next cycle |
| Approval mutation fails before commit | Approval and outbox rollback together |
| Worker loses lease during run | Current worker cycle cancelled |
| Expired approval denial attempted | Denied transition rejected (`IllegalApprovalTransitionException`) |
| Outbox insert conflict (duplicate `event_key`) | Transaction rolls back; approval unchanged |
| Approval version conflict | Transaction rolls back; no orphan outbox record |
| Malformed approval metadata (missing fields) | Transaction rolls back; approval unchanged |
| Payload codec failure | Transaction rolls back; approval unchanged |

## Rollback strategy

### Rolling back an application deployment

1. Stop the new version.
2. Deploy the previous version.
3. Verify database connectivity and store health.
4. Verify the worker acquires its lease (if multi-node).
5. Verify no stale outbox records from the aborted deployment.

Schema migrations are **additive-only** — rolling back an application version does not require rolling back migrations. If a future migration is destructive, a separate rollback migration must be documented.

### Recovering from a failed deployment

1. Check that PostgreSQL is healthy.
2. Check that all required environment variables are set.
3. Check the application startup log for `tramai-sovereign-*` error codes.
4. Verify migrations are applied:
   ```sql
   SELECT table_name FROM information_schema.tables
   WHERE table_schema = 'public' AND table_name IN (
     'approvals', 'suspended_invocations', 'approval_continuations',
     'audit_events', 'audit_outbox', 'worker_leases'
   );
   ```
   All six tables must exist.

## Security and observability boundaries

### Allowed in operational surfaces

- Worker state (cycle count, last run)
- Counters and timestamps
- Sanitized failure types
- Retry counts
- Lease acquisition status

### Forbidden in operational surfaces

- Prompts
- Model responses
- Replay envelopes
- Raw exception messages
- PII
- Medical or financial data
- Approval IDs as metric labels
- Encryption keys
- SHA-256 hashes that can be reversed to plaintext

## Non-goals

This runbook does **not** cover:

- Flyway/Liquibase integration (migrations are plain SQL; integrate with your own tooling)
- Kubernetes manifests or cloud-provider-specific deployment templates
- Key rotation implementation
- Cross-database distributed transactions (XA)
- Production migration executor
- Stable 1.0 API
- Maven Central availability

## See also

- [Sovereign JDBC Persistence Design](../architecture/sovereign-jdbc-persistence-design.md) — architecture and rollout plan
- [Sovereign Runtime Quickstart](../guides/sovereign-runtime-quickstart.md) — evaluation and integration
- [Sovereign Runtime RC Boundary](../releases/sovereign-runtime-rc-boundary.md) — what the RC includes
- [Worker Observability Runbook](../operations/sovereign-ops-worker-observability-runbook.md) — monitoring and alerts
