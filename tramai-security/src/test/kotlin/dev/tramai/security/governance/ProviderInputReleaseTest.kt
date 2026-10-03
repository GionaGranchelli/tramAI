package dev.tramai.security.governance

import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the 0.7.2d provider-input release boundary.
 *
 * One question: given a compatible trust-zone decision, may this specific
 * provider-bound input be released? The tests audit that release needs BOTH
 * authorities to agree for the same input, and that neither can widen the other.
 */
class ProviderInputReleaseTest {
    /** The only permitted zone pair throughout: LOCAL -> EU_CLOUD. */
    private val zonesAllowLocalToEu =
        TrustZonePolicy(
            setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.EU_CLOUD),
        )

    /**
     * CONFIDENTIAL: EU_CLOUD only. INTERNAL: EU_CLOUD or LOCAL, with EU_CLOUD as
     * its only fallback zone. RESTRICTED: LOCAL only. PUBLIC: no rule at all.
     */
    private val rules =
        mapOf(
            DataClassification.CONFIDENTIAL to
                ClassificationRoutingRule(
                    allowedZones = setOf(ProviderTrustZone.EU_CLOUD),
                    allowedFallbackZones = emptySet(),
                ),
            DataClassification.INTERNAL to
                ClassificationRoutingRule(
                    allowedZones = setOf(ProviderTrustZone.EU_CLOUD, ProviderTrustZone.LOCAL),
                    allowedFallbackZones = setOf(ProviderTrustZone.EU_CLOUD),
                ),
            DataClassification.RESTRICTED to
                ClassificationRoutingRule(
                    allowedZones = setOf(ProviderTrustZone.LOCAL),
                    allowedFallbackZones = emptySet(),
                ),
        )

    private val release = ProviderInputRelease(zonesAllowLocalToEu, rules)

    @Test
    fun `both authorities agreeing releases the input`() {
        assertTrue(
            release.releases(ProviderTrustZone.LOCAL, DataClassification.INTERNAL, ProviderTrustZone.EU_CLOUD),
        )
    }

    @Test
    fun `a compatible zone pair does not release a classification with no rule`() {
        assertFalse(
            release.releases(ProviderTrustZone.LOCAL, DataClassification.PUBLIC, ProviderTrustZone.EU_CLOUD),
            "a classification absent from the rules must release nothing",
        )
    }

    @Test
    fun `a rule excluding the zone withholds even when the zones are compatible`() {
        assertTrue(zonesAllowLocalToEu.allows(ProviderTrustZone.LOCAL, ProviderTrustZone.EU_CLOUD))

        assertFalse(
            release.releases(ProviderTrustZone.LOCAL, DataClassification.RESTRICTED, ProviderTrustZone.EU_CLOUD),
            "RESTRICTED permits LOCAL only, so a compatible zone pair must still withhold it",
        )
    }

    @Test
    fun `incompatible zones withhold even when the classification permits that zone`() {
        assertTrue(rules.getValue(DataClassification.INTERNAL).allowedZones.contains(ProviderTrustZone.LOCAL))

        assertFalse(
            release.releases(ProviderTrustZone.EU_CLOUD, DataClassification.INTERNAL, ProviderTrustZone.LOCAL),
            "a classification that permits LOCAL must not release across incompatible zones",
        )
    }

    @Test
    fun `default construction releases nothing`() {
        val nothing = ProviderInputRelease()

        ProviderTrustZone.entries.forEach { workloadZone ->
            ProviderTrustZone.entries.forEach { providerZone ->
                DataClassification.entries.forEach { classification ->
                    assertFalse(
                        nothing.releases(workloadZone, classification, providerZone),
                        "an unconfigured release boundary must withhold $classification -> $providerZone",
                    )
                }
            }
        }
    }

    @Test
    fun `the release path consults the zone allow-list and not the fallback allow-list`() {
        val localToLocal =
            ProviderInputRelease(
                TrustZonePolicy(setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.LOCAL)),
                rules,
            )

        assertFalse(rules.getValue(DataClassification.INTERNAL).allowedFallbackZones.contains(ProviderTrustZone.LOCAL))
        assertTrue(
            localToLocal.releases(ProviderTrustZone.LOCAL, DataClassification.INTERNAL, ProviderTrustZone.LOCAL),
            "LOCAL is in INTERNAL's allowedZones, so it releases even though it is not a fallback zone",
        )
    }

    @Test
    fun `only the zone pair and the classification rule together release`() {
        val pairs = setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.EU_CLOUD)

        ProviderTrustZone.entries.forEach { workloadZone ->
            ProviderTrustZone.entries.forEach { providerZone ->
                DataClassification.entries.forEach { classification ->
                    val expected =
                        (workloadZone to providerZone) in pairs &&
                            rules[classification]?.allowedZones?.contains(providerZone) == true
                    assertTrue(
                        release.releases(workloadZone, classification, providerZone) == expected,
                        "expected releases($workloadZone, $classification, $providerZone) == $expected",
                    )
                }
            }
        }
    }
}
