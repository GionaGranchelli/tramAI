package dev.tramai.orchestration

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.identity.RunId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E8: a governed run asks the durable authority once per call into an external step executor, and an ordinary run is
 * not touched.
 *
 * **What a grant here means, and what it does not.** `ExternalStepExecutor` is a third-party extension point, so the
 * permit authorizes *this call into the executor* and cannot observe, count or fence the subprocesses, requests or
 * other outbound work the executor performs inside its own code (§3a). Every assertion below is about the invocation
 * boundary; none of them claims containment, and no test here could prove one.
 *
 * **Determinism.** Ordering comes from a store decorator that commits a cancellation immediately after the n-th
 * granted admission: #1 is the step fence (E11), #2 the external invocation (E8). The resolver counts `create` calls
 * separately from `execute` calls, so "the executor was never even instantiated" is a counter, not an inference.
 */
class WorkflowExternalAdmissionTest {
    @Test
    fun `a plugin step takes a step grant and an external grant`() {
        val executor = CountingExecutor()
        val store = ExtAdmissionStore(InMemoryWorkflowCheckpointStore())

        val result =
            runBlocking {
                externalWorkflow(ExtResolver(executor)).run(
                    ExtState(),
                    extGoverned(),
                    NoOpWorkflowObserver,
                    extPersistence(store),
                )
            }

        assertEquals("done", result.result, "the executor really ran and its result was merged")
        assertEquals(1, executor.executions.get(), "one invocation")
        assertEquals(0, executor.isRegisteredCalls.get(), "production never asks the resolver ahead of the grant")
        assertEquals(2, store.admitCount, "the step and the invocation are two decisions, not one")
        assertEquals(2, store.grantedSequences.distinct().size, "each grant carries its own sequence")
        // Attribution is on the permit and in the refusal, not in the durable request: the store records that a grant
        // was issued, not which effect kind it was for. That is §7's per-effect child record, still unimplemented.
        assertEquals("EXTERNAL:slack", WorkflowExternalPermit("notify", externalEffectKind("slack"), 1L).effectKind)
    }

    @Test
    fun `an ordinary run invokes the executor without asking any authority`() {
        val executor = CountingExecutor()
        val store = ExtAdmissionStore(InMemoryWorkflowCheckpointStore())

        val result =
            runBlocking {
                externalWorkflow(
                    ExtResolver(executor),
                ).run(ExtState(), WorkflowContext(workflowId = "run-1"), NoOpWorkflowObserver, extPersistence(store))
            }

        assertEquals("done", result.result)
        assertEquals(1, executor.executions.get())
        assertEquals(0, store.admitCount, "an ungoverned run must ask no authority at all")
    }

    @Test
    fun `cancellation before the external grant prevents the invocation`() {
        val executor = CountingExecutor()
        // Admission #1 is the step fence; the cancellation commits straight after it, so E8 is refused.
        val store = ExtAdmissionStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 1)

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    externalWorkflow(ExtResolver(executor)).run(
                        ExtState(),
                        extGoverned(),
                        NoOpWorkflowObserver,
                        extPersistence(store),
                    )
                }
            }

        assertEquals(0, executor.creates.get(), "a refused invocation must not even instantiate the executor")
        assertEquals(0, executor.executions.get(), "and certainly must not run it")
        assertTrue(
            error.message!!.contains("cannot dispatch ${externalEffectKind("slack")} for step 'notify'"),
            "the refusal names the effect and the step, and is not sanitised into a step failure: ${error.message}",
        )
    }

    @Test
    fun `a store that cannot admit refuses the invocation`() {
        val executor = CountingExecutor()
        val store = ExtAdmissionStore(InMemoryWorkflowCheckpointStore(), refuseAdmissionsFromAdmit = 2)

        assertFailsWith<WorkflowCancellationUnsupportedException> {
            runBlocking {
                externalWorkflow(ExtResolver(executor)).run(
                    ExtState(),
                    extGoverned(),
                    NoOpWorkflowObserver,
                    extPersistence(store),
                )
            }
        }

        assertEquals(0, executor.creates.get(), "no durable authority means no invocation")
    }

    @Test
    fun `a governed run with no durable session never invokes the executor`() {
        val executor = CountingExecutor()

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    externalWorkflow(ExtResolver(executor)).run(
                        ExtState(),
                        extGoverned(),
                        NoOpWorkflowObserver,
                        persistence = null,
                    )
                }
            }

        assertEquals(0, executor.creates.get(), "fail-closed, not fail-open")
        assertTrue(
            error.message!!.contains("no durable checkpoint authority"),
            "fail-closed reason: ${error.message}",
        )
    }

    @Test
    fun `an unregistered plugin type is refused by the factory after the grant`() {
        val governed = CountingExecutor()
        val governedStore = ExtAdmissionStore(InMemoryWorkflowCheckpointStore())

        // The step runs, E8 is admitted, and the factory is what refuses. The grant therefore corresponds to no
        // dispatch: an admitted attempt that never happened. That is the honest shape, because the alternative is
        // asking a public resolver interface before admission, which runs arbitrary third-party code on a denied
        // invocation.
        assertFailsWith<ExternalStepExecutorNotRegisteredException> {
            runBlocking {
                externalWorkflow(ExtResolver(governed, registered = false))
                    .run(ExtState(), extGoverned(), NoOpWorkflowObserver, extPersistence(governedStore))
            }
        }
        assertEquals(2, governedStore.admitCount, "the step and the external attempt were both admitted")
        assertEquals(1, governed.creates.get(), "the factory was the refusal point")
        assertEquals(0, governed.isRegisteredCalls.get(), "and it was reached without asking the resolver first")
        assertEquals(0, governed.executions.get(), "nothing was dispatched")

        val ordinary = CountingExecutor()
        val ordinaryStore = ExtAdmissionStore(InMemoryWorkflowCheckpointStore())
        assertFailsWith<ExternalStepExecutorNotRegisteredException> {
            runBlocking {
                externalWorkflow(ExtResolver(ordinary, registered = false))
                    .run(
                        ExtState(),
                        WorkflowContext(workflowId = "run-2"),
                        NoOpWorkflowObserver,
                        extPersistence(ordinaryStore),
                    )
            }
        }
        assertEquals(0, ordinaryStore.admitCount, "an ordinary run asks nothing and keeps its existing error")
        assertEquals(1, ordinary.creates.get())
        assertEquals(0, ordinary.isRegisteredCalls.get())
    }

    @Test
    fun `a denied invocation reaches no resolver code at all`() {
        val executor = CountingExecutor()
        // Admission #1 is the step fence; the cancellation commits after it, so E8 is refused before any resolver call.
        val store = ExtAdmissionStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 1)

        assertFailsWith<WorkflowAdmissionRefusedException> {
            runBlocking {
                externalWorkflow(ExtResolver(executor))
                    .run(ExtState(), extGoverned(), NoOpWorkflowObserver, extPersistence(store))
            }
        }

        assertEquals(0, executor.isRegisteredCalls.get(), "a refusal must reach no resolver code, not even a lookup")
        assertEquals(0, executor.creates.get(), "nor the factory")
        assertEquals(0, executor.executions.get(), "nor the executor")
    }

    @Test
    fun `a nested plugin step is fenced by the same authority`() {
        val executor = CountingExecutor()
        val store = ExtAdmissionStore(InMemoryWorkflowCheckpointStore())

        val result =
            runBlocking {
                nestedExternalWorkflow(ExtResolver(executor)).run(
                    ExtState(),
                    extGoverned(),
                    NoOpWorkflowObserver,
                    extPersistence(store),
                )
            }

        assertEquals("done", result.result)
        assertEquals(1, executor.executions.get())
        assertEquals(
            3,
            store.admitCount,
            "branch step, nested plugin step and its external invocation: the nested step inherits the " +
                "frame, so it is fenced too",
        )
    }

    @Test
    fun `cancellation between the nested step and its invocation prevents it`() {
        val executor = CountingExecutor()
        // #1 branch step, #2 nested plugin step, #3 its external invocation: cancel after the nested step's own fence.
        val store = ExtAdmissionStore(InMemoryWorkflowCheckpointStore(), cancelAfterAdmit = 2)

        val error =
            assertFailsWith<WorkflowAdmissionRefusedException> {
                runBlocking {
                    nestedExternalWorkflow(
                        ExtResolver(executor),
                    ).run(ExtState(), extGoverned(), NoOpWorkflowObserver, extPersistence(store))
                }
            }

        assertEquals(0, executor.creates.get(), "the nested step was admitted, its effect was not")
        assertTrue(
            error.message!!.contains(externalEffectKind("slack")),
            "the refusal names the effect: ${error.message}",
        )
    }

    @Test
    fun `one external permit authorizes one invocation`() {
        val permit =
            WorkflowExternalPermit(
                stepName = "notify",
                effectKind = externalEffectKind("slack"),
                admissionSequence = 7L,
            )

        assertEquals("EXTERNAL:slack", permit.effectKind)
        assertTrue(permit.tryConsume(), "the first consumption authorizes one dispatch")
        val reuse = assertFailsWith<IllegalStateException> { permit.tryConsume() }
        assertTrue(
            reuse.message!!.contains("already consumed"),
            "reuse must name the programming error: ${reuse.message}",
        )
        assertTrue(
            permit::class != WorkflowProcessPermit("notify", PROCESS_EFFECT_KIND, 8L)::class,
            "an external permit must not be presentable as a process permit",
        )
    }

    @Test
    fun `the external dispatch site asks its own capability once`() {
        val sources = extMainSourceFiles()
        val sites = sources.filter { (_, text) -> text.contains(".admitExternalStep(") }

        assertEquals(
            listOf("WorkflowStepExecution.kt"),
            sites.map { (file, _) -> file.fileName.toString() },
            "E8 is admitted at the plugin step dispatch site and nowhere else",
        )
        assertEquals(
            1,
            sites
                .single()
                .second
                .windowed(".admitExternalStep(".length)
                .count { it == ".admitExternalStep(" },
            "one grant per invocation",
        )
        val creators =
            sources.filter { (name, text) ->
                name.fileName.toString() != "WorkflowStepExecution.kt" && text.contains("executorResolver.create(")
            }
        assertEquals(
            emptyList(),
            creators.map { (file, _) -> file.fileName.toString() },
            "one production dispatch site",
        )
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private fun externalWorkflow(resolver: ExternalStepExecutorResolver): Workflow<ExtState, ExtState> =
        Workflow(
            name = "checkout",
            definitionVersion = "1",
            stateType = typeOf<ExtState>(),
            resultType = typeOf<ExtState>(),
            schedule = null,
            steps =
                listOf(
                    PluginWorkflowStep(
                        name = "notify",
                        type = "slack",
                        config = emptyMap(),
                        merge = { state, _result, _context -> state.copy(result = "done") },
                    ),
                ),
            resultSelector = { it },
            stopPolicy = StopPolicy(),
            clock = Clock.systemUTC(),
            externalStepExecutorResolver = resolver,
        )

    private fun nestedExternalWorkflow(resolver: ExternalStepExecutorResolver): Workflow<ExtState, ExtState> =
        Workflow(
            name = "checkout",
            definitionVersion = "1",
            stateType = typeOf<ExtState>(),
            resultType = typeOf<ExtState>(),
            schedule = null,
            steps =
                listOf(
                    BranchWorkflowStep(
                        name = "route",
                        select = { "go" },
                        branches =
                            mapOf(
                                "go" to
                                    listOf(
                                        PluginWorkflowStep(
                                            name = "notify",
                                            type = "slack",
                                            config = emptyMap(),
                                            merge = { state, _result, _context -> state.copy(result = "done") },
                                        ),
                                    ),
                            ),
                        defaultSteps = null,
                    ),
                ),
            resultSelector = { it },
            stopPolicy = StopPolicy(),
            clock = Clock.systemUTC(),
            externalStepExecutorResolver = resolver,
        )

    private fun extPersistence(store: WorkflowCheckpointStore): WorkflowPersistence<ExtState> =
        WorkflowPersistence(checkpointStore = store, stateCodec = ExtStateCodec)

    private fun extGoverned(): GovernedRun = GovernedRun(WorkflowContext(workflowId = "run-1"), EXT_GOVERNED)

    // A plugin type whose every resolver call is counted. `registered = false` makes create() behave as the real
    // resolver does for an unknown type, so the "unregistered" path is exercised without pretending production code
    // may ask the resolver ahead of time.
    private class CountingExecutor {
        val isRegisteredCalls = AtomicInteger(0)
        val creates = AtomicInteger(0)
        val executions = AtomicInteger(0)
    }

    private class ExtResolver(
        private val counter: CountingExecutor,
        private val registered: Boolean = true,
    ) : ExternalStepExecutorResolver {
        override fun isRegistered(typeId: String): Boolean {
            counter.isRegisteredCalls.incrementAndGet()
            return registered && typeId == "slack"
        }

        override fun registeredTypeIds(): Set<String> = if (registered) setOf("slack") else emptySet()

        override fun create(typeId: String): ExternalStepExecutor {
            counter.creates.incrementAndGet()
            if (!registered || typeId != "slack") {
                throw ExternalStepExecutorNotRegisteredException(typeId)
            }
            return ExternalStepExecutor { spec ->
                counter.executions.incrementAndGet()
                spec + ("status" to "ok")
            }
        }
    }
}

private data class ExtState(
    val result: String? = null,
)

private object ExtStateCodec : WorkflowStateCodec<ExtState> {
    override fun encode(state: ExtState): String = state.result.orEmpty()

    override fun decode(payload: String): ExtState = ExtState(result = payload)
}

private suspend fun extCancelRun(store: WorkflowCheckpointStore) {
    val current = store.load("checkout", "run-1") ?: return
    store.cancel(
        WorkflowCancellationRequest(
            workflowName = "checkout",
            workflowId = current.workflowId,
            expectedRevision = current.revision,
            expectedGeneration = current.checkpointGeneration,
            actor = "operator",
            reason = "stopped",
            cancelledAtEpochMillis = 0L,
        ),
    )
}

/**
 * Decorates the real store to force an admission ordering and to record what the runtime asked for.
 *
 * [cancelAfterAdmit] commits a cancellation immediately after that granted admission, which is how "the step was
 * admitted but the effect was not" becomes deterministic instead of a race.
 */
private class ExtAdmissionStore(
    private val delegate: WorkflowCheckpointStore,
    private val cancelAfterAdmit: Int? = null,
    private val refuseAdmissionsFromAdmit: Int? = null,
) : WorkflowCheckpointStore by delegate {
    private val mutex = Mutex()
    private var attempts = 0
    private val granted = mutableListOf<Long>()

    val admitCount: Int
        get() = attempts

    val grantedSequences: List<Long>
        get() = granted

    override suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission =
        mutex.withLock {
            attempts += 1
            if (refuseAdmissionsFromAdmit != null && attempts >= refuseAdmissionsFromAdmit) {
                throw WorkflowCancellationUnsupportedException(
                    "${delegate::class.simpleName} cannot admit in this test",
                )
            }
            val admission = delegate.admit(request)
            if (admission.admitted) {
                granted += admission.sequence
                if (cancelAfterAdmit == attempts) {
                    extCancelRun(delegate)
                }
            }
            admission
        }
}

private val EXT_GOVERNED =
    GovernedRunIdentity(
        deployment =
            WorkloadDeploymentIdentity(
                workloadId = WorkloadId("checkout"),
                configuration = WorkloadConfigurationIdentity(ConfigurationId("conf"), ConfigurationVersion("1")),
                environmentId = EnvironmentId("test"),
                deploymentId = DeploymentId("dep"),
            ),
        runId = RunId("run-1"),
    )

/** Every orchestration main source file, keyed by path, for the dispatch-site assertions. */
private fun extMainSourceFiles(): List<Pair<Path, String>> {
    val candidates =
        listOf(
            Path.of("src/main/kotlin/dev/tramai/orchestration"),
            Path.of("tramai-orchestration/src/main/kotlin/dev/tramai/orchestration"),
        )
    val root =
        candidates.firstOrNull { Files.isDirectory(it) }
            ?: throw AssertionError("none of the orchestration source roots exist: $candidates")
    return Files.list(root).use { stream ->
        stream
            .filter { it.fileName.toString().endsWith(".kt") }
            .map { it to Files.readString(it) }
            .toList()
    }
}
