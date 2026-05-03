# TASK-039D: Rate Limiting

- Status: planned
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Parent task: [TASK-039](../tasks/task-039.md)
- Last updated: 2026-05-03

## Purpose

Implement rate limiting that protects the platform from abuse at both the API key and tenant granularity. Limits use a token-bucket model with burst allowance and return a consistent 429 response shape.

## Scope

- Per-key rate limits tracked by API key prefix
- Per-tenant rate limits that cap aggregate usage across all keys in a tenant
- Token-bucket algorithm with configurable refill rate and burst capacity
- 429 response shape with Retry-After header, limit, remaining, and reset fields
- Graceful degradation — rate limit checks are fast and do not block the request pipeline

## Exit Criteria

- [ ] Per-key limits are enforced independently for each API key
- [ ] Per-tenant limits cap the sum of all key usage within the tenant
- [ ] Token bucket supports burst (e.g., 100 req in 1 s followed by 10 req/s refill)
- [ ] 429 responses include Retry-After, X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset
- [ ] Tests prove: key-level exceed, tenant-level exceed, burst recovery, header correctness
