package dev.tramai.controlplane

import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import dev.tramai.controlplane.testing.runInParallel
import dev.tramai.core.identity.WorkloadMetadata
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The public control-plane authority contract (0.7.1e).
 *
 * These tests exercise the authority through the DECLARED PORT TYPES
 * ([WorkloadControlPlaneCommands], [WorkloadControlPlaneQueries]) rather than the concrete class,
 * because the port is the deliverable: the guarantees must be reachable by a transport adapter
 * that only ever sees the contract.
 *
 * The authority's own rules are pinned in [WorkloadRegistrationAuthorityTest]; these tests pin the
 * contract around them — version-exact stale reporting through the port, read classification,
 * projection lag never authorizing a command, and the structural claim that a read port cannot
 * mutate and a read value cannot be used as a mutation witness.
 */
class WorkloadControlPlaneContractTest {
    private lateinit var store: InMemoryWorkloadRegistrationStore
    private lateinit var commands: WorkloadControlPlaneCommands
    private lateinit var queries: WorkloadControlPlaneQueries

    private val identity = WorkloadRegistrationFixtures.identity(deployment = "contract-deployment")
    private val fingerprint = WorkloadRegistrationFixtures.fingerprint()
    private val metadata = WorkloadRegistrationFixtures.metadata()

    @BeforeEach
    fun setUp() {
        store = InMemoryWorkloadRegistrationStore()
        // One authority object, two contracts: no second copy of the state, no forwarding wrapper.
        val authority = WorkloadRegistrationAuthority(store)
        commands = authority
        queries = authority
    }

    // ── Command contract ────────────────────────────────────────────

    @Test
    fun `concurrent commands on the same expected version yield exactly one winner and a truthful stale loser`() =
        runBlocking<Unit> {
            // Reach the exact shape the contract cares about: authoritative version 7.
            val version7 = advanceTo(7)
            assertThat(version7).isEqualTo(WorkloadStateVersion(7))

            val outcomes =
                runInParallel(
                    {
                        commands.updateMetadata(
                            identity.workloadId,
                            identity.environmentId,
                            identity.deploymentId,
                            version7,
                            WorkloadMetadata(owner = "Writer A", purpose = "a"),
                        )
                    },
                    {
                        commands.updateMetadata(
                            identity.workloadId,
                            identity.environmentId,
                            identity.deploymentId,
                            version7,
                            WorkloadMetadata(owner = "Writer B", purpose = "b"),
                        )
                    },
                )

            // Not merely "one failed": exactly one Applied at v8, and one Stale that reports the
            // winner's version as current rather than the version it read before the race.
            val applied = outcomes.filterIsInstance<MetadataUpdateOutcome.Applied>().single()
            assertThat(applied.exposure.stateVersion).isEqualTo(WorkloadStateVersion(8))

            val stale = outcomes.filterIsInstance<MetadataUpdateOutcome.Stale>().single()
            assertThat(stale.expectedVersion).isEqualTo(WorkloadStateVersion(7))
            assertThat(stale.currentVersion).isEqualTo(WorkloadStateVersion(8))

            val settled = store.find(identity.workloadId, identity.environmentId, identity.deploymentId)
            assertThat(settled?.stateVersion).isEqualTo(WorkloadStateVersion(8))
        }

    @Test
    fun `RETIRED is terminal through the command port and stays distinct from a stale expectation`() =
        runBlocking<Unit> {
            commands.register(identity, fingerprint, metadata)
            val retired =
                commands.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion.INITIAL,
                    WorkloadLifecycleState.RETIRED,
                )
            assertThat(retired).isInstanceOf(LifecycleTransitionOutcome.Applied::class.java)
            val version = (retired as LifecycleTransitionOutcome.Applied).exposure.stateVersion

            // Current version, illegal target -> domain conflict, NOT a precondition failure.
            val illegal =
                commands.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    version,
                    WorkloadLifecycleState.ACTIVE,
                )
            assertThat(illegal).isInstanceOf(LifecycleTransitionOutcome.InvalidTransition::class.java)

            // Stale expectation -> precondition failure, NOT a domain conflict.
            val stale =
                commands.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion.INITIAL,
                    WorkloadLifecycleState.ACTIVE,
                )
            assertThat(stale).isInstanceOf(LifecycleTransitionOutcome.Stale::class.java)

            val settled = store.find(identity.workloadId, identity.environmentId, identity.deploymentId)
            assertThat(settled?.lifecycle).isEqualTo(WorkloadLifecycleState.RETIRED)
        }

    // ── Read contract ───────────────────────────────────────────────

    @Test
    fun `authoritative read is classified as such and reports the authoritative version`() =
        runBlocking<Unit> {
            val version = advanceTo(3)

            val read = queries.authoritative(identity.workloadId, identity.environmentId, identity.deploymentId)

            assertThat(read).isNotNull
            assertThat(read!!.consistency).isEqualTo(QueryConsistency.AUTHORITATIVE)
            assertThat(read.observedVersion).isEqualTo(version)
            assertThat(read.exposure.stateVersion).isEqualTo(version)
        }

    @Test
    fun `projection read states its consistency class and the version it observed`() =
        runBlocking<Unit> {
            val version = advanceTo(3)

            val read = queries.projection(identity.workloadId, identity.environmentId, identity.deploymentId)

            assertThat(read).isNotNull
            assertThat(read!!.consistency).isEqualTo(QueryConsistency.PROJECTION)
            assertThat(read.observedVersion).isEqualTo(version)
        }

    @Test
    fun `a lagging projection observation never authorizes a command`() =
        runBlocking<Unit> {
            // Projection observed v16, then the authority moved on to v17.
            val version16 = advanceTo(16)
            val observed = queries.projection(identity.workloadId, identity.environmentId, identity.deploymentId)
            assertThat(observed!!.observedVersion).isEqualTo(version16)

            val winner =
                commands.updateMetadata(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    version16,
                    WorkloadMetadata(owner = "Concurrent writer", purpose = "advance"),
                )
            assertThat(winner).isInstanceOf(MetadataUpdateOutcome.Applied::class.java)
            val current = (winner as MetadataUpdateOutcome.Applied).exposure.stateVersion
            assertThat(current).isEqualTo(WorkloadStateVersion(17))

            // The lagging observation is spent: conditioning a command on it fails closed and
            // reports the current authority, so a client can re-read and reconcile.
            val late =
                commands.updateMetadata(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    observed.observedVersion,
                    WorkloadMetadata(owner = "Late writer", purpose = "stale"),
                )

            assertThat(late).isInstanceOf(MetadataUpdateOutcome.Stale::class.java)
            (late as MetadataUpdateOutcome.Stale).let {
                assertThat(it.expectedVersion).isEqualTo(WorkloadStateVersion(16))
                assertThat(it.currentVersion).isEqualTo(WorkloadStateVersion(17))
            }
            val settled = store.find(identity.workloadId, identity.environmentId, identity.deploymentId)
            assertThat(settled?.metadata?.owner).isNotEqualTo("Late writer")
        }

    @Test
    fun `absence is null on the read path, not an error`() =
        runBlocking<Unit> {
            val authoritative =
                queries.authoritative(identity.workloadId, identity.environmentId, identity.deploymentId)
            assertThat(authoritative).isNull()
            assertThat(queries.projection(identity.workloadId, identity.environmentId, identity.deploymentId)).isNull()
        }

    // ── Structural guarantees ───────────────────────────────────────

    @Test
    fun `the read port exposes no operation that can mutate authoritative state`() {
        val readOperations =
            WorkloadControlPlaneQueries::class.java.methods
                .map { it.name }
                .toSet()

        assertThat(readOperations).contains("authoritative", "projection")
        assertThat(readOperations)
            .doesNotContainAnyElementsOf(
                listOf("register", "updateMetadata", "transitionLifecycle", "compareAndSet", "create"),
            )
    }

    @Test
    fun `a command takes an explicit version, never a read record`() {
        // The guarantee is about the RECORD, not about the version type. Commands take
        // (identity fields, expectedVersion, payload), so a read result cannot be handed over as a
        // mutation witness. The version a read observed may still be submitted as an explicit
        // expected version — that is not the read becoming authoritative, because the authority
        // re-reads and answers Stale unless its current version still equals it (pinned by
        // `a lagging projection observation never authorizes a command`).
        val commandParameters =
            WorkloadControlPlaneCommands::class.java.methods
                .flatMap { it.parameterTypes.toList() }
                .toSet()

        assertThat(commandParameters).doesNotContain(RegisteredWorkload::class.java, ClassifiedRead::class.java)
        assertThat(commandParameters).contains(WorkloadStateVersion::class.java)
    }

    // ── helpers ─────────────────────────────────────────────────────

    /** Registers, then advances the authoritative version to [target] with distinct metadata. */
    private suspend fun advanceTo(target: Long): WorkloadStateVersion {
        commands.register(identity, fingerprint, metadata)
        var version = WorkloadStateVersion.INITIAL
        while (version.value < target) {
            val outcome =
                commands.updateMetadata(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    version,
                    WorkloadMetadata(owner = "owner-${version.value}", purpose = "advance"),
                )
            version = (outcome as MetadataUpdateOutcome.Applied).exposure.stateVersion
        }
        return version
    }
}
