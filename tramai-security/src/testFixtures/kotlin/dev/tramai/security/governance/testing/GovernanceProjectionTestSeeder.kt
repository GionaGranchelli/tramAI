package dev.tramai.security.governance.testing

import dev.tramai.controlplane.InMemoryWorkloadRegistrationStore
import dev.tramai.controlplane.WorkloadRegistrationAuthority
import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import dev.tramai.core.identity.WorkloadMetadata
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.governance.AuditBackedGovernanceDecisionFactSource
import dev.tramai.security.governance.DeclaredRunIdentity
import dev.tramai.security.governance.GovernanceProjectionMaterializer
import dev.tramai.security.governance.GovernanceProjectionReader
import dev.tramai.security.governance.JdbcGovernanceProjectionStore
import dev.tramai.security.governance.MaterializationOutcome
import dev.tramai.security.governance.ProjectionIdentity
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.sql.DataSource

/**
 * Test fixture that persists a real governance projection for another module's integration test.
 *
 * The projection writer, its digest canonicalisation and the audit-backed decision source are internal to
 * `:tramai-security`, and `:tramai-observability` cannot depend on `:tramai-security`'s test source set. This
 * fixture runs the real writer against a caller-supplied [DataSource], so a downstream test reads a projection
 * through the public query port exactly as production would, rather than a hand-inserted row whose digest the
 * store would refuse.
 *
 * It is test-only and not published.
 */
public object GovernanceProjectionTestSeeder {
    public const val WORKLOAD_ID: String = "claims"
    public const val ENVIRONMENT_ID: String = "production"
    public const val DEPLOYMENT_ID: String = "eu-west-amsterdam-01"
    public const val WORKFLOW_RUN_ID: String = "run-1"
    public const val REASON_CODE: String = "policy_allowed"

    private val scope = WorkloadRegistrationFixtures.identity()
    private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))

    /**
     * Registers the workload, emits one denied policy decision into an in-memory audit stream, and materializes
     * the projection into [dataSource]. The schema must already exist.
     */
    public suspend fun seedDeniedPolicyDecision(dataSource: DataSource) {
        val auditStore = InMemoryAuditStore()
        val emitter = AuditEnginePolicyDecisionAuditEmitter(AuditEngine(auditStore, clock = clock))

        val authority = WorkloadRegistrationAuthority(InMemoryWorkloadRegistrationStore())
        authority.register(
            scope,
            WorkloadRegistrationFixtures.fingerprint(),
            WorkloadMetadata(owner = "Claims Team", purpose = "Fraud review"),
        )

        emitter.emit(
            EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
            PolicyContext(
                enforcementPoint = EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                workflowId = WORKLOAD_ID,
                workflowRunId = WORKFLOW_RUN_ID,
                correlationId = "corr-1",
                actorId = "system.anonymous",
                providerId = "ollama",
                modelName = "mistral",
                toolName = null,
                policyVersion = "v17",
            ),
            PolicyDecision.Deny(reason = "blocked", reasonCode = REASON_CODE),
        )

        val materializer =
            GovernanceProjectionMaterializer(
                GovernanceProjectionReader(authority, AuditBackedGovernanceDecisionFactSource(auditStore)),
                JdbcGovernanceProjectionStore(dataSource),
            )

        materializer.materialize(
            ProjectionIdentity(WORKLOAD_ID, ENVIRONMENT_ID, DEPLOYMENT_ID, WORKFLOW_RUN_ID),
            scope,
            DeclaredRunIdentity(
                workloadId = WORKLOAD_ID,
                configurationId = "cfg-1",
                configurationVersion = "v1",
                environmentId = ENVIRONMENT_ID,
                deploymentId = DEPLOYMENT_ID,
                workflowRunId = WORKFLOW_RUN_ID,
            ),
        )
    }
}
