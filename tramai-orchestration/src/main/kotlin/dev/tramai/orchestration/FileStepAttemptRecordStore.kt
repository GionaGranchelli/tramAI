package dev.tramai.orchestration

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

class FileStepAttemptRecordStore internal constructor(
    private val rootDirectory: Path,
    private val atomicWriter: AtomicFileWriter,
) : StepAttemptRecordStore {
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver =
        NoOpPersistenceFailureDiagnosticObserver
        internal set

    constructor(rootDirectory: Path) : this(rootDirectory, realAtomicFileWriter)

    constructor(rootDirectory: Path, observer: PersistenceFailureDiagnosticObserver) :
        this(rootDirectory, realAtomicFileWriter) {
        persistenceFailureDiagnosticObserver = observer
    }

    internal companion object {
        private const val ATTEMPT_SUFFIX = ".attempt.properties"
        private const val SEQUENCE_SUFFIX = ".attempt-sequence"
        private const val RECORD_HASH = "record_hash"

        fun forTest(rootDirectory: Path, atomicWriter: AtomicFileWriter): FileStepAttemptRecordStore =
            FileStepAttemptRecordStore(rootDirectory, atomicWriter)

        fun forTest(
            rootDirectory: Path,
            atomicWriter: AtomicFileWriter,
            observer: PersistenceFailureDiagnosticObserver,
        ): FileStepAttemptRecordStore = FileStepAttemptRecordStore(rootDirectory, atomicWriter).also {
            it.persistenceFailureDiagnosticObserver = observer
        }
    }

    override suspend fun recordStepAttempt(record: StepAttemptRecord): StepAttemptRecord {
        record.requirePersistableIdentity()
        return persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver,
            classify = ::classifyStepAttemptFailure,
        ) {
            val path = attemptPath(record.runId, record.stepName, record.attemptId)
            withFileLockCancellable(sequencePath(record.runId)) {
                val sequence = if (Files.exists(path)) {
                    readStoredRecord(path, record.runId, record.stepName, record.attemptId).attemptSequence
                } else {
                    allocateSequence(record.runId)
                }
                atomicWriter.write(path, encodeStoredRecord(record, sequence))
                record
            }
        }
    }

    override suspend fun updateStepAttempt(record: StepAttemptRecord): StepAttemptRecord {
        record.requirePersistableIdentity()
        return persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver,
            classify = ::classifyStepAttemptFailure,
        ) {
            val path = attemptPath(record.runId, record.stepName, record.attemptId)
            withFileLockCancellable(sequencePath(record.runId)) {
                if (!Files.exists(path)) {
                    throw IllegalStateException("Step attempt does not exist")
                }
                val existing = readStoredRecord(path, record.runId, record.stepName, record.attemptId)
                atomicWriter.write(path, encodeStoredRecord(record, existing.attemptSequence))
                record
            }
        }
    }

    override suspend fun compareAndSetStepAttempt(
        expected: StepAttemptRecord,
        updated: StepAttemptRecord,
    ): Boolean {
        if (expected.identity() != updated.identity()) return false
        updated.requirePersistableIdentity()
        return persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.COMPARE_AND_SET, persistenceFailureDiagnosticObserver,
            classify = ::classifyStepAttemptFailure,
        ) {
            val path = attemptPath(expected.runId, expected.stepName, expected.attemptId)
            withFileLockCancellable(sequencePath(expected.runId)) {
                val current = if (Files.exists(path)) {
                    readStoredRecord(path, expected.runId, expected.stepName, expected.attemptId)
                } else {
                    null
                }
                if (current?.record != expected) {
                    false
                } else {
                    atomicWriter.write(path, encodeStoredRecord(updated, current.attemptSequence))
                    true
                }
            }
        }
    }

    override suspend fun latestStepAttempt(runId: String, stepName: String): StepAttemptRecord? {
        require(runId.isNotBlank() && stepName.isNotBlank()) { "Step-attempt runId and stepName must not be blank" }
        return persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.LOAD, persistenceFailureDiagnosticObserver,
            classify = ::classifyStepAttemptFailure,
        ) {
            val stepDirectory = rootDirectory
                .resolve(base64UrlEncodeNoPadding(runId))
                .resolve(base64UrlEncodeNoPadding(stepName))
            if (!Files.exists(stepDirectory)) {
                null
            } else {
                val paths = runInterruptible(Dispatchers.IO) {
                    Files.list(stepDirectory).use { stream ->
                        stream.filter(Files::isRegularFile)
                            .filter { it.fileName.toString().endsWith(ATTEMPT_SUFFIX) }
                            .toList()
                    }
                }
                paths.mapNotNull { path ->
                    withFileLockCancellable(path) {
                        if (Files.exists(path)) {
                            val fileName = path.fileName.toString()
                            val keyAttemptId = decodePathSegment(fileName.removeSuffix(ATTEMPT_SUFFIX), path)
                            readStoredRecord(path, runId, stepName, keyAttemptId)
                        } else {
                            null
                        }
                    }
                }.maxWithOrNull(
                    compareBy<DecodedStepAttemptRecord>(
                        { it.record.startedAt },
                        { it.record.stepName },
                        { it.attemptSequence ?: Long.MIN_VALUE },
                        { it.record.attemptId },
                    ),
                )?.record
            }
        }
    }

    override suspend fun listStepAttempts(runId: String): List<StepAttemptRecord> {
        require(runId.isNotBlank()) { "Step-attempt runId must not be blank" }
        return persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.LIST, persistenceFailureDiagnosticObserver,
            classify = ::classifyStepAttemptFailure,
        ) {
            val runDirectory = rootDirectory.resolve(base64UrlEncodeNoPadding(runId))
            if (!Files.exists(runDirectory)) {
                emptyList()
            } else {
                val paths = runInterruptible(Dispatchers.IO) {
                    Files.walk(runDirectory).use { stream ->
                        stream.filter(Files::isRegularFile)
                            .filter { it.fileName.toString().endsWith(ATTEMPT_SUFFIX) }
                            .toList()
                    }
                }
                paths.map { path ->
                    val relative = runDirectory.relativize(path)
                    if (relative.nameCount != 2) {
                        throw CorruptStepAttemptException("Persisted step-attempt record is invalid", path.toString())
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
                    compareBy<DecodedStepAttemptRecord>(
                        { it.record.startedAt },
                        { it.record.stepName },
                        { it.attemptSequence ?: Long.MIN_VALUE },
                        { it.record.attemptId },
                    ),
                ).map(DecodedStepAttemptRecord::record)
            }
        }
    }

    private fun sequencePath(runId: String): Path = rootDirectory
        .resolve(base64UrlEncodeNoPadding(runId))
        .resolve(SEQUENCE_SUFFIX)

    private fun attemptPath(runId: String, stepName: String, attemptId: String): Path = rootDirectory
        .resolve(base64UrlEncodeNoPadding(runId))
        .resolve(base64UrlEncodeNoPadding(stepName))
        .resolve(base64UrlEncodeNoPadding(attemptId) + ATTEMPT_SUFFIX)

    private fun readSequenceCounter(path: Path): Long {
        if (!Files.exists(path)) return 0
        val value = try {
            Files.readString(path).trim().toLongOrNull()
        } catch (error: Exception) {
            throw CorruptStepAttemptException("Persisted step-attempt record is invalid", path.toString(), error)
        }
        return value ?: throw CorruptStepAttemptException("Persisted step-attempt record is invalid", path.toString())
    }

    private fun writeSequenceCounter(path: Path, value: Long) {
        atomicWriter.write(path, value.toString())
    }

    private fun allocateSequence(runId: String): Long {
        val path = sequencePath(runId)
        val next = readSequenceCounter(path) + 1
        writeSequenceCounter(path, next)
        return next
    }

    private fun readStoredRecord(path: Path, runId: String, stepName: String, attemptId: String): DecodedStepAttemptRecord {
        val payload = try {
            Files.readString(path)
        } catch (error: Exception) {
            throw CorruptStepAttemptException("Persisted step-attempt record is invalid", path.toString(), error)
        }
        val properties = try {
            Properties().apply { load(payload.reader()) }
        } catch (error: Exception) {
            throw CorruptStepAttemptException("Persisted step-attempt record is invalid", payload, error)
        }
        val decoded = StepAttemptRecordCodec.decodeWithSequence(payload)
        val storedHash = properties.getProperty(RECORD_HASH)
            ?: throw CorruptStepAttemptException("Persisted step-attempt record is invalid", path.toString())
        StepAttemptRecordCodec.requireValidFingerprint(
            decoded.record,
            decoded.attemptSequence,
            storedHash,
            path.toString(),
        )
        if (decoded.record.identity() != AttemptIdentity(runId, stepName, attemptId)) {
            throw CorruptStepAttemptException("Persisted step-attempt record is invalid", path.toString())
        }
        return decoded
    }

    private fun encodeStoredRecord(record: StepAttemptRecord, attemptSequence: Long?): String =
        StepAttemptRecordCodec.encode(record, attemptSequence) +
            "$RECORD_HASH=${StepAttemptRecordCodec.fingerprint(record, attemptSequence)}\n"

    private fun decodePathSegment(value: String, path: Path): String = try {
        base64UrlDecode(value)
    } catch (error: IllegalArgumentException) {
        throw CorruptStepAttemptException("Persisted step-attempt record is invalid", path.toString(), error)
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

internal fun classifyStepAttemptFailure(error: Throwable): PersistenceFailureCode? =
    if (error is CorruptStepAttemptException) PersistenceFailureCode.CORRUPTED_DATA else null
