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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.io.path.*
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileBackedSovereignStoresTest {

    private val rootDir: Path = Files.createTempDirectory("tramai-bundle-test-").toAbsolutePath()

    private fun testKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val testKey: SecretKey by lazy { testKey() }

    private val keyProvider = FileStoreEncryptionKeyProvider { testKey }

    private fun createConfig(root: Path = rootDir) = FileBackedStoreConfiguration(
        rootDirectory = root,
        encryption = FileStoreEncryptionConfiguration(
            activeKeyId = "test-key",
            keyProvider = keyProvider,
        ),
        verifyOnOpen = false,
    )

    @AfterEach
    fun cleanup() {
        if (rootDir.exists()) {
            rootDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `open creates root directory with 0700 permissions`() {
        val newRoot = rootDir.resolve("fresh-root")
        val config = createConfig(newRoot)

        val stores = FileBackedSovereignStores.open(config)
        stores.close()

        assertTrue(newRoot.exists(), "Root directory must exist after open")
        assertTrue(newRoot.isDirectory(), "Root must be a directory")

        val perms = Files.getPosixFilePermissions(newRoot)
        assertTrue(
            perms.containsAll(PosixFilePermissions.fromString("rwx------")),
            "Permissions must be 0700",
        )
    }

    @Test
    fun `open creates manifest json`() {
        val config = createConfig()
        val stores = FileBackedSovereignStores.open(config)
        stores.close()

        val manifestPath = rootDir.resolve("manifest.json")
        assertTrue(manifestPath.exists(), "manifest.json must exist")

        val manifestJson = manifestPath.readText()
        val manifest = StoreManifestV1.fromJson(manifestJson)
        assertTrue(manifest.createdAt.isNotBlank())
    }

    @Test
    fun `open acquires exclusive lock on tramai lock`() {
        val config = createConfig()
        val stores = FileBackedSovereignStores.open(config)

        try {
            val lockPath = rootDir.resolve(".tramai.lock")
            assertTrue(lockPath.exists(), "Lock file must exist")
        } finally {
            stores.close()
        }
    }

    @Test
    fun `second open for same root is rejected`() {
        val config = createConfig()
        val stores1 = FileBackedSovereignStores.open(config)

        try {
            assertThrows<Exception> {
                FileBackedSovereignStores.open(config)
            }
        } finally {
            stores1.close()
        }
    }

    @Test
    fun `close releases lock`() {
        val config = createConfig()
        val stores1 = FileBackedSovereignStores.open(config)
        stores1.close()

        val stores2 = FileBackedSovereignStores.open(config)
        stores2.close()
    }

    @Test
    fun `open rejects symlink root`() {
        val realRoot = rootDir.resolve("real-dir")
        Files.createDirectories(realRoot)

        val symlinkRoot = rootDir.resolve("link-to-root")
        Files.createSymbolicLink(symlinkRoot, realRoot)

        val config = createConfig(symlinkRoot)

        assertThrows<IllegalArgumentException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open rejects root with wrong permissions`() {
        val badPermsRoot = rootDir.resolve("bad-perms-root")
        Files.createDirectories(badPermsRoot)
        Files.setPosixFilePermissions(badPermsRoot, PosixFilePermissions.fromString("rwxrwxrwx"))

        assertThrows<IllegalArgumentException> {
            FileBackedSovereignStores.open(createConfig(badPermsRoot))
        }
    }

    @Test
    fun `stores are accessible after open`() {
        val config = createConfig()
        val stores = FileBackedSovereignStores.open(config)

        try {
            assertNotNull(stores.approvalStore, "approvalStore must be non-null")
            assertNotNull(stores.approvalContinuationStore, "approvalContinuationStore must be non-null")
            assertNotNull(stores.auditStore, "auditStore must be non-null")
            assertNotNull(stores.suspendedInvocationStore, "suspendedInvocationStore must be non-null")

            assertTrue(stores.approvalStore is FileApprovalStore)
            assertTrue(stores.approvalContinuationStore is FileApprovalContinuationStore)
            assertTrue(stores.auditStore is FileAuditStore)
            assertTrue(stores.suspendedInvocationStore is FileSuspendedInvocationStore)
        } finally {
            stores.close()
        }
    }

    @Test
    fun `open creates suspended directory with 0700 permissions`() {
        val config = createConfig()
        val stores = FileBackedSovereignStores.open(config)
        stores.close()

        val suspendedDir = rootDir.resolve("suspended")
        assertTrue(suspendedDir.exists(), "suspended directory must exist after open")
        assertTrue(suspendedDir.isDirectory(), "suspended must be a directory")
        assertTrue(
            Files.getPosixFilePermissions(suspendedDir) == PosixFilePermissions.fromString("rwx------"),
            "suspended permissions must be 0700",
        )
    }

    @Test
    fun `open with verifyOnOpen succeeds on empty directory`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("verify-empty"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "test-key",
                keyProvider = keyProvider,
            ),
            verifyOnOpen = true,
        )
        val stores = FileBackedSovereignStores.open(config)
        stores.close()
    }

    @Test
    fun `close is idempotent`() {
        val config = createConfig()
        val stores = FileBackedSovereignStores.open(config)
        stores.close()
        stores.close()
    }

    @Test
    fun `corrupted suspended record fails startup verification`() { runBlocking {
        val config = createConfig()
        val approvalId = "bundle-suspended-corrupt-1"

        FileBackedSovereignStores.open(config).use { stores ->
            val (metadata, envelope) = createValidSuspendedRecord(approvalId)
            stores.suspendedInvocationStore.create(metadata, envelope)
        }

        val path = suspendedRecordPath(approvalId)
        val encrypted = EncryptedFileEnvelopeV1.fromJson(path.readText())
        // Flip one byte of the ciphertext+tag so the corruption is guaranteed to
        // change the decrypted content. String-level mutations are unreliable:
        // depending on the trailing Base64 padding, some character replacements
        // decode to the same significant bits (e.g. 'X' -> 'Y' under '==' padding
        // keeps the top 2 bits), making the corruption a silent no-op. A byte
        // flip always changes the decoded ciphertext, so GCM auth always fails.
        val corruptedCiphertext = Base64.getDecoder().decode(encrypted.ciphertextBase64)
            .also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        Files.writeString(
            path,
            encrypted.copy(
                ciphertextBase64 = Base64.getEncoder().encodeToString(corruptedCiphertext),
            ).toJson(),
        )

        assertThrows<FileStoreCorruptionException> {
            FileBackedSovereignStores.open(
                FileBackedStoreConfiguration(
                    rootDirectory = rootDir,
                    encryption = FileStoreEncryptionConfiguration(
                        activeKeyId = "test-key",
                        keyProvider = keyProvider,
                    ),
                    verifyOnOpen = true,
                ),
            )
        }
    }
    }

    @Test
    fun `operations after close are rejected on suspended store`() { runBlocking {
        val stores = FileBackedSovereignStores.open(createConfig())
        val suspendedStore = stores.suspendedInvocationStore
        stores.close()

        assertThrows<IllegalStateException> {
            runBlocking { suspendedStore.get("approval-closed") }
        }
        assertThrows<IllegalStateException> {
            runBlocking { suspendedStore.revealReplayEnvelope("approval-closed") }
        }
        assertThrows<IllegalStateException> {
            runBlocking { suspendedStore.remove("approval-closed") }
        }
    }
    }

    // ── activeKeyId validation ──────────────────────────────────────────

    @Test
    fun `open rejects blank activeKeyId`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("blank-keyid"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "",
                keyProvider = keyProvider,
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open rejects whitespace-only activeKeyId`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("ws-keyid"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "   ",
                keyProvider = keyProvider,
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open rejects overly long activeKeyId`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("long-keyid"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "x".repeat(129),
                keyProvider = keyProvider,
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open rejects activeKeyId with unsafe pattern`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("unsafe-keyid"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "-starts-with-hyphen",
                keyProvider = keyProvider,
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open rejects activeKeyId containing whitespace`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("space-keyid"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "my key",
                keyProvider = keyProvider,
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open accepts valid activeKeyId with allowed special characters`() {
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("valid-special-keyid"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "k8s_prod:us-east-1@v2",
                keyProvider = keyProvider,
            ),
        )
        val stores = FileBackedSovereignStores.open(config)
        stores.close()
    }

    // ── Encryption key validation ──────────────────────────────────────

    @Test
    fun `open rejects non-AES encryption key`() {
        val desKey = KeyGenerator.getInstance("DES").generateKey()
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("non-aes-key"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "test-key",
                keyProvider = FileStoreEncryptionKeyProvider { desKey },
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    @Test
    fun `open rejects AES key that is not 256 bit`() {
        val aes128Key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir.resolve("aes-128-key"),
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "test-key",
                keyProvider = FileStoreEncryptionKeyProvider { aes128Key },
            ),
        )
        assertThrows<FileStoreConfigurationException> {
            FileBackedSovereignStores.open(config)
        }
    }

    // ── Symlink validation ─────────────────────────────────────────────

    @Test
    fun `open rejects symlink on tramai lock`() {
        val setupRoot = rootDir.resolve("lock-symlink")
        val realTarget = Files.createTempDirectory("lock-target-")

        // First open to create proper structure
        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        // Replace .tramai.lock with a symlink
        val lockPath = setupRoot.resolve(".tramai.lock")
        Files.delete(lockPath)
        Files.createSymbolicLink(lockPath, realTarget)

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects symlink on manifest json`() {
        val setupRoot = rootDir.resolve("manifest-symlink")
        val realTarget = rootDir.resolve("manifest-target")

        // First open to create proper structure
        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        // Replace manifest.json with a symlink
        val manifestPath = setupRoot.resolve("manifest.json")
        Files.delete(manifestPath)
        Files.createSymbolicLink(manifestPath, realTarget)

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects symlink on approvals subdirectory`() {
        val setupRoot = rootDir.resolve("approvals-symlink")
        val realApprovals = rootDir.resolve("real-approvals")
        Files.createDirectories(realApprovals)

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val approvalsDir = setupRoot.resolve("approvals")
        approvalsDir.toFile().deleteRecursively()
        Files.createSymbolicLink(approvalsDir, realApprovals)

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects symlink on continuations subdirectory`() {
        val setupRoot = rootDir.resolve("continuations-symlink")
        val realCont = rootDir.resolve("real-continuations")
        Files.createDirectories(realCont)

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val contDir = setupRoot.resolve("continuations")
        contDir.toFile().deleteRecursively()
        Files.createSymbolicLink(contDir, realCont)

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects symlink on audit subdirectory`() {
        val setupRoot = rootDir.resolve("audit-symlink")
        val realAudit = rootDir.resolve("real-audit")
        Files.createDirectories(realAudit)

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val auditDir = setupRoot.resolve("audit")
        auditDir.toFile().deleteRecursively()
        Files.createSymbolicLink(auditDir, realAudit)

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    // ── Permissions validation ─────────────────────────────────────────

    @Test
    fun `open rejects wrong permissions on manifest json`() {
        val setupRoot = rootDir.resolve("manifest-perms")

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val manifestPath = setupRoot.resolve("manifest.json")
        Files.setPosixFilePermissions(manifestPath, PosixFilePermissions.fromString("rwx------"))

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects wrong permissions on approvals subdirectory`() {
        val setupRoot = rootDir.resolve("approvals-perms")

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val approvalsDir = setupRoot.resolve("approvals")
        Files.setPosixFilePermissions(approvalsDir, PosixFilePermissions.fromString("rwxrwx---"))

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects wrong permissions on continuations subdirectory`() {
        val setupRoot = rootDir.resolve("continuations-perms")

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val contDir = setupRoot.resolve("continuations")
        Files.setPosixFilePermissions(contDir, PosixFilePermissions.fromString("rwxrwx---"))

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    @Test
    fun `open rejects wrong permissions on audit subdirectory`() {
        val setupRoot = rootDir.resolve("audit-perms")

        val stores = FileBackedSovereignStores.open(createConfig(setupRoot))
        stores.close()

        val auditDir = setupRoot.resolve("audit")
        Files.setPosixFilePermissions(auditDir, PosixFilePermissions.fromString("rwxrwx---"))

        assertThrows<FileStorePermissionException> {
            FileBackedSovereignStores.open(createConfig(setupRoot))
        }
    }

    private fun suspendedRecordPath(approvalId: String): Path =
        rootDir.resolve(
            "suspended/${FileStoreSha256.digest("suspended-invocation", approvalId)}.tram.enc",
        )

    private fun createValidSuspendedRecord(
        approvalId: String,
    ): Pair<SuspendedInvocationMetadata, SensitiveReplayEnvelope> {
        val toolName = "bundle_lookup"
        val toolCallId = "bundle-tool-call-1"
        val operationReference = ResumeOperationReference(
            serviceInterface = "dev.tramai.persistence.file.BundleTestService",
            methodName = "resume",
            jvmMethodDescriptor = "(Ljava/lang/String;)Ljava/lang/String;",
            resumeDefinitionDigest = Sha256Digest.of(
                "sha256:4444444444444444444444444444444444444444444444444444444444444444",
            ),
        )
        val messages = listOf(
            Message(
                role = MessageRole.USER,
                content = "bundle prompt",
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
            correlationId = "bundle-correlation-1",
            identity = EngineExecutionIdentity(
                workflowRunId = "bundle-workflow-1",
                correlationId = "bundle-correlation-1",
                workflowDigest = Sha256Digest.of(
                    "sha256:5555555555555555555555555555555555555555555555555555555555555555",
                ),
                policyVersion = "policy-v1",
                actorId = "bundle-actor",
            ),
            securityContext = ExecutionSecurityContext(
                dataClassification = DataClassification.CONFIDENTIAL,
                classificationSource = ClassificationSource.RULE_BASED,
            ),
            operationReference = operationReference,
            replayEnvelopeDigest = ReplayEnvelopePersistenceCodec.computeReplayEnvelopeDigest(operationReference, messages),
            conversationId = "bundle-conversation-1",
            historySize = 1,
            tokenBudgetSnapshot = TokenBudgetSnapshot(
                totalInputTokens = 1,
                totalOutputTokens = 2,
                totalInputCost = 0.01,
                totalOutputCost = 0.02,
                warnIfExceeded = true,
            ),
            toolReference = ResumeToolReference(
                toolName = toolName,
                declarationDigest = Sha256Digest.of(
                    "sha256:6666666666666666666666666666666666666666666666666666666666666666",
                ),
            ),
            toolSecurity = ToolSecurityMetadata(
                permission = "bundle.read",
                risk = RiskLevel.LOW,
                approval = ApprovalMode.HUMAN_REQUIRED,
                managedNetworkEgress = ManagedNetworkEgress.DENY,
                audit = AuditDetail.FULL,
                compatibilityMode = CompatibilityMode.STRICT,
            ),
        )
        return metadata to SensitiveReplayEnvelope.of(messages)
    }
}
