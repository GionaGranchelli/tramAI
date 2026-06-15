# Sovereign Document Intelligence

This example demonstrates a sovereign document-intelligence workflow built with TramAI’s typed `@AiService` model. A RESTRICTED invoice is analyzed through an approved local provider, a HIGH-risk payment tool is suspended for human approval, the workflow is resumed with a bound approval token, and the final result is written alongside audit and evidence artifacts.

## What It Shows

- RESTRICTED document handling with typed input and typed output
- Sovereign routing that denies cloud providers and only allows the approved local provider
- HIGH-risk tool execution suspension at `schedule-payment`
- Replay-safe resume with token-bound continuation state and exactly-once tool execution
- Audit-chain and evidence-pack generation suitable for downstream review

## Why The Document Is RESTRICTED

The invoice includes supplier identity, banking details, payment amount, and business-purpose context. In a real enterprise setting, that combination is typically sensitive enough to require RESTRICTED handling because it can expose counterparties, financial controls, and payment instructions.

## Why Cloud Routing Is Denied

The example profile only allows `local-provider`, and the model registry only registers `local-invoice-model` on that provider. That means routing to a cloud provider is denied before any model invocation occurs. The intent is to demonstrate sovereign enforcement, not best-effort fallback.

## Why Local Routing Is Allowed

`local-provider` is explicitly listed in the sovereign profile, mapped to the `LOCAL` trust zone, and bound to the registered model. The deterministic provider in this example stands in for an approved on-prem or enclave-hosted model endpoint.

## Where Approval Suspension Happens

Suspension occurs when the model requests the `schedule-payment` tool. That tool is marked HIGH risk with `HUMAN_REQUIRED`, so the engine stores continuation state, emits approval audit events, and throws `ApprovalSuspendedException` instead of executing the side effect immediately.

## How Replay-Safe Resume Works

The resume path uses the stored approval record, the expected approval and continuation versions, and the presented approval token. The tool itself is idempotent and keyed by the engine-provided idempotency key, so duplicate resume attempts cannot schedule duplicate payments.

## Output Artifacts

Running the example writes these files under `build/sovereign-document-intelligence/`:

- `result.json`
- `audit-chain.json`
- `approval-events.json`
- `sovereign-evidence-pack-v1.json`

## Running The Example

From the repository root:

```bash
./gradlew :examples:sovereign-document-intelligence:run --args="--release-bundle-manifest=build/sovereign-release/release-artifacts-v1.json"
```

The `--release-bundle-manifest` argument is optional. If omitted, the example will still run; if `build/sovereign-release/release-artifacts-v1.json` already exists, it will be loaded automatically.

## Enterprise Mapping

This reference workflow maps to real review-and-execute flows such as:

- invoice or procurement review in regulated finance environments
- public-sector payment authorization with locality constraints
- sovereign AI deployments where cloud egress is disallowed for sensitive financial documents
- audit-heavy human-in-the-loop automations that need deterministic evidence artifacts
