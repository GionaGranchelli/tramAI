package dev.tramai.security.governance

import dev.tramai.controlplane.QueryConsistency
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 0.7.4g M08 — the freshness authority table, pinned branch by branch.
 *
 * `GovernanceFactClassification.freshnessFor` is the one place that decides how a read's consistency becomes a
 * freshness claim, and its own KDoc says why it lives in one place: inverting it is a silent authority violation
 * rather than a visible failure. The mutation campaign found that promoting the last branch to
 * `CURRENT_AT_VERSION` was not detected by any test, because the table was only ever exercised through a reader
 * that always supplies a watermark when the read is not authoritative.
 *
 * That makes the mutated branch unreachable from today's single call site, but an unpinned branch in an authority
 * table is a defect waiting for its second caller, so the whole table is asserted directly here.
 */
internal class GovernanceFactClassificationTest {
    @Test
    fun `an authoritative read is current at the version it observed`() {
        assertThat(GovernanceFactClassification.freshnessFor(QueryConsistency.AUTHORITATIVE, null))
            .isEqualTo(FactFreshness.CURRENT_AT_VERSION)
        // The authoritative branch wins over a watermark: authority, not the presence of a number, decides.
        assertThat(GovernanceFactClassification.freshnessFor(QueryConsistency.AUTHORITATIVE, 7L))
            .isEqualTo(FactFreshness.CURRENT_AT_VERSION)
    }

    @Test
    fun `a derived read with a named watermark claims only a known lag`() {
        assertThat(GovernanceFactClassification.freshnessFor(QueryConsistency.PROJECTION, 7L))
            .isEqualTo(FactFreshness.EVENTUAL_KNOWN_LAG)
    }

    @Test
    fun `a derived read with no watermark establishes no freshness at all`() {
        // Unknown lag is not zero lag, and it is certainly not current.
        val freshness = GovernanceFactClassification.freshnessFor(QueryConsistency.PROJECTION, null)
        assertThat(freshness).isEqualTo(FactFreshness.STALE_OR_UNKNOWN)
        assertThat(freshness).isNotEqualTo(FactFreshness.CURRENT_AT_VERSION)
    }
}
