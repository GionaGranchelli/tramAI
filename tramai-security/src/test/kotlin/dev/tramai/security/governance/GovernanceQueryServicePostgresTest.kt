package dev.tramai.security.governance

import dev.tramai.controlplane.InMemoryWorkloadRegistrationStore
import dev.tramai.controlplane.WorkloadRegistrationAuthority
import dev.tramai.controlplane.WorkloadStateVersion
import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import dev.tramai.core.identity.WorkloadMetadata
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.security.audit.AuditEmission
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.governance.query.Completeness
import dev.tramai.security.governance.query.DecisionKind
import dev.tramai.security.governance.query.FactAuthority
import dev.tramai.security.governance.query.FactFreshness
import dev.tramai.security.governance.query.GovernanceQueryRequest
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.GovernanceQueryService
import dev.tramai.security.governance.query.ReadConsistency
import dev.tramai.security.governance.query.ReasonCodeExposure
import dev.tramai.security.governance.query.SourceCurrency
import dev.tramai.security.governance.query.VerifiedStreamRange
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.sql.DataSource
import kotlin.test.Test
import dev.tramai.security.governance.Completeness as InternalCompleteness
import dev.tramai.security.governance.DecisionKind as InternalDecisionKind
import dev.tramai.security.governance.FactAuthority as InternalFactAuthority
import dev.tramai.security.governance.FactFreshness as InternalFactFreshness

/**
 * 0.7.4e phase 4 — real PostgreSQL-backed qualification of [GovernanceQueryService].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GovernanceQueryServicePostgresTest {
    private lateinit var postgres: PostgreSQLContainer
    private lateinit var dataSource: DataSource
    private lateinit var setupConnection: Connection

    private val scope = WorkloadRegistrationFixtures.identity()
    private val runId = "run-1"
    private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))

    private lateinit var auditStore: InMemoryAuditStore
    private lateinit var engine: AuditEngine
    private lateinit var emitter: AuditEnginePolicyDecisionAuditEmitter

    @BeforeEach
    fun resetAuditStream() {
        auditStore = InMemoryAuditStore()
        engine = AuditEngine(auditStore, clock = clock)
        emitter = AuditEnginePolicyDecisionAuditEmitter(engine)
    }

    @BeforeAll
    fun setUpAll() {
        postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("governance_query_api")
                .withUsername("test")
                .withPassword("test")
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        setupConnection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        val migration = "V1__governance_projection_read_model.sql"
        val sql =
            this::class.java.classLoader
                .getResource("tramai/security/governance/postgres/$migration")
                ?.readText()
                ?: error("Migration not found: $migration")
        setupConnection.createStatement().use { it.execute(sql) }
    }

    @AfterAll
    fun tearDownAll() {
        runCatching { setupConnection.close() }
        runCatching { postgres.stop() }
    }

    private fun freshStore(): JdbcGovernanceProjectionStore {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("DELETE FROM governance_projection_snapshot") }
        }
        return JdbcGovernanceProjectionStore(dataSource)
    }

    private suspend fun registeredAuthority(): WorkloadRegistrationAuthority {
        val authority = WorkloadRegistrationAuthority(InMemoryWorkloadRegistrationStore())
        authority.register(
            scope,
            WorkloadRegistrationFixtures.fingerprint(),
            WorkloadMetadata(owner = "Claims Team", purpose = "Fraud review"),
        )
        return authority
    }

    private fun identity(workflowRunId: String? = runId): ProjectionIdentity =
        ProjectionIdentity("claims", "production", "eu-west-amsterdam-01", workflowRunId)

    private fun request(
        workloadId: String = "claims",
        environmentId: String = "production",
        deploymentId: String = "eu-west-amsterdam-01",
        workflowRunId: String? = runId,
    ) = GovernanceQueryRequest(
        workloadId = workloadId,
        environmentId = environmentId,
        deploymentId = deploymentId,
        workflowRunId = workflowRunId,
    )

    private fun context() =
        PolicyContext(
            enforcementPoint = EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
            workflowId = "claims",
            workflowRunId = runId,
            correlationId = "corr-1",
            actorId = "system.anonymous",
            providerId = "ollama",
            modelName = "mistral",
            toolName = null,
            policyVersion = "v17",
        )

    private suspend fun emitPolicy(decision: PolicyDecision) =
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, context(), decision)

    private fun materializer(
        authority: WorkloadRegistrationAuthority,
        store: GovernanceProjectionStore,
    ) = GovernanceProjectionMaterializer(
        GovernanceProjectionReader(authority, AuditBackedGovernanceDecisionFactSource(auditStore)),
        store,
    )

    // ------------------------------------------- 16. reason-code exposure policy (owner review round 2)

    @Test
    fun `16 a credential-shaped reason code is withheld and never exported`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            // Shaped like a live provider key. It satisfies every charset and length rule a token check can
            // apply, which is why exposure runs through an approved vocabulary instead.
            val credentialShaped = "sk-live-example123"
            emitPolicy(PolicyDecision.Deny(reason = "blocked", reasonCode = credentialShaped))
            val store = freshStore()
            assertThat(materializer(authority, store).materialize(identity(), scope, declaredIdentity()))
                .isEqualTo(MaterializationOutcome.PUBLISHED)

            val service: GovernanceQueryService = GovernanceQueryService.jdbc(dataSource = dataSource)
            val result = service.query(request())
            assertThat(result).isInstanceOf(GovernanceQueryResult.Found::class.java)
            val view = (result as GovernanceQueryResult.Found).view
            val policy = requireNotNull(view.decisionFor(DecisionKind.POLICY_DECISION))

            // The stored value is not exported, and its withholding is stated rather than rendered as
            // "no reason was recorded".
            assertThat(policy.reasonCode).isNull()
            assertThat(policy.reasonExposure).isEqualTo(ReasonCodeExposure.WITHHELD)
            assertThat(view.toString()).doesNotContain(credentialShaped)
            assertThat(policy.toString()).doesNotContain(credentialShaped)
        }

    // ------------------------------------------- 17. missing family is not a synthetic absence

    @Test
    fun `17 a decision family the projection does not contain is absent from the list`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            assertThat(materializer(authority, store).materialize(identity(), scope, declaredIdentity()))
                .isEqualTo(MaterializationOutcome.PUBLISHED)

            val service: GovernanceQueryService = GovernanceQueryService.jdbc(dataSource = dataSource)
            val view = (service.query(request()) as GovernanceQueryResult.Found).view

            // The projection reports one entry per family it recorded, and a family this deployment has no
            // producer for is reported as NOT_PRODUCED with what may not be inferred from it. That is a
            // statement about the producer set, not a claim that nothing occurred, and the API never
            // strengthens it into one.
            val tool = requireNotNull(view.decisionFor(DecisionKind.TOOL_PERMISSION))
            assertThat(tool.fact.authority).isEqualTo(FactAuthority.NOT_OBSERVED)
            assertThat(tool.fact.valuePresent).isFalse()
            assertThat(tool.fact.mustNotInfer).isNotEmpty()

            // A family the stored snapshot does not contain at all is never synthesized into a recorded
            // absence. Deleting the row leaves the digest disagreeing with the content, and the query
            // refuses the projection instead of fabricating a family from it.
            mutateStored(
                "DELETE FROM governance_projection_decision WHERE projection_key = '${identity().key}'",
            )
            assertThat(service.query(request()))
                .isInstanceOf(GovernanceQueryResult.InvalidStoredProjection::class.java)
        }

    // ------------------------------------------- 18. stored identity must match the projection key

    @Test
    fun `18 stored identity columns that disagree with the projection key are refused`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            assertThat(materializer(authority, store).materialize(identity(), scope, declaredIdentity()))
                .isEqualTo(MaterializationOutcome.PUBLISHED)

            val service: GovernanceQueryService = GovernanceQueryService.jdbc(dataSource = dataSource)
            assertThat(service.query(request())).isInstanceOf(GovernanceQueryResult.Found::class.java)

            // Rewrite the row's own identity columns, leaving the key and the digest untouched. Rebuilding
            // the identity from the request instead of comparing it would report this row under the
            // caller's scope; the digest cannot catch it because it covers the snapshot content, not the
            // identity stored beside it.
            mutateStored(
                "UPDATE governance_projection_snapshot SET workload_id = 'another-workload' " +
                    "WHERE projection_key = '${identity().key}'",
            )

            // The public result is a typed refusal whose reason is generic on purpose: the public surface
            // does not narrate internal storage detail. The discriminator that names the rule is the
            // store's own refusal, and it is asserted here rather than assumed.
            val refused = service.query(request())
            assertThat(refused).isInstanceOf(GovernanceQueryResult.InvalidStoredProjection::class.java)

            val storeRefusal = runCatching { store.load(identity()) }.exceptionOrNull()
            assertThat(storeRefusal).isInstanceOf(ProjectionCorruptionException::class.java)
            assertThat(storeRefusal?.message).contains("identity")
        }

    private fun declaredIdentity(): DeclaredRunIdentity =
        DeclaredRunIdentity(
            workloadId = "claims",
            configurationId = "cfg-1",
            configurationVersion = "v1",
            environmentId = "production",
            deploymentId = "eu-west-amsterdam-01",
            workflowRunId = runId,
        )

    private fun mutateStored(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    // ------------------------------------------------------------------ 1. previously materialized snapshot

    @Test
    fun `1 previously materialized snapshot reconstructs typed view with both coverage claims intact`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"))
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            val declared =
                DeclaredRunIdentity(
                    workloadId = "claims",
                    configurationId = "cfg-1",
                    configurationVersion = "v1",
                    environmentId = "production",
                    deploymentId = "eu-west-amsterdam-01",
                    workflowRunId = runId,
                )
            assertThat(materializer(authority, store).materialize(identity(), scope, declared))
                .isEqualTo(MaterializationOutcome.PUBLISHED)

            val queryService: GovernanceQueryService =
                GovernanceQueryService.jdbc(
                    dataSource = dataSource,
                    controlPlaneQueries = authority,
                    auditStore = auditStore,
                )

            val result = queryService.query(request())
            assertThat(result).isInstanceOf(GovernanceQueryResult.Found::class.java)
            val view = (result as GovernanceQueryResult.Found).view

            assertThat(view.identity.workloadId).isEqualTo("claims")
            assertThat(view.identity.environmentId).isEqualTo("production")
            assertThat(view.identity.deploymentId).isEqualTo("eu-west-amsterdam-01")
            assertThat(view.identity.workflowRunId).isEqualTo(runId)
            assertThat(view.formatVersion).isEqualTo(GOVERNANCE_READ_MODEL_FORMAT_VERSION)
            assertThat(view.provenance.registrationVersion).isEqualTo(1L)
            assertThat(view.provenance.auditHeadPosition).isEqualTo(2L)
            assertThat(view.provenance.auditHeadHash).isNotBlank()
            assertThat(view.provenance.classificationRevision).isEqualTo(GOVERNANCE_CLASSIFICATION_REVISION)
            assertThat(view.provenance.readConsistency).isEqualTo(ReadConsistency.INDEPENDENT_SOURCES)

            assertThat(view.registration.authority).isEqualTo(FactAuthority.AUTHORITATIVE_PERSISTED)
            assertThat(view.registration.freshness).isEqualTo(FactFreshness.CURRENT_AT_VERSION)
            assertThat(view.registration.observedVersion).isEqualTo(1L)
            assertThat(view.declaredRunIdentity.authority).isEqualTo(FactAuthority.PRODUCER_DECLARED)
            assertThat(view.declaredRunIdentity.freshness).isEqualTo(FactFreshness.STALE_OR_UNKNOWN)
            assertThat(view.declaredRunIdentity.mustNotInfer).isNotEmpty()
            assertThat(view.telemetry.authority).isEqualTo(FactAuthority.TELEMETRY)

            val policy = view.decisionFor(DecisionKind.POLICY_DECISION)!!
            assertThat(policy.decision).isEqualTo("ALLOW")
            assertThat(policy.boundaryId).isEqualTo("BEFORE_PROVIDER_INVOCATION")
            assertThat(policy.fact.authority).isEqualTo(FactAuthority.POLICY_AUTHORIZATION_RECORD)
            assertThat(policy.fact.freshness).isEqualTo(FactFreshness.HISTORICAL)
            assertThat(policy.fact.sourceVersion).isEqualTo(2L)
            assertThat(policy.staleIgnored).isEqualTo(1)

            // Both coverage claims intact and separate:
            assertThat(policy.rawCoverage.verifiedRange).isEqualTo(VerifiedStreamRange(1L, 2L))
            assertThat(policy.rawCoverage.observedHeadHash).isEqualTo(view.provenance.auditHeadHash)
            assertThat(policy.recordCoverage.completeness).isEqualTo(Completeness.COMPLETE)
            assertThat(policy.recordCoverage.accountedPositions).isEqualTo(2L)
            assertThat(policy.recordCoverage.unrecordedDecisionPositions).isEmpty()
            assertThat(policy.recordCoverage.unclassifiedPositions).isEmpty()
            assertThat(policy.recordCoverage.classificationRevision).isEqualTo(GOVERNANCE_CLASSIFICATION_REVISION)
        }

    // ------------------------------------------------------------------ 2. no matching projection

    @Test
    fun `2 querying a scope with no materialized projection returns NotMaterialized`() =
        runBlocking<Unit> {
            val store = freshStore()
            val queryService: GovernanceQueryService = StoreBackedGovernanceQueryService(store.readOnly())

            val result = queryService.query(request(workflowRunId = "run-never-materialized"))

            assertThat(result).isSameAs(GovernanceQueryResult.NotMaterialized)
        }

    // ------------------------------------------------------------------ 3. wrong workload / env / deployment

    @Test
    fun `3 querying with wrong workload environment deployment or run returns no cross-scope data`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            materializer(authority, store).materialize(identity(), scope)

            val queryService: GovernanceQueryService = StoreBackedGovernanceQueryService(store.readOnly())

            assertThat(queryService.query(request(workloadId = "other-workload")))
                .isSameAs(GovernanceQueryResult.NotMaterialized)
            assertThat(queryService.query(request(environmentId = "staging")))
                .isSameAs(GovernanceQueryResult.NotMaterialized)
            assertThat(queryService.query(request(deploymentId = "us-east-01")))
                .isSameAs(GovernanceQueryResult.NotMaterialized)
            assertThat(queryService.query(request(workflowRunId = "run-2")))
                .isSameAs(GovernanceQueryResult.NotMaterialized)
            assertThat(queryService.query(request(workflowRunId = null)))
                .isSameAs(GovernanceQueryResult.NotMaterialized)
        }

    // ------------------------------------------------------------------ 4. no fresh source observation

    @Test
    fun `4 querying without fresh source observations reports currency UNKNOWN for both sources`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            materializer(authority, store).materialize(identity(), scope)

            // No fresh registration or audit observer supplied: persisted snapshot cannot claim CURRENT.
            val queryService: GovernanceQueryService = StoreBackedGovernanceQueryService(store.readOnly())

            val view = (queryService.query(request()) as GovernanceQueryResult.Found).view

            assertThat(view.currency.registration).isEqualTo(SourceCurrency.UNKNOWN)
            assertThat(view.currency.audit).isEqualTo(SourceCurrency.UNKNOWN)
            assertThat(view.currency.note).contains("a stored row never declares itself current")
        }

    // ------------------------------------------------------------------ 5. registration version advanced

    @Test
    fun `5 registration version advancing marks registration STALE while audit currency stays CURRENT`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            materializer(authority, store).materialize(identity(), scope)

            // Advance registration version (1 -> 2) without touching the audit stream.
            authority.updateMetadata(
                workloadId = scope.workloadId,
                environmentId = scope.environmentId,
                deploymentId = scope.deploymentId,
                expectedVersion = WorkloadStateVersion(1L),
                metadata = WorkloadMetadata(owner = "Claims Team", purpose = "Updated purpose"),
            )

            val queryService: GovernanceQueryService =
                StoreBackedGovernanceQueryService(
                    store = store,
                    controlPlaneQueries = authority,
                    auditStore = auditStore,
                )

            val view = (queryService.query(request()) as GovernanceQueryResult.Found).view

            assertThat(view.currency.registration).isEqualTo(SourceCurrency.STALE)
            assertThat(view.currency.audit).isEqualTo(SourceCurrency.CURRENT)
            // Historical registration version recorded at materialization is untouched:
            assertThat(view.provenance.registrationVersion).isEqualTo(1L)
            assertThat(view.registration.observedVersion).isEqualTo(1L)
        }

    // ------------------------------------------------------------------ 6. audit head advanced

    @Test
    fun `6 audit head advancing marks audit STALE while registration currency stays CURRENT`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            materializer(authority, store).materialize(identity(), scope)

            // Append a new audit event (position 1 -> 2) without touching registration.
            emitPolicy(PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"))

            val queryService: GovernanceQueryService =
                StoreBackedGovernanceQueryService(
                    store = store,
                    controlPlaneQueries = authority,
                    auditStore = auditStore,
                )

            val view = (queryService.query(request()) as GovernanceQueryResult.Found).view

            assertThat(view.currency.audit).isEqualTo(SourceCurrency.STALE)
            assertThat(view.currency.registration).isEqualTo(SourceCurrency.CURRENT)
            assertThat(view.provenance.auditHeadPosition).isEqualTo(1L)
            assertThat(view.decisionFor(DecisionKind.POLICY_DECISION)!!.rawCoverage.verifiedRange)
                .isEqualTo(VerifiedStreamRange(1L, 1L))
        }

    // ------------------------------------------------------------------ 7. same audit position, different hash

    @Test
    fun `7 same audit head position with a different head hash marks audit currency STALE`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            materializer(authority, store).materialize(identity(), scope)

            val queryService: GovernanceQueryService =
                StoreBackedGovernanceQueryService(
                    readModel = store.readOnly(),
                    observeRegistrationVersion = { 1L },
                    observeAuditHead = { ObservedAuditHead(position = 1L, hash = "diverged-head-hash") },
                )

            val view = (queryService.query(request()) as GovernanceQueryResult.Found).view

            assertThat(view.provenance.auditHeadPosition).isEqualTo(1L)
            assertThat(view.currency.audit).isEqualTo(SourceCurrency.STALE)
            assertThat(view.currency.registration).isEqualTo(SourceCurrency.CURRENT)
        }

    // ------------------------------------------------------------------ 8. missing comparable head hash

    @Test
    fun `8 same audit position without a comparable head hash reports audit currency UNKNOWN and never CURRENT`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            materializer(authority, store).materialize(identity(), scope)

            val queryService: GovernanceQueryService =
                StoreBackedGovernanceQueryService(
                    readModel = store.readOnly(),
                    observeRegistrationVersion = { 1L },
                    observeAuditHead = { ObservedAuditHead(position = 1L, hash = null) },
                )

            val view = (queryService.query(request()) as GovernanceQueryResult.Found).view

            assertThat(view.provenance.auditHeadPosition).isEqualTo(1L)
            assertThat(view.currency.audit).isEqualTo(SourceCurrency.UNKNOWN)
            assertThat(view.currency.audit).isNotEqualTo(SourceCurrency.CURRENT)
            assertThat(view.currency.registration).isEqualTo(SourceCurrency.CURRENT)
        }

    // ------------------------------------------------------------------ 9. classification revision changed

    @Test
    fun `9 different classification revision reports completeness UNKNOWN while raw coverage survives`() =
        runBlocking<Unit> {
            val store = freshStore()
            val olderRevision = "governance-classification.v0"
            val snapshot =
                customSnapshot(
                    classificationRevision = olderRevision,
                    completeness = InternalCompleteness.COMPLETE,
                )
            assertThat(store.publish(snapshot)).isEqualTo(MaterializationOutcome.PUBLISHED)

            val queryService: GovernanceQueryService = StoreBackedGovernanceQueryService(store.readOnly())
            val view = (queryService.query(request()) as GovernanceQueryResult.Found).view

            val policy = view.decisionFor(DecisionKind.POLICY_DECISION)!!
            // Decision completeness is downgraded to UNKNOWN because classification revision moved on:
            assertThat(policy.recordCoverage.completeness).isEqualTo(Completeness.UNKNOWN)
            assertThat(policy.recordCoverage.classificationRevision).isEqualTo(olderRevision)
            // Raw stream coverage and historical decision evidence survive intact:
            assertThat(policy.rawCoverage.verifiedRange).isEqualTo(VerifiedStreamRange(1L, 3L))
            assertThat(policy.rawCoverage.observedHeadHash).isEqualTo("hash-3")
            assertThat(policy.recordCoverage.accountedPositions).isEqualTo(3L)
            assertThat(policy.decision).isEqualTo("ALLOW")
        }

    // ------------------------------------------------------------------ 10. missing decision evidence

    @Test
    fun `10 missing decision evidence preserves absence authority and forbids inferring execution`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            val store = freshStore()
            // Materialize with no audit events at all for this run:
            assertThat(materializer(authority, store).materialize(identity(), scope))
                .isEqualTo(MaterializationOutcome.PUBLISHED)

            val queryService: GovernanceQueryService = StoreBackedGovernanceQueryService(store.readOnly())
            val view = (queryService.query(request()) as GovernanceQueryResult.Found).view

            val policy = view.decisionFor(DecisionKind.POLICY_DECISION)!!
            assertThat(policy.decision).isNull()
            assertThat(policy.fact.valuePresent).isFalse()
            assertThat(policy.fact.authority).isEqualTo(FactAuthority.NOT_OBSERVED)
            assertThat(policy.fact.freshness).isEqualTo(FactFreshness.ABSENT)
            assertThat(policy.recordCoverage.completeness).isEqualTo(Completeness.UNKNOWN)
            assertThat(policy.rawCoverage.verifiedRange).isNull()
            assertThat(policy.fact.mustNotInfer)
                .anyMatch { it.contains("does not establish that no decision was produced") }

            val tool = view.decisionFor(DecisionKind.TOOL_PERMISSION)!!
            assertThat(tool.decision).isNull()
            assertThat(tool.fact.authority).isEqualTo(FactAuthority.NOT_OBSERVED)
            assertThat(tool.fact.freshness).isEqualTo(FactFreshness.ABSENT)
            assertThat(tool.recordCoverage.completeness).isEqualTo(Completeness.UNKNOWN)

            val route = view.decisionFor(DecisionKind.PROVIDER_ROUTE)!!
            assertThat(route.decision).isNull()
            assertThat(route.fact.authority).isEqualTo(FactAuthority.NOT_PRODUCED)
            assertThat(route.fact.freshness).isEqualTo(FactFreshness.ABSENT)
            assertThat(route.recordCoverage.completeness).isEqualTo(Completeness.UNKNOWN)
            assertThat(route.fact.mustNotInfer)
                .anyMatch { it.contains("absence of routing records is not evidence") }
        }

    // ------------------------------------------------------------------ 11. stored digest corrupted

    @Test
    fun `11 corrupt digest malformed coverage and mismatched identity return InvalidStoredProjection`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            materializer(authority, store).materialize(identity(), scope)

            // (a) Corrupt stored digest -> store throws ProjectionCorruptionException,
            // query returns InvalidStoredProjection:
            mutateStored("UPDATE governance_projection_snapshot SET semantic_digest = '0000000000000000'")
            val readOnly = store.readOnly()
            assertThatThrownBy { runBlocking { readOnly.load(identity()) } }
                .isInstanceOf(ProjectionCorruptionException::class.java)

            val queryService: GovernanceQueryService = StoreBackedGovernanceQueryService(readOnly)
            val digestResult = queryService.query(request())
            assertThat(digestResult).isEqualTo(
                GovernanceQueryResult.InvalidStoredProjection(
                    "stored governance projection failed integrity or contract verification",
                ),
            )

            // (b) Malformed decision coverage (unrecorded position outside verified range 1..1) ->
            // store maps contract-guard IllegalArgumentException to ProjectionCorruptionException,
            // query returns InvalidStoredProjection:
            val storeWithBadCoverage = freshStore()
            materializer(authority, storeWithBadCoverage).materialize(identity(), scope)
            mutateStored("UPDATE governance_projection_decision SET unrecorded_positions = '99'")
            val badCoverageReadOnly = storeWithBadCoverage.readOnly()
            assertThatThrownBy { runBlocking { badCoverageReadOnly.load(identity()) } }
                .isInstanceOf(ProjectionCorruptionException::class.java)

            val badCoverageService: GovernanceQueryService = StoreBackedGovernanceQueryService(badCoverageReadOnly)
            val coverageResult = badCoverageService.query(request())
            assertThat(coverageResult).isEqualTo(
                GovernanceQueryResult.InvalidStoredProjection(
                    "stored governance projection failed integrity or contract verification",
                ),
            )

            // (c) Stored identity that disagrees with the requested scope ->
            // refused by matchesRequestedScope as InvalidStoredProjection:
            val validStore = freshStore()
            materializer(authority, validStore).materialize(identity(), scope)
            val validLoaded = validStore.readOnly().load(identity())!!
            val fixedReadModel =
                object : GovernanceProjectionReadModel {
                    override suspend fun load(identity: ProjectionIdentity) = validLoaded
                }
            val mismatchedService: GovernanceQueryService = StoreBackedGovernanceQueryService(fixedReadModel)
            val mismatchResult = mismatchedService.query(request(workloadId = "other-workload"))
            assertThat(mismatchResult).isEqualTo(
                GovernanceQueryResult.InvalidStoredProjection(
                    "stored governance projection identity does not match the requested scope",
                ),
            )

            // (d) An IllegalArgumentException from translation or observation guard is never misreported
            // as InvalidStoredProjection; it propagates loudly as IllegalArgumentException:
            val loudDefectService: GovernanceQueryService =
                StoreBackedGovernanceQueryService(
                    readModel = validStore.readOnly(),
                    observeAuditHead = { ObservedAuditHead(position = 0L, hash = "invalid") },
                )
            assertThatThrownBy { loudDefectService.query(request()) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("audit positions start at one")
        }

    // ------------------------------------------------------------------ 12. unknown storage format

    @Test
    fun `12 unknown stored format version is refused as UnsupportedFormat with no automatic upgrade`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            materializer(authority, store).materialize(identity(), scope)
            mutateStored("UPDATE governance_projection_snapshot SET format_version = 'governance-read-model.v99'")

            val readOnly = store.readOnly()
            assertThatThrownBy { runBlocking { readOnly.load(identity()) } }
                .isInstanceOf(UnsupportedProjectionFormatException::class.java)

            val queryService: GovernanceQueryService = StoreBackedGovernanceQueryService(readOnly)
            val result = queryService.query(request())

            assertThat(result).isInstanceOf(GovernanceQueryResult.UnsupportedFormat::class.java)
            assertThat(result).isNotEqualTo(GovernanceQueryResult.NotMaterialized)
            assertThat(result).isNotInstanceOf(GovernanceQueryResult.InvalidStoredProjection::class.java)
            val refusal = result as GovernanceQueryResult.UnsupportedFormat
            assertThat(refusal.reason).contains("unsupported format version")
        }

    // ------------------------------------------------------------------ 13. no mutation operation reachable

    @Test
    fun `13 no mutation publish rebuild update or delete operation is reachable through the query port`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            val store = freshStore()
            materializer(authority, store).materialize(identity(), scope)

            val queryService: GovernanceQueryService = StoreBackedGovernanceQueryService(store.readOnly())

            val serviceInstanceMethods =
                GovernanceQueryService::class.java.declaredMethods
                    .filter {
                        !java.lang.reflect.Modifier
                            .isStatic(it.modifiers)
                    }.map { it.name }
            assertThat(serviceInstanceMethods).containsExactly("query")

            val publicImplMethods =
                queryService::class.java.declaredMethods
                    .filter {
                        java.lang.reflect.Modifier
                            .isPublic(it.modifiers) && !it.isSynthetic
                    }.map { it.name }
            assertThat(publicImplMethods).containsExactly("query")
            assertThat(queryService::class.java.methods.map { it.name }).doesNotContain(
                "publish",
                "materialize",
                "rebuild",
                "update",
                "delete",
                "refresh",
            )

            // Authority upgrade is also impossible at the public view boundary:
            val found = queryService.query(request()) as GovernanceQueryResult.Found
            val policy = found.view.decisionFor(DecisionKind.POLICY_DECISION)!!
            assertThatThrownBy {
                policy.copy(
                    fact =
                        policy.fact.copy(
                            authority = FactAuthority.AUTHORITATIVE_PERSISTED,
                            freshness = FactFreshness.CURRENT_AT_VERSION,
                            observedVersion = 1L,
                        ),
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("POLICY_AUTHORIZATION_RECORD")
        }

    // ------------------------------------------------------------------ 14. protected-data sentinel

    @Test
    fun `14 protected data sentinels in audit metadata and reason fields never appear in the public query response`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            val rawErrorSentinel = "org.postgresql.util.PSQLException: SENTINEL_RAW_VENDOR_ERROR"
            engine.emit(
                AuditEmission(
                    auditStreamId = runId,
                    workflowRunId = runId,
                    correlationId = "corr-1",
                    actor = "actor-1",
                    enforcementPoint = EnforcementPoint.BEFORE_PROVIDER_INVOCATION.name,
                    decision = "DENY",
                    policyVersion = "v1",
                    workflowDigest = null,
                    reasonCode = rawErrorSentinel,
                    metadata =
                        mapOf(
                            "prompt" to "SENTINEL_SECRET_PROMPT_BODY_99182",
                            "completion" to "SENTINEL_COMPLETION_OUTPUT_77261",
                            "toolArguments" to "SENTINEL_TOOL_ARGUMENTS_55412",
                            "authorization" to "Bearer sk-live-SENTINEL_CREDENTIAL_88310",
                            "documentBody" to "SENTINEL_DOCUMENT_BODY_11223",
                            "rawError" to rawErrorSentinel,
                        ),
                ),
            )

            val store = freshStore()
            assertThat(materializer(authority, store).materialize(identity(), scope))
                .isEqualTo(MaterializationOutcome.PUBLISHED)

            val queryService: GovernanceQueryService =
                StoreBackedGovernanceQueryService(store, authority, auditStore)
            val result = queryService.query(request()) as GovernanceQueryResult.Found
            val serialized = result.toString() + " | " + result.view.toString()

            listOf("SENTINEL_", "PSQLException", "sk-live").forEach { sentinel ->
                assertThat(serialized).doesNotContain(sentinel)
            }
            val policy = result.view.decisionFor(DecisionKind.POLICY_DECISION)!!
            assertThat(policy.decision).isEqualTo("DENY")
            assertThat(policy.reasonCode).isNull()
        }

    // ------------------------------------------------------------------ 15. accounted positions vs verified range span

    @Test
    fun `15 accounted positions are the recorded positions not the span of the verified range`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            emitPolicy(PolicyDecision.Allow)
            listOf(EnforcementPoint.BEFORE_TOOL_EXECUTION.name, "UNRECOGNISED_BOUNDARY").forEach { pt ->
                val emission =
                    AuditEmission(runId, runId, "corr-1", "actor-1", pt, "ALLOW", "v1", null, "r", emptyMap())
                engine.emit(emission)
            }

            val store = freshStore()
            assertThat(materializer(authority, store).materialize(identity(), scope))
                .isEqualTo(MaterializationOutcome.PUBLISHED)

            val queryService = GovernanceQueryService.jdbc(dataSource, authority, auditStore)
            val result = queryService.query(request()) as GovernanceQueryResult.Found
            val policy = result.view.decisionFor(DecisionKind.POLICY_DECISION)!!

            val verifiedRange = policy.rawCoverage.verifiedRange!!
            assertThat(verifiedRange).isEqualTo(VerifiedStreamRange(1L, 3L))
            assertThat(verifiedRange.span).isEqualTo(3L)
            assertThat(policy.recordCoverage.accountedPositions).isEqualTo(2L)
            assertThat(verifiedRange.span).isGreaterThan(policy.recordCoverage.accountedPositions)
            assertThat(policy.recordCoverage.unrecordedDecisionPositions).containsExactly(2L)
            assertThat(policy.recordCoverage.unclassifiedPositions).containsExactly(3L)
            assertThat(policy.recordCoverage.completeness).isEqualTo(Completeness.UNKNOWN)
        }
}

// ------------------------------------------------------------------ fixture helper

private fun customSnapshot(
    classificationRevision: String,
    completeness: InternalCompleteness,
): GovernanceProjectionSnapshot =
    GovernanceProjectionSnapshot(
        formatVersion = GOVERNANCE_READ_MODEL_FORMAT_VERSION,
        identity = ProjectionIdentity("claims", "production", "eu-west-amsterdam-01", "run-1"),
        registration =
            SnapshotFact(
                authority = InternalFactAuthority.AUTHORITATIVE_PERSISTED,
                freshness = InternalFactFreshness.CURRENT_AT_VERSION,
                established = "registration authority state at version 1",
                mustNotInfer = listOf("registration is not execution evidence"),
                valuePresent = true,
                observedVersion = 1L,
            ),
        declaredRunIdentity =
            SnapshotFact(
                authority = InternalFactAuthority.NOT_PRODUCED,
                freshness = InternalFactFreshness.ABSENT,
                established = "no declared run identity accompanied this read",
                mustNotInfer = listOf("missing declaration is not a negative governance fact"),
                valuePresent = false,
            ),
        telemetry =
            SnapshotFact(
                authority = InternalFactAuthority.TELEMETRY,
                freshness = InternalFactFreshness.STALE_OR_UNKNOWN,
                established = "0 telemetry observations for this run",
                mustNotInfer = listOf("telemetry is an observation, not a governance fact"),
                valuePresent = true,
            ),
        decisions = listOf(customPolicyDecision(classificationRevision, completeness)),
        provenance =
            SourceProvenance(
                registrationVersion = 1L,
                auditHeadPosition = 3L,
                auditHeadHash = "hash-3",
                classificationRevision = classificationRevision,
            ),
    )

private fun customPolicyDecision(
    classificationRevision: String,
    completeness: InternalCompleteness,
): SnapshotDecision =
    SnapshotDecision(
        kind = InternalDecisionKind.POLICY_DECISION,
        completeness = completeness,
        coverage =
            SnapshotCoverage(
                verifiedRange = 1L..3L,
                observedHeadHash = "hash-3",
                accountedPositions = 3L,
                unrecordedDecisionPositions = emptySet(),
                unclassifiedPositions = emptySet(),
                classificationRevision = classificationRevision,
            ),
        latest =
            SnapshotFact(
                authority = InternalFactAuthority.POLICY_AUTHORIZATION_RECORD,
                freshness = InternalFactFreshness.HISTORICAL,
                established = "a recorded policy decision fact at its own source position",
                mustNotInfer = listOf("authorization is not execution"),
                valuePresent = true,
                sourceVersion = 3L,
            ),
        latestDecision = "ALLOW",
        latestReasonCode = "policy_allowed",
        latestBoundaryId = "BEFORE_PROVIDER_INVOCATION",
        latestBoundaryStrength = null,
        duplicatesIgnored = 0,
        staleIgnored = 0,
    )
