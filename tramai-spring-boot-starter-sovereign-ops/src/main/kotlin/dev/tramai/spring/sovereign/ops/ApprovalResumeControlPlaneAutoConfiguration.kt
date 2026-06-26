package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.sovereign.SovereignTramaiRuntime
import java.time.Clock
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for the Preview [ApprovalResumeControlPlane].
 *
 * Creates a [SovereignOpsApprovalResumeControlPlane] bean when
 * [ApprovalStore], [ApprovalContinuationStore], and [SovereignTramaiRuntime] are available.
 *
 * Guarded by `tramai.sovereign.ops.resume-enabled=true`.
 *
 * @see SovereignOpsApprovalResumeControlPlane
 */
@AutoConfiguration(after = [SovereignOpsAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "tramai.sovereign.ops",
    name = ["resume-enabled"],
    havingValue = "true",
)
class ApprovalResumeControlPlaneAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ApprovalResumeControlPlane::class)
    @ConditionalOnBean(
        value = [
            ApprovalStore::class,
            ApprovalContinuationStore::class,
            SovereignTramaiRuntime::class,
        ],
    )
    fun sovereignOpsApprovalResumeControlPlane(
        approvalStore: ApprovalStore,
        approvalContinuationStore: ApprovalContinuationStore,
        runtime: SovereignTramaiRuntime,
        clock: Clock,
    ): ApprovalResumeControlPlane =
        SovereignOpsApprovalResumeControlPlane(
            approvalStore = approvalStore,
            approvalContinuationStore = approvalContinuationStore,
            resumeApproval = runtime::resumeApproval,
            clock = clock,
        )
}
