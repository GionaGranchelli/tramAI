package dev.tramai.engine.approval.testing

import dev.tramai.core.approval.Sha256Digest
import java.time.Duration

/**
 * Configurable defaults for [TestApprovalGatewayRequestFactory].
 *
 * All values have sensible defaults designed for test/example use.
 * Override any field to match scenario-specific metadata without
 * rebuilding the full persistence request manually.
 */
data class TestApprovalGatewayRequestDefaults(
    val requestedBy: String = "test-system",
    val policyVersion: String = "1.0",
    val toolName: String = "test-tool",
    val ttl: Duration = Duration.ofMinutes(5),
    val workflowDigest: Sha256Digest = Sha256Digest.of("sha256:${"1".repeat(64)}"),
    val resumeDefinitionDigest: Sha256Digest = Sha256Digest.of("sha256:${"0".repeat(64)}"),
    val approvalTokenDigest: Sha256Digest = Sha256Digest.of("sha256:${"3".repeat(64)}"),
)
