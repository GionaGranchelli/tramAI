package dev.tramai.spring.sovereign.ops

import dev.tramai.security.audit.AuditEngine
import dev.tramai.security.audit.AuditStore
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

class SovereignOpsAuditEmissionTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SovereignOpsAutoConfiguration::class.java),
        )

    // ── Audit emission tests ─────────────────────────────────────────

    @Test
    fun `denyApproval emits audit event when mutation succeeds`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val store = ctx.getBean(AuditStore::class.java)

                runBlocking {
                    ops.denyApproval("test-approval", "admin", "Administrative denial")
                }

                // Audit event should have been emitted
                val streamEvents = runBlocking {
                    store.readStream("sovereign-ops-approval:${sha256Hex("sovereign-ops-approval:test-approval")}")
                }
                assertThat(streamEvents).isNotEmpty
                val event = streamEvents.first()
                assertThat(event.actor).isEqualTo("admin")
                assertThat(event.enforcementPoint).isEqualTo("sovereign-ops.approval.deny")
                assertThat(event.decision).isEqualTo("DENIED")
                assertThat(event.reasonCode).isEqualTo("sovereign-ops-admin-denial")
                assertThat(event.metadata)
                    .containsKey("approvalIdDigest")
                    .containsKey("approvalStatus")
                    .containsKey("approvalVersion")
                    .containsKey("reasonDigest")
                    .containsKey("reasonLength")
            }
    }

    @Test
    fun `denyApproval audit event does not expose raw reason`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val store = ctx.getBean(AuditStore::class.java)

                runBlocking {
                    ops.denyApproval("test-approval", "admin", "Contains sensitive info: SSN-123-45-6789")
                }

                val streamEvents = runBlocking {
                    store.readStream("sovereign-ops-approval:${sha256Hex("sovereign-ops-approval:test-approval")}")
                }
                val event = streamEvents.first()

                // Raw reason must NOT appear anywhere in the event
                assertThat(event.metadata["reasonDigest"]).isNotEqualTo("Contains sensitive info: SSN-123-45-6789")
                assertThat(event.reasonCode).isNotEqualTo("Contains sensitive info: SSN-123-45-6789")
                assertThat(event.metadata).doesNotContainKey("reason")

                // Only digest + length are stored
                assertThat(event.metadata).containsKey("reasonDigest")
                assertThat(event.metadata).containsKey("reasonLength")
            }
    }

    @Test
    fun `denyApproval audit event does not expose tokens or replay envelope`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val store = ctx.getBean(AuditStore::class.java)

                runBlocking {
                    ops.denyApproval("test-approval", "admin", "Test reason")
                }

                val streamEvents = runBlocking {
                    store.readStream("sovereign-ops-approval:${sha256Hex("sovereign-ops-approval:test-approval")}")
                }
                val event = streamEvents.first()
                val keys = event.metadata.keys

                assertThat(keys).doesNotContain("approvalToken")
                assertThat(keys).doesNotContain("resumeToken")
                assertThat(keys).doesNotContain("approvalTokenDigest")
                assertThat(keys).doesNotContain("replayEnvelope")
                assertThat(keys).doesNotContain("toolArguments")
                assertThat(keys).doesNotContain("prompt")
                assertThat(keys).doesNotContain("response")
                assertThat(keys).doesNotContain("reason")
            }
    }

    @Test
    fun `denyApproval uses digested approval id in audit stream`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)
                val store = ctx.getBean(AuditStore::class.java)

                runBlocking {
                    ops.denyApproval("test-approval", "admin", "Test reason")
                }

                // The raw approvalId should NOT appear as a stream ID
                val rawIdStream = runBlocking {
                    store.readStream("sovereign-ops-approval:test-approval")
                }
                assertThat(rawIdStream).isEmpty()

                // The stream ID should use the digested form
                val digestIdStream = runBlocking {
                    val digest = sha256Hex("sovereign-ops-approval:test-approval")
                    store.readStream("sovereign-ops-approval:$digest")
                }
                assertThat(digestIdStream).isNotEmpty
            }
    }

    @Test
    fun `denyApproval does not emit audit event when mutations disabled`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)

                val ex = runCatching {
                    runBlocking {
                        ops.denyApproval("test-approval", "admin", "Should not work")
                    }
                }.exceptionOrNull()

                assertThat(ex)
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("tramai-sovereign-ops-mutations-disabled")

                // No audit event should have been emitted
                val store = ctx.getBean(AuditStore::class.java)
                val streamEvents = runBlocking {
                    val digest = sha256Hex("sovereign-ops-approval:test-approval")
                    store.readStream("sovereign-ops-approval:$digest")
                }
                assertThat(streamEvents).isEmpty()
            }
    }

    @Test
    fun `denyApproval does not emit audit event when validation fails`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                TestAuditEngineConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)

                val ex = runCatching {
                    runBlocking {
                        ops.denyApproval("non-existent-approval", "admin", "Test reason")
                    }
                }.exceptionOrNull()

                assertThat(ex)
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("tramai-sovereign-ops-invalid-approval-id")

                // No audit event should have been emitted
                val store = ctx.getBean(AuditStore::class.java)
                val streamEvents = runBlocking {
                    val digest = sha256Hex("sovereign-ops-approval:non-existent-approval")
                    store.readStream("sovereign-ops-approval:$digest")
                }
                assertThat(streamEvents).isEmpty()
            }
    }

    @Test
    fun `denyApproval propagates CancellationException from audit emitter`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                FailingAuditEmitterConfig::class.java,
            )
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                val ops = ctx.getBean(SovereignApprovalOperations::class.java)

                val ex = runCatching {
                    runBlocking {
                        ops.denyApproval("test-approval", "admin", "Test reason")
                    }
                }.exceptionOrNull()

                // CancellationException from the custom emitter should propagate
                assertThat(ex)
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("tramai-sovereign-ops-audit-emission-failed")
            }
    }

    @Test
    fun `noop audit emitter allows startup without audit engine`() {
        contextRunner
            .withUserConfiguration(MinimalStoreConfig::class.java)
            .withPropertyValues("tramai.sovereign.ops.mutations-enabled=true")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SovereignApprovalOperations::class.java)
                assertThat(ctx).hasSingleBean(SovereignOpsAuditEmitter::class.java)

                val emitter = ctx.getBean(SovereignOpsAuditEmitter::class.java)
                assertThat(emitter).isInstanceOf(NoopSovereignOpsAuditEmitter::class.java)
            }
    }

    @Test
    fun `custom SovereignOpsAuditEmitter bean is not overridden`() {
        contextRunner
            .withUserConfiguration(
                MinimalStoreConfig::class.java,
                CustomAuditEmitterConfig::class.java,
            )
            .run { ctx ->
                val emitter = ctx.getBean(SovereignOpsAuditEmitter::class.java)
                assertThat(emitter).isInstanceOf(CustomTestAuditEmitter::class.java)
            }
    }

    // ── Helper: SHA-256 hex ──────────────────────────────────────────

    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

// ── Test configuration classes ────────────────────────────────────

class TestAuditEngineConfig {
    @Bean
    open fun testAuditEngine(auditStore: AuditStore): AuditEngine =
        AuditEngine(auditStore)
}

class FailingAuditEmitterConfig {
    @Bean
    open fun failingEmitter(): SovereignOpsAuditEmitter = FailingTestAuditEmitter()
}

class FailingTestAuditEmitter : SovereignOpsAuditEmitter {
    override suspend fun approvalDenied(
        approvalId: String,
        actor: String,
        reason: String,
        approvalStatus: String,
        approvalVersion: Long?,
        workflowRunId: String?,
        correlationId: String?,
    ) {
        throw RuntimeException("Audit engine unavailable")
    }
}

class CustomAuditEmitterConfig {
    @Bean
    @Primary
    open fun customAuditEmitter(): SovereignOpsAuditEmitter = CustomTestAuditEmitter()
}

class CustomTestAuditEmitter : SovereignOpsAuditEmitter {
    override suspend fun approvalDenied(
        approvalId: String,
        actor: String,
        reason: String,
        approvalStatus: String,
        approvalVersion: Long?,
        workflowRunId: String?,
        correlationId: String?,
    ) {
        // Custom implementation — no-op for test
    }
}
