package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalIdGenerator
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalTokenDigester
import dev.tramai.core.approval.ApprovalTokenGenerator
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalBindingMismatchException
import dev.tramai.core.exception.ApprovalTokenRejectedException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DefaultApprovalGateCoordinatorTest {

    private lateinit var clock: CoordinatorMutableClock
    private lateinit var store: InMemoryApprovalStore
    private lateinit var digester: ApprovalTokenDigester
    private lateinit var coordinator: DefaultApprovalGateCoordinator
    private val fixedApprovalId = "approval-fixed-1"
    private val issuedTokenRaw = "issued-token-123"

    @BeforeEach
    fun setUp() {
        clock = CoordinatorMutableClock(Instant.parse("2026-06-05T10:00:00Z"))
        store = InMemoryApprovalStore(clock = clock)
        digester = Sha256ApprovalTokenDigester()
        coordinator = coordinator()
    }

    @Test
    fun `createApproval creates pending request`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())

        val stored = store.get(challenge.approvalId)

        assertThat(stored).isNotNull
        assertThat(stored!!.status).isEqualTo(ApprovalStatus.PENDING)
        assertThat(stored.version).isEqualTo(0L)
    }

    @Test
    fun `createApproval stores only digest not raw token`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())

        val stored = store.get(challenge.approvalId)!!

        assertThat(stored.binding.approvalTokenDigest).isEqualTo(digester.digest(challenge.token))
        assertThat(stored.binding.approvalTokenDigest.value).doesNotContain(challenge.token.reveal())
    }

    @Test
    fun `challenge contains raw token`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())

        assertThat(challenge.token.reveal()).isEqualTo(issuedTokenRaw)
    }

    @Test
    fun `stored request has no raw token`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())

        val stored = store.get(challenge.approvalId)!!

        assertThat(stored.toString()).doesNotContain(challenge.token.reveal())
    }

    @Test
    fun `challenge does not expose digest`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())
        val digest = digester.digest(challenge.token)

        assertThat(challenge.toString()).doesNotContain(digest.value)
    }

    @Test
    fun `requestedAt uses injected clock`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())

        val stored = store.get(challenge.approvalId)!!

        assertThat(stored.requestedAt).isEqualTo(clock.instant())
    }

    @Test
    fun `approval ID uses injected generator`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())

        assertThat(challenge.approvalId).isEqualTo(fixedApprovalId)
    }

    @Test
    fun `bindings stored exactly`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())

        val stored = store.get(challenge.approvalId)!!

        assertThat(stored.binding).isEqualTo(
            ApprovalBinding(
                workflowRunId = "wf-run-1",
                toolName = "tool-1",
                argumentsDigest = argumentsDigest(),
                policyVersion = "policy-v1",
                workflowDigest = workflowDigest(),
                approvalTokenDigest = digester.digest(challenge.token),
            ),
        )
    }

    @Test
    fun `invalid identifiers rejected`() : Unit = runBlocking {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    coordinator.createApproval(createCommand(workflowRunId = "  "))
                }
            }
            .withMessage("workflowRunId must not be blank")
    }

    @Test
    fun `past expiry rejected`() : Unit = runBlocking {
        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    coordinator.createApproval(createCommand(expiresAt = clock.instant().minusSeconds(1)))
                }
            }
            .withMessage("expiresAt must be in the future")
    }

    @Test
    fun `duplicate ID propagates safely`() : Unit = runBlocking {
        coordinator.createApproval(createCommand())

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { coordinator.createApproval(createCommand(toolName = "tool-2")) }
            }
            .withMessage("Approval '$fixedApprovalId' already exists")
            .withMessageNotContaining(issuedTokenRaw)
    }

    @Test
    fun `exact binding approved valid token succeeds`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        val authorization = coordinator.authorizeResume(authorizeCommand(challenge))

        assertThat(authorization.approvalId).isEqualTo(fixedApprovalId)
        assertThat(authorization.consumedBy).isEqualTo("consumer-1")
        assertThat(authorization.consumedAt).isEqualTo(clock.instant())
        assertThat(authorization.version).isEqualTo(2L)
    }

    @Test
    fun `pending request rejected`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())

        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { coordinator.authorizeResume(authorizeCommand(challenge, expectedVersion = 0L)) } }
            .withMessageContaining("status is PENDING")
    }

    @Test
    fun `denied rejected`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())
        store.transition(fixedApprovalId, 0L, ApprovalTransition.Deny("approver", null))

        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { coordinator.authorizeResume(authorizeCommand(challenge)) } }
            .withMessageContaining("status is DENIED")
    }

    @Test
    fun `timed out rejected`() : Unit = runBlocking {
        val expiredCoordinator = coordinator(
            store = InMemoryApprovalStore(clock = clock),
        )
        val challenge = expiredCoordinator.createApproval(createCommand(expiresAt = clock.instant().plusSeconds(10)))
        val localStore = InMemoryApprovalStore(clock = clock)
        val localCoordinator = coordinator(store = localStore)
        val localChallenge = localCoordinator.createApproval(createCommand(expiresAt = clock.instant().plusSeconds(10)))
        clock.advance(Duration.ofSeconds(11))
        localStore.transition(fixedApprovalId, 0L, ApprovalTransition.Timeout)

        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { localCoordinator.authorizeResume(authorizeCommand(localChallenge, expectedVersion = 1L)) } }
            .withMessageContaining("status is TIMED_OUT")

        assertThat(challenge.approvalId).isEqualTo(fixedApprovalId)
    }

    @Test
    fun `expired rejected`() : Unit = runBlocking {
        val challenge = approvedChallenge(expiresAt = clock.instant().plusSeconds(5))
        clock.advance(Duration.ofSeconds(6))

        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { coordinator.authorizeResume(authorizeCommand(challenge)) } }
            .withMessageContaining("has expired")
    }

    @Test
    fun `wrong token rejected`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking {
                coordinator.authorizeResume(
                    authorizeCommand(challenge, presentedToken = ApprovalToken.parsePresented("wrong-token")),
                )
            }
        }
            .isInstanceOf(ApprovalTokenRejectedException::class.java)
            .hasMessage("Approval token rejected for '$fixedApprovalId'")
    }

    @Test
    fun `second consume rejected`() : Unit = runBlocking {
        val challenge = approvedChallenge()
        coordinator.authorizeResume(authorizeCommand(challenge))

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { coordinator.authorizeResume(authorizeCommand(challenge, expectedVersion = 2L)) }
            }
            .withMessageContaining("already been consumed")
    }

    @Test
    fun `stale version rejected`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { coordinator.authorizeResume(authorizeCommand(challenge, expectedVersion = 0L)) }
            }
            .withMessageContaining("version mismatch")
    }

    @Test
    fun `workflowRunId mismatch rejected before consumption`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking {
                coordinator.authorizeResume(authorizeCommand(challenge, workflowRunId = "wf-run-2"))
            }
        }
            .isInstanceOf(ApprovalBindingMismatchException::class.java)
            .hasMessage("Approval binding mismatch for '$fixedApprovalId': workflowRunId")
    }

    @Test
    fun `toolName mismatch rejected before consumption`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking { coordinator.authorizeResume(authorizeCommand(challenge, toolName = "tool-2")) }
        }
            .isInstanceOf(ApprovalBindingMismatchException::class.java)
            .hasMessage("Approval binding mismatch for '$fixedApprovalId': toolName")
    }

    @Test
    fun `argumentsDigest mismatch rejected before consumption`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking {
                coordinator.authorizeResume(
                    authorizeCommand(
                        challenge,
                        argumentsDigest = Sha256Digest.of("sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                    ),
                )
            }
        }
            .isInstanceOf(ApprovalBindingMismatchException::class.java)
            .hasMessage("Approval binding mismatch for '$fixedApprovalId': argumentsDigest")
    }

    @Test
    fun `policyVersion mismatch rejected before consumption`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking { coordinator.authorizeResume(authorizeCommand(challenge, policyVersion = "policy-v2")) }
        }
            .isInstanceOf(ApprovalBindingMismatchException::class.java)
            .hasMessage("Approval binding mismatch for '$fixedApprovalId': policyVersion")
    }

    @Test
    fun `workflowDigest mismatch rejected before consumption`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking {
                coordinator.authorizeResume(
                    authorizeCommand(
                        challenge,
                        workflowDigest = Sha256Digest.of("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                    ),
                )
            }
        }
            .isInstanceOf(ApprovalBindingMismatchException::class.java)
            .hasMessage("Approval binding mismatch for '$fixedApprovalId': workflowDigest")
    }

    @Test
    fun `binding mismatch leaves unconsumed`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking { coordinator.authorizeResume(authorizeCommand(challenge, toolName = "tool-2")) }
        }.isInstanceOf(ApprovalBindingMismatchException::class.java)

        assertThat(store.get(fixedApprovalId)!!.consumedAt).isNull()
    }

    @Test
    fun `wrong token leaves unconsumed`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking {
                coordinator.authorizeResume(
                    authorizeCommand(challenge, presentedToken = ApprovalToken.parsePresented("wrong-token")),
                )
            }
        }.isInstanceOf(ApprovalTokenRejectedException::class.java)

        assertThat(store.get(fixedApprovalId)!!.consumedAt).isNull()
    }

    @Test
    fun `concurrent calls exactly one succeeds`() : Unit = runBlocking {
        val challenge = approvedChallenge()
        val start = CountDownLatch(1)

        val results = listOf(
            async(Dispatchers.Default) {
                start.await()
                runCatching { coordinator.authorizeResume(authorizeCommand(challenge)) }
            },
            async(Dispatchers.Default) {
                start.await()
                runCatching { coordinator.authorizeResume(authorizeCommand(challenge)) }
            },
        )

        start.countDown()
        val settled = results.awaitAll()

        assertThat(settled.count { it.isSuccess }).isEqualTo(1)
        assertThat(settled.count { it.isFailure }).isEqualTo(1)
        assertThat(store.get(fixedApprovalId)!!.consumedAt).isNotNull()
    }

    @Test
    fun `raw token absent from exceptions`() : Unit = runBlocking {
        val challenge = approvedChallenge()
        val rawToken = challenge.token.reveal()

        assertThatThrownBy {
            runBlocking {
                coordinator.authorizeResume(
                    authorizeCommand(challenge, presentedToken = ApprovalToken.parsePresented("wrong-token")),
                )
            }
        }
            .isInstanceOf(ApprovalTokenRejectedException::class.java)
            .hasMessageNotContaining(rawToken)
    }

    @Test
    fun `token digest absent from exceptions`() : Unit = runBlocking {
        val challenge = approvedChallenge()
        val tokenDigest = store.get(fixedApprovalId)!!.binding.approvalTokenDigest.value

        assertThatThrownBy {
            runBlocking {
                coordinator.authorizeResume(
                    authorizeCommand(challenge, presentedToken = ApprovalToken.parsePresented("wrong-token")),
                )
            }
        }
            .isInstanceOf(ApprovalTokenRejectedException::class.java)
            .hasMessageNotContaining(tokenDigest)
    }

    private suspend fun approvedChallenge(
        expiresAt: Instant = clock.instant().plusSeconds(3600),
    ) = coordinator.createApproval(createCommand(expiresAt = expiresAt)).also {
        store.transition(fixedApprovalId, 0L, ApprovalTransition.Approve("approver", "approved"))
    }

    private fun createCommand(
        workflowRunId: String = "wf-run-1",
        toolName: String = "tool-1",
        policyVersion: String = "policy-v1",
        requestedBy: String = "requester-1",
        expiresAt: Instant = clock.instant().plusSeconds(3600),
    ) = CreateApprovalCommand(
        workflowRunId = workflowRunId,
        toolName = toolName,
        argumentsDigest = argumentsDigest(),
        policyVersion = policyVersion,
        workflowDigest = workflowDigest(),
        requestedBy = requestedBy,
        expiresAt = expiresAt,
    )

    private fun authorizeCommand(
        challenge: dev.tramai.core.approval.ApprovalChallenge,
        expectedVersion: Long = 1L,
        presentedToken: ApprovalToken = challenge.token,
        consumedBy: String = "consumer-1",
        workflowRunId: String = "wf-run-1",
        toolName: String = "tool-1",
        argumentsDigest: Sha256Digest = argumentsDigest(),
        policyVersion: String = "policy-v1",
        workflowDigest: Sha256Digest = workflowDigest(),
    ) = AuthorizeResumeCommand(
        approvalId = challenge.approvalId,
        expectedVersion = expectedVersion,
        presentedToken = presentedToken,
        consumedBy = consumedBy,
        workflowRunId = workflowRunId,
        toolName = toolName,
        argumentsDigest = argumentsDigest,
        policyVersion = policyVersion,
        workflowDigest = workflowDigest,
    )

    private fun coordinator(
        store: InMemoryApprovalStore = this.store,
    ) = DefaultApprovalGateCoordinator(
        store = store,
        approvalIdGenerator = ApprovalIdGenerator { fixedApprovalId },
        approvalTokenGenerator = ApprovalTokenGenerator { ApprovalToken.parsePresented(issuedTokenRaw) },
        approvalTokenDigester = digester,
        decisionValidator = AllowAnyApprovalDecisionValidator,
        clock = clock,
    )

    private fun argumentsDigest(): Sha256Digest =
        Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000000")

    private fun workflowDigest(): Sha256Digest =
        Sha256Digest.of("sha256:1111111111111111111111111111111111111111111111111111111111111111")
}

private class CoordinatorMutableClock(
    private var now: Instant,
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun instant(): Instant = now

    override fun withZone(zone: ZoneId): Clock = CoordinatorMutableClock(now, zone)

    override fun getZone(): ZoneId = zone

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}
