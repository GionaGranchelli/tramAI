package dev.tramai.security.governance

import dev.tramai.core.policy.DataClassification
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the 0.7.3d viability stage.
 *
 * One question: can an *already authorized* candidate be used right now? The tests
 * audit the ordering invariant directly — viability is never evaluated for a
 * candidate authorization refused — and the structural properties that make it
 * hold: viability accepts only [AuthorizedCandidates], and its results are a
 * subset of the authorized set.
 *
 * The fixtures are the ones the authorization boundary is audited against, so a
 * refusal here cannot be confused with a refusal there: a candidate that fails
 * authorization must be absent from every viability result, not reported as
 * non-viable.
 */
class CandidateViabilityTest {
    /** The only permitted zone pair throughout: LOCAL -> EU_CLOUD. */
    private val zonesAllowLocalToEu =
        TrustZonePolicy(
            setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.EU_CLOUD),
        )

    private val rules =
        mapOf(
            DataClassification.INTERNAL to
                ClassificationRoutingRule(
                    allowedZones = setOf(ProviderTrustZone.EU_CLOUD, ProviderTrustZone.LOCAL),
                    allowedFallbackZones = emptySet(),
                ),
        )

    /**
     * The viability fixtures are authorized candidates, so their providers must be registered:
     * authorization refuses an unregistered provider before viability is ever asked (0.7.3d1).
     */
    private val registration = registrationPlan("openai" to ProviderCapability.entries.toSet())

    private val authorization =
        CandidateAuthorization(ProviderInputRelease(zonesAllowLocalToEu, rules), registration)

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
    private val globalDeployment = deployment("dep-global", "openai", ProviderTrustZone.GLOBAL_CLOUD)

    private fun candidate(
        providerId: String,
        modelId: String,
        deployment: ProviderDeployment,
    ) = ProviderCandidate(providerId, modelId, deployment)

    private val authorizedCandidate = candidate("openai", "gpt-4o", euDeployment)

    /** Denied authorization: LOCAL -> GLOBAL_CLOUD is not a listed pair. */
    private val refusedCandidate = candidate("openai", "gpt-4o", globalDeployment)

    private fun authorized(vararg candidates: ProviderCandidate) =
        authorization.authorizedCandidates(
            candidates.toList(),
            ProviderTrustZone.LOCAL,
            DataClassification.INTERNAL,
        )

    // --- the ordering invariant ------------------------------------------------

    @Test
    fun `viability is never evaluated for a candidate authorization refused`() {
        val evaluated = ArrayList<ProviderCandidate>()
        val viability =
            CandidateViability { candidate ->
                evaluated += candidate
                null
            }

        val result = viability.decisions(authorized(authorizedCandidate, refusedCandidate))

        assertEquals(setOf(authorizedCandidate), result.keys)
        assertEquals(listOf(authorizedCandidate), evaluated)
        assertTrue(refusedCandidate !in result.keys)
    }

    @Test
    fun `the authorized value carries exactly the authorized set`() {
        val candidates = listOf(authorizedCandidate, refusedCandidate)
        val zone = ProviderTrustZone.LOCAL

        assertEquals(
            authorization.authorizedSet(candidates, zone, DataClassification.INTERNAL),
            authorized(authorizedCandidate, refusedCandidate).candidates,
        )
        assertEquals(setOf(authorizedCandidate), authorized(authorizedCandidate, refusedCandidate).candidates)
    }

    // --- VIABLE | NOT_VIABLE ---------------------------------------------------

    @Test
    fun `an authorized candidate satisfying every constraint is viable`() {
        val viability = CandidateViability { null }

        assertEquals(
            mapOf(authorizedCandidate to CandidateViabilityDecision.Viable),
            viability.decisions(authorized(authorizedCandidate)),
        )
        assertEquals(
            setOf(authorizedCandidate),
            viability.viableCandidates(authorized(authorizedCandidate)).candidates,
        )
    }

    @Test
    fun `an unauthorized candidate is not viable, it is absent`() {
        val viability = CandidateViability { null }

        // The refused candidate is never a key: "not viable" would be the wrong answer,
        // because the question is only asked about candidates that are authorized.
        val result = viability.decisions(authorized(authorizedCandidate, refusedCandidate))

        assertEquals(1, result.size)
        assertTrue(
            viability
                .viableCandidates(authorized(authorizedCandidate, refusedCandidate))
                .candidates
                .none { it == refusedCandidate },
        )
    }

    @Test
    fun `an availability constraint makes an authorized candidate non-viable for that reason`() {
        val viability = CandidateViability { ViabilityRefusal.AVAILABILITY }

        assertEquals(
            CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY),
            viability.decisions(authorized(authorizedCandidate))[authorizedCandidate],
        )
    }

    // --- viable is a subset of authorized -------------------------------------

    @Test
    fun `viable candidates are always a subset of the authorized set`() {
        val viability =
            CandidateViability { candidate ->
                if (candidate.modelId == "gpt-4o") null else ViabilityRefusal.AVAILABILITY
            }
        val authorizedValue = authorized(authorizedCandidate)

        val viable = viability.viableCandidates(authorizedValue).candidates
        assertTrue(authorizedValue.candidates.containsAll(viable))
        assertTrue(authorizedValue.candidates.containsAll(viability.decisions(authorizedValue).keys))
    }

    // --- no default, no ordering, no drift -------------------------------------

    @Test
    fun `an empty authorized set yields no viability decisions`() {
        val viability = CandidateViability { ViabilityRefusal.AVAILABILITY }

        assertTrue(viability.decisions(authorized()).isEmpty())
        assertTrue(viability.viableCandidates(authorized()).isEmpty())
    }

    @Test
    fun `candidate ordering does not change the viability results`() {
        val first = candidate("openai", "gpt-4o", euDeployment)
        val second = candidate("openai", "gpt-4o-mini", euDeployment)
        val viability =
            CandidateViability { candidate ->
                if (candidate.modelId == "gpt-4o-mini") ViabilityRefusal.AVAILABILITY else null
            }

        val forward = viability.decisions(authorized(first, second))
        val reversed = viability.decisions(authorized(second, first))

        assertEquals(forward, reversed)
        assertEquals(
            setOf(first),
            viability.viableCandidates(authorized(second, first)).candidates,
        )
    }

    @Test
    fun `repeated evaluation is stable`() {
        val viability = CandidateViability { null }

        repeat(5) {
            assertEquals(
                CandidateViabilityDecision.Viable,
                viability.decisions(authorized(authorizedCandidate))[authorizedCandidate],
            )
        }
    }

    @Test
    fun `the refusal family has exactly the constraints this repository can report`() {
        // Shrunk twice against the same rule: the vocabulary may not keep states no
        // producer can report (HEALTH), and the epic puts capability in authorization
        // rather than viability. One member remains, and it has a real producer.
        assertEquals(
            listOf(ViabilityRefusal.AVAILABILITY),
            ViabilityRefusal.entries.sortedBy { it.name },
        )
    }
}
