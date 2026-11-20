package dev.tramai.security.governance

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The failure path of the raw-stream coverage contract.
 *
 * These guards are what stop a caller being handed coverage that cannot be true: a position classified
 * outside the range it is claimed to belong to, an account that names positions no range verified, or
 * an account larger than the range it is measured against. Each is asserted here rather than in the
 * projection test, because a guard that is never handed an invalid value is a guard nobody has run.
 */
class StreamCoverageTest {
    @Test
    fun `coverage that classifies a position outside its verified range is refused`() {
        assertFailsWith<IllegalArgumentException> {
            StreamCoverage(verifiedRange = 1L..3L, unrecordedDecisionPositions = setOf(4L))
        }
    }

    @Test
    fun `coverage that classifies a position without a verified range is refused`() {
        assertFailsWith<IllegalArgumentException> {
            StreamCoverage(unclassifiedPositions = setOf(1L))
        }
    }

    @Test
    fun `coverage that accounts for a position without a verified range is refused`() {
        assertFailsWith<IllegalArgumentException> {
            StreamCoverage(accountedPositions = 1L)
        }
    }

    @Test
    fun `coverage that accounts for more positions than it verified is refused`() {
        assertFailsWith<IllegalArgumentException> {
            StreamCoverage(verifiedRange = 1L..3L, accountedPositions = 4L)
        }
    }

    @Test
    fun `coverage with a negative account is refused`() {
        assertFailsWith<IllegalArgumentException> {
            StreamCoverage(verifiedRange = 1L..3L, accountedPositions = -1L)
        }
    }
}
