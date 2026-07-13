package dev.tramai.structured

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaArrayListResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaClaimResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaComparisonResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaDecision
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaEnvelope
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaGenericCollectionResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaGetterOnlyResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaMapResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaNestedCollectionResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaNestedPrimitiveResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaPrimitiveResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaRecursiveNode
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaScoredResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaSetResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaSetterParamAnnotationResult
import dev.tramai.structured.JavaBeanStructuredOutputFixtures.JavaWriteOnlyResult
import dev.tramai.core.structured.StructuredOutputResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test
import kotlin.reflect.typeOf

/**
 * Tests for JavaBean structured-output schema generation and validation.
 *
 * These tests verify the Jackson-introspection path for JavaBean DTOs.
 * Kotlin-only behaviour is tested in [JacksonStructuredOutputHandlerTest].
 */
class JavaBeanStructuredOutputHandlerTest {

    private val handler = JacksonStructuredOutputHandler()
    private val mapper: ObjectMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .build()

    // ---------------------------------------------------------------
    // Schema generation
    // ---------------------------------------------------------------

    @Test
    fun `schema contains JavaBean properties`() {
        val contract = handler.createContract(typeOf<JavaScoredResult>())

        assertThat(contract.schemaJson)
            .contains("\"status\"")
            .contains("\"confidence\"")
            .contains("\"reasons\"")
            .doesNotContain("\"properties\" : { }")
    }

    @Test
    fun `schema has deterministic alphabetical ordering`() {
        val root = mapper.readTree(handler.createContract(typeOf<JavaScoredResult>()).schemaJson)

        val propertyNames = root["properties"].fieldNames().asSequence().toList()
        assertThat(propertyNames).containsExactly("confidence", "reasons", "status")
    }

    @Test
    fun `scalar type mappings`() {
        val root = mapper.readTree(handler.createContract(typeOf<JavaPrimitiveResult>()).schemaJson)
        val props = root["properties"]

        assertThat(props["count"]["type"].asText()).isEqualTo("integer")
        assertThat(props["active"]["type"].asText()).isEqualTo("boolean")
        assertThat(props["timestamp"]["type"].asText()).isEqualTo("integer")
        assertThat(props["score"]["type"].asText()).isEqualTo("number")
    }

    @Test
    fun `generic collection produces array schema with items`() {
        val root = mapper.readTree(handler.createContract(typeOf<JavaGenericCollectionResult>()).schemaJson)
        val tags = root["properties"]["tags"]

        assertThat(tags["type"].asText()).isEqualTo("array")
        assertThat(tags["items"]["type"].asText()).isEqualTo("string")
    }

    @Test
    fun `Java field annotations contribute to schema`() {
        val root = mapper.readTree(handler.createContract(typeOf<JavaScoredResult>()).schemaJson)
        val props = root["properties"]

        assertThat(props["status"]["description"].asText()).isEqualTo("Evaluation status")
        assertThat(props["confidence"]["minimum"].asDouble()).isEqualTo(0.0)
        assertThat(props["confidence"]["maximum"].asDouble()).isEqualTo(1.0)
        assertThat(props["reasons"]["minItems"].asInt()).isEqualTo(1)
    }

    @Test
    fun `setter parameter annotations contribute to schema`() {
        val root = mapper.readTree(handler.createContract(typeOf<JavaSetterParamAnnotationResult>()).schemaJson)
        val props = root["properties"]

        assertThat(props["label"]["description"].asText()).isEqualTo("Display label")
        assertThat(props["score"]["minimum"].asDouble()).isEqualTo(0.0)
        assertThat(props["score"]["maximum"].asDouble()).isEqualTo(100.0)
    }

    @Test
    fun `nested JavaBeans produce nested object schema`() {
        val root = mapper.readTree(handler.createContract(typeOf<JavaClaimResult>()).schemaJson)
        val decision = root["properties"]["decision"]

        assertThat(decision["type"].asText()).isEqualTo("object")
        assertThat(decision["properties"]["outcome"]["type"].asText()).isEqualTo("string")
    }

    @Test
    fun `all discovered JavaBean properties are required`() {
        val root = mapper.readTree(handler.createContract(typeOf<JavaScoredResult>()).schemaJson)

        assertThat(root["required"].map { it.asText() })
            .containsExactly("confidence", "reasons", "status")
    }

    @Test
    fun `getter-only calculated properties are excluded`() {
        val root = mapper.readTree(handler.createContract(typeOf<JavaGetterOnlyResult>()).schemaJson)
        val props = root["properties"]

        assertThat(props["firstName"]).isNotNull
        assertThat(props["lastName"]).isNotNull
        assertThat(props["fullName"]).isNull()
    }

    @Test
    fun `setter-only write-only properties are excluded`() {
        val contract = handler.createContract(typeOf<JavaWriteOnlyResult>())

        assertThat(contract.schemaJson)
            .contains("something")
            .doesNotContain("secret")
    }

    @Test
    fun `unsupported map type throws appropriate error`() {
        assertThatThrownBy {
            handler.createContract(typeOf<JavaMapResult>())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unsupported structured output type")
    }

    @Test
    fun `self-referencing recursive type fails with controlled error`() {
        assertThatThrownBy {
            handler.createContract(typeOf<JavaRecursiveNode>())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Recursive JavaBean structured output type is unsupported")
    }

    @Test
    fun `repeated sibling bean type both get complete schemas`() {
        val root = mapper.readTree(handler.createContract(typeOf<JavaComparisonResult>()).schemaJson)
        val props = root["properties"]

        // Both siblings must have complete object schemas (not truncated to {"type":"object"})
        assertThat(props["primary"]["properties"]["outcome"]).isNotNull
        assertThat(props["secondary"]["properties"]["outcome"]).isNotNull
    }

    @Test
    fun `concrete ArrayList type is recognized as array`() {
        val root = mapper.readTree(handler.createContract(typeOf<JavaArrayListResult>()).schemaJson)
        val values = root["properties"]["values"]

        assertThat(values["type"].asText()).isEqualTo("array")
        assertThat(values["items"]["type"].asText()).isEqualTo("string")
    }

    @Test
    fun `Java Set property is recognized as array`() {
        val root = mapper.readTree(handler.createContract(typeOf<JavaSetResult>()).schemaJson)
        val decisions = root["properties"]["decisions"]

        assertThat(decisions["type"].asText()).isEqualTo("array")
        assertThat(decisions["items"]["type"].asText()).isEqualTo("object")
    }

    @Test
    fun `nested collection produces nested array schema`() {
        val root = mapper.readTree(handler.createContract(typeOf<JavaNestedCollectionResult>()).schemaJson)
        val groups = root["properties"]["decisionGroups"]

        assertThat(groups["type"].asText()).isEqualTo("array")
        assertThat(groups["items"]["type"].asText()).isEqualTo("array")
        assertThat(groups["items"]["items"]["type"].asText()).isEqualTo("object")
    }

    // ---------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------

    @Test
    fun `valid JavaBean succeeds validation and populates field values`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "status": "approved",
                  "confidence": 0.85,
                  "reasons": ["good score"]
                }
            """.trimIndent(),
            targetType = typeOf<JavaScoredResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
        val success = result as StructuredOutputResult.Success
        val value = success.value as JavaScoredResult
        assertThat(value.status).isEqualTo("approved")
        assertThat(value.confidence).isEqualTo(0.85)
        assertThat(value.reasons).containsExactly("good score")
    }

    @Test
    fun `null required string field fails validation`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "status": null,
                  "confidence": 0.85,
                  "reasons": ["good"]
                }
            """.trimIndent(),
            targetType = typeOf<JavaScoredResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("status")
    }

    @Test
    fun `missing primitive required field fails validation`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "status": "approved",
                  "reasons": ["valid"]
                }
            """.trimIndent(),
            targetType = typeOf<JavaScoredResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("confidence")
    }

    @Test
    fun `missing boolean required field fails validation`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "count": 5,
                  "timestamp": 1000,
                  "score": 1.0
                }
            """.trimIndent(),
            targetType = typeOf<JavaPrimitiveResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("active")
    }

    @Test
    fun `missing int required field fails validation`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "active": true,
                  "timestamp": 1000,
                  "score": 1.0
                }
            """.trimIndent(),
            targetType = typeOf<JavaPrimitiveResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("count")
    }

    @Test
    fun `missing nested primitive required field fails validation`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "inner": {
                    "active": true,
                    "timestamp": 1000,
                    "score": 1.0
                  }
                }
            """.trimIndent(),
            targetType = typeOf<JavaNestedPrimitiveResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("count")
    }

    @Test
    fun `out of range numeric value fails validation`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "status": "approved",
                  "confidence": 1.5,
                  "reasons": ["good"]
                }
            """.trimIndent(),
            targetType = typeOf<JavaScoredResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("confidence")
        assertThat(failure.feedbackMessage).contains("failed validation")
    }

    @Test
    fun `undersized collection fails validation`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "status": "approved",
                  "confidence": 0.5,
                  "reasons": []
                }
            """.trimIndent(),
            targetType = typeOf<JavaScoredResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("reasons")
    }

    @Test
    fun `nested JavaBean violation includes parent path`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "decision": {
                    "outcome": null
                  }
                }
            """.trimIndent(),
            targetType = typeOf<JavaClaimResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("decision")
    }

    @Test
    fun `null list element fails validation`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "status": "a",
                  "confidence": 0.5,
                  "reasons": ["one", null, "three"]
                }
            """.trimIndent(),
            targetType = typeOf<JavaScoredResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("reasons")
    }

    @Test
    fun `validation feedback is suitable for repair`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "status": "approved",
                  "confidence": 1.5,
                  "reasons": ["good"]
                }
            """.trimIndent(),
            targetType = typeOf<JavaScoredResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.feedbackMessage).contains("corrected JSON")
    }

    // ---------------------------------------------------------------
    // Root collection, mixed types, and advanced validation
    // ---------------------------------------------------------------

    @Test
    fun `missing primitive in root List of JavaBeans fails`() {
        val result = handler.analyze(
            rawResponse = """
                [
                  {
                    "status": "approved",
                    "reasons": ["valid"]
                  }
                ]
            """.trimIndent(),
            targetType = typeOf<List<JavaScoredResult>>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("confidence")
    }

    @Test
    fun `null nested property inside root List of JavaBeans fails`() {
        val result = handler.analyze(
            rawResponse = """
                [
                  {
                    "decision": {
                      "outcome": null
                    }
                  }
                ]
            """.trimIndent(),
            targetType = typeOf<List<JavaClaimResult>>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("outcome")
    }

    @Test
    fun `missing primitive inside nested collection List of List fails`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "decisionGroups": [
                    [
                      {
                        "outcome": "ok"
                      },
                      {}
                    ]
                  ]
                }
            """.trimIndent(),
            targetType = typeOf<JavaNestedCollectionResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("outcome")
    }

    @Test
    fun `nested generic envelope preserves type bindings during validation`() {
        // Missing nested primitive inside payload.value.count should fail
        val result = handler.analyze(
            rawResponse = """
                {
                  "payload": {
                    "value": {
                      "active": true,
                      "timestamp": 1000,
                      "score": 1.0
                    }
                  }
                }
            """.trimIndent(),
            targetType = typeOf<JavaBeanStructuredOutputFixtures.JavaEnvelopeHolder>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("count")
        assertThat(failure.errorSummary).contains("payload")
    }

    @Test
    fun `custom Map subclass is rejected as unsupported`() {
        assertThatThrownBy {
            handler.createContract(typeOf<JavaBeanStructuredOutputFixtures.JavaMapSubclassResult>())
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unsupported structured output type")
    }

    @Test
    fun `missing primitive in JavaBean nested inside Kotlin data class fails`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "bean": {
                    "status": "ok",
                    "reasons": ["good"]
                  }
                }
            """.trimIndent(),
            targetType = typeOf<KotlinWrapsJavaBean>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Failure::class.java)
        val failure = result as StructuredOutputResult.Failure
        assertThat(failure.errorSummary).contains("confidence")
    }

    @Test
    fun `valid root List of JavaBeans with all required fields succeeds`() {
        val result = handler.analyze(
            rawResponse = """
                [
                  {
                    "status": "ok",
                    "confidence": 0.9,
                    "reasons": ["a"]
                  }
                ]
            """.trimIndent(),
            targetType = typeOf<List<JavaScoredResult>>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
    }

    @Test
    fun `valid nested collection with List of List of JavaBeans succeeds`() {
        val result = handler.analyze(
            rawResponse = """
                {
                  "decisionGroups": [
                    [
                      {
                        "outcome": "approved"
                      }
                    ]
                  ]
                }
            """.trimIndent(),
            targetType = typeOf<JavaNestedCollectionResult>(),
        )

        assertThat(result).isInstanceOf(StructuredOutputResult.Success::class.java)
    }
}

/**
 * Kotlin data class wrapping a JavaBean — used to test mixed Kotlin/JavaBean
 * shape validation (primitive enforcement through Kotlin wrapper).
 */
private data class KotlinWrapsJavaBean(
    val bean: JavaBeanStructuredOutputFixtures.JavaScoredResult,
)
