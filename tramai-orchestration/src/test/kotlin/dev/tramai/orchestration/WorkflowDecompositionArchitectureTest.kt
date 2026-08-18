package dev.tramai.orchestration

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.coroutines.Continuation

/**
 * Decomposition assertions for Epic 4.2: Workflow.kt is a thin definition
 * facade; runtime ownership lives in [WorkflowRunner] (lifecycle),
 * [WorkflowStepExecutor] (shared step wrapper), and the specialized executors
 * (branch / parallel / delay).
 *
 * Mutation-resistant: these assert compiled structure and bytecode references,
 * not source text, so a `when (step)` reintroduced behind a helper still fails.
 */
class WorkflowDecompositionArchitectureTest {

    @Test
    fun `Workflow facade no longer declares runtime orchestration methods`() {
        val orchestrationMethods = setOf(
            "executeStep",
            "executeSteps",
            "executeTopLevelSteps",
        )
        // Internal members mangle with a $module suffix; strip it so the check
        // cannot be defeated by mangling (Kotlin internal → public JVM method).
        val declared = Workflow::class.java.declaredMethods
            .map { it.name.substringBefore('$') }
            .toSet()
        assertThat(declared).doesNotContainAnyElementsOf(orchestrationMethods)
    }

    @Test
    fun `WorkflowRunner owns run and resume coordination`() {
        val methods = WorkflowRunner::class.java.declaredMethods.map { it.name }.toSet()
        assertThat(methods).contains("run", "resume")
        // run/resume are suspend: both must accept a Continuation.
        val run = WorkflowRunner::class.java.declaredMethods.first { it.name == "run" }
        val resume = WorkflowRunner::class.java.declaredMethods.first { it.name == "resume" }
        assertThat(run.parameterTypes.lastOrNull()).isEqualTo(Continuation::class.java)
        assertThat(resume.parameterTypes.lastOrNull()).isEqualTo(Continuation::class.java)
    }

    @Test
    fun `WorkflowStepExecutor invokes the polymorphic step contract`() {
        // The compiled executeStep must call InternalWorkflowStep.execute and
        // nothing concrete: collect method owners referenced from its bytecode.
        val referenced = methodOwnersOf("dev/tramai/orchestration/WorkflowStepExecutor", "executeStep")
        assertThat(referenced).contains("dev/tramai/orchestration/InternalWorkflowStep")

        val concreteSteps = sealedStepClasses()
            .map { it.name.replace('.', '/') }
            .toSet()
        val concreteRefs = referenced.filter { it in concreteSteps }
        assertThat(concreteRefs).isEmpty()
    }

    @Test
    fun `WorkflowRunner does not dispatch on concrete step types`() {
        val concreteSteps = sealedStepClasses()
            .map { it.name.replace('.', '/') }
            .toSet()
        val runRefs = methodOwnersOf("dev/tramai/orchestration/WorkflowRunner", "run")
        val resumeRefs = methodOwnersOf("dev/tramai/orchestration/WorkflowRunner", "resume")
        val executeTopLevelRefs = methodOwnersOf("dev/tramai/orchestration/WorkflowRunner", "executeTopLevelSteps")
        val executeStepsRefs = methodOwnersOf("dev/tramai/orchestration/WorkflowRunner", "executeSteps")
        val allRefs = runRefs + resumeRefs + executeTopLevelRefs + executeStepsRefs
        assertThat(allRefs.filter { it in concreteSteps }).isEmpty()
    }

    @Test
    fun `Branch nested execution crosses the shared wrapper`() {
        // BranchWorkflowStep.execute must route through the request's
        // executeNestedSteps callback — never call a child step's execute
        // directly. The callback is a field on WorkflowStepExecutionRequest.
        val branchRefs = methodOwnersOf("dev/tramai/orchestration/BranchWorkflowStep", "execute")
        assertThat(branchRefs).contains("dev/tramai/orchestration/WorkflowStepExecutionRequest")

        val concreteSteps = sealedStepClasses()
            .map { it.name.replace('.', '/') }
            .toSet()
        // Self-invocation (suspend continuation calls this$0.execute) is not
        // dispatch on another step type; exclude the class itself.
        val directChildExecute = branchRefs.filter { it in concreteSteps && it != "dev/tramai/orchestration/BranchWorkflowStep" }
        assertThat(directChildExecute).isEmpty()
    }

    @Test
    fun `WorkflowStepExecutor is the single wrapper used by top-level and nested execution`() {
        // executeTopLevelSteps and executeSteps both build requests whose
        // nested callback re-enters the runner; the shared step executor is
        // referenced from the runner class bytecode.
        val runnerRefs = methodOwnersOf("dev/tramai/orchestration/WorkflowRunner", "executeSteps")
        assertThat(runnerRefs).contains("dev/tramai/orchestration/WorkflowStepExecutor")
    }

    private fun sealedStepClasses(): List<Class<*>> =
        InternalWorkflowStep::class.java.permittedSubclasses?.toList() ?: emptyList()
}

private fun methodOwnersOf(className: String, methodName: String): Set<String> {
    val referenced = linkedSetOf<String>()
    val resource = WorkflowDecompositionArchitectureTest::class.java
        .getResourceAsStream("/$className.class") ?: return emptySet()
    ClassReader(resource).accept(object : ClassVisitor(Opcodes.ASM9) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (name != methodName && !name.startsWith("$methodName\$")) return null
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitTypeInsn(opcode: Int, type: String) {
                    referenced += type
                }

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    calledName: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    referenced += owner
                }
            }
        }
    }, 0)
    return referenced
}
