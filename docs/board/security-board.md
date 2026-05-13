# Security Hardening Board (SPEC-018)

This board tracks security hardening work for TramAI, as defined in SPEC-018.

- Board owner: maintainer
- Last updated: 2026-05-13
- Related spec: [SPEC-018 Security Hardening](../specs/spec-018-security-hardening.md)

## How to Use This Board

- Spec defines requirements and acceptance criteria
- Tasks define concrete implementation work
- Board status reflects delivery progress
- Phases must be implemented in order

## Phase Dependency Graph

```text
Phase 1 (Secure Defaults)
    └── no dependencies
Phase 2 (Prompt Injection Defense)
    └── depends on Phase 1 command-policy model staying explicit
Phase 3 (Security Event Observability)
    └── depends on Phase 2 defense hooks and Phase 1 command-policy denials
```

## Status Legend

⬜ TODO | 🔄 IN PROGRESS | ✅ DONE | ❌ BLOCKED

---

## Phase 1 — Secure Defaults

Estimated effort: 1.5-2 days

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-040 | Flip `ShellStepConfig.allowedCommands` default to `emptySet()` and preserve explicit allowlist behavior | §Phase 1 | — | 0.5d | ⬜ |
| TASK-041 | Flip `McpStepConfig.allowedCommands` default to `emptySet()`, keep `deniedCommands`, and add `McpStepConfig.unrestricted()` | §Phase 1 | — | 0.5d | ⬜ |
| TASK-042 | Add runtime shell validation, build-time validation for static MCP commands only, and clear command-policy errors | §Phase 1 | TASK-040, TASK-041 | 0.5d | ⬜ |
| TASK-043 | Add/update migration tests and migration guide entries for secure-default changes, including direct `McpStepConfig()` users | §Phase 1 | TASK-040, TASK-041 | 0.5d | ⬜ |

## Phase 2 — Prompt Injection Defense Framework

Estimated effort: 4-5 days

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-044 | Define `PromptSanitizer`, `OutputValidator`, `InstructionDefense`, `ValidationResult`, and sealed `StepSecurityConfig` in `tramai-core`, with `InstructionDefense.wrap(...): String` returning a flat prompt string | §Phase 2 | — | 0.75d | ✅ |
| TASK-045 | Implement `DefaultPromptSanitizer` in `tramai-orchestration` with stable rule IDs and edge-case handling | §Phase 2 | TASK-044 | 0.5d | ✅ |
| TASK-046 | Implement `DefaultOutputValidator` in `tramai-orchestration`, including raw-text validation before structured parsing and truncation-focused false-positive coverage | §Phase 2 | TASK-044 | 0.75d | ✅ |
| TASK-047 | Implement `DefaultInstructionDefense` in `tramai-orchestration` using the flat-string delimiter template and custom-system-instruction extension points | §Phase 2 | TASK-044 | 0.5d | ✅ |
| TASK-048 | Wire the defense layer into `hermesStep` execution only | §Phase 2 | TASK-045, TASK-046, TASK-047 | 0.5d | ✅ |
| TASK-049 | Wire the defense layer into `codexStep` execution only | §Phase 2 | TASK-045, TASK-046, TASK-047 | 0.5d | ✅ |
| TASK-050 | Document `aiStep` as out of scope for framework-owned prompt defenses and clarify user responsibility inside `invoke` | §Phase 2 | TASK-044 | 0.25d | ✅ |
| TASK-051 | Add defense tests for jailbreaks, extraction attempts, partial opt-out, false positives, Unicode confusables, null bytes, multiline delimiter tricks, very large prompts, and truncated-output fragments | §Phase 2 | TASK-045, TASK-046, TASK-047, TASK-048, TASK-049 | 1.0d | ✅ |

## Phase 3 — Security Event Observability

Estimated effort: 1-1.5 days

| ID | Task | Spec § | Deps | Effort | Status |
|----|------|--------|------|--------|--------|
| TASK-052 | Add security event constants and expanded attribute schema to `WorkflowObserver` | §Phase 3 | TASK-045, TASK-046, TASK-047 | 0.25d | ✅ |
| TASK-053 | Emit `step_executed`, `sanitizer_triggered`, `command_denied`, and `output_rejected` from Hermes/Codex defense hooks and Shell/MCP command-policy denials | §Phase 3 | TASK-052, TASK-048, TASK-049 | 0.75d | ✅ |
| TASK-054 | Emit `output_rejected` when the typed `decode(String): T` lambda throws after validation passes, recording `reason = "parse_failure"` as best-effort decode-failure observability | §Phase 3 | TASK-046, TASK-052 | 0.25d | ✅ |
| TASK-055 | Add observer tests for expanded event attributes, decode-failure events, disabled/custom defense modes, and truncation-related validation behavior | §Phase 3 | TASK-053, TASK-054 | 0.5d | ✅ |

---

## Progress Tracking

| Phase | Status | Tasks |
|-------|--------|-------|
| Phase 1 — Secure Defaults | ⬜ Not started | 4 |
| Phase 2 — Prompt Injection Defense | ✅ Done | 8 |
| Phase 3 — Security Event Observability | ✅ Done | 4 |
| **Total** | | **16** |
