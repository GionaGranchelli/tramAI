package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.persistence.jdbc.JdbcApprovalContinuationStore
import dev.tramai.persistence.jdbc.JdbcApprovalStore
import dev.tramai.persistence.jdbc.JdbcAuditPayloadCodec
import dev.tramai.persistence.jdbc.JdbcAuditStore
import dev.tramai.persistence.jdbc.JdbcContinuationArgumentsCodec
import dev.tramai.persistence.jdbc.JdbcReplayEnvelopeCodec
import dev.tramai.persistence.jdbc.JdbcSuspendedInvocationStore
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseStore
import dev.tramai.security.approval.InMemoryApprovalContinuationStore
import dev.tramai.security.approval.InMemoryApprovalStore
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.InMemoryAuditStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import java.io.PrintWriter
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.logging.Logger
import javax.crypto.SecretKey
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

class SovereignJdbcPersistenceAutoConfigurationTest {

    @TempDir
    lateinit var tempDir: Path

    /** A valid base64-encoded 32-byte (256-bit) AES key. */
    private val validBase64Key: String =
        Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    /** Properties for a valid JDBC config using key-file. */
    private fun validJdbcProps(keyFile: Path): Map<String, String> = mapOf(
        "tramai.sovereign.persistence.type" to "jdbc",
        "tramai.sovereign.persistence.encryption.key-file" to keyFile.toAbsolutePath().toString(),
    )

    /** Properties with custom outbox config. */
    private fun jdbcPropsWithOutboxConfig(keyFile: Path): Map<String, String> = mapOf(
        "tramai.sovereign.persistence.type" to "jdbc",
        "tramai.sovereign.persistence.encryption.key-file" to keyFile.toAbsolutePath().toString(),
        "tramai.sovereign.persistence.jdbc.claim-lease-duration" to "10m",
        "tramai.sovereign.persistence.jdbc.max-claim-limit" to "1000",
    )

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SovereignJdbcPersistenceAutoConfiguration::class.java),
        )
        .withUserConfiguration(TestDataSourceConfig::class.java)

    // ── type=memory does not create JDBC stores ──────────────────────

    @Test
    fun `type equals memory does not create JDBC stores`() {
        contextRunner
            .withPropertyValues("tramai.sovereign.persistence.type=memory")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(JdbcApprovalStore::class.java)
                assertThat(ctx).doesNotHaveBean(JdbcApprovalContinuationStore::class.java)
                assertThat(ctx).doesNotHaveBean(JdbcSuspendedInvocationStore::class.java)
                assertThat(ctx).doesNotHaveBean(JdbcAuditStore::class.java)
                assertThat(ctx).doesNotHaveBean(JdbcSovereignOpsAuditOutboxStore::class.java)
            }
    }

    // ── type=file does not create JDBC stores ────────────────────────

    @Test
    fun `type equals file does not create JDBC stores`() {
        contextRunner
            .withPropertyValues("tramai.sovereign.persistence.type=file")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(JdbcApprovalStore::class.java)
                assertThat(ctx).doesNotHaveBean(JdbcApprovalContinuationStore::class.java)
                assertThat(ctx).doesNotHaveBean(JdbcSuspendedInvocationStore::class.java)
                assertThat(ctx).doesNotHaveBean(JdbcAuditStore::class.java)
                assertThat(ctx).doesNotHaveBean(JdbcSovereignOpsAuditOutboxStore::class.java)
            }
    }

    // ── type=jdbc without DataSource fails ────────────────────────────

    @Test
    fun `type is jdbc without DataSource fails with deterministic error`() {
        val keyFile = prepareKeyFile("no-datasource-key.b64")

        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(SovereignJdbcPersistenceAutoConfiguration::class.java),
            )
            .withPropertyValues(
                "tramai.sovereign.persistence.type=jdbc",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val failure = requireNotNull(ctx.startupFailure)
                assertThat(failure)
                    .hasMessageContaining(
                        "tramai-sovereign-jdbc-persistence-missing-datasource",
                    )
            }
    }

    // ── type=jdbc without key source fails ────────────────────────────

    @Test
    fun `type is jdbc without key source fails`() {
        contextRunner
            .withPropertyValues("tramai.sovereign.persistence.type=jdbc")
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val failure = requireNotNull(ctx.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-jdbc-persistence-missing-key-source")
            }
    }

    // ── both key-env and key-file set fails ───────────────────────────

    @Test
    fun `both key env and key file set fails`() {
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.persistence.type=jdbc",
                "tramai.sovereign.persistence.encryption.key-env=MY_KEY",
                "tramai.sovereign.persistence.encryption.key-file=/tmp/some-key-file",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val failure = requireNotNull(ctx.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-jdbc-persistence-ambiguous-key-source")
            }
    }

    // ── missing env var fails ───────────────────────────────────────

    @Test
    fun `key env set but env var missing fails`() {
        contextRunner
            .withPropertyValues(
                "tramai.sovereign.persistence.type=jdbc",
                "tramai.sovereign.persistence.encryption.key-env=TRAMAI_TEST_NONEXISTENT_KEY_98765",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val failure = requireNotNull(ctx.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-jdbc-persistence-missing-key-env")
            }
    }

    // ── invalid base64 key file fails ───────────────────────────────

    @Test
    fun `invalid base64 key file fails`() {
        val keyFile = tempDir.resolve("key.b64")
        keyFile.toFile().writeText("===NOT-BASE64===")

        contextRunner
            .withPropertyValues(
                "tramai.sovereign.persistence.type=jdbc",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val failure = requireNotNull(ctx.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-jdbc-persistence-invalid-key")
            }
    }

    // ── decoded key not 32 bytes fails ─────────────────────────────

    @Test
    fun `decoded key not 32 bytes fails`() {
        val keyFile = tempDir.resolve("key.b64")
        // 16 bytes (AES-128) — not allowed, must be 32
        val sixteenBytes = Base64.getEncoder().encodeToString(ByteArray(16) { 0x42 })
        keyFile.toFile().writeText(sixteenBytes)

        contextRunner
            .withPropertyValues(
                "tramai.sovereign.persistence.type=jdbc",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val failure = requireNotNull(ctx.startupFailure)
                assertThat(failure)
                    .hasMessageContaining("tramai-sovereign-jdbc-persistence-invalid-key")
            }
    }

    // ── valid config creates ApprovalStore ────────────────────────────

    @Test
    fun `valid config creates ApprovalStore instance of JdbcApprovalStore`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalStore::class.java)
                val store = ctx.getBean(ApprovalStore::class.java)
                assertThat(store).isExactlyInstanceOf(JdbcApprovalStore::class.java)
            }
    }

    @Test
    fun `valid config creates SovereignOpsApprovalRequestMutationStore instance of Jdbc implementation`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsApprovalRequestMutationStore::class.java)
                val store = ctx.getBean(SovereignOpsApprovalRequestMutationStore::class.java)
                assertThat(store).isExactlyInstanceOf(JdbcSovereignOpsApprovalRequestMutationStore::class.java)
            }
    }

    // ── valid config creates SuspendedInvocationStore ─────────────────

    @Test
    fun `valid config creates SuspendedInvocationStore instance of JdbcSuspendedInvocationStore`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SuspendedInvocationStore::class.java)
                val store = ctx.getBean(SuspendedInvocationStore::class.java)
                assertThat(store).isExactlyInstanceOf(JdbcSuspendedInvocationStore::class.java)
            }
    }

    // ── valid config creates ApprovalContinuationStore ────────────────

    @Test
    fun `valid config creates ApprovalContinuationStore instance of JdbcApprovalContinuationStore`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalContinuationStore::class.java)
                val store = ctx.getBean(ApprovalContinuationStore::class.java)
                assertThat(store).isExactlyInstanceOf(JdbcApprovalContinuationStore::class.java)
            }
    }

    // ── valid config creates AuditStore ───────────────────────────────

    @Test
    fun `valid config creates AuditStore instance of JdbcAuditStore`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(AuditStore::class.java)
                val store = ctx.getBean(AuditStore::class.java)
                assertThat(store).isExactlyInstanceOf(JdbcAuditStore::class.java)
            }
    }

    // ── valid config creates SovereignOpsAuditOutboxStore ─────────────

    @Test
    fun `valid config creates SovereignOpsAuditOutboxStore instance of JdbcSovereignOpsAuditOutboxStore`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxStore::class.java)
                val store = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)
                assertThat(store).isExactlyInstanceOf(
                    JdbcSovereignOpsAuditOutboxStore::class.java,
                )
            }
    }

    // ── outbox store is durable ──────────────────────────────────────

    @Test
    fun `outbox store is durable`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                val store = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)
                assertThat(store.isDurable()).isTrue
            }
    }

    // ── all beans created with valid config ───────────────────────────

    @Test
    fun `valid config creates all six store beans`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ApprovalStore::class.java)
                assertThat(ctx).hasSingleBean(SuspendedInvocationStore::class.java)
                assertThat(ctx).hasSingleBean(ApprovalContinuationStore::class.java)
                assertThat(ctx).hasSingleBean(AuditStore::class.java)
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxStore::class.java)
                assertThat(ctx).hasSingleBean(SovereignOpsApprovalMutationStore::class.java)
            }
    }

    // ── codec beans are created ───────────────────────────────────────

    @Test
    fun `valid config creates all four codec beans`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(JdbcAuditPayloadCodec::class.java)
                assertThat(ctx).hasSingleBean(JdbcReplayEnvelopeCodec::class.java)
                assertThat(ctx).hasSingleBean(JdbcContinuationArgumentsCodec::class.java)
                assertThat(ctx).hasSingleBean(JdbcOpsAuditOutboxPayloadCodec::class.java)
            }
    }

    // ── Base starter uses JDBC stores instead of in-memory ────────────

    @Test
    fun `base starter uses JDBC stores instead of in memory`() {
        val keyFile = prepareKeyFile()

        val combinedRunner = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    SovereignJdbcPersistenceAutoConfiguration::class.java,
                    dev.tramai.spring.sovereign.SovereignTramaiAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(TestDataSourceConfig::class.java)
            .withPropertyValues(
                "tramai.sovereign.enabled=true",
                "tramai.sovereign.allowed-models[0]=local-model",
                "tramai.sovereign.allowed-providers[0]=local-provider",
                "tramai.sovereign.provider-zones.local-provider=LOCAL",
                "tramai.sovereign.models.local-model=local-provider",
                "tramai.sovereign.persistence.type=jdbc",
                "tramai.sovereign.persistence.encryption.key-file=${keyFile.toAbsolutePath()}",
            )

        combinedRunner.run { ctx ->
            // AuditStore should be JDBC-backed, NOT in-memory
            val auditStore = ctx.getBean(AuditStore::class.java)
            assertThat(auditStore)
                .isNotInstanceOf(InMemoryAuditStore::class.java)
                .isExactlyInstanceOf(JdbcAuditStore::class.java)

            // ApprovalStore should be JDBC-backed, NOT in-memory
            val approvalStore = ctx.getBean(ApprovalStore::class.java)
            assertThat(approvalStore)
                .isNotInstanceOf(InMemoryApprovalStore::class.java)
                .isExactlyInstanceOf(JdbcApprovalStore::class.java)

            // ApprovalContinuationStore should be JDBC-backed, NOT in-memory
            val continuationStore = ctx.getBean(ApprovalContinuationStore::class.java)
            assertThat(continuationStore)
                .isNotInstanceOf(InMemoryApprovalContinuationStore::class.java)
                .isExactlyInstanceOf(JdbcApprovalContinuationStore::class.java)
        }
    }

    // ── User-provided store overrides JDBC default ────────────────────

    @Test
    fun `custom user provided AuditStore is not overridden`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withUserConfiguration(CustomAuditStoreConfig::class.java)
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx.getBeansOfType(AuditStore::class.java)).hasSize(1)
                val store = ctx.getBean(AuditStore::class.java)
                assertThat(store).isInstanceOf(CustomAuditStore::class.java)
            }
    }

    @Test
    fun `custom user provided ApprovalStore is not overridden`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withUserConfiguration(CustomApprovalStoreConfig::class.java)
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx.getBeansOfType(ApprovalStore::class.java)).hasSize(1)
                val store = ctx.getBean(ApprovalStore::class.java)
                assertThat(store).isInstanceOf(CustomApprovalStore::class.java)
            }
    }

    @Test
    fun `user provided SovereignOpsAuditOutboxStore is not overridden`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withUserConfiguration(CustomOutboxStoreConfig::class.java)
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx.getBeansOfType(SovereignOpsAuditOutboxStore::class.java)).hasSize(1)
                val store = ctx.getBean(SovereignOpsAuditOutboxStore::class.java)
                assertThat(store).isInstanceOf(CustomOutboxStore::class.java)
            }
    }

    @Test
    fun `custom codec overrides default codec`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withUserConfiguration(CustomAuditPayloadCodecConfig::class.java)
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx.getBeansOfType(JdbcAuditPayloadCodec::class.java)).hasSize(1)
                val codec = ctx.getBean(JdbcAuditPayloadCodec::class.java)
                assertThat(codec).isInstanceOf(CustomAuditPayloadCodec::class.java)
            }
    }

    // ── outbox config properties bind ─────────────────────────────────

    @Test
    fun `claim lease duration property binds`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *jdbcPropsWithOutboxConfig(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                val props = ctx.getBean(SovereignJdbcPersistenceProperties::class.java)
                assertThat(props.jdbc.claimLeaseDuration).isEqualTo(Duration.ofMinutes(10))
            }
    }

    @Test
    fun `max claim limit property binds`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *jdbcPropsWithOutboxConfig(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                val props = ctx.getBean(SovereignJdbcPersistenceProperties::class.java)
                assertThat(props.jdbc.maxClaimLimit).isEqualTo(1000)
            }
    }

    // ── P1 regression: unrelated SecretKey bean ───────────────────────

    @Test
    fun `default codecs use sovereign JDBC encryption key when another SecretKey bean exists`() {
        val keyFile = prepareKeyFile()

        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(SovereignJdbcPersistenceAutoConfiguration::class.java),
            )
            .withUserConfiguration(TestDataSourceConfig::class.java, UnrelatedSecretKeyConfig::class.java)
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                // Context starts successfully despite an unrelated SecretKey bean
                assertThat(ctx).hasSingleBean(JdbcAuditPayloadCodec::class.java)
                assertThat(ctx).hasSingleBean(JdbcReplayEnvelopeCodec::class.java)
                assertThat(ctx).hasSingleBean(JdbcContinuationArgumentsCodec::class.java)
                assertThat(ctx).hasSingleBean(JdbcOpsAuditOutboxPayloadCodec::class.java)
                assertThat(ctx).hasSingleBean(SovereignOpsAuditOutboxStore::class.java)
            }
    }

    // ── Worker lease store ────────────────────────────────────────────

    @Test
    fun `type=jdbc + DataSource creates SovereignOpsWorkerLeaseStore`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsWorkerLeaseStore::class.java)
                val store = ctx.getBean(SovereignOpsWorkerLeaseStore::class.java)
                assertThat(store).isExactlyInstanceOf(JdbcSovereignOpsWorkerLeaseStore::class.java)
            }
    }

    @Test
    fun `type=jdbc + DataSource creates SovereignOpsApprovalMutationStore`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignOpsApprovalMutationStore::class.java)
                val store = ctx.getBean(SovereignOpsApprovalMutationStore::class.java)
                assertThat(store).isExactlyInstanceOf(JdbcSovereignOpsApprovalMutationStore::class.java)
            }
    }

    @Test
    fun `user-provided lease store wins`() {
        val keyFile = prepareKeyFile()

        contextRunner
            .withUserConfiguration(CustomLeaseStoreConfig::class.java)
            .withPropertyValues(
                *validJdbcProps(keyFile).entries
                    .map { "${it.key}=${it.value}" }
                    .toTypedArray(),
            )
            .run { ctx ->
                assertThat(ctx.getBeansOfType(SovereignOpsWorkerLeaseStore::class.java)).hasSize(1)
                val store = ctx.getBean(SovereignOpsWorkerLeaseStore::class.java)
                assertThat(store).isInstanceOf(CustomLeaseStore::class.java)
            }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun prepareKeyFile(name: String = "key.b64"): Path {
        val keyFile = tempDir.resolve(name)
        keyFile.toFile().writeText(validBase64Key)
        return keyFile
    }
}

// ── Test Configuration classes (must be open for CGLIB) ─────────────

open class TestDataSourceConfig {
    @Bean
    open fun testDataSource(): DataSource = NoOpDataSource()
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

open class CustomOutboxStoreConfig {
    @Bean
    @Primary
    open fun customOutboxStore(): SovereignOpsAuditOutboxStore = CustomOutboxStore()
}

open class CustomAuditPayloadCodecConfig {
    @Bean
    @Primary
    open fun customAuditPayloadCodec(): JdbcAuditPayloadCodec = CustomAuditPayloadCodec()
}

/**
 * Configuration that provides an unrelated [SecretKey] bean to verify that
 * the JDBC auto-configuration codec beans correctly qualify their key injection
 * and do not accidentally bind to a non-sovereign key.
 */
open class UnrelatedSecretKeyConfig {
    @Bean
    open fun unrelatedAppSecretKey(): SecretKey {
        val raw = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 0x01 }, "AES")
        return raw
    }
}

// ── DataSource that never actually connects ──────────────────────────

/**
 * A [DataSource] that never opens real connections. Used in auto-configuration
 * tests to verify bean creation without requiring a running database.
 *
 * JDBC store constructors simply store the [DataSource] reference without
 * connecting, so this is safe for bean-creation verification tests.
 */
class NoOpDataSource : DataSource {
    override fun getConnection(): Connection = throw UnsupportedOperationException("NoOpDataSource")
    override fun getConnection(username: String, password: String): Connection =
        throw UnsupportedOperationException("NoOpDataSource")
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) = Unit
    override fun setLoginTimeout(seconds: Int) = Unit
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = throw UnsupportedOperationException("NoOpDataSource")
    override fun <T> unwrap(iface: Class<T>): T = throw UnsupportedOperationException("NoOpDataSource")
    override fun isWrapperFor(iface: Class<*>): Boolean = false
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

class CustomOutboxStore : SovereignOpsAuditOutboxStore {
    override fun isDurable(): Boolean = true

    override suspend fun append(
        record: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsAuditOutboxRecord = record

    override suspend fun markReadyForDispatch(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
    ): SovereignOpsAuditOutboxRecord {
        throw UnsupportedOperationException("custom stub")
    }

    override suspend fun claimPending(
        claimedBy: String,
        limit: Int,
        now: Instant,
    ): List<SovereignOpsAuditOutboxRecord> = emptyList()

    override suspend fun markEmitted(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        expectedAttemptCount: Int,
        emittedAt: Instant,
    ): SovereignOpsAuditOutboxRecord {
        throw UnsupportedOperationException("custom stub")
    }

    override suspend fun markFailed(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        expectedAttemptCount: Int,
        errorCode: String,
        retryable: Boolean,
    ): SovereignOpsAuditOutboxRecord {
        throw UnsupportedOperationException("custom stub")
    }

    override suspend fun get(outboxId: String): SovereignOpsAuditOutboxRecord? = null
    override suspend fun findByEventKey(eventKey: String): SovereignOpsAuditOutboxRecord? = null
    override suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord> = emptyList()
    override suspend fun listByStatus(
        status: SovereignOpsAuditOutboxStatus,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord> = emptyList()
    override suspend fun listExpiredEmitting(
        now: Instant,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord> = emptyList()
}

class CustomAuditPayloadCodec : JdbcAuditPayloadCodec {
    override fun encode(plaintext: ByteArray): dev.tramai.persistence.jdbc.JdbcEncryptedAuditPayload =
        dev.tramai.persistence.jdbc.JdbcEncryptedAuditPayload(
            ciphertext = plaintext,
            keyId = "custom",
            algorithm = "CUSTOM",
            nonce = ByteArray(12),
            payloadDigest = "custom",
        )
    override fun decode(envelope: dev.tramai.persistence.jdbc.JdbcEncryptedAuditPayload): ByteArray =
        envelope.ciphertext
}

// ── Worker lease store stubs ───────────────────────────────────────

open class CustomLeaseStoreConfig {
    @Bean
    @Primary
    open fun customLeaseStore(): SovereignOpsWorkerLeaseStore = CustomLeaseStore()
}

class CustomLeaseStore : SovereignOpsWorkerLeaseStore {
    override suspend fun tryAcquire(
        leaseName: String,
        ownerId: String,
        now: Instant,
        leaseDuration: Duration,
    ) = throw UnsupportedOperationException("custom stub")

    override suspend fun heartbeat(
        leaseName: String,
        ownerId: String,
        now: Instant,
        leaseDuration: Duration,
    ) = throw UnsupportedOperationException("custom stub")

    override suspend fun release(
        leaseName: String,
        ownerId: String,
        now: Instant,
    ) = throw UnsupportedOperationException("custom stub")

    override suspend fun get(leaseName: String) =
        throw UnsupportedOperationException("custom stub")
}
