# Sovereign JDBC Persistence Design

## Purpose

This document defines the production-hardening direction for database-backed Sovereign Runtime persistence.

The current Sovereign Runtime RC uses encrypted file-backed persistence suitable for local evaluation and single-node scenarios. Enterprise deployments will require JDBC / database-backed persistence for approvals, suspended invocations, audit streams, and outbox recovery.

This document is a **design target**. It does **not** claim that JDBC persistence is implemented yet.

The implementation is in the [`tramai-persistence-jdbc`](../../tramai-persistence-jdbc) module, which provides the PostgreSQL schema (V1 foundation + V2 approval continuations + V3 audit hardening) and the following JDBC stores:

- `JdbcApprovalStore` — approval request/decision persistence
- `JdbcSuspendedInvocationStore` — replay-safe suspended continuation persistence
- `JdbcApprovalContinuationStore` — approval continuation lifecycle with encrypted arguments
- `JdbcAuditStore` — tamper-evident audit event stream with hash-chain validation

Audit outbox store is not yet implemented.

## Current State

The Sovereign Runtime RC currently provides:

- encrypted file-backed persistence
- approval persistence
- suspended invocation persistence
- approval continuation persistence (human-in-the-loop resume)
- audit chain persistence
- audit outbox persistence
- worker recovery and dispatch
- local verification through `verifySovereignRuntimeReleaseCandidate`

Current JDBC stores (implemented):

- `JdbcApprovalStore` — approval request/decision persistence (PR #80)
- `JdbcSuspendedInvocationStore` — replay-safe suspended continuation persistence (PR #81)
- `JdbcApprovalContinuationStore` — approval continuation lifecycle with encrypted arguments (PR #83)
- `JdbcAuditStore` — tamper-evident audit event stream with hash-chain validation (PR #84)

Current limitations:

- no JDBC audit outbox store
- no multi-node worker coordination
- no database transaction boundary
- no production deployment certification

## Target Capabilities

The production-hardening target is to support:

- JDBC-backed approval store
- JDBC-backed suspended invocation store
- JDBC-backed audit stream store
- JDBC-backed audit outbox store
- durable recovery after process restart
- transactional writes where required
- duplicate dispatch protection
- schema migration support
- Testcontainers-based integration tests
- future distributed worker coordination

## Persistence Areas

| Area | Purpose | Production Requirement |
|------|---------|------------------------|
| Approvals | Store approval requests and decisions | Durable, queryable, auditable |
| Approval continuations | Store human-in-the-loop approval lifecycle state | Durable, restart-safe, concurrency-safe | ✅ PR #83 |
| Suspended invocations | Store replay-safe continuations | Encrypted, tamper-resistant, resumable | ✅ PR #81 |
| Audit stream | Store ordered audit events | Append-only, hash-chain verifiable | ✅ PR #84 |
| Audit outbox | Store dispatchable operational events | Durable retry, duplicate protection |
| Worker status | Store sanitized worker state | Optional, operational visibility |

## Design Principles

1. **Fail closed** for governed workflows when critical persistence is unavailable.
2. Keep sensitive payloads **encrypted at rest**.
3. **Do not expose** prompts, model responses, replay envelopes, raw exception messages, or PII through operational surfaces.
4. Preserve **replay safety** for suspended invocations.
5. Preserve **audit hash-chain verification**.
6. Preserve **idempotency and duplicate protection**.
7. Keep JDBC implementation **compatible with future distributed worker coordination**.
8. **Avoid coupling** persistence to a specific application domain.

## Minimum Constraints

The first JDBC implementation should define at least:

- `approvals`: primary key on `approval_id`; optimistic update on `version`
- `suspended_invocations`: primary key on `invocation_id`; unique `replay_envelope_digest`
- `approval_continuations`: primary key on `approval_id`; optimistic update on `version`; status-version index; claimed-at index
- `audit_events`: primary key on (`stream_id`, `sequence_number`); unique `event_id`
- `audit_outbox`: primary key on `outbox_id`; unique `event_key`
- `worker_leases`: primary key on `lease_name`

These constraints give the implementation agent a precise contract for the first PR.

## Database Scope

Initial target database:

- **PostgreSQL-compatible JDBC**

Future-compatible but not required initially:

- generic JDBC portability
- vendor-specific optimizations
- managed cloud database certification

## Proposed Tables

### approvals

Purpose: store approval requests and decisions.

Required fields (conceptual):

- `approval_id`
- `status`
- `required_role`
- `created_at`
- `decided_at`
- `decision_actor_hash`
- `decision_type`
- `sanitized_metadata`
- `encryption_key_id`
- `encryption_algorithm`
- `encryption_nonce`
- `encrypted_payload`
- `payload_digest`
- `version`

### suspended_invocations

Purpose: store replay-safe suspended workflow continuations.

Required fields (conceptual):

- `invocation_id`
- `service_key`
- `operation_key`
- `descriptor_hash`
- `status`
- `created_at`
- `resumed_at`
- `replay_envelope_digest`
- `encryption_key_id`
- `encryption_algorithm`
- `encryption_nonce`
- `encrypted_replay_envelope`
- `version`

### approval_continuations

Purpose: store human-in-the-loop approval continuation lifecycle state (PENDING → CLAIMED → COMPLETED / EXPIRED / CANCELLED / CANCELLED_UNCERTAIN).

Implemented as `JdbcApprovalContinuationStore` (PR #83) using the V2 schema.

Concurrency model: optimistic locking via `UPDATE ... WHERE version = ? AND status = ?` (CAS).

Required fields (conceptual):

- `approval_id` — primary key
- `status` — PENDING, CLAIMED, COMPLETED, EXPIRED, CANCELLED, CANCELLED_UNCERTAIN
- `version` — optimistic lock counter
- `created_at` — domain timestamp (preserves caller's value)
- `approval_expires_at` — deadline for PENDING→EXPIRED transition
- `claimed_by` — actor who claimed for execution
- `claimed_at` — when claimed
- `completed_at` — when completed
- `workflow_run_id`, `correlation_id` — workflow tracing
- `tool_call_id`, `tool_name` — tool identity
- `arguments_digest` — SHA-256 digest of tool arguments
- `policy_version`, `workflow_digest` — policy identity
- `recovery_resolved_by`, `recovery_resolved_at`, `recovery_reason_code` — force-cancel recovery metadata
- `encrypted_arguments` — AES-GCM encrypted tool arguments (BYTEA)
- `encryption_key_id`, `encryption_algorithm`, `encryption_nonce`, `payload_digest` — encryption metadata

Non-goal: arguments are not exposed via `get()` — only through `claimForExecution()`.
Non-goal: CLAIMED continuations must never lazy-expire (only PENDING).

### audit_events

Purpose: store tamper-evident audit events.

Required fields (conceptual):

- `stream_id`
- `sequence_number`
- `event_id`
- `event_type`
- `event_hash`
- `previous_event_hash`
- `occurred_at`
- `sanitized_actor`
- `encryption_key_id`
- `encryption_algorithm`
- `encryption_nonce`
- `encrypted_payload`
- `payload_digest`
- `schema_version`

### audit_outbox

Purpose: store dispatchable audit / outbox records.

Required fields (conceptual):

- `outbox_id`
- `event_key`
- `status`
- `correlation_key_hash`
- `created_at`
- `claimed_at`
- `dispatched_at`
- `attempt_count`
- `last_failure_type`
- `next_attempt_at`
- `encryption_key_id`
- `encryption_algorithm`
- `encryption_nonce`
- `encrypted_payload`
- `payload_digest`
- `version`

### worker_leases

Purpose: future multi-node coordination.

This is **not** required for the first JDBC implementation, but the design should reserve space for it.

Required fields (conceptual):

- `lease_name`
- `owner_id`
- `acquired_at`
- `expires_at`
- `heartbeat_at`
- `version`

## Transaction Boundaries

Documented expected transaction behaviour:

- Approval request creation should be **atomic**.
- Approval decision update should be **atomic and idempotent**.
- Suspended invocation creation should be **atomic**.
- Resume should **atomically mark** invocation consumed / resumed.
- Audit event append must preserve **sequence and hash-chain integrity**. Append must use either a per-stream lock row (`SELECT ... FOR UPDATE`) or optimistic insert with unique (`stream_id`, `sequence_number`) and retry on conflict. Concurrent writers must not be able to create two valid events with the same `previous_event_hash`.
- When audit event append and outbox record creation use the same JDBC `DataSource`, they must occur in the **same database transaction**. If the application uses different persistence backends, TramAI must document that atomicity is not guaranteed and must fail closed or expose an explicit degraded mode.
- Outbox dispatch should use **claim / lease semantics** to avoid duplicate dispatch.

## Idempotency and Duplicate Protection

The JDBC design must protect against:

- duplicate approval decisions
- duplicate resume attempts
- duplicate audit events
- duplicate outbox dispatch
- worker restart replay
- concurrent dispatch workers

Suggested mechanisms:

- unique constraints
- version columns
- compare-and-set updates
- event keys
- replay envelope digests
- claim tokens / leases

## Encryption

The JDBC implementation must preserve the same security posture as file persistence:

- sensitive payloads encrypted **before** storage
- no plaintext prompts
- no plaintext model responses
- no plaintext replay envelopes
- no plaintext tool arguments
- no raw exception messages in persisted operational metadata

Key rotation is **not** part of the first implementation, but the schema should not make future rotation impossible.

## Migration Strategy

The JDBC implementation should eventually provide schema migrations.

Possible options:

- plain SQL migration files
- Flyway-compatible scripts
- Liquibase-compatible scripts

Initial recommendation:

- provide **plain SQL migration scripts** first
- document Flyway / Liquibase integration later

## Testing Strategy

The JDBC persistence track should include:

- reusable persistence contract tests
- Postgres Testcontainers integration tests
- restart / reopen tests
- duplicate detection tests
- concurrent claim tests
- audit hash-chain verification tests
- corruption / tamper detection tests where applicable
- migration smoke tests

## Observability

Database-backed persistence must preserve sanitized observability.

**Allowed:**

- worker state
- counters
- timestamps
- sanitized failure type
- retry counts

**Forbidden:**

- prompts
- model responses
- replay envelopes
- raw exception messages
- PII
- medical / financial data
- claim IDs as metric labels
- approval IDs as metric labels

## Rollout Plan

Suggested implementation sequence:

1. Add `tramai-persistence-jdbc` module skeleton.
2. Add JDBC schema and migration layout.
3. Add approval JDBC store. ✅ `JdbcApprovalStore` (PR #80)
4. Add suspended invocation JDBC store. ✅ `JdbcSuspendedInvocationStore` (PR #81)
5. Add approval continuation JDBC store. ✅ `JdbcApprovalContinuationStore` (PR #83)
6. Add audit stream JDBC store. ✅ `JdbcAuditStore` (PR #84)
7. Add audit outbox JDBC store.
8. Add Testcontainers-based integration tests.
9. Add optional worker lease support.
10. Add production deployment documentation.

## Non-Goals

This design does **not** claim:

- JDBC persistence is already implemented
- Production readiness
- Distributed worker coordination
- Key rotation
- Cloud database certification
- Cross-database distributed transactions / XA coordination
- Stable 1.0 API
- Maven Central availability
