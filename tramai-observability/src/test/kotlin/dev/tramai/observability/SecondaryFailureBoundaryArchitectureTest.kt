package dev.tramai.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Epic 5.3 — secondary-failure boundary guard.
 *
 * Observer callbacks are non-authoritative secondary effects and must only be
 * invoked through the failure-isolating boundary (FailureIsolating*
 * wrappers) or through the instances the composition wiring has already
 * wrapped. A NEW execution component that grabs a raw observer and calls its
 * callbacks directly bypasses the boundary: a throwing observer could then
 * change the business outcome.
 *
 * Forbidden: `observer.` / `observability.` / `engineEventObserver.` /
 * `request.observer.` followed by a callback method, in tramai-engine /
 * tramai-orchestration production sources — EXCEPT in the files below, which
 * hold the already-wrapped instances passed down through the wiring
 * (WorkflowRunner wraps at entry; TramaiWorker wraps at construction;
 * EngineComponentFactory wraps at composition).
 */
class SecondaryFailureBoundaryArchitectureTest {

    private val callbackRegex = Regex(
        "(observer|observability|engineEventObserver|request\\.observer)\\s*\\.\\s*" +
            "(on[A-Z][A-Za-z]*|emitWorkflowEvent|emitRuntimeEvent)\\s*\\(",
    )

    /** Files that hold wrapped instances via the composition wiring. */
    private val allowedFiles = setOf(
        // Engine: engineEventObserver field holds the wrapped instance.
        "tramai-engine/src/main/kotlin/dev/tramai/engine/approval/ApprovalResumeCoordinator.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/approval/ReplayAuthorizationService.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/tool/ToolResultSanitizer.kt",
        // Orchestration: WorkflowRunner wraps the observer before passing it down.
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowStepExecutor.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowStepFailures.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/ParallelBranchExecution.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowParallelExecutor.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowDelayCoordinator.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowPersistenceSession.kt",
        // Step components receive the wrapped observer through the step request.
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/AgentCliSupport.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/CodexStep.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/HermesStep.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/HttpStep.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/McpStep.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/ShellStep.kt",
        // Orchestration: worker subsystems receive the wrapped observability.
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowExecutionSupervisor.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkerShutdownCoordinator.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/LeaseCoordinator.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/LeaseRenewalLoop.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkerLifecycleController.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkerHeartbeatPublisher.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/PersistenceFailures.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/CheckpointPoller.kt",
    )

    @Test
    fun `observer callbacks are only invoked through the failure-isolating boundary`() {
        val repoRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
        val offenders = mutableListOf<String>()
        listOf("tramai-engine", "tramai-orchestration").forEach { module ->
            val mainDir = File(repoRoot, "$module/src/main")
            if (!mainDir.isDirectory) return@forEach
            mainDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val relative = file.relativeTo(repoRoot).path
                    if (relative in allowedFiles) return@forEach
                    callbackRegex.findAll(file.readText()).forEach { match ->
                        offenders.add("$relative -> ${match.value.trim()}")
                    }
                }
        }
        assertThat(offenders)
            .withFailMessage(
                "Observer callbacks must be invoked through the failure-isolating boundary " +
                    "(FailureIsolating* wrappers) or the wrapped instances supplied by the composition " +
                    "wiring. Direct raw-observer calls from arbitrary execution components can let a " +
                    "throwing observer change the business outcome. Found: $offenders",
            )
            .isEmpty()
    }
}
