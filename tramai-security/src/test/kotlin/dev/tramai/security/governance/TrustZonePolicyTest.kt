package dev.tramai.security.governance

import dev.tramai.security.ProviderTrustZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the 0.7.2c trust-zone compatibility rule.
 *
 * One question: may a workload in zone X use a deployment in zone Y? The tests
 * audit that only explicitly listed ordered pairs pass, and that every form of
 * omission — missing pair, reversed pair, same-zone, empty policy — denies.
 */
class TrustZonePolicyTest {
    private val localToEu =
        TrustZonePolicy(
            setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.EU_CLOUD),
        )

    @Test
    fun `an explicitly allowed pair is allowed`() {
        assertTrue(localToEu.allows(ProviderTrustZone.LOCAL, ProviderTrustZone.EU_CLOUD))
    }

    @Test
    fun `the same pair in the opposite direction is denied`() {
        assertFalse(localToEu.allows(ProviderTrustZone.EU_CLOUD, ProviderTrustZone.LOCAL))
    }

    @Test
    fun `an unlisted pair is denied`() {
        assertFalse(localToEu.allows(ProviderTrustZone.LOCAL, ProviderTrustZone.GLOBAL_CLOUD))
        assertFalse(localToEu.allows(ProviderTrustZone.GLOBAL_CLOUD, ProviderTrustZone.EU_CLOUD))
    }

    @Test
    fun `the same zone on both sides is denied unless it is listed`() {
        assertFalse(localToEu.allows(ProviderTrustZone.LOCAL, ProviderTrustZone.LOCAL))
        assertFalse(localToEu.allows(ProviderTrustZone.GLOBAL_CLOUD, ProviderTrustZone.GLOBAL_CLOUD))
    }

    @Test
    fun `the same zone on both sides is allowed when it is listed`() {
        val policy = TrustZonePolicy(setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.LOCAL))

        assertTrue(policy.allows(ProviderTrustZone.LOCAL, ProviderTrustZone.LOCAL))
    }

    @Test
    fun `a policy holding no pairs denies every pair`() {
        val policy = TrustZonePolicy()

        ProviderTrustZone.entries.forEach { workload ->
            ProviderTrustZone.entries.forEach { provider ->
                assertFalse(
                    policy.allows(workload, provider),
                    "empty policy must deny $workload -> $provider",
                )
            }
        }
    }

    @Test
    fun `only the listed pairs of a full policy are allowed`() {
        val listed =
            setOf(
                ProviderTrustZone.LOCAL to ProviderTrustZone.EU_CLOUD,
                ProviderTrustZone.EU_CLOUD to ProviderTrustZone.GLOBAL_CLOUD,
            )
        val policy = TrustZonePolicy(listed)

        ProviderTrustZone.entries.forEach { workload ->
            ProviderTrustZone.entries.forEach { provider ->
                val expected = (workload to provider) in listed
                assertEquals(
                    expected,
                    policy.allows(workload, provider),
                    "allows($workload, $provider)",
                )
            }
        }
    }

    @Test
    fun `a policy cannot be widened by mutating the set it was built from`() {
        val callerOwned = mutableSetOf(ProviderTrustZone.LOCAL to ProviderTrustZone.LOCAL)
        val policy = TrustZonePolicy(callerOwned)

        callerOwned.add(ProviderTrustZone.LOCAL to ProviderTrustZone.GLOBAL_CLOUD)

        assertTrue(policy.allows(ProviderTrustZone.LOCAL, ProviderTrustZone.LOCAL))
        assertFalse(
            policy.allows(ProviderTrustZone.LOCAL, ProviderTrustZone.GLOBAL_CLOUD),
            "a policy must not be widened after construction",
        )
    }
}
