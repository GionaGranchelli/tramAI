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

    // ── Coverage guard ─────────────────────────────────────────────────────

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
