package dev.tramai.security.governance.consumer

import dev.tramai.security.governance.query.Completeness
import dev.tramai.security.governance.query.DecisionKind
import dev.tramai.security.governance.query.DecisionRecordCoverage
import dev.tramai.security.governance.query.FactAuthority
import dev.tramai.security.governance.query.FactFreshness
import dev.tramai.security.governance.query.GovernanceCurrencyAssessment
import dev.tramai.security.governance.query.GovernanceDecisionFact
import dev.tramai.security.governance.query.GovernanceFactSummary
import dev.tramai.security.governance.query.GovernanceProjectionIdentity
import dev.tramai.security.governance.query.GovernanceProjectionView
import dev.tramai.security.governance.query.GovernanceQueryRequest
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.GovernanceQueryService
import dev.tramai.security.governance.query.GovernanceSourceProvenance
import dev.tramai.security.governance.query.RawStreamCoverage
import dev.tramai.security.governance.query.ReadConsistency
import dev.tramai.security.governance.query.SourceCurrency
import dev.tramai.security.governance.query.VerifiedStreamRange
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Instant
import kotlin.test.Test

/**
 * 0.7.4e — external Kotlin consumer test exercising only the public `dev.tramai.security.governance.query`
 * contract from outside the internal implementation package.
 */
class GovernanceQueryKotlinConsumerTest {
    @Test
    fun `request validates blank and malformed identifiers at construction`() {
        assertThatThrownBy { GovernanceQueryRequest("", "prod", "dep-1") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("workloadId")

        assertThatThrownBy { GovernanceQueryRequest("claims", "prod env with spaces", "dep-1") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("environmentId")

        assertThatThrownBy { GovernanceQueryRequest("claims", "prod", "dep-1", "run/invalid") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("workflowRunId")
    }

    @Test
    fun `sealed result variants are distinct and preserve authority freshness and dual coverage`() {
        val sampleView = sampleView()
        val service =
            GovernanceQueryService { request ->
                when (request.workflowRunId) {
                    "run-found" -> GovernanceQueryResult.Found(sampleView)
                    "run-unsupported" -> GovernanceQueryResult.UnsupportedFormat("unsupported format v99")
                    "run-corrupt" -> GovernanceQueryResult.InvalidStoredProjection("digest mismatch")
                    else -> GovernanceQueryResult.NotMaterialized
                }
            }

        assertThat(service.query(GovernanceQueryRequest("claims", "prod", "dep-1", "run-missing")))
            .isSameAs(GovernanceQueryResult.NotMaterialized)

        assertThat(service.query(GovernanceQueryRequest("claims", "prod", "dep-1", "run-unsupported")))
            .isEqualTo(GovernanceQueryResult.UnsupportedFormat("unsupported format v99"))

        assertThat(service.query(GovernanceQueryRequest("claims", "prod", "dep-1", "run-corrupt")))
            .isEqualTo(GovernanceQueryResult.InvalidStoredProjection("digest mismatch"))

        val found =
            service.query(GovernanceQueryRequest("claims", "prod", "dep-1", "run-found"))
                as GovernanceQueryResult.Found
        val policy = found.view.decisionFor(DecisionKind.POLICY_DECISION)!!
        assertThat(policy.rawCoverage.verifiedRange).isEqualTo(VerifiedStreamRange(1L, 4L))
        assertThat(policy.recordCoverage.completeness).isEqualTo(Completeness.GAPS_PRESENT)
        assertThat(policy.recordCoverage.unrecordedDecisionPositions).containsExactly(3L)
    }

    @Test
    fun `public contract forbids authority upgrades and incomplete coverage claims`() {
        // Provider route cannot be upgraded to AUTHORITATIVE_PERSISTED or POLICY_AUTHORIZATION_RECORD:
        assertThatThrownBy {
            GovernanceDecisionFact(
                kind = DecisionKind.PROVIDER_ROUTE,
                fact =
                    GovernanceFactSummary(
                        authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
                        freshness = FactFreshness.HISTORICAL,
                        established = "recorded route",
                        mustNotInfer = listOf("not authoritative"),
                        valuePresent = true,
                        sourceVersion = 1L,
                    ),
                rawCoverage = RawStreamCoverage(VerifiedStreamRange(1L, 1L), "hash-1"),
                recordCoverage =
                    DecisionRecordCoverage(
                        completeness = Completeness.COMPLETE,
                        accountedPositions = 1L,
                        unrecordedDecisionPositions = emptySet(),
                        unclassifiedPositions = emptySet(),
                        classificationRevision = "governance-classification.v1",
                    ),
                decision = "ALLOW",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("PRODUCER_DECLARED")

        // Decision completeness cannot be COMPLETE when rawCoverage.verifiedRange is null:
        assertThatThrownBy {
            GovernanceDecisionFact(
                kind = DecisionKind.POLICY_DECISION,
                fact =
                    GovernanceFactSummary(
                        authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
                        freshness = FactFreshness.HISTORICAL,
                        established = "recorded policy decision",
                        mustNotInfer = listOf("authorization is not execution"),
                        valuePresent = true,
                        sourceVersion = 1L,
                    ),
                rawCoverage = RawStreamCoverage(verifiedRange = null, observedHeadHash = null),
                recordCoverage =
                    DecisionRecordCoverage(
                        completeness = Completeness.COMPLETE,
                        accountedPositions = 0L,
                        unrecordedDecisionPositions = emptySet(),
                        unclassifiedPositions = emptySet(),
                        classificationRevision = "governance-classification.v1",
                    ),
                decision = "ALLOW",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("verified stream range")
    }

    @Test
    fun `public factory signature exposes only public parameter and return types`() {
        val companionMethods =
            GovernanceQueryService.Companion::class.java.declaredMethods
                .filter {
                    it.name == "jdbc" &&
                        java.lang.reflect.Modifier
                            .isPublic(it.modifiers)
                }
        val interfaceMethods =
            GovernanceQueryService::class.java.declaredMethods
                .filter {
                    it.name == "jdbc" &&
                        java.lang.reflect.Modifier
                            .isPublic(it.modifiers)
                }

        assertThat(companionMethods).hasSize(3)
        assertThat(interfaceMethods).hasSize(3)

        val expectedPublicParameterTypes =
            setOf(
                javax.sql.DataSource::class.java,
                dev.tramai.controlplane.WorkloadControlPlaneQueries::class.java,
                dev.tramai.security.audit.AuditStore::class.java,
            )

        for (method in companionMethods + interfaceMethods) {
            assertThat(
                java.lang.reflect.Modifier
                    .isPublic(method.returnType.modifiers),
            ).isTrue()
            assertThat(method.returnType).isEqualTo(GovernanceQueryService::class.java)
            assertThat(method.parameterTypes).isNotEmpty()
            for (paramType in method.parameterTypes) {
                assertThat(
                    java.lang.reflect.Modifier
                        .isPublic(paramType.modifiers),
                ).isTrue()
                assertThat(paramType).isIn(expectedPublicParameterTypes)
                assertThat(paramType.packageName).isNotEqualTo("dev.tramai.security.governance")
            }
        }
    }

    private fun sampleView(): GovernanceProjectionView =
        GovernanceProjectionView(
            identity = GovernanceProjectionIdentity("claims", "prod", "dep-1", "run-found"),
            formatVersion = "governance-read-model.v1",
            materializedAt = Instant.parse("2026-06-01T12:00:00Z"),
            provenance =
                GovernanceSourceProvenance(
                    registrationVersion = 2L,
                    auditHeadPosition = 4L,
                    auditHeadHash = "hash-4",
                    classificationRevision = "governance-classification.v1",
                    readConsistency = ReadConsistency.INDEPENDENT_SOURCES,
                ),
            currency =
                GovernanceCurrencyAssessment(
                    registration = SourceCurrency.CURRENT,
                    audit = SourceCurrency.UNKNOWN,
                    note = "per-source currency assessment",
                ),
            registration =
                GovernanceFactSummary(
                    authority = FactAuthority.AUTHORITATIVE_PERSISTED,
                    freshness = FactFreshness.CURRENT_AT_VERSION,
                    established = "registered at version 2",
                    mustNotInfer = listOf("registration is not execution"),
                    valuePresent = true,
                    observedVersion = 2L,
                ),
            declaredRunIdentity =
                GovernanceFactSummary(
                    authority = FactAuthority.PRODUCER_DECLARED,
                    freshness = FactFreshness.STALE_OR_UNKNOWN,
                    established = "declared run identity",
                    mustNotInfer = listOf("declared identity is not authenticated"),
                    valuePresent = true,
                ),
            decisions = listOf(samplePolicyDecision()),
            telemetry =
                GovernanceFactSummary(
                    authority = FactAuthority.TELEMETRY,
                    freshness = FactFreshness.STALE_OR_UNKNOWN,
                    established = "0 telemetry observations",
                    mustNotInfer = listOf("telemetry is not governance evidence"),
                    valuePresent = true,
                ),
        )

    private fun samplePolicyDecision(): GovernanceDecisionFact =
        GovernanceDecisionFact(
            kind = DecisionKind.POLICY_DECISION,
            fact =
                GovernanceFactSummary(
                    authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
                    freshness = FactFreshness.HISTORICAL,
                    established = "policy decision at position 4",
                    mustNotInfer = listOf("authorization is not execution"),
                    valuePresent = true,
                    sourceVersion = 4L,
                ),
            rawCoverage = RawStreamCoverage(VerifiedStreamRange(1L, 4L), "hash-4"),
            recordCoverage =
                DecisionRecordCoverage(
                    completeness = Completeness.GAPS_PRESENT,
                    accountedPositions = 4L,
                    unrecordedDecisionPositions = setOf(3L),
                    unclassifiedPositions = emptySet(),
                    classificationRevision = "governance-classification.v1",
                ),
            decision = "ALLOW",
            reasonCode = "policy_allowed",
            boundaryId = "BEFORE_PROVIDER_INVOCATION",
        )

    @Test
    fun `public factory queries a materialized snapshot with both coverage claims intact`() {
        val runId = "run-public-factory"
        withMigratedPostgres { dataSource ->
            val authority =
                dev.tramai.controlplane.WorkloadRegistrationAuthority(
                    dev.tramai.controlplane.InMemoryWorkloadRegistrationStore(),
                )
            val auditStore =
                dev.tramai.security.audit
                    .InMemoryAuditStore()
            publishSampleSnapshotForJavaConsumer(dataSource, authority, auditStore, runId)

            val queryService: GovernanceQueryService =
                GovernanceQueryService.jdbc(
                    dataSource = dataSource,
                    controlPlaneQueries = authority,
                    auditStore = auditStore,
                )

            val request = GovernanceQueryRequest("claims", "production", "eu-west-amsterdam-01", runId)
            val result = queryService.query(request)
            assertThat(result).isInstanceOf(GovernanceQueryResult.Found::class.java)
            val view = (result as GovernanceQueryResult.Found).view
            assertThat(view.identity).isEqualTo(
                GovernanceProjectionIdentity("claims", "production", "eu-west-amsterdam-01", runId),
            )
            assertThat(view.currency.registration).isEqualTo(SourceCurrency.CURRENT)
            assertThat(view.currency.audit).isEqualTo(SourceCurrency.CURRENT)

            val policy = view.decisionFor(DecisionKind.POLICY_DECISION)!!
            assertThat(policy.decision).isEqualTo("ALLOW")
            assertThat(policy.rawCoverage.verifiedRange).isEqualTo(VerifiedStreamRange(1L, 1L))
            assertThat(policy.rawCoverage.observedHeadHash).isNotBlank()
            assertThat(policy.recordCoverage.completeness).isEqualTo(Completeness.COMPLETE)
            assertThat(policy.recordCoverage.accountedPositions).isEqualTo(1L)
            assertThat(policy.recordCoverage.unrecordedDecisionPositions).isEmpty()
            assertThat(policy.recordCoverage.unclassifiedPositions).isEmpty()

            val storeOnlyResult = GovernanceQueryService.jdbc(dataSource).query(request)
            val storeOnlyView = (storeOnlyResult as GovernanceQueryResult.Found).view
            assertThat(storeOnlyView.currency.registration).isEqualTo(SourceCurrency.UNKNOWN)
            assertThat(storeOnlyView.currency.audit).isEqualTo(SourceCurrency.UNKNOWN)
        }
    }

    private fun withMigratedPostgres(block: (javax.sql.DataSource) -> Unit) {
        org.testcontainers.postgresql
            .PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("governance_query_consumer")
            .withUsername("test")
            .withPassword("test")
            .use { postgres ->
                postgres.start()
                val dataSource =
                    org.postgresql.ds.PGSimpleDataSource().apply {
                        setUrl(postgres.jdbcUrl)
                        user = postgres.username
                        password = postgres.password
                    }
                val sql =
                    this::class.java.classLoader
                        .getResource("tramai/security/governance/postgres/V1__governance_projection_read_model.sql")
                        ?.readText()
                        ?: error("Migration not found")
                dataSource.connection.use { conn -> conn.createStatement().use { it.execute(sql) } }
                block(dataSource)
            }
    }

    companion object {
        @JvmStatic
        fun publishSampleSnapshotForJavaConsumer(
            dataSource: javax.sql.DataSource,
            authority: dev.tramai.controlplane.WorkloadRegistrationAuthority,
            auditStore: dev.tramai.security.audit.AuditStore,
            runId: String,
        ): Unit =
            kotlinx.coroutines.runBlocking {
                val scope =
                    dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
                        .identity()
                authority.register(
                    scope,
                    dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
                        .fingerprint(),
                    dev.tramai.core.identity
                        .WorkloadMetadata(owner = "Claims Team", purpose = "Fraud review"),
                )
                val clock = java.time.Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), java.time.ZoneId.of("UTC"))
                val emitter =
                    dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter(
                        dev.tramai.security.audit
                            .AuditEngine(auditStore, clock = clock),
                    )
                val context =
                    dev.tramai.core.policy.PolicyContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                        workflowId = "claims",
                        workflowRunId = runId,
                        correlationId = "corr-java",
                        actorId = "system.anonymous",
                        providerId = "ollama",
                        modelName = "mistral",
                        toolName = null,
                        policyVersion = "v17",
                    )
                emitter.emit(
                    dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    context,
                    dev.tramai.core.policy.PolicyDecision.Allow,
                )
                val materializer =
                    dev.tramai.security.governance.GovernanceProjectionMaterializer(
                        dev.tramai.security.governance.GovernanceProjectionReader(
                            authority,
                            dev.tramai.security.governance
                                .AuditBackedGovernanceDecisionFactSource(auditStore),
                        ),
                        dev.tramai.security.governance
                            .JdbcGovernanceProjectionStore(dataSource),
                    )
                val outcome =
                    materializer.materialize(
                        dev.tramai.security.governance.ProjectionIdentity(
                            "claims",
                            "production",
                            "eu-west-amsterdam-01",
                            runId,
                        ),
                        scope,
                    )
                assertThat(outcome).isEqualTo(dev.tramai.security.governance.MaterializationOutcome.PUBLISHED)
            }
    }
}
