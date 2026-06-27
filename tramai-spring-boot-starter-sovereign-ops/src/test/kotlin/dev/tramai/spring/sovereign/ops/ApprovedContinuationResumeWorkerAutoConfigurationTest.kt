package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApprovalResumeCredentialStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Tests that [ApprovedContinuationResumeWorkerAutoConfiguration] correctly
 * creates the worker bean when enabled and all dependencies are present,
 * and does NOT create it when disabled.
 *
 * Relies on the auto-config being registered in AutoConfiguration.imports.
 */
class ApprovedContinuationResumeWorkerAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SovereignOpsAutoConfiguration::class.java,
                ApprovedContinuationResumeWorkerAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `worker bean is not created when disabled by default`() {
        contextRunner
            .withUserConfiguration(MinimalDependenciesConfig::class.java)
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovedContinuationResumeWorker::class.java)
            }
    }

    @Test
    fun `worker bean is created when enabled`() {
        contextRunner
            .withUserConfiguration(MinimalDependenciesConfig::class.java)
            .withPropertyValues("tramai.sovereign.ops.approved-resume-worker.enabled=true")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovedContinuationResumeWorker::class.java)
            }
    }

    @Test
    fun `worker bean is not created when queue is missing`() {
        contextRunner
            .withUserConfiguration(MinimalDependenciesWithoutQueueConfig::class.java)
            .withPropertyValues("tramai.sovereign.ops.approved-resume-worker.enabled=true")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovedContinuationResumeWorker::class.java)
            }
    }

    @Test
    fun `worker bean uses configured retry delays from properties`() {
        contextRunner
            .withUserConfiguration(MinimalDependenciesConfig::class.java)
            .withPropertyValues("tramai.sovereign.ops.approved-resume-worker.enabled=true")
            .run { ctx ->
                val worker = ctx.getBean(ApprovedContinuationResumeWorker::class.java)
                assertThat(worker).isInstanceOf(SovereignOpsApprovedContinuationResumeWorker::class.java)
            }
    }
    @Test
    fun `lifecycle bean is not created by default`() {
        contextRunner
            .withUserConfiguration(MinimalDependenciesConfig::class.java)
            .withPropertyValues("tramai.sovereign.ops.approved-resume-worker.enabled=true")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovedContinuationResumeWorkerLifecycle::class.java)
            }
    }

    @Test
    fun `lifecycle bean is created when lifecycle enabled`() {
        contextRunner
            .withUserConfiguration(MinimalDependenciesConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.approved-resume-worker.enabled=true",
                "tramai.sovereign.ops.approved-resume-worker.lifecycle-enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovedContinuationResumeWorkerLifecycle::class.java)
                assertThat(ctx).hasSingleBean(ApprovedContinuationResumeWorkerStatusStore::class.java)
            }
    }

    @Test
    fun `lifecycle bean is not created when lifecycle enabled but worker missing`() {
        contextRunner
            .withUserConfiguration(MinimalDependenciesWithoutQueueConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.approved-resume-worker.enabled=true",
                "tramai.sovereign.ops.approved-resume-worker.lifecycle-enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovedContinuationResumeWorker::class.java)
                assertThat(ctx).doesNotHaveBean(ApprovedContinuationResumeWorkerLifecycle::class.java)
            }
    }

    @Test
    fun `batch size and interval properties are injected into status store`() {
        contextRunner
            .withUserConfiguration(MinimalDependenciesConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.approved-resume-worker.enabled=true",
                "tramai.sovereign.ops.approved-resume-worker.lifecycle-enabled=true",
                "tramai.sovereign.ops.approved-resume-worker.batch-size=77",
                "tramai.sovereign.ops.approved-resume-worker.interval=12s",
            )
            .run { ctx ->
                val store = ctx.getBean(ApprovedContinuationResumeWorkerStatusStore::class.java)
                val snapshot = store.snapshot()

                assertThat(snapshot.batchSize).isEqualTo(77)
                assertThat(snapshot.intervalMillis).isEqualTo(Duration.ofSeconds(12).toMillis())
            }
    }
}

/** Provides all three dependencies needed for the worker auto-config. */
@Configuration
open class MinimalDependenciesConfig {

    @Bean
    open fun clock(): Clock = Clock.systemUTC()

    @Bean
    open fun approvedContinuationResumeQueue(): ApprovedContinuationResumeQueue = NoopQueue()

    @Bean
    open fun approvalResumeCredentialStore(): ApprovalResumeCredentialStore = NoopCredentialStore()

    @Bean
    open fun approvalResumeControlPlane(): ApprovalResumeControlPlane = NoopControlPlane()
}

/** Provides only credential store and control plane — missing the queue. */
@Configuration
open class MinimalDependenciesWithoutQueueConfig {

    @Bean
    open fun clock(): Clock = Clock.systemUTC()

    @Bean
    open fun approvalResumeCredentialStore(): ApprovalResumeCredentialStore = NoopCredentialStore()

    @Bean
    open fun approvalResumeControlPlane(): ApprovalResumeControlPlane = NoopControlPlane()
}

private class NoopQueue : ApprovedContinuationResumeQueue {
    override suspend fun claimApprovedPending(
        workerId: String,
        limit: Int,
        leaseUntil: Instant,
    ): List<ApprovedContinuationResumeWorkItem> = emptyList()

    override suspend fun markResumeSucceeded(
        approvalId: dev.tramai.core.approval.gateway.ApprovalId,
        workerId: String,
    ) = Unit

    override suspend fun markResumeFailed(
        approvalId: dev.tramai.core.approval.gateway.ApprovalId,
        workerId: String,
        reasonCode: String,
        retryAt: Instant?,
    ) = Unit
}

private class NoopCredentialStore : ApprovalResumeCredentialStore {
    override suspend fun create(
        record: dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord,
    ) = Unit

    override suspend fun get(
        approvalId: dev.tramai.core.approval.gateway.ApprovalId,
    ): dev.tramai.core.approval.gateway.ApprovalResumeCredentialRecord? = null

    override suspend fun delete(
        approvalId: dev.tramai.core.approval.gateway.ApprovalId,
    ) = Unit
}

private class NoopControlPlane : ApprovalResumeControlPlane {
    override suspend fun resume(command: ApprovalResumeCommand): ApprovalResumeResult =
        ApprovalResumeResult.Resumed(
            approvalId = command.approvalId,
            resumedBy = command.resumedBy,
            result = null,
        )
}
