package dev.tramai.spring.sovereign.ops.inbox

import dev.tramai.core.approval.gateway.ApproverRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ApprovalInboxMetadataPolicyTest {

    @Test
    fun `accepts valid inbox metadata`() {
        val metadata = ApprovalInboxMetadata(
            requiredRole = ApproverRole("medical-reviewer"),
            riskLevel = "HIGH",
            subjectType = "claim",
            subjectId = "claim-123",
            recommendationType = "claim-payout",
        )
        // Should not throw
        ApprovalInboxMetadataPolicy.validate(metadata)
    }

    @Test
    fun `accepts metadata with all null fields`() {
        val metadata = ApprovalInboxMetadata()
        ApprovalInboxMetadataPolicy.validate(metadata)
    }

    @Test
    fun `accepts metadata with partial null fields`() {
        val metadata = ApprovalInboxMetadata(
            requiredRole = ApproverRole("medical-reviewer"),
            riskLevel = null,
            subjectType = "claim",
            subjectId = null,
            recommendationType = null,
        )
        ApprovalInboxMetadataPolicy.validate(metadata)
    }

    @Test
    fun `rejects blank riskLevel`() {
        val metadata = ApprovalInboxMetadata(riskLevel = "  ")
        val ex = assertThrows<IllegalArgumentException> {
            ApprovalInboxMetadataPolicy.validate(metadata)
        }
        assertThat(ex)
            .hasMessageContaining("approval-inbox-metadata-riskLevel-blank")
    }

    @Test
    fun `rejects blank subjectType`() {
        val metadata = ApprovalInboxMetadata(subjectType = "  ")
        val ex = assertThrows<IllegalArgumentException> {
            ApprovalInboxMetadataPolicy.validate(metadata)
        }
        assertThat(ex)
            .hasMessageContaining("approval-inbox-metadata-subjectType-blank")
    }

    @Test
    fun `rejects blank subjectId`() {
        val metadata = ApprovalInboxMetadata(subjectId = "  ")
        val ex = assertThrows<IllegalArgumentException> {
            ApprovalInboxMetadataPolicy.validate(metadata)
        }
        assertThat(ex)
            .hasMessageContaining("approval-inbox-metadata-subjectId-blank")
    }

    @Test
    fun `rejects blank recommendationType`() {
        val metadata = ApprovalInboxMetadata(recommendationType = "  ")
        val ex = assertThrows<IllegalArgumentException> {
            ApprovalInboxMetadataPolicy.validate(metadata)
        }
        assertThat(ex)
            .hasMessageContaining("approval-inbox-metadata-recommendationType-blank")
    }

    @Test
    fun `rejects too-long requiredRole`() {
        val metadata = ApprovalInboxMetadata(
            requiredRole = ApproverRole("x".repeat(129)),
        )
        val ex = assertThrows<IllegalArgumentException> {
            ApprovalInboxMetadataPolicy.validate(metadata)
        }
        assertThat(ex)
            .hasMessageContaining("approval-inbox-metadata-requiredRole-too-long")
    }

    @Test
    fun `rejects control-character requiredRole`() {
        val metadata = ApprovalInboxMetadata(
            requiredRole = ApproverRole("medical-reviewer\n"),
        )
        val ex = assertThrows<IllegalArgumentException> {
            ApprovalInboxMetadataPolicy.validate(metadata)
        }
        assertThat(ex)
            .hasMessageContaining("approval-inbox-metadata-requiredRole-control-characters")
    }

    @Test
    fun `rejects suspicious-pattern requiredRole`() {
        val metadata = ApprovalInboxMetadata(
            requiredRole = ApproverRole("<<script>>"),
        )
        val ex = assertThrows<IllegalArgumentException> {
            ApprovalInboxMetadataPolicy.validate(metadata)
        }
        assertThat(ex)
            .hasMessageContaining("approval-inbox-metadata-requiredRole-suspicious-pattern")
    }

    @Test
    fun `rejects too-long subjectId`() {
        val metadata = ApprovalInboxMetadata(subjectId = "x".repeat(129))
        val ex = assertThrows<IllegalArgumentException> {
            ApprovalInboxMetadataPolicy.validate(metadata)
        }
        assertThat(ex)
            .hasMessageContaining("approval-inbox-metadata-subjectId-too-long")
    }

    @Test
    fun `rejects control-character subjectId`() {
        val metadata = ApprovalInboxMetadata(subjectId = "claim-123\u0000")
        val ex = assertThrows<IllegalArgumentException> {
            ApprovalInboxMetadataPolicy.validate(metadata)
        }
        assertThat(ex)
            .hasMessageContaining("approval-inbox-metadata-subjectId-control-characters")
    }

    @Test
    fun `rejects suspicious-pattern recommendationType`() {
        val metadata = ApprovalInboxMetadata(recommendationType = "\\N{claim}")
        val ex = assertThrows<IllegalArgumentException> {
            ApprovalInboxMetadataPolicy.validate(metadata)
        }
        assertThat(ex)
            .hasMessageContaining("approval-inbox-metadata-recommendationType-suspicious-pattern")
    }

    @Test
    fun `accepts JSON-like requiredRole because policy only rejects known suspicious patterns`() {
        val metadata = ApprovalInboxMetadata(
            requiredRole = ApproverRole("{\"role\": \"admin\"}"),
        )
        ApprovalInboxMetadataPolicy.validate(metadata)
    }
}
