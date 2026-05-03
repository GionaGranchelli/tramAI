# TASK-039G: Dashboard Run History Slice

- Status: planned
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Parent task: [TASK-039](../tasks/task-039.md)
- Last updated: 2026-05-03

## Purpose

Build the run history UI slice for the dashboard, comprising three views: a workflow list, a run list scoped to a workflow, and a run detail view. All views apply redaction and payload truncation for security-sensitive data.

## Scope

- Workflow list: paginated table of registered workflows with name, version, last run time, status summary
- Run list: paginated table of runs for a selected workflow with run ID, status, duration, started-at, triggered-by
- Run detail: single-run view with step timeline, input/output per step, error traces, and duration breakdown
- Redaction: sensitive parameters (API keys, secrets) are masked in the detail view
- Payload truncation: large input/output payloads are truncated at a configurable limit (e.g., 10 KB displayed)

## Exit Criteria

- [ ] Workflow list loads and paginates with a server-side query
- [ ] Run list filters by workflow ID, paginates, and sorts by started-at descending
- [ ] Run detail shows step-by-step timeline with expandable input/output
- [ ] Sensitive fields marked with a metadata annotation are redacted (e.g., `****`)
- [ ] Payloads over the truncation limit show `[truncated N bytes]` with a raw download link
- [ ] Tests cover: list loading, pagination, redaction patterns, truncation boundary
