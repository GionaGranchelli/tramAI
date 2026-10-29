package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.databind.node.ObjectNode
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
    fun `a V1 payload carrying evidence fields is corruption, never unversioned attribution`() {
        val json =
            legacyV1Json()
                .replaceFirst("{\"schemaVersion\":1,", "{\"schemaVersion\":1,\"actorIssuer\":\"issuer\",")

        assertThatThrownBy { decodeOutboxRecord(json.toByteArray()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("legacy payload carries 0.7.6f evidence fields")
    }

    @Test
    fun `a structurally valid V2 payload carrying evidence inside record is corruption`() {
        // Built from a real encode, so the nesting is exactly what the writer produces: the evidence has to
        // be injected into "record", which is where the decoder has to look for it.
        val json = governedPayload()
        assertThat(String(json)).contains("\"governedRunIdentity\"")
        assertThat(String(json)).contains("\"schemaVersion\":2")

        val injected = withEvidenceInsideRecord(json, "actorIssuer", "https://issuer.example")

        assertThatThrownBy { decodeOutboxRecord(injected) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("V2 payload carries 0.7.6f evidence fields")
    }

    @Test
    fun `a V2 payload carrying the identity digest inside record is corruption`() {
        val injected = withEvidenceInsideRecord(governedPayload(), "actorIdentityDigest", "e".repeat(64))

        assertThatThrownBy { decodeOutboxRecord(injected) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("V2 payload carries 0.7.6f evidence fields")
    }

    @Test
    fun `a V3 payload whose identity names another run is corruption`() {
        // Structurally valid V3: real nested record, real identity block, only the run disagrees.
        val v3 =
            OPS_OUTBOX_JSON.readTree(governedPayload()).let { tree ->
                (tree as ObjectNode).put("schemaVersion", 3)
                (tree.get("record") as ObjectNode).put("workflowRunId", "other-run")
                tree
            }

        assertThatThrownBy { decodeOutboxRecord(OPS_OUTBOX_JSON.writeValueAsBytes(v3)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("governed attribution names another run")
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

    // A payload the encoder actually produces for a governed record: nested record + complete identity.
    private fun governedPayload(): ByteArray =
        encodeOutboxRecord(
            record(),
            GovernedRunIdentity(
                deployment =
                    WorkloadDeploymentIdentity(
                        workloadId = WorkloadId("workload-1"),
                        configuration =
                            WorkloadConfigurationIdentity(
                                id = ConfigurationId("config-1"),
                                version = ConfigurationVersion("1"),
                            ),
                        environmentId = EnvironmentId("env-1"),
                        deploymentId = DeploymentId("deployment-1"),
                    ),
                runId = RunId("run-1"),
            ),
        )

    // Injects a field into the nested `record` object: the location V2 evidence would have to hide in.
    private fun withEvidenceInsideRecord(
        json: ByteArray,
        field: String,
        value: String,
    ): ByteArray {
        val tree = OPS_OUTBOX_JSON.readTree(json)
        (tree.get("record") as ObjectNode).put(field, value)
        return OPS_OUTBOX_JSON.writeValueAsBytes(tree)
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
