package dev.tramai.engine.approval

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The attribution parser is an identity boundary: presence and validity are separate equivalence
 * classes, and malformed governed state must never be downgraded into legacy state.
 *
 * The all-blank case is the one that hides: every key is present, so a "treat blanks as absent"
 * reading classifies corrupt governed state as an intentionally un-attributed legacy approval —
 * exactly the silent downgrade 0.7.1d1 exists to prevent.
 */
class ApprovalAttributionPresenceCorruptionTest {
    private val keys = ApprovalAttributionKeys.ALL

    @Test
    fun `no reserved keys is an intentionally unattributed legacy approval`() {
        assertThat(decodeApprovalAttribution("run-1", emptyMap()))
            .isEqualTo(ApprovalRunAttribution.Ungoverned)
    }

    @Test
    fun `all reserved keys present but blank is corruption never legacy`() {
        val blank = keys.associateWith { "   " }

        assertThrows<ApprovalAttributionCorruptionException> {
            decodeApprovalAttribution("run-1", blank)
        }
    }

    @Test
    fun `a partial reserved set is corruption`() {
        val partial = keys.drop(1).associateWith { "value" }

        assertThrows<ApprovalAttributionCorruptionException> {
            decodeApprovalAttribution("run-1", partial)
        }
    }

    @Test
    fun `a single blank among valid values is corruption`() {
        val mixed = keys.associateWith { "value" }.toMutableMap().apply { this[keys.first()] = "" }

        assertThrows<ApprovalAttributionCorruptionException> {
            decodeApprovalAttribution("run-1", mixed)
        }
    }
}
