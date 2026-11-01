package dev.tramai.security.governance

import dev.tramai.controlplane.ClassifiedRead
import dev.tramai.controlplane.InMemoryWorkloadRegistrationStore
import dev.tramai.controlplane.WorkloadControlPlaneQueries
import dev.tramai.controlplane.WorkloadRegistrationAuthority
import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.identity.WorkloadMetadata
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditEnginePolicyDecisionAuditEmitter
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.security.governance.query.GovernanceQueryRequest
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.SourceCurrency
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import dev.tramai.security.governance.query.DecisionKind as PublicDecisionKind

/**
 * 0.7.4g — the fail-open scenario: what an *unavailable* authoritative source can and cannot become.
 *
 * Every existing discriminator in this module hands the projection a source that answers. The gap
 * this class closes is the other shape — a source that throws, or answers with nothing at all while a
 * persisted row already exists. The contract being audited is narrow and stated:
 *
 * - [StoreBackedGovernanceQueryService] models only four states. An unexpected infrastructure or
 *   observation failure is deliberately *not* one of them: it propagates as `IllegalStateException`
 *   (`GovernanceQueryContract.kt:26-28`, `:38-39`), so a failure can never be read as
 *   `NotMaterialized`, `Found`, or any partially assessed view.
 * - [StoredGovernanceProjection.assessAgainst] cannot claim more currency than the observations it was
 *   handed (`GovernanceProjectionReadModel.kt:105-137`): nothing observed means `UNKNOWN`, never
 *   `CURRENT`.
 * - A decision family's authority is assigned by this module's classification, never by the source
 *   (`AuditBackedGovernanceDecisionFactSource.kt:49-52`), so no failure can mint an `ALLOW`.
 *
 * Each test below asserts the outcome the production code actually produces — an identity of the
 * propagated throwable, or an exact enum value — plus the negatives: no `CURRENT`, no `COMPLETE`, no
 * decision value and no success claim derived from the failure.
 */
class GovernanceSourceUnavailabilityTest {
    private val scope = WorkloadRegistrationFixtures.identity()
    private val runId = "run-1"
    private val clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneId.of("UTC"))

    private fun identity() = ProjectionIdentity("claims", "production", "eu-west-amsterdam-01", runId)

    private fun request() =
        GovernanceQueryRequest(
            workloadId = "claims",
            environmentId = "production",
            deploymentId = "eu-west-amsterdam-01",
            workflowRunId = runId,
        )

    private suspend fun registeredAuthority(): WorkloadRegistrationAuthority {
        val authority = WorkloadRegistrationAuthority(InMemoryWorkloadRegistrationStore())
        authority.register(
            scope,
            WorkloadRegistrationFixtures.fingerprint(),
            WorkloadMetadata(owner = "Claims Team", purpose = "Fraud review"),
        )
        return authority
    }

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

    private suspend fun emitPolicy(
        auditStore: AuditStore,
        decision: PolicyDecision,
    ) {
        val emitter = AuditEnginePolicyDecisionAuditEmitter(AuditEngine(auditStore, clock = clock))
        emitter.emit(EnforcementPoint.BEFORE_PROVIDER_INVOCATION, context(), decision)
    }

    private fun materializer(
        authority: WorkloadControlPlaneQueries,
        auditStore: AuditStore,
        store: GovernanceProjectionStore,
    ) = GovernanceProjectionMaterializer(
        GovernanceProjectionReader(authority, AuditBackedGovernanceDecisionFactSource(auditStore)),
        store,
    )

    /** The throwable a call produced, or null when it produced one. Never swallows the failure mode. */
    private suspend fun failureOf(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (t: Throwable) {
            t
        }

    /** A real, valid persisted projection, over the real reader and fold, into an in-memory store. */
    private suspend fun storedProjection(): Pair<InMemoryProjectionStore, StoredGovernanceProjection> {
        val authority = registeredAuthority()
        val auditStore = InMemoryAuditStore()
        emitPolicy(auditStore, PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"))
        val store = InMemoryProjectionStore()
        assertThat(materializer(authority, auditStore, store).materialize(identity(), scope))
            .isEqualTo(MaterializationOutcome.PUBLISHED)
        return store to requireNotNull(store.load(identity()))
    }

    // ------------------------------------------- 1. registration source unavailable

    @Test
    fun `1 an unavailable registration authority propagates and establishes nothing`() =
        runBlocking<Unit> {
            val failure = IllegalStateException("registration authority unavailable")
            val auditStore = InMemoryAuditStore()
            emitPolicy(auditStore, PolicyDecision.Allow)
            val store = InMemoryProjectionStore()

            // Materialize path: the read fails before any publish can happen, so the failure cannot
            // become a PUBLISHED or ALREADY_CURRENT outcome for the row it never read.
            val materializeFailure =
                failureOf {
                    materializer(UnavailableControlPlaneQueries(failure), auditStore, store)
                        .materialize(identity(), scope)
                }
            assertThat(materializeFailure).isSameAs(failure)
            assertThat(store.publishAttempts).isZero()
            assertThat(store.load(identity())).isNull()

            // Query path, over a row that really is valid: the registration observation throws after
            // the load. The service has no result variant for this, so the *same* throwable leaves,
            // unchanged — no Found, no UNKNOWN currency, no quiet fallback to NotMaterialized.
            val (persisted, stored) = storedProjection()
            val service =
                StoreBackedGovernanceQueryService(
                    readModel = persisted.readOnly(),
                    controlPlaneQueries = UnavailableControlPlaneQueries(failure),
                )
            val queryFailure = failureOf { service.query(request()) }
            assertThat(queryFailure).isSameAs(failure)
            assertThat(queryFailure).isInstanceOf(IllegalStateException::class.java)
            assertThat(queryFailure).isNotInstanceOf(GovernanceQueryResult::class.java)

            // And the row it refused to assess is still exactly the row that was there: an
            // unavailable source observes nothing and changes nothing.
            assertThat(persisted.load(identity())?.snapshot?.semanticDigest)
                .isEqualTo(stored.snapshot.semanticDigest)
        }

    // ------------------------------------------- 2. audit / decision source unavailable

    @Test
    fun `2 an unavailable audit store propagates through the decision read and the audit-head observation`() =
        runBlocking<Unit> {
            val failure = IllegalStateException("audit store unavailable")
            val authority = registeredAuthority()

            // Materialize path: the decision read is the first thing the reader performs, and it
            // throws. Nothing is published, so no absence, completeness or decision value can be
            // derived from a stream that was never read.
            val store = InMemoryProjectionStore()
            val materializeFailure =
                failureOf {
                    materializer(authority, UnavailableAuditStore(failure), store)
                        .materialize(identity(), scope)
                }
            assertThat(materializeFailure).isSameAs(failure)
            assertThat(store.publishAttempts).isZero()
            assertThat(store.load(identity())).isNull()

            // Query path: registration was observed and would have been CURRENT, but the audit
            // observation throws. Reporting a half-assessed view (registration CURRENT, audit UNKNOWN)
            // would be the fail-open reading, so the whole query is refused instead.
            val (persisted, _) = storedProjection()
            val service =
                StoreBackedGovernanceQueryService(
                    readModel = persisted.readOnly(),
                    controlPlaneQueries = authority,
                    auditStore = UnavailableAuditStore(failure),
                )
            val queryFailure = failureOf { service.query(request()) }
            assertThat(queryFailure).isSameAs(failure)
            assertThat(queryFailure).isInstanceOf(IllegalStateException::class.java)
            assertThat(queryFailure).isNotInstanceOf(GovernanceQueryResult::class.java)
        }

    // ------------------------------------------- 3. infrastructure failure on publish / load

    @Test
    fun `3 an infrastructure failure during publish and load propagates as the sanitized store failure`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            val auditStore = InMemoryAuditStore()
            emitPolicy(auditStore, PolicyDecision.Allow)

            // Materialize path: the sources answer, the store cannot. The JDBC boundary reports it as
            // the module's sanitized IllegalStateException rather than as a materialization outcome:
            // a failed publish is not PUBLISHED, and it is emphatically not ALREADY_CURRENT.
            val materializeFailure =
                failureOf {
                    materializer(authority, auditStore, JdbcGovernanceProjectionStore(UnavailableDataSource()))
                        .materialize(identity(), scope)
                }
            assertThat(materializeFailure).isInstanceOf(GovernanceProjectionStoreException::class.java)
            assertThat(materializeFailure).isInstanceOf(IllegalStateException::class.java)
            assertThat(materializeFailure).isNotInstanceOf(MaterializationOutcome::class.java)
            assertThat(materializeFailure)
                .hasMessage("the governance projection store operation failed")
            assertThat(materializeFailure!!.message).doesNotContain("injected")
            assertThat(materializeFailure.cause).isInstanceOf(SQLException::class.java)

            // Query path: a store that cannot be read is not an unmaterialized scope. The documented
            // contract is propagation, so `NotMaterialized` — the state a consumer reads as "nothing
            // was ever published here" — must not be reachable from a transport failure.
            val queryService = StoreBackedGovernanceQueryService(JdbcGovernanceProjectionStore(UnavailableDataSource()))
            val queryFailure = failureOf { queryService.query(request()) }
            assertThat(queryFailure).isInstanceOf(GovernanceProjectionStoreException::class.java)
            assertThat(queryFailure)
                .hasMessage("the governance projection store operation failed")
            assertThat(queryFailure).isNotInstanceOf(GovernanceQueryResult::class.java)
        }

    // ------------------------------------------- 4. no facts from the source, a valid row persists

    @Test
    fun `4 a source that returns no facts does not promote a stored projection to CURRENT`() =
        runBlocking<Unit> {
            val authority = registeredAuthority()
            val auditStore = InMemoryAuditStore()
            emitPolicy(auditStore, PolicyDecision.Deny(reason = "blocked", reasonCode = "policy_denied"))
            val store = InMemoryProjectionStore()
            assertThat(materializer(authority, auditStore, store).materialize(identity(), scope))
                .isEqualTo(MaterializationOutcome.PUBLISHED)
            val stored = requireNotNull(store.load(identity()))

            // Both sources now answer, and both answer with nothing: an unregistered scope and a
            // stream with no events. The stored row still exists and is still served, so the only
            // question left is whether it is allowed to look current.
            val emptyAuthority = WorkloadRegistrationAuthority(InMemoryWorkloadRegistrationStore())
            val service =
                StoreBackedGovernanceQueryService(
                    readModel = store.readOnly(),
                    controlPlaneQueries = emptyAuthority,
                    auditStore = InMemoryAuditStore(),
                )

            val result = service.query(request())
            assertThat(result).isInstanceOf(GovernanceQueryResult.Found::class.java)
            val view = (result as GovernanceQueryResult.Found).view

            // No CURRENT: a row that exists is not evidence that it is current.
            assertThat(view.currency.registration).isEqualTo(SourceCurrency.UNKNOWN)
            assertThat(view.currency.registration).isNotEqualTo(SourceCurrency.CURRENT)
            assertThat(view.currency.audit).isEqualTo(SourceCurrency.UNKNOWN)
            assertThat(view.currency.audit).isNotEqualTo(SourceCurrency.CURRENT)
            assertThat(view.currency.note).contains("a stored row never declares itself current")

            // No COMPLETE, and no coverage, manufactured by an empty read: the record claim and the
            // verified range are exactly the stored ones, unchanged in either direction.
            val policy = requireNotNull(view.decisionFor(PublicDecisionKind.POLICY_DECISION))
            assertThat(policy.recordCoverage.completeness.name)
                .isEqualTo(stored.completenessFor(DecisionKind.POLICY_DECISION).name)
            assertThat(policy.rawCoverage.verifiedRange?.firstPosition)
                .isEqualTo(stored.auditCoverageFor(DecisionKind.POLICY_DECISION)?.verifiedRange?.first)
            assertThat(policy.rawCoverage.verifiedRange?.lastPosition)
                .isEqualTo(stored.auditCoverageFor(DecisionKind.POLICY_DECISION)?.verifiedRange?.last)
            assertThat(view.decisions).hasSameSizeAs(stored.snapshot.decisions)

            // No ALLOW: the only decision token in the view is the one the stored record carried. An
            // empty source cannot supply one, and the query does not invent a success from absence.
            // (Absent families carry no token, which is the documented shape — not an empty string.)
            assertThat(policy.decision).isEqualTo("DENY")
            assertThat(view.decisions.mapNotNull { it.decision }).containsExactly("DENY")
            assertThat(view.decisions.mapNotNull { it.decision }).doesNotContain("ALLOW")

            // The stored provenance is reported as recorded, not refreshed by the empty observation.
            assertThat(view.provenance.registrationVersion)
                .isEqualTo(stored.snapshot.provenance.registrationVersion)
            assertThat(view.provenance.auditHeadPosition)
                .isEqualTo(stored.snapshot.provenance.auditHeadPosition)
        }
}

// ------------------------------------------- fakes

/** A registration read port that is down: every read is the same infrastructure failure. */
private class UnavailableControlPlaneQueries(
    private val failure: RuntimeException,
) : WorkloadControlPlaneQueries {
    override suspend fun authoritative(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
    ): ClassifiedRead? = throw failure

    override suspend fun projection(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
    ): ClassifiedRead? = throw failure
}

/** An audit store that is down, on every operation the projection or query path can reach. */
private class UnavailableAuditStore(
    private val failure: RuntimeException,
) : AuditStore {
    override suspend fun appendNext(
        auditStreamId: String,
        eventFactory: (latest: AuditEvent?) -> AuditEvent,
    ): AuditEvent = throw failure

    override suspend fun readStream(auditStreamId: String): List<AuditEvent> = throw failure

    override suspend fun latestEvent(auditStreamId: String): AuditEvent? = throw failure
}

/** A datasource whose connections cannot be opened, as a real backend outage looks to the store. */
private class UnavailableDataSource : DataSource {
    private fun unavailable(): Nothing = throw SQLException("injected connection failure: injected database detail")

    override fun getConnection(): Connection = unavailable()

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = unavailable()

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): Logger = Logger.getGlobal()

    override fun <T : Any?> unwrap(iface: Class<T>): T = unavailable()

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}

/**
 * A store that holds one snapshot per identity, with the same publish ordering rule the durable
 * store applies: identical content is already current, different content replaces it.
 */
private class InMemoryProjectionStore : GovernanceProjectionStore {
    private val snapshots = mutableMapOf<ProjectionIdentity, StoredGovernanceProjection>()

    var publishAttempts: Int = 0
        private set

    override suspend fun load(identity: ProjectionIdentity): StoredGovernanceProjection? = snapshots[identity]

    override suspend fun publish(snapshot: GovernanceProjectionSnapshot): MaterializationOutcome {
        publishAttempts++
        val existing = snapshots[snapshot.identity]
        if (existing != null && existing.snapshot.semanticDigest == snapshot.semanticDigest) {
            return MaterializationOutcome.ALREADY_CURRENT
        }
        snapshots[snapshot.identity] = StoredGovernanceProjection(snapshot, MATERIALIZED_AT)
        return MaterializationOutcome.PUBLISHED
    }

    private companion object {
        val MATERIALIZED_AT: Instant = Instant.parse("2026-06-01T12:00:00Z")
    }
}
