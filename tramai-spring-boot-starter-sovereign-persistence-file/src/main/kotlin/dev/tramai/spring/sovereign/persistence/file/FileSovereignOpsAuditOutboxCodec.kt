package dev.tramai.spring.sovereign.persistence.file

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.persistence.file.FileStoreCorruptionException
import dev.tramai.persistence.file.FileStorePermissionException
import dev.tramai.persistence.file.FileStoreUnsupportedFormatException
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord

/**
 * 0.7.1d: the single file-persistence codec for sovereign-ops audit outbox records.
 *
 * Persistence has exactly two shapes:
 *
 * - **V1** — the ordinary record with no governed attribution. Permanently legacy.
 * - **V2** — the ordinary record plus the complete canonical [GovernedRunIdentity].
 *
 * The identity is one non-optional nested object in V2, never five independently optional fields, so a
 * partially attributed V2 cannot be constructed or decoded at all. An unknown schema version is an
 * unsupported format, never an old record.
 *
 * Every file transition decodes through [decodeOutboxRecord], mutates only
 * [DecodedOutboxRecord.record], and re-encodes through [encodeOutboxRecord] with the decoded identity
 * untouched. The encoder chooses the shape from the identity's presence, so a transition can only drop
 * attribution by first dropping it from the decoded value.
 *
 * The codec emits exactly two exception types: [FileStoreCorruptionException] for a damaged payload and
 * [FileStoreUnsupportedFormatException] for an unknown version. The two must never be conflated.
 */
internal data class DecodedOutboxRecord(
    val record: SovereignOpsAuditOutboxRecord,
    val runIdentity: GovernedRunIdentity?,
    val outboxRecordVersion: Long,
)

/**
 * Persisted form of the canonical identity: the complete deployment tuple plus the run identifier,
 * which must agree with the ordinary record's `workflowRunId` on decode.
 */
internal data class PersistedGovernedRunIdentity(
    @param:JsonProperty("runId") val runId: String,
    @param:JsonProperty("workloadId") val workloadId: String,
    @param:JsonProperty("configurationId") val configurationId: String,
    @param:JsonProperty("configurationVersion") val configurationVersion: String,
    @param:JsonProperty("environmentId") val environmentId: String,
    @param:JsonProperty("deploymentId") val deploymentId: String,
) {
    fun toCore(): GovernedRunIdentity =
        GovernedRunIdentity(
            deployment =
                WorkloadDeploymentIdentity(
                    workloadId = WorkloadId(workloadId),
                    configuration =
                        WorkloadConfigurationIdentity(
                            id = ConfigurationId(configurationId),
                            version = ConfigurationVersion(configurationVersion),
                        ),
                    environmentId = EnvironmentId(environmentId),
                    deploymentId = DeploymentId(deploymentId),
                ),
            runId = RunId(runId),
        )
}

internal fun GovernedRunIdentity.toPersisted(): PersistedGovernedRunIdentity =
    PersistedGovernedRunIdentity(
        runId = runId.value,
        workloadId = deployment.workloadId.value,
        configurationId = deployment.configuration.id.value,
        configurationVersion = deployment.configuration.version.value,
        environmentId = deployment.environmentId.value,
        deploymentId = deployment.deploymentId.value,
    )

/**
 * V2 carrier: the ordinary record (reusing the V1 DTO verbatim, so the ordinary fields cannot drift
 * between shapes) plus the complete identity.
 */
internal data class PersistedSovereignOpsAuditOutboxRecordV2(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int = 2,
    @param:JsonProperty("record") val record: PersistedSovereignOpsAuditOutboxRecordV1,
    @param:JsonProperty("governedRunIdentity") val governedRunIdentity: PersistedGovernedRunIdentity,
) {
    fun toJson(): String = OPS_OUTBOX_JSON.writeValueAsString(this)
}

/**
 * Decodes any persisted outbox payload, dispatching on the declared schema version.
 */
internal fun decodeOutboxRecord(json: String): DecodedOutboxRecord {
    val version = outboxSchemaVersion(json)
    if (version != OFFICIAL_VERSION && version != GOVERNED_VERSION) {
        throw FileStoreUnsupportedFormatException("unsupported-outbox-schema-version: $version")
    }
    return decodeKnownVersion(json, version)
}

/**
 * Decodes a known schema version, classifying malformed content as corruption.
 *
 * A V2 payload whose identity disagrees with the ordinary record's `workflowRunId` is corruption and
 * fails closed; it is never read as legacy.
 */
private fun decodeKnownVersion(
    json: String,
    version: Int,
): DecodedOutboxRecord =
    try {
        if (version == OFFICIAL_VERSION) decodeLegacy(json) else decodeGoverned(json)
    } catch (e: IllegalStateException) {
        throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, e)
    } catch (e: IllegalArgumentException) {
        throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, e)
    }

private fun decodeLegacy(json: String): DecodedOutboxRecord {
    val v1 = PersistedSovereignOpsAuditOutboxRecordV1.fromJson(json)
    return DecodedOutboxRecord(
        record = v1.toDomain(),
        runIdentity = null,
        outboxRecordVersion = v1.outboxRecordVersion,
    )
}

private fun decodeGoverned(json: String): DecodedOutboxRecord {
    val v2 = strictOpsOutboxReadValue<PersistedSovereignOpsAuditOutboxRecordV2>(json)
    val identity = v2.governedRunIdentity.toCore()
    val record = v2.record.toDomain()
    if (identity.runId.value != record.workflowRunId) {
        throw FileStoreCorruptionException(
            "governed-outbox-attribution-mismatch: " +
                "'${record.workflowRunId}' != canonical runId '${identity.runId.value}'",
        )
    }
    return DecodedOutboxRecord(
        record = record,
        runIdentity = identity,
        outboxRecordVersion = v2.record.outboxRecordVersion,
    )
}

/**
 * Encodes a decoded record, choosing the shape from the identity's presence: absent → V1, present → V2.
 */
internal fun encodeOutboxRecord(
    decoded: DecodedOutboxRecord,
    outboxRecordVersion: Long,
): String {
    val persisted = decoded.record.toPersistedV1(outboxRecordVersion)
    return if (decoded.runIdentity == null) {
        persisted.toJson()
    } else {
        PersistedSovereignOpsAuditOutboxRecordV2(
            record = persisted,
            governedRunIdentity = decoded.runIdentity.toPersisted(),
        ).toJson()
    }
}

/**
 * Reads only the declared schema version, leaving the payload untouched for the real decode.
 */
private fun outboxSchemaVersion(json: String): Int =
    readJsonTree(json)?.get("schemaVersion")?.asInt()
        ?: throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD)

/**
 * Strict tree read: a payload that is not valid JSON at all is corruption, not an unknown version.
 */
private fun readJsonTree(json: String): JsonNode? =
    try {
        OPS_OUTBOX_JSON.readTree(json)
    } catch (e: JsonProcessingException) {
        throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, e)
    }

/**
 * Raises a file-permission failure with the validators' exact exception type and message.
 *
 * Hosted here rather than beside its callers because the legacy store file sits at its
 * function-count ceiling and must not be restructured for a reporting concern.
 */
internal fun permissionFailure(message: String): Nothing = throw FileStorePermissionException(message)

private const val OFFICIAL_VERSION = 1
private const val GOVERNED_VERSION = 2
