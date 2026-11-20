package dev.tramai.security.governance.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tramai.security.governance.query.Completeness;
import dev.tramai.security.governance.query.DecisionKind;
import dev.tramai.security.governance.query.DecisionRecordCoverage;
import dev.tramai.security.governance.query.FactAuthority;
import dev.tramai.security.governance.query.FactFreshness;
import dev.tramai.security.governance.query.GovernanceCurrencyAssessment;
import dev.tramai.security.governance.query.GovernanceDecisionFact;
import dev.tramai.security.governance.query.GovernanceFactSummary;
import dev.tramai.security.governance.query.GovernanceProjectionIdentity;
import dev.tramai.security.governance.query.GovernanceProjectionView;
import dev.tramai.security.governance.query.ReasonCodeExposure;
import dev.tramai.security.governance.query.GovernanceQueryRequest;
import dev.tramai.security.governance.query.GovernanceQueryResult;
import dev.tramai.security.governance.query.GovernanceQueryService;
import dev.tramai.security.governance.query.GovernanceSourceProvenance;
import dev.tramai.security.governance.query.RawStreamCoverage;
import dev.tramai.security.governance.query.ReadConsistency;
import dev.tramai.security.governance.query.SourceCurrency;
import dev.tramai.security.governance.query.VerifiedStreamRange;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 0.7.4e — Java consumer compatibility test exercising the public governance query API from Java
 * without Kotlin-only signature artifacts.
 */
class JavaGovernanceQueryConsumerTest {

    @Test
    void javaConsumerCanConstructRequestInvokeServiceAndInspectTypedResult() {
        GovernanceQueryRequest scopeOnlyRequest =
                new GovernanceQueryRequest("claims", "production", "eu-west-amsterdam-01");
        assertThat(scopeOnlyRequest.getWorkloadId()).isEqualTo("claims");
        assertThat(scopeOnlyRequest.getWorkflowRunId()).isNull();

        GovernanceQueryRequest runScopedRequest =
                new GovernanceQueryRequest("claims", "production", "eu-west-amsterdam-01", "run-1");
        assertThat(runScopedRequest.getWorkflowRunId()).isEqualTo("run-1");

        GovernanceProjectionView view = createSampleView();
        GovernanceQueryService service =
                request -> {
                    if ("run-1".equals(request.getWorkflowRunId())) {
                        return new GovernanceQueryResult.Found(view);
                    }
                    if ("run-unsupported".equals(request.getWorkflowRunId())) {
                        return new GovernanceQueryResult.UnsupportedFormat("unsupported format");
                    }
                    if ("run-invalid".equals(request.getWorkflowRunId())) {
                        return new GovernanceQueryResult.InvalidStoredProjection("corrupt digest");
                    }
                    return GovernanceQueryResult.NotMaterialized.INSTANCE;
                };

        GovernanceQueryResult notMaterialized = service.query(scopeOnlyRequest);
        assertThat(notMaterialized).isSameAs(GovernanceQueryResult.NotMaterialized.INSTANCE);

        GovernanceQueryResult foundResult = service.query(runScopedRequest);
        assertThat(foundResult).isInstanceOf(GovernanceQueryResult.Found.class);

        GovernanceProjectionView returnedView =
                ((GovernanceQueryResult.Found) foundResult).getView();
        assertThat(returnedView.getIdentity().getWorkloadId()).isEqualTo("claims");
        assertThat(returnedView.getProvenance().getReadConsistency())
                .isEqualTo(ReadConsistency.INDEPENDENT_SOURCES);
        assertThat(returnedView.getCurrency().getRegistration()).isEqualTo(SourceCurrency.CURRENT);
        assertThat(returnedView.getCurrency().getAudit()).isEqualTo(SourceCurrency.UNKNOWN);

        GovernanceDecisionFact policyDecision =
                returnedView.decisionFor(DecisionKind.POLICY_DECISION);
        assertThat(policyDecision).isNotNull();
        assertThat(policyDecision.getDecision()).isEqualTo("ALLOW");
        assertThat(policyDecision.getRawCoverage().getVerifiedRange())
                .isEqualTo(new VerifiedStreamRange(1L, 3L));
        assertThat(policyDecision.getRawCoverage().getVerifiedRange().getSpan()).isEqualTo(3L);
        assertThat(policyDecision.getRawCoverage().getObservedHeadHash()).isEqualTo("hash-3");
        assertThat(policyDecision.getRecordCoverage().getCompleteness())
                .isEqualTo(Completeness.COMPLETE);
        assertThat(policyDecision.getRecordCoverage().getAccountedPositions()).isEqualTo(3L);

        assertThatThrownBy(() -> new GovernanceQueryRequest(" ", "production", "dep-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void javaConsumerCanObtainRealServiceFromPublicJdbcFactoryAndQuery() throws Exception {
        try (org.testcontainers.postgresql.PostgreSQLContainer postgres =
                new org.testcontainers.postgresql.PostgreSQLContainer("postgres:17-alpine")
                        .withDatabaseName("java_governance_query")
                        .withUsername("test")
                        .withPassword("test")) {
            postgres.start();
            org.postgresql.ds.PGSimpleDataSource dataSource =
                    new org.postgresql.ds.PGSimpleDataSource();
            dataSource.setUrl(postgres.getJdbcUrl());
            dataSource.setUser(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());

            String sql =
                    new String(
                            java.util.Objects.requireNonNull(
                                            JavaGovernanceQueryConsumerTest.class
                                                    .getClassLoader()
                                                    .getResourceAsStream(
                                                            "tramai/security/governance/postgres/V1__governance_projection_read_model.sql"))
                                    .readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            try (java.sql.Connection connection = dataSource.getConnection();
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }

            dev.tramai.controlplane.WorkloadRegistrationAuthority authority =
                    new dev.tramai.controlplane.WorkloadRegistrationAuthority(
                            new dev.tramai.controlplane.InMemoryWorkloadRegistrationStore());
            dev.tramai.security.audit.InMemoryAuditStore auditStore =
                    new dev.tramai.security.audit.InMemoryAuditStore();

            GovernanceQueryService storeOnlyService = GovernanceQueryService.jdbc(dataSource);
            GovernanceQueryService withRegistrationService =
                    GovernanceQueryService.jdbc(dataSource, authority);
            GovernanceQueryService fullService =
                    GovernanceQueryService.jdbc(dataSource, authority, auditStore);

            GovernanceQueryRequest request =
                    new GovernanceQueryRequest(
                            "claims", "production", "eu-west-amsterdam-01", "run-java");
            assertThat(storeOnlyService.query(request))
                    .isSameAs(GovernanceQueryResult.NotMaterialized.INSTANCE);

            GovernanceQueryKotlinConsumerTest.publishSampleSnapshotForJavaConsumer(
                    dataSource, authority, auditStore, "run-java");

            GovernanceQueryResult foundFromFull = fullService.query(request);
            assertThat(foundFromFull).isInstanceOf(GovernanceQueryResult.Found.class);
            GovernanceProjectionView fullView =
                    ((GovernanceQueryResult.Found) foundFromFull).getView();
            assertThat(fullView.getIdentity().getWorkflowRunId()).isEqualTo("run-java");
            assertThat(fullView.getCurrency().getRegistration()).isEqualTo(SourceCurrency.CURRENT);
            assertThat(fullView.getCurrency().getAudit()).isEqualTo(SourceCurrency.CURRENT);
            GovernanceDecisionFact policy = fullView.decisionFor(DecisionKind.POLICY_DECISION);
            assertThat(policy).isNotNull();
            assertThat(policy.getDecision()).isEqualTo("ALLOW");
            assertThat(policy.getRawCoverage().getVerifiedRange())
                    .isEqualTo(new VerifiedStreamRange(1L, 1L));
            assertThat(policy.getRecordCoverage().getCompleteness())
                    .isEqualTo(Completeness.COMPLETE);

            GovernanceQueryResult foundFromStoreOnly = storeOnlyService.query(request);
            GovernanceProjectionView storeOnlyView =
                    ((GovernanceQueryResult.Found) foundFromStoreOnly).getView();
            assertThat(storeOnlyView.getCurrency().getRegistration())
                    .isEqualTo(SourceCurrency.UNKNOWN);
            assertThat(storeOnlyView.getCurrency().getAudit()).isEqualTo(SourceCurrency.UNKNOWN);

            GovernanceQueryResult foundWithRegistration = withRegistrationService.query(request);
            GovernanceProjectionView withRegistrationView =
                    ((GovernanceQueryResult.Found) foundWithRegistration).getView();
            assertThat(withRegistrationView.getCurrency().getRegistration())
                    .isEqualTo(SourceCurrency.CURRENT);
            assertThat(withRegistrationView.getCurrency().getAudit())
                    .isEqualTo(SourceCurrency.UNKNOWN);
        }
    }

    private static GovernanceProjectionView createSampleView() {
        GovernanceProjectionIdentity identity =
                new GovernanceProjectionIdentity(
                        "claims", "production", "eu-west-amsterdam-01", "run-1");
        GovernanceSourceProvenance provenance =
                new GovernanceSourceProvenance(
                        1L, 3L, "hash-3", "governance-classification.v1");
        GovernanceCurrencyAssessment currency =
                new GovernanceCurrencyAssessment(
                        SourceCurrency.CURRENT,
                        SourceCurrency.UNKNOWN,
                        "registration observed; audit not freshly observed");
        GovernanceFactSummary registrationFact =
                new GovernanceFactSummary(
                        FactAuthority.AUTHORITATIVE_PERSISTED,
                        FactFreshness.CURRENT_AT_VERSION,
                        "registered at version 1",
                        List.of("registration is not execution evidence"),
                        true,
                        1L,
                        null,
                        null);
        GovernanceFactSummary declaredFact =
                new GovernanceFactSummary(
                        FactAuthority.NOT_PRODUCED,
                        FactFreshness.ABSENT,
                        "no declared run identity",
                        List.of("missing declaration is not a negative governance fact"),
                        false);
        GovernanceFactSummary decisionFactSummary =
                new GovernanceFactSummary(
                        FactAuthority.POLICY_AUTHORIZATION_RECORD,
                        FactFreshness.HISTORICAL,
                        "policy decision at position 3",
                        List.of("authorization is not execution"),
                        true,
                        null,
                        3L,
                        null);
        GovernanceDecisionFact policyDecision =
                new GovernanceDecisionFact(
                        DecisionKind.POLICY_DECISION,
                        decisionFactSummary,
                        new RawStreamCoverage(new VerifiedStreamRange(1L, 3L), "hash-3"),
                        new DecisionRecordCoverage(
                                Completeness.COMPLETE,
                                3L,
                                Collections.emptySet(),
                                Set.of(),
                                "governance-classification.v1"),
                        "ALLOW",
                        "policy_allowed",
                        "BEFORE_PROVIDER_INVOCATION",
                        null,
                        0,
                        0,
                        ReasonCodeExposure.EXPOSED);
        GovernanceFactSummary telemetryFact =
                new GovernanceFactSummary(
                        FactAuthority.TELEMETRY,
                        FactFreshness.STALE_OR_UNKNOWN,
                        "0 telemetry observations",
                        List.of("telemetry is not a governance fact"),
                        true);
        return new GovernanceProjectionView(
                identity,
                "governance-read-model.v1",
                Instant.parse("2026-06-01T12:00:00Z"),
                provenance,
                currency,
                registrationFact,
                declaredFact,
                List.of(policyDecision),
                telemetryFact);
    }
}
