package dev.tramai.orchestration

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI

/**
 * Epic 5.1 boundary guard: the legacy single-axis [ReplayPolicy] enum is a
 * persistence-compatibility wire format, not the runtime domain model. The
 * runtime model is [WorkflowStepReplayDescriptor] (replayability × repetition
 * safety × idempotency key); business logic must decode persisted attempts
 * through [StepAttemptRecord.replayDescriptor] and encode via
 * [WorkflowStepReplayDescriptor.toPersistedReplayPolicy].
 *
 * Direct consumption (field/method references to the ReplayPolicy class) is
 * allowed only at:
 * - the persistence boundary ([StepAttemptRecord], [StepAttemptRecordCodec],
 *   [JdbcStepAttemptRecordStore], [ReplayPolicyCompatibility]);
 * - the retained public aiStep DSL overloads ([WorkflowBuilder] family), which
 *   map the legacy parameter into the two-dimensional model immediately.
 */
class ReplayPolicyBoundaryArchitectureTest {
    private val allowedConsumers = setOf(
        "dev/tramai/orchestration/ReplayPolicy",
        "dev/tramai/orchestration/StepAttemptRecord",
        "dev/tramai/orchestration/StepAttemptRecordCodec",
        "dev/tramai/orchestration/JdbcStepAttemptRecordStore",
        "dev/tramai/orchestration/ReplayPolicyCompatibilityKt",
        "dev/tramai/orchestration/WorkflowBuilderKt",
        "dev/tramai/orchestration/WorkflowBuilder",
        "dev/tramai/orchestration/AbstractWorkflowBuilder",
        "dev/tramai/orchestration/BranchWorkflowBuilder",
    )

    @Test
    fun `ReplayPolicy is consumed only at the persistence and DSL boundary`() {
        val offenders = productionClasses()
            .filterNot { it in allowedConsumers }
            .filter(::referencesReplayPolicy)
        assertThat(offenders)
            .withFailMessage(
                "ReplayPolicy is the persistence-compatibility encoding of the two-dimensional " +
                    "replay model and must not be consumed by business logic; decode attempts via " +
                    "StepAttemptRecord.replayDescriptor instead. Offenders: $offenders",
            )
            .isEmpty()
    }

    private fun productionClasses(): List<String> {
        val mainClassesDir = File(
            URI.create(checkNotNull(WorkflowRecoveryCoordinator::class.java.protectionDomain.codeSource.location.toString())),
        )
        val orchestrationDir = File(mainClassesDir, "dev/tramai/orchestration")
        return orchestrationDir.listFiles { file -> file.isFile && file.name.endsWith(".class") && !file.name.contains('$') }
            .orEmpty()
            .map { file -> "dev/tramai/orchestration/${file.name.removeSuffix(".class")}" }
    }

    private fun referencesReplayPolicy(className: String): Boolean {
        val resource = checkNotNull(
            ReplayPolicyBoundaryArchitectureTest::class.java.getResourceAsStream("/$className.class"),
        ) { "Unable to load bytecode for $className" }
        var references = false
        ClassReader(resource).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                    if (owner == "dev/tramai/orchestration/ReplayPolicy") references = true
                }

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) {
                    if (owner == "dev/tramai/orchestration/ReplayPolicy") references = true
                }
            }
        }, 0)
        return references
    }
}
