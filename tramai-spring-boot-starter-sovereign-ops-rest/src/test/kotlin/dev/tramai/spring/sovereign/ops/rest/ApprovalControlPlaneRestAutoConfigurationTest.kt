package dev.tramai.spring.sovereign.ops.rest

import dev.tramai.core.approval.ApprovalConsumptionReceipt
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.ClaimedApprovalContinuation
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.spring.sovereign.ops.ApprovalDecisionCommand
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalDecisionResult
import dev.tramai.spring.sovereign.ops.ApprovalResumeCommand
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlane
import dev.tramai.spring.sovereign.ops.ApprovalResumeResult
import dev.tramai.spring.sovereign.ops.OperatorAuthority
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxPage
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQuery
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxQueryService
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxWorkItem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant

/**
 * Verifies the auto-configuration guards for
 * [ApprovalControlPlaneRestAutoConfiguration].
 *
 * NOTE: ApplicationContextRunner silently catches bean-creation exceptions
 * (see Spring Boot pitfall), so tests dependent on missing required beans
 * are handled by Spring's standard bean-resolution behavior and are not
 * duplicated here.
 */
class ApprovalControlPlaneRestAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ApprovalControlPlaneRestAutoConfiguration::class.java),
            ).withUserConfiguration(
                TestControlPlanesConfig::class.java,
                TestStoresConfig::class.java,
            )

    private val contextRunnerWithInbox =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ApprovalControlPlaneRestAutoConfiguration::class.java),
            ).withUserConfiguration(
                TestControlPlanesConfig::class.java,
                TestStoresConfig::class.java,
                TestInboxQueryServiceConfig::class.java,
            )

    // ── Control plane controller ──────────────────────────────────

    @Test
    fun `rest control plane disabled by default does not create controller`() {
        contextRunner.run { ctx ->
            assertThat(ctx).doesNotHaveBean(ApprovalControlPlaneController::class.java)
        }
    }

    @Test
    fun `rest-control-plane-enabled false does not create controller`() {
        contextRunner
            .withPropertyValues("tramai.sovereign.ops.rest-control-plane-enabled=false")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovalControlPlaneController::class.java)
            }
    }

    @Test
    fun `rest-control-plane-enabled true with all dependencies creates controller`() {
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=true",
            ).run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalControlPlaneController::class.java)
            }
    }

    // ── Inbox controller ──────────────────────────────────────────

    @Test
    fun `inbox controller not created when property disabled`() {
        contextRunnerWithInbox.run { ctx ->
            assertThat(ctx).doesNotHaveBean(ApprovalInboxController::class.java)
        }
    }

    @Test
    fun `inbox controller created when property enabled and query service exists`() {
        contextRunnerWithInbox
            .withPropertyValues(
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=true",
            ).run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalInboxController::class.java)
            }
    }

    @Test
    fun `inbox controller not created when query service missing`() {
        contextRunner // no TestInboxQueryServiceConfig
            .withPropertyValues(
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=true",
            ).run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovalInboxController::class.java)
            }
    }

    @Test
    fun `custom ApprovalInboxController backs off`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ApprovalControlPlaneRestAutoConfiguration::class.java),
            ).withUserConfiguration(
                TestControlPlanesConfig::class.java,
                TestStoresConfig::class.java,
                TestInboxQueryServiceConfig::class.java,
                CustomInboxControllerConfig::class.java,
            ).withPropertyValues(
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=true",
            ).run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalInboxController::class.java)
                // only the custom bean should exist (back-off works)
            }
    }

    // -- test configurations --

    @Configuration
    open class TestControlPlanesConfig {
        @Bean
        open fun testDecisionControlPlane(): ApprovalDecisionControlPlane = DecisionControlPlaneStub()

        @Bean
        open fun testResumeControlPlane(): ApprovalResumeControlPlane = ResumeControlPlaneStub()
    }

    @Configuration
    open class TestStoresConfig {
        @Bean
        open fun testApprovalStore(): ApprovalStore = ApprovalStoreStub()

        @Bean
        open fun testApprovalContinuationStore(): ApprovalContinuationStore = ApprovalContinuationStoreStub()
    }

    @Configuration
    open class TestInboxQueryServiceConfig {
        @Bean
        open fun testInboxQueryService(): ApprovalInboxQueryService = InboxQueryServiceStub()
    }

    @Configuration
    open class CustomControllerConfig {
        @Bean
        open fun customApprovalControlPlaneController(): ApprovalControlPlaneController =
            ApprovalControlPlaneController(
                decisionControlPlane = DecisionControlPlaneStub(),
                resumeControlPlane = ResumeControlPlaneStub(),
                approvalStore = ApprovalStoreStub(),
                approvalContinuationStore = ApprovalContinuationStoreStub(),
            )

        @Bean
        open fun testDecisionControlPlane(): ApprovalDecisionControlPlane = DecisionControlPlaneStub()

        @Bean
        open fun testResumeControlPlane(): ApprovalResumeControlPlane = ResumeControlPlaneStub()

        @Bean
        open fun testApprovalStore(): ApprovalStore = ApprovalStoreStub()

        @Bean
        open fun testApprovalContinuationStore(): ApprovalContinuationStore = ApprovalContinuationStoreStub()
    }

    @Configuration
    open class CustomInboxControllerConfig {
        @Bean
        open fun customApprovalInboxController(): ApprovalInboxController {
            val controller = ApprovalInboxController(InboxQueryServiceStub())
            return controller
        }
    }

    // -- stub implementations --

    private class DecisionControlPlaneStub : ApprovalDecisionControlPlane {
        override suspend fun approve(
            command: ApprovalDecisionCommand,
            authority: OperatorAuthority,
        ): ApprovalDecisionResult = ApprovalDecisionResult.NotFound(command.approvalId)

        override suspend fun deny(
            command: ApprovalDecisionCommand,
            authority: OperatorAuthority,
        ): ApprovalDecisionResult = ApprovalDecisionResult.NotFound(command.approvalId)
    }

    private class ResumeControlPlaneStub : ApprovalResumeControlPlane {
        override suspend fun resume(command: ApprovalResumeCommand): ApprovalResumeResult =
            ApprovalResumeResult.NotFound(command.approvalId)
    }

    private class ApprovalStoreStub : ApprovalStore {
        override suspend fun create(request: ApprovalRequest): ApprovalRequest = request

        override suspend fun get(approvalId: String): ApprovalRequest? = null

        override suspend fun transition(
            approvalId: String,
            expectedVersion: Long,
            transition: ApprovalTransition,
        ): ApprovalRequest = throw UnsupportedOperationException()

        override suspend fun consumeApprovedOrReplay(
            approvalId: String,
            expectedVersion: Long,
            presentedTokenDigest: Sha256Digest,
            consumedBy: String,
        ): ApprovalConsumptionReceipt = throw UnsupportedOperationException()
    }

    private class ApprovalContinuationStoreStub : ApprovalContinuationStore {
        override suspend fun create(
            continuation: ApprovalContinuation,
            arguments: SensitiveToolArguments,
        ): ApprovalContinuation = continuation

        override suspend fun get(approvalId: String): ApprovalContinuation? = null

        override suspend fun claimForExecution(
            approvalId: String,
            expectedVersion: Long,
            claimedBy: String,
        ): ClaimedApprovalContinuation = throw UnsupportedOperationException()

        override suspend fun complete(
            approvalId: String,
            expectedVersion: Long,
            completedBy: String,
        ): ApprovalContinuation = throw UnsupportedOperationException()

        override suspend fun expire(
            approvalId: String,
            expectedVersion: Long,
        ): ApprovalContinuation = throw UnsupportedOperationException()

        override suspend fun cancel(
            approvalId: String,
            expectedVersion: Long,
        ): ApprovalContinuation = throw UnsupportedOperationException()

        override suspend fun findStaleClaimed(
            claimedBefore: Instant,
            limit: Int,
        ): List<ApprovalContinuation> = emptyList()

        override suspend fun forceCancelClaimed(
            approvalId: String,
            expectedVersion: Long,
            cancelledBy: String,
            reasonCode: String,
        ): ApprovalContinuation = throw UnsupportedOperationException()

        override suspend fun sweepExpired(): Int = 0
    }

    private class InboxQueryServiceStub : ApprovalInboxQueryService {
        override suspend fun search(query: ApprovalInboxQuery): ApprovalInboxPage = ApprovalInboxPage(emptyList())

        override suspend fun getWorkItem(approvalId: ApprovalId): ApprovalInboxWorkItem? = null
    }

    // ══════════════════════════════════════════════════════════════
    //  Reviewer UI controller
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `reviewer UI disabled by default`() {
        contextRunnerWithInbox
            .withPropertyValues(
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=true",
            ).run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovalReviewerUiController::class.java)
            }
    }

    @Test
    fun `reviewer UI not registered when rest-control-plane-enabled false`() {
        contextRunnerWithInbox
            .withPropertyValues(
                "tramai.sovereign.ops.reviewer-ui-enabled=true",
                "tramai.sovereign.ops.rest-control-plane-enabled=false",
            ).run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovalReviewerUiController::class.java)
            }
    }

    @Test
    fun `reviewer UI registered when both properties enabled and required beans exist`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ApprovalControlPlaneRestAutoConfiguration::class.java),
            ).withUserConfiguration(
                TestControlPlanesConfig::class.java,
                TestStoresConfig::class.java,
                TestInboxQueryServiceConfig::class.java,
            ).withPropertyValues(
                "tramai.sovereign.ops.reviewer-ui-enabled=true",
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=true",
            ).run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalReviewerUiController::class.java)
            }
    }

    @Test
    fun `reviewer UI backs off when custom controller bean exists`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ApprovalControlPlaneRestAutoConfiguration::class.java),
            ).withUserConfiguration(
                TestControlPlanesConfig::class.java,
                TestStoresConfig::class.java,
                TestInboxQueryServiceConfig::class.java,
                CustomReviewerUiControllerConfig::class.java,
            ).withPropertyValues(
                "tramai.sovereign.ops.reviewer-ui-enabled=true",
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=true",
            ).run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalReviewerUiController::class.java)
            }
    }

    /**
     * Tests that require missing service beans cause the reviewer UI controller
     * to not be registered (context still starts because the web auto-config
     * controllers don't depend on these beans for control plane controllers).
     *
     * The reviewer UI requires both ApprovalInboxQueryService and
     * ApprovalDecisionControlPlane. Resume is not a UI dependency since the
     * preview UI is approve/deny only (resume tokens are intentionally not
     * exposed by the safe inbox boundary).
     *
     * NOTE: The missing ApprovalDecisionControlPlane case cannot be tested
     * via ApplicationContextRunner because ApprovalControlPlaneController
     * requires it as a direct constructor dependency, causing the entire
     * context to fail on startup (the runner reports an unstartable context
     * rather than a bean-absent context).
     */

    @Test
    fun `reviewer UI not registered without ApprovalInboxQueryService`() {
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.ops.reviewer-ui-enabled=true",
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=true",
            ).run { ctx ->
                assertThat(ctx).doesNotHaveBean(ApprovalReviewerUiController::class.java)
            }
    }

    @Configuration
    open class CustomReviewerUiControllerConfig {
        @Bean
        open fun customApprovalReviewerUiController(): ApprovalReviewerUiController = ApprovalReviewerUiController()
    }

    @Configuration
    open class TestResumeControlPlaneOnlyConfig {
        @Bean
        open fun testResumeControlPlane(): ApprovalResumeControlPlane = ResumeControlPlaneStub()
    }

    @Configuration
    open class TestDecisionControlPlaneOnlyConfig {
        @Bean
        open fun testDecisionControlPlane(): ApprovalDecisionControlPlane = DecisionControlPlaneStub()
    }

    /**
     * Startup matrix row 1 (0.7.6e): the control plane may not exist without the operator boundary.
     *
     * This is the fail-open combination the epic exists to remove — endpoints that mutate approvals reachable
     * with authentication and authorization both off — so the context must refuse to start and must name the
     * configuration keys rather than dying later on a missing dependency.
     */
    @Test
    fun `rest control plane enabled without operator security fails startup`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ApprovalControlPlaneRestAutoConfiguration::class.java),
            ).withUserConfiguration(TestControlPlanesConfig::class.java, TestStoresConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=false",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("tramai-rest-control-plane-requires-operator-security")
            }
    }

    /**
     * Startup matrix row 5: authentication on, policy configured, everything wired — the controller exists.
     * Without this the previous test could pass by never creating the controller at all.
     */
    @Test
    fun `rest control plane enabled with operator security and a decision service starts`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ApprovalControlPlaneRestAutoConfiguration::class.java),
            ).withUserConfiguration(
                TestControlPlanesConfig::class.java,
                TestStoresConfig::class.java,
                SecurityPolicyConfig::class.java,
            ).withPropertyValues(
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=true",
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(ApprovalControlPlaneController::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    class SecurityPolicyConfig {
        @Bean
        fun operatorApprovalPolicy(): dev.tramai.spring.sovereign.ops.OperatorApprovalPolicy =
            dev.tramai.spring.sovereign.ops.OperatorApprovalPolicy(
                mapOf(
                    "v1" to
                        setOf(
                            dev.tramai.core.approval.gateway
                                .ApproverRole("medical-reviewer"),
                        ),
                ),
            )
    }

    /**
     * A host that supplies a valid policy but leaves authentication off must not get an accessible endpoint.
     *
     * This is the boundary between two different trust questions: an in-process host is allowed to wire its own
     * trusted collaborators, but the requirement under test is about *external requests*. A configured policy
     * alone must not be enough to publish approve/deny routes, because those routes would then be reachable
     * without any authenticated identity at all.
     */
    @Test
    fun `a host-supplied policy cannot make an unauthenticated control plane reachable`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(ApprovalControlPlaneRestAutoConfiguration::class.java),
            ).withUserConfiguration(
                TestControlPlanesConfig::class.java,
                TestStoresConfig::class.java,
                SecurityPolicyConfig::class.java,
            ).withPropertyValues(
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.security.enabled=false",
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasMessageContaining("tramai-rest-control-plane-requires-operator-security")
            }
    }
}
