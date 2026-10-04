package dev.tramai.security.governance

import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the 0.7.3c authorized-set boundary.
 *
 * One question: does a provider/model candidate belong to the authorized set?
 * The tests audit the invariant directly — a candidate is authorized only when
 * every required restriction permits it — and the ways authority could be widened
 * by accident: ordering, duplication, a malformed pair, the absence of policy, or
 * another candidate's authorization.
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

    // --- 1. explicitly authorized candidates enter the set --------------------

    @Test
    fun `an explicitly authorized candidate enters the authorized set`() {
        val authorized = candidate("openai", "gpt-4o", euDeployment)

        assertTrue(authorization.authorizes(authorized, ProviderTrustZone.LOCAL, DataClassification.INTERNAL))
        assertEquals(
            setOf(authorized),
            authorization.authorizedSet(listOf(authorized), ProviderTrustZone.LOCAL, DataClassification.INTERNAL),
        )
    }

    // --- 2. denied and incomplete candidates do not ---------------------------

    @Test
    fun `a candidate whose zone no policy pair allows is denied`() {
        val denied = candidate("openai", "gpt-4o", globalDeployment)

        assertFalse(authorization.authorizes(denied, ProviderTrustZone.LOCAL, DataClassification.INTERNAL))
        assertTrue(
            authorization
                .authorizedSet(listOf(denied), ProviderTrustZone.LOCAL, DataClassification.INTERNAL)
                .isEmpty(),
        )
    }

    @Test
    fun `a candidate the classification rule forbids is denied even on an allowed zone pair`() {
        val denied = candidate("openai", "gpt-4o", euDeployment)

        // LOCAL -> EU_CLOUD is an allowed pair; RESTRICTED permits only LOCAL.
        assertFalse(authorization.authorizes(denied, ProviderTrustZone.LOCAL, DataClassification.RESTRICTED))
    }

    @Test
    fun `a candidate for a classification with no rule is denied on an allowed zone pair`() {
        val denied = candidate("openai", "gpt-4o", euDeployment)

        assertFalse(authorization.authorizes(denied, ProviderTrustZone.LOCAL, DataClassification.PUBLIC))
    }

    @Test
    fun `a candidate whose identity disagrees with its deployment is denied`() {
        val inconsistent = candidate("anthropic", "claude", euDeployment)

        assertFalse(authorization.authorizes(inconsistent, ProviderTrustZone.LOCAL, DataClassification.INTERNAL))
    }

    @Test
    fun `same-zone compatibility is not implicitly authorized`() {
        val sameZone = candidate("vllm", "llama3", localDeployment)

        // RESTRICTED permits LOCAL, but LOCAL -> LOCAL is not a listed pair.
        assertFalse(authorization.authorizes(sameZone, ProviderTrustZone.LOCAL, DataClassification.RESTRICTED))
    }

    // --- 3. ordering does not change the authorized set ------------------------

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

    // --- 4. duplicate evaluation cannot widen authority ------------------------

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
            assertTrue(authorization.authorizes(authorized, ProviderTrustZone.LOCAL, DataClassification.INTERNAL))
        }
    }

    // --- 5. nothing is selected, and nothing defaults ---------------------------

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

    // --- the invariant: absent policy denies, and nothing widens ---------------

    @Test
    fun `absent policy and rules deny every candidate`() {
        val closed = CandidateAuthorization()
        val candidates =
            listOf(
                candidate("openai", "gpt-4o", euDeployment),
                candidate("vllm", "llama3", localDeployment),
            )

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
        // Remove the authorized candidate: the denied one must not inherit anything.
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
    fun `a blank or untrimmed identity is rejected at construction, not denied`() {
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
