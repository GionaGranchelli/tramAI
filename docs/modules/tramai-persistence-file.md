# Module: `tramai-persistence-file`

> **One-liner:** Encrypted-at-rest, single-node file persistence for TramAI sovereign state — `FileApprovalStore`, `FileApprovalContinuationStore`, `FileSuspendedInvocationStore`, and `FileAuditStore` backed by AES-256-GCM encrypted files on a POSIX filesystem.
> **Module type:** `persistence` + `storage`
> **Dependencies:** `tramai-core`, `tramai-engine`, `tramai-security`
> **Source files:** 15 files + new files

## Purpose

`tramai-persistence-file` provides durable, encrypted-at-rest storage for the three sovereign store SPIs — `ApprovalStore`, `ApprovalContinuationStore`, and `AuditStore` — using the local filesystem. It is designed for single-node sovereign TramAI deployments that need persistent state without a database or cloud service dependency.

All persisted data is authenticated-encrypted with AES-256-GCM. Ciphertext, nonces, and keys never appear in filenames or directory structure. The module enforces strict POSIX permissions, rejects symlinks, and acquires an exclusive file lock to prevent concurrent JVM processes from accessing the same store directory.

## Threat Model

- **Data at rest**: All persisted data is authenticated-encrypted with AES-256-GCM. Ciphertext, nonces, and keys never appear in filenames or directory structure.
- **Filesystem access**: Unix user with read access to the store directory can see file names (SHA-256 digests) and encrypted blobs, but cannot decrypt without the key.
- **Active tampering**: GCM authentication tag detects ciphertext modification. Additional Authenticated Data (AAD) binds each record to its record type and key digest, preventing record substitution.
- **Process isolation**: Exclusive file lock prevents multiple JVM processes from accessing the same store directory concurrently.
- **Not a threat model for**: Network attacks, multi-user filesystem access controls, key management infrastructure, side-channel attacks.

## Encryption

- **Algorithm**: AES-256-GCM (`AES/GCM/NoPadding` in JCA)
- **Key size**: 256 bits
- **Nonce**: 96-bit (12 bytes), fresh `SecureRandom` per write
- **Authentication tag**: 128 bits (16 bytes)
- **AAD format**: `tramAI-file-store|envelope-v1|<recordType>|<recordKeyDigest>|<keyId>`

### Envelope format (v1)

```json
{
  "envelopeVersion": 1,
  "recordType": "approval-request",
  "recordKeyDigest": "abcdef...",
  "keyId": "my-key-2026",
  "nonceBase64": "...",
  "ciphertextBase64": "..."
}
```

Key ownership is the caller's responsibility. The module provides `FileStoreEncryptionKeyProvider` as a functional interface for key resolution. Keys are never persisted, logged, or included in any output.

## File Layout

```
<tramai-root>/
├── .tramai.lock              # Exclusive POSIX file lock
├── manifest.json             # Store metadata (format version, creation time)
├── approvals/
│   └── <sha256-hex>.tram.enc # One encrypted file per approval request
├── continuations/
│   └── <sha256-hex>.tram.enc # One encrypted file per continuation
├── suspended/
│   └── <sha256-hex>.tram.enc # One encrypted file per suspended invocation
└── audit/
    └── <sha256-stream-id>/
        ├── 00000000000000000001-<sha256-event-id>.tram.enc
        └── 00000000000000000002-<sha256-event-id>.tram.enc
```

Filenames are SHA-256 hex digests of `"<recordType>:<stableRecordId>"` — never raw record IDs.

## Permissions and Locking

| Mechanism | Detail |
|-----------|--------|
| Directory permissions | `0700` (`drwx------`) |
| File permissions | `0600` (`-rw-------`) |
| Symlinks | Rejected at every managed path |
| Cross-process lock | Exclusive `.tramai.lock` acquired on `open()` |
| Same-process guard | Second `open()` for the same root is rejected |
| Per-record lock | `ReentrantLock` for approvals and continuations |
| Per-stream lock | `ReentrantLock` for audit streams |

## Atomic Writes

Mutable writes (approvals, continuations) follow:

1. Validate and encode the record
2. Encrypt with AES-256-GCM (fresh nonce per write)
3. Write to a temporary sibling file with atomic 0600 permissions
4. Flush to disk
5. `ATOMIC_MOVE` to the committed location
6. Best-effort cleanup of the temp file

**Immutable audit events** use a direct `CREATE_NEW` write instead of `ATOMIC_MOVE`. This guarantees create-only semantics even on platforms where `ATOMIC_MOVE` without `REPLACE_EXISTING` is implementation-specific. The encrypted envelope is written directly to the target path with `CREATE_NEW + DSYNC`, atomic 0600 permissions, and fsync. Silent replacement is never possible.

## Public API

### `FileBackedStoreConfiguration`

```kotlin
data class FileBackedStoreConfiguration(
    val rootDirectory: Path,
    val encryption: FileStoreEncryptionConfiguration,
    val verifyOnOpen: Boolean = true,
)
```

### `FileStoreEncryptionConfiguration`

```kotlin
data class FileStoreEncryptionConfiguration(
    val activeKeyId: String,
    val keyProvider: FileStoreEncryptionKeyProvider,
)
```

### `FileStoreEncryptionKeyProvider`

```kotlin
fun interface FileStoreEncryptionKeyProvider {
    fun resolve(keyId: String): SecretKey
}
```

### `FileBackedSovereignStores`

```kotlin
class FileBackedSovereignStores(
    val approvalStore: ApprovalStore,
    val approvalContinuationStore: ApprovalContinuationStore,
    val auditStore: AuditStore,
) : AutoCloseable {

    companion object {
        fun open(configuration: FileBackedStoreConfiguration): FileBackedSovereignStores
    }

    override fun close()
}
```

### `FileApprovalStore`

Implements `ApprovalStore` with encrypted atomic file persistence. Each approval request is stored as a single encrypted file under `{root}/approvals/<sha256>.tram.enc`. Serialises reads, mutations, and writes per approval ID via `ReentrantLock` for atomic read-modify-write semantics.

### `FileApprovalContinuationStore`

Implements `ApprovalContinuationStore` with exactly-once claim semantics. Raw sensitive tool arguments are stored alongside the continuation metadata. On `claimForExecution`, the arguments are atomically released exactly once — the record is re-encrypted with `arguments = null` and the raw arguments are returned via `ClaimedApprovalContinuation`.

Supports lazy expiry (auto-transitions `PENDING` records past their `approvalExpiresAt` to `EXPIRED` on read or mutation). `CLAIMED` records must never lazy-expire — the side effect may have started.

#### State machine

```
PENDING (arguments present)
  → claimForExecution → CLAIMED (arguments = null, exactly once)
  → expire → EXPIRED
  → cancel → CANCELLED

CLAIMED (arguments already released)
  → complete → COMPLETED
  → forceCancelClaimed → CANCELLED_UNCERTAIN (with recovery metadata)

COMPLETED / EXPIRED / CANCELLED / CANCELLED_UNCERTAIN → terminal
```

CLAIMED must never use lazy expiry or ordinary cancel — the side effect may have started.

### `FileAuditStore`

Implements `AuditStore` with per-event encrypted files, hash chain validation, and stream ordering. Each append validates sequence continuity (increments by exactly 1), hash chain (`previousEventHash` matches the previous event's `eventHash`), event hash integrity, schema version support, and unique `eventId` within the stream.

Supports full stream reads with chain integrity validation, and `verifyAll()` for startup verification.

### `FileStoreException` hierarchy

| Exception | When thrown |
|-----------|-------------|
| `FileStoreConfigurationException` | Invalid configuration values |
| `FileStoreLockUnavailableException` | Another process holds the lock |
| `FileStorePermissionException` | Wrong filesystem permissions or symlink detected |
| `FileStoreCorruptionException` | Decryption failure, tampered data, chain validation failure |
| `FileStoreUnsupportedFormatException` | Unknown envelope or manifest format version |

**Security invariant:** Exception messages contain safe reason codes only. Never embed raw record IDs, workflow IDs, tool arguments, keys, ciphertext, or nonces in exception messages.

## Usage

```kotlin
val key = loadMySecretKey() // caller-owned key management

val stores = FileBackedSovereignStores.open(
    FileBackedStoreConfiguration(
        rootDirectory = Path.of("/var/tramai/sovereign"),
        encryption = FileStoreEncryptionConfiguration(
            activeKeyId = "production-key-1",
            keyProvider = FileStoreEncryptionKeyProvider { keyId -> loadKey(keyId) },
        ),
        verifyOnOpen = true,
    )
)

// Use stores...
stores.approvalStore.create(request)
stores.approvalContinuationStore.create(continuation, arguments)
stores.auditStore.appendNext(streamId) { latest -> /* build event */ }

// Close on shutdown
stores.close()
```

## Open Sequence (FileBackedSovereignStores.open)

1. Create root directory with `0700` permissions if absent.
2. Verify root is a directory, not a symlink, with `0700` permissions.
3. Acquire exclusive lock on `.tramai.lock` (throws `FileStoreLockUnavailableException` if held).
4. Create subdirectories (`approvals/`, `continuations/`, `suspended/`, `audit/`) with `0700` permissions.
5. Validate or create `manifest.json` (format version must be 1).
6. Resolve the encryption key.
7. Construct and wire the four file-backed store stubs.
8. If `verifyOnOpen` is true, verify all existing records.
9. On any failure, release the lock before the exception propagates.

## Limitations

- **Single-node Linux/POSIX only** — requires `java.nio.file.attribute.PosixFilePermissions`
- No network filesystem support
- No multi-process writers
- No distributed locking
- No key rotation
- No JDBC / PostgreSQL / cloud KMS integration

## Deferred Work

- **PR #30**: Local-model artifact manifest and byte-level verification
- **PR #31**: Offline runtime profile and zero-egress verification harness
