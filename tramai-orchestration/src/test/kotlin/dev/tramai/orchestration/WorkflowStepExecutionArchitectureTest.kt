package dev.tramai.orchestration

import kotlinx.coroutines.runBlocking
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation

private fun sealedStepClasses(): List<Class<*>> =
    InternalWorkflowStep::class.java.permittedSubclasses?.toList() ?: emptyList()

private fun stepClassResource(stepClass: Class<*>): java.io.InputStream? =
    stepClass.getResourceAsStream("/${stepClass.name.replace('.', '/')}.class")

/**
 * Inspects the compiled [getSuspensionMode] body of a step class: reports
 * true only when the getter directly returns the [WorkflowStepSuspensionMode.TOP_LEVEL_CHECKPOINT]
 * constant (GETSTATIC), i.e. the class declares the capability itself rather
 * than inheriting the interface default. Never instantiates the step, so a
 * class cannot silently disappear from the assertion.
 */
private fun stepClassDeclaresTopLevelCheckpoint(stepClass: Class<*>): Boolean {
    var declares = false
    ClassReader(stepClassResource(stepClass)).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (name != "getSuspensionMode") return super.visitMethod(access, name, descriptor, signature, exceptions)
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, descriptor: String) {
                    if (opcode == Opcodes.GETSTATIC &&
                        owner == "dev/tramai/orchestration/WorkflowStepSuspensionMode" &&
                        fieldName == "TOP_LEVEL_CHECKPOINT"
                    ) {
                        declares = true
                    }
                }
            }
        }
    }, 0)
    return declares
}

/**
 * Collects every class the compiled [Workflow.executeStep] (and its suspend
 * state-machine helper classes) references. If central concrete dispatch is
 * reintroduced, the step classes appear here and the guard fails.
 */
private fun executeStepReferencedClasses(): Set<String> {
    val referenced = linkedSetOf<String>()
    val workflowClass = Workflow::class.java
    val targets = buildList {
        add(workflowClass)
        workflowClass.declaredClasses.forEach { nested ->
            if (nested.simpleName.startsWith("executeStep")) add(nested)
        }
    }
    targets.forEach { target ->
        val resource = target.getResourceAsStream("/${target.name.replace('.', '/')}.class")
            ?: return@forEach
        ClassReader(resource).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitTypeInsn(opcode: Int, type: String) {
                        referenced += type
                    }

                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        methodName: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        referenced += owner
                    }
                }
            }
        }, 0)
    }
    return referenced
}

/**
 * Architectural assertions for Epic 4.1: runtime step execution is
 * polymorphic through [InternalWorkflowStep.execute], and
 * [Workflow]'s step wrapper contains no concrete-step dispatch.
 */
class WorkflowStepExecutionArchitectureTest {

    @Test
    fun `every built-in step implements the polymorphic execute contract`() {
        val missing = sealedStepClasses().filter { stepClass ->
            val execute = stepClass
                .declaredMethods
                .firstOrNull { it.name == "execute" && it.parameterCount == 2 }
            execute == null ||
                execute.parameterTypes[0] != WorkflowStepExecutionRequest::class.java ||
                execute.parameterTypes[1] != Continuation::class.java
        }
        assertThat(missing.map { it.simpleName }).isEmpty()
    }

    @Test
    fun `only delay declares TOP_LEVEL_CHECKPOINT`() {
        val declaring = sealedStepClasses()
            .filter { stepClassDeclaresTopLevelCheckpoint(it) }
            .map { it.simpleName }
        assertThat(declaring).containsExactly("DelayWorkflowStep")
    }

    @Test
    fun `executeStep has no concrete-step dispatch`() {
        val stepClassNames = sealedStepClasses()
            .map { it.name.replace('.', '/') }
            .toSet()
        val referenced = executeStepReferencedClasses()
        val dispatchRefs = referenced.filter { it in stepClassNames }
        assertThat(dispatchRefs).isEmpty()
    }

    @Test
    fun `observer ordering is start then completed for successful steps`() {
        val observer = ArchRecordingWorkflowObserver()
        val workflow = workflow<WorkflowState>("obs-order") {
            localStep("one") { state, _ -> state.copy(log = state.log + "one:") }
            localStep("two") { state, _ -> state.copy(log = state.log + "two:") }
        }.build(clock = Clock.systemUTC()) { it.log }

        runBlocking { workflow.run(initialState = WorkflowState(""), observer = observer) }

        assertThat(observer.startedSteps).containsExactly("one", "two")
        assertThat(observer.completedSteps).containsExactly("one", "two")
    }

    @Test
    fun `observer ordering is start then failed for failing steps`() {
        val observer = ArchRecordingWorkflowObserver()
        val workflow = workflow<WorkflowState>("obs-fail") {
            localStep("ok") { state, _ -> state.copy(log = state.log + "ok:") }
            gateStep("gate") { _, _ ->
                throw WorkflowGateRejectedException("Workflow gate 'gate' rejected execution: no")
            }
        }.build(clock = Clock.systemUTC()) { it.log }

        runBlocking {
            runCatching { workflow.run(initialState = WorkflowState(""), observer = observer) }
        }

        assertThat(observer.startedSteps).containsExactly("ok", "gate")
        assertThat(observer.completedSteps).containsExactly("ok")
        assertThat(observer.failedSteps).containsExactly("gate")
    }

    @Test
    fun `cancellation escapes without sanitisation or onStepFailed`() {
        val observer = ArchRecordingWorkflowObserver()
        val workflow = workflow<WorkflowState>("cancel") {
            localStep("cancel-me") { _, _ ->
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }.build(clock = Clock.systemUTC()) { it.log }

        val error = runBlocking {
            runCatching { workflow.run(initialState = WorkflowState(""), observer = observer) }
                .exceptionOrNull()
        }

        assertThat(error).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        assertThat(error).hasMessage("cancelled")
        assertThat(observer.failedSteps).isEmpty()
        assertThat(observer.completedSteps).isEmpty()
    }

    @Test
    fun `suspended delay emits started but not completed until actual resume`() {
        val clock = ArchMutableClock(Instant.parse("2026-05-03T09:00:00Z"))
        val store = InMemoryWorkflowCheckpointStore()
        val delayScheduler = ArchRecordingDelayWakeupScheduler()
        val persistence = WorkflowPersistence(
            checkpointStore = store,
            stateCodec = ArchResumeStateCodec,
            delayWakeupScheduler = delayScheduler,
        )
        val context = WorkflowContext(workflowId = "arch-delay-1")
        val observer = ArchRecordingWorkflowObserver()
        val workflow = workflow<ArchResumeState>("arch-delay") {
            delayStep("pause", 5, TimeUnit.SECONDS)
            localStep("after") { state, _ -> state.copy(finalAnswer = "done") }
        }.build(clock = clock) { it.finalAnswer }

        val suspended = runBlocking {
            runCatching {
                workflow.run(
                    initialState = ArchResumeState(request = "x"),
                    context = context,
                    observer = observer,
                    persistence = persistence,
                )
            }.exceptionOrNull()
        }
        assertThat(suspended).isInstanceOf(WorkflowSuspendedException::class.java)
        assertThat(observer.startedSteps).containsExactly("pause")
        assertThat(observer.completedSteps).isEmpty()

        // Resume after the delay elapses: completion is only emitted now.
        clock.instant = Instant.parse("2026-05-03T09:00:06Z")
        val result = runBlocking {
            workflow.resume(
                context = context,
                observer = observer,
                persistence = persistence,
            )
        }
        assertThat(result).isEqualTo("done")
        assertThat(observer.completedSteps).containsExactly("pause", "after")
    }

    @Test
    fun `nested delay is rejected during build`() {
        val error = runCatching {
            workflow<WorkflowState>("nested-delay") {
                branchStep("branch", select = { "a" }) {
                    branch("a") {
                        delayStep("pause", 5, TimeUnit.SECONDS)
                    }
                    default {
                        localStep("other") { state, _ -> state }
                    }
                }
            }.build(clock = Clock.systemUTC()) { it.log }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessage(
            "Workflow 'nested-delay' step 'pause' uses TOP_LEVEL_CHECKPOINT suspension inside a nested branch. Checkpoint-suspending steps must be top-level.",
        )
    }

    @Test
    fun `deeply nested delay is rejected during build`() {
        val error = runCatching {
            workflow<WorkflowState>("deep-nested-delay") {
                branchStep("outer", select = { "a" }) {
                    branch("a") {
                        branchStep("inner", select = { "x" }) {
                            branch("x") {
                                delayStep("pause", 5, TimeUnit.SECONDS)
                            }
                            default {
                                localStep("other") { state, _ -> state }
                            }
                        }
                    }
                    default {
                        localStep("outer-other") { state, _ -> state }
                    }
                }
            }.build(clock = Clock.systemUTC()) { it.log }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessage(
            "Workflow 'deep-nested-delay' step 'pause' uses TOP_LEVEL_CHECKPOINT suspension inside a nested branch. Checkpoint-suspending steps must be top-level.",
        )
    }

    @Test
    fun `top-level delay remains valid`() {
        val workflow = workflow<WorkflowState>("top-delay") {
            delayStep("pause", 0, TimeUnit.SECONDS)
        }.build(clock = Clock.systemUTC()) { it.log }

        assertThat(runBlocking { workflow.run(initialState = WorkflowState("")) }).isEqualTo("")
    }

    @Test
    fun `branch execution routes nested steps through the shared wrapper`() {
        val observer = ArchRecordingWorkflowObserver()
        val workflow = workflow<WorkflowState>("branch-wrap") {
            branchStep("branch", select = { "a" }) {
                branch("a") {
                    localStep("a1") { state, _ -> state.copy(log = state.log + "a1:") }
                    localStep("a2") { state, _ -> state.copy(log = state.log + "a2:") }
                }
                default {
                    localStep("d1") { state, _ -> state.copy(log = state.log + "d1:") }
                }
            }
        }.build(clock = Clock.systemUTC()) { it.log }

        val result = runBlocking { workflow.run(initialState = WorkflowState(""), observer = observer) }

        assertThat(result).isEqualTo("a1:a2:")
        assertThat(observer.startedSteps).containsExactly("branch", "a1", "a2")
        assertThat(observer.completedSteps).containsExactly("a1", "a2", "branch")
    }

    @Test
    fun `branch nested steps still consume step budget`() {
        val observer = ArchRecordingWorkflowObserver()
        val workflow = workflow<WorkflowState>("branch-budget") {
            branchStep("branch", select = { "a" }) {
                branch("a") {
                    localStep("a1") { state, _ -> state.copy(log = state.log + "a1:") }
                    localStep("a2") { state, _ -> state.copy(log = state.log + "a2:") }
                }
                default {
                    localStep("d1") { state, _ -> state.copy(log = state.log + "d1:") }
                }
            }
        }.build(clock = Clock.systemUTC(), stopPolicy = StopPolicy(maxStepExecutions = 2)) { it.log }

        val error = runBlocking {
            runCatching { workflow.run(initialState = WorkflowState(""), observer = observer) }
                .exceptionOrNull()
        }

        assertThat(error).isInstanceOf(WorkflowLimitExceededException::class.java)
        assertThat(error!!.message).contains("maxStepExecutions=2")
    }

    private data class WorkflowState(val log: String = "")
}

private data class ArchResumeState(
    val request: String,
    val draft: String? = null,
    val finalAnswer: String? = null,
)

private object ArchResumeStateCodec : WorkflowStateCodec<ArchResumeState> {
    override fun encode(state: ArchResumeState): String = "${state.request}|${state.draft}|${state.finalAnswer}"
    override fun decode(payload: String): ArchResumeState {
        val parts = payload.split("|")
        return ArchResumeState(parts[0], parts.getOrNull(1).takeIf { it != "null" }, parts.getOrNull(2).takeIf { it != "null" })
    }
}

private data class ArchDelayWakeup(
    val runId: String,
    val stepId: String,
    val resumeAt: Instant,
)

private class ArchRecordingDelayWakeupScheduler : WorkflowDelayWakeupScheduler {
    val wakeups = mutableListOf<ArchDelayWakeup>()
    override suspend fun scheduleDelayWakeup(
        runId: String,
        stepId: String,
        resumeAt: Instant,
    ) {
        wakeups += ArchDelayWakeup(runId, stepId, resumeAt)
    }
}

private class ArchMutableClock(
    var instant: Instant,
    private val zoneId: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun instant(): Instant = instant
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = ArchMutableClock(instant, zone)
}

private class ArchRecordingWorkflowObserver : WorkflowObserver {
    val startedSteps = mutableListOf<String>()
    val completedSteps = mutableListOf<String>()
    val failedSteps = mutableListOf<String>()
    val workflowEvents = mutableListOf<String>()
    override fun onWorkflowEvent(
        workflowName: String,
        name: String,
        attributes: Map<String, Any?>,
        context: WorkflowContext,
    ) {
        workflowEvents += name
    }
    override fun onStepStarted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        startedSteps += stepName
    }
    override fun onStepCompleted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        completedSteps += stepName
    }
    override fun onStepFailed(
        workflowName: String,
        stepName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        failedSteps += stepName
    }
    override fun onWorkflowStarted(workflowName: String, context: WorkflowContext) = Unit
    override fun onWorkflowCompleted(workflowName: String, context: WorkflowContext) = Unit
    override fun onWorkflowFailed(workflowName: String, error: Throwable, context: WorkflowContext) = Unit
}
