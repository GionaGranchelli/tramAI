package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialStore
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.persistence.jdbc.JdbcApprovalContinuationStore
import dev.tramai.persistence.jdbc.JdbcApprovalStore
import dev.tramai.persistence.jdbc.JdbcAuditPayloadCodec
import dev.tramai.persistence.jdbc.JdbcAuditStore
import dev.tramai.persistence.jdbc.JdbcContinuationArgumentsCodec
import dev.tramai.persistence.jdbc.JdbcReplayEnvelopeCodec
import dev.tramai.persistence.jdbc.JdbcSuspendedInvocationStore
import dev.tramai.security.audit.AuditStore
import dev.tramai.spring.sovereign.ops.lease.SovereignOpsWorkerLeaseStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for JDBC-backed sovereign persistence.
 *
 * Activated when `tramai.sovereign.persistence.type=jdbc` and a [DataSource]
 * is available.
 * Runs before [dev.tramai.spring.sovereign.SovereignTramaiAutoConfiguration]
 * so JDBC store beans are registered before the base starter creates in-memory
 * defaults. The base starter's `@ConditionalOnMissingBean` then backs off,
 * resulting in JDBC stores in [dev.tramai.sovereign.SovereignTramaiRuntime].
 *
 * ## Store beans
 * - [ApprovalStore] → [JdbcApprovalStore]
 * - [ApprovalContinuationStore] → [JdbcApprovalContinuationStore]
 * - [SuspendedInvocationStore] → [JdbcSuspendedInvocationStore]
 * - [AuditStore] → [JdbcAuditStore]
 * - [SovereignOpsAuditOutboxStore] → [JdbcSovereignOpsAuditOutboxStore]
 * - [SovereignOpsApprovalMutationStore] → [JdbcSovereignOpsApprovalMutationStore]
 *
 * All store beans are `@ConditionalOnMissingBean` — user-provided stores
 * always take precedence.
 *
 * ## Encryption codec beans
 * All payload codecs are `@ConditionalOnMissingBean` — user-provided codecs
 * always take precedence.
 *
 * The codec beans explicitly qualify their [SecretKey] injection with
 * `@Qualifier("sovereignJdbcEncryptionKey")` to avoid binding to an
 * unrelated `SecretKey` bean from another part of the application.
 *
 * ## Configuration example
 * ```yaml
 * tramai:
 *   sovereign:
 *     persistence:
 *       type: jdbc
 *       jdbc:
 *         claim-lease-duration: 5m
 *         max-claim-limit: 500
 *       encryption:
 *         key-env: TRAMAI_SOVEREIGN_STORE_KEY
 * ```
 *
 * ## Key requirements
 * - Exactly one key source must be specified: `key-env` or `key-file` (not both, not neither)
 * - Key must be base64-encoded 256-bit AES key (decodes to 32 bytes)
 * - Plaintext keys in YAML are **not** supported
 * - Keys are never logged and never appear in exception messages
 *
 * ## Fail-safe
 * If `type=jdbc` is configured but no [DataSource] is available, startup
 * fails with `tramai-sovereign-jdbc-persistence-missing-datasource`. The
 * base starter **must not** silently fall back to in-memory stores.
 */
@AutoConfiguration(before = [dev.tramai.spring.sovereign.SovereignTramaiAutoConfiguration::class])
@EnableConfigurationProperties(SovereignJdbcPersistenceProperties::class)
@ConditionalOnProperty(
    prefix = "tramai.sovereign.persistence",
    name = ["type"],
    havingValue = "jdbc",
)
class SovereignJdbcPersistenceAutoConfiguration {

    // ── Fail-fast: missing DataSource ─────────────────────────────────

    /**
     * Fail-fast guard: when `type=jdbc` is configured but no [DataSource]
     * bean exists, startup fails with `tramai-sovereign-jdbc-persistence-missing-datasource`.
     *
     * This is a `@ConditionalOnMissingBean` — it only runs when DataSource
     * is absent. When a DataSource is present, this method is skipped and
     * the store beans below use it directly.
     */
    @Bean
    @ConditionalOnMissingBean(DataSource::class)
    fun missingJdbcDataSourceFailure(): Nothing {
        throw IllegalStateException(
            "tramai-sovereign-jdbc-persistence-missing-datasource",
        )
    }

    // ── Encryption key ────────────────────────────────────────────────

    /**
     * Loads and validates the AES-256 encryption key from the configured
     * source (key-env or key-file). This is a @Bean so that key loading
     * failure (missing source, bad base64, wrong key size) causes a clear
     * startup failure without creating any stores.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["sovereignJdbcEncryptionKey"])
    fun sovereignJdbcEncryptionKey(
        properties: SovereignJdbcPersistenceProperties,
    ): SecretKey {
        val rawKey = SovereignJdbcKeyLoader.load(properties)
        return SecretKeySpec(rawKey, "AES")
    }

    // ── Default codec beans ───────────────────────────────────────────
    //
    // All codec beans qualify their SecretKey injection to avoid binding
    // to an unrelated SecretKey bean from another part of the application.

    @Bean
    @ConditionalOnMissingBean
    fun jdbcAuditPayloadCodec(
        @Qualifier("sovereignJdbcEncryptionKey") key: SecretKey,
        properties: SovereignJdbcPersistenceProperties,
    ): JdbcAuditPayloadCodec =
        DefaultJdbcAuditPayloadCodec(key, properties.encryption.keyId)

    @Bean
    @ConditionalOnMissingBean
    fun jdbcReplayEnvelopeCodec(
        @Qualifier("sovereignJdbcEncryptionKey") key: SecretKey,
        properties: SovereignJdbcPersistenceProperties,
    ): JdbcReplayEnvelopeCodec =
        DefaultJdbcSuspendedInvocationPayloadCodec(key, properties.encryption.keyId)

    @Bean
    @ConditionalOnMissingBean
    fun jdbcContinuationArgumentsCodec(
        @Qualifier("sovereignJdbcEncryptionKey") key: SecretKey,
        properties: SovereignJdbcPersistenceProperties,
    ): JdbcContinuationArgumentsCodec =
        DefaultJdbcApprovalContinuationPayloadCodec(key, properties.encryption.keyId)

    @Bean
    @ConditionalOnMissingBean
    fun jdbcOpsAuditOutboxPayloadCodec(
        @Qualifier("sovereignJdbcEncryptionKey") key: SecretKey,
        properties: SovereignJdbcPersistenceProperties,
    ): JdbcOpsAuditOutboxPayloadCodec =
        DefaultJdbcOpsAuditOutboxPayloadCodec(key, properties.encryption.keyId)

    // ── Store beans ───────────────────────────────────────────────────
    //
    // All store beans require a DataSource and back off via
    // @ConditionalOnMissingBean when a user-provided store exists.

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource::class)
    fun approvalStore(
        dataSource: DataSource,
    ): ApprovalStore = JdbcApprovalStore(dataSource)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource::class)
    fun approvalContinuationStore(
        dataSource: DataSource,
        argumentsCodec: JdbcContinuationArgumentsCodec,
    ): ApprovalContinuationStore = JdbcApprovalContinuationStore(dataSource, argumentsCodec)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource::class)
    fun suspendedInvocationStore(
        dataSource: DataSource,
        replayEnvelopeCodec: JdbcReplayEnvelopeCodec,
    ): SuspendedInvocationStore = JdbcSuspendedInvocationStore(dataSource, replayEnvelopeCodec)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource::class)
    fun auditStore(
        dataSource: DataSource,
        payloadCodec: JdbcAuditPayloadCodec,
    ): AuditStore = JdbcAuditStore(dataSource, payloadCodec)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource::class)
    fun sovereignOpsAuditOutboxStore(
        dataSource: DataSource,
        payloadCodec: JdbcOpsAuditOutboxPayloadCodec,
        properties: SovereignJdbcPersistenceProperties,
    ): SovereignOpsAuditOutboxStore {
        val jdbcConfig = properties.jdbc
        return JdbcSovereignOpsAuditOutboxStore(
            dataSource = dataSource,
            payloadCodec = payloadCodec,
            claimLeaseDuration = jdbcConfig.claimLeaseDuration,
            maxClaimLimit = jdbcConfig.maxClaimLimit,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource::class)
    fun sovereignOpsApprovalMutationStore(
        dataSource: DataSource,
        outboxPayloadCodec: JdbcOpsAuditOutboxPayloadCodec,
    ): SovereignOpsApprovalMutationStore =
        JdbcSovereignOpsApprovalMutationStore(dataSource, outboxPayloadCodec)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource::class)
    fun sovereignOpsApprovalRequestMutationStore(
        dataSource: DataSource,
        replayEnvelopeCodec: JdbcReplayEnvelopeCodec,
        continuationArgumentsCodec: JdbcContinuationArgumentsCodec,
        outboxPayloadCodec: JdbcOpsAuditOutboxPayloadCodec,
        @Qualifier("sovereignJdbcEncryptionKey") encryptionKey: SecretKey,
        properties: SovereignJdbcPersistenceProperties,
    ): SovereignOpsApprovalRequestMutationStore =
        JdbcSovereignOpsApprovalRequestMutationStore(
            dataSource = dataSource,
            replayEnvelopeCodec = replayEnvelopeCodec,
            continuationArgumentsCodec = continuationArgumentsCodec,
            outboxPayloadCodec = outboxPayloadCodec,
            encryptionKey = encryptionKey,
            encryptionKeyId = properties.encryption.keyId,
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource::class)
    fun sovereignOpsWorkerLeaseStore(
        dataSource: DataSource,
    ): SovereignOpsWorkerLeaseStore =
        JdbcSovereignOpsWorkerLeaseStore(dataSource)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource::class)
    fun approvalResumeCredentialStore(
        dataSource: DataSource,
        @Qualifier("sovereignJdbcEncryptionKey") key: SecretKey,
        properties: SovereignJdbcPersistenceProperties,
    ): ApprovalResumeCredentialStore =
        JdbcApprovalResumeCredentialStore(
            dataSourceProvider = { dataSource.connection },
            key = key,
            keyId = properties.encryption.keyId,
        )
}
