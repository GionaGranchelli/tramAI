package dev.tramai.orchestration

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

class FileStepAttemptRecordStore private constructor(
    private val rootDirectory: Path,
    private val atomicWriter: AtomicFileWriter,
) : StepAttemptRecordStore {
    constructor(rootDirectory: Path) : this(rootDirectory, realAtomicFileWriter)

    internal companion object {
        private const val ATTEMPT_SUFFIX = ".attempt.properties"
        private const val RECORD_HASH = "record_hash"

        fun forTest(rootDirectory: Path, atomicWriter: AtomicFileWriter): FileStepAttemptRecordStore =
            FileStepAttemptRecordStore(rootDirectory, atomicWriter)
    }

    override suspend fun recordStepAttempt(record: StepAttemptRecord): StepAttemptRecord {
        val path = attemptPath(record.runId, record.stepName, record.attemptId)
        return withFileLockCancellable(path) {
            atomicWriter.write(path, encodeStoredRecord(record))
            record
        }
    }

    override suspend fun updateStepAttempt(record: StepAttemptRecord): StepAttemptRecord {
        val path = attemptPath(record.runId, record.stepName, record.attemptId)
        return withFileLockCancellable(path) {
            if (!Files.exists(path)) {
                throw IllegalStateException(
                    "Step attempt '${record.attemptId}' for run '${record.runId}' and step '${record.stepName}' does not exist",
                )
            }
            readStoredRecord(path, record.runId, record.stepName, record.attemptId)
            atomicWriter.write(path, encodeStoredRecord(record))
            record
        }
    }

    override suspend fun compareAndSetStepAttempt(
        expected: StepAttemptRecord,
        updated: StepAttemptRecord,
    ): Boolean {
        if (expected.identity() != updated.identity()) return false
        val path = attemptPath(expected.runId, expected.stepName, expected.attemptId)
        return withFileLockCancellable(path) {
            val current = if (Files.exists(path)) {
                readStoredRecord(path, expected.runId, expected.stepName, expected.attemptId)
            } else {
                null
            }
            if (current != expected) {
                false
            } else {
                atomicWriter.write(path, encodeStoredRecord(updated))
                true
            }
        }
    }

    override suspend fun latestStepAttempt(runId: String, stepName: String): StepAttemptRecord? =
        listStepAttempts(runId)
            .filter { it.stepName == stepName }
            .maxWithOrNull(compareBy<StepAttemptRecord>({ it.startedAt }, { it.attemptId }))

    override suspend fun listStepAttempts(runId: String): List<StepAttemptRecord> {
        val runDirectory = rootDirectory.resolve(base64UrlEncodeNoPadding(runId))
        if (!Files.exists(runDirectory)) return emptyList()
        val paths = runInterruptible(Dispatchers.IO) {
            Files.walk(runDirectory).use { stream ->
                stream.filter(Files::isRegularFile)
                    .filter { it.fileName.toString().endsWith(ATTEMPT_SUFFIX) }
                    .toList()
            }
        }
        return paths.map { path ->
            val relative = runDirectory.relativize(path)
            if (relative.nameCount != 2) {
                throw StepAttemptRecordCorruptionException("Invalid step-attempt path '$path'")
            }
            val keyStepName = decodePathSegment(relative.getName(0).toString(), path)
            val fileName = relative.fileName.toString()
            val keyAttemptId = decodePathSegment(fileName.removeSuffix(ATTEMPT_SUFFIX), path)
            withFileLockCancellable(path) {
                if (!Files.exists(path)) {
                    null
                } else {
                    readStoredRecord(path, runId, keyStepName, keyAttemptId)
                }
            }
        }.filterNotNull().sortedWith(
            compareBy<StepAttemptRecord>({ it.startedAt }, { it.stepName }, { it.attemptId }),
        )
    }

    private fun attemptPath(runId: String, stepName: String, attemptId: String): Path = rootDirectory
        .resolve(base64UrlEncodeNoPadding(runId))
        .resolve(base64UrlEncodeNoPadding(stepName))
        .resolve(base64UrlEncodeNoPadding(attemptId) + ATTEMPT_SUFFIX)

    private fun readStoredRecord(path: Path, runId: String, stepName: String, attemptId: String): StepAttemptRecord {
        val payload = try {
            Files.readString(path)
        } catch (error: Exception) {
            throw StepAttemptRecordCorruptionException("Unable to read step-attempt record '$path'", error)
        }
        val properties = try {
            Properties().apply { load(payload.reader()) }
        } catch (error: Exception) {
            throw StepAttemptRecordCorruptionException("Invalid step-attempt properties in '$path'", error)
        }
        val record = StepAttemptRecordCodec.decode(payload)
        val storedHash = properties.getProperty(RECORD_HASH)
            ?: throw StepAttemptRecordCorruptionException("Missing record fingerprint in '$path'")
        StepAttemptRecordCodec.requireValidFingerprint(record, storedHash, "'$path'")
        if (record.identity() != AttemptIdentity(runId, stepName, attemptId)) {
            throw StepAttemptRecordCorruptionException("Step-attempt identity does not match storage path '$path'")
        }
        return record
    }

    private fun encodeStoredRecord(record: StepAttemptRecord): String =
        StepAttemptRecordCodec.encode(record) + "$RECORD_HASH=${StepAttemptRecordCodec.fingerprint(record)}\n"

    private fun decodePathSegment(value: String, path: Path): String = try {
        base64UrlDecode(value)
    } catch (error: IllegalArgumentException) {
        throw StepAttemptRecordCorruptionException("Invalid encoded identity in step-attempt path '$path'", error)
    }

}

internal fun base64UrlEncodeNoPadding(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

internal fun base64UrlDecode(value: String): String = String(
    Base64.getUrlDecoder().decode(value),
    StandardCharsets.UTF_8,
)

private data class AttemptIdentity(val runId: String, val stepName: String, val attemptId: String)

private fun StepAttemptRecord.identity(): AttemptIdentity = AttemptIdentity(runId, stepName, attemptId)
