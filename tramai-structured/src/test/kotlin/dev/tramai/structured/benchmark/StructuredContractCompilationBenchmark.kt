package dev.tramai.structured.benchmark

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiRange
import dev.tramai.structured.descriptor.StructuredTypeCompiler
import dev.tramai.structured.descriptor.StructuredTypeDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.reflect.typeOf

/**
 * B04 — structured-contract compilation. Compiles a Kotlin data-class
 * contract (annotations, nested list, ranges) into a language-neutral
 * StructuredTypeDescriptor. Mirrors the StructuredContractFingerprintTest
 * fixture; the compiler is constructed once, compilation is timed.
 */
@EnabledIfSystemProperty(named = "tramai.benchmark", matches = "true")
class StructuredContractCompilationBenchmark {
    private val compiler =
        StructuredTypeCompiler(JsonMapper.builder().addModule(kotlinModule()).build())

    @Test
    fun `B04 structured-contract compilation latency`() {
        val probe = compiler.compile(typeOf<BenchContract>())
        assertEquals(3, (probe as StructuredTypeDescriptor.Object).properties.size)

        BenchmarkSupport.latency(
            operation = "B04-structured-contract-compilation",
            module = "tramai-structured",
            fixture =
                "StructuredTypeCompiler.compile<BenchContract>() " +
                    "(3 props: @AiDescription String, @AiRange Double, List<String>)",
        ) {
            val compiled = compiler.compile(typeOf<BenchContract>())
            assertEquals(3, (compiled as StructuredTypeDescriptor.Object).properties.size)
        }
    }
}

private data class BenchContract(
    @property:AiDescription("The document title")
    val title: String,
    @property:AiRange(min = 0.0, max = 1.0)
    val score: Double,
    val tags: List<String>,
)
