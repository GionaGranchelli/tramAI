package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 0.7.6f V3 payload shape for the JDBC outbox codec.
 *
 * Two things matter here and they pull in opposite directions: a record *with* evidence must round-trip
 * its attribution, and a record *without* it -- every row written before 0.7.6f -- must keep decoding with
 * the attribution explicitly absent. A legacy payload that acquired an issuer here would be exactly the
 * fabrication the contract forbids.
 */
class JdbcSovereignOpsAuditOutboxEvidenceCodecTest {
    @Test
    fun `a record with evidence round-trips its attribution`() {
        val record =
            record(
                actorIssuer = "https://issuer.example",
                actorIdentityDigest = "b".repeat(64),
                previousApprovalStatus = "PENDING",
                previousApprovalVersion = 7,
            )

        val decoded = decodeOutboxRecord(encodeOutboxRecord(record, runIdentity = null)).record

        assertThat(decoded.actorIssuer).isEqualTo("https://issuer.example")
        assertThat(decoded.actorIdentityDigest).isEqualTo("b".repeat(64))
        assertThat(decoded.previousApprovalStatus).isEqualTo("PENDING")
        assertThat(decoded.previousApprovalVersion).isEqualTo(7)
        assertThat(decoded.approvalStatus).isEqualTo("DENIED")
        assertThat(decoded.approvalVersion).isEqualTo(8)
    }

    @Test
    fun `a record with evidence declares schema version 3`() {
        val json = String(encodeOutboxRecord(record(actorIssuer = "issuer"), runIdentity = null))

        assertThat(json).contains("\"schemaVersion\":3")
    }

    @Test
    fun `a record without evidence keeps the released shape`() {
        val json = String(encodeOutboxRecord(record(), runIdentity = null))

        assertThat(json).contains("\"schemaVersion\":1")
        assertThat(json).doesNotContain("actorIssuer")
    }

    @Test
    fun `a legacy V1 payload decodes with no attribution`() {
        val decoded = decodeOutboxRecord(legacyV1Json().toByteArray()).record

        assertThat(decoded.actorIssuer).isNull()
        assertThat(decoded.actorIdentityDigest).isNull()
        assertThat(decoded.previousApprovalStatus).isNull()
        assertThat(decoded.previousApprovalVersion).isNull()
    }

    @Test
    fun `the frozen digests keep their prefixed form while the identity digest stays bare hex`() {
        val json =
            String(
                encodeOutboxRecord(
                    record(actorIssuer = "issuer", actorIdentityDigest = "c".repeat(64)),
                    runIdentity = null,
                ),
            )

        assertThat(json).contains("sha256:")
        assertThat(json).contains("\"actorIdentityDigest\":\"${"c".repeat(64)}\"")
    }

    @Test
    fun `an unknown schema version is rejected rather than read as evidence or legacy`() {
        val json = legacyV1Json().replace("\"schemaVersion\":1", "\"schemaVersion\":9")

        assertThatThrownBy { decodeOutboxRecord(json.toByteArray()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("audit-outbox-unsupported-schema-version: 9")
    }

    @Test
    fun `a V3 payload whose identity names another run is corruption`() {
        val identityJson =
            """
            {"runId":"other-run","workloadId":"w","configurationId":"c","configurationVersion":"1",
             "environmentId":"e","deploymentId":"d"}
            """.trimIndent()
        val json =
            legacyV1Json()
                .replace("\"schemaVersion\":1", "\"schemaVersion\":3")
                .replaceFirst("{", "{\"governedRunIdentity\":$identityJson,")

        assertThatThrownBy { decodeOutboxRecord(json.toByteArray()) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a V1 payload carrying evidence fields is corruption, never unversioned attribution`() {
        val json =
            legacyV1Json()
                .replaceFirst("{\"schemaVersion\":1,", "{\"schemaVersion\":1,\"actorIssuer\":\"issuer\",")

        assertThatThrownBy { decodeOutboxRecord(json.toByteArray()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("legacy payload carries 0.7.6f evidence fields")
    }

    @Test
    fun `a V2 payload carrying evidence fields is corruption`() {
        val identityJson =
            """
            {"runId":"run-1","workloadId":"w","configurationId":"c","configurationVersion":"1",
             "environmentId":"e","deploymentId":"d"}
            """.trimIndent()
        val json =
            legacyV1Json()
                .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
                .replaceFirst("{\"schemaVersion\":2,", "{\"schemaVersion\":2,\"previousApprovalVersion\":3,")
                .replaceFirst("{\"schemaVersion\":2,", "{\"schemaVersion\":2,\"governedRunIdentity\":$identityJson,")

        assertThatThrownBy { decodeOutboxRecord(json.toByteArray()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("V2 payload carries 0.7.6f evidence fields")
    }

    @Test
    fun `a V3 payload with only one half of the actor pair is corruption`() {
        val json =
            withoutField(
                encodeOutboxRecord(record(actorIssuer = "issuer", actorIdentityDigest = "f".repeat(64)), null),
                "actorIdentityDigest",
            )

        assertThatThrownBy { decodeOutboxRecord(json) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("actor provenance is incomplete")
    }

    @Test
    fun `a V3 payload with only one half of the previous state is corruption`() {
        val json =
            withoutField(
                encodeOutboxRecord(record(previousApprovalStatus = "PENDING", previousApprovalVersion = 7L), null),
                "previousApprovalVersion",
            )

        assertThatThrownBy { decodeOutboxRecord(json) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("previous approval state is incomplete")
    }

    /** Removes a field from a payload, leaving the declared version alone: the shape stays, one half of a pair goes. */
    private fun withoutField(
        json: ByteArray,
        field: String,
    ): ByteArray {
        val tree = OPS_OUTBOX_JSON.readTree(json)
        (tree.get("record") as ObjectNode).remove(field)
        return OPS_OUTBOX_JSON.writeValueAsBytes(tree)
    }

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
         "claimExpiresAt":null,"createdAt":"2026-10-29T00:00:00Z","emittedAt":null}
        """.trimIndent()
}
