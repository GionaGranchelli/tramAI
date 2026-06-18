# Sovereign Document Intelligence Example

This example demonstrates how TramAI handles a restricted document workflow with local-only routing, policy enforcement, approval suspension, replay-safe resume, audit evidence, and release/evidence artifacts.

It is the best entry point for understanding the sovereign runtime architecture.

## What It Demonstrates

```
RESTRICTED document
  -> sensitivity classification
  -> LOCAL-only model route
  -> policy enforcement
  -> approval gate
  -> replay-safe continuation
  -> audit chain
  -> evidence pack
```

In detail:

- **RESTRICTED document handling** with typed input and typed output
- **Sovereign routing** that denies cloud providers and only allows the approved local provider
- **Policy enforcement** before any model invocation or tool execution
- **Approval suspension** for high-risk operations (e.g., `schedule-payment`)
- **Replay-safe resume** with token-bound continuation state and exactly-once tool execution
- **Audit chain** recording every governance decision with tamper-evident sequencing
- **Evidence pack / release bundle** suitable for downstream review and verification

## Why the Document is RESTRICTED

The invoice includes supplier identity, banking details, payment amount, and business-purpose context — a combination typically sensitive enough to require RESTRICTED handling in regulated environments.

## Why Cloud Routing is Denied

The sovereign profile only allows `local-provider`, mapped to the `LOCAL` trust zone. Routing to a cloud provider is denied before any model invocation occurs. This demonstrates sovereign enforcement, not best-effort fallback.

## Where Approval Suspension Happens

Suspension occurs when the model requests the `schedule-payment` tool, which is marked HIGH risk with `HUMAN_REQUIRED`. The engine stores continuation state, emits approval audit events, and throws `ApprovalSuspendedException` instead of executing the side effect immediately.

## How Replay-Safe Resume Works

Resume uses the stored approval record, expected approval and continuation versions, and a presented approval token. The tool is idempotent and keyed by the engine-provided idempotency key — duplicate resume attempts cannot schedule duplicate payments.

## Output Artifacts

Running the example writes artifacts under `build/sovereign-document-intelligence/`:

- `result.json` — the final typed assessment
- `audit-chain.json` — tamper-evident audit events
- `approval-events.json` — approval lifecycle events
- `sovereign-evidence-pack-v1.json` — evidence bundle

## Running the Example

From the repository root:

```bash
./gradlew :examples:sovereign-document-intelligence:run --args="--release-bundle-manifest=build/sovereign-release/release-artifacts-v1.json"
```

The `--release-bundle-manifest` argument is optional. If `build/sovereign-release/release-artifacts-v1.json` already exists, it will be loaded automatically.

## Enterprise Mapping

This reference workflow maps to real review-and-execute flows such as:

- invoice or procurement review in regulated finance environments
- public-sector payment authorization with locality constraints
- sovereign AI deployments where cloud egress is disallowed for sensitive financial documents
- audit-heavy human-in-the-loop automations that need deterministic evidence artifacts

## Note

This is a **reference workflow**, not a production deployment template. It demonstrates architectural capabilities; production deployment requires additional infrastructure, key management, and operational hardening beyond what this example includes.
