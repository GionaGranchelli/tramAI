package dev.tramai.orchestration

import kotlin.test.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * Instance-scoped, type-safe workflow binding registry — Epic 4.3.
 *
 * Covers the registration matrix: runtime isolation, version coexistence,
 * deterministic conflict rejection, no implicit registration, and the worker
 * resolution contract.
 */
class WorkflowBindingRegistryTest {

    // --- 1. Runtime isolation ---

    @Test
    fun `two registries with the same workflow name are fully independent`() {
        val workflowA = workflow<OrdersState>("orders", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":a") }
        }.build { it.value }
        val workflowB = workflow<OrdersState>("orders", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":b") }
        }.build { it.value }

        val registryA = WorkflowBindingRegistry {
            bind(workflowA, WorkflowPersistence(checkpointStore = InMemoryWorkflowCheckpointStore(), stateCodec = OrdersCodec))
        }
        val registryB = WorkflowBindingRegistry {
            bind(workflowB, WorkflowPersistence(checkpointStore = InMemoryWorkflowCheckpointStore(), stateCodec = OrdersCodec))
        }

        val resolvedA = registryA.resolve("orders", "v1")
        val resolvedB = registryB.resolve("orders", "v1")
        assertThat(resolvedA).isNotNull()
        assertThat(resolvedB).isNotNull()
        assertThat(resolvedA!!.erased.workflow).isSameAs(workflowA)
        assertThat(resolvedB!!.erased.workflow).isSameAs(workflowB)
    }

    // --- 3. No implicit registration ---

    @Test
    fun `running a workflow with persistence does not register it anywhere`() {
        val store = InMemoryWorkflowCheckpointStore()
        val workflow = workflow<OrdersState>("implicit", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":x") }
        }.build { it.value }

        runBlocking {
            workflow.run(
                initialState = OrdersState("start"),
                persistence = WorkflowPersistence(checkpointStore = store, stateCodec = OrdersCodec),
            )
        }

        // A fresh worker with an empty registry must not see the workflow merely
        // because it was executed somewhere with persistence.
        val emptyRegistry = WorkflowBindingRegistry {}
        assertThat(emptyRegistry.resolve("implicit", "v1")).isNull()
    }

    // --- 4. Same name, multiple versions ---

    @Test
    fun `same workflow name can be bound under multiple definition versions`() {
        val v1 = workflow<OrdersState>("orders", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":v1") }
        }.build { it.value }
        val v2 = workflow<OrdersState>("orders", definitionVersion = "v2") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":v2") }
        }.build { it.value }

        val registry = WorkflowBindingRegistry {
            bind(v1, WorkflowPersistence(checkpointStore = InMemoryWorkflowCheckpointStore(), stateCodec = OrdersCodec))
            bind(v2, WorkflowPersistence(checkpointStore = InMemoryWorkflowCheckpointStore(), stateCodec = OrdersCodec))
        }

        assertThat(registry.resolve("orders", "v1")!!.erased.workflow).isSameAs(v1)
        assertThat(registry.resolve("orders", "v2")!!.erased.workflow).isSameAs(v2)
    }

    // --- 5. Type conflict ---

    @Test
    fun `same name and version with different state types is rejected`() {
        val workflowA = workflow<OrdersState>("orders", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":a") }
        }.build { it.value }
        val workflowB = workflow<OtherState>("orders", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":b") }
        }.build { it.value }

        assertThatThrownBy {
            WorkflowBindingRegistry {
                bind(workflowA, WorkflowPersistence(checkpointStore = InMemoryWorkflowCheckpointStore(), stateCodec = OrdersCodec))
                bind(workflowB, WorkflowPersistence(checkpointStore = InMemoryWorkflowCheckpointStore(), stateCodec = OtherCodec))
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("orders")
            .hasMessageContaining("state/result types")
    }

    // --- 6. Duplicate registration ---

    @Test
    fun `binding the same workflow twice fails deterministically`() {
        val workflow = workflow<OrdersState>("orders", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":x") }
        }.build { it.value }

        assertThatThrownBy {
            WorkflowBindingRegistry {
                bind(workflow, WorkflowPersistence(checkpointStore = InMemoryWorkflowCheckpointStore(), stateCodec = OrdersCodec))
                bind(workflow, WorkflowPersistence(checkpointStore = InMemoryWorkflowCheckpointStore(), stateCodec = OrdersCodec))
            }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("registered more than once")
    }

    // --- 7. Definition conflict ---

    @Test
    fun `same identity but different workflow definition is rejected`() {
        val workflowA = workflow<OrdersState>("orders", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":a") }
        }.build { it.value }
        val workflowB = workflow<OrdersState>("orders", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":b") }
            localStep("extra") { state, _ -> state.copy(value = state.value + ":extra") }
        }.build { it.value }

        assertThatThrownBy {
            WorkflowBindingRegistry {
                bind(workflowA, WorkflowPersistence(checkpointStore = InMemoryWorkflowCheckpointStore(), stateCodec = OrdersCodec))
                bind(workflowB, WorkflowPersistence(checkpointStore = InMemoryWorkflowCheckpointStore(), stateCodec = OrdersCodec))
            }
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("different workflow definition")
    }

    // --- 8. Worker execution happy path through registry ---

    @Test
    fun `worker executes a checkpoint through the registry`() {
        val store = InMemoryWorkflowCheckpointStore()
        val workflow = workflow<OrdersState>("orders", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":done") }
        }.build { it.value }
        runBlocking {
            store.save(
                checkpoint = WorkflowCheckpoint(
                    workflowName = "orders",
                    workflowId = "orders-1",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = OrdersCodec.encode(OrdersState("start")),
                    metadata = workflow.checkpointMetadata(),
                ),
            )
        }

        val worker = TramaiWorker(
            config = WorkerConfig(workerId = "w", poolName = "tests", pollIntervalMillis = 20, leaseDurationMillis = 200, drainTimeoutMillis = 1_000),
            leaseStore = InMemoryWorkflowLeaseStore(),
            checkpointStore = store,
            workflowBindings = WorkflowBindingRegistry {
                bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = OrdersCodec))
            },
        )
        runBlocking {
            worker.start()
            try {
                withTimeout(10_000) {
                    while (store.load("orders", "orders-1") != null) delay(10)
                }
            } finally {
                worker.shutdown()
            }
        }
        assertThat(runBlocking { store.listStepAttempts("orders-1") }).isNotEmpty()
    }

    // --- 9. Unknown workflow: lease released, nothing executed ---

    @Test
    fun `worker skips a checkpoint whose workflow is not in the registry`() {
        val store = InMemoryWorkflowCheckpointStore()
        runBlocking {
            store.save(
                checkpoint = WorkflowCheckpoint(
                    workflowName = "unknown",
                    workflowId = "unknown-1",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = OrdersCodec.encode(OrdersState("start")),
                    metadata = mapOf(WORKFLOW_DEFINITION_VERSION_METADATA_KEY to "v1"),
                ),
            )
        }
        val leaseStore = InMemoryWorkflowLeaseStore()
        val worker = TramaiWorker(
            config = WorkerConfig(workerId = "w", poolName = "tests", pollIntervalMillis = 20, leaseDurationMillis = 200, drainTimeoutMillis = 1_000),
            leaseStore = leaseStore,
            checkpointStore = store,
            workflowBindings = WorkflowBindingRegistry {
                bind(
                    workflow<OrdersState>("other", definitionVersion = "v1") {
                        localStep("mark") { state, _ -> state.copy(value = state.value + ":x") }
                    }.build { it.value },
                    WorkflowPersistence(checkpointStore = store, stateCodec = OrdersCodec),
                )
            },
        )
        runBlocking {
            worker.start()
            delay(300)
            worker.shutdown()
        }
        // Checkpoint survives, no attempt was recorded, no lease is held.
        assertThat(runBlocking { store.load("unknown", "unknown-1") }).isNotNull()
        assertThat(runBlocking { store.listStepAttempts("unknown-1") }).isEmpty()
        assertThat(runBlocking { leaseStore.currentLease("unknown", "unknown-1") }).isNull()
    }

    // --- 10. Version-mismatched checkpoint is skipped, matching definition version ---

    @Test
    fun `checkpoint of an unbound version is skipped`() {
        val store = InMemoryWorkflowCheckpointStore()
        val workflow = workflow<OrdersState>("orders", definitionVersion = "v2") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":v2") }
        }.build { it.value }
        runBlocking {
            store.save(
                checkpoint = WorkflowCheckpoint(
                    workflowName = "orders",
                    workflowId = "orders-old",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = OrdersCodec.encode(OrdersState("start")),
                    metadata = mapOf(WORKFLOW_DEFINITION_VERSION_METADATA_KEY to "v1"),
                ),
            )
        }
        val worker = TramaiWorker(
            config = WorkerConfig(workerId = "w", poolName = "tests", pollIntervalMillis = 20, leaseDurationMillis = 200, drainTimeoutMillis = 1_000),
            leaseStore = InMemoryWorkflowLeaseStore(),
            checkpointStore = store,
            workflowBindings = WorkflowBindingRegistry {
                bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = OrdersCodec))
            },
        )
        runBlocking {
            worker.start()
            delay(300)
            worker.shutdown()
        }
        assertThat(runBlocking { store.load("orders", "orders-old") }).isNotNull()
        assertThat(runBlocking { store.listStepAttempts("orders-old") }).isEmpty()
    }

    @Test
    fun `checkpoint missing definition version metadata fails diagnostically instead of being skipped`() {
        // Absent version metadata means no worker can ever route the checkpoint.
        // Unlike an unbound version (which another worker may implement), it must
        // surface as a visible failure rather than a silent poll/release loop.
        val store = InMemoryWorkflowCheckpointStore()
        val workflow = workflow<OrdersState>("orders", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":a") }
        }.build { it.value }
        runBlocking {
            store.save(
                checkpoint = WorkflowCheckpoint(
                    workflowName = "orders",
                    workflowId = "c-1",
                    nextStepIndex = 0,
                    stepExecutions = 0,
                    lastCompletedStepName = null,
                    statePayload = OrdersCodec.encode(OrdersState("start")),
                    metadata = emptyMap(),
                ),
            )
        }
        val worker = TramaiWorker(
            config = WorkerConfig(workerId = "w", poolName = "tests", pollIntervalMillis = 20, leaseDurationMillis = 200, drainTimeoutMillis = 1_000),
            leaseStore = InMemoryWorkflowLeaseStore(),
            checkpointStore = store,
            workflowBindings = WorkflowBindingRegistry {
                bind(workflow, WorkflowPersistence(checkpointStore = store, stateCodec = OrdersCodec))
            },
        )
        runBlocking {
            worker.start()
            try {
                withTimeout(10_000) {
                    while (worker.latestFailure("c-1") == null) delay(10)
                }
            } finally {
                worker.shutdown()
            }
        }
        assertThat(worker.latestFailure("c-1"))
            .isInstanceOf(WorkflowResumeException::class.java)
            .hasMessageContaining("missing required workflow definition metadata")
        // No step ever ran: the checkpoint is untouched, waiting for diagnosis.
        assertThat(runBlocking { store.load("orders", "c-1") }).isNotNull()
        assertThat(runBlocking { store.listStepAttempts("c-1") }).isEmpty()
    }

    // --- 11. Concurrent isolation: two workers, same names, independent registries ---

    @Test
    fun `two workers with the same workflow name operate without interference`() {
        val storeA = InMemoryWorkflowCheckpointStore()
        val storeB = InMemoryWorkflowCheckpointStore()
        val workflowA = workflow<OrdersState>("orders", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":a") }
        }.build { it.value }
        val workflowB = workflow<OrdersState>("orders", definitionVersion = "v1") {
            localStep("mark") { state, _ -> state.copy(value = state.value + ":b") }
        }.build { it.value }

        runBlocking {
            storeA.save(
                WorkflowCheckpoint(
                    workflowName = "orders", workflowId = "a-1", nextStepIndex = 0, stepExecutions = 0,
                    lastCompletedStepName = null, statePayload = OrdersCodec.encode(OrdersState("start")),
                    metadata = workflowA.checkpointMetadata(),
                ),
            )
            storeB.save(
                WorkflowCheckpoint(
                    workflowName = "orders", workflowId = "b-1", nextStepIndex = 0, stepExecutions = 0,
                    lastCompletedStepName = null, statePayload = OrdersCodec.encode(OrdersState("start")),
                    metadata = workflowB.checkpointMetadata(),
                ),
            )
        }

        val workerA = TramaiWorker(
            config = WorkerConfig(workerId = "w-a", poolName = "tests", pollIntervalMillis = 20, leaseDurationMillis = 200, drainTimeoutMillis = 1_000),
            leaseStore = InMemoryWorkflowLeaseStore(),
            checkpointStore = storeA,
            workflowBindings = WorkflowBindingRegistry {
                bind(workflowA, WorkflowPersistence(checkpointStore = storeA, stateCodec = OrdersCodec, deleteCheckpointOnCompletion = false))
            },
        )
        val workerB = TramaiWorker(
            config = WorkerConfig(workerId = "w-b", poolName = "tests", pollIntervalMillis = 20, leaseDurationMillis = 200, drainTimeoutMillis = 1_000),
            leaseStore = InMemoryWorkflowLeaseStore(),
            checkpointStore = storeB,
            workflowBindings = WorkflowBindingRegistry {
                bind(workflowB, WorkflowPersistence(checkpointStore = storeB, stateCodec = OrdersCodec, deleteCheckpointOnCompletion = false))
            },
        )
        runBlocking {
            workerA.start()
            workerB.start()
            try {
                withTimeout(10_000) {
                    // deleteCheckpointOnCompletion=false keeps the final checkpoint, so
                    // its statePayload proves which definition actually executed.
                    while (storeA.load("orders", "a-1")?.statePayload != "start:a" ||
                        storeB.load("orders", "b-1")?.statePayload != "start:b"
                    ) {
                        delay(10)
                    }
                }
            } finally {
                workerA.shutdown()
                workerB.shutdown()
            }
        }
        // Each worker executed only its own binding's definition: the retained
        // checkpoint state carries the distinguishing step output (:a vs :b), which
        // would fail if worker A ever resolved worker B's workflow definition.
        val attemptA = runBlocking { storeA.listStepAttempts("a-1") }.single()
        val attemptB = runBlocking { storeB.listStepAttempts("b-1") }.single()
        assertThat(attemptA.workerId).isEqualTo("w-a")
        assertThat(attemptB.workerId).isEqualTo("w-b")
        assertThat(runBlocking { storeA.load("orders", "a-1") }?.statePayload).isEqualTo("start:a")
        assertThat(runBlocking { storeB.load("orders", "b-1") }?.statePayload).isEqualTo("start:b")
    }

    private data class OrdersState(val value: String)

    private object OrdersCodec : WorkflowStateCodec<OrdersState> {
        override fun encode(state: OrdersState): String = state.value
        override fun decode(payload: String): OrdersState = OrdersState(payload)
    }

    private data class OtherState(val value: String)

    private object OtherCodec : WorkflowStateCodec<OtherState> {
        override fun encode(state: OtherState): String = state.value
        override fun decode(payload: String): OtherState = OtherState(payload)
    }
}
