package dev.tramai.security.governance

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource
import kotlin.test.Test

/**
 * 0.7.4d — the cross-process half of "persistable across process restart".
 *
 * The suite's other durability test loads a snapshot through a store instance that did not write it, in
 * the same JVM. That is a weaker claim than the task makes: the proof needs a database that outlives the
 * process that wrote to it, and a container started by a test dies with the test JVM that started it.
 *
 * So this is an opt-in harness. `scripts/qualify-cross-process-restart.sh` creates a PostgreSQL database
 * for the run, starts it on a loopback port Docker chooses, and runs this class once per phase in two
 * separate JVMs, configuring both through environment variables. Nothing secret is passed as a JVM
 * argument, and nothing about the configuration is read from a file the reader could reconstruct: the
 * only handoff between the processes is the writer's process id, the digest of what it published, and
 * the timestamp of that write. The database is fresh for each run, so the writer deletes nothing. The reader opens the
 * row the writer left, rebuilds it through the store's own load path, and asserts all three match — it
 * never migrates, never clears and never publishes.
 *
 * When the properties are absent, both tests are skipped, which is what happens in CI: an opt-in
 * qualification that says so rather than silently qualifying nothing.
 */
class GovernanceProjectionRestartQualificationTest {
    // The caller owns the handoff path: it lives in the run's temporary directory, not at a fixed
    // location a previous run could have left something in.
    private val handoff = Path.of(property("GOV_RESTART_HANDOFF") ?: HANDOFF)

    @Test
    fun `publish phase records the snapshot a later process must find`() =
        runBlocking<Unit> {
            assumeTrue(property("GOV_RESTART_PHASE") == PUBLISH, "publish phase only")
            val store = JdbcGovernanceProjectionStore(schemaApplied(dataSource()))
            val snapshot = snapshot()
            assertThat(store.publish(snapshot)).isEqualTo(MaterializationOutcome.PUBLISHED)

            val stored = store.load(identity())
            val committed = stored ?: error("the publish did not leave a readable row")
            assertThat(committed.materializedAt).isNotNull()
            Files.writeString(
                handoff,
                listOf(
                    "pid=${ProcessHandle.current().pid()}",
                    "digest=${snapshot.semanticDigest}",
                    "materializedAt=${committed.materializedAt}",
                ).joinToString("\n") + "\n",
            )
        }

    @Test
    fun `load phase rebuilds the row another process wrote, without writing anything`() =
        runBlocking<Unit> {
            assumeTrue(property("GOV_RESTART_PHASE") == LOAD, "load phase only")
            assumeTrue(Files.exists(handoff), "the publish phase must have run in another process")
            val written = handoffProperties()

            // No migration, no clear, no publish: this process only reads what is already there.
            val store = JdbcGovernanceProjectionStore(dataSource())
            val stored = store.load(identity()) ?: error("no snapshot: the publish phase left nothing to read")
            val snapshot = stored.snapshot

            // Separate processes: the writer's id is not this JVM's, and the row is the writer's row.
            val writer = written["pid"]?.toLongOrNull()
            assertThat(writer).describedAs("the writer recorded its own process id").isNotNull()
            assertThat(writer).isNotEqualTo(ProcessHandle.current().pid())
            assertThat(snapshot.semanticDigest).isEqualTo(written["digest"])
            assertThat(stored.materializedAt.toString()).isEqualTo(written["materializedAt"])

            // Identity and provenance survive the process boundary.
            assertThat(snapshot.identity.key).isEqualTo(identity().key)
            assertThat(snapshot.provenance.auditHeadPosition).isEqualTo(HEAD_POSITION)
            assertThat(snapshot.provenance.auditHeadHash).isEqualTo(HEAD_HASH)
            assertThat(snapshot.provenance.registrationVersion).isEqualTo(REGISTRATION_VERSION)

            // Both coverage claims, independently: raw range and decision-record completeness.
            val decision = snapshot.decisions.single()
            assertThat(decision.coverage.verifiedRange).isEqualTo(1L..HEAD_POSITION)
            assertThat(decision.coverage.observedHeadHash).isEqualTo(HEAD_HASH)
            assertThat(decision.coverage.unclassifiedPositions).containsExactly(UNCLASSIFIED_POSITION)
            assertThat(decision.coverage.unrecordedDecisionPositions).containsExactly(UNRECORDED_POSITION)
            assertThat(decision.completeness).isEqualTo(Completeness.GAPS_PRESENT)
        }

    private fun handoffProperties(): Map<String, String> =
        Files
            .readAllLines(handoff)
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
            }.toMap()

    private fun property(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun dataSource(): DataSource {
        val url = property("GOV_RESTART_URL")
        assumeTrue(!url.isNullOrBlank(), "the qualification must name the database it reads")
        return PGSimpleDataSource().apply {
            setUrl(url)
            user = property("GOV_RESTART_USER")
            password = property("GOV_RESTART_PASSWORD")
        }
    }

    private fun schemaApplied(source: DataSource): DataSource {
        val migration = "V1__governance_projection_read_model.sql"
        val sql =
            this::class.java.classLoader
                .getResource("tramai/security/governance/postgres/$migration")
                ?.readText()
                ?: error("Migration not found: $migration")
        source.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
        return source
    }

    private fun identity() = ProjectionIdentity(WORKLOAD, ENVIRONMENT, DEPLOYMENT, WORKFLOW_RUN_ID)

    private fun snapshot(): GovernanceProjectionSnapshot =
        GovernanceProjectionSnapshot(
            formatVersion = GOVERNANCE_READ_MODEL_FORMAT_VERSION,
            identity = identity(),
            registration = registrationFact(),
            declaredRunIdentity = declaredFact(),
            telemetry = telemetryFact(),
            decisions =
                listOf(
                    SnapshotDecision(
                        kind = DecisionKind.POLICY_DECISION,
                        completeness = Completeness.GAPS_PRESENT,
                        coverage =
                            SnapshotCoverage(
                                verifiedRange = 1L..HEAD_POSITION,
                                accountedPositions = HEAD_POSITION,
                                unrecordedDecisionPositions = setOf(UNRECORDED_POSITION),
                                unclassifiedPositions = setOf(UNCLASSIFIED_POSITION),
                                observedHeadHash = HEAD_HASH,
                                classificationRevision = GOVERNANCE_CLASSIFICATION_REVISION,
                            ),
                        latest = decisionFact(),
                        latestDecision = "ALLOW",
                        latestReasonCode = "policy_allowed",
                        latestBoundaryId = "BEFORE_PROVIDER_INVOCATION",
                        latestBoundaryStrength = null,
                        duplicatesIgnored = 0,
                        staleIgnored = 0,
                    ),
                ),
            provenance =
                SourceProvenance(
                    registrationVersion = REGISTRATION_VERSION,
                    auditHeadPosition = HEAD_POSITION,
                    auditHeadHash = HEAD_HASH,
                    classificationRevision = GOVERNANCE_CLASSIFICATION_REVISION,
                ),
        )

    private fun registrationFact() =
        SnapshotFact(
            authority = FactAuthority.AUTHORITATIVE_PERSISTED,
            freshness = FactFreshness.CURRENT_AT_VERSION,
            established = "the registration authority reports this workload at the version named",
            mustNotInfer = listOf("registration is not a governance decision"),
            valuePresent = true,
            observedVersion = REGISTRATION_VERSION,
        )

    private fun declaredFact() =
        SnapshotFact(
            authority = FactAuthority.PRODUCER_DECLARED,
            freshness = FactFreshness.HISTORICAL,
            established = "a producer declared this identity for the run; it is not authenticated",
            mustNotInfer = listOf("declared identity is not authenticated identity"),
            valuePresent = true,
            sourceVersion = REGISTRATION_VERSION,
        )

    private fun telemetryFact() =
        SnapshotFact(
            authority = FactAuthority.TELEMETRY,
            freshness = FactFreshness.HISTORICAL,
            established = "an audit stream is evidence, not telemetry; the read reported no observations",
            mustNotInfer = listOf("a non-authoritative observation cannot establish a governance fact"),
            valuePresent = true,
            sourceVersion = HEAD_POSITION,
        )

    private fun decisionFact() =
        SnapshotFact(
            authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
            freshness = FactFreshness.HISTORICAL,
            established = "a recorded policy decision at a source position",
            mustNotInfer = listOf("a policy ALLOW is authorization, not completion"),
            valuePresent = true,
            sourceVersion = HEAD_POSITION,
        )

    private companion object {
        const val HANDOFF = "/tmp/tramai-gov-restart-handoff.properties"
        const val PUBLISH = "publish"
        const val LOAD = "load"
        const val WORKLOAD = "claims"
        const val ENVIRONMENT = "production"
        const val DEPLOYMENT = "eu-west-amsterdam-01"
        const val WORKFLOW_RUN_ID = "run-1"
        const val HEAD_POSITION = 5L
        const val HEAD_HASH = "hash-5"
        const val REGISTRATION_VERSION = 1L
        const val UNRECORDED_POSITION = 4L
        const val UNCLASSIFIED_POSITION = 2L
    }
}
