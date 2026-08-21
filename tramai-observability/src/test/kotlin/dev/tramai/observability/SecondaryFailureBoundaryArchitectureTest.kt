package dev.tramai.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Epic 5.3 — secondary-failure boundary guard.
 *
 * Observer callbacks are non-authoritative secondary effects and must only be
 * invoked through the failure-isolating boundary (FailureIsolating* wrappers)
 * or through the instances the composition wiring has already wrapped. A NEW
 * execution component that grabs a raw observer and calls its callbacks
 * directly bypasses the boundary: a throwing observer could then change the
 * business outcome.
 *
 * The guard matches CALLBACK INVOCATION NAMES (not receiver variable names —
 * `workflowObserver.onWorkflowCompleted(...)` and `telemetry.onStepStarted(...)`
 * must be caught too) across every runtime module that owns observers
 * (engine, orchestration, scheduler). Files are exempt only when they are the
 * wrappers themselves, the interface definitions, or the known wiring points
 * that hold already-wrapped instances.
 */
class SecondaryFailureBoundaryArchitectureTest {

    private val observerCallbackNames = listOf(
        "onCallCancelled", "onCallCompleted", "onCallStarted",
        "onDrainProgress", "onEngineEvent",
        "onLeaseAcquired", "onLeaseContested", "onLeaseExpired",
        "onLeaseReleaseFailed", "onLeaseReleased", "onLeaseRenewalFailed",
        "onLeaseRenewed",
        "onMissedTick",
        "onPollFailed", "onProviderFailure", "onProviderResponse",
        "onScheduledTick", "onShutdownComplete", "onShutdownStarted",
        "onSkippedTick",
        "onStepAttemptCompleted", "onStepAttemptFailed", "onStepAttemptStarted",
        "onStepCompleted", "onStepFailed", "onStepStarted",
        "onStructuredParseFailure",
        "onUnknownAttempt",
        "onWorkTakenOver", "onWorkerHeartbeat", "onWorkerStarted",
        "onWorkerStopped", "onWorkflowAbandoned",
        "onWorkflowCompleted", "onWorkflowEvent", "onWorkflowFailed",
        "onWorkflowStarted",
    )

    private val callbackRegex = Regex(
        "\\.\\s*(${observerCallbackNames.joinToString("|")}|emitWorkflowEvent|emitRuntimeEvent)\\s*\\(",
    )

    private val emitterRegex = Regex(
        "(observer|observability|engineEventObserver|request\\.observer)\\s*\\.\\s*(emitWorkflowEvent|emitRuntimeEvent)\\s*\\(",
    )

    /**
     * Files that hold wrapped instances via the composition wiring or define
     * the boundary itself. Adding a NEW file here requires a reviewer to
     * confirm it only invokes callbacks on wrapped instances.
     */
    private val allowedFiles = setOf(
        // Boundary definitions and wrappers.
        "tramai-core/src/main/kotlin/dev/tramai/core/observation/OperationObservation.kt",
        "tramai-core/src/main/kotlin/dev/tramai/core/observation/FailureIsolatingOperationObserver.kt",
        "tramai-core/src/main/kotlin/dev/tramai/core/observation/secondary/SecondaryEffectPolicy.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/EngineEventObserver.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/FailureIsolatingEngineEventObserver.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowObservation.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/TramaiWorker.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/FailureIsolatingWorkflowObserver.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/FailureIsolatingTramaiWorkerObserver.kt",
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/RuntimeEventEmission.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/RuntimeEventEmission.kt",
        // Engine: engineEventObserver field holds the wrapped instance.
        "tramai-engine/src/main/kotlin/dev/tramai/engine/approval/ApprovalResumeCoordinator.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/approval/ReplayAuthorizationService.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/tool/ToolResultSanitizer.kt",
        // Engine: coordinators receive the wrapped observation from the wiring.
        "tramai-engine/src/main/kotlin/dev/tramai/engine/budget/TokenBudgetCoordinator.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/invocation/ClaimedResumeExecutionCoordinator.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/invocation/ProviderResponseDlpSanitizer.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/invocation/RawResponseCoordinator.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/invocation/ToolLoopCoordinator.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/provider/ProviderAttemptExecutor.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/streaming/StreamingExecutionCoordinator.kt",
        "tramai-engine/src/main/kotlin/dev/tramai/engine/structured/StructuredResponseCoordinator.kt",
        // Orchestration: WorkflowRunner wraps the observer before passing it down.
        "tramai-orchestration/src/main/kotlin/dev/tramai/orchestration/WorkflowRunner.kt",
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
        // Scheduler: timer and store wrap both observer sources at the boundary.
        "tramai-scheduler/src/main/kotlin/dev/tramai/scheduler/ScheduledWorkflowTimer.kt",
        "tramai-scheduler/src/main/kotlin/dev/tramai/scheduler/JdbcWorkflowSchedulerStore.kt",
    )

    @Test
    fun `observer callbacks are only invoked through the failure-isolating boundary`() {
        val repoRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { it.resolve("settings.gradle.kts").isFile }
        val offenders = mutableListOf<String>()
        listOf("tramai-core", "tramai-engine", "tramai-orchestration", "tramai-scheduler").forEach { module ->
            val mainDir = File(repoRoot, "$module/src/main")
            if (!mainDir.isDirectory) return@forEach
            mainDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val relative = file.relativeTo(repoRoot).path
                    if (relative in allowedFiles) return@forEach
                    val text = file.readText()
                    callbackRegex.findAll(text).forEach { match ->
                        offenders.add("$relative -> ${match.value.trim()}")
                    }
                    emitterRegex.findAll(text).forEach { match ->
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
