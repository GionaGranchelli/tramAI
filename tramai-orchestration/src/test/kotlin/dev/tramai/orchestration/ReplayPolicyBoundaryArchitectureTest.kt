package dev.tramai.orchestration

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
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
 * Direct consumption is allowed only at:
 * - the persistence boundary ([StepAttemptRecord], [StepAttemptRecordCodec],
 *   [JdbcStepAttemptRecordStore], [ReplayPolicyCompatibility]);
 * - the retained public aiStep DSL overloads ([WorkflowBuilder] family), which
 *   map the legacy parameter into the two-dimensional model immediately.
 *
 * The scan is fail-closed and covers EVERY compiled class in the orchestration
 * main output, including nested/synthetic classes: Kotlin compiles enum
 * `when` dispatch through a generated `<Enclosing>$WhenMappings` class, so a
 * forbidden `when (attempt.replayPolicy)` hides its enum references in a
 * nested class, never in the enclosing business class. A nested class is
 * allowed only when its enclosing class is itself an allowed consumer.
 */
class ReplayPolicyBoundaryArchitectureTest {
    private val replayPolicyInternalName = "dev/tramai/orchestration/ReplayPolicy"
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
        val scanned = productionClasses()
        assertThat(scanned.size)
            .withFailMessage("ReplayPolicy boundary guard scanned zero classes — fail closed")
            .isGreaterThan(50)
        val offenders = scanned
            .filterNot(::isAllowedConsumer)
            .filter(::referencesReplayPolicy)
        assertThat(offenders)
            .withFailMessage(
                "ReplayPolicy is the persistence-compatibility encoding of the two-dimensional " +
                    "replay model and must not be consumed by business logic; decode attempts via " +
                    "StepAttemptRecord.replayDescriptor instead. Offenders: $offenders",
            )
            .isEmpty()
    }

    private fun isAllowedConsumer(className: String): Boolean =
        className in allowedConsumers ||
            allowedConsumers.any { allowed -> className.startsWith("$allowed$") }

    private fun productionClasses(): List<String> {
        val mainClassesDir = File(
            URI.create(checkNotNull(WorkflowRecoveryCoordinator::class.java.protectionDomain.codeSource.location.toString())),
        )
        val orchestrationDir = File(mainClassesDir, "dev/tramai/orchestration")
        check(orchestrationDir.isDirectory) {
            "Orchestration main classes directory unavailable: ${orchestrationDir.absolutePath}"
        }
        return orchestrationDir.listFiles { file -> file.isFile && file.name.endsWith(".class") }
            .orEmpty()
            .map { file -> "dev/tramai/orchestration/${file.name.removeSuffix(".class")}" }
    }

    private fun referencesReplayPolicy(className: String): Boolean {
        val resource = checkNotNull(
            ReplayPolicyBoundaryArchitectureTest::class.java.getResourceAsStream("/$className.class"),
        ) { "Unable to load bytecode for $className" }
        var references = false
        ClassReader(resource).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                if (signature?.contains(replayPolicyInternalName) == true) references = true
            }

            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): net.bytebuddy.jar.asm.FieldVisitor? {
                if (descriptor.contains(replayPolicyInternalName) || signature?.contains(replayPolicyInternalName) == true) {
                    references = true
                }
                return null
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                if (descriptor.contains(replayPolicyInternalName) || signature?.contains(replayPolicyInternalName) == true) {
                    references = true
                }
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitTypeInsn(opcode: Int, type: String) {
                        if (type == replayPolicyInternalName) references = true
                    }

                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        if (owner == replayPolicyInternalName) references = true
                    }

                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        if (owner == replayPolicyInternalName) references = true
                    }

                    override fun visitLdcInsn(value: Any?) {
                        if (value is Type && value.internalName == replayPolicyInternalName) references = true
                    }
                }
            }
        }, 0)
        return references
    }
}
