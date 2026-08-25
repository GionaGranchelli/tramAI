package dev.tramai.orchestration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Epic 8.2c — worker lifecycle state-machine properties (PR #280).
 *
 * Primary question: after arbitrary start/shutdown/crash/restart histories
 * and adversarial interleavings, can the worker have two lifecycle owners,
 * resurrect an old generation, continue accepting work after shutdown,
 * leak registration, or emit lifecycle events for a generation that has
 * already died?
 *
 * Properties 2 and 3 are the expected discriminators for two genuine
 * current-master defects: failed registration retains root ownership, and
 * a shutdown during suspended registration does not abort the resurrecting
 * startup. Production is fixed only where the documented contract and the
 * pure model agree and the implementation differs.
 */
class WorkerLifecyclePropertyTest {

    // ── Property 1: generated sequential histories ─────────────────────────

    @Test
    fun `generated worker lifecycle sequences match the independent model after every action`() = runBlocking<Unit> {
        for (seed in 0L until WorkerLifecycleActionGenerator.SEED_COUNT) {
            val harness = WorkerLifecyclePropertyHarness()
            val actions = WorkerLifecycleActionGenerator.generate(seed)
            var model = WorkerLifecycleModel.stopped()

            actions.forEachIndexed { step, action ->
                val message = "seed=$seed step=$step action=$action\nmodelBefore=$model"
                val predicted = model.apply(action)
                harness.runAction(action)
                val observed = harness.snapshot(harness.isRegistered())

                val expected = (predicted as? WorkerLifecycleOutcome.Success)?.next
                    ?: (predicted as WorkerLifecycleOutcome.Failure).next
                assertThat(observed.workerStartedEvents)
                    .withFailMessage("$message\nstarted events\nexpected=${expected.workerStartedEvents} actual=${observed.workerStartedEvents}")
                    .isEqualTo(expected.workerStartedEvents)
                assertThat(observed.shutdownStartedEvents)
                    .withFailMessage("$message\nshutdownStarted events")
                    .isEqualTo(expected.shutdownStartedEvents)
                assertThat(observed.shutdownCompleteEvents)
                    .withFailMessage("$message\nshutdownComplete events")
                    .isEqualTo(expected.shutdownCompleteEvents)
                assertThat(observed.workerStoppedEvents)
                    .withFailMessage("$message\nworkerStopped events")
                    .isEqualTo(expected.workerStoppedEvents)
                assertThat(observed.registrations)
                    .withFailMessage("$message\nregistrations")
                    .isEqualTo(expected.registrations)
                assertThat(observed.unregistrations)
                    .withFailMessage("$message\nunregistrations")
                    .isEqualTo(expected.unregistrations)
                assertThat(observed.registeredNow)
                    .withFailMessage("$message\nregistry presence")
                    .isEqualTo(expected.registered)
                assertThat(observed.phase)
                    .withFailMessage("$message\nphase")
                    .isEqualTo(expected.phase)

                assertThat(expected.invariantViolation())
                    .withFailMessage("$message\nmodel invariant violated")
                    .isNull()
                model = expected
            }
        }
    }

    // ── Property 2: registration failure rolls startup back ────────────────

    @Test
    fun `failed registration rolls startup back to a retryable stopped state`() = runBlocking<Unit> {
        val harness = WorkerLifecyclePropertyHarness()

        harness.hooks.failNextRegistration = true
        val error = runCatching { harness.worker.start() }.exceptionOrNull()
        assertThat(error).withFailMessage("start must surface the registration failure").isNotNull()

        assertThat(harness.observer.workerStarted).withFailMessage("failed startup must not emit onWorkerStarted").isEmpty()
        assertThat(harness.observer.shutdownStarted).isEmpty()
        assertThat(harness.isRegistered()).withFailMessage("failed startup must not leave a registry row").isFalse()
        assertThat(harness.observedPhase).isEqualTo(WorkerLifecyclePhase.STOPPED)

        // A subsequent start must retry normally: root ownership was rolled back.
        harness.worker.start()
        assertThat(harness.observer.workerStarted).hasSize(1)
        assertThat(harness.isRegistered()).isTrue()

        harness.worker.shutdown()
        assertThat(harness.observer.shutdownComplete).hasSize(1)
        assertThat(harness.isRegistered()).isFalse()
    }

    // ── Property 3: shutdown during registration cannot resurrect startup ──

    @Test
    fun `shutdown during registration cannot resurrect the startup`() = runBlocking<Unit> {
        val harness = WorkerLifecyclePropertyHarness()

        // Generation A completes cleanly so B's registration is the SECOND one.
        harness.worker.start()
        harness.worker.shutdown()
        assertThat(harness.observer.shutdownComplete).hasSize(1)

        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        harness.hooks.onRegister = {
            entered.complete(Unit)
            gate.await()
        }

        // Generation B suspends inside registration.
        val startedB = launch { harness.worker.start() }
        withTimeout(10_000) { entered.await() }

        // Shutdown during B's registration must be accepted and must complete.
        withTimeout(10_000) { harness.worker.shutdown() }
        assertThat(harness.observer.shutdownComplete).hasSize(2)
        assertThat(harness.observer.workerStopped).hasSize(2)

        val heartbeatsBefore = harness.observer.heartbeats.size
        gate.complete(Unit)
        withTimeout(10_000) { startedB.join() }

        // B's startup must have been ABORTED by the shutdown: no started event
        // for a generation that already died, no zombie registry row, no
        // heartbeat resurrection. The event-order invariant
        // shutdownComplete(B) -> workerStarted(B) is impossible.
        assertThat(harness.observer.workerStarted)
            .withFailMessage("aborted startup must not emit onWorkerStarted after shutdown completed")
            .hasSize(1)
        assertThat(harness.isRegistered())
            .withFailMessage("aborted startup must not leave a zombie registry row")
            .isFalse()
        assertThat(harness.observer.heartbeats.size)
            .withFailMessage("aborted startup must not resurrect the heartbeat loop")
            .isEqualTo(heartbeatsBefore)

        // A completely fresh generation C can start normally.
        harness.worker.start()
        assertThat(harness.observer.workerStarted).hasSize(2)
        assertThat(harness.isRegistered()).isTrue()
        harness.worker.shutdown()
        assertThat(harness.observer.shutdownComplete).hasSize(3)
        assertThat(harness.isRegistered()).isFalse()
    }

    // ── Property 4: eight concurrent starts create one generation ──────────

    @Test
    fun `eight concurrent starts create exactly one generation`() = runBlocking<Unit> {
        repeat(20) { iteration ->
            val harness = WorkerLifecyclePropertyHarness(workerId = "concurrent-start-$iteration")
            val gate = CompletableDeferred<Unit>()
            val starters = (0 until 8).map {
                launch(Dispatchers.Default) {
                    gate.await()
                    harness.worker.start()
                }
            }
            gate.complete(Unit)
            starters.forEach { it.join() }

            assertThat(harness.observer.workerStarted)
                .withFailMessage("iteration $iteration: exactly one winner must emit onWorkerStarted")
                .hasSize(1)
            assertThat(harness.registrations.get())
                .withFailMessage("iteration $iteration: exactly one registration")
                .isEqualTo(1)
            assertThat(harness.isRegistered()).isTrue()

            harness.worker.shutdown()
            assertThat(harness.observer.shutdownComplete).hasSize(1)
            assertThat(harness.isRegistered()).isFalse()
        }
    }

    // ── Property 5: eight concurrent shutdowns have one owner ──────────────

    @Test
    fun `eight concurrent shutdowns have exactly one owner`() = runBlocking<Unit> {
        repeat(20) { iteration ->
            val harness = WorkerLifecyclePropertyHarness(workerId = "concurrent-shutdown-$iteration")
            harness.worker.start()
            assertThat(harness.observer.workerStarted).hasSize(1)

            val gate = CompletableDeferred<Unit>()
            val shutters = (0 until 8).map {
                launch(Dispatchers.Default) {
                    gate.await()
                    harness.worker.shutdown()
                }
            }
            gate.complete(Unit)
            shutters.forEach { it.join() }

            assertThat(harness.observer.shutdownStarted)
                .withFailMessage("iteration $iteration: exactly one shutdown owner")
                .hasSize(1)
            assertThat(harness.observer.shutdownComplete)
                .withFailMessage("iteration $iteration: exactly one shutdown completion")
                .hasSize(1)
            assertThat(harness.observer.workerStopped)
                .withFailMessage("iteration $iteration: exactly one worker-stopped")
                .hasSize(1)
            assertThat(harness.unregistrations.get())
                .withFailMessage("iteration $iteration: exactly one unregister")
                .isEqualTo(1)
            assertThat(harness.isRegistered()).isFalse()

            // A fresh generation can start after the ownership release.
            harness.worker.start()
            assertThat(harness.observer.workerStarted).hasSize(2)
            harness.worker.shutdown()
            assertThat(harness.observer.shutdownComplete).hasSize(2)
        }
    }

    // ── Property 6: start while shutdown is in progress cannot transfer ownership ──

    @Test
    fun `start while shutdown is in progress cannot transfer ownership`() = runBlocking<Unit> {
        repeat(5) { iteration ->
            val gate = CompletableDeferred<Unit>()
            val observer = RecordingObserver()
            val store = InMemoryWorkflowCheckpointStore()
            val leaseStore = InMemoryWorkflowLeaseStore()
            val workflow = workerWorkflow("lifecycle-$iteration") {
                localStep("hold") { state, _ ->
                    gate.await()
                    state
                }
            }
            seedCheckpoint(store, workflow, "w-$iteration")
            val worker = TramaiWorker(
                config = workerConfig("lifecycle-hold-$iteration"),
                leaseStore = leaseStore,
                checkpointStore = store,
                checkpointCatalog = store,
                stepAttemptStore = store,
                workflowBindings = WorkflowBindingRegistry {
                    bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = WorkerStateCodec))
                },
                observability = observer,
            )

            worker.start()
            withTimeout(10_000) {
                while (store.latestStepAttempt("w-$iteration", "hold") == null) delay(5)
            }
            assertThat(observer.workerStarted).hasSize(1)

            // Shutdown A starts draining and holds on the gated execution.
            val shutdownA = launch { worker.shutdown() }
            withTimeout(10_000) {
                while (observer.shutdownStarted.isEmpty()) delay(5)
            }

            // Eight start() calls while draining must all be no-ops: ownership
            // still belongs to generation A, so no registration and no
            // worker-started for a phantom generation B.
            repeat(8) { worker.start() }
            assertThat(observer.workerStarted)
                .withFailMessage("iteration $iteration: start during drain must not create generation B")
                .hasSize(1)
            assertThat(leaseStore.listActiveWorkers().size).isEqualTo(1)

            gate.complete(Unit)
            withTimeout(10_000) { shutdownA.join() }
            assertThat(observer.shutdownComplete).hasSize(1)

            // One fresh start afterwards is generation B.
            worker.start()
            assertThat(observer.workerStarted).hasSize(2)
            worker.shutdown()
            assertThat(observer.shutdownComplete).hasSize(2)
            gate.complete(Unit)
        }
    }

    // ── Property 7: crash is not graceful shutdown ─────────────────────────

    @Test
    fun `crash is not graceful shutdown`() = runBlocking<Unit> {
        val harness = WorkerLifecyclePropertyHarness()
        val heartbeatEntered = CompletableDeferred<Unit>()
        val heartbeatExited = CompletableDeferred<Unit>()
        harness.hooks.onHeartbeat = { blockUntilCancelled(heartbeatEntered, heartbeatExited) }

        harness.worker.start()
        withTimeout(10_000) { heartbeatEntered.await() }
        assertThat(harness.observer.workerStarted).hasSize(1)
        assertThat(harness.isRegistered()).isTrue()

        harness.worker.crash()

        // Root cancellation kills the heartbeat loop — deterministic via the
        // blocking fake, no sleeps.
        withTimeout(10_000) { heartbeatExited.await() }

        // Crash must NOT look like a graceful departure: no shutdown events,
        // and the registry record is retained so it can go stale and be taken
        // over by another worker.
        assertThat(harness.observer.shutdownStarted).isEmpty()
        assertThat(harness.observer.shutdownComplete).isEmpty()
        assertThat(harness.observer.workerStopped).isEmpty()
        assertThat(harness.isRegistered()).isTrue()

        // A later explicit shutdown performs the real cleanup.
        harness.worker.shutdown()
        assertThat(harness.observer.shutdownComplete).hasSize(1)
        assertThat(harness.isRegistered()).isFalse()
    }

    // ── Property 8: shutdown stops heartbeat/poll ownership completely ─────

    @Test
    fun `shutdown stops heartbeat and poll ownership completely`() = runBlocking<Unit> {
        val harness = WorkerLifecyclePropertyHarness()
        val heartbeatEntered = CompletableDeferred<Unit>()
        val heartbeatExited = CompletableDeferred<Unit>()
        val pollEntered = CompletableDeferred<Unit>()
        val pollExited = CompletableDeferred<Unit>()
        harness.hooks.onHeartbeat = { blockUntilCancelled(heartbeatEntered, heartbeatExited) }
        harness.catalogHooks.onList = { blockUntilCancelled(pollEntered, pollExited) }

        harness.worker.start()
        withTimeout(10_000) { heartbeatEntered.await() }
        withTimeout(10_000) { pollEntered.await() }

        // Deterministic observation point: the shutdown sequence stops the
        // poller and the heartbeat BEFORE it dispatches onWorkerStopped. So at
        // the instant the stopped event fires, both loops must already be dead
        // (cancelAndJoin semantics). If cancellation were left to the final
        // root.cancel() instead, the loops would still be live here — the
        // defect the cancellation-ownership property must reject. The hook runs
        // synchronously inside the shutdown sequence, so there is no race.
        var heartbeatLiveAtStopped: Boolean? = null
        var pollLiveAtStopped: Boolean? = null
        harness.observer.onWorkerStoppedHook = {
            heartbeatLiveAtStopped = !heartbeatExited.isCompleted
            pollLiveAtStopped = !pollExited.isCompleted
        }

        harness.worker.shutdown()

        assertThat(heartbeatLiveAtStopped)
            .withFailMessage("heartbeat loop must be cancelled and joined before the stopped event fires")
            .isFalse()
        assertThat(pollLiveAtStopped)
            .withFailMessage("poll loop must be cancelled and joined before the stopped event fires")
            .isFalse()
        // Belt-and-braces: both loops joined before shutdown() returns.
        assertThat(heartbeatExited.isCompleted).isTrue()
        assertThat(pollExited.isCompleted).isTrue()
        withTimeout(10_000) { heartbeatExited.await() }
        withTimeout(10_000) { pollExited.await() }
        assertThat(harness.observer.shutdownComplete).hasSize(1)
        assertThat(harness.isRegistered()).isFalse()
    }

    // ── Property 9: stale start contender has zero authority (P1) ─────────

    @Test
    fun `stale start contender cannot reset the winning generation's shutdown state`() = runBlocking<Unit> {
        // Idle-worker setup: NO workflow, NO lease claim, NO recovery. The
        // stale-contender contract is about lifecycle ownership only, so the
        // harness exercises nothing below the poll loop. The winning
        // generation's shutdown is held at the unregister step (a registry
        // hook) instead of a drain, keeping the discriminator inside the
        // lifecycle state machine.
        val hookGate = CompletableDeferred<Unit>()
        val hookEntered = CompletableDeferred<Unit>()
        val unregisterEntered = CompletableDeferred<Unit>()
        val unregisterGate = CompletableDeferred<Unit>()
        val blockFirstStart = java.util.concurrent.atomic.AtomicBoolean(true)

        val leaseStore = InMemoryWorkflowLeaseStore()
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val registrations = java.util.concurrent.atomic.AtomicInteger(0)
        val unregistrations = java.util.concurrent.atomic.AtomicInteger(0)
        val hooks = WorkerLifecyclePropertyHarness.WorkerRegistryHooks()
        val registry = ControllableWorkerRegistry(leaseStore, hooks, registrations, unregistrations)
        val observer = WorkerLifecyclePropertyHarness.RecordingWorkerObserver()
        val controller = WorkerLifecycleController(
            config = workerConfig("stale-contender"),
            leaseStore = registry,
            checkpointStore = checkpointStore,
            checkpointCatalog = checkpointStore,
            stepAttemptStore = checkpointStore,
            workflowBindings = WorkflowBindingRegistry { },
            observability = observer,
            partitionStrategy = ModHashPartitionStrategy(),
        )
        // Gate the FIRST starter (A) at the claim boundary after it observed
        // STOPPED; every later start passes straight through.
        controller.onStartClaimBoundary = {
            if (blockFirstStart.compareAndSet(true, false)) {
                hookEntered.complete(Unit)
                hookGate.await()
            }
        }

        // Starter A observes STOPPED and pauses at the ownership-claim boundary.
        val starterA = launch { controller.start() }
        withTimeout(10_000) { hookEntered.await() }

        // Starter B wins ownership and reaches RUNNING (idle: no checkpoints).
        controller.start()
        assertThat(observer.workerStarted).hasSize(1)

        // Shutdown B begins and is HELD at the unregister step: the shutdown
        // owner has won the shutdown CAS but has NOT yet released ownership.
        hooks.onUnregister = {
            unregisterEntered.complete(Unit)
            unregisterGate.await()
        }
        val shutdownB = launch { controller.shutdown() }
        withTimeout(10_000) { unregisterEntered.await() }
        assertThat(observer.shutdownStarted).hasSize(1)

        // Resume stale A: it must lose the ownership CAS and — critically —
        // must NOT reset the winning generation's shutdown state. Gates are
        // completed in a finally so a RED failure (e.g. stale reset, M21)
        // returns fast instead of stranding B in the held unregister.
        try {
            hookGate.complete(Unit)
            withTimeout(10_000) { starterA.join() }
            assertThat(observer.workerStarted)
                .withFailMessage("stale contender must not become an owner")
                .hasSize(1)
            assertThat(registrations.get()).isEqualTo(1)

            // A second shutdown caller must remain a loser: B's shutdown CAS was
            // never reset, so this call returns immediately instead of starting a
            // second (concurrent) shutdown sequence.
            withTimeout(5_000) {
                val secondShutdown = launch { controller.shutdown() }
                secondShutdown.join()
            }
            assertThat(observer.shutdownStarted)
                .withFailMessage("stale contender must not reset the shutdown CAS: exactly one shutdown owner")
                .hasSize(1)
        } finally {
            // Release the held unregister: exactly one shutdown sequence completes
            // and the shutdown owner releases ownership.
            unregisterGate.complete(Unit)
        }
        withTimeout(10_000) { shutdownB.join() }
        assertThat(observer.shutdownComplete).hasSize(1)
        assertThat(observer.workerStopped).hasSize(1)
        assertThat(unregistrations.get()).isEqualTo(1)
        assertThat(leaseStore.listActiveWorkers()).isEmpty()

        // A fresh generation starts normally after the shutdown completed.
        controller.start()
        assertThat(observer.workerStarted).hasSize(2)
        controller.shutdown()
        assertThat(observer.shutdownComplete).hasSize(2)
    }

    // ── Property 10: aborted start keeps ownership while shutdown drains (P2a) ──

    @Test
    fun `aborted start cannot release ownership while shutdown is still draining`() = runBlocking<Unit> {
        val observer = WorkerLifecyclePropertyHarness.RecordingWorkerObserver()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val registrations = java.util.concurrent.atomic.AtomicInteger(0)
        val unregistrations = java.util.concurrent.atomic.AtomicInteger(0)
        val hooks = WorkerLifecyclePropertyHarness.WorkerRegistryHooks()
        val registry = ControllableWorkerRegistry(leaseStore, hooks, registrations, unregistrations)
        val worker = TramaiWorker(
            config = workerConfig("abort-mid-drain"),
            leaseStore = registry,
            checkpointStore = checkpointStore,
            checkpointCatalog = checkpointStore,
            stepAttemptStore = checkpointStore,
            workflowBindings = WorkflowBindingRegistry { },
            observability = observer,
        )

        // Generation B's startup suspends inside registration.
        val regEntered = CompletableDeferred<Unit>()
        val regGate = CompletableDeferred<Unit>()
        hooks.onRegister = {
            regEntered.complete(Unit)
            regGate.await()
        }
        val startedB = launch { worker.start() }
        withTimeout(10_000) { regEntered.await() }

        // Shutdown B begins. The drain is trivial (B never ran) but the
        // shutdown is HELD at the unregister step — at that point the shutdown
        // owner still owns this generation and has NOT released it.
        val unregisterEntered = CompletableDeferred<Unit>()
        val unregisterGate = CompletableDeferred<Unit>()
        hooks.onUnregister = {
            unregisterEntered.complete(Unit)
            unregisterGate.await()
        }
        val shutdownB = launch { worker.shutdown() }
        withTimeout(10_000) { unregisterEntered.await() }
        assertThat(observer.shutdownStarted).hasSize(1)

        // Resume B's startup: it must abort WITHOUT releasing ownership while
        // the shutdown owner still drains this generation. B's zombie
        // reconciliation also blocks on the held unregister until released.
        regGate.complete(Unit)
        assertThat(observer.workerStarted)
            .withFailMessage("aborted startup must not emit onWorkerStarted")
            .isEmpty()

        // A new start while the shutdown is still draining must remain a
        // guard-level no-op: ownership still belongs to the draining
        // generation, so it must not even claim and register (a mid-drain
        // claim is a second generation root created under the shutdown owner —
        // the revalidation flag alone would mask that, which is why this
        // property asserts the REGISTRATION side effect, not just the started
        // event). The gate is completed in a finally so a RED failure cannot
        // strand B/C in NonCancellable reconciliation and hang the test scope.
        try {
            val cStarter = launch { worker.start() }
            var sawSecondRegistration = false
            for (i in 0 until 400) {
                if (registrations.get() >= 2) {
                    sawSecondRegistration = true
                    break
                }
                delay(5)
            }
            assertThat(sawSecondRegistration)
                .withFailMessage("a new start must not claim/register while the previous shutdown is still draining")
                .isFalse()
            withTimeout(10_000) { cStarter.join() }
            assertThat(observer.workerStarted)
                .withFailMessage("new generation must not start while the previous shutdown is still draining")
                .isEmpty()
        } finally {
            unregisterGate.complete(Unit)
        }

        // B's reconcile and the shutdown's unregister unblock via the finally
        // above: exactly one shutdown sequence ran and released ownership.
        withTimeout(10_000) { startedB.join() }
        withTimeout(10_000) { shutdownB.join() }
        assertThat(observer.shutdownComplete).hasSize(1)
        assertThat(observer.workerStopped).hasSize(1)

        // Ownership was released by the shutdown owner: a fresh generation runs.
        worker.start()
        assertThat(observer.workerStarted).hasSize(1)
        assertThat(leaseStore.listActiveWorkers()).hasSize(1)
        worker.shutdown()
        assertThat(observer.shutdownComplete).hasSize(2)
    }

    // ── Property 11: cancelled startup rolls back (P2b) ───────────────────

    @Test
    fun `cancelled registration rolls the startup back to a retryable stopped state`() = runBlocking<Unit> {
        val harness = WorkerLifecyclePropertyHarness()
        val entered = CompletableDeferred<Unit>()
        val regGate = CompletableDeferred<Unit>()
        harness.hooks.onRegister = {
            entered.complete(Unit)
            regGate.await()
        }

        val cause = kotlinx.coroutines.CancellationException("test-cancel-start")
        var caught: kotlinx.coroutines.CancellationException? = null
        val starter = launch {
            try {
                harness.worker.start()
            } catch (e: kotlinx.coroutines.CancellationException) {
                caught = e
            }
        }
        withTimeout(10_000) { entered.await() }

        // Cancel the start coroutine while it is suspended in registration:
        // the SAME cancellation must escape, after mandatory rollback.
        starter.cancel(cause)
        regGate.complete(Unit)
        withTimeout(10_000) { starter.join() }

        assertThat(caught).withFailMessage("cancellation must propagate, not be swallowed").isNotNull()
        assertThat(caught?.cause)
            .withFailMessage("the same cancellation instance must escape the cancelled start")
            .isSameAs(cause)
        assertThat(harness.observer.workerStarted).isEmpty()
        assertThat(harness.observer.shutdownStarted).isEmpty()
        assertThat(harness.isRegistered())
            .withFailMessage("cancelled startup must not leave a registry row")
            .isFalse()

        // Ownership was rolled back: a subsequent start succeeds.
        harness.worker.start()
        assertThat(harness.observer.workerStarted).hasSize(1)
        assertThat(harness.isRegistered()).isTrue()
        harness.worker.shutdown()
        assertThat(harness.observer.shutdownComplete).hasSize(1)
    }

    // ── Property 12: shutdown in the claim→prepare gap is not lost (M24) ──

    @Test
    fun `shutdown between ownership claim and shutdown-state reset is accepted, never lost`() = runBlocking<Unit> {
        // Idle-worker setup: NO workflow, NO lease claim, NO recovery. The
        // discriminator is purely the lifecycle state machine: generation A
        // completes a full lifecycle (leaving the coordinator's shutdown state
        // "used"), then generation B wins the root but is suspended before the
        // shutdown-state reset. A shutdown of B in that gap must be ACCEPTED —
        // not rejected by a stale shutdown CAS from generation A.
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val registrations = java.util.concurrent.atomic.AtomicInteger(0)
        val unregistrations = java.util.concurrent.atomic.AtomicInteger(0)
        val hooks = WorkerLifecyclePropertyHarness.WorkerRegistryHooks()
        val registry = ControllableWorkerRegistry(leaseStore, hooks, registrations, unregistrations)
        val observer = WorkerLifecyclePropertyHarness.RecordingWorkerObserver()
        val controller = WorkerLifecycleController(
            config = workerConfig("claim-gap-shutdown"),
            leaseStore = registry,
            checkpointStore = checkpointStore,
            checkpointCatalog = checkpointStore,
            stepAttemptStore = checkpointStore,
            workflowBindings = WorkflowBindingRegistry { },
            observability = observer,
            partitionStrategy = ModHashPartitionStrategy(),
        )

        // Generation A: complete start + shutdown. Coordinator shutdown state
        // is now "used" (shutdownStarted=true, shuttingDownGracefully=true)
        // and is only reset by the NEXT start's prepareLifecycleStart().
        controller.start()
        assertThat(observer.workerStarted).hasSize(1)
        controller.shutdown()
        assertThat(observer.shutdownComplete).hasSize(1)
        assertThat(observer.workerStopped).hasSize(1)

        // Generation B: wins the root, pauses BEFORE the shutdown-state reset.
        controller.onLifecycleStateClaimed = {
            entered.complete(Unit)
            gate.await()
        }
        val starterB = launch { controller.start() }
        withTimeout(10_000) { entered.await() }

        // Shutdown B in the gap: must be accepted and must complete the full
        // shutdown sequence — it must NOT be lost to the stale shutdown CAS
        // left by generation A. The gate is completed in a finally so a RED
        // failure (shutdown lost, M24) returns fast instead of stranding B.
        try {
            withTimeout(10_000) { controller.shutdown() }
            assertThat(observer.shutdownStarted)
                .withFailMessage("shutdown in the claim→prepare gap must be accepted, not lost")
                .hasSize(2)
            assertThat(observer.shutdownComplete)
                .withFailMessage("shutdown in the claim→prepare gap must complete")
                .hasSize(2)
            assertThat(observer.workerStopped).hasSize(2)
        } finally {
            gate.complete(Unit)
        }

        // Resume B: it must NOT reach RUNNING (the shutdown already owns the
        // generation) and must not emit onWorkerStarted.
        withTimeout(10_000) { starterB.join() }
        assertThat(observer.workerStarted)
            .withFailMessage("generation B must not reach RUNNING after its shutdown was accepted")
            .hasSize(1)
        assertThat(leaseStore.listActiveWorkers()).isEmpty()
    }

    // ── Property 13: no RUNNING commit after a completed shutdown (M25) ──

    @Test
    fun `startup cannot commit RUNNING after shutdown completed at the commit boundary`() = runBlocking<Unit> {
        // Idle-worker setup. The startup passes every revalidation, then pauses
        // IMMEDIATELY before the RUNNING commit. A shutdown completes fully in
        // that window. Resuming must not emit workerStarted, must not install a
        // hook, must not begin accepting work — the shutdown owns the
        // generation, and the STARTING→RUNNING transition must be atomic.
        val regEntered = CompletableDeferred<Unit>()
        val regGate = CompletableDeferred<Unit>()
        val commitEntered = CompletableDeferred<Unit>()
        val commitGate = CompletableDeferred<Unit>()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val registrations = java.util.concurrent.atomic.AtomicInteger(0)
        val unregistrations = java.util.concurrent.atomic.AtomicInteger(0)
        val hooks = WorkerLifecyclePropertyHarness.WorkerRegistryHooks()
        val registry = ControllableWorkerRegistry(leaseStore, hooks, registrations, unregistrations)
        val observer = WorkerLifecyclePropertyHarness.RecordingWorkerObserver()
        val controller = WorkerLifecycleController(
            config = workerConfig("commit-boundary"),
            leaseStore = registry,
            checkpointStore = checkpointStore,
            checkpointCatalog = checkpointStore,
            stepAttemptStore = checkpointStore,
            workflowBindings = WorkflowBindingRegistry { },
            observability = observer,
            partitionStrategy = ModHashPartitionStrategy(),
        )

        // Generation B: registration completes, revalidation passes, then pause
        // at the RUNNING commit boundary.
        hooks.onRegister = {
            regEntered.complete(Unit)
            regGate.await()
        }
        controller.onRunCommitBoundary = {
            commitEntered.complete(Unit)
            commitGate.await()
        }
        val starterB = launch { controller.start() }
        withTimeout(10_000) { regEntered.await() }
        regGate.complete(Unit)
        withTimeout(10_000) { commitEntered.await() }
        assertThat(observer.workerStarted).isEmpty()

        // Shutdown B completes fully while the startup is parked at the commit
        // boundary: shutdownStarted/Complete, unregister, workerStopped. The
        // commit gate is completed in a finally so a RED failure returns fast
        // instead of stranding B.
        try {
            withTimeout(10_000) { controller.shutdown() }
            assertThat(observer.shutdownComplete).hasSize(1)
            assertThat(observer.workerStopped).hasSize(1)
        } finally {
            commitGate.complete(Unit)
        }

        // Resume B: the commit must FAIL — the generation was shut down.
        withTimeout(10_000) { starterB.join() }
        assertThat(observer.workerStarted)
            .withFailMessage("startup must not commit RUNNING after a completed shutdown")
            .isEmpty()
        assertThat(observer.heartbeats).isEmpty()
        assertThat(leaseStore.listActiveWorkers()).isEmpty()

        // A fresh generation C can still start normally afterwards.
        controller.start()
        assertThat(observer.workerStarted).hasSize(1)
        assertThat(leaseStore.listActiveWorkers()).hasSize(1)
        controller.shutdown()
        assertThat(observer.shutdownComplete).hasSize(2)
        assertThat(leaseStore.listActiveWorkers()).isEmpty()
    }

    // ── Property 14: cleanup never deletes a newer generation's row (M26) ──

    @Test
    fun `failed startup cleanup cannot unregister a newer generation's registry row`() = runBlocking<Unit> {
        // A's registration fails; A's rollback reaches its registry cleanup and
        // pauses BEFORE the delegate unregister. Generation B starts and
        // registers successfully. When A's cleanup resumes it must NOT delete
        // B's row — registry identity is workerId-only, so the cleanup must be
        // ordered (or reserved) so it can never hit a row it did not create.
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val registrations = java.util.concurrent.atomic.AtomicInteger(0)
        val unregistrations = java.util.concurrent.atomic.AtomicInteger(0)
        val hooks = WorkerLifecyclePropertyHarness.WorkerRegistryHooks()
        val registry = ControllableWorkerRegistry(leaseStore, hooks, registrations, unregistrations)
        val observer = WorkerLifecyclePropertyHarness.RecordingWorkerObserver()
        val controller = WorkerLifecycleController(
            config = workerConfig("cleanup-race"),
            leaseStore = registry,
            checkpointStore = checkpointStore,
            checkpointCatalog = checkpointStore,
            stepAttemptStore = checkpointStore,
            workflowBindings = WorkflowBindingRegistry { },
            observability = observer,
            partitionStrategy = ModHashPartitionStrategy(),
        )

        // A's registration fails; A's rollback cleanup pauses at the unregister
        // delegate (the registry hook fires inside the cleanup path). A runs in
        // its own coroutine so the test can drive the gate while A is parked.
        hooks.failNextRegistration = true
        hooks.onUnregister = {
            cleanupEntered.complete(Unit)
            cleanupGate.await()
        }
        val aCompleted = CompletableDeferred<Unit>()
        val starterA = launch {
            runCatching { controller.start() }
            aCompleted.complete(Unit)
        }
        withTimeout(10_000) { cleanupEntered.await() }

        // B attempts to start while A is parked in cleanup. Correct design: A
        // still owns the lifecycle during its cleanup, so B's start is a
        // guard-level no-op and B does NOT register while A is parked. Broken
        // design (cleanup-after-release): B claims, registers and runs while A
        // is parked, and A's late unregister deletes B's row.
        controller.start()

        // Release A's cleanup. A's unregister must never delete a row that a
        // newer generation registered.
        cleanupGate.complete(Unit)
        withTimeout(10_000) { aCompleted.await() }

        // B starts again after A fully released. In the fixed design this is
        // B's real start (the earlier one was a no-op); in the broken design B
        // is already running and this is a no-op. Either way B must be the one
        // generation that is registered and running, with its row intact.
        controller.start()
        assertThat(observer.workerStarted).hasSize(1)
        assertThat(leaseStore.listActiveWorkers())
            .withFailMessage("old-generation cleanup must not delete the new generation's registry row")
            .hasSize(1)
        assertThat(registrations.get()).isEqualTo(1)

        // B shuts down cleanly; A's abort left nothing behind.
        controller.shutdown()
        assertThat(observer.shutdownComplete).hasSize(1)
        assertThat(leaseStore.listActiveWorkers()).isEmpty()
    }

    // ── Property 15: no epilogue (hook/acceptingWork/started) after shutdown
    //    lands post-commit (M27) ─────────────────────────────────────────

    @Test
    fun `startup cannot emit workerStarted or accept work after shutdown at the post-commit epilogue`() = runBlocking<Unit> {
        // Idle-worker setup. B COMMITS RUNNING (the atomic STARTING→RUNNING
        // CAS succeeds), then pauses immediately AFTER the commit but BEFORE
        // the epilogue emits workerStarted / installs the hook / accepts work.
        // A shutdown completes fully in that window. The post-commit re-check
        // must abort the epilogue: no workerStarted after workerStopped, no
        // heartbeat loop, no accepting work — the event order
        // shutdownComplete(B) → workerStarted(B) stays impossible.
        val regEntered = CompletableDeferred<Unit>()
        val regGate = CompletableDeferred<Unit>()
        val commitEntered = CompletableDeferred<Unit>()
        val commitGate = CompletableDeferred<Unit>()
        val leaseStore = InMemoryWorkflowLeaseStore()
        val checkpointStore = InMemoryWorkflowCheckpointStore()
        val registrations = java.util.concurrent.atomic.AtomicInteger(0)
        val unregistrations = java.util.concurrent.atomic.AtomicInteger(0)
        val hooks = WorkerLifecyclePropertyHarness.WorkerRegistryHooks()
        val registry = ControllableWorkerRegistry(leaseStore, hooks, registrations, unregistrations)
        val observer = WorkerLifecyclePropertyHarness.RecordingWorkerObserver()
        val controller = WorkerLifecycleController(
            config = workerConfig("post-commit-epilogue"),
            leaseStore = registry,
            checkpointStore = checkpointStore,
            checkpointCatalog = checkpointStore,
            stepAttemptStore = checkpointStore,
            workflowBindings = WorkflowBindingRegistry { },
            observability = observer,
            partitionStrategy = ModHashPartitionStrategy(),
        )

        // Generation B: registration completes, the RUNNING commit SUCCEEDS,
        // then pause at the post-commit epilogue boundary.
        hooks.onRegister = {
            regEntered.complete(Unit)
            regGate.await()
        }
        controller.onRunCommitted = {
            commitEntered.complete(Unit)
            commitGate.await()
        }
        val starterB = launch { controller.start() }
        withTimeout(10_000) { regEntered.await() }
        regGate.complete(Unit)
        withTimeout(10_000) { commitEntered.await() }
        assertThat(observer.workerStarted).isEmpty()

        // Shutdown B completes fully while B is parked at the post-commit
        // epilogue boundary: shutdownStarted/Complete, unregister,
        // workerStopped. The epilogue gate is completed in a finally so a RED
        // failure (M27) returns fast instead of stranding B.
        try {
            withTimeout(10_000) { controller.shutdown() }
            assertThat(observer.shutdownComplete).hasSize(1)
            assertThat(observer.workerStopped).hasSize(1)
        } finally {
            commitGate.complete(Unit)
        }

        // Resume B: the post-commit re-check must abort the epilogue — no
        // workerStarted, no heartbeat loop, no accepting work.
        withTimeout(10_000) { starterB.join() }
        assertThat(observer.workerStarted)
            .withFailMessage("post-commit shutdown must abort the epilogue: no workerStarted after workerStopped")
            .isEmpty()
        assertThat(observer.heartbeats).isEmpty()
        assertThat(leaseStore.listActiveWorkers()).isEmpty()

        // A fresh generation C can still start normally afterwards.
        controller.start()
        assertThat(observer.workerStarted).hasSize(1)
        assertThat(leaseStore.listActiveWorkers()).hasSize(1)
        controller.shutdown()
        assertThat(observer.shutdownComplete).hasSize(2)
        assertThat(leaseStore.listActiveWorkers()).isEmpty()
    }

    @Test
    fun `generator corpus is deterministic and reaches every semantic category`() = runBlocking<Unit> {
        // Determinism: same seed -> identical action sequence.
        for (seed in 0L until WorkerLifecycleActionGenerator.SEED_COUNT) {
            assertThat(WorkerLifecycleActionGenerator.generate(seed))
                .isEqualTo(WorkerLifecycleActionGenerator.generate(seed))
        }

        // Category reachability computed from model pre-state + action +
        // predicted outcome (never from enum presence alone).
        val reached = mutableSetOf<WorkerLifecycleCategory>()
        for (seed in 0L until WorkerLifecycleActionGenerator.SEED_COUNT) {
            var model = WorkerLifecycleModel.stopped()
            for (action in WorkerLifecycleActionGenerator.generate(seed)) {
                val pre = model
                val outcome = model.apply(action)
                reached += category(pre, action, outcome)
                val next = (outcome as? WorkerLifecycleOutcome.Success)?.next
                    ?: (outcome as WorkerLifecycleOutcome.Failure).next

                // Derived per-transition categories: exactly-once semantics,
                // registry presence, generation monotonicity.
                val startedNewGeneration = next.generation == pre.generation + 1L
                if (startedNewGeneration) {
                    reached += WorkerLifecycleCategory.REGISTRATION_ONCE_PER_GENERATION
                    reached += WorkerLifecycleCategory.WORKER_STARTED_ONCE_PER_GENERATION
                    reached += WorkerLifecycleCategory.REGISTRY_PRESENT_WHILE_RUNNING
                }
                val completedShutdown = next.shutdownCompleteEvents == pre.shutdownCompleteEvents + 1
                if (completedShutdown) {
                    reached += WorkerLifecycleCategory.SHUTDOWN_STARTED_ONCE_PER_GENERATION
                    reached += WorkerLifecycleCategory.SHUTDOWN_COMPLETE_ONCE_PER_GENERATION
                    reached += WorkerLifecycleCategory.WORKER_STOPPED_ONCE_PER_GENERATION
                    reached += WorkerLifecycleCategory.UNREGISTER_ONCE_PER_GRACEFUL_GENERATION
                    reached += WorkerLifecycleCategory.REGISTRY_ABSENT_AFTER_GRACEFUL_STOP
                }
                if (next.phase == WorkerLifecyclePhase.CRASHED) {
                    reached += WorkerLifecycleCategory.REGISTRY_RETAINED_AFTER_CRASH
                }
                reached += WorkerLifecycleCategory.GENERATION_MONOTONIC
                if (next.generation >= 2L) {
                    reached += WorkerLifecycleCategory.MULTI_GENERATION
                }

                assertThat(next.invariantViolation()).withFailMessage("seed $seed invariant").isNull()
                assertThat(next.generation).withFailMessage("seed $seed: generation must be monotonic").isGreaterThanOrEqualTo(pre.generation)
                model = next
            }
        }

        val missing = WorkerLifecycleCategory.entries.filter { it !in reached }
        assertThat(missing)
            .withFailMessage("generator corpus never reaches categories: $missing")
            .isEmpty()
    }

    private fun category(
        pre: WorkerLifecycleModel,
        action: WorkerLifecycleAction,
        outcome: WorkerLifecycleOutcome,
    ): WorkerLifecycleCategory {
        val success = (outcome as? WorkerLifecycleOutcome.Success)?.next
        return when (action) {
            WorkerLifecycleAction.START,
            WorkerLifecycleAction.START_AGAIN,
            -> when {
                pre.phase == WorkerLifecyclePhase.STOPPED && success!!.generation > 1L -> WorkerLifecycleCategory.RESTART_AFTER_SHUTDOWN
                pre.phase == WorkerLifecyclePhase.STOPPED -> WorkerLifecycleCategory.STOPPED_START
                pre.phase == WorkerLifecyclePhase.RUNNING -> WorkerLifecycleCategory.RUNNING_START_IDEMPOTENT
                else -> WorkerLifecycleCategory.CRASHED_START_NOOP
            }

            WorkerLifecycleAction.CRASH -> when {
                pre.phase == WorkerLifecyclePhase.RUNNING -> WorkerLifecycleCategory.RUNNING_CRASH
                else -> WorkerLifecycleCategory.STOPPED_CRASH_NOOP
            }

            WorkerLifecycleAction.CLOSE -> when (pre.phase) {
                WorkerLifecyclePhase.RUNNING -> WorkerLifecycleCategory.CLOSE_FROM_RUNNING
                else -> WorkerLifecycleCategory.CLOSE_FROM_STOPPED
            }

            WorkerLifecycleAction.SHUTDOWN,
            WorkerLifecycleAction.SHUTDOWN_AGAIN,
            WorkerLifecycleAction.SHUTDOWN_AFTER_CRASH,
            -> when {
                pre.phase == WorkerLifecyclePhase.CRASHED && success != null -> WorkerLifecycleCategory.CRASHED_SHUTDOWN_CLEANUP
                pre.phase == WorkerLifecyclePhase.RUNNING && success != null -> WorkerLifecycleCategory.RUNNING_SHUTDOWN
                pre.phase == WorkerLifecyclePhase.STOPPED && pre.shutdownCompleteEvents > 0 -> WorkerLifecycleCategory.REPEATED_SHUTDOWN_NOOP
                pre.phase == WorkerLifecyclePhase.STOPPED -> WorkerLifecycleCategory.STOPPED_SHUTDOWN_NOOP
                else -> WorkerLifecycleCategory.REPEATED_SHUTDOWN_NOOP
            }
        }
    }

    private enum class WorkerLifecycleCategory {
        STOPPED_START,
        RUNNING_START_IDEMPOTENT,
        CRASHED_START_NOOP,
        STOPPED_SHUTDOWN_NOOP,
        RUNNING_SHUTDOWN,
        REPEATED_SHUTDOWN_NOOP,
        RESTART_AFTER_SHUTDOWN,
        MULTI_GENERATION,
        RUNNING_CRASH,
        STOPPED_CRASH_NOOP,
        CRASHED_SHUTDOWN_CLEANUP,
        CLOSE_FROM_RUNNING,
        CLOSE_FROM_STOPPED,
        REGISTRATION_ONCE_PER_GENERATION,
        WORKER_STARTED_ONCE_PER_GENERATION,
        SHUTDOWN_STARTED_ONCE_PER_GENERATION,
        SHUTDOWN_COMPLETE_ONCE_PER_GENERATION,
        WORKER_STOPPED_ONCE_PER_GENERATION,
        UNREGISTER_ONCE_PER_GRACEFUL_GENERATION,
        REGISTRY_PRESENT_WHILE_RUNNING,
        REGISTRY_ABSENT_AFTER_GRACEFUL_STOP,
        REGISTRY_RETAINED_AFTER_CRASH,
        GENERATION_MONOTONIC,
    }

    // ── Local fixtures (mirrored from the existing orchestration tests) ─────

    private class RecordingObserver : TramaiWorkerObserver {
        val workerStarted = java.util.concurrent.CopyOnWriteArrayList<String>()
        val shutdownStarted = java.util.concurrent.CopyOnWriteArrayList<String>()
        val shutdownComplete = java.util.concurrent.CopyOnWriteArrayList<String>()

        override fun onWorkerStarted(workerId: String) {
            workerStarted += workerId
        }

        override fun onShutdownStarted(workerId: String) {
            shutdownStarted += workerId
        }

        override fun onShutdownComplete(workerId: String) {
            shutdownComplete += workerId
        }
    }

    private data class WorkerState(val value: String)

    private object WorkerStateCodec : WorkflowStateCodec<WorkerState> {
        override fun encode(state: WorkerState): String = state.value
        override fun decode(payload: String): WorkerState = WorkerState(payload)
    }

    private fun workerWorkflow(
        name: String,
        configure: WorkflowBuilder<WorkerState>.() -> Unit,
    ): Workflow<WorkerState, String> = workflow<WorkerState>(name, configure = configure).build { it.value }

    private fun workerConfig(workerId: String) = WorkerConfig(
        workerId = workerId,
        poolName = "tests",
        pollIntervalMillis = 20,
        leaseDurationMillis = 5_000,
        drainTimeoutMillis = 60_000,
    )

    private suspend fun seedCheckpoint(
        checkpointStore: WorkflowCheckpointStore,
        workflow: Workflow<WorkerState, String>,
        workflowId: String,
    ) {
        checkpointStore.save(
            checkpoint = WorkflowCheckpoint(
                workflowName = workflow.name,
                workflowId = workflowId,
                nextStepIndex = 0,
                stepExecutions = 0,
                lastCompletedStepName = null,
                statePayload = WorkerStateCodec.encode(WorkerState("start")),
                metadata = workflow.checkpointMetadata(),
            ),
        )
    }
}
