# TASK-031: Implement Webhook Receiver

- Status: done
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

- [x] `POST /webhooks/invoice` with valid JSON starts the workflow and returns 202
- [x] Webhook with invalid body returns 400 without starting a workflow
- [x] GitHub HMAC verification passes for valid signatures
- [x] Webhook without valid signature is rejected with 401
- [ ] Rate-limited client receives 429 after exceeding limits (deferred)

## Implementation Notes

- Endpoint added to WorkflowController alongside existing REST endpoints
- HMAC-SHA256 verification via X-Hub-Signature-256 header
- RequestBodySizeLimitFilter extended to cover /webhooks/* path
- Webhook secret, body size configurable via application.yml
- Asynchronous workflow execution — returns 202 immediately
- 5 new tests: valid webhook, invalid signature, valid HMAC, invalid body, oversized payload
- 30 tests total, 0 failures

## Review

Implemented by Copilot CLI (gpt-5.4). All tests pass. Rate limiting deferred to platform phase.
