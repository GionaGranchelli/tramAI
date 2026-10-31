package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalConsumptionReceipt
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.operator.OperatorPrincipal
import dev.tramai.spring.sovereign.ops.ApprovalDecisionCommand
import dev.tramai.spring.sovereign.ops.ApprovalDecisionResult
import dev.tramai.spring.sovereign.ops.ApprovalUnauthorizedReason
import dev.tramai.spring.sovereign.ops.OperatorApprovalPolicy
import dev.tramai.spring.sovereign.ops.OperatorAuthority
import dev.tramai.spring.sovereign.ops.SovereignOpsApprovalDecisionControlPlane
import dev.tramai.spring.sovereign.ops.SovereignOpsProperties
import dev.tramai.spring.sovereign.ops.outbox.DefaultSovereignOpsAuditOutboxOperations
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsPreparedRecoveryDecision
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/**
 * 0.7.6f §5.2/§8 end to end: control plane -> durable outbox -> a restarted reader.
 *
 * The unit tests prove recording and the JDBC tests prove storage; neither proves the seam between them. This
 * drives the real control plane against the real PostgreSQL outbox and then reads the evidence back through
 * a fresh store, which is the only way to show what an auditor would actually find after a restart.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefusalRecordingEndToEndTest {
    private lateinit var dataSource: DataSource
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-31T09:00:00Z"), ZoneOffset.UTC)

    @BeforeAll
    fun startPostgres() {
        postgres.start()
        dataSource =
            PGSimpleDataSource().apply {
                setUrl(postgres.jdbcUrl)
                user = postgres.username
                password = postgres.password
            }
        val base =
            Paths
                .get(System.getProperty("user.dir"))
                .parent
                .resolve("tramai-persistence-jdbc/src/main/resources/tramai/persistence/jdbc/postgres")
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                for (file in MIGRATIONS) stmt.execute(Files.readString(base.resolve(file)))
            }
        }
    }

    @AfterAll
    fun stopPostgres() {
        postgres.stop()
    }

    @BeforeEach
    fun clean() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("TRUNCATE TABLE audit_outbox, approvals CASCADE") }
        }
    }

    @Test
    fun `a capability refusal persists reconstructable evidence and reads no approval`() =
        runBlocking<Unit> {
            val approvals = CountingApprovalStore()
            val mutations = CountingMutationStore()
            val plane = controlPlane(approvals, mutations)

            val result = plane.approve(decisionCommand(), authorityWithoutCapability())

            assertThat(result)
                .isEqualTo(
                    ApprovalDecisionResult.Unauthorized(
                        ApprovalId(APPROVAL_ID),
                        ApprovalUnauthorizedReason.MISSING_CAPABILITY,
                    ),
                )
            // Zero reads, zero mutations: the refusal decided nothing and disclosed nothing.
            assertThat(approvals.reads).isZero()
            assertThat(mutations.calls).isZero()
            assertThat(countApprovals()).isZero()

            // Exactly one durable record, readable by a store that did not write it.
            val stored = restartedStore().listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10).single()
            assertThat(stored.evidenceKind).isEqualTo(SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL)
            assertThat(stored.refusalReason).isEqualTo("missing-capability")
            // Reconstruction: the key inputs are readable as values, not only as a hash.
            assertThat(stored.refusalPolicyVersion).isNull()
            assertThat(stored.refusalExpectedVersion).isEqualTo(EXPECTED_VERSION)
            assertThat(stored.eventKey).startsWith("refusal-sha256:")
            assertThat(stored.eventKey).hasSize("refusal-sha256:".length + 64)
            assertThat(stored.actor).isEqualTo(SUBJECT)
            assertThat(stored.actorIssuer).isEqualTo(ISSUER)
            assertThat(stored.actorIdentityDigest).matches("[0-9a-f]{64}")
            // A refusal claims no approval state, and no approval state was invented for it.
            assertThat(stored.approvalStatus).isNull()
            assertThat(stored.approvalVersion).isNull()
            assertThat(stored.previousApprovalStatus).isNull()
            assertThat(stored.previousApprovalVersion).isNull()
        }

    @Test
    fun `a retried refusal is still a refusal and adds no record`() =
        runBlocking<Unit> {
            val approvals = CountingApprovalStore()
            val mutations = CountingMutationStore()
            val plane = controlPlane(approvals, mutations)

            repeat(3) {
                assertThat(plane.approve(decisionCommand(), authorityWithoutCapability()))
                    .describedAs("attempt ${it + 1} must remain a refusal, never an authorized operation")
                    .isEqualTo(
                        ApprovalDecisionResult.Unauthorized(
                            ApprovalId(APPROVAL_ID),
                            ApprovalUnauthorizedReason.MISSING_CAPABILITY,
                        ),
                    )
            }

            assertThat(approvals.reads).isZero()
            assertThat(mutations.calls).isZero()
            assertThat(countApprovals()).isZero()
            assertThat(restartedStore().listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10)).hasSize(1)
        }

    @Test
    fun `a role refusal persists the policy version it consulted`() =
        runBlocking<Unit> {
            val approvals = CountingApprovalStore()
            val plane = controlPlane(approvals, CountingMutationStore())

            val result =
                plane.approve(decisionCommand(), authorityWithCapability(setOf(ApproverRole("finance-reviewer"))))

            assertThat(result)
                .isEqualTo(
                    ApprovalDecisionResult.Unauthorized(
                        ApprovalId(APPROVAL_ID),
                        ApprovalUnauthorizedReason.ROLE_NOT_PERMITTED,
                    ),
                )
            // This refusal necessarily followed a read, and says which policy version governed it.
            assertThat(approvals.reads).isEqualTo(1)
            val stored = restartedStore().listByStatus(SovereignOpsAuditOutboxStatus.PENDING, 10).single()
            assertThat(stored.refusalReason).isEqualTo("role-not-permitted")
            assertThat(stored.refusalPolicyVersion).isEqualTo("v1")
            assertThat(stored.refusalExpectedVersion).isEqualTo(EXPECTED_VERSION)
        }

    // --- helpers --------------------------------------------------------------------------------

    private fun controlPlane(
        approvals: ApprovalStore,
        mutations: CountingMutationStore,
    ): SovereignOpsApprovalDecisionControlPlane {
        val operations = operations()
        return SovereignOpsApprovalDecisionControlPlane(
            approvalStore = approvals,
            mutationStore = mutations,
            approvalPolicy = OperatorApprovalPolicy(mapOf("v1" to setOf(ApproverRole("medical-reviewer")))),
            clock = clock,
            refusalRecorder = operations,
        )
    }

    private fun operations(): DefaultSovereignOpsAuditOutboxOperations =
        DefaultSovereignOpsAuditOutboxOperations(
            outboxStore = newStore(),
            outboxDispatcher = null,
            properties = SovereignOpsProperties(),
            recoveryResolver = { SovereignOpsPreparedRecoveryDecision.UNKNOWN },
            clock = clock,
        )

    /** A store this test never wrote through: what a restarted process sees. */
    private fun restartedStore(): JdbcSovereignOpsAuditOutboxStore = newStore()

    private fun newStore(): JdbcSovereignOpsAuditOutboxStore =
        JdbcSovereignOpsAuditOutboxStore(
            dataSource = dataSource,
            payloadCodec = TestCodec(),
            claimLeaseDuration = Duration.ofMinutes(5),
        )

    private fun countApprovals(): Int =
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT count(*) FROM approvals").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun decisionCommand() = ApprovalDecisionCommand(ApprovalId(APPROVAL_ID), EXPECTED_VERSION)

    private fun authorityWithoutCapability() =
        OperatorAuthority(
            OperatorPrincipal(
                subject = SUBJECT,
                issuer = ISSUER,
                authenticatedAt = clock.instant(),
                capabilities = emptySet(),
                approverRoles = emptySet(),
            ),
        )

    private fun authorityWithCapability(roles: Set<ApproverRole>) =
        OperatorAuthority(
            OperatorPrincipal(
                subject = SUBJECT,
                issuer = ISSUER,
                authenticatedAt = clock.instant(),
                capabilities = setOf(dev.tramai.core.operator.OperatorCapability.DECIDE_APPROVAL),
                approverRoles = roles,
            ),
        )

    /** Counts reads so "zero lookup" is a fact about the run, not a claim about the code. */
    private class CountingApprovalStore : ApprovalStore {
        var reads: Int = 0
            private set

        override suspend fun get(approvalId: String): ApprovalRequest? {
            reads++
            return pendingApproval()
        }

        override suspend fun create(request: ApprovalRequest): ApprovalRequest = request

        override suspend fun transition(
            approvalId: String,
            expectedVersion: Long,
            transition: ApprovalTransition,
        ): ApprovalRequest = error("a refusal must never transition an approval")

        override suspend fun consumeApprovedOrReplay(
            approvalId: String,
            expectedVersion: Long,
            presentedTokenDigest: Sha256Digest,
            consumedBy: String,
        ): ApprovalConsumptionReceipt = error("a refusal must never consume an approval")
    }

    /** Delegate-shaped, but any call is a failure: a refusal must never reach the mutation store. */
    private class CountingMutationStore : SovereignOpsApprovalMutationStore {
        var calls: Int = 0
            private set

        override suspend fun approveApprovalWithAuditIntent(
            approvalId: String,
            expectedVersion: Long,
            actor: String,
            reason: String,
            auditIntent: SovereignOpsAuditOutboxRecord,
        ): SovereignOpsApprovalMutationResult {
            calls++
            error("a refusal must never reach the mutation store")
        }

        override suspend fun denyApprovalWithAuditIntent(
            approvalId: String,
            expectedVersion: Long,
            actor: String,
            reason: String,
            auditIntent: SovereignOpsAuditOutboxRecord,
        ): SovereignOpsApprovalMutationResult {
            calls++
            error("a refusal must never reach the mutation store")
        }
    }

    /** Real AES-GCM: the payload must authenticate on the way back out. */
    private class TestCodec : JdbcOpsAuditOutboxPayloadCodec {
        override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
            val nonce = ByteArray(NONCE_LENGTH).also { java.security.SecureRandom().nextBytes(it) }
            return JdbcEncryptedAuditOutboxPayload(
                ciphertext = cipher(Cipher.ENCRYPT_MODE, nonce, plaintext),
                keyId = KEY_ID,
                algorithm = AES_GCM,
                nonce = nonce,
                payloadDigest =
                    "sha256:" +
                        java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(plaintext)
                            .joinToString("") { "%02x".format(it) },
            )
        }

        override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray {
            require(envelope.keyId == KEY_ID) { "audit-outbox-test-key-mismatch" }
            return cipher(Cipher.DECRYPT_MODE, envelope.nonce, envelope.ciphertext)
        }

        private fun cipher(
            mode: Int,
            nonce: ByteArray,
            bytes: ByteArray,
        ): ByteArray =
            Cipher.getInstance(AES_GCM).run {
                init(mode, SecretKeySpec(KEY, "AES"), GCMParameterSpec(128, nonce))
                doFinal(bytes)
            }
    }

    private companion object {
        private const val APPROVAL_ID = "approval-refusal-e2e"
        private const val ISSUER = "https://issuer.example"
        private const val SUBJECT = "operator-1"
        private const val EXPECTED_VERSION = 3L
        private const val NONCE_LENGTH = 12
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val KEY_ID = "test-key"
        private val KEY = ByteArray(16) { it.toByte() }

        private const val POSTGRES_IMAGE = "postgres:17-alpine"
        private val MIGRATIONS =
            listOf(
                "V1__sovereign_persistence.sql",
                "V4__audit_outbox_hardening.sql",
                "V10__approval_actor_provenance.sql",
            )

        private val postgres =
            PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("refusal_e2e")
                .withUsername("test")
                .withPassword("test")

        private fun pendingApproval() =
            ApprovalRequest(
                approvalId = APPROVAL_ID,
                binding =
                    ApprovalBinding(
                        workflowRunId = "wf-1",
                        toolName = "claim-triage",
                        argumentsDigest = Sha256Digest.of("sha256:" + "01".repeat(32)),
                        policyVersion = "v1",
                        workflowDigest = Sha256Digest.of("sha256:" + "02".repeat(32)),
                        approvalTokenDigest = Sha256Digest.of("sha256:" + "03".repeat(32)),
                    ),
                status = ApprovalStatus.PENDING,
                requestedBy = SUBJECT,
                requestedAt = Instant.parse("2026-10-31T08:00:00Z"),
                expiresAt = Instant.parse("2026-10-31T10:00:00Z"),
                decidedBy = null,
                decidedAt = null,
                decisionComment = null,
                consumedBy = null,
                consumedAt = null,
                version = EXPECTED_VERSION,
            )
    }
}
