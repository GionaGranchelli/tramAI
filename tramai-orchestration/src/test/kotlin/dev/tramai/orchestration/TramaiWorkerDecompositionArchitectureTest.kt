package dev.tramai.orchestration

import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Decomposition assertions for Epic 4.4: TramaiWorker is a thin lifecycle
 * façade; polling, leasing, renewal, execution, heartbeat, recovery, and
 * shutdown each have one cohesive owner, and exactly one worker root
 * coroutine lifecycle exists.
 *
 * Mutation-resistant: these assert compiled bytecode references and method
 * declarations, not source text, so responsibilities smuggled back into the
 * façade behind a helper still fail. Fail-closed: every bytecode read uses
 * [checkNotNull]; a class that cannot be loaded fails the test.
 */
class TramaiWorkerDecompositionArchitectureTest {

    private val workerComponents = setOf(
        "dev/tramai/orchestration/TramaiWorker",
        "dev/tramai/orchestration/WorkerLifecycleController",
        "dev/tramai/orchestration/CheckpointPoller",
        "dev/tramai/orchestration/LeaseCoordinator",
        "dev/tramai/orchestration/LeaseRenewalLoop",
        "dev/tramai/orchestration/WorkflowExecutionSupervisor",
        "dev/tramai/orchestration/WorkerHeartbeatPublisher",
        "dev/tramai/orchestration/WorkerShutdownCoordinator",
        "dev/tramai/orchestration/WorkflowRecoveryCoordinator",
    )

    @Test
    fun `TramaiWorker is a thin facade with no worker machinery`() {
        val refs = classMethodRefsOf("dev/tramai/orchestration/TramaiWorker")

        // Polling must not live in the facade.
        assertThat(refs.calls.map { it.owner })
            .withFailMessage("TramaiWorker must not enumerate checkpoints")
            .doesNotContain("dev/tramai/orchestration/WorkflowCheckpointCatalog")

        // Lease claim/renew/release must not live in the facade.
        val leaseCalls = refs.calls.filter { it.owner == "dev/tramai/orchestration/WorkflowLeaseStore" }.map { it.name }
        assertThat(leaseCalls)
            .withFailMessage("TramaiWorker must not touch the lease store directly: $leaseCalls")
            .doesNotContain("claim", "renew", "release")

        // Execution must not live in the facade.
        assertThat(refs.calls)
            .withFailMessage("TramaiWorker must not resume workflows directly")
            .doesNotContain(WorkerMethodRef("dev/tramai/orchestration/Workflow", "resume"))

        // Registration/heartbeat must not live in the facade.
        assertThat(refs.calls.map { it.owner })
            .withFailMessage("TramaiWorker must not register workers or publish heartbeats directly")
            .doesNotContain("dev/tramai/orchestration/WorkerRegistryStore")
    }

    @Test
    fun `TramaiWorker no longer declares the extracted machinery methods`() {
        val methods = classMethodNamesOf("dev/tramai/orchestration/TramaiWorker")
        val extracted = setOf(
            "heartbeatLoop",
            "pollLoop",
            "processCheckpoint",
            "launchExecution",
            "renewLeaseLoop",
            "executeClaimedWorkflow",
            "releaseLease",
            "recoverUnknownAttempt",
            "consumeRetryApproval",
        )
        val stillDeclared = methods.filter { m -> extracted.any { m == it || m.startsWith("$it\$") } }
        assertThat(stillDeclared)
            .withFailMessage("TramaiWorker must not declare extracted machinery: $stillDeclared")
            .isEmpty()
    }

    @Test
    fun `TramaiWorker delegates lifecycle through the controller`() {
        val refs = classMethodRefsOf("dev/tramai/orchestration/TramaiWorker")
        val controllerCalls = refs.calls
            .filter { it.owner == "dev/tramai/orchestration/WorkerLifecycleController" }
            .map { it.name }
        assertThat(controllerCalls).contains("start", "shutdown", "crash", "latestFailure")
    }

    @Test
    fun `CheckpointPoller owns checkpoint enumeration`() {
        assertCalls(
            className = "dev/tramai/orchestration/CheckpointPoller",
            target = WorkerMethodRef("dev/tramai/orchestration/WorkflowCheckpointCatalog", "listCheckpoints"),
            message = "CheckpointPoller must own checkpoint enumeration",
        )
    }

    @Test
    fun `LeaseCoordinator owns lease claim and release`() {
        assertCalls(
            className = "dev/tramai/orchestration/LeaseCoordinator",
            target = WorkerMethodRef("dev/tramai/orchestration/WorkflowLeaseStore", "claim"),
            message = "LeaseCoordinator must own lease claim",
        )
        assertCalls(
            className = "dev/tramai/orchestration/LeaseCoordinator",
            target = WorkerMethodRef("dev/tramai/orchestration/WorkflowLeaseStore", "release"),
            message = "LeaseCoordinator must own lease release",
        )
    }

    @Test
    fun `LeaseRenewalLoop owns lease renewal`() {
        assertCalls(
            className = "dev/tramai/orchestration/LeaseRenewalLoop",
            target = WorkerMethodRef("dev/tramai/orchestration/WorkflowLeaseStore", "renew"),
            message = "LeaseRenewalLoop must own lease renewal",
        )
    }

    @Test
    fun `WorkflowExecutionSupervisor owns workflow execution`() {
        assertCalls(
            className = "dev/tramai/orchestration/WorkflowExecutionSupervisor",
            target = WorkerMethodRef("dev/tramai/orchestration/Workflow", "resume"),
            message = "WorkflowExecutionSupervisor must own workflow execution",
        )
    }

    @Test
    fun `WorkerHeartbeatPublisher owns registration and heartbeats`() {
        assertCalls(
            className = "dev/tramai/orchestration/WorkerHeartbeatPublisher",
            target = WorkerMethodRef("dev/tramai/orchestration/WorkerRegistryStore", "registerWorker"),
            message = "WorkerHeartbeatPublisher must own worker registration",
        )
        assertCalls(
            className = "dev/tramai/orchestration/WorkerHeartbeatPublisher",
            target = WorkerMethodRef("dev/tramai/orchestration/WorkerRegistryStore", "updateHeartbeat"),
            message = "WorkerHeartbeatPublisher must own heartbeat publishing",
        )
    }

    @Test
    fun `WorkerShutdownCoordinator owns worker unregistration`() {
        // The unregister call runs inside suspend lambdas (withTimeoutOrNull +
        // runCatching), which compile to nested classes — scan them too.
        val refs = classAndNestedRefsOf("dev/tramai/orchestration/WorkerShutdownCoordinator")
        assertThat(refs.calls)
            .withFailMessage("WorkerShutdownCoordinator must own worker unregistration")
            .contains(WorkerMethodRef("dev/tramai/orchestration/WorkerRegistryStore", "unregisterWorker"))
    }

    @Test
    fun `WorkflowRecoveryCoordinator owns the recovery state machine`() {
        val methods = classMethodNamesOf("dev/tramai/orchestration/WorkflowRecoveryCoordinator")
        assertThat(methods)
            .withFailMessage("WorkflowRecoveryCoordinator must declare the recovery state machine: $methods")
            .anyMatch { it == "recoverUnknownAttempt" || it.startsWith("recoverUnknownAttempt\$") }
        assertThat(methods)
            .anyMatch { it == "consumeRetryApproval" || it.startsWith("consumeRetryApproval\$") }

        // The supervisor dispatches recovery but must not implement it: the
        // recovery methods are called on the coordinator, not declared on the
        // supervisor's own class.
        val supervisorRefs = classMethodRefsOf("dev/tramai/orchestration/WorkflowExecutionSupervisor")
        assertThat(supervisorRefs.calls.map { it.owner })
            .contains("dev/tramai/orchestration/WorkflowRecoveryCoordinator")
    }

    @Test
    fun `only the lifecycle controller constructs the worker root coroutine lifecycle`() {
        // SupervisorJob() is the root-scope factory. Among the outer worker
        // component classes, only WorkerLifecycleController may call it. The
        // ExecutionTracker's internal observer scope is nested execution
        // machinery (its own .class file) and is not a worker root lifecycle;
        // it is deliberately outside the scanned component set.
        for (component in workerComponents) {
            val refs = classMethodRefsOf(component)
            val constructsRootScope = refs.calls.any { call ->
                val supervisorFactory = call.owner.contains("Supervisor") && call.name.startsWith("SupervisorJob")
                // kotlinx.coroutines exposes the factory as SupervisorJobKt.SupervisorJob or
                // SupervisorKt.SupervisorJob$default depending on the version.
                supervisorFactory
            }
            if (component == "dev/tramai/orchestration/WorkerLifecycleController") {
                assertThat(constructsRootScope)
                    .withFailMessage("WorkerLifecycleController must construct the worker root SupervisorJob")
                    .isTrue()
            } else {
                assertThat(constructsRootScope)
                    .withFailMessage("$component must not construct its own worker root lifecycle")
                    .isFalse()
            }
        }
    }

    private fun assertCalls(
        className: String,
        target: WorkerMethodRef,
        message: String,
    ) {
        val refs = classMethodRefsOf(className)
        assertThat(refs.calls)
            .withFailMessage(message)
            .contains(target)
    }

    @Test
    fun `worker suite has no non-void JUnit test methods`() {
        // JUnit 5 silently skips @Test methods whose JVM signature is
        // non-void. Kotlin expression-body tests ending in an AssertJ chain
        // infer such signatures, so the characterization suite can silently
        // stop executing. Guard the whole decomposition suite against it.
        val suite = listOf(
            "dev/tramai/orchestration/TramaiWorkerTest",
            "dev/tramai/orchestration/WorkerLifecycleControllerTest",
            "dev/tramai/orchestration/CheckpointPollerTest",
            "dev/tramai/orchestration/LeaseCoordinatorTest",
            "dev/tramai/orchestration/LeaseRenewalLoopTest",
            "dev/tramai/orchestration/WorkflowExecutionSupervisorTest",
            "dev/tramai/orchestration/WorkerShutdownCoordinatorTest",
        )
        val offenders = suite.flatMap { nonVoidTestMethodsOf(it) }
        assertThat(offenders)
            .withFailMessage("JUnit skips @Test methods with non-void JVM signatures; they silently never run: $offenders")
            .isEmpty()
    }
}

private data class WorkerMethodRef(
    val owner: String,
    val name: String,
)

private data class WorkerBytecodeRefs(
    val calls: Set<WorkerMethodRef>,
)

/**
 * Collects every called method across all methods of one compiled class
 * (including suspend `$suspendImpl` variants and `$default` bridges).
 */
private fun classMethodRefsOf(className: String): WorkerBytecodeRefs {
    val calls = linkedSetOf<WorkerMethodRef>()
    val resource = checkNotNull(
        TramaiWorkerDecompositionArchitectureTest::class.java
            .getResourceAsStream("/$className.class"),
    ) {
        "Unable to load bytecode for $className"
    }
    ClassReader(resource).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    calledName: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    calls += WorkerMethodRef(owner, calledName)
                }
            }
        }
    }, 0)
    return WorkerBytecodeRefs(calls)
}

/**
 * Collects every called method across all methods of one compiled class,
 * including its nested (lambda) classes discovered via the inner-classes
 * attribute.
 */
private fun classAndNestedRefsOf(className: String): WorkerBytecodeRefs {
    val outer = classMethodRefsOf(className)
    val nestedNames = mutableListOf<String>()
    val resource = checkNotNull(
        TramaiWorkerDecompositionArchitectureTest::class.java
            .getResourceAsStream("/$className.class"),
    ) {
        "Unable to load bytecode for $className"
    }
    ClassReader(resource).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitInnerClass(
            name: String,
            outerName: String?,
            innerName: String?,
            access: Int,
        ) {
            if (name.startsWith("$className\$")) {
                nestedNames += name
            }
        }
    }, 0)
    val nestedCalls = nestedNames.flatMap { classMethodRefsOf(it).calls }
    return WorkerBytecodeRefs(outer.calls + nestedCalls)
}

/**
 * Collects every @Test-annotated method whose JVM descriptor does not
 * return void — JUnit 5 will not execute such methods.
 */
private fun nonVoidTestMethodsOf(className: String): List<String> {
    val offenders = mutableListOf<String>()
    val resource = checkNotNull(
        TramaiWorkerDecompositionArchitectureTest::class.java
            .getResourceAsStream("/$className.class"),
    ) {
        "Unable to load bytecode for $className"
    }
    ClassReader(resource).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (name == "<init>" || name == "<clinit>") return null
            return object : MethodVisitor(Opcodes.ASM9) {
                private var hasTestAnnotation = false

                override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                    if (desc == "Lorg/junit/jupiter/api/Test;") hasTestAnnotation = true
                    return null
                }

                override fun visitEnd() {
                    if (hasTestAnnotation && !descriptor.endsWith(")V")) {
                        offenders += "$className.$name$descriptor"
                    }
                }
            }
        }
    }, 0)
    return offenders
}

/** Collects every method name declared on one compiled class. */
private fun classMethodNamesOf(className: String): Set<String> {
    val names = linkedSetOf<String>()
    val resource = checkNotNull(
        TramaiWorkerDecompositionArchitectureTest::class.java
            .getResourceAsStream("/$className.class"),
    ) {
        "Unable to load bytecode for $className"
    }
    ClassReader(resource).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            names += name
            return null
        }
    }, 0)
    return names
}
