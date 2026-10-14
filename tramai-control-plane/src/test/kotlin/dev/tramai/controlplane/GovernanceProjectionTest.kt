package dev.tramai.controlplane

import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadMetadata
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * 0.7.4c1 — adversarial discriminators for the authority-aware projection.
 *
 * These run against the real [WorkloadRegistrationAuthority] over the in-memory store, not a
 * stub port: the registration half of every projection below is produced by the authority that
 * owns it. Decision and telemetry records are scripted, because no engine producer exists for
 * them yet — that wiring is the next slice, and these tests must not imply otherwise.
 */
class GovernanceProjectionTest {
    private val identity = WorkloadRegistrationFixtures.identity()

    private suspend fun registeredAuthority(): WorkloadRegistrationAuthority {
        val authority = WorkloadRegistrationAuthority(InMemoryWorkloadRegistrationStore())
        authority.register(
            identity,
            WorkloadRegistrationFixtures.fingerprint(),
            WorkloadMetadata(owner = "Claims Team", purpose = "Fraud review"),
        )
        return authority
    }

    private class ScriptedFacts(
        private val decisions: List<RecordedDecision> = emptyList(),
        private val telemetry: List<TelemetryObservation> = emptyList(),
    ) : GovernanceDecisionFactSource {
        var decisionReads = 0
            private set
        var telemetryReads = 0
            private set

        override suspend fun decisionsFor(workflowRunId: String): List<RecordedDecision> {
            decisionReads++
            return decisions
        }

        override suspend fun telemetryFor(workflowRunId: String): List<TelemetryObservation> {
            telemetryReads++
            return telemetry
        }
    }

    private fun reader(
        authority: WorkloadControlPlaneQueries,
        facts: GovernanceDecisionFactSource = ScriptedFacts(),
    ) = GovernanceProjectionReader(authority, facts)

    private fun decision(
        kind: DecisionKind,
        value: String = "ALLOW",
        sequence: Long? = 1,
        boundary: DeclaredBoundary? = null,
    ) = RecordedDecision(
        workflowRunId = "run-1",
        kind = kind,
        decision = value,
        boundary = boundary,
        sourceSequence = sequence,
    )

    private fun declared() =
        DeclaredRunIdentity(
            workloadId = "claims",
            configurationId = "claims-prod",
            configurationVersion = "17",
            environmentId = "production",
            deploymentId = "eu-west-amsterdam-01",
            workflowRunId = "run-1",
        )

    // ---------------------------------------------------------------- declared identity

    @Test
    fun `a complete declared identity stays declared, never authoritative`() =
        runBlocking<Unit> {
            val facts = ScriptedFacts()
            val projection = reader(registeredAuthority(), facts).read(identity, declared(), "run-1")

            val subject = projection.declaredRunIdentity
            assertThat(subject.authority).isEqualTo(FactAuthority.PRODUCER_DECLARED)
            assertThat(subject.authority).isNotEqualTo(FactAuthority.AUTHORITATIVE_PERSISTED)
            assertThat(subject.freshness).isEqualTo(FactFreshness.STALE_OR_UNKNOWN)
            assertThat(subject.mustNotInfer)
                .anyMatch { it.contains("not authenticated against the workload registration authority") }
        }

    @Test
    fun `a registered boundary named by a record grants no verified-origin claim`() =
        runBlocking<Unit> {
            val records =
                listOf(
                    decision(
                        DecisionKind.TOOL_PERMISSION,
                        boundary = DeclaredBoundary("spring-ai-tool-callback", "AUTHORITATIVE"),
                    ),
                )
            val projection =
                reader(registeredAuthority(), ScriptedFacts(decisions = records))
                    .read(identity, declared(), "run-1")

            val toolPermission = projection.decisions.single { it.kind == DecisionKind.TOOL_PERMISSION }
            assertThat(
                toolPermission.latest.value
                    ?.boundary
                    ?.registeredStrength,
            ).isEqualTo("AUTHORITATIVE")
            assertThat(toolPermission.latest.authority).isEqualTo(FactAuthority.POLICY_AUTHORIZATION_RECORD)
            assertThat(toolPermission.latest.mustNotInfer)
                .contains(GovernanceFactClassification.BOUNDARY_ORIGIN_LIMIT)
            assertThat(projection.declaredRunIdentity.mustNotInfer)
                .anyMatch { it.contains("registered strength does not prove") }
        }

    // ---------------------------------------------------------------- authorization vs completion

    @Test
    fun `an ALLOW record is authorization evidence and the read surface cannot carry completion`() =
        runBlocking<Unit> {
            val projection =
                reader(registeredAuthority(), ScriptedFacts(decisions = listOf(decision(DecisionKind.TOOL_PERMISSION))))
                    .read(identity, declared(), "run-1")

            val fact = projection.decisions.single { it.kind == DecisionKind.TOOL_PERMISSION }.latest
            assertThat(fact.value?.decision).isEqualTo("ALLOW")
            assertThat(fact.mustNotInfer)
                .anyMatch { it.contains("never that the operation ran, succeeded or changed state") }
            val completionish = Regex("(?i)complet|result|success|output|payload|arguments|content")
            assertThat(RecordedDecision::class.java.declaredFields.map { it.name })
                .noneMatch { completionish.containsMatchIn(it) }
        }

    // ---------------------------------------------------------------- provider routing

    @Test
    fun `a missing provider route fact is unsupported, never an assertion that no routing happened`() =
        runBlocking<Unit> {
            val projection = reader(registeredAuthority()).read(identity, declared(), "run-1")

            val routing = projection.decisions.single { it.kind == DecisionKind.PROVIDER_ROUTE }
            assertThat(routing.latest.value).isNull()
            assertThat(routing.latest.authority).isEqualTo(FactAuthority.NOT_PRODUCED)
            assertThat(routing.latest.freshness).isEqualTo(FactFreshness.ABSENT)
            assertThat(routing.latest.mustNotInfer)
                .anyMatch { it.contains("absence of routing records is not evidence that no routing") }
            assertThat(routing.completeness).isEqualTo(Completeness.UNKNOWN)
        }

    @Test
    fun `telemetry reporting a selected route cannot become routing evidence`() =
        runBlocking<Unit> {
            val facts = ScriptedFacts(telemetry = listOf(TelemetryObservation("route.selected", sourceSequence = 4)))
            val projection = reader(registeredAuthority(), facts).read(identity, declared(), "run-1")

            assertThat(projection.telemetry.authority).isEqualTo(FactAuthority.TELEMETRY)
            assertThat(projection.telemetry.mustNotInfer)
                .anyMatch { it.contains("is not routing evidence") }
            assertThat(
                projection.decisions
                    .single { it.kind == DecisionKind.PROVIDER_ROUTE }
                    .latest.authority,
            ).isEqualTo(FactAuthority.NOT_PRODUCED)
        }

    // ---------------------------------------------------------------- ordering, duplication, freshness

    @Test
    fun `a duplicated source event does not duplicate the projected fact`() =
        runBlocking<Unit> {
            val record = decision(DecisionKind.POLICY_DECISION)
            val projection =
                reader(registeredAuthority(), ScriptedFacts(decisions = listOf(record, record)))
                    .read(identity, declared(), "run-1")

            val policy = projection.decisions.single { it.kind == DecisionKind.POLICY_DECISION }
            assertThat(policy.duplicatesIgnored).isEqualTo(1)
            assertThat(policy.completeness).isEqualTo(Completeness.COMPLETE)
            assertThat(projection.decisions.count { it.kind == DecisionKind.POLICY_DECISION }).isEqualTo(1)
        }

    @Test
    fun `an older record cannot overwrite the newer position`() =
        runBlocking<Unit> {
            val newer = decision(DecisionKind.POLICY_DECISION, value = "ALLOW", sequence = 3)
            val older = decision(DecisionKind.POLICY_DECISION, value = "DENY", sequence = 1)
            val projection =
                reader(registeredAuthority(), ScriptedFacts(decisions = listOf(newer, older)))
                    .read(identity, declared(), "run-1")

            val policy = projection.decisions.single { it.kind == DecisionKind.POLICY_DECISION }
            assertThat(policy.latest.sourceVersion).isEqualTo(3)
            assertThat(policy.latest.value?.decision).isEqualTo("ALLOW")
            assertThat(policy.staleIgnored).isEqualTo(1)
        }

    @Test
    fun `a fold with a missing intermediate event does not claim completeness`() =
        runBlocking<Unit> {
            val records =
                listOf(
                    decision(DecisionKind.POLICY_DECISION, sequence = 1),
                    decision(DecisionKind.POLICY_DECISION, sequence = 3),
                )
            val projection =
                reader(registeredAuthority(), ScriptedFacts(decisions = records))
                    .read(identity, declared(), "run-1")

            val policy = projection.decisions.single { it.kind == DecisionKind.POLICY_DECISION }
            assertThat(policy.completeness).isEqualTo(Completeness.GAPS_PRESENT)
            assertThat(policy.latest.sourceVersion).isEqualTo(3)
        }

    @Test
    fun `freshness is not invented when the source reports no watermark`() =
        runBlocking<Unit> {
            val facts =
                ScriptedFacts(
                    decisions =
                        listOf(
                            decision(DecisionKind.POLICY_DECISION, sequence = null),
                            decision(DecisionKind.POLICY_DECISION, value = "DENY", sequence = null),
                        ),
                    telemetry = listOf(TelemetryObservation("provider.call.completed")),
                )
            val projection = reader(registeredAuthority(), facts).read(identity, declared(), "run-1")

            val policy = projection.decisions.single { it.kind == DecisionKind.POLICY_DECISION }
            assertThat(policy.completeness).isEqualTo(Completeness.UNKNOWN)
            assertThat(policy.latest.freshness).isEqualTo(FactFreshness.STALE_OR_UNKNOWN)
            assertThat(policy.latest.freshness).isNotEqualTo(FactFreshness.EVENTUAL_KNOWN_LAG)
            assertThat(projection.telemetry.freshness).isEqualTo(FactFreshness.STALE_OR_UNKNOWN)
            assertThat(projection.telemetry.watermark).isNull()
        }

    // ---------------------------------------------------------------- historical truth

    @Test
    fun `a historical decision is never restated at the current registration version`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            authority.updateMetadata(
                identity.workloadId,
                identity.environmentId,
                identity.deploymentId,
                WorkloadStateVersion.INITIAL,
                WorkloadMetadata(owner = "Second owner", purpose = "advanced"),
            )
            val facts = ScriptedFacts(decisions = listOf(decision(DecisionKind.POLICY_DECISION, sequence = 1)))
            val projection = reader(authority, facts).read(identity, declared(), "run-1")

            assertThat(projection.registration.observedVersion).isEqualTo(WorkloadStateVersion(2))
            val policy = projection.decisions.single { it.kind == DecisionKind.POLICY_DECISION }
            assertThat(policy.latest.freshness).isEqualTo(FactFreshness.HISTORICAL)
            assertThat(policy.latest.sourceVersion).isEqualTo(1)
            assertThat(policy.latest.observedVersion).isNull()
        }

    @Test
    fun `an unregistered scope reports the authority's own absence, not a governance conclusion`() =
        runBlocking<Unit> {
            val authority = WorkloadRegistrationAuthority(InMemoryWorkloadRegistrationStore())
            val projection = reader(authority).read(identity, declared(), "run-1")

            assertThat(projection.registration.value).isNull()
            assertThat(projection.registration.authority).isEqualTo(FactAuthority.AUTHORITATIVE_PERSISTED)
            assertThat(projection.registration.freshness).isEqualTo(FactFreshness.ABSENT)
            assertThat(projection.registration.mustNotInfer)
                .anyMatch { it.contains("absence of registration is not evidence that a run is ungoverned") }
        }

    // ---------------------------------------------------------------- sensitive payloads

    @Test
    fun `the read surface carries no map, any or payload field`() {
        val shape =
            mapOf(
                GovernanceProjection::class.java to
                    setOf("registration", "declaredRunIdentity", "decisions", "telemetry"),
                DecisionProjection::class.java to
                    setOf("kind", "latest", "completeness", "duplicatesIgnored", "staleIgnored"),
                ProjectedFact::class.java to
                    setOf(
                        "value",
                        "authority",
                        "freshness",
                        "established",
                        "mustNotInfer",
                        "observedVersion",
                        "sourceVersion",
                        "watermark",
                    ),
                RecordedDecision::class.java to
                    setOf("workflowRunId", "kind", "decision", "reasonCode", "boundary", "sourceSequence"),
                TelemetryObservation::class.java to setOf("name", "sourceSequence"),
                DeclaredRunIdentity::class.java to
                    setOf(
                        "workloadId",
                        "configurationId",
                        "configurationVersion",
                        "environmentId",
                        "deploymentId",
                        "workflowRunId",
                    ),
                DeclaredBoundary::class.java to setOf("id", "registeredStrength"),
            )
        shape.forEach { (type, approved) ->
            assertThat(instanceFieldsOf(type).map { it.name })
                .`as`("exact field shape of ${type.simpleName}")
                .containsExactlyInAnyOrderElementsOf(approved)
            assertThat(instanceFieldsOf(type).map { it.type })
                .`as`("no map carrier on ${type.simpleName}")
                .noneMatch { Map::class.java.isAssignableFrom(it) }
        }
        val payloadish = Regex("(?i)prompt|content|argument|credential|token|secret|body|traffic|payload|result")
        assertThat(shape.keys.flatMap { instanceFieldsOf(it).map { field -> field.name } })
            .noneMatch { payloadish.containsMatchIn(it) }
    }

    private fun instanceFieldsOf(type: Class<*>) =
        type.declaredFields.filterNot {
            java.lang.reflect.Modifier
                .isStatic(it.modifiers)
        }

    // ---------------------------------------------------------------- read-only proof

    @Test
    fun `the projection cannot reach a command port or a command outcome type`() =
        runBlocking<Unit> {
            val readerTypes = typesReachableFrom(GovernanceProjectionReader::class.java)
            assertThat(readerTypes).doesNotContain(
                WorkloadControlPlaneCommands::class.java,
                RegisterOutcome::class.java,
                MetadataUpdateOutcome::class.java,
                LifecycleTransitionOutcome::class.java,
                RegisteredWorkload::class.java,
                ConfigurationFingerprint::class.java,
            )
            assertThat(GovernanceProjectionReader::class.java.declaredFields.map { it.type })
                .containsExactlyInAnyOrder(
                    WorkloadControlPlaneQueries::class.java,
                    GovernanceDecisionFactSource::class.java,
                )
        }

    @Test
    fun `reading the projection changes no authoritative state and invokes no effect`() =
        runBlocking<Unit> {
            val store = InMemoryWorkloadRegistrationStore()
            val authority = WorkloadRegistrationAuthority(store)
            authority.register(
                identity,
                WorkloadRegistrationFixtures.fingerprint(),
                WorkloadMetadata(owner = "Claims Team", purpose = "Fraud review"),
            )
            val facts = ScriptedFacts(decisions = listOf(decision(DecisionKind.TOOL_PERMISSION)))
            val target = reader(authority, facts)

            val first = target.read(identity, declared(), "run-1")
            val second = target.read(identity, declared(), "run-1")

            assertThat(second).isEqualTo(first)
            assertThat(store.find(identity.workloadId, identity.environmentId, identity.deploymentId)?.stateVersion)
                .isEqualTo(WorkloadStateVersion.INITIAL)
            assertThat(facts.decisionReads).isEqualTo(2)
            assertThat(facts.telemetryReads).isEqualTo(2)
        }

    @Test
    fun `a read with no declared identity reports the absence rather than an ungoverned run`() =
        runBlocking<Unit> {
            val projection = reader(registeredAuthority()).read(identity, null, "run-1")

            assertThat(projection.declaredRunIdentity.value).isNull()
            assertThat(projection.declaredRunIdentity.authority).isEqualTo(FactAuthority.NOT_PRODUCED)
            assertThat(projection.declaredRunIdentity.freshness).isEqualTo(FactFreshness.ABSENT)
            assertThat(projection.declaredRunIdentity.mustNotInfer)
                .anyMatch { it.contains("not a negative governance fact") }
        }

    @Test
    fun `a read with no run id claims no decision fact and no freshness`() =
        runBlocking<Unit> {
            val facts = ScriptedFacts(decisions = listOf(decision(DecisionKind.POLICY_DECISION)))
            val projection = reader(registeredAuthority(), facts).read(identity, declared(), null)

            assertThat(projection.decisions).allSatisfy { decision ->
                assertThat(decision.latest.authority).isEqualTo(FactAuthority.NOT_PRODUCED)
                assertThat(decision.latest.freshness).isEqualTo(FactFreshness.ABSENT)
                assertThat(decision.completeness).isEqualTo(Completeness.UNKNOWN)
            }
            assertThat(projection.telemetry.freshness).isEqualTo(FactFreshness.ABSENT)
            assertThat(projection.telemetry.mustNotInfer).isEmpty()
            assertThat(facts.decisionReads).isZero()
            assertThat(facts.telemetryReads).isZero()
        }

    @Test
    fun `a wide gap between source positions is a gap, not a hang`() =
        runBlocking<Unit> {
            val records =
                listOf(
                    decision(DecisionKind.POLICY_DECISION, sequence = 1),
                    decision(DecisionKind.POLICY_DECISION, sequence = 5_000_000_000L),
                )
            val projection =
                reader(registeredAuthority(), ScriptedFacts(decisions = records))
                    .read(identity, declared(), "run-1")

            val policy = projection.decisions.single { it.kind == DecisionKind.POLICY_DECISION }
            assertThat(policy.completeness).isEqualTo(Completeness.GAPS_PRESENT)
            assertThat(policy.latest.sourceVersion).isEqualTo(5_000_000_000L)
        }

    // ---------------------------------------------------------------- the load-bearing discriminators

    @Test
    fun `a declared identity cannot be labelled current at the version it does not own`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectedFact(
                value = declared(),
                authority = FactAuthority.PRODUCER_DECLARED,
                freshness = FactFreshness.CURRENT_AT_VERSION,
                established = "declared identity presented as current at the authority's version",
                mustNotInfer = listOf("origin is not authenticated"),
                observedVersion = WorkloadStateVersion.INITIAL,
            )
        }
    }

    @Test
    fun `a declared fact must state what may not be inferred`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectedFact(
                value = declared(),
                authority = FactAuthority.PRODUCER_DECLARED,
                freshness = FactFreshness.STALE_OR_UNKNOWN,
                established = "declared identity with no stated limit",
            )
        }
    }

    @Test
    fun `a current fact must name the version it is current at`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectedFact(
                value = declared(),
                authority = FactAuthority.AUTHORITATIVE_PERSISTED,
                freshness = FactFreshness.CURRENT_AT_VERSION,
                established = "current without a version",
                observedVersion = null,
            )
        }
    }

    @Test
    fun `absence cannot be dressed as a recorded authorization`() {
        assertFailsWith<IllegalArgumentException> {
            ProjectedFact<DeclaredRunIdentity>(
                value = null,
                authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
                freshness = FactFreshness.ABSENT,
                established = "absence dressed as a record",
            )
        }
    }

    @Test
    fun `a routing fact cannot be presented as engine-established`() {
        assertFailsWith<IllegalArgumentException> {
            projectionWith(listOf(routingProjection(FactAuthority.AUTHORITATIVE_PERSISTED)))
        }
    }

    @Test
    fun `a recorded decision cannot be presented as authoritative runtime state`() {
        assertFailsWith<IllegalArgumentException> {
            projectionWith(listOf(authorizationProjection(FactAuthority.AUTHORITATIVE_PERSISTED)))
        }
    }

    private fun projected(
        kind: DecisionKind,
        authority: FactAuthority,
    ) = ProjectedFact(
        value = decision(kind),
        authority = authority,
        freshness = FactFreshness.HISTORICAL,
        established = "fact under an authority its producer never had",
        mustNotInfer = listOf("stated so the guard, not the limit, is what rejects this"),
        sourceVersion = 1,
    )

    private fun routingProjection(authority: FactAuthority) =
        DecisionProjection(
            DecisionKind.PROVIDER_ROUTE,
            projected(DecisionKind.PROVIDER_ROUTE, authority),
            Completeness.COMPLETE,
        )

    private fun authorizationProjection(authority: FactAuthority) =
        DecisionProjection(
            DecisionKind.TOOL_PERMISSION,
            projected(DecisionKind.TOOL_PERMISSION, authority),
            Completeness.COMPLETE,
        )

    private fun projectionWith(decisions: List<DecisionProjection>) =
        GovernanceProjection(
            registration =
                ProjectedFact(
                    value = null,
                    authority = FactAuthority.NOT_PRODUCED,
                    freshness = FactFreshness.ABSENT,
                    established = "no registration",
                ),
            declaredRunIdentity =
                ProjectedFact(
                    value = declared(),
                    authority = FactAuthority.PRODUCER_DECLARED,
                    freshness = FactFreshness.STALE_OR_UNKNOWN,
                    established = "declared",
                    mustNotInfer = listOf("origin is not authenticated"),
                ),
            decisions = decisions,
            telemetry =
                ProjectedFact(
                    value = emptyList<TelemetryObservation>(),
                    authority = FactAuthority.TELEMETRY,
                    freshness = FactFreshness.STALE_OR_UNKNOWN,
                    established = "no telemetry",
                ),
        )

    private fun typesReachableFrom(root: Class<*>): Set<Class<*>> {
        val seen = linkedSetOf<Class<*>>()

        fun walk(type: Class<*>) {
            if (type.isPrimitive || type.name.startsWith("java.lang.") || !seen.add(type)) return
            type.declaredFields.forEach { walk(it.type) }
            type.declaredMethods.forEach { walk(it.returnType) }
            type.declaredConstructors.forEach { ctor -> ctor.parameterTypes.forEach { walk(it) } }
        }
        walk(root)
        return seen
    }
}
