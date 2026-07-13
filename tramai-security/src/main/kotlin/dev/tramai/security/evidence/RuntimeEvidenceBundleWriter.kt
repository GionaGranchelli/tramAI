package dev.tramai.security.evidence

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/**
 * Writes [RuntimeEvidenceRecord] lists into a sovereign evidence bundle's
 * `runtime-evidence/` section, grouping records by event type into the
 * documented JSONL files.
 *
 * ## Event-to-file mapping
 *
 * | eventType          | File                        |
 * |--------------------|-----------------------------|
 * | policy.decision    | policy-decisions.jsonl      |
 * | approval.decision  | approval-decisions.jsonl    |
 * | provider.route     | provider-routing.jsonl      |
 *
 * ## Fail-closed validation
 *
 * Before writing anything, the entire record set is validated by
 * [RuntimeEvidenceContractValidator]. The validator enforces both the
 * structural rules (schema version, event type, decision kind, digest format)
 * and the privacy/semantic rules (source component, metadata allowlists,
 * reason code format, strict key sets) so the writer can never produce a
 * section that the verifier would reject.
 *
 * ## Transactional section replacement
 *
 * The writer uses a unique temporary sibling directory (outside the bundle
 * directory) and a backup-based transactional strategy:
 *
 * 1. A unique temporary directory is created as a sibling of the bundle root.
 * 2. New evidence is written into the temporary directory.
 * 3. If `runtime-evidence/` exists, it is moved to `runtime-evidence.bak/`.
 * 4. The temporary directory is moved to `runtime-evidence/`.
 * 5. On success, the backup is deleted.
 * 6. On failure, the backup is restored to `runtime-evidence/`.
 *
 * Before any move, the state of target and backup is evaluated:
 *
 * | Target | Backup | Action |
 * |--------|--------|--------|
 * | Exists | Absent | Normal: backup target, replace, delete backup |
 * | Absent | Exists | Recovery: restore backup, then proceed |
 * | Exists | Exists | Ambiguous: validate target, delete backup, proceed |
 * | Absent | Absent | No previous section: just replace |
 *
 * This guarantees that the completed previous section remains recoverable
 * until the replacement has succeeded.
 *
 * ## Empty input
 *
 * An empty record list is rejected — callers that have no runtime
 * evidence should simply not invoke the writer.
 */
class RuntimeEvidenceBundleWriter {

    /**
     * Writes the given records into the bundle's `runtime-evidence/`
     * directory, replacing any existing section transactionally.
     *
     * @param bundleDirectory root directory of the evidence bundle
     * @param records runtime evidence records to write
     * @return result describing what was written
     * @throws IllegalArgumentException if input is empty or fails validation
     * @throws IOException if filesystem operations fail
     */
    @Throws(IllegalArgumentException::class, IOException::class)
    fun write(
        bundleDirectory: Path,
        records: List<RuntimeEvidenceRecord>,
    ): RuntimeEvidenceBundleWriteResult {
        require(records.isNotEmpty()) {
            "Runtime evidence record list must not be empty"
        }

        // Normalize bundle directory to handle relative single-segment paths
        val normalizedBundleDir = bundleDirectory.toAbsolutePath().normalize()
        val tempParent = requireNotNull(normalizedBundleDir.parent) {
            "bundleDirectory must have a parent directory for temp file placement: $bundleDirectory"
        }

        // Fail closed: require a valid evidence bundle root.
        requireBundleRoot(normalizedBundleDir)

        RuntimeEvidenceContractValidator.validate(records)

        val grouped = records
            .sortedWith(compareBy(RuntimeEvidenceRecord::createdAt, RuntimeEvidenceRecord::eventId))
            .groupBy { it.eventType }

        // Use a unique temporary sibling directory OUTSIDE the bundle root
        // so a crashed temp dir can never be mistaken for legitimate evidence.
        val runtimeEvidenceDir = normalizedBundleDir.resolve(RUNTIME_EVIDENCE_DIR)
        val tempDir = Files.createTempDirectory(
            tempParent,
            ".${RUNTIME_EVIDENCE_DIR}-",
        )

        try {
            val writtenFiles = mutableListOf<Path>()
            val countsByEventType = mutableMapOf<String, Int>()

            for ((eventType, typedRecords) in grouped) {
                val filename = requireNotNull(EVENT_FILES[eventType]) {
                    "Unknown event type: $eventType"
                }
                val jsonlContent = RuntimeEvidenceJsonlWriter.write(typedRecords)
                val filePath = tempDir.resolve(filename)
                Files.writeString(filePath, jsonlContent, StandardCharsets.UTF_8)
                writtenFiles.add(Path.of(filename))
                countsByEventType[eventType] = typedRecords.size
            }

            // Transactional replace of the runtime-evidence directory
            replaceDirectoryTransactionally(tempDir, runtimeEvidenceDir)

            return RuntimeEvidenceBundleWriteResult(
                runtimeEvidenceDirectory = runtimeEvidenceDir,
                writtenFiles = writtenFiles,
                countsByEventType = countsByEventType,
            )
        } catch (e: Exception) {
            // Clean up temp directory on failure
            try {
                deleteSafely(tempDir)
            } catch (_: Exception) {
                // Best-effort cleanup
            }
            throw e
        }
    }

    /**
     * Validates that [bundleDirectory] contains a valid sovereign-lab
     * evidence bundle manifest.
     */
    private fun requireBundleRoot(bundleDirectory: Path) {
        val manifestPath = bundleDirectory.resolve("manifest.json")
        require(Files.exists(bundleDirectory) && Files.isDirectory(bundleDirectory)) {
            "bundleDirectory must exist and be a directory: $bundleDirectory"
        }
        require(Files.exists(manifestPath) && Files.isRegularFile(manifestPath)) {
            "bundleDirectory must contain manifest.json: $manifestPath"
        }
        require(!Files.isSymbolicLink(manifestPath)) {
            "manifest.json must not be a symbolic link"
        }

        val manifestContent = Files.readString(manifestPath)
        val bundleType = ManifestJsonReader.readString(manifestContent, "bundleType")
        require(bundleType == "sovereign-lab-evidence-bundle") {
            "manifest.json must declare bundleType " +
                "\"sovereign-lab-evidence-bundle\", got: $bundleType"
        }
    }

    /**
     * Replaces [target] directory with [source] directory transactionally,
     * using a state machine that handles crash recovery.
     *
     * Before any move, the state of target and backup is evaluated:
     *
     * | Target | Backup | Action |
     * |--------|--------|--------|
     * | Exists | Absent | Normal: backup target, replace, delete backup |
     * | Absent | Exists | Recovery: restore backup, then proceed |
     * | Exists | Exists | Both preserved: fail closed for manual recovery |
     * | Absent | Absent | No previous section: just replace |
     */
    private fun replaceDirectoryTransactionally(source: Path, target: Path) {
        val backup = target.resolveSibling("$RUNTIME_EVIDENCE_DIR.bak")

        val targetExists = Files.exists(target)
        val backupExists = Files.exists(backup)

        when {
            // Recovery: a previous replacement was interrupted after
            // moving target to backup but before completing the move.
            !targetExists && backupExists -> {
                atomicMoveOrFallback(backup, target)
            }

            // Ambiguous: both exist — fail closed. No heuristic can
            // distinguish a valid target from a superficially plausible
            // one, so preserving both is the only safe action.
            targetExists && backupExists -> {
                error(
                    "Ambiguous runtime-evidence recovery state: " +
                        "both runtime-evidence/ and runtime-evidence.bak/ exist. " +
                        "Both directories are preserved for manual recovery. " +
                        "Backup: $backup"
                )
            }

            // Normal: clean stale backup, proceed
            targetExists && !backupExists -> {
                // No stale backup to clean — normal case
            }

            // No previous section
            !targetExists && !backupExists -> {
                // Nothing to do
            }
        }

        // Phase 1: move existing target to backup
        if (Files.exists(target)) {
            // Note: we check again because recovery may have restored target
            atomicMoveOrFallback(target, backup)
        }

        // Phase 2: move new source to target
        try {
            atomicMoveOrFallback(source, target)
        } catch (e: Exception) {
            // Phase 3: restore backup on failure
            try {
                if (Files.exists(backup)) {
                    if (Files.exists(target)) {
                        deleteSafely(target)
                    }
                    atomicMoveOrFallback(backup, target)
                }
            } catch (_: Exception) {
                // Best-effort restore — original content is in backup/
                // Target may be empty or partially written
            }
            throw e
        }

        // Phase 4: delete backup on success
        if (Files.exists(backup)) {
            deleteSafely(backup)
        }
    }

    /**
     * Moves [source] to [target] with `ATOMIC_MOVE` if supported,
     * falling back to a regular move.
     */
    private fun atomicMoveOrFallback(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    /**
     * Deletes a directory tree without following symbolic links.
     * Rejects any symlink encountered.
     */
    private fun deleteSafely(path: Path) {
        Files.walkFileTree(
            path,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink || Files.isSymbolicLink(file)) {
                        throw IOException(
                            "Symlink detected in evidence directory, rejecting: $file"
                        )
                    }
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    internal companion object {
        internal const val RUNTIME_EVIDENCE_DIR = "runtime-evidence"
        internal const val SCHEMA_VERSION = "runtime-evidence.v1"

        internal val EVENT_FILES = mapOf(
            "policy.decision" to "policy-decisions.jsonl",
            "approval.decision" to "approval-decisions.jsonl",
            "provider.route" to "provider-routing.jsonl",
            "tool.permission" to "tool-permissions.jsonl",
        )

        internal val ALLOWED_DECISION_KINDS = mapOf(
            "policy.decision" to setOf("ALLOW", "DENY", "REQUIRE_APPROVAL"),
            "approval.decision" to setOf("APPROVED", "DENIED"),
            "provider.route" to setOf("SELECTED", "FALLBACK", "BLOCKED"),
            "tool.permission" to setOf("ALLOW", "DENY", "REQUIRE_APPROVAL"),
        )

        /**
         * Expected source.component values per event type.
         */
        internal val EXPECTED_SOURCE_COMPONENTS = mapOf(
            "policy.decision" to "policy-engine",
            "approval.decision" to "approval-control-plane",
            "provider.route" to "provider-router",
            "tool.permission" to "policy-engine",
        )

        /**
         * Allowlisted metadata keys per event family.
         */
        internal val ALLOWED_METADATA_KEYS = mapOf(
            "policy.decision" to setOf(
                "providerName", "modelName", "toolName", "classification",
                "classificationSource", "riskLevel", "fallbackProviderName",
                "attr_cacheReuse", "attr_fallbackReason",
            ),
            "approval.decision" to setOf(
                "approvalVersion", "reasonDigest", "reasonLength",
                "outboxStatus", "eventKeyDigest",
            ),
            "provider.route" to setOf(
                "requestedModelDigest", "selectedProviderDigest", "selectedModelDigest",
                "previousProviderDigest", "previousModelDigest", "routeIndex",
                "attempt", "fallbackReason",
            ),
            "tool.permission" to setOf(
                "toolName", "enforcementPoint", "riskLevel",
                "classification", "classificationSource",
            ),
        )

        internal val DIGEST_REGEX = Regex("^sha256:[0-9a-f]{64}$")

        /**
         * Regex for sanitised reason codes: code-shaped values only.
         */
        internal val REASON_CODE_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$")
    }
}

/**
 * Result of a [RuntimeEvidenceBundleWriter.write] call.
 */
data class RuntimeEvidenceBundleWriteResult(
    /** Path to the `runtime-evidence/` directory within the bundle. */
    val runtimeEvidenceDirectory: Path,
    /** Written file names relative to [runtimeEvidenceDirectory]. */
    val writtenFiles: List<Path>,
    /** Count of records written per event type. */
    val countsByEventType: Map<String, Int>,
)
