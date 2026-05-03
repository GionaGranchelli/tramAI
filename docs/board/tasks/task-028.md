# TASK-028: Add Timezone and Calendar-Aware Scheduling

- Status: planned
- Priority: medium
- Primary spec: [SPEC-013](../../specs/spec-013-scheduler.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Extend the schedule DSL with timezone support and a "skip on" calendar for
holidays and business-hours-only windows.

## Scope

- Add `zone` parameter to schedule DSL
- Add `skipCalendar` parameter with holiday set configuration
- Business-hours-only mode: skip execution outside configured hours
- Validate calendar config at build time
- Observable via `onSkippedTick` event with skip reason

## Exit Criteria

- [ ] `at("0 9 * * 1", zone = "Europe/Rome")` adjusts for timezone correctly
- [ ] `at("0 9 * * 1", skipOn = christmasCalendar)` skips Christmas
- [ ] Business-hours-only mode skips ticks outside 9-18 Mon-Fri
- [ ] Invalid timezone ID rejected at build time
