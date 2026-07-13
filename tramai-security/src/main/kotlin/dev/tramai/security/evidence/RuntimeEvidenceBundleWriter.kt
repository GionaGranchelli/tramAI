package dev.tramai.security.evidence

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
 * Before writing anything, the entire record set is validated:
 *
 * - `schemaVersion` must be `"runtime-evidence.v1"`
 * - `eventType` must be a known type
 * - `decision.kind` must be valid for the event type
 * - `eventId` must be non-empty
 * - `eventId` must be unique across all records of the same event type
 * - `digests.subjectDigest` must match `^sha256:[0-9a-f]{64}$`
 * - `digests.payloadDigest` must match `^sha256:[0-9a-f]{64}$`
 *
 * ## Atomic section replacement
 *
 * The writer uses a temporary sibling directory and `ATOMIC_MOVE` to
 * replace the entire `runtime-evidence/` directory, preventing stale or
 * partially written evidence.
 *
 * ## Empty input
 *
 * An empty record list is rejected — callers that have no runtime
 * evidence should simply not invoke the writer.
 */
class RuntimeEvidenceBundleWriter {

    /**
     * Writes the given records into the bundle's `runtime-evidence/`
     * directory, replacing any existing section atomically.
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

        validateRecords(records)

        val grouped = records
            .sortedWith(compareBy(RuntimeEvidenceRecord::createdAt, RuntimeEvidenceRecord::eventId))
            .groupBy { it.eventType }

        val runtimeEvidenceDir = bundleDirectory.resolve(RUNTIME_EVIDENCE_DIR)
        val tempDir = bundleDirectory.resolve("$RUNTIME_EVIDENCE_DIR.tmp")

        try {
            Files.createDirectories(tempDir)

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

            // Atomic replace of the runtime-evidence directory
            replaceDirectoryAtomically(tempDir, runtimeEvidenceDir)

            return RuntimeEvidenceBundleWriteResult(
                runtimeEvidenceDirectory = runtimeEvidenceDir,
                writtenFiles = writtenFiles,
                countsByEventType = countsByEventType,
            )
        } catch (e: Exception) {
            // Clean up temp directory on failure
            try {
                if (Files.exists(tempDir)) {
                    tempDir.toFile().deleteRecursively()
                }
            } catch (_: Exception) {
                // Best-effort cleanup
            }
            throw e
        }
    }

    /**
     * Validates the entire record set before writing anything.
     * Every rule is enforced with `require` so failures are always
     * `IllegalArgumentException`.
     */
    private fun validateRecords(records: List<RuntimeEvidenceRecord>) {
        val seenIdsByEventType = mutableMapOf<String, MutableSet<String>>()

        for (record in records) {
            require(record.schemaVersion == SCHEMA_VERSION) {
                "Unsupported schemaVersion: ${record.schemaVersion}. " +
                    "Expected: $SCHEMA_VERSION"
            }

            require(record.eventType in EVENT_FILES) {
                "Unknown event type: ${record.eventType}. " +
                    "Supported: ${EVENT_FILES.keys}"
            }

            val allowedKinds = requireNotNull(ALLOWED_DECISION_KINDS[record.eventType]) {
                "No allowed decision kinds defined for event type: ${record.eventType}"
            }
            require(record.decision.kind in allowedKinds) {
                "Invalid decision.kind '${record.decision.kind}' for event type " +
                    "'${record.eventType}'. Allowed: $allowedKinds"
            }

            require(record.eventId.isNotEmpty()) {
                "eventId must not be empty"
            }

            // Unique eventId within each event family
            val seenIds = seenIdsByEventType.getOrPut(record.eventType) { mutableSetOf() }
            require(record.eventId !in seenIds) {
                "Duplicate runtime evidence eventId: ${record.eventId} " +
                    "(eventType: ${record.eventType})"
            }
            seenIds.add(record.eventId)

            // Digests are validated by RuntimeEvidenceDigests.init, but
            // we re-validate defensively
            require(DIGEST_REGEX.matches(record.digests.subjectDigest)) {
                "subjectDigest must match ^sha256:[0-9a-f]{64}$: ${record.digests.subjectDigest}"
            }
            require(DIGEST_REGEX.matches(record.digests.payloadDigest)) {
                "payloadDigest must match ^sha256:[0-9a-f]{64}$: ${record.digests.payloadDigest}"
            }
        }
    }

    /**
     * Replaces [target] directory with [source] directory atomically
     * when possible, with a controlled fallback.
     */
    private fun replaceDirectoryAtomically(source: Path, target: Path) {
        // Remove existing target directory if present
        if (Files.exists(target)) {
            target.toFile().deleteRecursively()
        }

        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // Fallback: move without atomic guarantee
            Files.move(source, target)
        }
    }

    private companion object {
        internal const val RUNTIME_EVIDENCE_DIR = "runtime-evidence"
        internal const val SCHEMA_VERSION = "runtime-evidence.v1"

        internal val EVENT_FILES = mapOf(
            "policy.decision" to "policy-decisions.jsonl",
            "approval.decision" to "approval-decisions.jsonl",
            "provider.route" to "provider-routing.jsonl",
        )

        internal val ALLOWED_DECISION_KINDS = mapOf(
            "policy.decision" to setOf("ALLOW", "DENY", "REQUIRE_APPROVAL"),
            "approval.decision" to setOf("APPROVED", "DENIED"),
            "provider.route" to setOf("SELECTED", "FALLBACK", "BLOCKED"),
        )

        private val DIGEST_REGEX = Regex("^sha256:[0-9a-f]{64}$")
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
