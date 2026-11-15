package dev.tramai.spring.sovereign.ops

import dev.tramai.orchestration.WorkflowCheckpointStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsRefusalRecorder
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsWorkflowCancellationStore
import dev.tramai.spring.sovereign.ops.outbox.UnconfiguredRefusalRecorder
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import java.time.Clock

/**
 * Spring Boot auto-configuration for the [WorkflowCancellationControlPlane] (0.7.7m).
 *
 * Creates a [SovereignOpsWorkflowCancellationControlPlane] bean when
 * [SovereignOpsWorkflowCancellationStore], [WorkflowCheckpointStore], and
 * [OperatorWorkflowCancellationPolicy] are available.
 */
@AutoConfiguration(after = [SovereignOpsAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "tramai.sovereign.ops",
    name = ["mutations-enabled"],
    havingValue = "true",
)
class WorkflowCancellationControlPlaneAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(WorkflowCancellationControlPlane::class)
    @ConditionalOnBean(
        value = [
            OperatorWorkflowCancellationPolicy::class,
            SovereignOpsWorkflowCancellationStore::class,
            WorkflowCheckpointStore::class,
        ],
    )
    fun sovereignOpsWorkflowCancellationControlPlane(
        checkpointStore: WorkflowCheckpointStore,
        cancellationStore: SovereignOpsWorkflowCancellationStore,
        cancellationPolicy: OperatorWorkflowCancellationPolicy,
        refusalRecorder: ObjectProvider<SovereignOpsRefusalRecorder>,
        clock: ObjectProvider<Clock>,
    ): WorkflowCancellationControlPlane =
        SovereignOpsWorkflowCancellationControlPlane(
            checkpointStore = checkpointStore,
            mutationStore = cancellationStore,
            cancellationPolicy = cancellationPolicy,
            refusalRecorder = refusalRecorder.getIfAvailable() ?: UnconfiguredRefusalRecorder,
            clock = clock.getIfAvailable() ?: Clock.systemUTC(),
        )
}
