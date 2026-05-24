# Secure Defaults Migration Guide

**Applies to:** TramAI 0.3.1+
**Change:** `ShellStepConfig` and `McpStepConfig` now require explicit `allowedCommands` configuration.
**Related spec:** SPEC-018 Phase 1 — Secure Defaults

---

## What Changed

Prior to this version, `ShellStepConfig()` and `McpStepConfig()` had no default command allowlist, meaning any command was allowed by default. This was a security gap for production environments handling untrusted data.

Starting with TramAI 0.3.1:

- `ShellStepConfig.allowedCommands` defaults to `emptySet()` — **deny-all**
- `McpStepConfig.allowedCommands` defaults to `emptySet()` — **deny-all**
- `McpStepConfig.unrestricted()` exists as an explicit escape hatch for workflows that intentionally need unrestricted command execution

---

## Do You Need to Migrate?

Run this check:

```kotlin
// If your code looks like this (no allowedCommands = ...):
shellStep(
    name = "git-clone",
    command = { _, _ -> ShellCommand(listOf("git", "clone", repo)) },
    merge = { state, result, _ -> state.copy(cloneResult = result) },
)

// Or this (McpStepConfig() without allowedCommands):
mcpStep(
    name = "my-tool",
    config = McpStepConfig(),
    definition = McpToolCallDefinition(...),
    toolCall = { _, _ -> McpToolCall(...) },
    merge = { state, result, _ -> state.copy(result = result) },
)
```

Then **yes**, you need to migrate. Your workflows will fail at execution time with:

```
Workflow shell step 'git-clone' command is not in allowlist
```

or for MCP steps:

```
Workflow MCP step 'my-tool' command is not in allowlist
```

---

## Migration Path

### Option 1: Explicit Allowlist (Recommended)

The safest approach. List every command your workflow legitimately needs:

```kotlin
// Shell — before (UNRESTRICTED, will now fail):
shellStep(
    name = "git-clone",
    config = ShellStepConfig(),  // ❌ allowedCommands defaults to emptySet()
    ...
)

// Shell — after (RESTRICTED, secure):
shellStep(
    name = "git-clone",
    config = ShellStepConfig(allowedCommands = setOf("git")),
    ...
)

// MCP — before (UNRESTRICTED, will now fail):
mcpStep(
    name = "my-tool",
    config = McpStepConfig(),  // ❌ allowedCommands defaults to emptySet()
    ...
)

// MCP — after (RESTRICTED, secure):
mcpStep(
    name = "my-tool",
    config = McpStepConfig(allowedCommands = setOf("node")),
    ...
)
```

### Option 2: Unrestricted Escape Hatch (During Migration Only)

If you need the old unrestricted behavior temporarily during migration, use `McpStepConfig.unrestricted()`:

```kotlin
mcpStep(
    name = "my-tool",
    config = McpStepConfig.unrestricted(),  // ⚠️ disables command allowlist enforcement
    ...
)
```

> **Note:** `McpStepConfig.unrestricted()` still applies the denylist (`deniedCommands`). It only disables the allowlist enforcement. This is the explicit opt-in for the old unrestricted behavior.

For `ShellStep`, there is no `unrestricted()` method. Use an explicit allowlist covering the commands you need, or use `allowedCommands = setOf("*")` as a last resort during migration (documented below).

### Option 3: Broad Allowlist (Migration Shortcut)

If you cannot enumerate all commands immediately, use a broad allowlist as a temporary measure:

```kotlin
shellStep(
    name = "any-command",
    config = ShellStepConfig(allowedCommands = setOf("*")),  // ⚠️ allows everything
    ...
)
```

This is less secure than a narrow allowlist but preserves existing behavior. Replace with a narrow allowlist in your next sprint.

---

## Reference: Common Allowlist Patterns

```kotlin
// Single command
ShellStepConfig(allowedCommands = setOf("git"))
ShellStepConfig(allowedCommands = setOf("sh"))

// Multiple commands
ShellStepConfig(allowedCommands = setOf("git", "node", "docker"))

// Any command (not recommended for production)
ShellStepConfig(allowedCommands = setOf("*"))

// MCP subprocess command
McpStepConfig(allowedCommands = setOf("node"))
McpStepConfig(allowedCommands = setOf("python3", "uvx"))

// Allowlist + denylist work together:
// If a command matches both, the denylist wins
ShellStepConfig(allowedCommands = setOf("git"), deniedCommands = setOf("git-push"))
```

---

## Validation Timing

| Step Type | Allowlist Check | When |
|-----------|----------------|------|
| ShellStep | Runtime | At execution, because the command is produced by a state-derived lambda |
| McpStep (static command) | Build-time | During workflow construction — fails early with a clear error |
| McpStep (dynamic command) | Runtime | At execution, if the command is assembled from workflow state |

---

## What If I Forget to Migrate?

Your build will still compile successfully. The change is enforced at runtime:

```text
Exception in thread "main" dev.tramai.orchestration.WorkflowShellException:
 Workflow shell step 'git-clone' command is not in allowlist
```

This failure is deliberate. It prevents silent regression from restricted to unrestricted behavior. Once you add the `allowedCommands` configuration, the workflow will execute normally.

---

## Rollback

If you need to revert this change temporarily, pin your TramAI version to an older release that did not enforce command allowlists. Do not leave this in place longer than necessary — the unrestricted default was a known security gap.
