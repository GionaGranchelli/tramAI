package dev.tramai.security.governance

import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for 0.7.2e restrictive effective-policy composition.
 *
 * One invariant: a narrower scope may restrict authority, never widen it. These
 * tests attack that from both directions — a narrower scope naming a zone the
 * parent denied, and a narrower scope introducing a classification the
 * organization never defined.
 */
class EffectiveRoutingPolicyTest {
    private val confidential =
        ClassificationRoutingRule(
            allowedZones = setOf(ProviderTrustZone.LOCAL, ProviderTrustZone.EU_CLOUD),
            allowedFallbackZones = setOf(ProviderTrustZone.EU_CLOUD),
        )

    private val internal =
        ClassificationRoutingRule(
            allowedZones =
                setOf(
                    ProviderTrustZone.LOCAL,
                    ProviderTrustZone.EU_CLOUD,
                    ProviderTrustZone.GLOBAL_CLOUD,
                ),
            allowedFallbackZones = setOf(ProviderTrustZone.LOCAL, ProviderTrustZone.EU_CLOUD),
        )

    private val organization =
        mapOf(
            DataClassification.CONFIDENTIAL to confidential,
            DataClassification.INTERNAL to internal,
        )

    private val environment =
        mapOf(
            DataClassification.CONFIDENTIAL to
                ClassificationRoutingRule(
                    allowedZones = setOf(ProviderTrustZone.EU_CLOUD),
                    allowedFallbackZones = setOf(ProviderTrustZone.EU_CLOUD),
                ),
            DataClassification.INTERNAL to
                ClassificationRoutingRule(
                    allowedZones = setOf(ProviderTrustZone.LOCAL, ProviderTrustZone.EU_CLOUD),
                    allowedFallbackZones = setOf(ProviderTrustZone.LOCAL),
                ),
        )

    private val workload =
        mapOf(
            DataClassification.CONFIDENTIAL to
                ClassificationRoutingRule(
                    allowedZones = setOf(ProviderTrustZone.EU_CLOUD),
                    allowedFallbackZones = emptySet(),
                ),
            DataClassification.INTERNAL to
                ClassificationRoutingRule(
                    allowedZones = setOf(ProviderTrustZone.LOCAL),
                    allowedFallbackZones = setOf(ProviderTrustZone.LOCAL),
                ),
        )

    @Test
    fun `identical scopes leave the policy unchanged`() {
        val effective = effectiveRoutingRules(organization, organization, organization)

        assertEquals(organization, effective)
    }

    @Test
    fun `a narrower environment restricts the organization's zones`() {
        val effective = effectiveRoutingRules(organization, environment)

        val confidentialZones = effective.getValue(DataClassification.CONFIDENTIAL).allowedZones
        val internalZones = effective.getValue(DataClassification.INTERNAL).allowedZones

        assertEquals(setOf(ProviderTrustZone.EU_CLOUD), confidentialZones)
        assertEquals(setOf(ProviderTrustZone.LOCAL, ProviderTrustZone.EU_CLOUD), internalZones)
    }

    @Test
    fun `a workload scope restricts further`() {
        val effective = effectiveRoutingRules(organization, environment, workload)

        val confidentialZones = effective.getValue(DataClassification.CONFIDENTIAL).allowedZones
        val internalZones = effective.getValue(DataClassification.INTERNAL).allowedZones

        assertEquals(setOf(ProviderTrustZone.EU_CLOUD), confidentialZones)
        assertEquals(setOf(ProviderTrustZone.LOCAL), internalZones)
    }

    @Test
    fun `a narrower scope naming a zone the parent denied collapses to none`() {
        val widening =
            mapOf(
                DataClassification.CONFIDENTIAL to
                    ClassificationRoutingRule(
                        allowedZones = setOf(ProviderTrustZone.GLOBAL_CLOUD),
                        allowedFallbackZones = emptySet(),
                    ),
            )

        val effective = effectiveRoutingRules(organization, widening)

        assertTrue(effective.containsKey(DataClassification.CONFIDENTIAL))
        assertTrue(
            effective.getValue(DataClassification.CONFIDENTIAL).allowedZones.isEmpty(),
            "a zone outside the organization's authority must not survive composition",
        )
    }

    @Test
    fun `a classification the organization never defined is absent from the effective policy`() {
        val introducing =
            mapOf(
                DataClassification.RESTRICTED to
                    ClassificationRoutingRule(
                        allowedZones = setOf(ProviderTrustZone.LOCAL),
                        allowedFallbackZones = emptySet(),
                    ),
            )

        val effective = effectiveRoutingRules(organization, introducing, introducing)

        assertFalse(effective.containsKey(DataClassification.RESTRICTED))
        assertEquals(organization.keys, effective.keys)
    }

    @Test
    fun `a scope silent about a classification imposes no restriction`() {
        val effective = effectiveRoutingRules(organization, emptyMap(), workload)

        val confidentialZones = effective.getValue(DataClassification.CONFIDENTIAL).allowedZones
        val internalZones = effective.getValue(DataClassification.INTERNAL).allowedZones

        assertEquals(setOf(ProviderTrustZone.EU_CLOUD), confidentialZones)
        assertEquals(setOf(ProviderTrustZone.LOCAL), internalZones)
    }

    @Test
    fun `an organization silent about a classification authorizes nothing`() {
        val effective = effectiveRoutingRules(emptyMap(), environment, workload)

        assertTrue(effective.isEmpty())
    }

    @Test
    fun `fallback zones are intersected and stay within the effective allowed zones`() {
        val effective = effectiveRoutingRules(organization, environment, workload)

        val composedConfidential = effective.getValue(DataClassification.CONFIDENTIAL)
        assertEquals(emptySet<ProviderTrustZone>(), composedConfidential.allowedFallbackZones)

        val composedInternal = effective.getValue(DataClassification.INTERNAL)
        assertEquals(setOf(ProviderTrustZone.LOCAL), composedInternal.allowedFallbackZones)
        assertTrue(composedInternal.allowedZones.containsAll(composedInternal.allowedFallbackZones))
    }

    @Test
    fun `the effective policy never exceeds the authority of any scope`() {
        val scopes = listOf(organization, environment, workload)

        val effective = effectiveRoutingRules(organization, environment, workload)

        effective.forEach { (classification, rule) ->
            scopes.forEach { scope ->
                scope[classification]?.let { scopeRule ->
                    assertTrue(
                        scopeRule.allowedZones.containsAll(rule.allowedZones),
                        "$classification: effective zones must stay within the scope that defined them",
                    )
                    assertTrue(
                        scopeRule.allowedFallbackZones.containsAll(rule.allowedFallbackZones),
                        "$classification: effective fallback zones must stay within the defining scope",
                    )
                }
            }
        }
    }

    @Test
    fun `composition preserves a fallback violation rather than repairing it`() {
        // ClassificationRoutingRule is a plain data class with no validation, so a
        // rule whose fallback set exceeds its allowed set can be constructed. If it
        // validated, this test would not compile past construction.
        val sloppy =
            ClassificationRoutingRule(
                allowedZones = setOf(ProviderTrustZone.LOCAL),
                allowedFallbackZones = setOf(ProviderTrustZone.LOCAL, ProviderTrustZone.EU_CLOUD),
            )

        val composed =
            effectiveRoutingRules(mapOf(DataClassification.INTERNAL to sloppy))
                .getValue(DataClassification.INTERNAL)

        assertEquals(setOf(ProviderTrustZone.LOCAL), composed.allowedZones)
        assertEquals(
            setOf(ProviderTrustZone.LOCAL, ProviderTrustZone.EU_CLOUD),
            composed.allowedFallbackZones,
            "composition must not silently repair a violation it did not introduce",
        )
    }
}
