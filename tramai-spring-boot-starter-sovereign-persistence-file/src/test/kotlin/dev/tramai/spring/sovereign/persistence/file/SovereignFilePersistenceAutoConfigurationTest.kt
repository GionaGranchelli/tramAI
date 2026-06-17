package dev.tramai.spring.sovereign.persistence.file

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.persistence.file.FileBackedSovereignStores
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalStore
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.InMemoryAuditStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

class SovereignFilePersistenceAutoConfigurationTest {

    @TempDir
    lateinit var tempDir: Path

    /** A valid base64-encoded 32-byte (256-bit) AES key. */
    private val validBase64Key: String =
        Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    /** Properties for a valid file-persistence config using key-file. */
    private fun validFileProps(baseDir: Path, keyFile: Path): Map<String, String> = mapOf(
        "tramai.sovereign.persistence.type" to "file",
        "tramai.sovereign.persistence.base-dir" to baseDir.toAbsolutePath().toString(),
        "tramai.sovereign.persistence.encryption.key-file" to keyFile.toAbsolutePath().toString(),
    )

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SovereignFilePersistenceAutoConfiguration::class.java),
        )

    // ── type=memory does not create file stores ────────────────────────

    @Test
    fun `type equals memory does not create file backed stores`() {
        contextRunner
            .withPropertyValues("tramai.sovereign.persistence.type=memory")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(FileBackedSovereignStores::class.java)
            }
    }

    // ── type=file without base-dir fails ────────────────────────────────

    @Test
    fun `type is file without base dir fails`() {
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.persistence.type=file",
                "tramai.sovereign.persistence.encryption.key-file=/tmp/nonexistent",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val failure = requireNotNull(ctx.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-file-persistence-missing-base-dir")
            }
    }

    // ── type=file without key source fails ──────────────────────────────

    @Test
    fun `type is file without key source fails`() {
        val dir = tempDir.resolve("no-key")
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.persistence.type=file",
                "tramai.sovereign.persistence.base-dir=${dir.toAbsolutePath()}",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val failure = requireNotNull(ctx.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-file-persistence-missing-key-source")
            }
    }

    // ── both key-env and key-file set fails ─────────────────────────────

    @Test
    fun `both key env and key file set fails`() {
        val dir = tempDir.resolve("ambiguous-key")
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.persistence.type=file",
                "tramai.sovereign.persistence.base-dir=${dir.toAbsolutePath()}",
                "tramai.sovereign.persistence.encryption.key-env=MY_KEY",
                "tramai.sovereign.persistence.encryption.key-file=/tmp/some-key-file",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val failure = requireNotNull(ctx.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-file-persistence-ambiguous-key-source")
            }
    }

    // ── missing env var fails ─────────────────────────────────────────

    @Test
    fun `key env set but env var missing fails`() {
        val dir = tempDir.resolve("missing-env")
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.persistence.type=file",
                "tramai.sovereign.persistence.base-dir=${dir.toAbsolutePath()}",
                "tramai.sovereign.persistence.encryption.key-env=TRAMAI_TEST_NONEXISTENT_KEY_98765",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val failure = requireNotNull(ctx.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-file-persistence-missing-key-env")
            }
    }

    // ── invalid base64 key fails ─────────────────────────────────────

    @Test
    fun `invalid base64 key file fails`() {
        val dir = tempDir.resolve("invalid-base64-key")
        val keyFile = dir.resolve("key.b64")
        Files.createDirectories(dir)
        keyFile.toFile().writeText("===NOT-BASE64===")

        contextRunner
            .withPropertyValues(
                "tramai.sovereign.persistence.type=file",
                "tramai.sovereign.persistence.base-dir=${dir.toAbsolutePath()}",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val failure = requireNotNull(ctx.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-file-persistence-invalid-key")
            }
    }

    // ── decoded key not 32 bytes fails ───────────────────────────────

    @Test
    fun `decoded key not 32 bytes fails`() {
        val dir = tempDir.resolve("wrong-key-size")
        val keyFile = dir.resolve("key.b64")
        Files.createDirectories(dir)
        // 16 bytes (AES-128) — not allowed, must be 32
        val sixteenBytes = Base64.getEncoder().encodeToString(ByteArray(16) { 0x42 })
        keyFile.toFile().writeText(sixteenBytes)

        contextRunner
            .withPropertyValues(
                "tramai.sovereign.persistence.type=file",
                "tramai.sovereign.persistence.base-dir=${dir.toAbsolutePath()}",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val failure = requireNotNull(ctx.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-file-persistence-invalid-key")
            }
    }

    // ── valid key-file creates all four file-backed stores ─────────────

    @Test
    fun `valid properties create file backed AuditStore`() {
        val dir = tempDir.resolve("happy-audit")
        val keyFile = prepareKeyFile(dir)

        contextRunner
            .withPropertyValues(
                *validFileProps(dir, keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(FileBackedSovereignStores::class.java)
                assertThat(ctx).hasSingleBean(AuditStore::class.java)
                val store = ctx.getBean(AuditStore::class.java)
                assertThat(store).isExactlyInstanceOf(
                    dev.tramai.persistence.file.FileAuditStore::class.java,
                )
            }
    }

    @Test
    fun `valid properties create file backed ApprovalStore`() {
        val dir = tempDir.resolve("happy-approval")
        val keyFile = prepareKeyFile(dir)

        contextRunner
            .withPropertyValues(
                *validFileProps(dir, keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalStore::class.java)
                val store = ctx.getBean(ApprovalStore::class.java)
                assertThat(store).isExactlyInstanceOf(
                    dev.tramai.persistence.file.FileApprovalStore::class.java,
                )
            }
    }

    @Test
    fun `valid properties create file backed ApprovalContinuationStore`() {
        val dir = tempDir.resolve("happy-continuation")
        val keyFile = prepareKeyFile(dir)

        contextRunner
            .withPropertyValues(
                *validFileProps(dir, keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalContinuationStore::class.java)
                val store = ctx.getBean(ApprovalContinuationStore::class.java)
                assertThat(store).isExactlyInstanceOf(
                    dev.tramai.persistence.file.FileApprovalContinuationStore::class.java,
                )
            }
    }

    @Test
    fun `valid properties create file backed SuspendedInvocationStore`() {
        val dir = tempDir.resolve("happy-suspended")
        val keyFile = prepareKeyFile(dir)

        contextRunner
            .withPropertyValues(
                *validFileProps(dir, keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SuspendedInvocationStore::class.java)
                val store = ctx.getBean(SuspendedInvocationStore::class.java)
                assertThat(store).isExactlyInstanceOf(
                    dev.tramai.persistence.file.FileSuspendedInvocationStore::class.java,
                )
            }
    }

    // ── Sovereign base starter uses file-backed stores ────────────────

    @Test
    fun `base starter uses file backed stores instead of in memory`() {
        val dir = tempDir.resolve("base-starter-delegation")
        val keyFile = prepareKeyFile(dir)

        val combinedRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SovereignFilePersistenceAutoConfiguration::class.java,
                    dev.tramai.spring.sovereign.SovereignTramaiAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues(
                "tramai.sovereign.enabled=true",
                "tramai.sovereign.allowed-models[0]=local-model",
                "tramai.sovereign.allowed-providers[0]=local-provider",
                "tramai.sovereign.provider-zones.local-provider=LOCAL",
                "tramai.sovereign.models.local-model=local-provider",
                "tramai.sovereign.persistence.type=file",
                "tramai.sovereign.persistence.base-dir=${dir.toAbsolutePath()}",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
            )

        combinedRunner.run { ctx ->
            // AuditStore should be file-backed, NOT in-memory
            val auditStore = ctx.getBean(AuditStore::class.java)
            assertThat(auditStore)
                .isNotInstanceOf(InMemoryAuditStore::class.java)
                .isExactlyInstanceOf(dev.tramai.persistence.file.FileAuditStore::class.java)

            // ApprovalStore should be file-backed, NOT in-memory
            val approvalStore = ctx.getBean(ApprovalStore::class.java)
            assertThat(approvalStore)
                .isNotInstanceOf(InMemoryApprovalStore::class.java)
                .isExactlyInstanceOf(dev.tramai.persistence.file.FileApprovalStore::class.java)

            // ApprovalContinuationStore should be file-backed, NOT in-memory
            val continuationStore = ctx.getBean(ApprovalContinuationStore::class.java)
            assertThat(continuationStore)
                .isNotInstanceOf(InMemoryApprovalContinuationStore::class.java)
                .isExactlyInstanceOf(dev.tramai.persistence.file.FileApprovalContinuationStore::class.java)
        }
    }

    // ── User-provided beans are not overridden ─────────────────────────

    @Test
    fun `custom user provided AuditStore is not overridden`() {
        val dir = tempDir.resolve("custom-audit")
        val keyFile = prepareKeyFile(dir)

        contextRunner
            .withUserConfiguration(CustomAuditStoreConfig::class.java)
            .withPropertyValues(
                *validFileProps(dir, keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                val store = ctx.getBean(AuditStore::class.java)
                assertThat(store).isInstanceOf(CustomAuditStore::class.java)
            }
    }

    @Test
    fun `custom user provided ApprovalStore is not overridden`() {
        val dir = tempDir.resolve("custom-approval")
        val keyFile = prepareKeyFile(dir)

        contextRunner
            .withUserConfiguration(CustomApprovalStoreConfig::class.java)
            .withPropertyValues(
                *validFileProps(dir, keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                val store = ctx.getBean(ApprovalStore::class.java)
                assertThat(store).isInstanceOf(CustomApprovalStore::class.java)
            }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun prepareKeyFile(dir: Path): Path {
        // Write key file to a separate temp directory, NOT inside the store base dir.
        // FileBackedSovereignStores.open() creates the base dir with strict 0700 permissions.
        val keyDir = Files.createTempDirectory("tramai-test-key-")
        val keyFile = keyDir.resolve("key.b64")
        keyFile.toFile().writeText(validBase64Key)
        return keyFile
    }

    // ── Test Configuration classes (must be open for CGLIB) ────────────
}

open class CustomAuditStoreConfig {
    @Bean
    @Primary
    open fun customAuditStore(): AuditStore = CustomAuditStore()
}

open class CustomApprovalStoreConfig {
    @Bean
    @Primary
    open fun customApprovalStore(): ApprovalStore = CustomApprovalStore()
}

// ── Stub implementations ─────────────────────────────────────────────

class CustomAuditStore : AuditStore {
    override suspend fun appendNext(
        auditStreamId: String,
        eventFactory: (AuditEvent?) -> AuditEvent,
    ): AuditEvent = eventFactory(
        AuditEvent(
            schemaVersion = 1,
            hashAlgorithm = dev.tramai.security.audit.AuditHashAlgorithm.SHA_256,
            auditStreamId = auditStreamId,
            eventId = "custom-test-event",
            sequenceNumber = 1,
            workflowRunId = null,
            correlationId = null,
            actor = "test",
            enforcementPoint = "test",
            decision = "permit",
            policyVersion = null,
            workflowDigest = null,
            previousEventHash = null,
            eventHash = "a".repeat(64),
            timestamp = Instant.now(),
            reasonCode = null,
        ),
    )
    override suspend fun readStream(auditStreamId: String): List<AuditEvent> = emptyList()
    override suspend fun latestEvent(auditStreamId: String): AuditEvent? = null
}

class CustomApprovalStore : ApprovalStore {
    private var created = false
    override suspend fun create(
        request: dev.tramai.core.approval.ApprovalRequest,
    ): dev.tramai.core.approval.ApprovalRequest {
        created = true
        return request
    }
    override suspend fun get(approvalId: String): dev.tramai.core.approval.ApprovalRequest? = null
    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: dev.tramai.core.approval.ApprovalTransition,
    ): dev.tramai.core.approval.ApprovalRequest {
        throw UnsupportedOperationException("custom stub")
    }
    override suspend fun consumeApprovedOrReplay(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: dev.tramai.core.approval.Sha256Digest,
        consumedBy: String,
    ): dev.tramai.core.approval.ApprovalConsumptionReceipt {
        throw UnsupportedOperationException("custom stub")
    }
}
