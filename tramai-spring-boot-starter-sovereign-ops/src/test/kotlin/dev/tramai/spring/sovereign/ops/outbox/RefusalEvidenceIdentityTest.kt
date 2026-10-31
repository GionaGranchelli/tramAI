package dev.tramai.spring.sovereign.ops.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 0.7.6f §5.2 discriminator: refusal identity is deterministic and cannot conflate distinct evidence.
 *
 * Two failure modes would both be silent in production: an identity that varies for the same refusal
 * (duplicate records for one logical refusal) and an identity that collapses different refusals (one
 * record standing in for another attempt's evidence).
 */
class RefusalEvidenceIdentityTest {
    @Test
    fun `identical canonical inputs produce an identical stored key`() {
        assertThat(evidence().eventKey).isEqualTo(evidence().eventKey)
        assertThat(evidence().eventKey).isEqualTo(evidence().eventKey)
    }

    @Test
    fun `the stored key is printable, bounded and prefixed`() {
        val key = evidence().eventKey

        assertThat(key).startsWith("refusal-sha256:")
        assertThat(key).hasSize("refusal-sha256:".length + 64)
        assertThat(key.removePrefix("refusal-sha256:")).matches("^[0-9a-f]{64}$")
        // NUL bytes cannot be stored in a PostgreSQL text column; the canonical bytes are hash input only.
        assertThat(key).doesNotContain("\u0000")
    }

    @Test
    fun `every evidence-bearing dimension changes the key`() {
        val baseline = evidence().eventKey

        val variants =
            listOf(
                "operation" to evidence().copy(operation = RefusalOperation.DENY),
                "approval id" to evidence().copy(approvalId = "approval-2"),
                "issuer" to evidence().copy(issuer = "https://other-issuer"),
                "subject" to evidence().copy(subject = "operator-9"),
                "refusal reason" to evidence().copy(refusalReason = "role-not-permitted"),
                "policy version" to evidence().copy(policyVersion = "policy-2"),
                "expected version" to evidence().copy(expectedVersion = 8),
            )

        variants.forEach { (dimension, variant) ->
            assertThat(variant.eventKey)
                .describedAs("key must change with $dimension")
                .isNotEqualTo(baseline)
        }
        // Distinctness is pairwise, not merely "not the baseline": no two variants may collide either.
        assertThat(variants.map { it.second.eventKey }).doesNotHaveDuplicates()
    }

    @Test
    fun `consulted policy and absent policy are different inputs`() {
        assertThat(evidence().eventKey)
            .isNotEqualTo(evidence().copy(policyVersion = "policy-1").eventKey)
    }

    @Test
    fun `an empty policy version cannot masquerade as a consulted one`() {
        assertThatThrownBy { evidence().copy(policyVersion = "") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-refusal-evidence-policy-version-blank")
    }

    @Test
    fun `issuer and subject are bound as a pair`() {
        assertThat(evidence().copy(issuer = "issuer-a", subject = "subject-b").eventKey)
            .isNotEqualTo(evidence().copy(issuer = "subject-b", subject = "issuer-a").eventKey)
    }

    @Test
    fun `a length-prefixed encoding keeps separator-like values distinct`() {
        // Delimiter-joined encodings splice these two into the same input.
        assertThat(evidence().copy(refusalReason = "a|b").eventKey)
            .isNotEqualTo(evidence().copy(refusalReason = "a", policyVersion = "b").eventKey)
    }

    @Test
    fun `blank identifiers are rejected rather than hashed`() {
        assertThatThrownBy { evidence().copy(approvalId = " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-refusal-evidence-approval-id-blank")
        assertThatThrownBy { evidence().copy(refusalReason = "") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tramai-refusal-evidence-reason-blank")
    }

    /** One base refusal; each test varies exactly the dimension it is about. */
    private fun evidence(): RefusalEvidence =
        RefusalEvidence(
            operation = RefusalOperation.APPROVE,
            approvalId = "approval-1",
            issuer = "https://issuer.example",
            subject = "operator-1",
            refusalReason = "missing-capability",
            policyVersion = null,
            expectedVersion = 7,
        )
}
