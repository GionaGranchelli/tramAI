# Regulated Claim Triage Reference Scenario

## Purpose

This scenario shows how the Sovereign Runtime RC can support a regulated healthcare or insurance claim triage workflow.

It is a **reference scenario for architecture and evaluation**. It is **not** a production deployment guide, legal compliance certification, or medical decisioning system.

## Scenario Summary

A healthcare insurer receives a claim that may contain sensitive medical, financial, and personal data.

The organization wants AI assistance for triage, but the workflow must enforce:

- data classification
- DLP / redaction
- model routing based on sensitivity
- human approval for high-risk recommendations
- tamper-evident audit
- durable recovery through an outbox
- sanitized operational observability

## Actors

| Actor | Role |
|-------|------|
| Claimant / customer | Submits the claim |
| Claim intake service | Receives and validates the raw claim |
| TramAI governed workflow | Orchestrates policy, routing, approval, and audit |
| DLP classifier | Assigns sensitivity levels to data fields |
| Policy engine | Decides allow / deny / suspend per data class and route |
| Local or trusted model provider | Executes the AI triage recommendation |
| Human reviewer / medical ops specialist | Reviews high-risk recommendations |
| Audit / outbox worker | Persists and dispatches audit events durably |
| Compliance reviewer | Audits the audit trail retrospectively |
| Operator / SRE | Monitors worker health and metrics |

## Data Classes

| Data | Sensitivity | Example |
|------|-------------|---------|
| Claim ID | Internal | `claim-123` |
| Customer identity | Restricted | name, address, customer number |
| Medical details | Restricted | diagnosis, treatment, medication |
| Payment details | Confidential | invoice amount, bank / payment details |
| Claim metadata | Internal | submission date, claim type |
| AI recommendation | Internal / Restricted | depends on included reasoning |

## Trust Zones

| Zone | Allowed Data | Example Runtime Behavior |
|------|-------------|--------------------------|
| Local-only | Restricted medical or identity data | Use local model only |
| EU / trusted provider | Redacted confidential data | Route only after DLP redaction |
| Approved cloud | Non-sensitive metadata | Allowed if policy permits |
| Denied | Unsupported sensitivity / provider combination | Fail before model call |

## Workflow Overview

```
claim submitted
  -> input validation
  -> DLP classification
  -> policy decision
  -> redaction if needed
  -> sovereign routing
  -> model recommendation
  -> structured output validation
  -> approval decision
  -> audit event
  -> outbox dispatch
  -> operator / health / metrics visibility
```

## Policy Decisions

| Condition | Decision |
|-----------|----------|
| Restricted medical data + approved cloud model | Deny |
| Restricted medical data + local model | Allow |
| Confidential payment data + redaction available | Allow after redaction |
| Missing sensitivity classification | Deny by default |
| High-risk recommendation | Suspend for approval |
| Low-risk administrative recommendation | Allow without manual approval |

## Example Recommendation Types

| Recommendation | Risk | Approval Needed |
|----------------|------|-----------------|
| Request missing document | Low | No |
| Mark claim for manual review | Medium | Maybe |
| Suggest rejection | High | Yes |
| Suggest payout | High | Yes |
| Detect possible fraud | High | Yes |

## Human Approval Boundary

High-risk recommendations must **not** automatically execute.

The workflow should:

- persist the suspended invocation to the encrypted file store
- **not** block a thread while waiting for a human decision
- present a sanitized recommendation to an authorized reviewer (no raw prompts, no model responses, no PII)
- require a role-based approval decision (approve / deny / escalate)
- audit every approve / deny / escalate decision with a sequenced audit event
- resume through a replay-safe envelope when the decision arrives

## Audit and Outbox

The workflow emits audit events for:

- claim received
- DLP classification completed
- policy decision made
- model route selected
- recommendation generated
- approval requested
- approval decision recorded
- outbox dispatch attempted
- outbox dispatch completed or failed

The outbox allows recovery after process restart and avoids losing operational events. Each audit event is sequenced for tamper-evident ordering.

## Observability

Operators can inspect worker state **without** exposing sensitive claim data.

**Allowed observability:**
- worker enabled / running state
- cycle counters (success, failure)
- last success / failure timestamp
- sanitized health status exposed through the Actuator health component
- metric counters (OpenTelemetry, Micrometer)

**Not allowed:**
- prompts sent to models
- model responses
- claimant identity
- medical text
- payment details
- raw exception messages
- stack traces
- approval IDs or claim IDs as metric labels

## Failure Modes

| Failure | Expected Behavior |
|---------|-------------------|
| DLP classifier unavailable | Fail closed or route to manual review |
| Policy denies route | No model call — fail with explicit denial reason |
| Model provider unavailable | Retry / fallback according to policy |
| Approval timeout | Remain suspended or escalate |
| Outbox dispatch fails | Event remains durable for retry or operator investigation |
| Worker restarts | Recover pending outbox work |
| Audit store unavailable | Fail closed for governed workflow |

## What TramAI Provides in This Scenario

- Typed AI operation boundary (`@AiService`, `@Operation`)
- Policy enforcement (allow / deny / suspend based on data class and route)
- DLP-aware routing (redact before sending to model)
- Sovereign provider selection (local-only, EU / trusted, approved cloud)
- Replay-safe approval resume (encrypted continuation envelopes)
- Encrypted file-backed or JDBC-backed persistence (approvals, continuations, audit stream, outbox)
- Audit chain (tamper-evident sequencing)
- Outbox recovery (durable claim-based dispatch)
- Worker observability (Actuator status / health, Micrometer, OpenTelemetry)
- Release-candidate verification evidence (`verifySovereignRuntimeReleaseCandidate`)
- JDBC transactional approval mutation outbox boundary
- Multi-node worker lease coordination

## What Is Still Application Responsibility

- Legal compliance review (HIPAA, GDPR, sector-specific regulation)
- Domain-specific medical / insurance rules
- Final business decisioning
- User-facing reviewer UI
- Role / identity management integration
- Customer communication
- Production deployment and monitoring

## How to Evaluate This Scenario Locally

Start with the canonical RC verification command:

```bash
./gradlew verifySovereignRuntimeReleaseCandidate --no-configuration-cache --rerun-tasks
```

Then read:

- [Sovereign Runtime Quickstart](../guides/sovereign-runtime-quickstart.md)
- [Sovereign Runtime RC Boundary](../releases/sovereign-runtime-rc-boundary.md)
- [Worker Observability Runbook](../operations/sovereign-ops-worker-observability-runbook.md)

For the planned database-backed persistence direction, see [Sovereign JDBC Persistence Design](../architecture/sovereign-jdbc-persistence-design.md).

## Executable JDBC E2E Proof

This scenario is one of the closure evidence items for the Sovereign Runtime roadmap.
See [Sovereign Runtime Closure Boundary](../releases/sovereign-runtime-closure-boundary.md).

This scenario is now covered by a JDBC-backed E2E test in:

`examples/spring-sovereign-starter/src/test/kotlin/dev/tramai/examples/spring/RegulatedClaimTriageJdbcE2ETest.kt`

The test proves:
- High-risk recommendations are suspended through the Preview ApprovalGateway API instead of manual store creation
- Approval denial and audit intent are committed transactionally in one database transaction
- Audit outbox records are durable and dispatchable after context restart
- Restricted medical data is denied before cloud model invocation (policy enforcement)
- Low-risk recommendations complete without approval suspension
- Operational surfaces remain sanitized — no raw PII, medical text, payment data, prompts, or model responses in audit events or outbox payloads

## Non-Goals

This scenario does **not** claim:

- Production readiness
- Regulatory certification (HIPAA, GDPR, or any other)
- Medical decision automation
- Stable 1.0 API
- Maven Central availability
