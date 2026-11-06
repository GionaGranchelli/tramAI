package dev.tramai.spring.sovereign.persistence.file

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.tramai.persistence.file.FileStoreCorruptionException
import dev.tramai.persistence.file.FileStoreUnsupportedFormatException
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 0.7.6f V3 payload shape for the file outbox codec.
 *
 * The file backend writes one encrypted document per record, so the payload shape is also the on-disk
 * format: a record with evidence must round-trip its attribution, and a record without it must keep the
 * released encoding exactly -- a V1 file that gained an unknown field would break an older reader.
 */
class FileSovereignOpsAuditOutboxEvidenceCodecTest {
    @Test
    fun `a record with evidence round-trips its attribution`() {
        val record =
            record(
                actorIssuer = "https://issuer.example",
                actorIdentityDigest = "b".repeat(64),
                previousApprovalStatus = "PENDING",
                previousApprovalVersion = 7,
            )

        val decoded = decodeOutboxRecord(encode(record)).record

        assertThat(decoded.actorIssuer).isEqualTo("https://issuer.example")
        assertThat(decoded.actorIdentityDigest).isEqualTo("b".repeat(64))
        assertThat(decoded.previousApprovalStatus).isEqualTo("PENDING")
        assertThat(decoded.previousApprovalVersion).isEqualTo(7)
    }

    @Test
    fun `a record with evidence declares schema version 3`() {
        assertThat(encode(record(actorIssuer = "issuer"))).contains("\"schemaVersion\":3")
    }

    @Test
    fun `a record without evidence keeps the released encoding with no new fields`() {
        val json = encode(record())

        assertThat(json).contains("\"schemaVersion\":1")
        assertThat(json).doesNotContain("actorIssuer")
        assertThat(json).doesNotContain("previousApprovalStatus")
    }

    @Test
    fun `a legacy V1 payload decodes with no attribution`() {
        val decoded = decodeOutboxRecord(legacyV1Json()).record

        assertThat(decoded.actorIssuer).isNull()
        assertThat(decoded.actorIdentityDigest).isNull()
        assertThat(decoded.previousApprovalStatus).isNull()
        assertThat(decoded.previousApprovalVersion).isNull()
    }

    @Test
    fun `an unknown schema version is an unsupported format, not legacy`() {
        val json = legacyV1Json().replace("\"schemaVersion\":1", "\"schemaVersion\":9")

        assertThatThrownBy { decodeOutboxRecord(json) }
            .isInstanceOf(FileStoreUnsupportedFormatException::class.java)
    }

    @Test
    fun `a V1 payload carrying evidence fields is corruption, never unversioned attribution`() {
        val json =
            legacyV1Json()
                .replaceFirst("{\"schemaVersion\":1,", "{\"schemaVersion\":1,\"actorIssuer\":\"issuer\",")

        assertThatThrownBy { decodeOutboxRecord(json) }
            .isInstanceOf(FileStoreCorruptionException::class.java)
    }

    @Test
    fun `a V3 payload with only one half of the actor pair is corruption`() {
        val full = encode(record(actorIssuer = "issuer", actorIdentityDigest = "f".repeat(64)))
        val tree = OPS_OUTBOX_JSON.readTree(full)
        (tree.get("record") as ObjectNode).remove("actorIdentityDigest")

        assertThatThrownBy { decodeOutboxRecord(OPS_OUTBOX_JSON.writeValueAsString(tree)) }
            .isInstanceOf(FileStoreCorruptionException::class.java)
    }

    private fun encode(record: SovereignOpsAuditOutboxRecord): String =
        encodeOutboxRecord(
            DecodedOutboxRecord(record = record, runIdentity = null, outboxRecordVersion = 1),
            outboxRecordVersion = 1,
        )

    @Test
    fun `a refusal without its expected version is corrupted evidence`() {
        val encoded = encodeOutboxRecord(refusalDecoded(expectedVersion = null), outboxRecordVersion = 3)

        assertThatThrownBy { decodeOutboxRecord(encoded) }
            .isInstanceOf(FileStoreCorruptionException::class.java)
            .hasMessageContaining("authorization refusal is missing its inputs")
    }

    @Test
    fun `a refusal without its reason is corrupted evidence`() {
        val encoded = encodeOutboxRecord(refusalDecoded(reason = null), outboxRecordVersion = 3)

        assertThatThrownBy { decodeOutboxRecord(encoded) }
            .isInstanceOf(FileStoreCorruptionException::class.java)
            .hasMessageContaining("authorization refusal is missing its inputs")
    }

    @Test
    fun `a refusal with no consulted policy still decodes`() {
        val decoded = decodeOutboxRecord(encodeOutboxRecord(refusalDecoded(), outboxRecordVersion = 3)).record

        // Absent policy is a fact -- no policy was consulted -- not damage.
        assertThat(decoded.refusalPolicyVersion).isNull()
        assertThat(decoded.refusalReason).isEqualTo("missing-capability")
        assertThat(decoded.refusalExpectedVersion).isEqualTo(11L)
    }

    private fun refusalDecoded(
        reason: String? = "missing-capability",
        policyVersion: String? = null,
        expectedVersion: Long? = 11,
    ) = DecodedOutboxRecord(
        record = refusalRecord(reason, policyVersion, expectedVersion),
        runIdentity = null,
        outboxRecordVersion = 3,
    )

    private fun refusalRecord(
        reason: String? = "missing-capability",
        policyVersion: String? = null,
        expectedVersion: Long? = 11,
    ) = record().copy(
        evidenceKind = SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL,
        refusalReason = reason,
        refusalPolicyVersion = policyVersion,
        refusalExpectedVersion = expectedVersion,
    )

    private fun record(
        actorIssuer: String? = null,
        actorIdentityDigest: String? = null,
        previousApprovalStatus: String? = null,
        previousApprovalVersion: Long? = null,
    ) = SovereignOpsAuditOutboxRecord(
        outboxId = "outbox-1",
        aggregateIdDigest = "sha256:${"d".repeat(64)}",
        operation = "approval-denied.approval-1",
        eventKey = "approval-denied.approval-1",
        actor = "op-1",
        workflowRunId = "run-1",
        correlationId = "corr-1",
        approvalStatus = "DENIED",
        approvalVersion = 8,
        reasonDigest = "sha256:${"e".repeat(64)}",
        reasonLength = 5,
        createdAt = Instant.parse("2026-10-29T00:00:00Z"),
        status = SovereignOpsAuditOutboxStatus.PENDING,
        actorIssuer = actorIssuer,
        actorIdentityDigest = actorIdentityDigest,
        previousApprovalStatus = previousApprovalStatus,
        previousApprovalVersion = previousApprovalVersion,
    )

    private fun legacyV1Json(): String =
        """
        {"schemaVersion":1,"outboxId":"outbox-1","aggregateType":"approval",
         "aggregateIdDigest":"sha256:${"d".repeat(64)}","operation":"approval-denied.approval-1",
         "eventKey":"approval-denied.approval-1","actor":"op-1","workflowRunId":"run-1",
         "correlationId":"corr-1","approvalStatus":"DENIED","approvalVersion":8,
         "reasonDigest":"sha256:${"e".repeat(64)}","reasonLength":5,"status":"PENDING",
         "attemptCount":0,"lastErrorCode":null,"claimedBy":null,"claimedAt":null,
         "claimExpiresAt":null,"createdAt":"2026-10-29T00:00:00Z","emittedAt":null,
         "outboxRecordVersion":1}
        """.trimIndent()
}
