package dev.tramai.server

import dev.tramai.controlplane.WorkloadStateVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `If-Match` precondition grammar (0.7.1e).
 *
 * The contract accepts exactly one strong numeric ETag. Everything else is rejected rather than
 * interpreted, because a precondition that is silently ignored turns a version-guarded mutation
 * into a last-write-wins write.
 */
class WorkloadPreconditionTest {
    @Test
    fun `a single strong numeric ETag is the expected version`() {
        assertThat(parseWorkloadPrecondition("\"17\""))
            .isEqualTo(WorkloadPrecondition.Expected(WorkloadStateVersion(17)))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertThat(parseWorkloadPrecondition("  \"1\"  "))
            .isEqualTo(WorkloadPrecondition.Expected(WorkloadStateVersion.INITIAL))
    }

    @Test
    fun `an absent header is a missing precondition, not a malformed one`() {
        assertThat(parseWorkloadPrecondition(null)).isEqualTo(WorkloadPrecondition.Missing)
        assertThat(parseWorkloadPrecondition("")).isEqualTo(WorkloadPrecondition.Missing)
        assertThat(parseWorkloadPrecondition("   ")).isEqualTo(WorkloadPrecondition.Missing)
    }

    @Test
    fun `unsupported forms are rejected instead of interpreted`() {
        // `*` names no version: accepting it would authorize a mutation with no expected version.
        assertThat(parseWorkloadPrecondition("*")).isEqualTo(WorkloadPrecondition.Unsupported)
        // weak tag: not a version-specific precondition
        assertThat(parseWorkloadPrecondition("W/\"17\"")).isEqualTo(WorkloadPrecondition.Unsupported)
        // alternatives: the authority has exactly one version
        assertThat(parseWorkloadPrecondition("\"16\", \"17\"")).isEqualTo(WorkloadPrecondition.Unsupported)
        assertThat(parseWorkloadPrecondition("\"16\",\"17\"")).isEqualTo(WorkloadPrecondition.Unsupported)
        // malformed / non-numeric / unquoted
        assertThat(parseWorkloadPrecondition("abc")).isEqualTo(WorkloadPrecondition.Unsupported)
        assertThat(parseWorkloadPrecondition("17")).isEqualTo(WorkloadPrecondition.Unsupported)
        assertThat(parseWorkloadPrecondition("\"\"")).isEqualTo(WorkloadPrecondition.Unsupported)
        assertThat(parseWorkloadPrecondition("\"abc\"")).isEqualTo(WorkloadPrecondition.Unsupported)
        assertThat(parseWorkloadPrecondition("\"1.5\"")).isEqualTo(WorkloadPrecondition.Unsupported)
        assertThat(parseWorkloadPrecondition("\"-1\"")).isEqualTo(WorkloadPrecondition.Unsupported)
        // "0" and leading zeros are not state versions (versions start at 1)
        assertThat(parseWorkloadPrecondition("\"0\"")).isEqualTo(WorkloadPrecondition.Unsupported)
        assertThat(parseWorkloadPrecondition("\"007\"")).isEqualTo(WorkloadPrecondition.Unsupported)
        // beyond Long range
        assertThat(parseWorkloadPrecondition("\"99999999999999999999\""))
            .isEqualTo(WorkloadPrecondition.Unsupported)
    }

    @Test
    fun `the ETag for a version is one strong numeric tag`() {
        assertThat(workloadEtag(WorkloadStateVersion(7))).isEqualTo("\"7\"")
        assertThat(parseWorkloadPrecondition(workloadEtag(WorkloadStateVersion(7))))
            .isEqualTo(WorkloadPrecondition.Expected(WorkloadStateVersion(7)))
    }
}
