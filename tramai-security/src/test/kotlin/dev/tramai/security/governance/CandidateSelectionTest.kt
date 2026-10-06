package dev.tramai.security.governance

import dev.tramai.core.policy.DataClassification
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract tests for the 0.7.3e selection fence.
 *
 * One invariant: selection, fallback, retry, preference and ranking may choose among viable
 * candidates, but may never introduce one. The tests below attack that from the outside — a
 * strategy is caller-supplied, so its answer is untrusted input.
 *
 * The viable sets here are minted by the real chain (authorization then viability) rather than
 * constructed directly, so the fixtures prove what the production path produces.
 */
class CandidateSelectionTest {
    /** The only permitted zone pair throughout: LOCAL -> EU_CLOUD. */
    private val zones = TrustZonePolicy(setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.EU_CLOUD))

    private val rules =
        mapOf(
            DataClassification.INTERNAL to
                ClassificationRoutingRule(
                    allowedZones = setOf(ProviderTrustZone.EU_CLOUD),
                    allowedFallbackZones = emptySet(),
                ),
        )

    private val registration =
        registrationPlan(
            "openai" to ProviderCapability.entries.toSet(),
            "vllm" to ProviderCapability.entries.toSet(),
        )

    private val authorization = CandidateAuthorization(ProviderInputRelease(zones, rules), registration)

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

    private val euZone = ProviderTrustZone.EU_CLOUD

    /** Authorized, and available. */
    private val a = ProviderCandidate("openai", "gpt-4o", deployment("dep-a", "openai", euZone))

    /** Authorized, but unavailable: the configured fallback this section fences. */
    private val b = ProviderCandidate("vllm", "llama3", deployment("dep-b", "vllm", euZone))

    /** Never authorized: an unregistered provider, which is how a routing configuration can name a
     * provider that governance does not permit. */
    private val unregistered =
        ProviderCandidate("anthropic", "claude", deployment("dep-c", "anthropic", euZone))

    /** Only [b] is refused by the runtime-constraint evaluator. */
    private fun viabilityRefusing(vararg refused: ProviderCandidate) =
        CandidateViability { candidate -> if (candidate in refused) ViabilityRefusal.AVAILABILITY else null }

    /** Every supplied candidate is viable: the envelope for cases varying selection, not viability. */
    private fun viableSet(vararg candidates: ProviderCandidate): ViableCandidates =
        CandidateViability { null }.viableCandidates(
            authorization.authorizedCandidates(
                candidates.toList(),
                ProviderTrustZone.LOCAL,
                DataClassification.INTERNAL,
            ),
        )

    private val selection = CandidateSelection()

    // --- 1-3. the basic shape --------------------------------------------------

    @Test
    fun `a selected candidate belongs to the viable set`() {
        val viable = viableSet(a, b)
        val decision = selection.select(viable, CandidateSelectionStrategy { it.first() })

        assertTrue(decision is CandidateSelectionDecision.Selected)
        assertTrue(viable.contains((decision as CandidateSelectionDecision.Selected).candidate))
    }

    @Test
    fun `an empty viable set selects nothing and never consults a strategy`() {
        // The fallback used here is the routing configuration's *default provider*: a configured
        // route is not authority, so an empty envelope must refuse before a strategy can offer it.
        val viable = viableSet(unregistered)
        var consulted = false
        val defaultProvider =
            CandidateSelectionStrategy {
                consulted = true
                unregistered
            }

        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.NO_VIABLE_CANDIDATES),
            selection.select(viable, defaultProvider),
        )
        assertFalse(consulted, "an empty viable set must not reach the strategy")
    }

    @Test
    fun `a single viable candidate is the only one selectable`() {
        val viable = viableSet(a, unregistered)

        assertEquals(
            CandidateSelectionDecision.Selected(a),
            selection.select(viable, CandidateSelectionStrategy { viable -> viable.single() }),
        )
        assertEquals(1, viable.candidates.size)
    }

    // --- 4-8. adversarial: the strategy and the routing configuration ----------

    @Test
    fun `a strategy cannot inject a candidate from outside the viable set`() {
        val viable = viableSet(a)

        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
            selection.select(viable, CandidateSelectionStrategy { b }),
        )
    }

    @Test
    fun `a configured fallback that is not authorized cannot be selected`() {
        // `unregistered` is exactly what a ProviderRoutingPlan.fallbackProvider(...) entry may name;
        // authorization refuses it, so it never reaches the viable envelope.
        val viable = viableSet(a, unregistered)

        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
            selection.select(viable, CandidateSelectionStrategy { unregistered }),
        )
    }

    @Test
    fun `a configured fallback that is authorized but not viable cannot be selected`() {
        val viable =
            viabilityRefusing(b).viableCandidates(
                authorization.authorizedCandidates(
                    listOf(a, b),
                    ProviderTrustZone.LOCAL,
                    DataClassification.INTERNAL,
                ),
            )

        assertFalse(viable.contains(b), "a non-viable candidate must not be in the envelope")
        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
            selection.select(viable, CandidateSelectionStrategy { b }),
        )
    }

    @Test
    fun `a configured fallback that is viable may be selected`() {
        val viable = viableSet(a, b)

        assertEquals(
            CandidateSelectionDecision.Selected(b),
            selection.select(viable, CandidateSelectionStrategy { b }),
        )
    }

    @Test
    fun `retry after an attempted candidate stays inside the original viable set`() {
        val viable = viableSet(a, b)
        val first = selection.select(viable, CandidateSelectionStrategy { a })
        assertEquals(CandidateSelectionDecision.Selected(a), first)

        // Retry derives the remaining candidates from the original envelope, never from routing.
        val remaining = viable.without(a)
        val second = selection.select(remaining, CandidateSelectionStrategy { b })

        assertEquals(CandidateSelectionDecision.Selected(b), second)
        assertTrue(viable.contains(b))
        assertFalse(remaining.contains(a), "an attempted candidate must not be reconsidered")
        assertEquals(setOf(b), remaining.candidates)
    }

    @Test
    fun `a preference may reorder viable candidates but cannot add one`() {
        val viable = viableSet(a, b)

        // Reordering: the preference lists a non-viable candidate first and viable ones after it.
        assertEquals(listOf(b, a), viable.orderedBy(listOf(unregistered, b, a)))
        assertTrue(viable.orderedBy(listOf(unregistered)).isEmpty())

        // Injecting: a preference naming a candidate from outside the envelope cannot widen it.
        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
            selection.select(viable, CandidateSelectionStrategy { unregistered }),
        )

        // Declining: a preference that finds nothing to prefer selects nothing at all.
        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_DECLINED),
            selection.select(viable, CandidateSelectionStrategy { it.firstOrNull { c -> c == unregistered } }),
        )
    }

    @Test
    fun `a strategy that declines is a non-selection, not a failure`() {
        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_DECLINED),
            selection.select(viableSet(a, b), CandidateSelectionStrategy { null }),
        )
    }

    // --- 9-10. ordering, duplicates --------------------------------------------

    @Test
    fun `candidate ordering does not change membership`() {
        val forward = viableSet(a, b)
        val reversed = viableSet(b, a)

        assertEquals(forward.candidates, reversed.candidates)
        assertEquals(setOf(a, b), forward.candidates)
    }

    @Test
    fun `duplicate candidates do not widen the universe`() {
        val once = viableSet(a)
        val repeated = viableSet(a, a, a)

        assertEquals(once.candidates, repeated.candidates)
        assertEquals(1, repeated.candidates.size)
    }

    // --- 11-13. stage vocabulary and separation --------------------------------

    @Test
    fun `selection never reclassifies a non-viable candidate as governance-denied`() {
        // The three vocabularies stay separate: viability refused b, so b is absent from the viable
        // set. Selection cannot report that as an authorization refusal or as a viability refusal,
        // because neither type is expressible in its decision.
        val viable =
            viabilityRefusing(b).viableCandidates(
                authorization.authorizedCandidates(
                    listOf(a, b),
                    ProviderTrustZone.LOCAL,
                    DataClassification.INTERNAL,
                ),
            )

        assertEquals(setOf(a), viable.candidates)
        val decision = selection.select(viable, CandidateSelectionStrategy { b })
        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
            decision,
        )
        assertTrue(decision is CandidateSelectionDecision.NoSelection)
        assertTrue((decision as CandidateSelectionDecision.NoSelection).reason in SelectionRefusal.entries)
    }

    @Test
    fun `the selection vocabulary is exactly the three facts this boundary produces`() {
        assertEquals(
            listOf(
                SelectionRefusal.NO_VIABLE_CANDIDATES,
                SelectionRefusal.STRATEGY_DECLINED,
                SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET,
            ),
            SelectionRefusal.entries.toList(),
        )
    }

    @Test
    fun `selection does not recompute authorization or viability`() {
        var evaluations = 0
        val counting =
            CandidateViability { _ ->
                evaluations++
                null
            }
        val viable =
            counting.viableCandidates(
                authorization.authorizedCandidates(
                    listOf(a, b),
                    ProviderTrustZone.LOCAL,
                    DataClassification.INTERNAL,
                ),
            )
        val before = evaluations

        selection.select(viable, CandidateSelectionStrategy { it.first() })

        assertEquals(before, evaluations, "selection must consume the viable set, not recompute it")
    }

    @Test
    fun `selection performs no provider invocation`() {
        // Structural: CandidateSelection holds no ModelProvider and its strategy returns a candidate
        // rather than a response. The registered provider stub raises if anything invokes it, so a
        // successful selection is evidence that nothing did.
        assertEquals(
            CandidateSelectionDecision.Selected(a),
            selection.select(viableSet(a), CandidateSelectionStrategy { a }),
        )
    }
}
