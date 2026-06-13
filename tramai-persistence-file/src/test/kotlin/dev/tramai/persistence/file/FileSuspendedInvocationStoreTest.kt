package dev.tramai.persistence.file

import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.CompatibilityMode
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.TokenBudgetSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSuspendedInvocationStoreTest {

    private val rootDir: Path = Files.createTempDirectory("tramai-suspended-test-").toAbsolutePath()

    private fun testKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val testKey = testKey()
    private val keyProvider = FileStoreEncryptionKeyProvider { testKey }

    private fun createConfig(
        verifyOnOpen: Boolean = false,
        keyProvider: FileStoreEncryptionKeyProvider = this.keyProvider,
    ) = FileBackedStoreConfiguration(
        rootDirectory = rootDir,
        encryption = FileStoreEncryptionConfiguration(
            activeKeyId = "test-key",
            keyProvider = keyProvider,
        ),
        verifyOnOpen = verifyOnOpen,
    )

    private fun createStore(
        key: SecretKey = testKey,
        lease: FileStoreLease = FileStoreLease(),
    ) = FileSuspendedInvocationStore(rootDir, key, createConfig(), lease)

    @BeforeEach
    fun setup() {
        Files.createDirectories(rootDir.resolve("suspended"))
        Files.setPosixFilePermissions(
            rootDir.resolve("suspended"),
            PosixFilePermissions.fromString("rwx------"),
        )
    }

    @AfterEach
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `lifecycle create get reveal remove`() = runBlocking {
        val store = createStore()
        val (metadata, envelope) = createValidRecord()

        store.create(metadata, envelope)

        val retrieved = store.get(metadata.approvalId)
        assertNotNull(retrieved)
        assertEquals(metadata.approvalId, retrieved.approvalId)

        val revealed = store.revealReplayEnvelope(metadata.approvalId)
        assertNotNull(revealed)
        assertEquals(
            envelope.revealForResume().messages,
            revealed.revealForResume().messages,
        )

        val removed = store.remove(metadata.approvalId)
        assertNotNull(removed)
        assertEquals(metadata.approvalId, removed.approvalId)
        assertNull(store.get(metadata.approvalId))
    }

    @Test
    fun `restart reopen preserves suspended invocation`() = runBlocking {
        val (metadata, envelope) = createValidRecord()

        createStore().create(metadata, envelope)

        val reopened = createStore()
        val retrieved = reopened.get(metadata.approvalId)
        assertNotNull(retrieved)
        assertEquals(metadata.approvalId, retrieved.approvalId)
        assertNotNull(reopened.revealReplayEnvelope(metadata.approvalId))
    }

    @Test
    fun `encrypted file does not expose sensitive plaintext`() = runBlocking {
        val store = createStore()
        val workflowRunId = "workflow-run-sensitive-123"
        val correlationId = "corr-sensitive-456"
        val promptSecret = "TOP-SECRET-PROMPT"
        val (metadata, envelope) = createValidRecord(
            approvalId = "approval-confidentiality-1",
            workflowRunId = workflowRunId,
            correlationId = correlationId,
            promptSecret = promptSecret,
        )

        store.create(metadata, envelope)

        val fileContent = recordPath(metadata.approvalId).readText()
        assertFalse(fileContent.contains(promptSecret))
        assertFalse(fileContent.contains("""{"accountId":"A-42","amount":99}"""))
        assertFalse(fileContent.contains(workflowRunId))
        assertFalse(fileContent.contains(correlationId))
    }

    @Test
    fun `duplicate create rejected`() = runBlocking {
        val store = createStore()
        val (metadata, envelope) = createValidRecord()

        store.create(metadata, envelope)

        assertThrows<IllegalArgumentException> {
            runBlocking { store.create(metadata, envelope) }
        }
    }

    @Test
    fun `wrong key rejects suspended invocation reads`() = runBlocking {
        val store = createStore()
        val (metadata, envelope) = createValidRecord()
        store.create(metadata, envelope)

        val wrongStore = createStore(key = testKey())

        assertThrows<FileStoreCorruptionException> {
            runBlocking { wrongStore.get(metadata.approvalId) }
        }
        assertThrows<FileStoreCorruptionException> {
            runBlocking { wrongStore.revealReplayEnvelope(metadata.approvalId) }
        }
    }

    @Test
    fun `ciphertext tampering rejected`() = runBlocking {
        val store = createStore()
        val (metadata, envelope) = createValidRecord()
        store.create(metadata, envelope)

        val path = recordPath(metadata.approvalId)
        val encrypted = EncryptedFileEnvelopeV1.fromJson(path.readText())
        val corrupted = encrypted.copy(
            ciphertextBase64 = encrypted.ciphertextBase64.dropLast(1) + "X",
        )
        Files.writeString(path, corrupted.toJson())

        assertThrows<FileStoreCorruptionException> {
            runBlocking { store.get(metadata.approvalId) }
        }
        assertThrows<FileStoreCorruptionException> {
            runBlocking { store.revealReplayEnvelope(metadata.approvalId) }
        }
    }

    @Test
    fun `filename substitution rejected`() = runBlocking {
        val store = createStore()
        val (metadata, envelope) = createValidRecord(approvalId = "approval-substitution-a")
        store.create(metadata, envelope)

        val originalPath = recordPath(metadata.approvalId)
        val substitutedApprovalId = "approval-substitution-b"
        Files.move(originalPath, recordPath(substitutedApprovalId))

        assertNull(store.get(metadata.approvalId))
        assertThrows<FileStoreCorruptionException> {
            runBlocking { store.get(substitutedApprovalId) }
        }
    }

    @Test
    fun `startup verification fails for corrupted suspended record`() = runBlocking {
        val (metadata, envelope) = createValidRecord(approvalId = "approval-verify-open-1")
        createStore().create(metadata, envelope)

        val path = recordPath(metadata.approvalId)
        val encrypted = EncryptedFileEnvelopeV1.fromJson(path.readText())
        val corrupted = encrypted.copy(
            ciphertextBase64 = encrypted.ciphertextBase64.dropLast(1) + "X",
        )
        Files.writeString(path, corrupted.toJson())

        assertThrows<FileStoreCorruptionException> {
            FileBackedSovereignStores.open(createConfig(verifyOnOpen = true))
        }
    }

    @Test
    fun `unexpected file in suspended directory rejected`() = runBlocking {
        val store = createStore()
        Files.writeString(rootDir.resolve("suspended/foo.txt"), "unexpected")

        assertThrows<FileStoreCorruptionException> {
            store.verifyAll()
        }
    }

    @Test
    fun `unexpected directory in suspended directory rejected`() = runBlocking {
        val store = createStore()
        Files.createDirectories(rootDir.resolve("suspended/extra"))

        assertThrows<FileStoreCorruptionException> {
            store.verifyAll()
        }
    }

    @Test
    fun `bad permissions on suspended file rejected`() = runBlocking {
        val store = createStore()
        val (metadata, envelope) = createValidRecord()
        store.create(metadata, envelope)
        Files.setPosixFilePermissions(recordPath(metadata.approvalId), PosixFilePermissions.fromString("rw-r--r--"))

        assertThrows<FileStorePermissionException> {
            runBlocking { store.get(metadata.approvalId) }
        }
        assertThrows<FileStorePermissionException> {
            runBlocking { store.revealReplayEnvelope(metadata.approvalId) }
        }
    }

    @Test
    fun `symlinked suspended record file rejected`() = runBlocking {
        val store = createStore()
        val (metadata, envelope) = createValidRecord()
        store.create(metadata, envelope)

        val path = recordPath(metadata.approvalId)
        val target = rootDir.resolve("symlink-target.bin")
        Files.writeString(target, "not-a-record")
        Files.delete(path)
        Files.createSymbolicLink(path, target)

        assertThrows<FileStorePermissionException> {
            runBlocking { store.get(metadata.approvalId) }
        }
        assertThrows<FileStorePermissionException> {
            store.verifyAll()
        }
    }

    @Test
    fun `symlinked suspended directory rejected`() = runBlocking {
        val store = createStore()
        val realDir = rootDir.resolve("external-suspended")
        Files.createDirectories(realDir)
        rootDir.resolve("suspended").toFile().deleteRecursively()
        Files.createSymbolicLink(rootDir.resolve("suspended"), realDir)

        assertThrows<FileStorePermissionException> {
            runBlocking { store.get("approval-any") }
        }
        assertThrows<FileStorePermissionException> {
            store.verifyAll()
        }
    }

    @Test
    fun `operations after lease close rejected`() = runBlocking {
        val lease = FileStoreLease()
        val store = createStore(lease = lease)
        lease.close()

        assertThrows<IllegalStateException> {
            runBlocking { store.get("approval-closed") }
        }
        assertThrows<IllegalStateException> {
            runBlocking { store.revealReplayEnvelope("approval-closed") }
        }
        assertThrows<IllegalStateException> {
            runBlocking { store.remove("approval-closed") }
        }
    }

    @Test
    fun `concurrent creates have exactly one winner`() = runBlocking {
        val store = createStore()
        val (metadata, envelope) = createValidRecord(approvalId = "approval-concurrent-1")

        coroutineScope {
            val outcomes = listOf(
                async {
                    try {
                        store.create(metadata, envelope)
                        "winner"
                    } catch (_: IllegalArgumentException) {
                        "loser"
                    }
                },
                async {
                    try {
                        store.create(metadata, envelope)
                        "winner"
                    } catch (_: IllegalArgumentException) {
                        "loser"
                    }
                },
            ).map { it.await() }

            assertEquals(1, outcomes.count { it == "winner" })
        }
    }

    @Test
    fun `get and remove validate replay record before returning metadata or deleting`() = runBlocking {
        val store = createStore()
        val (metadata, envelope) = createValidRecord(approvalId = "approval-validated-read-1")
        store.create(metadata, envelope)
        corruptPersistedRecord(metadata.approvalId) { record ->
            record.copy(
                replayEnvelope = record.replayEnvelope.copy(
                    messages = record.replayEnvelope.messages.mapIndexed { index, message ->
                        if (index == 1) {
                            message.copy(
                                toolCalls = message.toolCalls?.map { it.copy(argumentsJson = """{"unsafe":true}""") },
                            )
                        } else {
                            message
                        }
                    },
                ),
            )
        }

        assertThrows<FileStoreCorruptionException> {
            runBlocking { store.get(metadata.approvalId) }
        }
        assertTrue(recordPath(metadata.approvalId).exists())

        assertThrows<FileStoreCorruptionException> {
            runBlocking { store.remove(metadata.approvalId) }
        }
        assertTrue(recordPath(metadata.approvalId).exists())
        assertThrows<FileStoreCorruptionException> {
            runBlocking { store.revealReplayEnvelope(metadata.approvalId) }
        }
    }

    @Test
    fun `cross module replay digest contract holds across persist and reveal`() = runBlocking {
        val store = createStore()
        val (metadata, envelope) = createValidRecord(approvalId = "approval-digest-contract-1")

        val createdMessages = ReplayEnvelopePersistenceCodec.snapshotForPersistence(metadata, envelope)
        store.create(metadata, envelope)

        val reopened = createStore()
        val restored = reopened.revealReplayEnvelope(metadata.approvalId)
        assertNotNull(restored)

        val revealedMessages = restored.revealForResume().messages
        assertEquals(createdMessages, revealedMessages)
        assertEquals(
            metadata.replayEnvelopeDigest,
            ReplayEnvelopePersistenceCodec.computeReplayEnvelopeDigest(
                metadata.operationReference, revealedMessages,
            ),
        )
    }

    private fun createValidRecord(): Pair<SuspendedInvocationMetadata, SensitiveReplayEnvelope> =
        createValidRecord(approvalId = "approval-valid-1")

    private fun createValidRecord(
        approvalId: String,
        workflowRunId: String = "workflow-run-1",
        correlationId: String = "correlation-1",
        promptSecret: String = "review-sensitive-prompt",
    ): Pair<SuspendedInvocationMetadata, SensitiveReplayEnvelope> {
        val toolName = "sensitive_lookup"
        val toolCallId = "tool-call-1"
        val operationReference = ResumeOperationReference(
            serviceInterface = "dev.tramai.persistence.file.SuspendedTestService",
            methodName = "resume",
            jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
            resumeDefinitionDigest = Sha256Digest.of(
                "sha256:1111111111111111111111111111111111111111111111111111111111111111",
            ),
        )
        val messages = listOf(
            Message(
                role = MessageRole.USER,
                content = "$promptSecret workflow=$workflowRunId correlation=$correlationId",
            ),
            Message(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = toolCallId,
                        name = toolName,
                        argumentsJson = "__redacted_approval_continuation_args__",
                    ),
                ),
            ),
        )
        val metadata = SuspendedInvocationMetadata(
            approvalId = approvalId,
            toolCallId = toolCallId,
            toolName = toolName,
            toolCallIndex = 0,
            correlationId = correlationId,
            identity = EngineExecutionIdentity(
                workflowRunId = workflowRunId,
                correlationId = correlationId,
                workflowDigest = Sha256Digest.of(
                    "sha256:2222222222222222222222222222222222222222222222222222222222222222",
                ),
                policyVersion = "policy-v1",
                actorId = "actor-1",
            ),
            securityContext = ExecutionSecurityContext(
                dataClassification = DataClassification.CONFIDENTIAL,
                classificationSource = ClassificationSource.RULE_BASED,
            ),
            operationReference = operationReference,
            replayEnvelopeDigest = ReplayEnvelopePersistenceCodec.computeReplayEnvelopeDigest(operationReference, messages),
            conversationId = "conversation-1",
            historySize = 1,
            tokenBudgetSnapshot = TokenBudgetSnapshot(
                totalInputTokens = 10,
                totalOutputTokens = 20,
                totalInputCost = 0.1,
                totalOutputCost = 0.2,
                warnIfExceeded = true,
            ),
            toolReference = ResumeToolReference(
                toolName = toolName,
                declarationDigest = Sha256Digest.of(
                    "sha256:3333333333333333333333333333333333333333333333333333333333333333",
                ),
            ),
            toolSecurity = ToolSecurityMetadata(
                permission = "files.read",
                risk = RiskLevel.MEDIUM,
                approval = ApprovalMode.HUMAN_REQUIRED,
                managedNetworkEgress = ManagedNetworkEgress.ALLOWLIST_ONLY,
                audit = AuditDetail.FULL,
                compatibilityMode = CompatibilityMode.STRICT,
            ),
        )
        return metadata to SensitiveReplayEnvelope.of(messages)
    }

    private fun recordPath(approvalId: String): Path =
        rootDir.resolve(
            "suspended/${FileStoreSha256.digest("suspended-invocation", approvalId)}.tram.enc",
        )

    private fun corruptPersistedRecord(
        approvalId: String,
        mutate: (PersistedSuspendedInvocationRecordV1) -> PersistedSuspendedInvocationRecordV1,
    ) {
        val path = recordPath(approvalId)
        val recordKeyDigest = FileStoreSha256.digest("suspended-invocation", approvalId)
        val plaintext = FileStoreUtil.readAndDecrypt(
            path,
            "suspended-invocation",
            recordKeyDigest,
            testKey,
            "test-key",
        )
        val mutated = mutate(
            PersistedSuspendedInvocationRecordV1.fromJson(plaintext.toString(Charsets.UTF_8)),
        )
        val (nonceBase64, ciphertextBase64) = AesGcmFileEncryption.encrypt(
            key = testKey,
            recordType = "suspended-invocation",
            recordKeyDigest = recordKeyDigest,
            keyId = "test-key",
            plaintextBytes = mutated.toJson().toByteArray(Charsets.UTF_8),
        )
        Files.writeString(
            path,
            EncryptedFileEnvelopeV1(
                envelopeVersion = 1,
                recordType = "suspended-invocation",
                recordKeyDigest = recordKeyDigest,
                keyId = "test-key",
                nonceBase64 = nonceBase64,
                ciphertextBase64 = ciphertextBase64,
            ).toJson(),
        )
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }
}
