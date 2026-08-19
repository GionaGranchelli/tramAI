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
 *
 * Fail-closed: every bytecode read uses [checkNotNull]; a class that cannot be
 * loaded fails the test instead of producing "no forbidden references found".
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
    fun `Workflow facade delegates run and resume to the runner`() {
        assertCalls(
            className = "dev/tramai/orchestration/Workflow",
            methodName = "run",
            target = MethodRef("dev/tramai/orchestration/WorkflowRunner", "run"),
            message = "Workflow.run must delegate to WorkflowRunner.run",
        )
        assertCalls(
            className = "dev/tramai/orchestration/Workflow",
            methodName = "resume",
            target = MethodRef("dev/tramai/orchestration/WorkflowRunner", "resume"),
            message = "Workflow.resume must delegate to WorkflowRunner.resume",
        )
    }

    @Test
    fun `WorkflowStepExecutor invokes the polymorphic step contract`() {
        // The compiled executeStep must call InternalWorkflowStep.execute and
        // nothing concrete: collect method calls from its bytecode.
        val refs = methodRefsOf("dev/tramai/orchestration/WorkflowStepExecutor", "executeStep")
        assertThat(refs.calls).contains(
            MethodRef("dev/tramai/orchestration/InternalWorkflowStep", "execute"),
        )

        val concreteSteps = sealedStepClasses()
            .map { it.name.replace('.', '/') }
            .toSet()
        val concreteCalls = refs.calls.filter { it.owner in concreteSteps }
        assertThat(concreteCalls).isEmpty()
    }

    @Test
    fun `WorkflowRunner does not dispatch on concrete step types`() {
        val concreteSteps = sealedStepClasses()
            .map { it.name.replace('.', '/') }
            .toSet()
        val runRefs = methodRefsOf("dev/tramai/orchestration/WorkflowRunner", "run")
        val resumeRefs = methodRefsOf("dev/tramai/orchestration/WorkflowRunner", "resume")
        val executeTopLevelRefs = methodRefsOf("dev/tramai/orchestration/WorkflowRunner", "executeTopLevelSteps")
        val executeStepsRefs = methodRefsOf("dev/tramai/orchestration/WorkflowRunner", "executeSteps")
        val allTypes = runRefs.types + resumeRefs.types + executeTopLevelRefs.types + executeStepsRefs.types
        assertThat(allTypes.filter { it in concreteSteps }).isEmpty()
    }

    @Test
    fun `Branch nested execution crosses the shared wrapper`() {
        // BranchWorkflowStep.execute must access the request's
        // executeNestedSteps callback — never call a child step's execute
        // directly.
        val refs = methodRefsOf("dev/tramai/orchestration/BranchWorkflowStep", "execute")
        assertThat(refs.calls).contains(
            MethodRef("dev/tramai/orchestration/WorkflowStepExecutionRequest", "getExecuteNestedSteps"),
        )

        val concreteSteps = sealedStepClasses()
            .map { it.name.replace('.', '/') }
            .toSet()
        // Self-invocation (suspend continuation calls this$0.execute) is not
        // dispatch on another step type; exclude the class itself.
        val directChildExecute = refs.calls.filter {
            it.owner in concreteSteps &&
                it.owner != "dev/tramai/orchestration/BranchWorkflowStep" &&
                it.name == "execute"
        }
        assertThat(directChildExecute).isEmpty()
    }

    @Test
    fun `top-level execution routes through the shared step executor`() {
        assertCalls(
            className = "dev/tramai/orchestration/WorkflowRunner",
            methodName = "executeTopLevelSteps",
            target = MethodRef("dev/tramai/orchestration/WorkflowStepExecutor", "executeStep"),
            message = "WorkflowRunner.executeTopLevelSteps must delegate to WorkflowStepExecutor.executeStep",
        )
    }

    @Test
    fun `nested execution routes through the shared step executor`() {
        assertCalls(
            className = "dev/tramai/orchestration/WorkflowRunner",
            methodName = "executeSteps",
            target = MethodRef("dev/tramai/orchestration/WorkflowStepExecutor", "executeStep"),
            message = "WorkflowRunner.executeSteps must delegate to WorkflowStepExecutor.executeStep",
        )
    }

    @Test
    fun `no global worker workflow registry may return`() {
        // The process-global binding object and its implicit-registration helpers
        // must not come back in any compiled orchestration class. They lived in
        // TramaiWorker.kt: `WorkerWorkflowBindings` is a nested object (JVM owner
        // TramaiWorker$WorkerWorkflowBindings) and the top-level helpers map to
        // TramaiWorkerKt. Forbid those exact owners; WorkflowKt stays open for
        // legitimate future top-level workflow helpers.
        val forbiddenOwners = setOf(
            "dev/tramai/orchestration/TramaiWorker\$WorkerWorkflowBindings",
            "dev/tramai/orchestration/TramaiWorkerKt",
        )
        val allRefs = methodRefsOf(
            "dev/tramai/orchestration/TramaiWorker",
            "executeClaimedWorkflow",
        )
        assertThat(allRefs.calls.map { it.owner }).doesNotContainAnyElementsOf(forbiddenOwners)

        // Workflow.run/resume must not call any worker-binding registration.
        for (method in listOf("run", "resume")) {
            val workflowRefs = methodRefsOf("dev/tramai/orchestration/Workflow", method)
            assertThat(workflowRefs.calls.map { it.name })
                .withFailMessage("Workflow.$method must not register a worker binding")
                .doesNotContain("rememberWorkerWorkflowBinding")
        }
    }

    @Test
    fun `worker execution path performs no unchecked workflow casts`() {
        // The worker must not perform source-level unchecked casts on workflow
        // types: resolution returns the binding's erased view, so no
        // `workflow as Workflow<Any?, Any?>` is needed.
        //
        // A naive CHECKCAST probe would false-positive: Kotlin's suspend state
        // machine restores captured locals via `getfield TramaiWorker$...$N.L$x`
        // followed by CHECKCAST after every suspension point. Those are
        // compiler-generated and safe. A genuine source cast has no preceding
        // GETFIELD on the method's own continuation class — it casts the result
        // of a lookup/call instead. We assert no such cast exists.
        val sourceCasts = sourceCastsTo(
            className = "dev/tramai/orchestration/TramaiWorker",
            methodPrefix = "executeClaimedWorkflow",
            continuationOwnerPrefix = "dev/tramai/orchestration/TramaiWorker\$executeClaimedWorkflow\$",
            castTargets = setOf(
                "dev/tramai/orchestration/Workflow",
                "dev/tramai/orchestration/WorkflowStateCodec",
                "dev/tramai/orchestration/WorkflowBinding",
            ),
        )
        assertThat(sourceCasts)
            .withFailMessage("TramaiWorker must not cast workflow types; use the binding's erased view: $sourceCasts")
            .isEmpty()
    }

    private fun sealedStepClasses(): List<Class<*>> =
        InternalWorkflowStep::class.java.permittedSubclasses?.toList() ?: emptyList()

    private fun assertCalls(
        className: String,
        methodName: String,
        target: MethodRef,
        message: String,
    ) {
        val refs = methodRefsOf(className, methodName)
        assertThat(refs.calls)
            .withFailMessage(message)
            .contains(target)
    }
}

private data class MethodRef(
    val owner: String,
    val name: String,
)

private data class BytecodeRefs(
    val types: Set<String>,
    val calls: Set<MethodRef>,
)

/**
 * Collects the referenced types and called methods of one compiled method.
 *
 * Kotlin suspend functions compile their body into the public method (plus
 * `$suspendImpl`/`$default` variants); matching `name == methodName` plus a
 * `$`-prefixed variant covers both. Internal members may mangle with a
 * `$module` suffix — matched via the `$` prefix so the assertions are robust
 * to Kotlin's internal-mangling without being defeated by it.
 */
/**
 * Finds CHECKCAST instructions targeting [castTargets] whose operand did NOT
 * come from a GETFIELD on the method's own suspend continuation class.
 *
 * Kotlin compiles a suspend function into an entry method whose bytecode both
 * (a) runs the real body and (b) restores captured locals after every
 * suspension point via `getfield <ContinuationClass>.L$x` + `checkcast`.
 * Those restores are compiler-generated and harmless. A source-level cast
 * (`workflow as Workflow<Any?, Any?>`) instead CHECKCASTs the result of a
 * lookup/call — no preceding continuation GETFIELD. Distinguishing the two is
 * what makes this guard mutation-resistant without false-positives.
 */
private fun sourceCastsTo(
    className: String,
    methodPrefix: String,
    continuationOwnerPrefix: String,
    castTargets: Set<String>,
): List<String> {
    val sourceCasts = mutableListOf<String>()
    val resource = checkNotNull(
        WorkflowDecompositionArchitectureTest::class.java
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
            if (!name.startsWith(methodPrefix)) return null
            val method = name
            return object : MethodVisitor(Opcodes.ASM9) {
                // The most recent field whose value is on the stack. A CHECKCAST
                // immediately consuming a continuation-field load is a suspend
                // restore; anything else is a source cast.
                private var lastFieldOwner: String? = null

                override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                    lastFieldOwner = if (opcode == Opcodes.GETFIELD) owner else null
                }

                override fun visitTypeInsn(opcode: Int, type: String) {
                    if (opcode != Opcodes.CHECKCAST || type !in castTargets) return
                    val fromContinuation = lastFieldOwner?.startsWith(continuationOwnerPrefix) == true
                    if (!fromContinuation) {
                        sourceCasts += "$method -> checkcast $type"
                    }
                    lastFieldOwner = null
                }

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    // A call between the load and the cast means the value is a
                    // call result, not a captured local.
                    lastFieldOwner = null
                }

                override fun visitInsn(opcode: Int) {
                    // Stack manipulation (dup, pops) does not change provenance
                    // of the cast operand.
                }
            }
        }
    }, 0)
    return sourceCasts
}

private fun methodRefsOf(className: String, methodName: String): BytecodeRefs {
    val types = linkedSetOf<String>()
    val calls = linkedSetOf<MethodRef>()
    val resource = checkNotNull(
        WorkflowDecompositionArchitectureTest::class.java
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
            if (name != methodName && !name.startsWith("$methodName$")) return null
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitTypeInsn(opcode: Int, type: String) {
                    types += type
                }

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    calledName: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    calls += MethodRef(owner, calledName)
                }
            }
        }
    }, 0)
    return BytecodeRefs(types, calls)
}
