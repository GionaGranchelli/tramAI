# TASK-039B: API Keys and Authorization

- Status: planned
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Parent task: [TASK-039](../tasks/task-039.md)
- Last updated: 2026-05-03

## Purpose

Implement an API key system that authenticates programmatic access to the platform. Keys are hashed at rest, carry a scope model (run, read, admin), and support rotation and revocation without invalidating active sessions.

## Scope

- API key hashing with bcrypt or Argon2 before storage
- Scope model with three levels: run (execute workflows), read (query state), admin (manage resources)
- Key rotation endpoint that issues a new key and optionally expires the old one with a grace period
- Key revocation endpoint that immediately invalidates a prefix-based lookup
- Auth middleware integration that resolves the API key to a tenant and scope on every request

## Exit Criteria

- [ ] Raw API keys are never stored — only bcrypt/Argon2 hashes persist
- [ ] Scopes are enforced at the middleware level and reject unauthorized operations
- [ ] Rotation replaces a key without breaking in-flight requests (grace period)
- [ ] Revocation is immediate — revoked keys fail authentication on the next request
- [ ] Auth middleware extracts key, resolves tenant, and attaches context to the request pipeline
- [ ] Tests cover: valid auth, invalid key, expired key, scope denial, rotation flows
