package dev.tramai.security.governance

import dev.tramai.core.policy.DataClassification
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.security.ProviderTrustZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 0.7.3g cross-stage adversarial proof: provider registration and capability facts through
 * authorization, viability and selection.
 *
 * One property is audited throughout: **no path after authorization may widen authority.** Every
 * case attacks one edge of `authorized = policy ∩ classification ∩ trust ∩ registration ∩ capability`
 * and then asserts the candidate is absent from every downstream authority-bearing value — not
 * merely that a refusal reason was produced.
 */
class GovernanceAdversarialCompositionTest {
    private val world = GovernanceWorld()
    private val selection = CandidateSelection()

    /** The fixture's capability requirement, which alpha/beta/eta satisfy and gamma does not. */
    private val requiresToolCalling = world.requiredCapability

    private fun strategyFor(candidate: ProviderCandidate?) = CandidateSelectionStrategy { candidate }

    /** The refusal that fences every out-of-set answer, named once so assertions stay readable. */
    private val outsideViableSet =
        CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET)

    /** The authorization boundary whose verdicts every assertion here reports. */
    private val auth = world.authorization

    /** The authorization verdict for [candidate] under this fixture's fixed inputs. */
    private fun decision(candidate: ProviderCandidate): CandidateAuthorizationDecision =
        auth.decisionFor(candidate, WORKLOAD_ZONE, DataClassification.INTERNAL, requiresToolCalling)

    // ---- 6. provider proof ----------------------------------------------------------------

    @Test
    fun `A a fully governed and available provider is authorized viable and selectable`() {
        val pipeline = world.pipeline(listOf(world.candidateA))

        assertEquals(CandidateAuthorizationDecision.Authorized, pipeline.decision(world.candidateA))
        assertEquals(setOf(world.candidateA), pipeline.authorizedSet)
        assertEquals(CandidateViabilityDecision.Viable, pipeline.viabilityDecisions[world.candidateA])
        assertEquals(setOf(world.candidateA), pipeline.viableSet)
        assertEquals(
            CandidateSelectionDecision.Selected(world.candidateA),
            selection.select(pipeline.viable, strategyFor(world.candidateA)),
        )
    }

    @Test
    fun `B a governed but unavailable provider is authorized then not viable then not selectable`() {
        val pipeline = world.pipeline(listOf(world.candidateA, world.candidateB), unavailable = setOf(world.candidateB))

        assertEquals(CandidateAuthorizationDecision.Authorized, pipeline.decision(world.candidateB))
        assertTrue(world.candidateB in pipeline.authorizedSet, "B must reach the viability stage")
        assertEquals(
            CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY),
            pipeline.viabilityDecisions[world.candidateB],
        )
        assertFalse(world.candidateB in pipeline.viableSet, "a non-viable candidate is not selectable")
        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
            selection.select(pipeline.viable, strategyFor(world.candidateB)),
        )
    }

    @Test
    fun `C through F are refused authorization and viability never evaluates them`() {
        val pipeline =
            world.pipeline(
                listOf(world.candidateA, world.candidateC, world.candidateD, world.candidateE, world.candidateF),
                requiredCapabilities = requiresToolCalling,
            )

        val refused =
            mapOf(
                world.candidateC to AuthorizationRefusal.REQUIRED_CAPABILITY_NOT_SUPPORTED,
                world.candidateD to AuthorizationRefusal.PROVIDER_NOT_REGISTERED,
                world.candidateE to AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED,
                world.candidateF to AuthorizationRefusal.IDENTITY_DEPLOYMENT_MISMATCH,
            )

        refused.forEach { (candidate, reason) ->
            assertEquals(
                CandidateAuthorizationDecision.NotAuthorized(reason),
                pipeline.decision(candidate),
                "wrong refusal for $candidate",
            )
            assertFalse(candidate in pipeline.authorizedSet, "$candidate must not be authorized")
            assertFalse(candidate in pipeline.viableSet, "$candidate must not be viable")
            assertEquals(null, pipeline.viabilityDecisions[candidate], "$candidate must carry no viability decision")
            assertFalse(pipeline.viabilityEvaluated(candidate), "viability must never be asked about $candidate")
            assertEquals(
                CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
                selection.select(pipeline.viable, strategyFor(candidate)),
                "$candidate must not be selectable",
            )
        }

        assertEquals(setOf(world.candidateA), pipeline.viableSet, "only A survives the whole chain")
        assertTrue(pipeline.viabilityEvaluated(world.candidateA), "A is evaluated: the instrument works")
    }

    // ---- 7. full conjunction proof ---------------------------------------------------------

    /** Every refusal means the same four things downstream, not just a reason. */
    private fun assertDisappearsFromAuthority(
        pipeline: Pipeline,
        candidate: ProviderCandidate,
        reason: AuthorizationRefusal,
        surviving: ProviderCandidate?,
    ) {
        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(reason),
            pipeline.decision(candidate),
        )
        assertFalse(candidate in pipeline.authorizedSet, "refused candidate must not be authorized")
        assertFalse(pipeline.viabilityEvaluated(candidate), "viability must never evaluate a refused candidate")
        assertFalse(candidate in pipeline.viableSet, "refused candidate must not be viable")

        val decision = selection.select(pipeline.viable, strategyFor(candidate))
        assertFalse(decision is CandidateSelectionDecision.Selected, "refused candidate must not be selectable")
        if (pipeline.viable.isEmpty()) {
            // When the tampered authority removes every candidate, the empty viable set is refused
            // before the strategy is consulted — the strategy cannot compensate for a missing
            // authority even when it asks for the refused candidate.
            assertEquals(
                CandidateSelectionDecision.NoSelection(SelectionRefusal.NO_VIABLE_CANDIDATES),
                decision,
            )
        } else {
            assertEquals(
                CandidateSelectionDecision.NoSelection(SelectionRefusal.STRATEGY_OUTSIDE_VIABLE_SET),
                decision,
            )
        }
        surviving?.let { assertTrue(it in pipeline.viableSet, "the untampered candidate must stay viable") }
    }

    @Test
    fun `removing the workload to deployment zone pair refuses for the zone pair`() {
        val tampered = GovernanceWorld(listZonePair = false)
        val pipeline = tampered.pipeline(listOf(tampered.candidateA, tampered.candidateB))

        assertDisappearsFromAuthority(
            pipeline,
            tampered.candidateA,
            AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED,
            null,
        )
        assertTrue(
            pipeline.viable.isEmpty(),
            "the only permitted workload-to-deployment pair was removed, so no candidate survives it",
        )
    }

    @Test
    fun `removing the deployment zone from the classification rule refuses for the rule`() {
        val tampered = GovernanceWorld(classificationsPermitEu = false)
        val pipeline = tampered.pipeline(listOf(tampered.candidateA, tampered.candidateB))

        assertDisappearsFromAuthority(
            pipeline,
            tampered.candidateA,
            AuthorizationRefusal.CLASSIFICATION_ZONE_NOT_PERMITTED,
            null,
        )
        assertTrue(
            pipeline.viable.isEmpty(),
            "the classification rule no longer permits the governed zone, so no candidate survives it",
        )
    }

    @Test
    fun `removing the provider from the registration plan refuses for registration`() {
        val tampered = GovernanceWorld(registered = GovernanceWorld.DEFAULT_REGISTERED - GovernanceWorld.ALPHA_PROVIDER)
        val pipeline = tampered.pipeline(listOf(tampered.candidateA, tampered.candidateB))

        assertDisappearsFromAuthority(
            pipeline,
            tampered.candidateA,
            AuthorizationRefusal.PROVIDER_NOT_REGISTERED,
            tampered.candidateB,
        )
    }

    @Test
    fun `removing a required capability refuses for capability`() {
        val tampered =
            GovernanceWorld(
                registered =
                    GovernanceWorld.DEFAULT_REGISTERED +
                        (GovernanceWorld.ALPHA_PROVIDER to setOf(ProviderCapability.VISION)),
            )
        val pipeline =
            tampered.pipeline(
                listOf(tampered.candidateA, tampered.candidateB),
                requiredCapabilities = tampered.requiredCapability,
            )

        assertDisappearsFromAuthority(
            pipeline,
            tampered.candidateA,
            AuthorizationRefusal.REQUIRED_CAPABILITY_NOT_SUPPORTED,
            tampered.candidateB,
        )
    }

    @Test
    fun `mismatching candidate and deployment provider identity refuses for consistency`() {
        val pipeline = world.pipeline(listOf(world.candidateF, world.candidateA))

        assertDisappearsFromAuthority(
            pipeline,
            world.candidateF,
            AuthorizationRefusal.IDENTITY_DEPLOYMENT_MISMATCH,
            world.candidateA,
        )
    }

    // ---- 8. refusal precedence ------------------------------------------------------------

    @Test
    fun `refusal precedence is deterministic and ordered`() {
        val untrustedZoneName = TrustZoneName("dep-inconsistent-untrusted-zone")
        val inconsistentAndUntrusted =
            ProviderCandidate(
                providerId = GovernanceWorld.ALPHA_PROVIDER,
                modelId = GovernanceWorld.ALPHA_MODEL,
                deployment =
                    ProviderDeployment(
                        deploymentId = "dep-inconsistent-untrusted",
                        providerId = GovernanceWorld.ZETA_PROVIDER,
                        trustZone = NamedTrustZone(untrustedZoneName, ProviderTrustZone.GLOBAL_CLOUD),
                    ),
            )
        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.IDENTITY_DEPLOYMENT_MISMATCH),
            world.authorization.decisionFor(
                inconsistentAndUntrusted,
                WORKLOAD_ZONE,
                DataClassification.INTERNAL,
            ),
        )

        // E: zone pair denied and the classification rule denied, in the same candidate.
        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED),
            world.authorization.decisionFor(world.candidateE, WORKLOAD_ZONE, DataClassification.RESTRICTED),
        )

        // Classification denied while the provider is also unregistered.
        val noEuRules = GovernanceWorld(classificationsPermitEu = false)
        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.CLASSIFICATION_ZONE_NOT_PERMITTED),
            noEuRules.authorization.decisionFor(
                noEuRules.candidateD,
                WORKLOAD_ZONE,
                DataClassification.INTERNAL,
                requiresToolCalling,
            ),
        )

        // Unregistered while a required capability is also unsupported.
        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.PROVIDER_NOT_REGISTERED),
            decision(world.candidateD),
        )
    }

    @Test
    fun `candidate input ordering never changes a refusal or the authorized set`() {
        val candidates = listOf(world.candidateA, world.candidateD, world.candidateC, world.candidateE)

        val base = world.pipeline(candidates, requiredCapabilities = requiresToolCalling)
        val reversed = world.pipeline(candidates.reversed(), requiredCapabilities = requiresToolCalling)

        assertEquals(base.authorizedSet, reversed.authorizedSet)
        candidates.forEach { candidate ->
            val forward = decision(candidate)
            val backward = decision(candidate)
            assertEquals(forward, backward, "the refusal for $candidate must not depend on evaluation order")
        }
    }

    // ---- 9. authorization to viability separation -----------------------------------------

    @Test
    fun `an unavailable candidate is not viable and never refused as unauthorized`() {
        val pipeline = world.pipeline(listOf(world.candidateB), unavailable = setOf(world.candidateB))

        assertEquals(CandidateAuthorizationDecision.Authorized, pipeline.decision(world.candidateB))
        assertEquals(
            CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY),
            pipeline.viabilityDecisions[world.candidateB],
        )
        assertTrue(
            pipeline.viabilityDecisions[world.candidateB] !is CandidateViabilityDecision.Viable,
            "an unavailable candidate must never be reported viable",
        )
    }

    @Test
    fun `a capability incompatible candidate is refused authorization and never reported non viable`() {
        val pipeline = world.pipeline(listOf(world.candidateC), requiredCapabilities = requiresToolCalling)

        assertEquals(
            CandidateAuthorizationDecision.NotAuthorized(AuthorizationRefusal.REQUIRED_CAPABILITY_NOT_SUPPORTED),
            pipeline.decision(world.candidateC),
        )
        assertEquals(null, pipeline.viabilityDecisions[world.candidateC])
        assertFalse(pipeline.viabilityEvaluated(world.candidateC))
        assertTrue(pipeline.viable.isEmpty(), "a capability refusal cannot leave a selectable candidate behind")
    }

    @Test
    fun `an empty selection from a stage swap would still misreport the reason`() {
        // The reason is what distinguishes an honest empty outcome from a lying one: this refusal is
        // an authorization fact and must never be reportable through the viability family.
        val pipeline = world.pipeline(listOf(world.candidateD), requiredCapabilities = requiresToolCalling)

        val refusal = pipeline.decision(world.candidateD)
        assertTrue(refusal is CandidateAuthorizationDecision.NotAuthorized)
        val notAuthorized = refusal as CandidateAuthorizationDecision.NotAuthorized
        assertEquals(AuthorizationRefusal.PROVIDER_NOT_REGISTERED, notAuthorized.reason)
        assertTrue(pipeline.viable.isEmpty())
        assertEquals(
            CandidateSelectionDecision.NoSelection(SelectionRefusal.NO_VIABLE_CANDIDATES),
            selection.select(pipeline.viable, strategyFor(world.candidateA)),
        )
    }

    // ---- 18. compact end-to-end governance matrix -----------------------------------------

    @Test
    fun `the compact governance and runtime matrix holds for every provider shape`() {
        val pipeline =
            world.pipeline(
                world.allShapes,
                requiredCapabilities = requiresToolCalling,
                unavailable = setOf(world.candidateB),
            )

        expectedMatrix().forEach { (candidate, outcome) ->
            val (governance, runtime, selectable) = outcome
            assertEquals(governance, pipeline.decision(candidate), "governance verdict for $candidate")
            assertEquals(runtime, pipeline.viabilityDecisions[candidate], "runtime verdict for $candidate")
            if (selectable) {
                assertEquals(
                    CandidateSelectionDecision.Selected(candidate),
                    selection.select(pipeline.viable, strategyFor(candidate)),
                    "expected $candidate to be selectable",
                )
            } else {
                assertEquals(
                    outsideViableSet,
                    selection.select(pipeline.viable, strategyFor(candidate)),
                    "expected $candidate to be unselectable",
                )
            }
        }

        // B is governed like A, so only the runtime constraint removes it — that is the row's point.
        assertEquals(setOf(world.candidateA, world.candidateB), pipeline.authorizedSet)
        assertEquals(setOf(world.candidateA), pipeline.viableSet)
    }

    /** One matrix row: the governance verdict, the runtime verdict, and whether it is selectable. */
    private data class MatrixRow(
        val governance: CandidateAuthorizationDecision,
        val runtime: CandidateViabilityDecision?,
        val selectable: Boolean,
    )

    private fun notAuthorized(reason: AuthorizationRefusal): CandidateAuthorizationDecision =
        CandidateAuthorizationDecision.NotAuthorized(reason)

    /** The expected row for each named provider shape. */
    private fun expectedMatrix(): Map<ProviderCandidate, MatrixRow> {
        val authorized = CandidateAuthorizationDecision.Authorized
        val viable = CandidateViabilityDecision.Viable
        val unavailable = CandidateViabilityDecision.NotViable(ViabilityRefusal.AVAILABILITY)
        val refusedCapability = notAuthorized(AuthorizationRefusal.REQUIRED_CAPABILITY_NOT_SUPPORTED)
        val refusedRegistration = notAuthorized(AuthorizationRefusal.PROVIDER_NOT_REGISTERED)
        val refusedZonePair = notAuthorized(AuthorizationRefusal.ZONE_PAIR_NOT_ALLOWED)
        val refusedIdentity = notAuthorized(AuthorizationRefusal.IDENTITY_DEPLOYMENT_MISMATCH)

        return mapOf(
            world.candidateA to MatrixRow(authorized, viable, true),
            world.candidateB to MatrixRow(authorized, unavailable, false),
            world.candidateC to MatrixRow(refusedCapability, null, false),
            world.candidateD to MatrixRow(refusedRegistration, null, false),
            world.candidateE to MatrixRow(refusedZonePair, null, false),
            world.candidateF to MatrixRow(refusedIdentity, null, false),
        )
    }

    @Test
    fun `a preference listing forbidden candidates first cannot resurrect them`() {
        val pipeline =
            world.pipeline(
                listOf(world.candidateA, world.candidateA2, world.candidateD, world.candidateB),
                unavailable = setOf(world.candidateB),
            )

        // D is unauthorized, B is unavailable: both are named ahead of the viable candidates.
        val preference = listOf(world.candidateD, world.candidateB, world.candidateA2, world.candidateA)

        assertEquals(listOf(world.candidateA2, world.candidateA), pipeline.viable.orderedBy(preference))

        val ranked = CandidateSelectionStrategy { viable -> pipeline.viable.orderedBy(preference).firstOrNull() }
        assertEquals(CandidateSelectionDecision.Selected(world.candidateA2), selection.select(pipeline.viable, ranked))

        assertEquals(setOf(world.candidateA, world.candidateA2, world.candidateB), pipeline.authorizedSet)
        assertEquals(setOf(world.candidateA, world.candidateA2), pipeline.viableSet)
    }

    // ---- 19. no provider invocation -------------------------------------------------------

    @Test
    fun `the whole decision path invokes no provider`() {
        val worlds =
            listOf(
                world,
                GovernanceWorld(listZonePair = false),
                GovernanceWorld(classificationsPermitEu = false),
            )

        worlds.forEach { each ->
            val pipeline =
                each.pipeline(
                    each.allShapes,
                    requiredCapabilities = each.requiredCapability,
                    unavailable = setOf(each.candidateB),
                )
            selection.select(pipeline.viable, strategyFor(each.candidateA))
            assertEquals(
                emptyMap<String, Int>(),
                each.invocationCounts().filterValues { it != 0 },
                "no provider may be invoked by authorization, viability, selection or their assembly",
            )
        }
    }
}
