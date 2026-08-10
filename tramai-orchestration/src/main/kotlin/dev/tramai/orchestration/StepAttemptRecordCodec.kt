package dev.tramai.orchestration

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

internal object StepAttemptRecordCodec {
    /** Current persisted-format version. Persisted stores must write and validate this. */
    internal const val SCHEMA_VERSION = "1"

    fun encode(record: StepAttemptRecord): String = buildString {
        property("schemaVersion", SCHEMA_VERSION)
        property("runId", encodeString(record.runId))
        property("stepName", encodeString(record.stepName))
        property("attemptId", encodeString(record.attemptId))
        property("workerId", encodeString(record.workerId))
        property("leaseToken", encodeString(record.leaseToken))
        property("status", record.status.name)
        property("startedAt", record.startedAt.toString())
        nullableLong("completedAt", record.completedAt)
        nullableString("idempotencyKey", record.idempotencyKey)
        property("replayPolicy", record.replayPolicy.name)
        nullableString("inputFingerprint", record.inputFingerprint)
        nullableString("outputSummary", record.outputSummary)
        nullableString("resolutionReason", record.resolutionReason)
        nullableLong("resolutionAtEpochMillis", record.resolutionAtEpochMillis)
        nullableString("resolutionAction", record.resolutionAction?.name)
        nullableString("approvedIdempotencyKey", record.approvedIdempotencyKey)
    }

    fun decode(payload: String): StepAttemptRecord = try {
        val properties = Properties().apply { load(payload.reader()) }
        val schemaVersion = properties.requireCodecProperty("schemaVersion")
        if (schemaVersion != SCHEMA_VERSION) {
            corrupt("Unsupported step-attempt schema version", schemaVersion)
        }
        StepAttemptRecord(
            runId = decodeString(properties.requireCodecProperty("runId"), "runId"),
            stepName = decodeString(properties.requireCodecProperty("stepName"), "stepName"),
            attemptId = decodeString(properties.requireCodecProperty("attemptId"), "attemptId"),
            workerId = decodeString(properties.requireCodecProperty("workerId"), "workerId"),
            leaseToken = decodeString(properties.requireCodecProperty("leaseToken"), "leaseToken"),
            status = enumValue(properties.requireCodecProperty("status"), "StepAttemptStatus", StepAttemptStatus.entries),
            startedAt = properties.requireCodecProperty("startedAt").toLong(),
            completedAt = properties.decodeNullableLong("completedAt"),
            idempotencyKey = properties.decodeNullableString("idempotencyKey"),
            replayPolicy = enumValue(properties.requireCodecProperty("replayPolicy"), "ReplayPolicy", ReplayPolicy.entries),
            inputFingerprint = properties.decodeNullableString("inputFingerprint"),
            outputSummary = properties.decodeNullableString("outputSummary"),
            resolutionReason = properties.decodeNullableString("resolutionReason"),
            resolutionAtEpochMillis = properties.decodeNullableLong("resolutionAtEpochMillis"),
            resolutionAction = properties.decodeNullableString("resolutionAction")?.let(::decodeResolutionAction),
            approvedIdempotencyKey = properties.decodeNullableString("approvedIdempotencyKey"),
        )
    } catch (error: CorruptStepAttemptException) {
        throw error
    } catch (error: Exception) {
        throw CorruptStepAttemptException("Persisted step-attempt record is invalid", payload, error)
    }

    fun fingerprint(record: StepAttemptRecord): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(record).toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    fun requireValidFingerprint(record: StepAttemptRecord, storedFingerprint: String, context: String) {
        if (storedFingerprint != fingerprint(record)) {
            throw CorruptStepAttemptException("Persisted step-attempt record is invalid", context)
        }
    }

    private fun StringBuilder.property(name: String, value: String) {
        append(name).append('=').append(value).append('\n')
    }

    private fun StringBuilder.nullableString(name: String, value: String?) {
        property("${name}Present", (value != null).toString())
        if (value != null) property(name, encodeString(value))
    }

    private fun StringBuilder.nullableLong(name: String, value: Long?) {
        property("${name}Present", (value != null).toString())
        if (value != null) property(name, value.toString())
    }

    private fun Properties.decodeNullableString(name: String): String? = when (requireCodecProperty("${name}Present")) {
        "true" -> decodeString(requireCodecProperty(name), name)
        "false" -> null
        else -> corrupt("Invalid presence marker for '$name'")
    }

    private fun Properties.decodeNullableLong(name: String): Long? = when (requireCodecProperty("${name}Present")) {
        "true" -> requireCodecProperty(name).toLong()
        "false" -> null
        else -> corrupt("Invalid presence marker for '$name'")
    }

    private fun Properties.requireCodecProperty(name: String): String = getProperty(name)
        ?: corrupt("Missing mandatory step-attempt field '$name'")

    private fun encodeString(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeString(value: String, field: String): String = try {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    } catch (error: IllegalArgumentException) {
        throw CorruptStepAttemptException("Invalid encoded value for '$field'", value, error)
    }

    private fun <E : Enum<E>> enumValue(value: String, label: String, entries: List<E>): E =
        entries.firstOrNull { it.name == value } ?: corrupt("Unknown $label", value)

    private fun corrupt(message: String, rawPayload: String? = null): Nothing =
        throw CorruptStepAttemptException(message, rawPayload)
}
