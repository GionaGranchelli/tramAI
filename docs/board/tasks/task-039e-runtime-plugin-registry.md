# TASK-039E: Runtime Plugin Registry

- Status: planned
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Parent task: [TASK-039](../tasks/task-039.md)
- Last updated: 2026-05-03

## Purpose

Create a runtime plugin registry that discovers, loads, and manages plugins at application startup. Each plugin is described by a descriptor, discovered via JAR scanning on the classpath, and supports an enable/disable lifecycle with version compatibility checks.

## Scope

- Runtime plugin descriptor format (name, version, vendor, entry point, dependencies)
- Discovery via classpath JAR scanning for annotated or manifest-declared plugins
- Enable/disable lifecycle with start and stop callbacks
- Version compatibility checks — reject plugins that target an incompatible platform version
- Hot-reload consideration: registry is populated at boot; dynamic reload is out of scope for v1

## Exit Criteria

- [ ] Plugin descriptor is a Kotlin data class with name, version, vendor, entryPoint, and minPlatformVersion
- [ ] JAR scanning discovers plugins from META-INF/services or a custom annotation
- [ ] Disabled plugins are registered but never started
- [ ] A plugin declaring minPlatformVersion > current version is rejected with a clear error
- [ ] Tests cover: discovery, enable, disable, version mismatch rejection
