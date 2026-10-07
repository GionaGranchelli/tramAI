package dev.tramai.security.governance

import dev.tramai.core.provider.ProviderCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 0.7.3g fallback, retry, preference and strategy-mutation attacks on the composed decision path.
 *
 * Each case treats configured routing, retry state, ranking signals and the strategy's own answer as
 * **adversarial input**: none of them is authority. The proof is always the same shape — the
 * candidate is absent from [ViableCandidates] and therefore cannot become
 * [CandidateSelectionDecision.Selected], however it is configured or requested.
 *
 * The viable sets here are produced by the real [CandidateAuthorization] → [CandidateViability]
 * path, never constructed directly.
 */
class GovernanceSelectionBoundaryAttackTest {
    private val world = GovernanceWorld()
    private val selection = CandidateSelection()

    /** A third fully governed provider, so retry narrowing can be repeated twice. */
    private val theta: ProviderCandidate =
        world.candidate(THETA_PROVIDER, THETA_MODEL, "dep-theta", GOVERNED_ZONE)

    private val thetaWorld: GovernanceWorld =
        GovernanceWorld(
            registered = GovernanceWorld.DEFAULT_REGISTERED + (THETA_PROVIDER to ProviderCapability.entries.toSet()),
        )

    private val threeViable: Pipeline =
        thetaWorld.pipeline(listOf(thetaWorld.candidateA, thetaWorld.candidateA2, theta))

    private fun strategyFor(candidate: ProviderCandidate?) = CandidateSelectionStrategy { candidate }

    /** The refusal that fences every out-of-set answer, named once so assertions stay readable. */
    private val outsideViableSet =
        CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET)

    // ---- 10. fallback attack matrix -------------------------------------------------------

    @Test
    fun `a configured fallback outside authorization cannot be selected`() {
        val pipeline = world.pipeline(listOf(world.candidateA, world.candidateD))

        assertEquals(setOf(world.candidateA), pipeline.viableSet, "D is unregistered and never reaches viability")
        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
            selection.select(pipeline.viable, strategyFor(world.candidateD)),
        )
        assertTrue(pipeline.viable.contains(world.candidateA), "requesting D must not disturb A")
    }

    @Test
    fun `a configured fallback that is authorized but unavailable cannot be selected`() {
        val pipeline =
            world.pipeline(listOf(world.candidateA, world.candidateB), unavailable = setOf(world.candidateB))

        assertTrue(world.candidateB in pipeline.authorizedSet, "B is governed")
        assertFalse(world.candidateB in pipeline.viableSet, "B is not usable right now")
        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
            selection.select(pipeline.viable, strategyFor(world.candidateB)),
        )
    }

    @Test
    fun `a configured fallback that is genuinely viable is selected`() {
        val pipeline = world.pipeline(listOf(world.candidateA, world.candidateA2))

        assertEquals(setOf(world.candidateA, world.candidateA2), pipeline.viableSet)
        assertEquals(
            CandidateSelectionDecision.Selected(world.candidateA2),
            selection.select(pipeline.viable, strategyFor(world.candidateA2)),
        )
    }

    @Test
    fun `an empty viable set is refused before the strategy is consulted even with a configured default`() {
        val configured = GovernanceWorld(configuredDefault = GovernanceWorld.BETA_PROVIDER)
        val pipeline =
            configured.pipeline(
                listOf(configured.candidateA, configured.candidateB),
                unavailable = setOf(configured.candidateA, configured.candidateB),
            )

        assertTrue(pipeline.viable.isEmpty(), "nothing is usable, so nothing may be selected")
        var strategyConsulted = false
        val decision =
            selection.select(
                pipeline.viable,
                CandidateSelectionStrategy {
                    strategyConsulted = true
                    configured.candidateB
                },
            )

        assertEquals(CandidateSelectionDecision.NoSelection(SelectionRefusal.NO_VIABLE_CANDIDATES), decision)
        assertFalse(strategyConsulted, "no try-the-configured-fallback-anyway path may exist")
    }

    // ---- 11. retry attack matrix ----------------------------------------------------------

    @Test
    fun `retry narrows the viable set and a removed candidate cannot reappear`() {
        val viable = threeViable.viable
        assertEquals(
            setOf(thetaWorld.candidateA, thetaWorld.candidateA2, theta),
            viable.candidates,
        )

        val afterA = viable.without(thetaWorld.candidateA)
        assertEquals(setOf(thetaWorld.candidateA2, theta), afterA.candidates)

        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
            selection.select(afterA, strategyFor(thetaWorld.candidateA)),
            "an attempted candidate must not be selectable again",
        )
        assertEquals(
            CandidateSelectionDecision.Selected(thetaWorld.candidateA2),
            selection.select(afterA, strategyFor(thetaWorld.candidateA2)),
        )
        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
            selection.select(afterA, strategyFor(world.candidateD)),
            "a candidate outside the original viable universe stays outside",
        )

        val afterB = afterA.without(thetaWorld.candidateA2)
        assertEquals(setOf(theta), afterB.candidates)
        assertFalse(afterB.contains(thetaWorld.candidateA))
        assertFalse(afterB.contains(thetaWorld.candidateA2))
        assertEquals(CandidateSelectionDecision.Selected(theta), selection.select(afterB, strategyFor(theta)))
        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
            selection.select(afterB, strategyFor(thetaWorld.candidateA2)),
            "removing down to one candidate must not re-admit an earlier one",
        )
    }

    @Test
    fun `narrowing can never add a candidate`() {
        val viable = threeViable.viable

        val narrowed = viable.without(world.candidateD).without(thetaWorld.candidateA)

        assertEquals(setOf(thetaWorld.candidateA2, theta), narrowed.candidates)
        assertFalse(narrowed.contains(world.candidateD), "a candidate that was never viable cannot be added by removal")
    }

    // ---- 12. preference and ranking attacks -----------------------------------------------

    @Test
    fun `preference orders but cannot widen membership`() {
        val pipeline =
            world.pipeline(
                listOf(world.candidateA, world.candidateA2, world.candidateD, world.candidateB),
                unavailable = setOf(world.candidateB),
            )

        // Forbidden first: unauthorized D, then authorized-but-unavailable B.
        val hostile = listOf(world.candidateD, world.candidateB, world.candidateA2, world.candidateA)
        assertEquals(listOf(world.candidateA2, world.candidateA), pipeline.viable.orderedBy(hostile))

        val reversedPreference = listOf(world.candidateA, world.candidateA2, world.candidateB, world.candidateD)
        assertEquals(listOf(world.candidateA, world.candidateA2), pipeline.viable.orderedBy(reversedPreference))

        assertEquals(setOf(world.candidateA, world.candidateA2, world.candidateB), pipeline.authorizedSet)
        assertEquals(setOf(world.candidateA, world.candidateA2), pipeline.viableSet)
        assertFalse(pipeline.viable.contains(world.candidateD))
        assertFalse(pipeline.viable.contains(world.candidateB))
    }

    @Test
    fun `a strategy that ignores the filtered ordering is fenced for both forbidden candidates`() {
        val pipeline =
            world.pipeline(
                listOf(world.candidateA, world.candidateA2, world.candidateD, world.candidateB),
                unavailable = setOf(world.candidateB),
            )

        listOf(world.candidateD, world.candidateB).forEach { forbidden ->
            assertEquals(
                CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
                selection.select(pipeline.viable, strategyFor(forbidden)),
                "$forbidden is not in the viable set and cannot be selected",
            )
        }
    }

    // ---- 13. mutating strategy attack ----------------------------------------------------

    @Test
    fun `a strategy cannot widen the envelope by mutating the set it is given`() {
        val pipeline = world.pipeline(listOf(world.candidateA, world.candidateA2))
        val viable = pipeline.viable

        val decision =
            selection.select(
                viable,
                CandidateSelectionStrategy { supplied ->
                    @Suppress("UNCHECKED_CAST")
                    val mutable = supplied as MutableSet<ProviderCandidate>
                    mutable += world.candidateD
                    world.candidateD
                },
            )

        assertEquals(CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET), decision)
        assertEquals(setOf(world.candidateA, world.candidateA2), viable.candidates)
        assertFalse(viable.contains(world.candidateD), "the authority envelope must be untouched")
        assertFalse(pipeline.viableSet.contains(world.candidateD))
    }

    @Test
    fun `a mutating strategy against a single candidate envelope cannot widen authority either`() {
        val pipeline = world.pipeline(listOf(world.candidateA))
        val viable = pipeline.viable
        assertEquals(setOf(world.candidateA), viable.candidates)

        val attempt =
            runCatching {
                selection.select(
                    viable,
                    CandidateSelectionStrategy { supplied ->
                        @Suppress("UNCHECKED_CAST")
                        val mutable = supplied as MutableSet<ProviderCandidate>
                        mutable += world.candidateD
                        world.candidateD
                    },
                )
            }

        // Either the supplied copy refuses the mutation outright (an immutable JDK set backs a
        // one-element toSet, so add() throws) or the fence refuses the answer. Both are a refusal to
        // widen; neither may ever produce a selection of the outside candidate.
        attempt.fold(
            onSuccess = { decision ->
                assertEquals(outsideViableSet, decision)
            },
            onFailure = { failure ->
                assertTrue(
                    failure is UnsupportedOperationException || failure is ClassCastException,
                    "unexpected failure mode for a mutation attempt: $failure",
                )
            },
        )

        assertEquals(setOf(world.candidateA), viable.candidates)
        assertFalse(viable.contains(world.candidateD))
    }

    @Test
    fun `a mutating strategy cannot add a completely unregistered candidate`() {
        val pipeline = world.pipeline(listOf(world.candidateA, world.candidateA2))

        val decision =
            selection.select(
                pipeline.viable,
                CandidateSelectionStrategy { supplied ->
                    @Suppress("UNCHECKED_CAST")
                    val mutable = supplied as MutableSet<ProviderCandidate>
                    val invented = world.candidate("ghost", "ghost-model", "dep-ghost", GOVERNED_ZONE)
                    mutable += invented
                    invented
                },
            )

        assertEquals(CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET), decision)
        assertEquals(setOf(world.candidateA, world.candidateA2), pipeline.viable.candidates)
        assertFalse(pipeline.viable.contains(world.candidate("ghost", "ghost-model", "dep-ghost", GOVERNED_ZONE)))
    }

    // ---- 14. duplicate and ordering attacks ----------------------------------------------

    @Test
    fun `a duplicated candidate input cannot increase authority`() {
        val once = world.pipeline(listOf(world.candidateA))
        val thrice = world.pipeline(listOf(world.candidateA, world.candidateA, world.candidateA))

        assertEquals(once.authorizedSet, thrice.authorizedSet)
        assertEquals(setOf(world.candidateA), thrice.authorizedSet)

        val onceViable = world.pipeline(listOf(world.candidateA)).viableSet
        val thriceViable = world.pipeline(listOf(world.candidateA, world.candidateA, world.candidateA)).viableSet
        assertEquals(onceViable, thriceViable)
    }

    @Test
    fun `input ordering cannot change authorized or viable membership`() {
        val forward =
            world.pipeline(
                listOf(world.candidateA, world.candidateA2, world.candidateD, world.candidateC),
                requiredCapabilities = world.requiredCapability,
            )
        val backward =
            world.pipeline(
                listOf(world.candidateC, world.candidateD, world.candidateA2, world.candidateA),
                requiredCapabilities = world.requiredCapability,
            )

        assertEquals(forward.authorizedSet, backward.authorizedSet)
        assertEquals(forward.viableSet, backward.viableSet)
        assertEquals(setOf(world.candidateA, world.candidateA2), forward.viableSet)
    }

    @Test
    fun `ordering may change which member is chosen but never membership`() {
        val pipeline = world.pipeline(listOf(world.candidateA, world.candidateA2))
        val members = pipeline.viable.candidates.toSet()

        val first = CandidateSelectionStrategy { viable -> viable.first() }
        val last = CandidateSelectionStrategy { viable -> viable.last() }

        val firstDecision = selection.select(pipeline.viable, first) as CandidateSelectionDecision.Selected
        val lastDecision = selection.select(pipeline.viable, last) as CandidateSelectionDecision.Selected

        assertTrue(firstDecision.candidate in members)
        assertTrue(lastDecision.candidate in members)
        assertEquals(members, pipeline.viable.candidates.toSet(), "membership is fixed by the stages, not the strategy")
    }

    private companion object {
        const val THETA_PROVIDER = "theta"
        const val THETA_MODEL = "theta-large"
    }
}
