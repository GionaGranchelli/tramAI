# TASK-031: Implement Webhook Receiver

- Status: planned
- Priority: medium
- Primary spec: [SPEC-014](../../specs/spec-014-server.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Allow external services to trigger TramAI workflows via HTTP webhooks.

## Scope

- `POST /webhooks/{workflow-name}` — generic webhook endpoint
- Request body deserialized into the workflow's initial state type
- Returns 202 Accepted with workflow ID immediately
- GitHub HMAC signature verification
- Configurable: max body size, allowed sources, rate limits
- Log every webhook receipt with source, size, and workflow started

## Exit Criteria

- [ ] `POST /webhooks/invoice` with valid JSON starts the workflow and returns 202
- [ ] Webhook with invalid body returns 400 without starting a workflow
- [ ] GitHub HMAC verification passes for valid signatures
- [ ] Webhook without valid signature is rejected with 401
- [ ] Rate-limited client receives 429 after exceeding limits
