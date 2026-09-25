package dev.tramai.security.evidence

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * Contract tests for the family-specific metadata rules of [RuntimeEvidenceContractValidator].
 *
 * The validator is what stops the writer from emitting a bundle the verifier rejects, so the
 * rules are asserted as a contract rather than as coverage: a record that violates a rule must
 * be rejected with a message naming that rule, and a record that satisfies it must pass. The
 * positive cases are not decoration — several rules fail closed on valid input when inverted,
 * so an accepted record is the discriminator that proves the check is not simply absent.
 */
class RuntimeEvidenceContractValidatorTest {
    private val timestamp = Instant.parse("2026-09-25T10:00:00Z")
    private val subjectDigest = "sha256:" + "a".repeat(64)
    private val payloadDigest = "sha256:" + "b".repeat(64)

    // ─── approval.decision: digest and numeric metadata ─────────────────

    @Test
    fun `approval metadata accepts well formed digests and numeric fields`() {
        validate(
            approvalRecord(
                metadata =
                    mapOf(
                        "reasonDigest" to subjectDigest,
                        "eventKeyDigest" to payloadDigest,
                        "reasonLength" to "0",
                        "approvalVersion" to "3",
                    ),
            ),
        )
    }

    @Test
    fun `approval metadata accepts an absent optional digest`() {
        validate(approvalRecord(metadata = mapOf("approvalVersion" to "1")))
    }

    @Test
    fun `approval metadata rejects a malformed reasonDigest`() {
        rejects(
            "Metadata reasonDigest must match",
            approvalRecord(metadata = mapOf("reasonDigest" to "sha256:not-a-digest")),
        )
    }

    @Test
    fun `approval metadata rejects a malformed eventKeyDigest`() {
        rejects(
            "Metadata eventKeyDigest must match",
            approvalRecord(metadata = mapOf("eventKeyDigest" to "deadbeef")),
        )
    }

    @Test
    fun `approval metadata rejects a negative reasonLength`() {
        rejects(
            "Metadata reasonLength must be a non-negative integer",
            approvalRecord(metadata = mapOf("reasonLength" to "-1")),
        )
    }

    @Test
    fun `approval metadata rejects a non numeric approvalVersion`() {
        rejects(
            "Metadata approvalVersion must be a non-negative integer",
            approvalRecord(metadata = mapOf("approvalVersion" to "v1")),
        )
    }

    // ─── tool.permission: mandatory toolName, known enforcement point ───

    @Test
    fun `tool permission metadata accepts a known enforcement point and risk level`() {
        validate(
            toolPermissionRecord(
                metadata =
                    mapOf(
                        "toolName" to "weather-tool",
                        "enforcementPoint" to "BEFORE_TOOL_EXECUTION",
                        "riskLevel" to "HIGH",
                    ),
            ),
        )
    }

    @Test
    fun `tool permission metadata accepts an absent optional risk level`() {
        validate(
            toolPermissionRecord(
                metadata =
                    mapOf(
                        "toolName" to "weather-tool",
                        "enforcementPoint" to "BEFORE_TOOL_EXPOSURE",
                    ),
            ),
        )
    }

    @Test
    fun `tool permission metadata rejects a missing toolName`() {
        rejects(
            "Metadata toolName is required",
            toolPermissionRecord(metadata = mapOf("enforcementPoint" to "BEFORE_TOOL_EXECUTION")),
        )
    }

    @Test
    fun `tool permission metadata rejects a blank toolName`() {
        rejects(
            "Metadata toolName is required",
            toolPermissionRecord(
                metadata =
                    mapOf(
                        "toolName" to "   ",
                        "enforcementPoint" to "BEFORE_TOOL_EXECUTION",
                    ),
            ),
        )
    }

    @Test
    fun `tool permission metadata rejects an unknown enforcement point`() {
        rejects(
            "Metadata enforcementPoint must be one of",
            toolPermissionRecord(
                metadata =
                    mapOf(
                        "toolName" to "weather-tool",
                        "enforcementPoint" to "BEFORE_TOOL_RETRY",
                    ),
            ),
        )
    }

    @Test
    fun `tool permission metadata rejects an unknown risk level`() {
        rejects(
            "Metadata riskLevel must be one of",
            toolPermissionRecord(
                metadata =
                    mapOf(
                        "toolName" to "weather-tool",
                        "enforcementPoint" to "BEFORE_TOOL_RESULT_REINJECTION",
                        "riskLevel" to "SEVERE",
                    ),
            ),
        )
    }

    @Test
    fun `tool permission metadata rejects a key that is not allowlisted`() {
        rejects(
            "is not allowlisted",
            toolPermissionRecord(
                metadata =
                    mapOf(
                        "toolName" to "weather-tool",
                        "enforcementPoint" to "BEFORE_TOOL_EXECUTION",
                        "riskScore" to "9",
                    ),
            ),
        )
    }

    // ─── reason codes are family-scoped ─────────────────────────────────

    @Test
    fun `approval rejects a reason code that belongs to another family`() {
        rejects(
            "decision.reasonCode must be one of",
            approvalRecord(metadata = mapOf("approvalVersion" to "1"), reasonCode = "provider-selected"),
        )
    }

    @Test
    fun `tool permission rejects a reason code that is not code shaped`() {
        rejects(
            "decision.reasonCode must match",
            toolPermissionRecord(
                metadata =
                    mapOf(
                        "toolName" to "weather-tool",
                        "enforcementPoint" to "BEFORE_TOOL_EXECUTION",
                    ),
                reasonCode = "not a code",
            ),
        )
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private fun approvalRecord(
        metadata: Map<String, String>,
        reasonCode: String = "approval-approved",
    ) = record("approval.decision", "approval-control-plane", "APPROVED", reasonCode, metadata)

    private fun toolPermissionRecord(
        metadata: Map<String, String>,
        reasonCode: String = "tool_allowed",
    ) = record("tool.permission", "policy-engine", "ALLOW", reasonCode, metadata)

    private fun record(
        eventType: String,
        sourceComponent: String,
        kind: String,
        reasonCode: String,
        metadata: Map<String, String>,
    ) = RuntimeEvidenceRecord(
        eventId = "evt-" + eventType.replace('.', '-'),
        eventType = eventType,
        workflowRunId = "wf-001",
        correlationId = "corr-001",
        actor = sourceComponent,
        createdAt = timestamp,
        source = RuntimeEvidenceSource(component = sourceComponent, module = "tramai-security"),
        decision = RuntimeEvidenceDecision(kind = kind, reasonCode = reasonCode),
        digests = RuntimeEvidenceDigests(subjectDigest = subjectDigest, payloadDigest = payloadDigest),
        metadata = metadata,
    )

    private fun validate(record: RuntimeEvidenceRecord) {
        RuntimeEvidenceContractValidator.validate(listOf(record))
    }

    private fun rejects(
        expectedMessage: String,
        record: RuntimeEvidenceRecord,
    ) {
        val failure =
            assertThrows<IllegalArgumentException> {
                RuntimeEvidenceContractValidator.validate(listOf(record))
            }
        assertTrue(
            failure.message.orEmpty().contains(expectedMessage),
            "expected message containing '$expectedMessage', got: ${failure.message}",
        )
    }
}
