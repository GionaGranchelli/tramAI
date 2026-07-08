# Approval Resume Example

A minimal, runnable TramAI approval workflow example for **expense approval** — demonstrates how a workflow can suspend for human approval, then continue or stop based on the decision.

## What It Demonstrates

- Approval suspension when a high-value expense requires manager review
- Approved path — expense is reimbursed exactly once after approval
- Denied path — expense is not reimbursed
- Low-value bypass — expenses below the threshold complete without approval
- Idempotent side effect — reimbursement executes at most once

## Scenarios

| Scenario | Result |
|----------|--------|
| Low-value expense (€500) | Completes without approval → `REIMBURSED` |
| High-value expense pending (€1,500) | Suspends for approval → `APPROVAL_REQUIRED` |
| High-value expense approved | Resumes and reimburses exactly once → `REIMBURSED` |
| High-value expense denied | Stops without reimbursement → `DENIED` |

## Run

```bash
./gradlew :examples:approval-resume:test
```

The tests use embedded PostgreSQL (no Docker required) and exercise all four scenarios deterministically.

## What This Example Proves

- Approval can suspend a workflow
- An approved workflow can resume and execute its side effect
- A denied workflow does not execute its side effect
- Side effects execute at most once, even across resume boundaries
- Low-risk workflows bypass approval entirely

## What This Example Does Not Prove

- Legal, financial, or regulatory correctness
- Production readiness or certification
- Compliance with any specific approval framework
- That human approval validates the correctness of the AI or business decision

## Related Docs

- [Approval Workflow Ergonomics Guide](../../docs/guides/approval-workflow-ergonomics.md)
- [Approval Gateway Golden Path](../../docs/guides/approval-gateway-golden-path.md)
- [Governed Workflow Quickstart](../../docs/guides/governed-workflow-quickstart.md)

## Non-claims

This example does **not** prove production readiness, legal correctness, compliance, certification, or that human approval means the decision was correct. It demonstrates TramAI approval workflow composition only.
