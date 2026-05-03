# TASK-028: Add Timezone and Calendar-Aware Scheduling

- Status: done
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
- Business-hours-only mode: skip execution outside configured hours (09:00-18:00 Mon-Fri)
- Calendar rule types: `FixedDate`, `NthWeekdayOfMonth`, `DateRange`
- Validate calendar config at build time
- Observable via `onSkippedTick` event with skip reason
- Convenience functions: `dailyAt()`, `every()` for interval-based scheduling
- JDBC persistence for `skipCalendar` and `businessHoursOnly` metadata
- JSON round-trip encoding for calendar rules
- Transaction rollback-on-commit-failure preservation
- Lock-contention mitigation: `nextFireAfter()` computed outside synchronized block

## Exit Criteria

- [x] `at("0 9 * * 1", zone = "Europe/Rome")` adjusts for timezone correctly
- [x] `at("0 9 * * *", skipCalendar = listOf(FixedDate(12, 25)))` skips Christmas
- [x] Business-hours-only mode skips ticks outside 09:00-18:00 Mon-Fri
- [x] Invalid timezone ID rejected at build time
- [x] Calendar rules validate month/day at build time (e.g., Feb 29 rejected)
- [x] `NthWeekdayOfMonth` skips specific weekdays (e.g., 3rd Monday of December)
- [x] `every(5, ChronoUnit.MINUTES)` fires every 5 minutes
- [x] `dailyAt(hour = 9, minute = 30)` creates correct cron expression
- [x] JDBC round-trips calendar rules and business hours mode
- [x] DST transitions handled (spring-forward gap, fall-back overlap)
- [x] Business hours + calendar rule interaction emits correct skip sequence
- [x] Malformed calendar rule payloads rejected at deserialization
- [x] `every()` validates amount >= 1 and within Int range
