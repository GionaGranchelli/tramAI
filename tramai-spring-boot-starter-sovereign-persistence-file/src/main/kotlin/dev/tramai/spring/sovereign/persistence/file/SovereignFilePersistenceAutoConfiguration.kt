package dev.tramai.spring.sovereign.persistence.file

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.persistence.file.FileBackedSovereignStores
import dev.tramai.persistence.file.FileBackedStoreConfiguration
import dev.tramai.persistence.file.FileStoreEncryptionConfiguration
import dev.tramai.persistence.file.FileStoreEncryptionKeyProvider
import dev.tramai.security.audit.AuditStore
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Spring Boot auto-configuration for encrypted file-backed sovereign persistence.
 *
 * Activated when `tramai.sovereign.persistence.type=file`.
 * Runs before [SovereignTramaiAutoConfiguration] so file-backed store beans
 * are registered before the base starter creates in-memory defaults. The
 * base starter's `@ConditionalOnMissingBean` then backs off, resulting in
 * file-backed stores in [SovereignTramaiRuntime].
 *
 * ## Store beans
 *
 * - [AuditStore] → `FileBackedSovereignStores.auditStore`
 * - [ApprovalStore] → `FileBackedSovereignStores.approvalStore`
 * - [ApprovalContinuationStore] → `FileBackedSovereignStores.approvalContinuationStore`
 * - [SuspendedInvocationStore] → `FileBackedSovereignStores.suspendedInvocationStore`
 *
 * All store beans are `@ConditionalOnMissingBean` — user-provided stores
 * always take precedence.
 *
 * ## Configuration example
 *
 * ```yaml
 * tramai:
 *   sovereign:
 *     persistence:
 *       type: file
 *       base-dir: ./data/tramai-sovereign
 *       encryption:
 *         key-env: TRAMAI_SOVEREIGN_STORE_KEY
 * ```
 *
 * ## Generate a test key
 *
 * ```
 * openssl rand -base64 32
 * ```
 *
 * ## Key requirements
 *
 * - Exactly one key source must be specified: `key-env` or `key-file` (not both, not neither)
 * - Key must be base64-encoded 256-bit AES key (decodes to 32 bytes)
 * - Plaintext keys in YAML are **not** supported
 * - Keys are never logged and never appear in exception messages
 */
@AutoConfiguration(before = [dev.tramai.spring.sovereign.SovereignTramaiAutoConfiguration::class])
@EnableConfigurationProperties(SovereignFilePersistenceProperties::class)
@ConditionalOnProperty(
    prefix = "tramai.sovereign.persistence",
    name = ["type"],
    havingValue = "file",
)
class SovereignFilePersistenceAutoConfiguration {

    /**
     * Creates the [FileBackedSovereignStores] bundle.
     *
     * This bean is `@ConditionalOnMissingBean` so users can provide their own
     * fully customised bundle.
     *
     * The `destroyMethod = "close"` ensures the exclusive file lock is released
     * when the Spring context closes.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun sovereignFileBackedStores(
        properties: SovereignFilePersistenceProperties,
    ): FileBackedSovereignStores {
        // ── Validate base directory ──
        val baseDir = properties.baseDir
            ?: throw IllegalStateException(
                "tramai-sovereign-file-persistence-missing-base-dir",
            )

        val rootDir = baseDir.toAbsolutePath().normalize()

        // ── Load encryption key ──
        val rawKey = SovereignStoreKeyLoader.load(properties)
        val secretKey: SecretKey = SecretKeySpec(rawKey, "AES")

        // ── Build file store configuration (secure defaults: verifyOnOpen=true) ──
        val config = FileBackedStoreConfiguration(
            rootDirectory = rootDir,
            encryption = FileStoreEncryptionConfiguration(
                activeKeyId = "default",
                keyProvider = FileStoreEncryptionKeyProvider { secretKey },
            ),
        )

        // ── Open stores (creates lock, manifest, validates) ──
        return FileBackedSovereignStores.open(config)
    }

    // ── Individual store beans (backed off from in-memory defaults) ──

    @Bean
    @ConditionalOnMissingBean
    fun auditStore(stores: FileBackedSovereignStores): AuditStore =
        stores.auditStore

    @Bean
    @ConditionalOnMissingBean
    fun approvalStore(stores: FileBackedSovereignStores): ApprovalStore =
        stores.approvalStore

    @Bean
    @ConditionalOnMissingBean
    fun approvalContinuationStore(
        stores: FileBackedSovereignStores,
    ): ApprovalContinuationStore = stores.approvalContinuationStore

    @Bean
    @ConditionalOnMissingBean
    fun suspendedInvocationStore(
        stores: FileBackedSovereignStores,
    ): SuspendedInvocationStore = stores.suspendedInvocationStore
}
