package dev.tramai.security.governance

import dev.tramai.core.policy.DataClassification
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
        assertEquals(setOf(authorizedCandidate), viability.viableCandidates(authorized(authorizedCandidate)))
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
                .none { it == refusedCandidate },
        )
    }

    @Test
    fun `a capability constraint makes an authorized candidate non-viable for that reason`() {
        val viability = CandidateViability { ViabilityRefusal.CAPABILITY }

        assertEquals(
            CandidateViabilityDecision.NotViable(ViabilityRefusal.CAPABILITY),
            viability.decisions(authorized(authorizedCandidate))[authorizedCandidate],
        )
        assertTrue(viability.viableCandidates(authorized(authorizedCandidate)).isEmpty())
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

        assertTrue(authorizedValue.candidates.containsAll(viability.viableCandidates(authorizedValue)))
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
                if (candidate.modelId == "gpt-4o-mini") ViabilityRefusal.CAPABILITY else null
            }

        val forward = viability.decisions(authorized(first, second))
        val reversed = viability.decisions(authorized(second, first))

        assertEquals(forward, reversed)
        assertEquals(setOf(first), viability.viableCandidates(authorized(second, first)))
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
        // HEALTH was declared in 0.7.3b and removed here, when the first producer
        // landed: nothing in the repository reports provider health.
        assertEquals(
            listOf(ViabilityRefusal.AVAILABILITY, ViabilityRefusal.CAPABILITY),
            ViabilityRefusal.entries.sortedBy { it.name },
        )
    }
}
