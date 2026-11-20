package dev.tramai.spring.sovereign.persistence.file

import com.fasterxml.jackson.annotation.JsonProperty
import dev.tramai.spring.sovereign.ops.outbox.QuarantinedEvidenceSummary
import java.time.Instant

/**
 * 0.7.6f: the durable quarantine marker for one file-store record.
 *
 * The marker lives in a separate namespace (`quarantine/<locator>.quarantine.json`) so the record's own
 * `.tram.enc` file stays byte-identical. Correlation is by [locator] alone -- the record-key digest taken
 * from the record's file name -- so an undecodable record is as recoverable as any other: [outboxId] and
 * [eventKey] live inside the ciphertext and are therefore null exactly when the record never decoded.
 * A null is recorded as null, never synthesised.
 */
internal data class PersistedQuarantineMarker(
    @param:JsonProperty("locator") val locator: String,
    @param:JsonProperty("outboxId") val outboxId: String? = null,
    @param:JsonProperty("eventKey") val eventKey: String? = null,
    @param:JsonProperty("payloadDigest") val payloadDigest: String,
    @param:JsonProperty("label") val label: String,
    @param:JsonProperty("quarantinedAt") val quarantinedAt: String,
    @param:JsonProperty("failureDetail") val failureDetail: String? = null,
) {
    fun toSummary(): QuarantinedEvidenceSummary =
        QuarantinedEvidenceSummary(
            locator = locator,
            outboxId = outboxId,
            eventKey = eventKey,
            payloadDigest = payloadDigest,
            label = label,
            quarantinedAt = Instant.parse(quarantinedAt),
            failureDetail = failureDetail,
        )
}
