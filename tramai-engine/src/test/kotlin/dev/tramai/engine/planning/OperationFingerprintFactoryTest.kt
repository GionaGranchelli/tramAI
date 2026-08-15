package dev.tramai.engine.planning

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ToolDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class OperationFingerprintFactoryTest {

    @Test
    fun `creates deterministic fingerprints`() {
        val operation = operation("base")

        assertThat(factory.create(tools, operation)).isEqualTo(factory.create(tools, operation))
    }

    @Test
    fun `changes fingerprint when meaningful cache metadata changes`() {
        val base = factory.create(tools, operation("base"))

        assertThat(factory.create(listOf(ToolDefinition("other", "Payment", schema)), operation("base"))).isNotEqualTo(base)
        assertThat(factory.create(listOf(ToolDefinition("payment", "Payment", "{\"type\":\"string\"}")), operation("base"))).isNotEqualTo(base)
        assertThat(factory.create(tools, operation("timeout"))).isNotEqualTo(base)
        assertThat(factory.create(tools, operation("cacheable"))).isNotEqualTo(base)
        assertThat(factory.create(tools, operation("cachedDefault")))
            .isNotEqualTo(factory.create(tools, operation("ttl")))
        assertThat(factory.create(tools + ToolDefinition("second", "Second", "{}"), operation("base"))).isNotEqualTo(base)
    }

    @Test
    fun `does not include the prompt in the fingerprint`() {
        assertThat(factory.create(tools, operation("base"))).isEqualTo(factory.create(tools, operation("sameMetadataDifferentPrompt")))
    }

    @Test
    fun `is byte identical to the pre extraction algorithm`() {
        val operation = operation("base")

        assertThat(factory.create(tools, operation)).isEqualTo(legacyFingerprint(tools, operation))
    }

    private fun operation(name: String): Operation = FingerprintOperations::class.java.methods
        .single { it.name == name }
        .getAnnotation(Operation::class.java)

    /**
     * Snapshot of the ORIGINAL pre-extraction algorithm (TramaiEngine.kt private
     * operationFingerprint). Kept deliberately as a copy to prove the extracted
     * OperationFingerprintFactory is byte-identical — if you change the factory,
     * this snapshot must change with it (or be replaced by a pinned hash constant).
     */
    private fun legacyFingerprint(toolDefinitions: List<ToolDefinition>, operation: Operation): String {
        val canonical = buildString {
            append("tools_count=").append(toolDefinitions.size).append('\n')
            toolDefinitions.forEachIndexed { index, tool ->
                append("tool_").append(index).append("_name_len=").append(tool.name.length).append('\n')
                append(tool.name).append('\n')
                append("tool_").append(index).append("_schema_len=").append(tool.inputSchemaJson.length).append('\n')
                append(tool.inputSchemaJson).append('\n')
            }
            append("timeout_millis=").append(operation.timeoutMillis).append('\n')
            append("cacheable=").append(operation.cacheable).append('\n')
            append("cache_ttl_millis=").append(operation.cacheTtlMillis).append('\n')
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val factory = OperationFingerprintFactory()
        const val schema = "{\"type\":\"object\"}"
        val tools = listOf(ToolDefinition("payment", "Payment", schema))
    }

    @AiService
    private interface FingerprintOperations {
        @Operation(prompt = "Base", model = "test-model", timeoutMillis = 100) fun base(): String
        @Operation(prompt = "Different prompt", model = "test-model", timeoutMillis = 100) fun sameMetadataDifferentPrompt(): String
        @Operation(prompt = "Timeout", model = "test-model", timeoutMillis = 101) fun timeout(): String
        @Operation(prompt = "Cacheable", model = "test-model", timeoutMillis = 100, cacheable = true) fun cacheable(): String
        @Operation(prompt = "Cached default", model = "test-model", timeoutMillis = 100, cacheable = true) fun cachedDefault(): String
        @Operation(prompt = "Ttl", model = "test-model", timeoutMillis = 100, cacheable = true, cacheTtlMillis = 101) fun ttl(): String
    }
}
