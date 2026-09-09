package dev.tramai.controlplane

import dev.tramai.controlplane.testing.WorkloadRegistrationFixtures
import dev.tramai.controlplane.testing.runInParallel
import dev.tramai.core.identity.WorkloadMetadata
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Authoritative registration rules (0.7.1c). The state machine lives in
 * [WorkloadRegistrationAuthority], above the store; these tests pin every
 * rule in the candidate brief, including the adversarial matrix.
 */
class WorkloadRegistrationAuthorityTest {
    private lateinit var store: InMemoryWorkloadRegistrationStore
    private lateinit var authority: WorkloadRegistrationAuthority

    @BeforeEach
    fun setUp() {
        store = InMemoryWorkloadRegistrationStore()
        authority = WorkloadRegistrationAuthority(store)
    }

    private val identity = WorkloadRegistrationFixtures.identity()
    private val fingerprint = WorkloadRegistrationFixtures.fingerprint()
    private val metadata = WorkloadRegistrationFixtures.metadata()

    private suspend fun current(): RegisteredWorkload? =
        store.find(
            identity.workloadId,
            identity.environmentId,
            identity.deploymentId,
        )

    // ── Registration ────────────────────────────────────────────────

    @Test
    fun `register creates ACTIVE registration at version 1`() =
        runBlocking<Unit> {
            val outcome = authority.register(identity, fingerprint, metadata)

            assertThat(outcome).isInstanceOf(RegisterOutcome.Created::class.java)
            val created = (outcome as RegisterOutcome.Created).registration
            assertThat(created.lifecycle).isEqualTo(WorkloadLifecycleState.ACTIVE)
            assertThat(created.stateVersion).isEqualTo(WorkloadStateVersion.INITIAL)
        }

    @Test
    fun `registering the identical declaration is idempotent at version 1`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)

            val again = authority.register(identity, fingerprint, metadata)

            assertThat(again).isInstanceOf(RegisterOutcome.AlreadyRegistered::class.java)
            val existing = (again as RegisterOutcome.AlreadyRegistered).registration
            assertThat(existing.stateVersion).isEqualTo(WorkloadStateVersion.INITIAL)
            assertThat(existing.lifecycle).isEqualTo(WorkloadLifecycleState.ACTIVE)
        }

    @Test
    fun `re-registering the same scope with a different configuration is rejected, not upserted`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)
            val nextConfiguration =
                identity.copy(
                    configuration =
                        identity.configuration.copy(
                            version =
                                dev.tramai.core.identity
                                    .ConfigurationVersion("18"),
                        ),
                )

            val outcome = authority.register(nextConfiguration, fingerprint, metadata)

            assertThat(outcome).isInstanceOf(RegisterOutcome.Rejected::class.java)
            (outcome as RegisterOutcome.Rejected).let { rejected ->
                assertThat(rejected.reason)
                    .isEqualTo(RegistrationConflictReason.CONFLICTING_REGISTRATION)
            }
            assertThat(current()).isEqualTo(
                WorkloadRegistrationFixtures.registration(identity = identity),
            )
        }

    @Test
    fun `re-registering with different metadata is rejected, never a silent update`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)

            val outcome =
                authority.register(
                    identity,
                    fingerprint,
                    WorkloadRegistrationFixtures.metadata(owner = "Risk Platform Team"),
                )

            assertThat(outcome).isInstanceOf(RegisterOutcome.Rejected::class.java)
            (outcome as RegisterOutcome.Rejected).let { rejected ->
                assertThat(rejected.reason)
                    .isEqualTo(RegistrationConflictReason.CONFLICTING_REGISTRATION)
            }
            assertThat(current()).isEqualTo(
                WorkloadRegistrationFixtures.registration(identity = identity),
            )
        }

    @Test
    fun `same configuration pair with a different fingerprint is configuration rebinding`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)

            val rebinding =
                authority.register(
                    identity,
                    WorkloadRegistrationFixtures.fingerprint(value = "sha256:bbbb"),
                    metadata,
                )

            assertThat(rebinding).isInstanceOf(RegisterOutcome.Rejected::class.java)
            (rebinding as RegisterOutcome.Rejected).let { rejected ->
                assertThat(rejected.reason)
                    .isEqualTo(RegistrationConflictReason.CONFIGURATION_REBINDING)
            }
        }

    @Test
    fun `configuration rebinding is rejected from a different deployment scope`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)
            val frankfurtIdentity =
                WorkloadRegistrationFixtures.identity(
                    deployment = "eu-central-frankfurt-01",
                )

            val outcome =
                authority.register(
                    frankfurtIdentity,
                    WorkloadRegistrationFixtures.fingerprint(value = "sha256:bbbb"),
                    metadata,
                )

            assertThat(outcome).isInstanceOf(RegisterOutcome.Rejected::class.java)
            (outcome as RegisterOutcome.Rejected).let { rejected ->
                assertThat(rejected.reason)
                    .isEqualTo(RegistrationConflictReason.CONFIGURATION_REBINDING)
            }
        }

    @Test
    fun `the same configuration on a different deployment registers cleanly`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)

            val outcome =
                authority.register(
                    WorkloadRegistrationFixtures.identity(deployment = "eu-central-frankfurt-01"),
                    fingerprint,
                    metadata,
                )

            assertThat(outcome).isInstanceOf(RegisterOutcome.Created::class.java)
        }

    @Test
    fun `registration survives reads without advancing version`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)
            repeat(3) { current() }

            assertThat(current()?.stateVersion).isEqualTo(WorkloadStateVersion.INITIAL)
        }

    // ── Metadata mutation ───────────────────────────────────────────

    @Test
    fun `metadata update preserves identity and advances version`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)
            val newMetadata = WorkloadMetadata(owner = "Risk Platform Team", purpose = "Fraud review")

            val outcome =
                authority.updateMetadata(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion.INITIAL,
                    newMetadata,
                )

            assertThat(outcome).isInstanceOf(MetadataUpdateOutcome.Applied::class.java)
            (outcome as MetadataUpdateOutcome.Applied).registration.let { updated ->
                assertThat(updated.metadata).isEqualTo(newMetadata)
                assertThat(updated.identity).isEqualTo(identity)
                assertThat(updated.stateVersion).isEqualTo(WorkloadStateVersion(2))
            }
        }

    @Test
    fun `identical metadata update is unchanged and does not advance version`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)

            val outcome =
                authority.updateMetadata(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion.INITIAL,
                    metadata,
                )

            assertThat(outcome).isInstanceOf(MetadataUpdateOutcome.Unchanged::class.java)
            assertThat(current()?.stateVersion).isEqualTo(WorkloadStateVersion.INITIAL)
        }

    @Test
    fun `stale metadata update is rejected without mutation`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)
            authority.updateMetadata(
                identity.workloadId,
                identity.environmentId,
                identity.deploymentId,
                WorkloadStateVersion.INITIAL,
                WorkloadMetadata(owner = "Team A", purpose = "p"),
            )
            // current version is now 2; a writer still holding version 1 must lose.
            val staleOutcome =
                authority.updateMetadata(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion.INITIAL,
                    WorkloadMetadata(owner = "Stale Team", purpose = "p"),
                )

            assertThat(staleOutcome).isInstanceOf(MetadataUpdateOutcome.Stale::class.java)
            (staleOutcome as MetadataUpdateOutcome.Stale).let {
                assertThat(it.currentVersion).isEqualTo(WorkloadStateVersion(2))
                assertThat(it.expectedVersion).isEqualTo(WorkloadStateVersion.INITIAL)
            }
            assertThat(current()?.stateVersion).isEqualTo(WorkloadStateVersion(2))
            assertThat(current()?.metadata?.owner).isEqualTo("Team A")
        }

    @Test
    fun `metadata update on unknown registration is not found`() =
        runBlocking<Unit> {
            val outcome =
                authority.updateMetadata(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion.INITIAL,
                    metadata,
                )

            assertThat(outcome).isEqualTo(MetadataUpdateOutcome.NotFound)
        }

    // ── Lifecycle transitions ───────────────────────────────────────

    @Test
    fun `ACTIVE to SUSPENDED to ACTIVE transitions advance version`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)

            val suspendOutcome =
                authority.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion.INITIAL,
                    WorkloadLifecycleState.SUSPENDED,
                )
            assertThat(suspendOutcome).isInstanceOf(LifecycleTransitionOutcome.Applied::class.java)
            assertThat((suspendOutcome as LifecycleTransitionOutcome.Applied).registration.stateVersion)
                .isEqualTo(WorkloadStateVersion(2))

            val resumeOutcome =
                authority.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion(2),
                    WorkloadLifecycleState.ACTIVE,
                )
            assertThat(resumeOutcome).isInstanceOf(LifecycleTransitionOutcome.Applied::class.java)
            assertThat((resumeOutcome as LifecycleTransitionOutcome.Applied).registration.stateVersion)
                .isEqualTo(WorkloadStateVersion(3))
            assertThat(current()?.lifecycle).isEqualTo(WorkloadLifecycleState.ACTIVE)
        }

    @Test
    fun `ACTIVE may retire directly and RETIRED is terminal`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)

            val retire =
                authority.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion.INITIAL,
                    WorkloadLifecycleState.RETIRED,
                )
            assertThat(retire).isInstanceOf(LifecycleTransitionOutcome.Applied::class.java)

            val resurrect =
                authority.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    current()!!.stateVersion,
                    WorkloadLifecycleState.ACTIVE,
                )
            assertThat(resurrect).isInstanceOf(LifecycleTransitionOutcome.InvalidTransition::class.java)
            (resurrect as LifecycleTransitionOutcome.InvalidTransition).let {
                assertThat(it.from).isEqualTo(WorkloadLifecycleState.RETIRED)
                assertThat(it.to).isEqualTo(WorkloadLifecycleState.ACTIVE)
            }
            assertThat(current()?.lifecycle).isEqualTo(WorkloadLifecycleState.RETIRED)
        }

    @Test
    fun `SUSPENDED may retire and RETIRED remains terminal`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)
            authority.transitionLifecycle(
                identity.workloadId,
                identity.environmentId,
                identity.deploymentId,
                WorkloadStateVersion.INITIAL,
                WorkloadLifecycleState.SUSPENDED,
            )

            val retire =
                authority.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    current()!!.stateVersion,
                    WorkloadLifecycleState.RETIRED,
                )
            assertThat(retire).isInstanceOf(LifecycleTransitionOutcome.Applied::class.java)

            val resurrect =
                authority.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    current()!!.stateVersion,
                    WorkloadLifecycleState.ACTIVE,
                )
            assertThat(resurrect).isInstanceOf(LifecycleTransitionOutcome.InvalidTransition::class.java)
            assertThat(current()?.lifecycle).isEqualTo(WorkloadLifecycleState.RETIRED)
        }

    @Test
    fun `retired registration cannot be suspended either`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)
            authority.transitionLifecycle(
                identity.workloadId,
                identity.environmentId,
                identity.deploymentId,
                WorkloadStateVersion.INITIAL,
                WorkloadLifecycleState.RETIRED,
            )

            val outcome =
                authority.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    current()!!.stateVersion,
                    WorkloadLifecycleState.SUSPENDED,
                )

            assertThat(outcome).isInstanceOf(LifecycleTransitionOutcome.InvalidTransition::class.java)
        }

    @Test
    fun `same-state transition is unchanged and does not advance version`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)

            val outcome =
                authority.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion.INITIAL,
                    WorkloadLifecycleState.ACTIVE,
                )

            assertThat(outcome).isInstanceOf(LifecycleTransitionOutcome.Unchanged::class.java)
            assertThat(current()?.stateVersion).isEqualTo(WorkloadStateVersion.INITIAL)
        }

    @Test
    fun `lifecycle transitions preserve identity`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)
            authority.transitionLifecycle(
                identity.workloadId,
                identity.environmentId,
                identity.deploymentId,
                WorkloadStateVersion.INITIAL,
                WorkloadLifecycleState.SUSPENDED,
            )

            assertThat(current()?.identity).isEqualTo(identity)
            assertThat(current()?.configurationFingerprint).isEqualTo(fingerprint)
        }

    @Test
    fun `stale lifecycle transition loses and does not mutate`() =
        runBlocking<Unit> {
            authority.register(identity, fingerprint, metadata)
            authority.transitionLifecycle(
                identity.workloadId,
                identity.environmentId,
                identity.deploymentId,
                WorkloadStateVersion.INITIAL,
                WorkloadLifecycleState.SUSPENDED,
            )

            val stale =
                authority.transitionLifecycle(
                    identity.workloadId,
                    identity.environmentId,
                    identity.deploymentId,
                    WorkloadStateVersion.INITIAL,
                    WorkloadLifecycleState.RETIRED,
                )

            assertThat(stale).isInstanceOf(LifecycleTransitionOutcome.Stale::class.java)
            assertThat(current()?.lifecycle).isEqualTo(WorkloadLifecycleState.SUSPENDED)
            assertThat(current()?.stateVersion).isEqualTo(WorkloadStateVersion(2))
        }

    // ── Concurrency ─────────────────────────────────────────────────

    @Test
    fun `two concurrent metadata writers produce one winner and one stale loser`() =
        runBlocking<Unit> {
            repeat(5) { iteration ->
                val scope =
                    WorkloadRegistrationFixtures.identity(
                        deployment = "meta-race-$iteration",
                    )
                authority.register(scope, fingerprint, metadata)

                val outcomes =
                    runInParallel(
                        {
                            authority.updateMetadata(
                                scope.workloadId,
                                scope.environmentId,
                                scope.deploymentId,
                                WorkloadStateVersion.INITIAL,
                                WorkloadMetadata(owner = "Writer A", purpose = "a"),
                            )
                        },
                        {
                            authority.updateMetadata(
                                scope.workloadId,
                                scope.environmentId,
                                scope.deploymentId,
                                WorkloadStateVersion.INITIAL,
                                WorkloadMetadata(owner = "Writer B", purpose = "b"),
                            )
                        },
                    )

                assertThat(outcomes.filterIsInstance<MetadataUpdateOutcome.Applied>()).hasSize(1)
                val stale = outcomes.filterIsInstance<MetadataUpdateOutcome.Stale>().single()
                // The loser must report the TRUTHFUL current version (2), not
                // the version it read before the race (1) — 0.7.1e command
                // preconditions will consume exactly this value.
                assertThat(stale.expectedVersion).isEqualTo(WorkloadStateVersion.INITIAL)
                assertThat(stale.currentVersion).isEqualTo(WorkloadStateVersion(2))
                val finalRecord = store.find(scope.workloadId, scope.environmentId, scope.deploymentId)
                assertThat(finalRecord?.stateVersion).isEqualTo(stale.currentVersion)
                assertThat(finalRecord?.metadata?.owner).isIn("Writer A", "Writer B")
            }
        }
}
