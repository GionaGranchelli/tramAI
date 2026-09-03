package dev.tramai.structured.benchmark

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiRange
import dev.tramai.structured.descriptor.StructuredTypeCompiler
import dev.tramai.structured.descriptor.StructuredValueValidator
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.reflect.typeOf

/**
 * B05 — structured validation. Validates a fully compliant runtime value
 * against its compiled descriptor (constraints, nested nullability, property
 * accessors). Mirrors the StructuredDescriptorArchitectureTest /
 * StructuredValueValidator fixture; descriptor + value built once, validation
 * timed (null result = valid, asserted each iteration).
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class StructuredValidationBenchmark {
    private val compiler =
        StructuredTypeCompiler(JsonMapper.builder().addModule(kotlinModule()).build())
    private val validator = StructuredValueValidator()

    @Test
    fun `B05 structured validation latency`() {
        val descriptor = compiler.compile(typeOf<BenchValidDoc>())
        val value = BenchValidDoc(title = "a valid document", score = 0.5, tags = listOf("a", "b"))
        assertNull(validator.validate(value, descriptor, ""))

        BenchmarkSupport.latency(
            operation = "B05-structured-validation",
            module = "tramai-structured",
            fixture = "StructuredValueValidator.validate(valid BenchValidDoc, compiled descriptor)",
        ) {
            assertNull(validator.validate(value, descriptor, ""))
        }
    }
}

private data class BenchValidDoc(
    @property:AiDescription("The document title")
    val title: String,
    @property:AiRange(min = 0.0, max = 1.0)
    val score: Double,
    val tags: List<String>,
)
