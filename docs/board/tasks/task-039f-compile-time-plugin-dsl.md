# TASK-039F: Compile-Time Plugin DSL Contract

- Status: planned
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Parent task: [TASK-039](../tasks/task-039.md)
- Last updated: 2026-05-03

## Purpose

Define the compile-time contract for plugins written against the platform DSL. This covers artifact naming conventions, step spec serialization format, registry validation rules, and a reference example plugin.

## Scope

- DSL artifact conventions: package naming, class naming, required annotations or supertypes
- Step spec serialization: how plugin steps declare their input/output schemas for registry consumption
- Registry validation: schema conformance checks at registration time
- Example plugin that demonstrates the full contract — artifact layout, step definitions, and registration

## Exit Criteria

- [ ] Artifact conventions are documented and enforced by a compile-time check (e.g., KSP or annotation processor)
- [ ] Step specs serialize to a registry-consumable format (JSON schema or equivalent)
- [ ] Registry validates step specs against the expected schema and rejects malformed definitions
- [ ] Example plugin compiles, registers its steps, and produces valid serialized specs
- [ ] Tests prove: valid plugin registration, invalid schema rejection, serialization round-trip
