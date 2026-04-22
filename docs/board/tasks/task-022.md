# TASK-022: Expand Orchestration Stability Test Matrix

- Status: completed
- Priority: high
- Primary spec: [SPEC-012](../../specs/spec-012-orchestration-and-coordination.md)
- Related ADRs: [ADR-017](../../adr/adr-017.md)
- Last updated: 2026-04-22

## Purpose

Raise orchestration test coverage from strong prototype coverage to stable-library coverage.

## Scope

- add missing failure and boundary tests for branch selection, checkpoint conflicts, completion-time persistence conflicts, and lease edge cases
- add collision and concurrency tests for workflow-level observability
- add tests for resume compatibility behavior once versioning and stronger definition metadata exist
- add tests that prove bounded parallel semantics with lazy iterables and exact budget accounting

## Definition Of Done

- the orchestration test suite covers happy path, failure path, and boundary behavior for every stable contract area
- important workflow invariants are asserted semantically rather than indirectly
- no known stabilization blocker remains untested

## Notes

This task exists because stability claims for a library module must be proven by tests, not by examples alone.
Covered in the repository with explicit tests for unknown-branch failures, run-time checkpoint conflicts, completion-time checkpoint delete conflicts, lease expiry and wrong-owner release conflicts, workflow-level observability collision scenarios, resume compatibility mismatches, and bounded parallel semantics with lazy iterables and exact budget accounting.
