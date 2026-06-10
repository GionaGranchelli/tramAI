package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalConsumptionReceipt
import dev.tramai.core.approval.ApprovalIdGenerator
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.ApprovalTokenDigester
import dev.tramai.core.approval.ApprovalTokenGenerator
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.exception.ApprovalAuthorizationException
import dev.tramai.core.exception.ApprovalBindingMismatchException
import dev.tramai.core.exception.ApprovalCreationException
import dev.tramai.core.exception.ApprovalException
import dev.tramai.core.exception.ApprovalFailureObserver
import dev.tramai.core.exception.ApprovalNotFoundException
import dev.tramai.core.exception.ApprovalTokenRejectedException
import dev.tramai.core.exception.ApprovalStoreConflictException
import dev.tramai.core.exception.ApprovalStoreNotConsumableException
import dev.tramai.core.exception.ApprovalStoreTokenRejectedException
import dev.tramai.core.exception.TramaiException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
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

        assertThatThrownBy {
            runBlocking { coordinator.createApproval(createCommand(toolName = "tool-2")) }
        }
            .isInstanceOf(ApprovalCreationException::class.java)
            .hasMessage("Approval creation failed")
    }

    @Test
    fun `exact binding approved valid token succeeds`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        val authorization = coordinator.authorizeResume(authorizeCommand(challenge))

        assertThat(authorization.approvalId).isEqualTo(fixedApprovalId)
        assertThat(authorization.consumedBy).isEqualTo("consumer-1")
        assertThat(authorization.consumedAt).isEqualTo(clock.instant())
        assertThat(authorization.version).isEqualTo(2L)
        assertThat(authorization.replayed).isFalse()
    }

    @Test
    fun `pending request rejected`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())

        assertThatThrownBy {
            runBlocking { coordinator.authorizeResume(authorizeCommand(challenge, expectedVersion = 0L)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
    }

    @Test
    fun `denied rejected`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())
        store.transition(fixedApprovalId, 0L, ApprovalTransition.Deny("approver", null))

        assertThatThrownBy {
            runBlocking { coordinator.authorizeResume(authorizeCommand(challenge)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
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

        assertThatThrownBy {
            runBlocking { localCoordinator.authorizeResume(authorizeCommand(localChallenge, expectedVersion = 1L)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)

        assertThat(challenge.approvalId).isEqualTo(fixedApprovalId)
    }

    @Test
    fun `expired rejected`() : Unit = runBlocking {
        val challenge = approvedChallenge(expiresAt = clock.instant().plusSeconds(5))
        clock.advance(Duration.ofSeconds(6))

        assertThatThrownBy {
            runBlocking { coordinator.authorizeResume(authorizeCommand(challenge)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
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
    fun `validateResume rejects wrong token`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking {
                coordinator.validateResume(
                    validateCommand(challenge, presentedToken = ApprovalToken.parsePresented("wrong-token")),
                )
            }
        }
            .isInstanceOf(ApprovalTokenRejectedException::class.java)
    }

    @Test
    fun `validateResume accepts exact replay candidate`() : Unit = runBlocking {
        val challenge = approvedChallenge()
        coordinator.authorizeResume(authorizeCommand(challenge))

        val validation = coordinator.validateResume(validateCommand(challenge))

        assertThat(validation.approvalId).isEqualTo(fixedApprovalId)
        assertThat(validation.validatedBy).isEqualTo("consumer-1")
        assertThat(validation.version).isEqualTo(1L)
    }

    @Test
    fun `same command replay succeeds with replayed true`() : Unit = runBlocking {
        val challenge = approvedChallenge()
        val first = coordinator.authorizeResume(authorizeCommand(challenge))
        val replay = coordinator.authorizeResume(authorizeCommand(challenge))

        assertThat(first.replayed).isFalse()
        assertThat(replay.replayed).isTrue()
        assertThat(replay.version).isEqualTo(2L)
        assertThat(replay.consumedAt).isEqualTo(first.consumedAt)
    }

    @Test
    fun `changed expected version after consumption is rejected`() : Unit = runBlocking {
        val challenge = approvedChallenge()
        coordinator.authorizeResume(authorizeCommand(challenge))

        assertThatThrownBy {
            runBlocking { coordinator.authorizeResume(authorizeCommand(challenge, expectedVersion = 2L)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
    }

    @Test
    fun `changed actor rejected after consumption`() : Unit = runBlocking {
        val challenge = approvedChallenge()
        coordinator.authorizeResume(authorizeCommand(challenge))

        assertThatThrownBy {
            runBlocking {
                coordinator.authorizeResume(authorizeCommand(challenge, consumedBy = "consumer-2"))
            }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
    }

    @Test
    fun `stale version rejected`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking { coordinator.authorizeResume(authorizeCommand(challenge, expectedVersion = 0L)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
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
    fun `validateResume changed binding is rejected`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking {
                coordinator.validateResume(validateCommand(challenge, toolName = "tool-2"))
            }
        }
            .isInstanceOf(ApprovalBindingMismatchException::class.java)
            .hasMessage("Approval binding mismatch for '$fixedApprovalId': toolName")
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
    fun `concurrent identical calls yield one fresh authorization and one replay`() : Unit = runBlocking {
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

        assertThat(settled.count { it.isSuccess }).isEqualTo(2)
        assertThat(settled.count { it.isFailure }).isEqualTo(0)
        val authorizations = settled.map { it.getOrThrow() }
        assertThat(authorizations.count { !it.replayed }).isEqualTo(1)
        assertThat(authorizations.count { it.replayed }).isEqualTo(1)
        assertThat(store.get(fixedApprovalId)!!.consumedAt).isNotNull()
    }

    @Test
    fun `concurrent different actors allow only original consumer`() : Unit = runBlocking {
        val challenge = approvedChallenge()
        val start = CountDownLatch(1)

        val results = listOf(
            async(Dispatchers.Default) {
                start.await()
                runCatching { coordinator.authorizeResume(authorizeCommand(challenge, consumedBy = "consumer-1")) }
            },
            async(Dispatchers.Default) {
                start.await()
                runCatching { coordinator.authorizeResume(authorizeCommand(challenge, consumedBy = "consumer-2")) }
            },
        )

        start.countDown()
        val settled = results.awaitAll()

        assertThat(settled.count { it.isSuccess }).isEqualTo(1)
        assertThat(settled.count { it.isFailure }).isEqualTo(1)
        assertThat(settled.single { it.isSuccess }.getOrThrow().consumedBy).isIn("consumer-1", "consumer-2")
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

    @Test
    fun `createApproval rejects expiry beyond max TTL`() : Unit = runBlocking {
        val tooFar = clock.instant().plusSeconds(1800) // 30 min > 15 min TTL

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking { coordinator.createApproval(createCommand(expiresAt = tooFar)) }
            }
            .withMessageContaining("expiresAt must be within")
            .withMessageContaining("PT15M")
    }

    @Test
    fun `createApproval accepts expiry at max TTL boundary`() : Unit = runBlocking {
        val atBoundary = clock.instant().plusSeconds(900) // exactly 15 min

        val challenge = coordinator.createApproval(createCommand(expiresAt = atBoundary))

        assertThat(challenge.expiresAt).isEqualTo(atBoundary)
    }

    @Test
    fun `createApproval with custom maxApprovalTtl accepts valid expiry`() : Unit = runBlocking {
        val customCoordinator = DefaultApprovalGateCoordinator(
            store = store,
            approvalIdGenerator = ApprovalIdGenerator { "custom-ttl-test" },
            approvalTokenGenerator = ApprovalTokenGenerator { ApprovalToken.parsePresented(issuedTokenRaw) },
            approvalTokenDigester = digester,
            maxApprovalTtl = Duration.ofMinutes(5),
            clock = clock,
        )

        val inBounds = clock.instant().plusSeconds(240) // 4 min < 5 min TTL
        val challenge = customCoordinator.createApproval(createCommand(expiresAt = inBounds))
        assertThat(challenge.expiresAt).isEqualTo(inBounds)
    }

    @Test
    fun `createApproval with custom maxApprovalTtl rejects exceeded expiry`() : Unit = runBlocking {
        val customCoordinator = DefaultApprovalGateCoordinator(
            store = store,
            approvalIdGenerator = ApprovalIdGenerator { "custom-ttl-reject" },
            approvalTokenGenerator = ApprovalTokenGenerator { ApprovalToken.parsePresented(issuedTokenRaw) },
            approvalTokenDigester = digester,
            maxApprovalTtl = Duration.ofMinutes(5),
            clock = clock,
        )

        val tooFar = clock.instant().plusSeconds(600) // 10 min > 5 min TTL
        assertThatIllegalArgumentException()
            .isThrownBy { runBlocking { customCoordinator.createApproval(createCommand(expiresAt = tooFar)) } }
            .withMessageContaining("expiresAt must be within")
    }

    @Test
    fun `authorizeResume validates workflowRunId`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    coordinator.authorizeResume(
                        authorizeCommand(challenge, workflowRunId = "  "),
                    )
                }
            }
            .withMessageContaining("workflowRunId must not be blank")
    }

    @Test
    fun `authorizeResume validates toolName`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    coordinator.authorizeResume(
                        authorizeCommand(challenge, toolName = "  "),
                    )
                }
            }
            .withMessageContaining("toolName must not be blank")
    }

    @Test
    fun `authorizeResume validates policyVersion`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    coordinator.authorizeResume(
                        authorizeCommand(challenge, policyVersion = "  "),
                    )
                }
            }
            .withMessageContaining("policyVersion must not be blank")
    }

    @Test
    fun `authorizeResume validates expectedVersion non-negative`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    coordinator.authorizeResume(
                        authorizeCommand(challenge, expectedVersion = -1),
                    )
                }
            }
            .withMessage("expectedVersion must be non-negative")
    }

    @Test
    fun `store not found maps to typed exception`() : Unit = runBlocking {
        assertThatThrownBy {
            runBlocking {
                coordinator.authorizeResume(
                    AuthorizeResumeCommand(
                        approvalId = "nonexistent",
                        expectedVersion = 1L,
                        presentedToken = ApprovalToken.parsePresented("some-token"),
                        consumedBy = "consumer-1",
                        workflowRunId = "wf-run-1",
                        toolName = "tool-1",
                        argumentsDigest = argumentsDigest(),
                        policyVersion = "policy-v1",
                        workflowDigest = workflowDigest(),
                    ),
                )
            }
        }
            .isInstanceOf(ApprovalNotFoundException::class.java)
            .hasMessage("Approval not found: 'nonexistent'")
    }

    // -----------------------------------------------------------------------
    // Exception taxonomy tests (CHANGE 3)
    // -----------------------------------------------------------------------

    @Test
    fun `every coordinator-facing exception is an ApprovalException`() : Unit = runBlocking {
        assertThat(ApprovalAuthorizationException::class.java.constructors).isNotEmpty
        assertThat(ApprovalAuthorizationException::class.java.superclass).isEqualTo(ApprovalException::class.java)
        assertThat(ApprovalCreationException::class.java.superclass).isEqualTo(ApprovalException::class.java)
        assertThat(ApprovalNotFoundException::class.java.superclass).isEqualTo(ApprovalException::class.java)
        assertThat(ApprovalTokenRejectedException::class.java.superclass).isEqualTo(ApprovalException::class.java)
        assertThat(ApprovalBindingMismatchException::class.java.superclass).isEqualTo(ApprovalException::class.java)
    }

    @Test
    fun `every coordinator-facing exception is a TramaiException`() : Unit = runBlocking {
        assertThat(TramaiException::class.java.isAssignableFrom(ApprovalAuthorizationException::class.java)).isTrue
        assertThat(TramaiException::class.java.isAssignableFrom(ApprovalCreationException::class.java)).isTrue
        assertThat(TramaiException::class.java.isAssignableFrom(ApprovalNotFoundException::class.java)).isTrue
        assertThat(TramaiException::class.java.isAssignableFrom(ApprovalTokenRejectedException::class.java)).isTrue
        assertThat(TramaiException::class.java.isAssignableFrom(ApprovalBindingMismatchException::class.java)).isTrue
    }

    @Test
    fun `store-internal exceptions are NOT exposed by coordinator paths`() : Unit = runBlocking {
        // ApprovalStoreConflictException should be mapped to ApprovalCreationException or ApprovalAuthorizationException
        val conflictStore = object : ApprovalStore by store {
            override suspend fun create(request: ApprovalRequest): ApprovalRequest {
                throw ApprovalStoreConflictException("test-id")
            }
        }
        val conflictCoordinator = coordinator(store = conflictStore)
        assertThatThrownBy {
            runBlocking { conflictCoordinator.createApproval(createCommand()) }
        }
            .isInstanceOf(ApprovalCreationException::class.java)
            .isNotInstanceOf(ApprovalStoreConflictException::class.java)

        // ApprovalStoreNotConsumableException should be mapped to ApprovalAuthorizationException
        val challenge = coordinator.createApproval(createCommand()).also {
            store.transition(fixedApprovalId, 0L, ApprovalTransition.Approve("approver", "approved"))
        }
        val notConsumableStore = object : ApprovalStore by store {
            override suspend fun consumeApprovedOrReplay(
                approvalId: String,
                expectedVersion: Long,
                presentedTokenDigest: Sha256Digest,
                consumedBy: String,
            ): ApprovalConsumptionReceipt {
                throw ApprovalStoreNotConsumableException("test-id")
            }
        }
        val notConsumableCoordinator = coordinator(store = notConsumableStore)
        assertThatThrownBy {
            runBlocking { notConsumableCoordinator.authorizeResume(authorizeCommand(challenge)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
            .isNotInstanceOf(ApprovalStoreNotConsumableException::class.java)

        // ApprovalStoreTokenRejectedException should be mapped to ApprovalTokenRejectedException
        val tokenRejectedStore = object : ApprovalStore by store {
            override suspend fun consumeApprovedOrReplay(
                approvalId: String,
                expectedVersion: Long,
                presentedTokenDigest: Sha256Digest,
                consumedBy: String,
            ): ApprovalConsumptionReceipt {
                throw ApprovalStoreTokenRejectedException("test-id")
            }
        }
        val tokenRejectedCoordinator = coordinator(store = tokenRejectedStore)
        val secondCoord = DefaultApprovalGateCoordinator(
            store = store,
            approvalIdGenerator = ApprovalIdGenerator { "approval-fixed-2" },
            approvalTokenGenerator = ApprovalTokenGenerator { ApprovalToken.parsePresented(issuedTokenRaw) },
            approvalTokenDigester = digester,
            clock = clock,
        )
        val challenge2 = secondCoord.createApproval(createCommand()).also {
            store.transition("approval-fixed-2", 0L, ApprovalTransition.Approve("approver", "approved"))
        }
        assertThatThrownBy {
            runBlocking { tokenRejectedCoordinator.authorizeResume(authorizeCommand(challenge2)) }
        }
            .isInstanceOf(ApprovalTokenRejectedException::class.java)
            .isNotInstanceOf(ApprovalStoreTokenRejectedException::class.java)
    }

    // -----------------------------------------------------------------------
    // Non-interfering observer tests (CHANGE 1)
    // -----------------------------------------------------------------------

    @Test
    fun `throwing failureObserver does not bypass safe exception on create`() : Unit = runBlocking {
        val throwingObserver = ApprovalFailureObserver { _, _, _ ->
            throw RuntimeException("observer-secret-marker")
        }
        val throwingCoordinator = coordinator(
            store = store,
            failureObserver = throwingObserver,
        )
        val leakyStore2 = object : ApprovalStore by store {
            override suspend fun create(request: ApprovalRequest): ApprovalRequest {
                throw RuntimeException("store-secret-marker")
            }
        }
        val throwingCoordinator2 = coordinator(
            store = leakyStore2,
            failureObserver = throwingObserver,
        )

        val ex = runCatching {
            throwingCoordinator2.createApproval(createCommand())
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(ApprovalCreationException::class.java)
        assertThat(ex).hasMessage("Approval creation failed")

        // Verify no secret marker leaks into message, toString(), or cause chain
        assertThat(ex!!.message).doesNotContain("observer-secret-marker")
        assertThat(ex!!.cause).isNull()
    }

    @Test
    fun `RuntimeException from observer is swallowed`() : Unit = runBlocking {
        val throwingObserver = ApprovalFailureObserver { _, _, _ ->
            throw RuntimeException("observer-secret-marker")
        }
        val leakyStore = object : ApprovalStore by store {
            override suspend fun get(approvalId: String): ApprovalRequest? {
                throw RuntimeException("store-secret-marker")
            }
        }
        val coord = coordinator(
            store = leakyStore,
            failureObserver = throwingObserver,
        )

        val ex = runCatching {
            coord.authorizeResume(authorizeCommand(approvedChallenge()))
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(ApprovalAuthorizationException::class.java)
        assertThat(ex!!.message).doesNotContain("observer-secret-marker")
        assertThat(ex!!.cause).isNull()
    }

    @Test
    fun `safe public exception remains visible`() : Unit = runBlocking {
        val safeObserver = ApprovalFailureObserver { _, _, _ -> }
        val leakyStore = object : ApprovalStore by store {
            override suspend fun get(approvalId: String): ApprovalRequest? {
                throw RuntimeException("store-secret-marker")
            }
        }
        val coord = coordinator(
            store = leakyStore,
            failureObserver = safeObserver,
        )

        assertThatThrownBy {
            runBlocking { coord.authorizeResume(authorizeCommand(approvedChallenge())) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
            .hasMessage("Approval authorization failed")
    }

    @Test
    fun `checked IOException is sanitized without secret leakage`() : Unit = runBlocking {
        val leakyStore = object : ApprovalStore by store {
            override suspend fun get(approvalId: String): ApprovalRequest? {
                throw IOException("io-secret-marker")
            }
        }
        val coord = coordinator(store = leakyStore)

        val ex = runCatching {
            coord.authorizeResume(authorizeCommand(approvedChallenge()))
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(ApprovalAuthorizationException::class.java)
        assertThat(ex).hasMessage("Approval authorization failed")
        assertThat(ex!!.message).doesNotContain("io-secret-marker")
        assertThat(ex.cause).isNull()
    }

    @Test
    fun `CancellationException propagates unchanged`() : Unit = runBlocking {
        val cancellation = CancellationException("cancel-secret")
        val cancellingStore = object : ApprovalStore by store {
            override suspend fun get(approvalId: String): ApprovalRequest? {
                throw cancellation
            }
        }
        val coord = coordinator(store = cancellingStore)

        val ex = catchThrowableOfType(
            { runBlocking { coord.authorizeResume(authorizeCommand(approvedChallenge())) } },
            CancellationException::class.java,
        )

        assertThat(ex).isSameAs(cancellation)
    }

    @Test
    fun `Error from observer is not swallowed`() : Unit = runBlocking {
        val throwingObserver = ApprovalFailureObserver { _, _, _ ->
            throw Error("fatal-error")
        }
        val leakyStore = object : ApprovalStore by store {
            override suspend fun get(approvalId: String): ApprovalRequest? {
                throw RuntimeException("store-secret-marker")
            }
        }
        val coord = coordinator(
            store = leakyStore,
            failureObserver = throwingObserver,
        )

        assertThatThrownBy {
            runBlocking { coord.authorizeResume(authorizeCommand(approvedChallenge())) }
        }
            .isInstanceOf(Error::class.java)
            .hasMessage("fatal-error")
    }

    @Test
    fun `Long MAX_VALUE expectedVersion rejected`() : Unit = runBlocking {
        val challenge = approvedChallenge()

        assertThatIllegalArgumentException()
            .isThrownBy {
                runBlocking {
                    coordinator.authorizeResume(
                        authorizeCommand(challenge, expectedVersion = Long.MAX_VALUE),
                    )
                }
            }
            .withMessage("expectedVersion must be less than Long.MAX_VALUE")
    }

    @Test
    fun `normal version 0 to 1 to 2 path still succeeds`() : Unit = runBlocking {
        val challenge = coordinator.createApproval(createCommand())
        store.transition(fixedApprovalId, 0L, ApprovalTransition.Approve("approver", "approved"))

        val auth1 = coordinator.authorizeResume(authorizeCommand(challenge, expectedVersion = 1L))
        assertThat(auth1.version).isEqualTo(2L)
    }

    @Test
    fun `throwing failureObserver does not bypass safe exception on authorizeResume`() : Unit = runBlocking {
        val throwingObserver = ApprovalFailureObserver { _, _, _ ->
            throw RuntimeException("observer-secret-marker")
        }
        val leakyStore = object : ApprovalStore by store {
            override suspend fun get(approvalId: String): ApprovalRequest? {
                throw RuntimeException("store-secret-marker")
            }
        }
        val throwingCoordinator = coordinator(
            store = leakyStore,
            failureObserver = throwingObserver,
        )
        val challenge = coordinator.createApproval(createCommand()).also {
            store.transition(fixedApprovalId, 0L, ApprovalTransition.Approve("approver", "approved"))
        }

        val ex = runCatching {
            throwingCoordinator.authorizeResume(authorizeCommand(challenge))
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(ApprovalAuthorizationException::class.java)
        assertThat(ex).hasMessage("Approval authorization failed")

        // Verify no secret marker leaks
        assertThat(ex!!.message).doesNotContain("observer-secret-marker")
        assertThat(ex!!.cause).isNull()

        // Also test consumeApproved path
        val leakyConsumeStore = object : ApprovalStore by store {
            override suspend fun consumeApprovedOrReplay(
                approvalId: String,
                expectedVersion: Long,
                presentedTokenDigest: Sha256Digest,
                consumedBy: String,
            ): ApprovalConsumptionReceipt {
                throw RuntimeException("store-secret-marker")
            }
        }
        val throwingCoordinator2 = coordinator(
            store = leakyConsumeStore,
            failureObserver = throwingObserver,
        )
        val secondCoord = DefaultApprovalGateCoordinator(
            store = store,
            approvalIdGenerator = ApprovalIdGenerator { "approval-fixed-2" },
            approvalTokenGenerator = ApprovalTokenGenerator { ApprovalToken.parsePresented(issuedTokenRaw) },
            approvalTokenDigester = digester,
            clock = clock,
        )
        val challenge2 = secondCoord.createApproval(createCommand()).also {
            store.transition("approval-fixed-2", 0L, ApprovalTransition.Approve("approver", "approved"))
        }

        val ex2 = runCatching {
            throwingCoordinator2.authorizeResume(authorizeCommand(challenge2))
        }.exceptionOrNull()

        assertThat(ex2).isInstanceOf(ApprovalAuthorizationException::class.java)
        assertThat(ex2).hasMessage("Approval authorization failed")

        assertThat(ex2!!.message).doesNotContain("observer-secret-marker")
        assertThat(ex2!!.cause).isNull()
    }

    // -----------------------------------------------------------------------
    // Consumed-result contract validation tests (CHANGE 2)
    // -----------------------------------------------------------------------

    @Test
    fun `custom store with wrong approvalId throws ApprovalAuthorizationException`() : Unit = runBlocking {
        val fakeStore = object : ApprovalStore by store {
            override suspend fun consumeApprovedOrReplay(
                approvalId: String,
                expectedVersion: Long,
                presentedTokenDigest: Sha256Digest,
                consumedBy: String,
            ): ApprovalConsumptionReceipt {
                val stored = store.get(approvalId)!!
                return receipt(stored.copy(
                    approvalId = "different-id",
                    consumedBy = consumedBy,
                    consumedAt = clock.instant(),
                    version = stored.version + 1,
                ))
            }
        }
        val fakeCoordinator = coordinator(store = fakeStore)
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking { fakeCoordinator.authorizeResume(authorizeCommand(challenge)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
            .hasMessage("Approval authorization failed")
    }

    @Test
    fun `custom store with altered binding throws ApprovalAuthorizationException`() : Unit = runBlocking {
        val fakeStore = object : ApprovalStore by store {
            override suspend fun consumeApprovedOrReplay(
                approvalId: String,
                expectedVersion: Long,
                presentedTokenDigest: Sha256Digest,
                consumedBy: String,
            ): ApprovalConsumptionReceipt {
                val stored = store.get(approvalId)!!
                return receipt(stored.copy(
                    binding = stored.binding.copy(toolName = "hacked-tool"),
                    consumedBy = consumedBy,
                    consumedAt = clock.instant(),
                    version = stored.version + 1,
                ))
            }
        }
        val fakeCoordinator = coordinator(store = fakeStore)
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking { fakeCoordinator.authorizeResume(authorizeCommand(challenge)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
            .hasMessage("Approval authorization failed")
    }

    @Test
    fun `custom store with PENDING status throws ApprovalAuthorizationException`() : Unit = runBlocking {
        val fakeStore = object : ApprovalStore by store {
            override suspend fun consumeApprovedOrReplay(
                approvalId: String,
                expectedVersion: Long,
                presentedTokenDigest: Sha256Digest,
                consumedBy: String,
            ): ApprovalConsumptionReceipt {
                val stored = store.get(approvalId)!!
                return receipt(stored.copy(
                    status = ApprovalStatus.PENDING,
                    consumedBy = consumedBy,
                    consumedAt = clock.instant(),
                    version = stored.version + 1,
                ))
            }
        }
        val fakeCoordinator = coordinator(store = fakeStore)
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking { fakeCoordinator.authorizeResume(authorizeCommand(challenge)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
            .hasMessage("Approval authorization failed")
    }

    @Test
    fun `custom store with wrong consumedBy throws ApprovalAuthorizationException`() : Unit = runBlocking {
        val fakeStore = object : ApprovalStore by store {
            override suspend fun consumeApprovedOrReplay(
                approvalId: String,
                expectedVersion: Long,
                presentedTokenDigest: Sha256Digest,
                consumedBy: String,
            ): ApprovalConsumptionReceipt {
                val stored = store.get(approvalId)!!
                return receipt(stored.copy(
                    consumedBy = "wrong-consumer",
                    consumedAt = clock.instant(),
                    version = stored.version + 1,
                ))
            }
        }
        val fakeCoordinator = coordinator(store = fakeStore)
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking { fakeCoordinator.authorizeResume(authorizeCommand(challenge)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
            .hasMessage("Approval authorization failed")
    }

    @Test
    fun `custom store with null consumedBy throws ApprovalAuthorizationException`() : Unit = runBlocking {
        val fakeStore = object : ApprovalStore by store {
            override suspend fun consumeApprovedOrReplay(
                approvalId: String,
                expectedVersion: Long,
                presentedTokenDigest: Sha256Digest,
                consumedBy: String,
            ): ApprovalConsumptionReceipt {
                val stored = store.get(approvalId)!!
                return receipt(stored.copy(
                    consumedBy = null,
                    consumedAt = clock.instant(),
                    version = stored.version + 1,
                ))
            }
        }
        val fakeCoordinator = coordinator(store = fakeStore)
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking { fakeCoordinator.authorizeResume(authorizeCommand(challenge)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
            .hasMessage("Approval authorization failed")
    }

    @Test
    fun `custom store with null consumedAt throws ApprovalAuthorizationException`() : Unit = runBlocking {
        val fakeStore = object : ApprovalStore by store {
            override suspend fun consumeApprovedOrReplay(
                approvalId: String,
                expectedVersion: Long,
                presentedTokenDigest: Sha256Digest,
                consumedBy: String,
            ): ApprovalConsumptionReceipt {
                val stored = store.get(approvalId)!!
                return receipt(stored.copy(
                    consumedBy = consumedBy,
                    consumedAt = null,
                    version = stored.version + 1,
                ))
            }
        }
        val fakeCoordinator = coordinator(store = fakeStore)
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking { fakeCoordinator.authorizeResume(authorizeCommand(challenge)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
            .hasMessage("Approval authorization failed")
    }

    @Test
    fun `custom store with wrong version throws ApprovalAuthorizationException`() : Unit = runBlocking {
        val fakeStore = object : ApprovalStore by store {
            override suspend fun consumeApprovedOrReplay(
                approvalId: String,
                expectedVersion: Long,
                presentedTokenDigest: Sha256Digest,
                consumedBy: String,
            ): ApprovalConsumptionReceipt {
                val stored = store.get(approvalId)!!
                return receipt(stored.copy(
                    consumedBy = consumedBy,
                    consumedAt = clock.instant(),
                    version = stored.version, // not incremented
                ))
            }
        }
        val fakeCoordinator = coordinator(store = fakeStore)
        val challenge = approvedChallenge()

        assertThatThrownBy {
            runBlocking { fakeCoordinator.authorizeResume(authorizeCommand(challenge)) }
        }
            .isInstanceOf(ApprovalAuthorizationException::class.java)
            .hasMessage("Approval authorization failed")
    }

    // -----------------------------------------------------------------------
    // Leaky-store tests
    // -----------------------------------------------------------------------

    private fun containsSecret(throwable: Throwable, secret: String): Boolean {
        // Check this throwable
        if (throwable.message?.contains(secret) == true) return true
        if (throwable.toString().contains(secret)) return true
        // Check suppressed
        for (suppressed in throwable.suppressed) {
            if (containsSecret(suppressed, secret)) return true
        }
        // Check cause chain
        val cause = throwable.cause
        if (cause != null && containsSecret(cause, secret)) return true
        return false
    }

    @Test
    fun `leaky store get secret not in exception tree`() : Unit = runBlocking {
        val leakyStore = object : ApprovalStore by store {
            override suspend fun get(approvalId: String): ApprovalRequest? {
                throw RuntimeException("secret-digest-value")
            }
        }
        val leakyCoordinator = coordinator(store = leakyStore)
        val challenge = approvedChallenge()

        val ex = runCatching {
            leakyCoordinator.authorizeResume(authorizeCommand(challenge))
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(ApprovalAuthorizationException::class.java)
        assertThat(containsSecret(ex!!, "secret-digest-value")).isFalse
    }

    @Test
    fun `leaky store create secret not in exception tree`() : Unit = runBlocking {
        val leakyStore = object : ApprovalStore by store {
            override suspend fun create(request: ApprovalRequest): ApprovalRequest {
                throw RuntimeException("secret-digest-value")
            }
        }
        val leakyCoordinator = coordinator(store = leakyStore)

        val ex = runCatching {
            leakyCoordinator.createApproval(createCommand())
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(ApprovalCreationException::class.java)
        assertThat(containsSecret(ex!!, "secret-digest-value")).isFalse
    }

    @Test
    fun `leaky store consumeApproved secret not in exception tree`() : Unit = runBlocking {
        val leakyStore = object : ApprovalStore by store {
            override suspend fun consumeApprovedOrReplay(
                approvalId: String,
                expectedVersion: Long,
                presentedTokenDigest: Sha256Digest,
                consumedBy: String,
            ): ApprovalConsumptionReceipt {
                throw RuntimeException("secret-digest-value")
            }
        }
        val leakyCoordinator = coordinator(store = leakyStore)
        val challenge = approvedChallenge()

        val ex = runCatching {
            leakyCoordinator.authorizeResume(authorizeCommand(challenge))
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(ApprovalAuthorizationException::class.java)
        assertThat(ex).hasMessage("Approval authorization failed")
        assertThat(containsSecret(ex!!, "secret-digest-value")).isFalse
    }

    @Test
    fun `throwing observer secret not in exception tree`() : Unit = runBlocking {
        val throwingObserver = ApprovalFailureObserver { _, _, _ ->
            throw RuntimeException("observer-secret-marker")
        }
        val leakyStore = object : ApprovalStore by store {
            override suspend fun get(approvalId: String): ApprovalRequest? {
                throw RuntimeException("store-secret-marker")
            }
        }
        val throwingCoordinator = coordinator(
            store = leakyStore,
            failureObserver = throwingObserver,
        )
        val challenge = coordinator.createApproval(createCommand()).also {
            store.transition(fixedApprovalId, 0L, ApprovalTransition.Approve("approver", "approved"))
        }

        val ex = runCatching {
            throwingCoordinator.authorizeResume(authorizeCommand(challenge))
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(ApprovalAuthorizationException::class.java)
        assertThat(containsSecret(ex!!, "observer-secret-marker")).isFalse
        assertThat(containsSecret(ex!!, "store-secret-marker")).isFalse
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private suspend fun approvedChallenge(
        expiresAt: Instant = clock.instant().plusSeconds(300),
    ) = coordinator.createApproval(createCommand(expiresAt = expiresAt)).also {
        store.transition(fixedApprovalId, 0L, ApprovalTransition.Approve("approver", "approved"))
    }

    private fun coordinator(
        store: ApprovalStore = this.store,
        failureObserver: ApprovalFailureObserver? = null,
    ) = DefaultApprovalGateCoordinator(
        store = store,
        approvalIdGenerator = ApprovalIdGenerator { fixedApprovalId },
        approvalTokenGenerator = ApprovalTokenGenerator { ApprovalToken.parsePresented(issuedTokenRaw) },
        approvalTokenDigester = digester,
        decisionValidator = AllowAnyApprovalDecisionValidator,
        clock = clock,
        failureObserver = failureObserver,
    )

    private fun createCommand(
        workflowRunId: String = "wf-run-1",
        toolName: String = "tool-1",
        policyVersion: String = "policy-v1",
        requestedBy: String = "requester-1",
        expiresAt: Instant = clock.instant().plusSeconds(300),
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

    private fun validateCommand(
        challenge: dev.tramai.core.approval.ApprovalChallenge,
        expectedVersion: Long = 1L,
        presentedToken: ApprovalToken = challenge.token,
        consumedBy: String = "consumer-1",
        workflowRunId: String = "wf-run-1",
        toolName: String = "tool-1",
        argumentsDigest: Sha256Digest = argumentsDigest(),
        policyVersion: String = "policy-v1",
        workflowDigest: Sha256Digest = workflowDigest(),
    ) = ValidateResumeCommand(
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

    private fun receipt(
        request: ApprovalRequest,
        replayed: Boolean = false,
    ) = ApprovalConsumptionReceipt(
        request = request,
        replayed = replayed,
    )

    private fun argumentsDigest(): Sha256Digest =
        Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000000")

    private fun workflowDigest(): Sha256Digest =
        Sha256Digest.of("sha256:1111111111111111111111111111111111111111111111111111111111111111")
}

private class CoordinatorMutableClock(
    @Volatile private var now: Instant,
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun instant(): Instant = now

    override fun withZone(zone: ZoneId): Clock = CoordinatorMutableClock(now, zone)

    override fun getZone(): ZoneId = zone

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}
