package dev.tramai.controlplane.testing

import dev.tramai.controlplane.CreateResult
import dev.tramai.controlplane.RegisteredWorkload
import dev.tramai.controlplane.RegistrationConflictReason
import dev.tramai.controlplane.WorkloadRegistrationStore
import dev.tramai.controlplane.WorkloadStateVersion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Shared [WorkloadRegistrationStore] compatibility contract (0.7.1c).
 *
 * Every implementation — InMemory reference, JDBC durable — must satisfy
 * exactly the same externally observable registration-storage semantics. The
 * TCK tests the store contract, not implementation internals and not the
 * authority state machine (which lives above the store and is covered by the
 * authority test suite).
 *
 * Each test gets a FRESH store from [harness].
 *
 * Suppressed rules: JUnit human-readable backtick test names are treated as
 * production function names by Detekt because testFixtures sources are not on
 * the ordinary test path, and the iteration literals of the race probes are
 * intentional. The TramAI Detekt policy keeps one repository-wide
 * configuration; these are narrow, documented class-level suppressions for a
 * reusable TCK, not global config changes.
 */
@Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")
abstract class WorkloadRegistrationStoreTck {
    abstract val harness: WorkloadRegistrationStoreTckHarness

    protected lateinit var store: WorkloadRegistrationStore

    @BeforeEach
    fun setUp() {
        store = harness.createStore()
    }

    @AfterEach
    fun tearDown() = runBlocking<Unit> { harness.closeStore(store) }

    private suspend fun current(registration: RegisteredWorkload): RegisteredWorkload? =
        store.find(
            registration.identity.workloadId,
            registration.identity.environmentId,
            registration.identity.deploymentId,
        )

    // ── Creation / read ─────────────────────────────────────────────

    @Test
    fun `fresh create round-trips through find`() =
        runBlocking<Unit> {
            val registration = WorkloadRegistrationFixtures.registration()

            val result = store.create(registration)

            assertThat(result).isEqualTo(CreateResult.Created(registration))
            assertThat(current(registration)).isEqualTo(registration)
        }

    @Test
    fun `find on unknown deployment scope returns null`() =
        runBlocking<Unit> {
            val identity = WorkloadRegistrationFixtures.identity()
            assertThat(
                store.find(identity.workloadId, identity.environmentId, identity.deploymentId),
            ).isNull()
        }

    // ── Idempotency (register is not an upsert) ─────────────────────

    @Test
    fun `identical declaration creates once then is idempotent`() =
        runBlocking<Unit> {
            val registration = WorkloadRegistrationFixtures.registration()

            val first = store.create(registration)
            val second = store.create(registration)

            assertThat(first).isEqualTo(CreateResult.Created(registration))
            assertThat(second).isEqualTo(CreateResult.Idempotent(registration))
        }

    // ── Deployment scope exclusivity ────────────────────────────────

    @Test
    fun `same scope with different configuration is a conflicting registration`() =
        runBlocking<Unit> {
            store.create(WorkloadRegistrationFixtures.registration())

            val differentConfiguration =
                WorkloadRegistrationFixtures.registration(
                    identity = WorkloadRegistrationFixtures.identity(configurationVersion = "18"),
                )

            val result = store.create(differentConfiguration)

            assertThat(result).isInstanceOf(CreateResult.Conflicting::class.java)
            result as CreateResult.Conflicting
            assertThat(result.reason).isEqualTo(RegistrationConflictReason.CONFLICTING_REGISTRATION)
        }

    @Test
    fun `same scope with different metadata is a conflicting registration`() =
        runBlocking<Unit> {
            store.create(WorkloadRegistrationFixtures.registration())

            val differentMetadata =
                WorkloadRegistrationFixtures.registration(
                    metadata = WorkloadRegistrationFixtures.metadata(owner = "Risk Platform Team"),
                )

            val result = store.create(differentMetadata)

            assertThat(result).isInstanceOf(CreateResult.Conflicting::class.java)
            result as CreateResult.Conflicting
            assertThat(result.reason).isEqualTo(RegistrationConflictReason.CONFLICTING_REGISTRATION)
        }

    @Test
    fun `distinct deployment ids within one environment stay distinct`() =
        runBlocking<Unit> {
            val amsterdam =
                WorkloadRegistrationFixtures.registration(
                    identity = WorkloadRegistrationFixtures.identity(deployment = "eu-west-amsterdam-01"),
                )
            val frankfurt =
                WorkloadRegistrationFixtures.registration(
                    identity = WorkloadRegistrationFixtures.identity(deployment = "eu-central-frankfurt-01"),
                )

            assertThat(store.create(amsterdam)).isInstanceOf(CreateResult.Created::class.java)
            assertThat(store.create(frankfurt)).isInstanceOf(CreateResult.Created::class.java)
        }

    @Test
    fun `same deployment id in distinct environments stays distinct`() =
        runBlocking<Unit> {
            val production =
                WorkloadRegistrationFixtures.registration(
                    identity = WorkloadRegistrationFixtures.identity(environment = "production"),
                )
            val staging =
                WorkloadRegistrationFixtures.registration(
                    identity = WorkloadRegistrationFixtures.identity(environment = "staging"),
                )

            assertThat(store.create(production)).isInstanceOf(CreateResult.Created::class.java)
            assertThat(store.create(staging)).isInstanceOf(CreateResult.Created::class.java)
        }

    // ── Configuration binding is fixed forever ──────────────────────

    @Test
    fun `same configuration pair with different fingerprint is rejected from another deployment`() =
        runBlocking<Unit> {
            val original = WorkloadRegistrationFixtures.registration()
            store.create(original)

            // Same (configurationId, version), different fingerprint, DIFFERENT
            // deployment scope: must still fail — the binding is global.
            val rebindingAttempt =
                WorkloadRegistrationFixtures.registration(
                    identity = WorkloadRegistrationFixtures.identity(deployment = "eu-central-frankfurt-01"),
                    configurationFingerprint = WorkloadRegistrationFixtures.fingerprint(value = "sha256:bbbb"),
                )

            val result = store.create(rebindingAttempt)

            assertThat(result).isInstanceOf(CreateResult.Conflicting::class.java)
            result as CreateResult.Conflicting
            assertThat(result.reason).isEqualTo(RegistrationConflictReason.CONFIGURATION_REBINDING)
        }

    @Test
    fun `same configuration pair with same fingerprint is allowed from another deployment`() =
        runBlocking<Unit> {
            val original = WorkloadRegistrationFixtures.registration()
            store.create(original)

            val sameConfiguration =
                WorkloadRegistrationFixtures.registration(
                    identity = WorkloadRegistrationFixtures.identity(deployment = "eu-central-frankfurt-01"),
                )

            assertThat(store.create(sameConfiguration)).isInstanceOf(CreateResult.Created::class.java)
        }

    // ── compareAndSet ───────────────────────────────────────────────

    @Test
    fun `compareAndSet applies when expected is current`() =
        runBlocking<Unit> {
            val registration = WorkloadRegistrationFixtures.registration()
            store.create(registration)

            val updated = registration.copy(stateVersion = WorkloadStateVersion(2))

            assertThat(store.compareAndSet(registration, updated)).isTrue()
            assertThat(current(registration)).isEqualTo(updated)
        }

    @Test
    fun `compareAndSet cannot change registration identity`() =
        runBlocking<Unit> {
            val registration = WorkloadRegistrationFixtures.registration()
            store.create(registration)

            val identityChange =
                registration.copy(
                    identity = WorkloadRegistrationFixtures.identity(deployment = "eu-central-frankfurt-01"),
                    stateVersion = WorkloadStateVersion(2),
                )

            assertThat(
                runCatching { store.compareAndSet(registration, identityChange) }.exceptionOrNull(),
            ).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(current(registration)).isEqualTo(registration)
        }

    @Test
    fun `compareAndSet cannot change the configuration inside identity`() =
        runBlocking<Unit> {
            val registration = WorkloadRegistrationFixtures.registration()
            store.create(registration)

            val configurationChange =
                registration.copy(
                    identity = WorkloadRegistrationFixtures.identity(configurationVersion = "18"),
                    stateVersion = WorkloadStateVersion(2),
                )

            assertThat(
                runCatching { store.compareAndSet(registration, configurationChange) }.exceptionOrNull(),
            ).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(current(registration)).isEqualTo(registration)
        }

    @Test
    fun `compareAndSet cannot change the configuration fingerprint`() =
        runBlocking<Unit> {
            val registration = WorkloadRegistrationFixtures.registration()
            store.create(registration)

            val fingerprintChange =
                registration.copy(
                    configurationFingerprint = WorkloadRegistrationFixtures.fingerprint(value = "sha256:ffff"),
                    stateVersion = WorkloadStateVersion(2),
                )

            assertThat(
                runCatching { store.compareAndSet(registration, fingerprintChange) }.exceptionOrNull(),
            ).isInstanceOf(IllegalArgumentException::class.java)
            // The CAS never applied, so the record — and its fingerprint — are untouched.
            assertThat(current(registration)).isEqualTo(registration)
        }

    @Test
    fun `stale compareAndSet is rejected and state is untouched`() =
        runBlocking<Unit> {
            val registration = WorkloadRegistrationFixtures.registration()
            store.create(registration)
            val winner = registration.copy(stateVersion = WorkloadStateVersion(2))
            assertThat(store.compareAndSet(registration, winner)).isTrue()

            // A stale writer still holds the original version-1 snapshot.
            val staleWriter =
                registration.copy(
                    metadata = WorkloadRegistrationFixtures.metadata(owner = "Stale Team"),
                )

            assertThat(store.compareAndSet(registration, staleWriter)).isFalse()
            assertThat(current(registration)).isEqualTo(winner)
        }

    // ── Concurrency ─────────────────────────────────────────────────

    @Test
    fun `two concurrent creates for one scope yield exactly one authoritative outcome`() =
        runBlocking<Unit> {
            repeat(RACE_ITERATIONS) { iteration ->
                val registration =
                    WorkloadRegistrationFixtures.registration(
                        identity =
                            WorkloadRegistrationFixtures.identity(
                                deployment = "create-race-$iteration",
                            ),
                    )

                val results =
                    runInParallel(
                        { store.create(registration) },
                        { store.create(registration) },
                    )

                val created = results.filterIsInstance<CreateResult.Created>()
                val idempotent = results.filterIsInstance<CreateResult.Idempotent>()
                assertThat(created.size + idempotent.size).isEqualTo(2)
                assertThat(created.size).isEqualTo(1)
                assertThat(current(registration)).isEqualTo(registration)
            }
        }

    @Test
    fun `two concurrent CAS writers yield exactly one winner and final version 2`() =
        runBlocking<Unit> {
            repeat(RACE_ITERATIONS) { iteration ->
                val registration =
                    WorkloadRegistrationFixtures.registration(
                        identity =
                            WorkloadRegistrationFixtures.identity(
                                deployment = "cas-race-$iteration",
                            ),
                    )
                store.create(registration)
                val winnerUpdate =
                    registration.copy(
                        metadata = WorkloadRegistrationFixtures.metadata(owner = "Winner"),
                        stateVersion = WorkloadStateVersion(2),
                    )
                val loserUpdate =
                    registration.copy(
                        metadata = WorkloadRegistrationFixtures.metadata(owner = "Loser"),
                        stateVersion = WorkloadStateVersion(2),
                    )

                val results =
                    runInParallel(
                        { store.compareAndSet(registration, winnerUpdate) },
                        { store.compareAndSet(registration, loserUpdate) },
                    )

                assertThat(results.filter { it }.size).isEqualTo(1)
                assertThat(results.filter { !it }.size).isEqualTo(1)
                assertThat(current(registration)?.stateVersion).isEqualTo(WorkloadStateVersion(2))
            }
        }

    companion object {
        /** How many times each concurrency race runs on fresh deployment scopes. */
        private const val RACE_ITERATIONS: Int = 5
    }
}

/**
 * Runs the contenders against each other on Dispatchers.Default, releasing
 * them simultaneously once all are parked. Two plain `async` calls inside
 * `runBlocking` do NOT run concurrently — runBlocking's event loop is
 * single-threaded — so the ready/release handshake is mandatory for a real
 * race.
 */
public suspend fun <T> runInParallel(vararg contenders: suspend () -> T): List<T> =
    coroutineScope {
        val ready = Channel<Unit>(capacity = contenders.size)
        val release = CompletableDeferred<Unit>()
        val results =
            contenders.map { op ->
                async(Dispatchers.Default) {
                    ready.send(Unit)
                    release.await()
                    op()
                }
            }
        repeat(contenders.size) { ready.receive() }
        release.complete(Unit)
        results.map { it.await() }
    }
