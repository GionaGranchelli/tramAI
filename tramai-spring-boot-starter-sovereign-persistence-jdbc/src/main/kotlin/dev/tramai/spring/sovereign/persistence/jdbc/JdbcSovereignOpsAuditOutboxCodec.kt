package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord

/**
 * 0.7.1d: the single JDBC codec for sovereign-ops audit outbox payloads.
 *
 * Persistence has exactly two shapes:
 *
 * - **V1** — the ordinary record with no governed attribution, byte-for-byte the released shape.
 * - **V2** — the ordinary record plus the complete canonical [GovernedRunIdentity].
 *
 * The identity is one non-optional nested object in V2, never six independently optional fields, so a
 * partially attributed V2 cannot be encoded and cannot be decoded. The stored version is required and
 * authoritative: a payload with no declared version, or with one this codec does not know, is never
 * read as an old record. That is deliberately stricter than
 * [dev.tramai.persistence.jdbc.JdbcSuspendedInvocationStore]'s `payloadVersion`, which predates
 * governed attribution and must treat an absent version as legacy; here an absent version would be a
 * silent downgrade of a governed row, so it fails closed instead.
 *
 * Both writer paths — the outbox store's own transactions and the approval-mutation transactions that
 * write outbox rows inline — encode through [encodeOutboxRecord] and decode through
 * [decodeOutboxRecord]. Neither owns its own JSON shape, so the two cannot drift.
 */
internal data class DecodedOutboxRecord(
    val record: SovereignOpsAuditOutboxRecord,
    val runIdentity: GovernedRunIdentity?,
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
 * V2 carrier: the ordinary record (reusing the released V1 DTO verbatim, so the ordinary fields cannot
 * drift between shapes) plus the complete identity.
 */
internal data class PersistedSovereignOpsAuditOutboxRecordV2(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int = 2,
    @param:JsonProperty("record") val record: PersistedSovereignOpsAuditOutboxRecordV1,
    @param:JsonProperty("governedRunIdentity") val governedRunIdentity: PersistedGovernedRunIdentity,
)

/**
 * The one JSON mapper for outbox payloads: strict on unknown properties, so an unrecognised field is a
 * decode failure rather than a silently dropped one.
 */
internal val OPS_OUTBOX_JSON: ObjectMapper =
    ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)

/**
 * Encodes a record, choosing the shape from the identity's presence: absent → V1, present → V2.
 *
 * A governed record can therefore lose its attribution only by a caller deliberately handing over a
 * null identity; no status transition can drop it as a side effect.
 */
internal fun encodeOutboxRecord(
    record: SovereignOpsAuditOutboxRecord,
    runIdentity: GovernedRunIdentity?,
): ByteArray {
    val persisted = record.toPersistedOutbox()
    return if (runIdentity == null) {
        OPS_OUTBOX_JSON.writeValueAsBytes(persisted)
    } else {
        OPS_OUTBOX_JSON.writeValueAsBytes(
            PersistedSovereignOpsAuditOutboxRecordV2(
                record = persisted,
                governedRunIdentity = runIdentity.toPersisted(),
            ),
        )
    }
}

/**
 * Decodes any persisted outbox payload, dispatching on the declared schema version.
 *
 * The two failure modes stay distinct: an unreadable or internally inconsistent payload is corruption,
 * while a version this codec does not know is an unsupported format — never an old record.
 */
internal fun decodeOutboxRecord(json: ByteArray): DecodedOutboxRecord {
    val node = readJsonTree(json)
    val version = outboxSchemaVersion(node)
    check(version == OFFICIAL_VERSION || version == GOVERNED_VERSION) {
        "audit-outbox-unsupported-schema-version: $version"
    }
    return try {
        if (version == OFFICIAL_VERSION) decodeLegacy(node, json) else decodeGoverned(json)
    } catch (e: IllegalArgumentException) {
        throw IllegalStateException(ERROR_CORRUPTED_RECORD, e)
    } catch (e: JsonProcessingException) {
        throw IllegalStateException(ERROR_CORRUPTED_RECORD, e)
    }
}

/**
 * Legacy V1: no attribution was persisted, and none is allowed in this shape.
 */
private fun decodeLegacy(
    node: JsonNode,
    json: ByteArray,
): DecodedOutboxRecord {
    check(node.get("governedRunIdentity") == null) {
        "$ERROR_CORRUPTED_RECORD: legacy payload carries a governed identity"
    }
    val v1 = OPS_OUTBOX_JSON.readValue<PersistedSovereignOpsAuditOutboxRecordV1>(json)
    return DecodedOutboxRecord(record = v1.toDomain(), runIdentity = null)
}

/**
 * Governed V2: the identity is mandatory and must name the same run as the ordinary record.
 */
private fun decodeGoverned(json: ByteArray): DecodedOutboxRecord {
    val v2 = OPS_OUTBOX_JSON.readValue<PersistedSovereignOpsAuditOutboxRecordV2>(json)
    val identity = v2.governedRunIdentity.toCore()
    val record = v2.record.toDomain()
    check(
        identity.deployment.workloadId.value
            .isNotBlank() && identity.runId.value.isNotBlank(),
    ) {
        "$ERROR_CORRUPTED_RECORD: governed run identity is incomplete"
    }
    check(identity.runId.value == record.workflowRunId) {
        "$ERROR_CORRUPTED_RECORD: governed attribution names another run: " +
            "'${record.workflowRunId}' != canonical runId '${identity.runId.value}'"
    }
    return DecodedOutboxRecord(record = record, runIdentity = identity)
}

/**
 * Reads only the declared schema version, leaving the payload untouched for the real decode.
 *
 * Only an integral, in-range JSON number is a schema declaration: anything else — missing, null,
 * text, float, out of int range — is corruption. `asInt()` coerces instead (`"2"` and `2.9` to 2,
 * `"abc"` to 0), which turns a damaged payload into a wrong answer: a bogus V1/V2 decode, or a
 * false "unsupported version" report for a payload that is simply damaged.
 */
private fun outboxSchemaVersion(node: JsonNode): Int {
    val version = node.get("schemaVersion")
    val declared = version?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
    return checkNotNull(declared) { "$ERROR_CORRUPTED_RECORD: no declared integral schema version" }
}

/**
 * Strict tree read: a payload that is not valid JSON at all is corruption, not an unknown version.
 */
private fun readJsonTree(json: ByteArray): JsonNode =
    try {
        checkNotNull(OPS_OUTBOX_JSON.readTree(json)) { "$ERROR_CORRUPTED_RECORD: empty payload" }
    } catch (e: JsonProcessingException) {
        throw IllegalStateException(ERROR_CORRUPTED_RECORD, e)
    }

/** Failure code for a payload that is damaged or internally inconsistent. */
internal const val ERROR_CORRUPTED_RECORD: String = "audit-outbox-payload-corrupted"

private const val OFFICIAL_VERSION = 1
private const val GOVERNED_VERSION = 2
