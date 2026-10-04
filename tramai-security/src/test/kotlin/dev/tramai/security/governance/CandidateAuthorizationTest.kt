package dev.tramai.security.governance

import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the 0.7.3 candidate decision model.
 *
 * The fixtures and the expected verdicts are the ones the Boolean boundary was
 * audited against, so these tests prove the migration changed the *shape* of the
 * answer and not the answer: every previously authorized candidate is AUTHORIZED,
 * every previously denied candidate is NOT_AUTHORIZED. The added tests audit what
 * was previously unexpressible — the refusal reason — plus the fact that no
 * viability decision can be produced by this boundary.
 */
class CandidateAuthorizationTest {
    /** The only permitted zone pair throughout: LOCAL -> EU_CLOUD. */
    private val zonesAllowLocalToEu =
        TrustZonePolicy(
            setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.EU_CLOUD),
        )

    /**
     * CONFIDENTIAL: EU_CLOUD only. INTERNAL: EU_CLOUD or LOCAL. RESTRICTED: LOCAL
     * only. PUBLIC: no rule at all.
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
                    allowedFallbackZones = emptySet(),
                ),
            DataClassification.RESTRICTED to
                ClassificationRoutingRule(
                    allowedZones = setOf(ProviderTrustZone.LOCAL),
                    allowedFallbackZones = emptySet(),
                ),
        )

    private val authorization = CandidateAuthorization(ProviderInputRelease(zonesAllowLocalToEu, rules))

    private fun deployment(
        deploymentId: String,
        providerId: String,
        zone: ProviderTrustZone,
    ): ProviderDeployment =
        ProviderDeployment(
            deploymentId = deploymentId,
            providerId = providerId,
            trustZone = NamedTrustZone(TrustZoneName("$deploymentId-zone"), zone),
        )

    private val euDeployment = deployment("dep-eu", "openai", ProviderTrustZone.EU_CLOUD)
    private val localDeployment = deployment("dep-local", "vllm", ProviderTrustZone.LOCAL)
    private val globalDeployment = deployment("dep-global", "openai", ProviderTrustZone.GLOBAL_CLOUD)

    private fun candidate(
        providerId: String,
        modelId: String,
        deployment: ProviderDeployment,
    ) = ProviderCandidate(providerId, modelId, deployment)

    private fun decisionOf(
        candidate: ProviderCandidate,
        classification: DataClassification = DataClassification.INTERNAL,
    ) = authorization.decisionFor(candidate, ProviderTrustZone.LOCAL, classification)

    // --- 1. every previously authorized candidate is AUTHORIZED ---------------

    @Test
    fun `an explicitly authorized candidate is AUTHORIZED`() {
        val authorized = candidate("openai", "gpt-4o", euDeployment)

        assertEquals(CandidateAuthorizationDecision.Authorized, decisionOf(authorized))
        assertEquals(
            setOf(authorized),
            authorization.authorizedSet(listOf(authorized), ProviderTrustZone.LOCAL, DataClassification.INTERNAL),
        )
    }

    // --- 2. every previously denied candidate is NOT_AUTHORIZED ---------------

    @Test
    fun `a candidate whose zone no policy pair allows is NOT_AUTHORIZED for that reason`() {
        val denied = candidate("openai", "gpt-4o", globalDeployment)

        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED),
            decisionOf(denied),
        )
        assertTrue(
            authorization
                .authorizedSet(listOf(denied), ProviderTrustZone.LOCAL, DataClassification.INTERNAL)
                .isEmpty(),
        )
    }

    @Test
    fun `a candidate the classification rule forbids is NOT_AUTHORIZED for that reason`() {
        val denied = candidate("openai", "gpt-4o", euDeployment)

        // LOCAL -> EU_CLOUD is an allowed pair; RESTRICTED permits only LOCAL.
        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(
                AuthorizationRefusal.CLASSIFICATION_ZONE_NOT_PERMITTED,
            ),
            decisionOf(denied, DataClassification.RESTRICTED),
        )
    }

    @Test
    fun `a candidate for a classification with no rule is NOT_AUTHORIZED`() {
        val denied = candidate("openai", "gpt-4o", euDeployment)

        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(
                AuthorizationRefusal.CLASSIFICATION_ZONE_NOT_PERMITTED,
            ),
            decisionOf(denied, DataClassification.PUBLIC),
        )
    }

    @Test
    fun `a candidate whose identity disagrees with its deployment is NOT_AUTHORIZED`() {
        val inconsistent = candidate("anthropic", "claude", euDeployment)

        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.IDENTITY_DEPLOYMENT_MISMATCH),
            decisionOf(inconsistent),
        )
    }

    @Test
    fun `same-zone compatibility is not implicitly authorized`() {
        val sameZone = candidate("vllm", "llama3", localDeployment)

        // RESTRICTED permits LOCAL, but LOCAL -> LOCAL is not a listed pair.
        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED),
            decisionOf(sameZone, DataClassification.RESTRICTED),
        )
    }

    // --- 3. a refusal always names a reason from the stable family -------------

    @Test
    fun `every refusal carries a member of the stable reason family`() {
        val refusals =
            listOf(
                decisionOf(candidate("openai", "gpt-4o", globalDeployment)),
                decisionOf(candidate("openai", "gpt-4o", euDeployment), DataClassification.RESTRICTED),
                decisionOf(candidate("openai", "gpt-4o", euDeployment), DataClassification.PUBLIC),
                decisionOf(candidate("anthropic", "claude", euDeployment)),
                decisionOf(candidate("vllm", "llama3", localDeployment), DataClassification.RESTRICTED),
            )

        assertEquals(refusals.size, refusals.count { it is CandidateAuthorizationDecision.NotAuthorized })
        refusals.forEach { decision ->
            val notAuthorized = decision as CandidateAuthorizationDecision.NotAuthorized
            assertTrue(notAuthorized.reason in AuthorizationRefusal.entries)
        }
    }

    @Test
    fun `the reason family has exactly the three restrictions this boundary consults`() {
        assertEquals(
            listOf(
                AuthorizationRefusal.IDENTITY_DEPLOYMENT_MISMATCH,
                AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED,
                AuthorizationRefusal.CLASSIFICATION_ZONE_NOT_PERMITTED,
            ),
            AuthorizationRefusal.entries.toList(),
        )
    }

    @Test
    fun `identity refusal takes precedence over the other restrictions`() {
        val inconsistentAndZoneDenied = candidate("anthropic", "claude", globalDeployment)

        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.IDENTITY_DEPLOYMENT_MISMATCH),
            decisionOf(inconsistentAndZoneDenied, DataClassification.RESTRICTED),
        )
    }

    @Test
    fun `the zone-pair refusal takes precedence over the classification refusal`() {
        // LOCAL -> LOCAL is unlisted AND RESTRICTED omits LOCAL: the zone authority refuses first.
        val sameZoneRestricted = candidate("vllm", "llama3", localDeployment)

        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED),
            decisionOf(sameZoneRestricted, DataClassification.RESTRICTED),
        )
    }

    // --- 5. no viability decision is performed by this boundary ----------------

    @Test
    fun `the viability vocabulary names the families this repository can report`() {
        // Updated by 0.7.3d on two counts: HEALTH was removed when the first producer
        // landed (nothing reports provider health), and CAPABILITY was removed because
        // the epic puts capability in authorization, not viability. The authorization
        // boundary still cannot express viability at all: it returns
        // CandidateAuthorizationDecision, which has no viability member.
        assertEquals(
            listOf(ViabilityRefusal.AVAILABILITY),
            ViabilityRefusal.entries.sortedBy { it.name },
        )
    }

    // --- ordering, duplication and denial-by-default (unchanged semantics) -----

    @Test
    fun `candidate ordering does not change the authorized set`() {
        val authorized = candidate("openai", "gpt-4o", euDeployment)
        val deniedZone = candidate("openai", "gpt-4o", globalDeployment)
        val deniedIdentity = candidate("anthropic", "claude", euDeployment)
        val candidates = listOf(authorized, deniedZone, deniedIdentity)

        val forward =
            authorization.authorizedSet(candidates, ProviderTrustZone.LOCAL, DataClassification.INTERNAL)
        val reversed =
            authorization.authorizedSet(candidates.reversed(), ProviderTrustZone.LOCAL, DataClassification.INTERNAL)

        assertEquals(setOf(authorized), forward)
        assertEquals(forward, reversed)
    }

    @Test
    fun `duplicate candidates do not widen the authorized set`() {
        val authorized = candidate("openai", "gpt-4o", euDeployment)
        val once = authorization.authorizedSet(listOf(authorized), ProviderTrustZone.LOCAL, DataClassification.INTERNAL)
        val repeated =
            authorization.authorizedSet(
                listOf(authorized, authorized, authorized),
                ProviderTrustZone.LOCAL,
                DataClassification.INTERNAL,
            )

        assertEquals(setOf(authorized), repeated)
        assertEquals(once, repeated)
        assertEquals(1, repeated.size)
    }

    @Test
    fun `repeated evaluation of the same candidate is stable`() {
        val authorized = candidate("openai", "gpt-4o", euDeployment)

        repeat(5) {
            assertEquals(CandidateAuthorizationDecision.Authorized, decisionOf(authorized))
        }
    }

    @Test
    fun `an empty candidate list yields an empty set rather than a default`() {
        assertTrue(
            authorization
                .authorizedSet(emptyList(), ProviderTrustZone.LOCAL, DataClassification.INTERNAL)
                .isEmpty(),
        )
    }

    @Test
    fun `a denied candidate never appears in the authorized set`() {
        val authorized = candidate("openai", "gpt-4o", euDeployment)
        val deniedCandidates =
            listOf(
                candidate("openai", "gpt-4o", globalDeployment),
                candidate("anthropic", "claude", euDeployment),
                candidate("vllm", "llama3", localDeployment),
            )

        val result =
            authorization.authorizedSet(
                deniedCandidates + authorized,
                ProviderTrustZone.LOCAL,
                DataClassification.INTERNAL,
            )

        assertEquals(setOf(authorized), result)
    }

    @Test
    fun `absent policy and rules refuse every candidate`() {
        val closed = CandidateAuthorization()
        val candidates =
            listOf(
                candidate("openai", "gpt-4o", euDeployment),
                candidate("vllm", "llama3", localDeployment),
            )

        candidates.forEach { candidate ->
            assertEquals(
                CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED),
                closed.decisionFor(candidate, ProviderTrustZone.LOCAL, DataClassification.INTERNAL),
            )
        }
        assertTrue(
            closed.authorizedSet(candidates, ProviderTrustZone.LOCAL, DataClassification.INTERNAL).isEmpty(),
        )
    }

    @Test
    fun `an authorized candidate cannot authorize a denied one`() {
        val authorized = candidate("openai", "gpt-4o", euDeployment)
        val denied = candidate("openai", "gpt-4o", globalDeployment)
        val both = listOf(authorized, denied)

        assertEquals(
            setOf(authorized),
            authorization.authorizedSet(both, ProviderTrustZone.LOCAL, DataClassification.INTERNAL),
        )
        assertTrue(
            authorization
                .authorizedSet(listOf(denied), ProviderTrustZone.LOCAL, DataClassification.INTERNAL)
                .isEmpty(),
        )
    }

    @Test
    fun `two deployments of one brand are not interchangeable`() {
        val euCandidate = candidate("openai", "gpt-4o", euDeployment)
        val globalCandidate = candidate("openai", "gpt-4o", globalDeployment)

        assertEquals(
            setOf(euCandidate),
            authorization.authorizedSet(
                listOf(euCandidate, globalCandidate),
                ProviderTrustZone.LOCAL,
                DataClassification.INTERNAL,
            ),
        )
    }

    @Test
    fun `a blank or untrimmed identity is rejected at construction, not refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderCandidate(" ", "gpt-4o", euDeployment)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderCandidate("openai", "", euDeployment)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderCandidate("openai ", "gpt-4o", euDeployment)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderCandidate("openai", " gpt-4o", euDeployment)
        }
    }
}
