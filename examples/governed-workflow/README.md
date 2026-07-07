# Governed Workflow Example

A minimal, deterministic TramAI governed workflow for **claim triage** — no external model or API credentials required.

## What It Demonstrates

- Typed workflow state and typed result
- `aiStep` wrapping a deterministic classifier service
- `gateStep` for policy enforcement (restricted claims)
- `gateStep` for approval gating (high-risk claims without approval)
- `localStep` for finalization
- Success and failure paths

## Run

```bash
./gradlew :examples:governed-workflow:run
```

Expected output:

```
=== Governed Workflow Example: Claim Triage ===

✓ Low-risk claim: ready-for-review — Policy and approval gates passed
✓ Restricted claim: rejected — Restricted claim requires manual handling
✓ High-risk unapproved claim: rejected — High-risk claim requires human approval
✓ High-risk approved claim: ready-for-review — Policy and approval gates passed

=== All scenarios demonstrated ===
```

## Test

```bash
./gradlew :examples:governed-workflow:test
```

## Related Docs

- [Governed Workflow Quickstart](../../docs/guides/governed-workflow-quickstart.md)
- [Orchestration Guide](../../docs/guides/orchestration.md)
- [Structured Output Contract Lifecycle](../../docs/structured-output-contract-lifecycle.md)
- [Approval Gateway Golden Path](../../docs/guides/approval-gateway-golden-path.md)

## Non-claims

This example does **not** prove production readiness, legal correctness, insurance correctness,
compliance, certification, or model accuracy. It demonstrates TramAI workflow composition only.
