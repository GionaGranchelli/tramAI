package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApprovalResumeCredentialStore
import java.time.Clock
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for the [ApprovedContinuationResumeWorker].
 *
 * Activated when:
 * - `tramai.sovereign.ops.approved-resume-worker.enabled=true`
 * - [ApprovedContinuationResumeQueue] is available
 * - [ApprovalResumeCredentialStore] is available
 * - [ApprovalResumeControlPlane] is available
 *
 * Disabled by default — the auto-resume worker must be explicitly enabled.
 *
 * @see SovereignOpsApprovedContinuationResumeWorker
 */
@AutoConfiguration(after = [ApprovalResumeControlPlaneAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "tramai.sovereign.ops.approved-resume-worker",
    name = ["enabled"],
    havingValue = "true",
)
class ApprovedContinuationResumeWorkerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ApprovedContinuationResumeWorker::class)
    @ConditionalOnBean(
        value = [
            ApprovedContinuationResumeQueue::class,
            ApprovalResumeCredentialStore::class,
            ApprovalResumeControlPlane::class,
        ],
    )
    fun approvedContinuationResumeWorker(
        queue: ApprovedContinuationResumeQueue,
        credentialStore: ApprovalResumeCredentialStore,
        resumeControlPlane: ApprovalResumeControlPlane,
        clock: Clock,
    ): ApprovedContinuationResumeWorker =
        SovereignOpsApprovedContinuationResumeWorker(
            queue = queue,
            credentialStore = credentialStore,
            resumeControlPlane = resumeControlPlane,
            clock = clock,
        )
}
