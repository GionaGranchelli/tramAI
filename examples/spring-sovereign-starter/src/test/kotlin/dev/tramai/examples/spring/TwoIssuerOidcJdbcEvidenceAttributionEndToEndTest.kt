package dev.tramai.examples.spring

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.zaxxer.hikari.HikariDataSource
import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.operator.ActorIdentityDigest
import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditStore
import dev.tramai.spring.sovereign.SovereignTramaiAutoConfiguration
import dev.tramai.spring.sovereign.ops.ApprovalDecisionControlPlaneAutoConfiguration
import dev.tramai.spring.sovereign.ops.ApprovalResumeControlPlaneAutoConfiguration
import dev.tramai.spring.sovereign.ops.OperatorAuthority
import dev.tramai.spring.sovereign.ops.SovereignOpsAutoConfiguration
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatcher
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import dev.tramai.spring.sovereign.ops.rest.ApprovalControlPlaneController
import dev.tramai.spring.sovereign.ops.rest.ApprovalControlPlaneRestAutoConfiguration
import dev.tramai.spring.sovereign.ops.rest.ApproveDenyRequest
import dev.tramai.spring.sovereign.ops.rest.security.OperatorApproverRoleMapping
import dev.tramai.spring.sovereign.ops.rest.security.OperatorAuthenticationAutoConfiguration
import dev.tramai.spring.sovereign.ops.rest.security.OperatorAuthenticationDependencyCheckAutoConfiguration
import dev.tramai.spring.sovereign.ops.rest.security.OperatorCapabilityMapping
import dev.tramai.spring.sovereign.ops.rest.security.OperatorPrincipalFactory
import dev.tramai.spring.sovereign.ops.rest.security.OperatorPrincipalResolver
import dev.tramai.spring.sovereign.ops.rest.security.OperatorSecurityProperties
import dev.tramai.spring.sovereign.persistence.jdbc.SovereignJdbcPersistenceAutoConfiguration
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.sql.DataSource

/**
 * 0.7.6f §8 Test #13 (G4): End-to-end two-issuer OIDC/JWT -> `NimbusJwtDecoder` ->
 * `OperatorPrincipalFactory` -> `OperatorPrincipalResolver` -> `OperatorAuthority` ->
 * `SovereignOpsApprovalDecisionControlPlane` -> PostgreSQL (`approvals` V10 + `audit_outbox` V3) ->
 * `SovereignOpsAuditOutboxDispatcher` -> `AuditEngineSovereignOpsAuditEmitter` -> `JdbcAuditStore`
 * qualification test.
 *
 * Proves that two operators sharing the same `sub` (`shared-operator-7741`) under two independently
 * configured OIDC/JWT issuer boundaries (`https://idp-alpha.sovereign.example` and
 * `https://idp-beta.sovereign.example`) remain cryptographically and durably distinct across:
 * 1. JWT signature and issuer verification at the OIDC boundary
 * 2. PostgreSQL `approvals` (`decision_actor_issuer`, `decision_actor_identity_digest`)
 * 3. PostgreSQL `audit_outbox` V3 encrypted payload (`actorIssuer`, `actorIdentityDigest`, `evidenceKind`,
 *    `previousApprovalStatus`, `previousApprovalVersion`, `refusalPolicyVersion`, `refusalExpectedVersion`)
 * 4. Emitted `AuditEvent` metadata in PostgreSQL `audit_events`
 */
class TwoIssuerOidcJdbcEvidenceAttributionEndToEndTest {
    companion object {
        private const val ISSUER_ALPHA = "https://idp-alpha.sovereign.example"
        private const val ISSUER_BETA = "https://idp-beta.sovereign.example"
        private const val AUDIENCE = "tramai-control-plane"
        private const val SHARED_SUBJECT = "shared-operator-7741"
        private const val POLICY_VERSION = "v1"

        private const val APPROVAL_ALPHA_ID = "approval-alpha-approve"
        private const val APPROVAL_BETA_ID = "approval-beta-deny"
        private const val APPROVAL_REFUSAL_ID = "approval-role-refusal"

        private val keyPairAlpha: KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val keyPairBeta: KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        private val VALID_BASE64_KEY: String =
            Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

        @JvmStatic
        @BeforeAll
        fun startPg() {
            PgEmbeddedTestSupport.start()
        }

        @JvmStatic
        @AfterAll
        fun stopPg() {
            PgEmbeddedTestSupport.stop()
        }
    }

    @TempDir
    lateinit var tempDir: Path

    private lateinit var keyFile: Path

    @BeforeEach
    fun setUp() {
        keyFile = tempDir.resolve("two-issuer-key.b64")
        keyFile.toFile().writeText(VALID_BASE64_KEY)
        HikariDataSource()
            .apply {
                jdbcUrl = PgEmbeddedTestSupport.jdbcUrl
                username = PgEmbeddedTestSupport.username
                password = PgEmbeddedTestSupport.password
                maximumPoolSize = 2
            }.use { ds ->
                ds.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute(
                            "TRUNCATE TABLE approval_continuations, suspended_invocations, " +
                                "audit_outbox, approvals, audit_events CASCADE",
                        )
                    }
                }
            }
    }

    @Test
    fun `same subject across two OIDC JWT issuer boundaries remains distinct through JDBC outbox and audit emission`() {
        val expectedAlphaDigest = ActorIdentityDigest.of(ISSUER_ALPHA, SHARED_SUBJECT)
        val expectedBetaDigest = ActorIdentityDigest.of(ISSUER_BETA, SHARED_SUBJECT)
        assertThat(expectedAlphaDigest).isNotEqualTo(expectedBetaDigest)

        val alphaRunner = createIssuerContextRunner(ISSUER_ALPHA, keyPairAlpha)
        val betaRunner = createIssuerContextRunner(ISSUER_BETA, keyPairBeta)

        // 1. Seed three PENDING approvals in PostgreSQL and execute Issuer Alpha's JWT-authenticated commands.
        alphaRunner.run { alphaCtx ->
            seedApprovalsAndExecuteAlphaCommands(alphaCtx)
        }

        // 2. Execute Issuer Beta's JWT-authenticated commands and verify durable DB, outbox, and audit emission.
        betaRunner.run { betaCtx ->
            executeBetaCommands(betaCtx)
            assertDurableApprovalRows(betaCtx, expectedAlphaDigest, expectedBetaDigest)
            val pendingOutbox =
                assertDurableOutboxRecords(betaCtx, expectedAlphaDigest, expectedBetaDigest)
            dispatchAndAssertAuditEvents(betaCtx, pendingOutbox, expectedAlphaDigest, expectedBetaDigest)
        }
    }

    private fun seedApprovalsAndExecuteAlphaCommands(alphaCtx: AssertableWebApplicationContext) {
        val approvalStore = alphaCtx.getBean(ApprovalStore::class.java)
        runBlocking {
            approvalStore.create(pendingApproval(APPROVAL_ALPHA_ID, "wf-alpha"))
            approvalStore.create(pendingApproval(APPROVAL_BETA_ID, "wf-beta"))
            approvalStore.create(pendingApproval(APPROVAL_REFUSAL_ID, "wf-refusal"))
        }

        val alphaProps = alphaCtx.getBean(OperatorSecurityProperties::class.java)
        val alphaClock = alphaCtx.getBean(Clock::class.java)
        val alphaController = alphaCtx.getBean(ApprovalControlPlaneController::class.java)

        assertCrossIssuerJwtRejection(alphaProps, alphaClock)

        val alphaApproveToken =
            signJwt(ISSUER_ALPHA, SHARED_SUBJECT, listOf("medical-reviewer-claim"), keyPairAlpha)
        val alphaReviewerAuthority = verifyJwtAndIssueAuthority(alphaApproveToken, alphaProps, alphaClock)
        assertThat(alphaReviewerAuthority.subject).isEqualTo(SHARED_SUBJECT)
        assertThat(alphaReviewerAuthority.issuer).isEqualTo(ISSUER_ALPHA)

        val approveResponse =
            alphaController.approve(
                approvalId = APPROVAL_ALPHA_ID,
                request =
                    ApproveDenyRequest(
                        expectedVersion = 0L,
                        comment = "Alpha medical approval",
                        correlationId = "corr-alpha-approve",
                    ),
                authority = alphaReviewerAuthority,
            )
        assertThat(approveResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(approveResponse.body?.status).isEqualTo("APPROVED")
        assertThat(approveResponse.body?.actorId).isEqualTo(SHARED_SUBJECT)

        val alphaFinanceToken =
            signJwt(ISSUER_ALPHA, SHARED_SUBJECT, listOf("finance-reviewer-claim"), keyPairAlpha)
        val alphaFinanceAuthority = verifyJwtAndIssueAuthority(alphaFinanceToken, alphaProps, alphaClock)
        val alphaRefusalResponse =
            alphaController.approve(
                approvalId = APPROVAL_REFUSAL_ID,
                request =
                    ApproveDenyRequest(
                        expectedVersion = 0L,
                        comment = "Alpha unauthorized role",
                        correlationId = "corr-alpha-refusal",
                    ),
                authority = alphaFinanceAuthority,
            )
        assertThat(alphaRefusalResponse.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(alphaRefusalResponse.body?.status).isEqualTo("UNAUTHORIZED")
    }

    private fun assertCrossIssuerJwtRejection(
        alphaProps: OperatorSecurityProperties,
        alphaClock: Clock,
    ) {
        val betaToken =
            signJwt(ISSUER_BETA, SHARED_SUBJECT, listOf("medical-reviewer-claim"), keyPairBeta)
        assertThatThrownBy {
            verifyJwtAndIssueAuthority(betaToken, alphaProps, alphaClock)
        }.isInstanceOf(JwtException::class.java)

        val wrongIssuerToken =
            signJwt(ISSUER_BETA, SHARED_SUBJECT, listOf("medical-reviewer-claim"), keyPairAlpha)
        assertThatThrownBy {
            verifyJwtAndIssueAuthority(wrongIssuerToken, alphaProps, alphaClock)
        }.isInstanceOf(JwtException::class.java)
    }

    private fun executeBetaCommands(betaCtx: AssertableWebApplicationContext) {
        val betaProps = betaCtx.getBean(OperatorSecurityProperties::class.java)
        val betaClock = betaCtx.getBean(Clock::class.java)
        val betaController = betaCtx.getBean(ApprovalControlPlaneController::class.java)

        // Verified Beta reviewer with identical subject denies APPROVAL_BETA_ID.
        val betaDenyToken =
            signJwt(ISSUER_BETA, SHARED_SUBJECT, listOf("medical-reviewer-claim"), keyPairBeta)
        val betaReviewerAuthority = verifyJwtAndIssueAuthority(betaDenyToken, betaProps, betaClock)
        assertThat(betaReviewerAuthority.subject).isEqualTo(SHARED_SUBJECT)
        assertThat(betaReviewerAuthority.issuer).isEqualTo(ISSUER_BETA)

        val denyResponse =
            betaController.deny(
                approvalId = APPROVAL_BETA_ID,
                request =
                    ApproveDenyRequest(
                        expectedVersion = 0L,
                        comment = "Beta medical denial",
                        correlationId = "corr-beta-deny",
                    ),
                authority = betaReviewerAuthority,
            )
        assertThat(denyResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(denyResponse.body?.status).isEqualTo("DENIED")
        assertThat(denyResponse.body?.actorId).isEqualTo(SHARED_SUBJECT)

        val betaFinanceToken =
            signJwt(ISSUER_BETA, SHARED_SUBJECT, listOf("finance-reviewer-claim"), keyPairBeta)
        val betaFinanceAuthority = verifyJwtAndIssueAuthority(betaFinanceToken, betaProps, betaClock)
        val betaRefusalResponse =
            betaController.deny(
                approvalId = APPROVAL_REFUSAL_ID,
                request =
                    ApproveDenyRequest(
                        expectedVersion = 0L,
                        comment = "Beta unauthorized role",
                        correlationId = "corr-beta-refusal",
                    ),
                authority = betaFinanceAuthority,
            )
        assertThat(betaRefusalResponse.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(betaRefusalResponse.body?.status).isEqualTo("UNAUTHORIZED")
    }

    private fun assertDurableApprovalRows(
        betaCtx: AssertableWebApplicationContext,
        expectedAlphaDigest: String,
        expectedBetaDigest: String,
    ) {
        val betaApprovalStore = betaCtx.getBean(ApprovalStore::class.java)
        assertThat(runBlocking { betaApprovalStore.get(APPROVAL_ALPHA_ID)?.decidedBy }).isEqualTo(SHARED_SUBJECT)
        assertThat(runBlocking { betaApprovalStore.get(APPROVAL_BETA_ID)?.decidedBy }).isEqualTo(SHARED_SUBJECT)
        assertThat(runBlocking { betaApprovalStore.get(APPROVAL_REFUSAL_ID)?.decidedBy }).isNull()

        val ds = betaCtx.getBean(DataSource::class.java)
        val approvalRows = readApprovalProvenanceRows(ds)
        val alphaRow = approvalRows.getValue(APPROVAL_ALPHA_ID)
        assertThat(alphaRow.status).isEqualTo("APPROVED")
        assertThat(alphaRow.actorIssuer).isEqualTo(ISSUER_ALPHA)
        assertThat(alphaRow.actorIdentityDigest).isEqualTo(expectedAlphaDigest)

        val betaRow = approvalRows.getValue(APPROVAL_BETA_ID)
        assertThat(betaRow.status).isEqualTo("DENIED")
        assertThat(betaRow.actorIssuer).isEqualTo(ISSUER_BETA)
        assertThat(betaRow.actorIdentityDigest).isEqualTo(expectedBetaDigest)

        val refusalTargetRow = approvalRows.getValue(APPROVAL_REFUSAL_ID)
        assertThat(refusalTargetRow.status).isEqualTo("PENDING")
        assertThat(refusalTargetRow.actorIssuer).isNull()
        assertThat(refusalTargetRow.actorIdentityDigest).isNull()
    }

    private fun assertDurableOutboxRecords(
        betaCtx: AssertableWebApplicationContext,
        expectedAlphaDigest: String,
        expectedBetaDigest: String,
    ): List<SovereignOpsAuditOutboxRecord> {
        val outboxStore = betaCtx.getBean(SovereignOpsAuditOutboxStore::class.java)
        val pendingOutbox =
            runBlocking {
                outboxStore.listByStatus(SovereignOpsAuditOutboxStatus.PENDING, limit = 10)
            }
        assertThat(pendingOutbox).hasSize(4)

        val approvedRecord =
            pendingOutbox.single { it.evidenceKind == SovereignOpsAuditEvidenceKind.APPROVAL_APPROVED }
        assertThat(approvedRecord.actor).isEqualTo(SHARED_SUBJECT)
        assertThat(approvedRecord.actorIssuer).isEqualTo(ISSUER_ALPHA)
        assertThat(approvedRecord.actorIdentityDigest).isEqualTo(expectedAlphaDigest)
        assertThat(approvedRecord.previousApprovalStatus).isEqualTo("PENDING")
        assertThat(approvedRecord.previousApprovalVersion).isEqualTo(0L)
        assertThat(approvedRecord.approvalStatus).isEqualTo("APPROVED")
        assertThat(approvedRecord.approvalVersion).isEqualTo(1L)

        val deniedRecord =
            pendingOutbox.single { it.evidenceKind == SovereignOpsAuditEvidenceKind.APPROVAL_DENIED }
        assertThat(deniedRecord.actor).isEqualTo(SHARED_SUBJECT)
        assertThat(deniedRecord.actorIssuer).isEqualTo(ISSUER_BETA)
        assertThat(deniedRecord.actorIdentityDigest).isEqualTo(expectedBetaDigest)
        assertThat(deniedRecord.previousApprovalStatus).isEqualTo("PENDING")
        assertThat(deniedRecord.previousApprovalVersion).isEqualTo(0L)
        assertThat(deniedRecord.approvalStatus).isEqualTo("DENIED")
        assertThat(deniedRecord.approvalVersion).isEqualTo(1L)

        val refusalRecords =
            pendingOutbox.filter { it.evidenceKind == SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL }
        assertThat(refusalRecords).hasSize(2)
        assertThat(refusalRecords.map { it.actorIssuer }).containsExactlyInAnyOrder(ISSUER_ALPHA, ISSUER_BETA)
        assertThat(refusalRecords.map { it.actorIdentityDigest })
            .containsExactlyInAnyOrder(expectedAlphaDigest, expectedBetaDigest)
        assertThat(refusalRecords.map { it.eventKey }.distinct()).hasSize(2)
        assertThat(refusalRecords).allSatisfy { refusal ->
            assertThat(refusal.actor).isEqualTo(SHARED_SUBJECT)
            assertThat(refusal.refusalReason).isEqualTo("role-not-permitted")
            assertThat(refusal.refusalPolicyVersion).isEqualTo(POLICY_VERSION)
            assertThat(refusal.refusalExpectedVersion).isEqualTo(0L)
            assertThat(refusal.approvalStatus).isNull()
            assertThat(refusal.approvalVersion).isNull()
            assertThat(refusal.previousApprovalStatus).isNull()
            assertThat(refusal.previousApprovalVersion).isNull()
        }
        return pendingOutbox
    }

    private fun dispatchAndAssertAuditEvents(
        betaCtx: AssertableWebApplicationContext,
        pendingOutbox: List<SovereignOpsAuditOutboxRecord>,
        expectedAlphaDigest: String,
        expectedBetaDigest: String,
    ) {
        val dispatcher = betaCtx.getBean(SovereignOpsAuditOutboxDispatcher::class.java)
        val dispatchResult = runBlocking { dispatcher.dispatchPending(limit = 10) }
        assertThat(dispatchResult.claimed).isEqualTo(4)
        assertThat(dispatchResult.emitted).isEqualTo(4)
        assertThat(dispatchResult.failedRetryable).isZero()
        assertThat(dispatchResult.failedPermanent).isZero()

        val approvedRecord =
            pendingOutbox.single { it.evidenceKind == SovereignOpsAuditEvidenceKind.APPROVAL_APPROVED }
        val deniedRecord =
            pendingOutbox.single { it.evidenceKind == SovereignOpsAuditEvidenceKind.APPROVAL_DENIED }
        val refusalRecords =
            pendingOutbox.filter { it.evidenceKind == SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL }

        val auditStore = betaCtx.getBean(AuditStore::class.java)
        assertDecisionAuditEvents(
            auditStore = auditStore,
            approvedDigest = approvedRecord.aggregateIdDigest,
            deniedDigest = deniedRecord.aggregateIdDigest,
            expectedAlphaDigest = expectedAlphaDigest,
            expectedBetaDigest = expectedBetaDigest,
        )
        assertRefusalAuditEvents(
            auditStore = auditStore,
            refusalDigest = refusalRecords.first().aggregateIdDigest,
            expectedAlphaDigest = expectedAlphaDigest,
            expectedBetaDigest = expectedBetaDigest,
        )
    }

    private fun assertDecisionAuditEvents(
        auditStore: AuditStore,
        approvedDigest: String,
        deniedDigest: String,
        expectedAlphaDigest: String,
        expectedBetaDigest: String,
    ) {
        val alphaEvents = runBlocking { auditStore.readStream("sovereign-ops-approval:$approvedDigest") }
        assertThat(alphaEvents).hasSize(1)
        val alphaEvent = alphaEvents.single()
        assertThat(alphaEvent.actor).isEqualTo(SHARED_SUBJECT)
        assertThat(alphaEvent.decision).isEqualTo("APPROVED")
        assertThat(alphaEvent.reasonCode).isEqualTo("sovereign-ops-approval-granted")
        assertThat(alphaEvent.enforcementPoint).isEqualTo("sovereign-ops.approval.decide")
        assertThat(alphaEvent.metadata)
            .containsEntry("actorIssuer", ISSUER_ALPHA)
            .containsEntry("actorIdentityDigest", expectedAlphaDigest)
            .containsEntry("previousApprovalStatus", "PENDING")
            .containsEntry("previousApprovalVersion", "0")
            .containsEntry("approvalStatus", "APPROVED")
            .containsEntry("approvalVersion", "1")
            .doesNotContainKey("legacyEvidence")
            .doesNotContainKey("issuerAttribution")

        val betaEvents = runBlocking { auditStore.readStream("sovereign-ops-approval:$deniedDigest") }
        assertThat(betaEvents).hasSize(1)
        val betaEvent = betaEvents.single()
        assertThat(betaEvent.actor).isEqualTo(SHARED_SUBJECT)
        assertThat(betaEvent.decision).isEqualTo("DENIED")
        assertThat(betaEvent.reasonCode).isEqualTo("sovereign-ops-admin-denial")
        assertThat(betaEvent.enforcementPoint).isEqualTo("sovereign-ops.approval.decide")
        assertThat(betaEvent.metadata)
            .containsEntry("actorIssuer", ISSUER_BETA)
            .containsEntry("actorIdentityDigest", expectedBetaDigest)
            .containsEntry("previousApprovalStatus", "PENDING")
            .containsEntry("previousApprovalVersion", "0")
            .containsEntry("approvalStatus", "DENIED")
            .containsEntry("approvalVersion", "1")
            .doesNotContainKey("legacyEvidence")
            .doesNotContainKey("issuerAttribution")
    }

    private fun assertRefusalAuditEvents(
        auditStore: AuditStore,
        refusalDigest: String,
        expectedAlphaDigest: String,
        expectedBetaDigest: String,
    ) {
        val refusalEvents = runBlocking { auditStore.readStream("sovereign-ops-approval:$refusalDigest") }
        assertThat(refusalEvents).hasSize(2)
        assertThat(refusalEvents).allSatisfy { event ->
            assertThat(event.actor).isEqualTo(SHARED_SUBJECT)
            assertThat(event.decision).isEqualTo("DENIED")
            assertThat(event.reasonCode).isEqualTo("sovereign-ops-authorization-refused")
            assertThat(event.enforcementPoint).isEqualTo("sovereign-ops.approval.authorization")
            assertThat(event.metadata)
                .containsEntry("refusalReason", "role-not-permitted")
                .containsEntry("refusalPolicyVersion", POLICY_VERSION)
                .containsEntry("refusalExpectedVersion", "0")
                .doesNotContainKey("approvalStatus")
                .doesNotContainKey("approvalVersion")
                .doesNotContainKey("previousApprovalStatus")
                .doesNotContainKey("previousApprovalVersion")
                .doesNotContainKey("legacyEvidence")
                .doesNotContainKey("issuerAttribution")
        }
        assertThat(refusalEvents.map { it.metadata.getValue("actorIssuer") })
            .containsExactlyInAnyOrder(ISSUER_ALPHA, ISSUER_BETA)
        assertThat(refusalEvents.map { it.metadata.getValue("actorIdentityDigest") })
            .containsExactlyInAnyOrder(expectedAlphaDigest, expectedBetaDigest)
    }

    private fun createIssuerContextRunner(
        issuer: String,
        keyPair: KeyPair,
    ): WebApplicationContextRunner {
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        return WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    WebMvcAutoConfiguration::class.java,
                    SecurityAutoConfiguration::class.java,
                    SovereignJdbcPersistenceAutoConfiguration::class.java,
                    SovereignTramaiAutoConfiguration::class.java,
                    SovereignOpsAutoConfiguration::class.java,
                    OperatorAuthenticationAutoConfiguration::class.java,
                    OperatorAuthenticationDependencyCheckAutoConfiguration::class.java,
                    ApprovalDecisionControlPlaneAutoConfiguration::class.java,
                    ApprovalResumeControlPlaneAutoConfiguration::class.java,
                    ApprovalControlPlaneRestAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(
                TwoIssuerJdbcDataSourceConfig::class.java,
                DemoProviderConfiguration::class.java,
            ).withPropertyValues(
                "tramai.sovereign.enabled=true",
                "tramai.sovereign.allowed-models[0]=local-invoice-model",
                "tramai.sovereign.allowed-providers[0]=deterministic-local-provider",
                "tramai.sovereign.provider-zones.deterministic-local-provider=LOCAL",
                "tramai.sovereign.models.local-invoice-model=deterministic-local-provider",
                "tramai.sovereign.persistence.type=jdbc",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
                "tramai.sovereign.ops.enabled=true",
                "tramai.sovereign.ops.mutations-enabled=true",
                "tramai.sovereign.ops.resume-enabled=true",
                "tramai.sovereign.ops.rest-control-plane-enabled=true",
                "tramai.sovereign.ops.rest.base-path=/tramai/sovereign/approvals",
                "tramai.sovereign.ops.security.enabled=true",
                "tramai.sovereign.ops.security.issuer=$issuer",
                "tramai.sovereign.ops.security.audience=$AUDIENCE",
                "tramai.sovereign.ops.security.public-key=$publicKeyBase64",
                "tramai.sovereign.ops.security.role-claim=roles",
                "tramai.sovereign.ops.security.role-mappings.medical-reviewer-claim=decide-approval",
                "tramai.sovereign.ops.security.role-mappings.finance-reviewer-claim=decide-approval",
                "tramai.sovereign.ops.security.approver-role-mappings.medical-reviewer-claim=medical-reviewer",
                "tramai.sovereign.ops.security.approver-role-mappings.finance-reviewer-claim=finance-reviewer",
                "tramai.sovereign.ops.security.approval-policies.$POLICY_VERSION=medical-reviewer",
            )
    }

    /**
     * Exercises the exact OIDC/JWT verification and `OperatorAuthority` issuance chain configured by
     * [OperatorAuthenticationAutoConfiguration]:
     * `NimbusJwtDecoder` (RS256 + pinned issuer + required audience + required exp) ->
     * `JwtAuthenticationConverter` -> `OperatorPrincipalFactory` -> `OperatorPrincipalResolver.issueAuthority`.
     */
    private fun verifyJwtAndIssueAuthority(
        bearerToken: String,
        properties: OperatorSecurityProperties,
        clock: Clock,
    ): OperatorAuthority {
        val decoder = buildIssuerJwtDecoder(properties)
        val verifiedJwt: Jwt = decoder.decode(bearerToken)
        val authentication = JwtAuthenticationConverter().convert(verifiedJwt)
        val resolver =
            OperatorPrincipalResolver(
                OperatorPrincipalFactory(
                    properties = properties,
                    capabilityMapping = OperatorCapabilityMapping(properties.roleClaim, properties.roleMappings),
                    approverRoleMapping =
                        OperatorApproverRoleMapping(
                            properties.roleClaim,
                            properties.approverRoleMappings,
                        ),
                    clock = clock,
                ),
            )
        return resolver.issueAuthority(authentication)
    }

    private fun buildIssuerJwtDecoder(properties: OperatorSecurityProperties): JwtDecoder {
        val der = Base64.getMimeDecoder().decode(properties.publicKey)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
        val decoder =
            NimbusJwtDecoder
                .withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build()
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(properties.issuer),
                JwtClaimValidator<List<String>>("aud") { claim -> claim != null && properties.audience in claim },
                JwtClaimValidator<Instant>("exp") { claim -> claim != null },
            ),
        )
        return decoder
    }

    private fun signJwt(
        issuer: String,
        subject: String,
        roles: List<String>,
        keyPair: KeyPair,
    ): String {
        val now = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(30)))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .claim("roles", roles)
                .build()
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build()
        return SignedJWT(header, claims)
            .apply { sign(RSASSASigner(keyPair.private as RSAPrivateKey)) }
            .serialize()
    }

    private fun pendingApproval(
        approvalId: String,
        workflowRunId: String,
    ): ApprovalRequest {
        val now = Instant.now()
        return ApprovalRequest(
            approvalId = approvalId,
            binding =
                ApprovalBinding(
                    workflowRunId = workflowRunId,
                    toolName = "regulated-claim-payout",
                    argumentsDigest = Sha256Digest.of("sha256:" + "11".repeat(32)),
                    policyVersion = POLICY_VERSION,
                    workflowDigest = Sha256Digest.of("sha256:" + "22".repeat(32)),
                    approvalTokenDigest = Sha256Digest.of("sha256:" + "33".repeat(32)),
                ),
            status = ApprovalStatus.PENDING,
            requestedBy = "workflow-engine",
            requestedAt = now,
            expiresAt = now.plusSeconds(600),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )
    }

    private data class ApprovalProvenanceRow(
        val approvalId: String,
        val status: String,
        val actorIssuer: String?,
        val actorIdentityDigest: String?,
    )

    private fun readApprovalProvenanceRows(ds: DataSource): Map<String, ApprovalProvenanceRow> =
        ds.connection.use { conn ->
            conn
                .prepareStatement(
                    "SELECT approval_id, status, decision_actor_issuer, decision_actor_identity_digest " +
                        "FROM approvals",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        buildMap {
                            while (rs.next()) {
                                val id = rs.getString("approval_id")
                                put(
                                    id,
                                    ApprovalProvenanceRow(
                                        approvalId = id,
                                        status = rs.getString("status"),
                                        actorIssuer = rs.getString("decision_actor_issuer"),
                                        actorIdentityDigest = rs.getString("decision_actor_identity_digest"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    @Configuration(proxyBeanMethods = false)
    private class TwoIssuerJdbcDataSourceConfig {
        @Bean(destroyMethod = "close")
        fun dataSource(): DataSource =
            HikariDataSource().apply {
                jdbcUrl = PgEmbeddedTestSupport.jdbcUrl
                username = PgEmbeddedTestSupport.username
                password = PgEmbeddedTestSupport.password
                maximumPoolSize = 3
            }

        @Bean
        fun auditEngine(auditStore: AuditStore): AuditEngine = AuditEngine(auditStore)
    }
}
