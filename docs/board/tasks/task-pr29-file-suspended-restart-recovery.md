# Task PR #29 — Encrypted FileSuspendedInvocationStore and Restart-Safe Recovery

## Status
**Complete** — Merged

## Branch
feat/file-suspended-invocation-restart-recovery

## Summary
Add the fourth encrypted file-backed sovereign store (`FileSuspendedInvocationStore`), a trusted replay-envelope persistence bridge, full bundle integration, and a JVM-restart-style integration test proving workflow resume after process termination.

## File Changes

### New files
1. `tramai-persistence-file/src/main/kotlin/dev/tramai/persistence/file/ReplayEnvelopePersistenceCodec.kt` — Trusted bridge exporting/restoring redacted Message snapshots
2. `tramai-persistence-file/src/main/kotlin/dev/tramai/persistence/file/FileSuspendedInvocationStore.kt` — AES-256-GCM encrypted store implementing `SuspendedInvocationStore`
3. `docs/board/tasks/task-pr29-file-suspended-restart-recovery.md` — this file

### Modified files
4. `tramai-persistence-file/build.gradle.kts` — add `api(project(":tramai-engine"))` dependency
5. `tramai-persistence-file/src/main/kotlin/dev/tramai/persistence/file/PersistedDtos.kt` — add suspended invocation V1 DTOs with domain conversions
6. `tramai-persistence-file/src/main/kotlin/dev/tramai/persistence/file/FileBackedSovereignStores.kt` — add `suspendedInvocationStore` to bundle, create `suspended/` subdir, verify on open
7. `docs/modules/tramai-persistence-file.md` — update to reflect PR #29 scope, shift deferred work
8. `docs/specs/spec-016-approval-engine-suspension-resume.md` — update status to include PR #29

## Implementation Details

### 1. Build dependency (build.gradle.kts)
Add: `api(project(":tramai-engine"))`
This is a one-way dependency: `tramai-engine` does NOT depend on `tramai-persistence-file`.

### 2. Persisted DTOs (PersistedDtos.kt)
Add V1 DTOs for all engine types needed:
- `PersistedSuspendedInvocationRecordV1` — top-level record
- `PersistedSuspendedInvocationMetadataV1` — all metadata fields
- `PersistedEngineExecutionIdentityV1`
- `PersistedExecutionSecurityContextV1`
- `PersistedResumeOperationReferenceV1`
- `PersistedResumeToolReferenceV1`
- `PersistedTokenBudgetSnapshotV1`
- `PersistedToolSecurityMetadataV1`
- `PersistedReplayEnvelopeV1`
- `PersistedMessageV1`
- `PersistedToolCallV1`
- `PersistedContentPartV1` (tagged: text/image/image_url)

Domain conversion functions: `toDomain()` and `toPersistedV1()` for each.

### 3. ReplayEnvelopePersistenceCodec
Narrow trusted bridge with two operations:
- `snapshotForPersistence(metadata, envelope): List<Message>` — validates all invariants (sentinel count, slot identity, digest binding) and returns the messages for DTO encoding
- `restoreFromPersistence(metadata, messages): SensitiveReplayEnvelope` — validates all invariants on the read side and returns a reconstructed opaque envelope

### 4. FileSuspendedInvocationStore
Follows the same pattern as `FileApprovalStore`:
- `RECORD_TYPE = "suspended-invocation"`
- `SUSPENDED_DIR = "suspended"`
- Filename: `sha256("suspended-invocation:" + approvalId).tram.enc`
- Create-only persistence via `FileStoreUtil.atomicEncryptCreate(...)`
- `verifyAll()` scans every file, decrypts, validates schema version, ID↔filename digest binding, replay digest, sentinel count
- All validation uses fixed reason codes (no interpolated values)
- Uses `FileStoreLease.withOpenOperation` for guarded access

### 5. FileBackedSovereignStores integration
- Add `suspendedInvocationStore: SuspendedInvocationStore` to the bundle
- Add `"suspended"` to `subdirs` list
- Instantiate `FileSuspendedInvocationStore(root, key, configuration, lease)`
- Verify on open when `verifyOnOpen = true`

### 6. Documentation updates
- `docs/modules/tramai-persistence-file.md`: update deferred work, add suspended/ to file layout
- `docs/specs/spec-016-approval-engine-suspension-resume.md`: mark PR #29 in status

## Key Design Decisions

- Use `atomicEncryptCreate` (not `atomicEncryptWrite`) — create-only, no replacement
- Never interpolate IDs into exception messages — fixed reason codes only
- Replay envelope is persisted as validated redacted Message snapshots, not runtime objects
- Raw selected tool arguments stay exclusively in `FileApprovalContinuationStore`

## Verification
```bash
./gradlew :tramai-persistence-file:compileKotlin
./gradlew :tramai-persistence-file:test --rerun-tasks
```
